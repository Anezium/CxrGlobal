package com.example.cxrglobal

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.cxrglobal.callbacks.IAiEventCbk
import com.example.cxrglobal.callbacks.IAudioStreamCbk
import com.example.cxrglobal.callbacks.ICXRLinkCbk
import com.example.cxrglobal.callbacks.ICustomCmdCbk
import com.example.cxrglobal.callbacks.ICustomViewCbk
import com.example.cxrglobal.callbacks.IGlassAppCbk
import com.example.cxrglobal.callbacks.IImageStreamCbk
import com.rokid.sprite.aiapp.externalapp.IAiEventCallback
import com.rokid.sprite.aiapp.externalapp.IAudioStreamCallback
import com.rokid.sprite.aiapp.externalapp.ICustomCmdCallback
import com.rokid.sprite.aiapp.externalapp.ICustomViewCallback
import com.rokid.sprite.aiapp.externalapp.IDeviceStatusCallback
import com.rokid.sprite.aiapp.externalapp.IGlassAppCallback
import com.rokid.sprite.aiapp.externalapp.IImageStreamCallback
import com.rokid.sprite.aiapp.externalapp.IMediaStreamService

/**
 * Hi Rokid (global) の MediaStreamService に直接バインドして CXR-L の機能を提供するクラス。
 *
 * 公式 SDK の `CXRLink` とほぼ同じシェイプの API を持つが、内部で AIDL に直接話しかけるため
 * 中国版 Hi Rokid に依存しない。
 */
class CXRGlobalLink(private val context: Context) {

    private var service: IMediaStreamService? = null
    private var bound = false
    @Volatile private var glassConnected = false

    private var linkCbk: ICXRLinkCbk? = null
    private var customViewCbk: ICustomViewCbk? = null
    private var audioStreamCbk: IAudioStreamCbk? = null
    private var imageStreamCbk: IImageStreamCbk? = null
    private var customCmdCbk: ICustomCmdCbk? = null
    private var glassAppCbk: IGlassAppCbk? = null
    private var aiEventCbk: IAiEventCbk? = null

    // AIDL stub から user の wrapper コールバックへ転送するアダプタ群。
    private val deviceStatusStub = object : IDeviceStatusCallback.Stub() {
        override fun onDeviceConnectChanged(connected: Boolean) {
            glassConnected = connected
            linkCbk?.onGlassBtConnected(connected)
        }
    }
    private val customViewStub = object : ICustomViewCallback.Stub() {
        override fun onCustomViewOpened() { customViewCbk?.onCustomViewOpened() }
        override fun onCustomViewUpdated() { customViewCbk?.onCustomViewUpdated() }
        override fun onCustomViewClosed() { customViewCbk?.onCustomViewClosed() }
        override fun onCustomViewIconsSent() { customViewCbk?.onCustomViewIconsSent() }
        override fun onCustomViewError(code: Int, msg: String?) { customViewCbk?.onCustomViewError(code, msg) }
    }
    private val audioStub = object : IAudioStreamCallback.Stub() {
        // AIDL 上の (byte[], int, int) は公式サンプルの解釈に従い (data, offset, length)。
        override fun onAudioReceived(data: ByteArray, offset: Int, length: Int) {
            audioStreamCbk?.onAudioReceived(data, offset, length)
        }
        override fun onAudioError(code: Int, msg: String?) { audioStreamCbk?.onAudioError(code, msg) }
        override fun onAudioStreamStateChanged(started: Boolean) { audioStreamCbk?.onAudioStreamStateChanged(started) }
    }
    private val imageStub = object : IImageStreamCallback.Stub() {
        override fun onImageReceived(data: ByteArray) { imageStreamCbk?.onImageReceived(data) }
        override fun onImageError(code: Int, msg: String?) { imageStreamCbk?.onImageError(code, msg) }
    }
    private val customCmdStub = object : ICustomCmdCallback.Stub() {
        override fun onCustomCmdResult(key: String, payload: ByteArray) {
            customCmdCbk?.onCustomCmdResult(key, payload)
        }
    }
    private val aiEventStub = object : IAiEventCallback.Stub() {
        override fun onAiKeyDown() { aiEventCbk?.onAiKeyDown() }
        override fun onAiKeyUp() { aiEventCbk?.onAiKeyUp() }
        override fun onAiExit() { aiEventCbk?.onAiExit() }
        override fun onGlassAppResumeChange(from: String, to: String) {
            aiEventCbk?.onGlassAppResumeChange(from, to)
        }
    }
    private val glassAppStub = object : IGlassAppCallback.Stub() {
        override fun onInstallAppResult(success: Boolean) { glassAppCbk?.onInstallAppResult(success) }
        override fun onUnInstallAppResult(success: Boolean) { glassAppCbk?.onUninstallAppResult(success) }
        override fun onOpenAppResult(success: Boolean) { glassAppCbk?.onOpenAppResult(success) }
        override fun onStopAppResult(success: Boolean) { glassAppCbk?.onStopAppResult(success) }
        override fun onQueryAppResult(pkg: String, installed: Boolean) {
            glassAppCbk?.onQueryAppResult(pkg, installed)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = IMediaStreamService.Stub.asInterface(binder)
            service = svc
            tryRegisterAllCallbacks(svc)
            glassConnected = tryCall { svc.isDeviceConnected } ?: false
            linkCbk?.onCXRLConnected(true)
            if (glassConnected) linkCbk?.onGlassBtConnected(true)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            glassConnected = false
            linkCbk?.onCXRLConnected(false)
        }
    }

