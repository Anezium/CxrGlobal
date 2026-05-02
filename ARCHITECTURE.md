# Architecture

CxrGlobal の内部設計。利用方法は [README.md](README.md)。

このドキュメントは「なぜこの形なのか」「どこを触れば拡張できるか」「将来何が起きたら見直すべきか」を残しておくためのもの。

## 1. なぜこのライブラリが存在するか

### 公式 CXR-L SDK の制約

`com.rokid.cxr:client-l:1.0.1` の AAR を逆コンパイルしたところ、SDK は **中国版 Hi Rokid (`com.rokid.sprite.aiapp`) のパッケージ名を 2 箇所でハードコード**していることが分かった:

- `AuthorizationHelper.requestAuthorization` が `Intent.setComponent(ComponentName("com.rokid.sprite.aiapp", "...AuthorizationActivity"))` で **明示的に中国版**を指す。呼び出し側で上書き不可能。
- `ExternalAppClient`(`CXRLink.connect` の内部実装) が `Intent("...MEDIA_STREAM_SERVICE").setPackage("com.rokid.sprite.aiapp")` で `bindService`。同じく中国版固定。

このためグローバル版環境 (`com.rokid.sprite.global.aiapp` のみインストール) では SDK の `CXRLink` / `AuthorizationHelper` は両方とも何もできない。中国版 APK は公式 CDN から入手可能だが初回利用時に中国アカウント作成が必要で、グローバルユーザーが現実的に使う経路ではない。

### グローバル版で AIDL 経路がそのまま使える

幸い、グローバル版アプリも以下を export している:

- 同 Action `com.rokid.sprite.aiapp.externalapp.AUTHORIZATION` の AuthorizationActivity
- 同 Action `com.rokid.sprite.aiapp.externalapp.MEDIA_STREAM_SERVICE` の Service
- AIDL サーフェス (`IMediaStreamService` 等) は完全に共通

確認コマンド:

```bash
adb shell dumpsys package com.rokid.sprite.global.aiapp | grep -iE 'auth|MEDIA_STREAM'
```

つまり SDK 側で書き直しが必要なのは **2 箇所** (auth Intent と service Intent の `setPackage`) のみ。それ以外の View / Audio / Photo / CustomCMD / アプリ制御 のロジックはグラス側 Hi Rokid Service (AIDL の実装側) に存在し、AIDL 越しに **そのまま呼べる**。

戦略:

- 自前で 2 箇所だけ書き直す (`AuthorizationHelper.requestAuthorization` 相当 + `bindService` 相当)
- AIDL stub クラスは AAR から借用 (`IMediaStreamService.Stub.asInterface(binder)` 等)
- 各 AIDL メソッドへの 1 行ラッパーで API を再現

### 公式 SDK の "セッション設定" は意図的に省いた

SDK には `CXRLink.configCXRSession(CxrDefs.CXRSession)` という API があり、利用前に `CUSTOMVIEW` / `CUSTOMAPP` などのセッション種別を宣言させる。バイトコードを追ったところ、これは **クライアント側のローカルガードフィールドを立てるだけで AIDL 越しに Service には何も伝わらない** ことが分かった (`takePhoto` 等の冒頭でセッション種別の整合性をクライアント内で検証する用途)。

このため本 wrapper では session 概念を持たず、機能を直接呼べるようにしている。仮に session を立てても Hi Rokid Service 側の挙動は変わらない。

### スタンス

- 本家 SDK の `CXRLink` / `AuthorizationHelper` は **使わない** (内部で中国版を見るため)
- 本家 SDK の AIDL クラス (`IMediaStreamService` / `ICustomViewCallback` 等) は **借用する** (`build.gradle.kts` で `implementation` 依存)
- 利用側コードからは `com.rokid.*` を見せない (公式 SDK の存在を意識しなくて済む API になっている)

### 将来の見直しタイミング

以下のいずれかが起きたら本ライブラリの存在意義を再検討する:

- 本家 SDK がターゲットパッケージ設定可能になる (例: `CXRLink(context, packageName = "...")` のような API が追加される) → 本家に戻す
- Rokid 公式のグローバル向け SDK (`client-l-global` 等) が出る → 本家に戻す
- グローバル版アプリが action 名 / AIDL を変更する → バイパス前提が崩れるので追従改修

特に最後については Hi Rokid アプリのアップデート後に動作確認を忘れないこと。`adb shell dumpsys package com.rokid.sprite.global.aiapp` で action 名の有無を再確認するのが手早い。

## 2. 配布形態 (composite build)

