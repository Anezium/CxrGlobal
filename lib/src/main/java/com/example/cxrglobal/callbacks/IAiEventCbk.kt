package com.example.cxrglobal.callbacks

interface IAiEventCbk {
    fun onAiKeyDown() {}
    fun onAiKeyUp() {}
    fun onAiExit() {}
    fun onGlassAppResumeChange(from: String, to: String) {}
}
