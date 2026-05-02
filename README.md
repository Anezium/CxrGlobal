# CxrGlobal

Rokid Glasses 用 CXR-L (CXR の S/M/L tier のうち L) を **グローバル版 Hi Rokid アプリ**経由で使うための薄いラッパーライブラリ。

## 背景

スマホ側の Hi Rokid アプリには地域別に 2 つの配布があり、パッケージ名が異なる:

- **グローバル版**: `com.rokid.sprite.global.aiapp` — グローバルユーザー向け。本ライブラリの対象。
- **中国版**: `com.rokid.sprite.aiapp` — 中国国内向け。初回利用時に中国アカウント作成が必要。

公式 CXR-L SDK (`com.rokid.cxr:client-l`) は **中国版にパッケージ名がハードコード**されていて、グローバル版環境ではそのままでは動かない。本ライブラリは公式 SDK の上位 API を使わず、より低レイヤーで Hi Rokid アプリと直接通信することでグローバル版に対応している。利用側からは公式 SDK 互換の薄い API として使える。詳細 (バイパスの仕組み / 内部設計) は [ARCHITECTURE.md](ARCHITECTURE.md)。

## 提供する機能

公式 CXR-L SDK (`com.rokid.cxr.link.CXRLink`) と **同じクラス名 / メソッド名 / セッション概念 / コールバック setter** を持つ。利用側は本家 SDK と同じ感覚で書ける (詳細な API シェイプは [ARCHITECTURE.md](ARCHITECTURE.md))。

- 認可フロー: グローバル版 Hi Rokid の AuthorizationActivity を呼び出し → token 取得 (`AuthorizationHelper`)
- AIDL バインディング: `IMediaStreamService` への接続 / 切断 (`CXRLink#connect` / `disconnect`)
- セッション管理: `configCXRSession(CxrDefs.CXRSession(...))` で `CUSTOMVIEW` / `CUSTOMAPP` を宣言
- CustomView: `customViewOpen` / `customViewUpdate` / `customViewClose` / `customViewSetIcons` / 状態取得
- Audio stream: PCM 16 kHz / mono / 16-bit リアルタイムストリーム (`startAudioStream`)
- Photo: `takePhoto`
- CustomCMD: `sendCustomCmd`
- グラス側アプリ操作: `appUploadAndInstall` / `appUninstall` / `appStart` / `appStop` / `appIsInstalled` (対象 package は session 由来)
- AI Assist イベント: `ICXRLinkCbk.onGlassAiAssistStart` / `onGlassAiAssistStop`

## 動作要件

| カテゴリ        | 必要条件                                                        | 動作確認済み                                           |
| --------------- | --------------------------------------------------------------- | ------------------------------------------------------ |
| スマホ          | Android (minSdk 31 / compileSdk 36)                             | Google Pixel 8 / Android 16 (SDK 36)                   |
| グラス          | ペアリング済みであること                                        | Rokid Glasses / YodaOS SPRITE 1.18.007-20260427-150201 |
| Hi Rokid アプリ | グローバル版 (`com.rokid.sprite.global.aiapp`) インストール済み | G1.5.9.0408 (versionCode 10050009)                     |

## オリジナルSDK

本ライブラリのオリジナルは CXR-L SDK `com.rokid.cxr:client-l:1.0.1`。 ラップするために、オリジナルから低レイヤーの AIDL を借用している (transitive 非公開、利用側からは見えない)。
SDK の AIDL シグネチャや Hi Rokid アプリの公開 Action 名が変わった場合は本ライブラリも追従改修が必要。

## インストール

Gradle composite build として組み込む。`maven-publish` には現状未対応のため、利用側プロジェクトの隣に clone して直接取り込む形を取る。

### 1. 利用側プロジェクトの **隣** にクローンする

`includeBuild("../CxrGlobal")` で参照するため、利用側プロジェクトと **同じ親ディレクトリ**に clone する:

```bash
# 例: ~/AndroidStudioProjects/MyApp を作っている場合
cd ~/AndroidStudioProjects
git clone https://github.com/TakanariShimbo/CxrGlobal.git
# → ~/AndroidStudioProjects/CxrGlobal と ~/AndroidStudioProjects/MyApp が並ぶ配置になる
```

### 2. Gradle に composite build として取り込ませる

利用側プロジェクトの `settings.gradle.kts`:

```kotlin
includeBuild("../CxrGlobal") {
    dependencySubstitution {
        substitute(module("com.example.cxrglobal:lib")).using(project(":lib"))
    }
}
```

### 3. 依存に追加する

利用側の `app/build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.example.cxrglobal:lib:0.1.0-SNAPSHOT")
}
```

### 4. AndroidManifest にパッケージ可視性を登録

Android 11+ で必須。`AndroidManifest.xml`:

```xml
<queries>
    <package android:name="com.rokid.sprite.global.aiapp" />
</queries>
```

## 使い方

本家 CXR-L SDK と同じ流れで使える: `requestAuthorization` → token 受取 → `configCXRSession` → `connect(token)` → 各機能呼び出し → `disconnect`。

実装例 (CustomView / CustomApp / Audio / Photo / CustomCmd の全機能) は **デモリポジトリ [TakanariShimbo/cxrlsample101-global](https://github.com/TakanariShimbo/cxrlsample101-global)** を参照。Rokid 公式 `CXRLSample` をグローバル版対応に書き換えた、本ライブラリの動く実装サンプル。

## トラブルシューティング

- `AuthorizationHelper.isRokidAppInstalled(activity)` が `false`: グローバル版 Hi Rokid 未インストール、または `<queries>` の登録漏れ。
- `link.connect(token)` 後に `onCXRLConnected(true)` が来ない: token の期限切れか、Hi Rokid Service 側で auth が通っていない。再度 `requestAuthorization` する。
- `customViewOpen` / `appStart` 等がそのまま return する: `configCXRSession(...)` での宣言が無いか不一致。`CUSTOMVIEW` 系メソッドは `CXRSessionType.CUSTOMVIEW`、`app*` 系は `CUSTOMAPP` (+ `customAppPackageName`) のセッション宣言が必須 (本家踏襲)。
- ログタグは `CXRGlobal`。`adb logcat -d -s CXRGlobal:*` で wrapper 内部のログだけ抽出できる。
