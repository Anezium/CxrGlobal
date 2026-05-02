package com.example.cxrglobal.callbacks

interface ICXRLinkCbk {
    fun onCXRLConnected(connected: Boolean) {}
    fun onGlassBtConnected(connected: Boolean) {}
    fun onGlassAiAssistStart() {}
    fun onGlassAiAssistStop() {}
}