    private fun tryRegisterAllCallbacks(svc: IMediaStreamService) {
        runCatching { svc.registerDeviceStatusCallback(deviceStatusStub) }
        runCatching { svc.registerCustomViewCallback(customViewStub) }
        runCatching { svc.registerAudioCallback(audioStub) }
        runCatching { svc.registerImageCallback(imageStub) }
        runCatching { svc.registerCustomCmdCallback(customCmdStub) }
        runCatching { svc.registAiEventCallback(aiEventStub) }
    }
    private fun tryUnregisterAllCallbacks(svc: IMediaStreamService) {
        runCatching { svc.unregisterDeviceStatusCallback(deviceStatusStub) }
        runCatching { svc.unregisterCustomViewCallback(customViewStub) }
        runCatching { svc.unregisterAudioCallback(audioStub) }
        runCatching { svc.unregisterImageCallback(imageStub) }
        runCatching { svc.unregisterCustomCmdCallback(customCmdStub) }
        runCatching { svc.unregistAiEventCallback(aiEventStub) }
    }

    private inline fun <T> tryCall(block: () -> T): T? = try {
        block()
    } catch (t: Throwable) {
        Log.w(LOG_TAG, "AIDL call failed: ${t.message}")
        null
    }

    // ---- callback setters ----
    fun setLinkCbk(cbk: ICXRLinkCbk?) { linkCbk = cbk }
    fun setCustomViewCbk(cbk: ICustomViewCbk?) { customViewCbk = cbk }
    fun setAudioStreamCbk(cbk: IAudioStreamCbk?) { audioStreamCbk = cbk }
    fun setImageStreamCbk(cbk: IImageStreamCbk?) { imageStreamCbk = cbk }
    fun setCustomCmdCbk(cbk: ICustomCmdCbk?) { customCmdCbk = cbk }
    fun setGlassAppCbk(cbk: IGlassAppCbk?) { glassAppCbk = cbk }
    fun setAiEventCbk(cbk: IAiEventCbk?) { aiEventCbk = cbk }

    // ---- connection lifecycle ----
    fun connect(token: String): Boolean {
        if (bound) return true
        val intent = Intent(MEDIA_STREAM_ACTION)
            .setPackage(GLOBAL_PKG)
            .putExtra(EXTRA_AUTH_TOKEN, token)
        bound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        if (!bound) Log.w(LOG_TAG, "bindService returned false; is Hi Rokid (global) installed and authorized?")
        return bound
    }

    fun disconnect() {
        service?.let { tryUnregisterAllCallbacks(it) }
        if (bound) {
            runCatching { context.unbindService(serviceConnection) }
            bound = false
        }
        service = null
        glassConnected = false
    }

    val isConnected: Boolean get() = service != null
    val isGlassConnected: Boolean get() = glassConnected

    // ---- CustomView ----
    fun customViewSetIcons(iconsJson: String): Boolean = tryCall { service?.setIcons(iconsJson) ?: false } ?: false
    fun customViewOpen(viewJson: String): Boolean = tryCall { service?.openCustomView(viewJson) ?: false } ?: false
    fun customViewUpdate(updateJson: String): Boolean = tryCall { service?.updateCustomView(updateJson) ?: false } ?: false
    fun customViewClose(): Boolean = tryCall { service?.closeCustomView() ?: false } ?: false
    val isCustomViewOpened: Boolean get() = tryCall { service?.isCustomViewOpened ?: false } ?: false
    val currentCustomViewData: String? get() = tryCall { service?.currentCustomViewData }
    val currentIcons: String? get() = tryCall { service?.currentIcons }

    // ---- Audio ----
    fun startAudioStream(codeType: Int): Boolean = tryCall { service?.startAudioStream(codeType) ?: false } ?: false
    fun stopAudioStream(): Boolean = tryCall { service?.stopAudioStream() ?: false } ?: false
    val isAudioStreaming: Boolean get() = tryCall { service?.isAudioStreaming ?: false } ?: false

    // ---- Photo ----
    fun takePhoto(width: Int, height: Int, quality: Int): Boolean =
        tryCall { service?.takePhoto(width, height, quality) ?: false } ?: false

    // ---- CustomCMD ----
    fun sendCustomCmd(key: String, payload: ByteArray): Int =
        tryCall { service?.sendCustomCmd(key, payload) ?: -1 } ?: -1

    // ---- App control (結果は setGlassAppCbk で設定したリスナへ届く) ----
    fun appInstall(filePath: String, fd: ParcelFileDescriptor) {
        tryCall { service?.uploadAndInstallApk(filePath, fd, glassAppStub) }
    }
    fun appStart(packageName: String, target: String) {
        tryCall { service?.openApp(packageName, target, glassAppStub) }
    }
    fun appStop(packageName: String) {
        tryCall { service?.stopApp(packageName, glassAppStub) }
    }
    fun appUninstall(packageName: String) {
        tryCall { service?.uninstallApp(packageName, glassAppStub) }
    }
    fun appQueryInstalled(packageName: String) {
        tryCall { service?.queryGlassAppInstalled(packageName, glassAppStub) }
    }

    fun sendExit(forceExit: Boolean): Boolean = tryCall { service?.sendExit(forceExit) ?: false } ?: false

    val serviceVersion: String? get() = tryCall { service?.serviceVersion }
    val serviceVersionCode: Int get() = tryCall { service?.serviceVersionCode ?: 0 } ?: 0
}
