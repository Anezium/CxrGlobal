package com.example.cxrglobal.callbacks

interface ICXRLinkCbk {
    /** Hi Rokid アプリの MediaStreamService にバインド成功/解除されたとき。 */
    fun onCXRLConnected(connected: Boolean) {}

    /** グラスとの BT 接続状態が変化したとき。 */
    fun onGlassBtConnected(connected: Boolean) {}
}
