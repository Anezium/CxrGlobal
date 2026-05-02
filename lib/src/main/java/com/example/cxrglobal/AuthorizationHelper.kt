package com.example.cxrglobal

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

object AuthorizationHelper {

    fun isHiRokidInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(GLOBAL_PKG, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun requestAuthorization(activity: Activity, requestCode: Int) {
        val intent = Intent(AUTH_ACTION).setPackage(GLOBAL_PKG)
        try {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, requestCode)
        } catch (t: Throwable) {
            Log.e(LOG_TAG, "requestAuthorization failed", t)
            throw RuntimeException(
                "Hi Rokid (global) アプリが認可をハンドリングできませんでした。" +
                    "$GLOBAL_PKG がインストール済みか確認してください。",
                t
            )
        }
    }

    fun parseAuthorizationResult(resultCode: Int, data: Intent?): AuthResult {
        if (resultCode != Activity.RESULT_OK || data == null) return AuthResult.AuthCancel
        return when (data.getIntExtra(EXTRA_AUTH_RESULT, -1)) {
            AUTH_RESULT_SUCCESS -> {
                val token = data.getStringExtra(EXTRA_AUTH_TOKEN).orEmpty()
                if (token.isEmpty()) AuthResult.AuthFail else AuthResult.AuthSuccess(token)
            }
            AUTH_RESULT_CANCEL -> AuthResult.AuthCancel
            else -> AuthResult.AuthFail
        }
    }
}