利用側は Gradle composite build (`includeBuild`) で取り込む。`maven-publish` には現状対応していない。具体的な記述は [README.md の "インストール"](README.md#インストール-gradle-composite-build) 参照。

設計上のポイント:

- **なぜ composite build か**: 利用側プロジェクトと並行して wrapper を編集することが多いため、publish 不要・編集即反映で動く形が便利。
- **なぜ `dependencySubstitution` が必要か**: `includeBuild` だけだと CLI ビルドは通るが Android Studio のインデックスが座標を解決できず "Unresolved reference" エラーが出る。`dependencySubstitution` で座標 → プロジェクトの対応を明示することで IDE が解決できるようになる。
- **将来 Maven 公開する場合**: `lib/build.gradle.kts` に `maven-publish` プラグインを足し、`publishToMavenLocal` で `~/.m2/repository` に publish。利用側は `mavenLocal()` を repository に追加すれば座標 (`com.example.cxrglobal:lib:0.1.0-SNAPSHOT`) で参照できる。

## 3. ファイル構成

```
lib/src/main/java/com/example/cxrglobal/
├── CxrGlobal.kt              定数 (パッケージ名 / Action / 認可結果コード)
├── AuthResult.kt             sealed class: AuthSuccess(token) / AuthFail / AuthCancel
├── AuthorizationHelper.kt    object: requestAuthorization / parseAuthorizationResult / isHiRokidInstalled
├── CXRGlobalLink.kt          メインクラス。bindService 管理 + AIDL メソッドのラッパー一式
└── callbacks/
    ├── ICXRLinkCbk.kt        サービスバインド/グラス BT 接続
    ├── ICustomViewCbk.kt     CustomView lifecycle
    ├── IAudioStreamCbk.kt    音声ストリーム
    ├── IImageStreamCbk.kt    写真結果
    ├── ICustomCmdCbk.kt      カスタムコマンド受信
    ├── IGlassAppCbk.kt       グラス側アプリ install/start/stop/uninstall 結果
    └── IAiEventCbk.kt        AI key イベント / アプリ resume 変化
```

## 4. API 設計

### 利用側は `com.rokid.*` を import しない

公式 SDK の callback 型 (`ICXRLinkCbk` 等) は AAR の `com.rokid.cxr.link.callbacks.*` に存在するが、それを露出すると利用側に「本家 SDK の存在」が見えてしまう。代わりに **同名の自前 interface を `com.example.cxrglobal.callbacks.*` に定義**し、wrapper 内部で AIDL Stub から自前 interface へ転送するアダプタを置いている (`CXRGlobalLink.kt` の `customViewStub` 等)。

利用側は `com.example.cxrglobal.*` だけ import すれば済む。本家 SDK は `implementation` 依存 (transitive 非公開)。

### AIDL メソッドは 1:1 で薄くラップ

機能ロジックはグラス側 Service の中にあるため wrapper 側で計算は一切しない。各メソッドは:

1. サービス参照が `null` でないか確認
2. AIDL 呼び出し
3. `RemoteException` 等の例外を握り潰してログだけ吐く

の 3 段で、`tryCall { service?.method() }` パターンに集約している。

### コールバックは connect 時に一括 register

`connect(token)` で `bindService` し、`onServiceConnected` 内で **全コールバック** (`registerDeviceStatusCallback` / `registerCustomViewCallback` 等) を一括 register する。利用側は `setCustomViewCbk()` 等で wrapper にリスナを渡すだけで、AIDL の register / unregister タイミングを意識しなくて済む。

`disconnect()` で逆にすべて unregister + unbind する。`Activity.onDestroy` で呼ぶ想定。

### スレッディング

AIDL コールバックは Binder スレッドで届く。wrapper はスレッド切り替えしない (透過的に転送する)。利用側で UI を触る場合は自分で `runOnUiThread` 等する。`CXRGlobalLink` のフィールド (`glassConnected` 等) は `@Volatile` で保護しているがそれ以上の同期はない。

## 5. 機能追加のしかた

### AIDL 側に既にあるメソッドを露出するだけ

`IMediaStreamService` に既存のメソッドを使うだけなら `CXRGlobalLink.kt` に 1 行追加する。例:

```kotlin
fun customViewClose(): Boolean = tryCall { service?.closeCustomView() ?: false } ?: false
```

### 新しい callback 種別を追加する

1. `lib/src/main/java/com/example/cxrglobal/callbacks/IFooCbk.kt` に自前の interface を定義 (引数は AIDL に揃える)
2. `CXRGlobalLink.kt` に:
   - リスナ保持フィールド: `private var fooCbk: IFooCbk? = null`
   - AIDL Stub アダプタ: `private val fooStub = object : IFooCallback.Stub() { ... fooCbk?.onFoo(...) }`
   - setter: `fun setFooCbk(cbk: IFooCbk?) { fooCbk = cbk }`
   - `tryRegisterAllCallbacks` / `tryUnregisterAllCallbacks` に登録/解除を 1 行追加

これだけで OK。AIDL Stub アダプタを噛ませる理由は前述 (`com.rokid.*` を利用側に見せないため)。

## 6. 既知の制限 / トレードオフ

- **`IGlassAppCallback` がシングルトン**: AIDL 上では各メソッド (install / uninstall / queryInstalled 等) 呼出毎にコールバックを渡す API だが、本 wrapper は `setGlassAppCbk()` で 1 つだけ登録するモデルにしている。複数操作を並行で実行して結果を区別したい用途には不足。
- **認可結果コードはマジックナンバー**: `parseAuthorizationResult` の result code (`2001` / `2002` / `2003`) はバイトコード逆解析で得た値。Rokid 公式仕様書には載っていないので、本家アプリ側で変更されたら壊れる。
- **`IAudioStreamCallback.onAudioReceived(byte[], int, int)` の引数解釈**: AIDL の宣言 `(byte[] data, int a, int b)` で `a` `b` の意味は docs に明記されていない。公式サンプル (`PhotoUsageViewModel` 等) が `(offset, length)` として扱っているのに従い、本 wrapper も同じ解釈で `IAudioStreamCbk.onAudioReceived(data, offset, length)` を提供している。
- **legacy `onActivityResult` 依存**: `requestAuthorization` は `startActivityForResult` を使う。Activity Result API (`registerForActivityResult`) には未移行。コンパイラ警告は既知。Hi Rokid 側がこの形で結果を返すため、利用側 Activity も `onActivityResult` で受ける必要がある。

## 7. デバッグ

ログタグは `CXRGlobal`。

```bash
adb logcat -d -s CXRGlobal:*
```

で wrapper 内部のログだけ抽出できる。利用側で動かない時はまずこれを見る。

AIDL 呼び出しは `tryCall` で例外を握り潰すため、エラーは握り潰し時の `Log.w` 出力にしか出ない。見落とさないよう必ずログを吐く設計にしている。
