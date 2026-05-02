plugins {
    alias(libs.plugins.android.library)
}

group = "com.example.cxrglobal"
version = "0.1.0-SNAPSHOT"

android {
    namespace = "com.example.cxrglobal"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

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
    implementation("com.rokid.cxr:client-l:1.0.1")
}
