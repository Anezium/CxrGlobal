package com.example.cxrglobal

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
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
import java.io.File

/**
 * Hi Rokid (global) の MediaStreamService に直接バインドして CXR-L 機能を提供する。
 *
 * 公式 CXR-L SDK の `com.rokid.cxr.link.CXRLink` と同じ API シェイプ (クラス名 / メソッド名 /
 * セッション概念 / コールバック setter) を持つ。中国版固定の Intent をグローバル版に振り直して
 * AIDL を直接叩いているだけで、利用側コードは本家 SDK と同じ感覚で書ける。
 */
class CXRLink(private val context: Context) {

    init {
        // 本家 CXRLink(Context) のコンストラクタが System.loadLibrary("cxr-sock-proto-jni") を
        // 呼ぶのを再現。この .so の JNI_OnLoad で com.rokid.cxr.Caps の native メソッドが
        // RegisterNatives される。これがないと利用側で Caps().serialize() を使ったとき
        // UnsatisfiedLinkError になる (CXR-L の wire 規約は Caps を payload に使うため
        // 本家と同じ書き味を維持するならここでロードしておくのが筋)。
        runCatching { System.loadLibrary("cxr-sock-proto-jni") }
            .onFailure { Log.w(LOG_TAG, "loadLibrary(cxr-sock-proto-jni) failed: ${it.message}") }
    }

    @Volatile private var service: IMediaStreamService? = null
    @Volatile private var bound = false
    @Volatile private var glassConnected = false

    private var session: CxrDefs.CXRSession = CxrDefs.CXRSession(CxrDefs.CXRSessionType.NONE)

    private var linkCbk: ICXRLinkCbk? = null
    private var customViewCbk: ICustomViewCbk? = null
    private var audioStreamCbk: IAudioStreamCbk? = null
    private var imageStreamCbk: IImageStreamCbk? = null
    private var customCmdCbk: ICustomCmdCbk? = null

    // IGlassAppCbk は本家踏襲で per-call。各 app* 呼び出しで上書きされる。
    private var glassAppCbk: IGlassAppCbk? = null

