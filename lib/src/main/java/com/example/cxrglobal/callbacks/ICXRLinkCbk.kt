package com.example.cxrglobal.callbacks

import com.example.cxrglobal.GlassInfo

interface ICXRLinkCbk {
    fun onCXRLConnected(connected: Boolean) {}
    fun onGlassBtConnected(connected: Boolean) {}
    fun onGlassDeviceInfo(info: GlassInfo) {}
    fun onGlassWearingStatus(wearing: Boolean) {}
    fun onGlassAiAssistStart() {}
    fun onGlassAiAssistStop() {}
    fun onGlassAiInterrupt(interrupted: Boolean) {}
}
