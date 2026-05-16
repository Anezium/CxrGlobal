// Top-level build file。
// 親 (claude-mobile-hud) から `include(":cxrglobal:lib")` で取り込まれる場合は、
// 親側で android-library プラグインのバージョン解決が済んでいるためここでは
// re-declare しない。standalone build (cxrglobal/ 直下から ./gradlew) で必要なら
// :lib 側で `alias(libs.plugins.android.library)` を apply している。
