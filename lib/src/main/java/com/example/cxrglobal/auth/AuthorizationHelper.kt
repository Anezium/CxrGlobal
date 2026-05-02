package com.example.cxrglobal.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.example.cxrglobal.AUTH_ACTION
import com.example.cxrglobal.AUTH_RESULT_CANCEL
import com.example.cxrglobal.AUTH_RESULT_SUCCESS
import com.example.cxrglobal.EXTRA_AUTH_RESULT
import com.example.cxrglobal.EXTRA_AUTH_TOKEN
import com.example.cxrglobal.GLOBAL_PKG
import com.example.cxrglobal.LOG_TAG

object AuthorizationHelper {

    fun isRokidAppInstalled(activity: Activity): Boolean =
        isAppInstalled(activity, GLOBAL_PKG)

    fun isRequiredRokidAppInstalled(activity: Activity): Boolean =
        isAppInstalled(activity, GLOBAL_PKG)

    fun isAppInstalled(activity: Activity, packageName: String): Boolean = try {
        activity.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun canLaunchApp(activity: Activity, packageName: String): Boolean =
        activity.packageManager.getLaunchIntentForPackage(packageName) != null

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
                t,
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
