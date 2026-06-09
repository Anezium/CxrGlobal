plugins {
    // 親プロジェクト (claude-mobile-hud) と standalone (cxrglobal/) の双方で動くよう
    // 直接 id を使い version は build classpath / settings の pluginManagement から解決。
    alias(libs.plugins.android.library)
}

group = "com.example.cxrglobal"
version = "0.2.0"

android {
    namespace = "com.example.cxrglobal"
    // 親 (claude-mobile-hud:phone) と compileSdk を合わせるため 36 にダウングレード。
    // 元の 36.1 (minor api level) は AGP 9+ の機能で phone 側も追従が必要だが、
    // CXR-L 1.0.3 keeps the same compile surface while providing 16 KB page-size native libs.
    compileSdk = 36

    defaultConfig {
        minSdk = 31
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // 本家 CXR-L SDK は AIDL stub クラスを借りるためだけに保持。
    // CXRLink / AuthorizationHelper は中国版パッケージにハードコードされており本ライブラリでは使わない。
    //
    // **`api`** 必須: phone/glass モジュールが `com.rokid.cxr.Caps` を直接触る
    // (CapsFactoryImpl) ため、SDK の symbol を transitively expose する必要がある。
    api("com.rokid.cxr:client-l:1.0.3")
}