    // ---- AIDL Stub アダプタ (利用側に com.rokid.* を見せないための一段噛み) ----

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
        override fun onAiKeyDown() { linkCbk?.onGlassAiAssistStart() }
        override fun onAiKeyUp() { linkCbk?.onGlassAiAssistStop() }
        override fun onAiExit() { linkCbk?.onGlassAiAssistStop() }
        override fun onGlassAppResumeChange(from: String, to: String) {
            // 本家は (Boolean) のみで session の customAppPackageName と一致するかを返す。
            val target = session.customAppPackageName ?: return
            val wasMine = (from == target)
            val isMine = (to == target)
            if (wasMine != isMine) glassAppCbk?.onGlassAppResume(isMine)
        }
    }
    private val glassAppStub = object : IGlassAppCallback.Stub() {
        override fun onInstallAppResult(success: Boolean) { glassAppCbk?.onInstallAppResult(success) }
        override fun onUnInstallAppResult(success: Boolean) { glassAppCbk?.onUnInstallAppResult(success) }
        override fun onOpenAppResult(success: Boolean) { glassAppCbk?.onOpenAppResult(success) }
        override fun onStopAppResult(success: Boolean) { glassAppCbk?.onStopAppResult(success) }
        override fun onQueryAppResult(pkg: String, installed: Boolean) {
            // 本家互換のため pkg は捨てる (session で 1 アプリに絞る前提)。
            glassAppCbk?.onQueryAppResult(installed)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null) {
                markDisconnected("null binder", unbind = false)
                return
            }
            val svc = IMediaStreamService.Stub.asInterface(binder)
            service = svc
            bound = true
            tryRegisterAllCallbacks(svc)
            glassConnected = tryCall { svc.isDeviceConnected } ?: false
            Log.i(LOG_TAG, "service connected glassConnected=$glassConnected")
            linkCbk?.onCXRLConnected(true)
            linkCbk?.onGlassBtConnected(glassConnected)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            markDisconnected("service disconnected", unbind = true)
        }
        override fun onBindingDied(name: ComponentName?) {
            markDisconnected("binding died", unbind = true)
        }
        override fun onNullBinding(name: ComponentName?) {
            markDisconnected("null binding", unbind = true)
        }
    }

    private fun markDisconnected(reason: String, unbind: Boolean) {
        val wasBound = bound
        val wasGlassConnected = glassConnected
        service = null
        bound = false
        glassConnected = false
        if (unbind && wasBound) {
            runCatching { context.unbindService(serviceConnection) }
        }
        Log.w(LOG_TAG, "service disconnected: $reason")
        linkCbk?.onCXRLConnected(false)
        if (wasGlassConnected) linkCbk?.onGlassBtConnected(false)
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
        if (t is DeadObjectException) {
            markDisconnected("dead binder", unbind = true)
        }
        null
    }

    private fun requireSession(required: CxrDefs.CXRSessionType): Boolean {
        if (session.sessionType != required) {
            Log.w(LOG_TAG, "session type mismatch: required=$required, current=${session.sessionType}")
            return false
        }
        return true
    }

    // ---- callback setters (本家 SDK と同名) ----
    fun setCXRLinkCbk(cbk: ICXRLinkCbk?) { linkCbk = cbk }
    fun setCXRCustomViewCbk(cbk: ICustomViewCbk?) { customViewCbk = cbk }
    fun setCXRAudioCbk(cbk: IAudioStreamCbk?) { audioStreamCbk = cbk }
    fun setCXRImageCbk(cbk: IImageStreamCbk?) { imageStreamCbk = cbk }
    fun setCXRCustomCmdCbk(cbk: ICustomCmdCbk?) { customCmdCbk = cbk }

    // ---- session ----
    fun configCXRSession(session: CxrDefs.CXRSession): Boolean {
        if (session.sessionType == CxrDefs.CXRSessionType.CUSTOMAPP &&
            session.customAppPackageName.isNullOrEmpty()
        ) {
            Log.w(LOG_TAG, "CUSTOMAPP session requires customAppPackageName")
            return false
        }
        this.session = session
        return true
    }

    // ---- connection lifecycle ----
    fun connect(token: String): Boolean {
        if (bound && service != null) return true
        if (bound) {
            runCatching { context.unbindService(serviceConnection) }
            bound = false
        }
        val intent = Intent(MEDIA_STREAM_ACTION)
            .setPackage(GLOBAL_PKG)
            .putExtra(EXTRA_AUTH_TOKEN, token)
            .putExtra(EXTRA_AUTH_PACKAGE, context.packageName)
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

    fun isServiceConnected(): Boolean = bound && service != null

    fun isGlassBtConnected(): Boolean = glassConnected

    fun getServiceVersion(): String? = tryCall { service?.serviceVersion }
    fun getServiceVersionCode(): Int? = tryCall { service?.serviceVersionCode }

    // ---- CustomView (CUSTOMVIEW セッション必須) ----
    fun customViewSetIcons(iconsJson: String): Boolean {
        if (!requireSession(CxrDefs.CXRSessionType.CUSTOMVIEW)) return false
        return tryCall { service?.setIcons(iconsJson) ?: false } ?: false
    }
    fun customViewOpen(viewJson: String): Boolean {
        if (!requireSession(CxrDefs.CXRSessionType.CUSTOMVIEW)) return false
        return tryCall { service?.openCustomView(viewJson) ?: false } ?: false
    }
    fun customViewUpdate(updateJson: String): Boolean {
        if (!requireSession(CxrDefs.CXRSessionType.CUSTOMVIEW)) return false
        return tryCall { service?.updateCustomView(updateJson) ?: false } ?: false
    }
    fun customViewClose(): Boolean {
        if (!requireSession(CxrDefs.CXRSessionType.CUSTOMVIEW)) return false
        return tryCall { service?.closeCustomView() ?: false } ?: false
    }
    fun customViewIsOpen(): Boolean = tryCall { service?.isCustomViewOpened ?: false } ?: false
    fun customViewGetCurrentIcons(): String? = tryCall { service?.currentIcons }
    fun customViewGetCurrentData(): String? = tryCall { service?.currentCustomViewData }

    // ---- Photo / Audio (セッション制限なし) ----
    fun takePhoto(width: Int, height: Int, quality: Int): Boolean =
        tryCall { service?.takePhoto(width, height, quality) ?: false } ?: false

    fun startAudioStream(codeType: Int): Boolean =
        tryCall { service?.startAudioStream(codeType) ?: false } ?: false

    fun stopAudioStream(): Boolean = tryCall { service?.stopAudioStream() ?: false } ?: false

    fun setCommunicationDevice(): Boolean = invokeServiceNoArg("setCommunicationDevice")

    fun clearCommunicationDevice(): Boolean = invokeServiceNoArg("clearCommunicationDevice")

    fun sendExitEvent(): Boolean = invokeServiceNoArg("sendExitEvent")

    // ---- CustomCMD ----
    fun sendCustomCmd(key: String, payload: ByteArray): Int? =
        tryCall { service?.sendCustomCmd(key, payload) }

    private fun invokeServiceNoArg(methodName: String): Boolean {
        val svc = service ?: return false
        return tryCall {
            svc.javaClass.getMethod(methodName).invoke(svc)
            true
        } ?: false
    }

    // ---- App control (CUSTOMAPP セッション必須, package は session 由来, cbk は per-call) ----
    fun appUploadAndInstall(filePath: String, cbk: IGlassAppCbk) {
        if (!requireSession(CxrDefs.CXRSessionType.CUSTOMAPP)) return
        glassAppCbk = cbk
        val svc = service
        if (svc == null) {
            Log.e(LOG_TAG, "appUploadAndInstall: service is null")
            cbk.onInstallAppResult(false)
            return
        }
        val file = File(filePath)
        if (file.isDirectory || !file.exists()) {
            Log.e(LOG_TAG, "appUploadAndInstall: not a file or doesn't exist: $filePath")
            cbk.onInstallAppResult(false)
            return
        }
        try {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            svc.uploadAndInstallApk(file.name, fd, glassAppStub)
        } catch (t: Throwable) {
            Log.e(LOG_TAG, "appUploadAndInstall failed", t)
            cbk.onInstallAppResult(false)
        }
    }

    fun appUninstall(cbk: IGlassAppCbk) {
        if (!requireSession(CxrDefs.CXRSessionType.CUSTOMAPP)) return
        glassAppCbk = cbk
        val pkg = session.customAppPackageName ?: return
        val svc = service
        if (svc == null) {
            Log.e(LOG_TAG, "appUninstall: service is null")
            cbk.onUnInstallAppResult(false)
            return
        }
        tryCall { svc.uninstallApp(pkg, glassAppStub) }
    }

    fun appStart(activityName: String, cbk: IGlassAppCbk) {
        if (!requireSession(CxrDefs.CXRSessionType.CUSTOMAPP)) return
        glassAppCbk = cbk
        val pkg = session.customAppPackageName ?: return
        val svc = service
        if (svc == null) {
            Log.e(LOG_TAG, "appStart: service is null")
            cbk.onOpenAppResult(false)
            return
        }
        tryCall { svc.openApp(pkg, activityName, glassAppStub) }
    }

    fun appStop(cbk: IGlassAppCbk) {
        if (!requireSession(CxrDefs.CXRSessionType.CUSTOMAPP)) return
        glassAppCbk = cbk
        val pkg = session.customAppPackageName ?: return
        val svc = service
        if (svc == null) {
            Log.e(LOG_TAG, "appStop: service is null")
            cbk.onStopAppResult(false)
            return
        }
        tryCall { svc.stopApp(pkg, glassAppStub) }
    }

    fun appIsInstalled(cbk: IGlassAppCbk) {
        if (!requireSession(CxrDefs.CXRSessionType.CUSTOMAPP)) return
        glassAppCbk = cbk
        val pkg = session.customAppPackageName ?: return
        val svc = service
        if (svc == null) {
            Log.e(LOG_TAG, "appIsInstalled: service is null")
            cbk.onQueryAppResult(false)
            return
        }
        tryCall { svc.queryGlassAppInstalled(pkg, glassAppStub) }
    }
}
