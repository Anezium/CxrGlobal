package com.example.cxrglobal.callbacks

interface IGlassAppCbk {
    fun onInstallAppResult(success: Boolean) {}
    fun onUnInstallAppResult(success: Boolean) {}
    fun onOpenAppResult(success: Boolean) {}
    fun onStopAppResult(success: Boolean) {}
    fun onGlassAppResume(resume: Boolean) {}
    fun onQueryAppResult(installed: Boolean) {}
}
