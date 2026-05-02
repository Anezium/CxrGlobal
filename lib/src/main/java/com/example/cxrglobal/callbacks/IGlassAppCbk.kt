package com.example.cxrglobal.callbacks

interface IGlassAppCbk {
    fun onInstallAppResult(success: Boolean) {}
    fun onUninstallAppResult(success: Boolean) {}
    fun onOpenAppResult(success: Boolean) {}
    fun onStopAppResult(success: Boolean) {}
    fun onQueryAppResult(packageName: String, installed: Boolean) {}
}
