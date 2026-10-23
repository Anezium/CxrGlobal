package com.example.cxrglobal.callbacks

import com.example.cxrglobal.GlassInfo

/**
 * Link up/down, the server-close reason, scene reports and AI key events are delivered on the main
 * thread; the device-status callbacks (BT, device info, wearing) on the binder thread they arrived on.
 */
interface ICXRLinkCbk {
    fun onCXRLConnected(connected: Boolean) {}

    /**
     * Hi Rokid ended the session itself. Followed by `onCXRLConnected(false)` when the link had
     * been reported up; a session closed during setup was never reported up.
     */
    fun onCXRLDisconnectedByServer() {}
    fun onGlassBtConnected(connected: Boolean) {}
    fun onGlassDeviceInfo(info: GlassInfo) {}
    fun onGlassWearingStatus(wearing: Boolean) {}
    fun onGlassCurrentScenesNotify(scenesJson: String?) {}
    fun onGlassAiAssistStart() {}
    fun onGlassAiAssistStop() {}
    fun onGlassAiInterrupt(interrupted: Boolean) {}
}
