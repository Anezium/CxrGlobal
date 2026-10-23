package com.example.cxrglobal

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.cxrglobal.callbacks.IAudioStreamCbk
import com.example.cxrglobal.callbacks.ICXRLinkCbk
import com.example.cxrglobal.callbacks.ICustomCmdCbk
import com.example.cxrglobal.callbacks.ICustomViewCbk
import com.example.cxrglobal.callbacks.IGlassAppCbk
import com.example.cxrglobal.callbacks.IImageStreamCbk
import com.rokid.cxr.Caps
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

    private val mainHandler = Handler(Looper.getMainLooper())

    // One bindService call, with its own AIDL callback adapters. A bind is retired the moment it is
    // replaced, torn down, closed by the service or hits a dead binder, from any thread. Its events
    // are forwarded only while it is still the current bind. Scene reports and AI key events go
    // through the main thread, where teardown runs, so none from a bind that is over can follow its
    // link-down; everything else is forwarded on the binder thread it arrived on. A retired bind
    // never reports the link up, unbinds its successor, or forwards an event.
    private inner class Bind : ServiceConnection {
        @Volatile var retired = false
        @Volatile var closedByServer = false
        @Volatile var service: IMediaStreamService? = null
        var reportedUp = false

        fun isCurrent(): Boolean = !retired && current === this

        private fun onMain(event: () -> Unit) {
            mainHandler.post { if (isCurrent()) deliver(event) }
        }

        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) =
            onBindConnected(this, binder)
        override fun onServiceDisconnected(name: ComponentName?) = retire(this, "service disconnected")
        override fun onBindingDied(name: ComponentName?) = retire(this, "binding died")
        override fun onNullBinding(name: ComponentName?) = retire(this, "null binding")

        // ---- AIDL Stub アダプタ (利用側に com.rokid.* を見せないための一段噛み) ----

        val deviceStatus = object : IDeviceStatusCallback.Stub() {
            override fun onDeviceConnectChanged(connected: Boolean) {
                if (!isCurrent()) return
                glassConnected = connected
                linkCbk?.onGlassBtConnected(connected)
            }
            override fun onDeviceInfoNotifiy(infoJson: String?) {
                if (!isCurrent() || infoJson.isNullOrBlank()) return
                runCatching { GlassInfo.fromJson(infoJson) }
                    .onSuccess { linkCbk?.onGlassDeviceInfo(it) }
                    .onFailure { Log.w(LOG_TAG, "parse glass info failed: ${it.message}") }
            }
            override fun onWearingStatusNotify(wearing: Boolean) {
                if (isCurrent()) linkCbk?.onGlassWearingStatus(wearing)
            }
            override fun onCurrentScenesNotify(scenesJson: String?) = onMain {
                linkCbk?.onGlassCurrentScenesNotify(scenesJson)
            }
            override fun onDisconnectByServer() {
                // Mirrors client-l 1.1.2: unbind only, with no call back into the service from
                // the thread it is delivering this callback on.
                if (retired) return
                closedByServer = true
                retire(this@Bind, "disconnected by server")
            }
        }
        val customView = object : ICustomViewCallback.Stub() {
            override fun onCustomViewOpened() { if (isCurrent()) customViewCbk?.onCustomViewOpened() }
            override fun onCustomViewUpdated() { if (isCurrent()) customViewCbk?.onCustomViewUpdated() }
            override fun onCustomViewClosed() { if (isCurrent()) customViewCbk?.onCustomViewClosed() }
            override fun onCustomViewIconsSent() { if (isCurrent()) customViewCbk?.onCustomViewIconsSent() }
            override fun onCustomViewError(code: Int, msg: String?) {
                if (isCurrent()) customViewCbk?.onCustomViewError(code, msg)
            }
        }
        val audio = object : IAudioStreamCallback.Stub() {
            override fun onAudioReceived(data: ByteArray, offset: Int, length: Int) {
                if (isCurrent()) audioStreamCbk?.onAudioReceived(data, offset, length)
            }
            override fun onAudioError(code: Int, msg: String?) {
                if (isCurrent()) audioStreamCbk?.onAudioError(code, msg)
            }
            override fun onAudioStreamStateChanged(started: Boolean) {
                if (isCurrent()) audioStreamCbk?.onAudioStreamStateChanged(started)
            }
        }
        val image = object : IImageStreamCallback.Stub() {
            override fun onImageReceived(data: ByteArray) {
                if (isCurrent()) imageStreamCbk?.onImageReceived(data)
            }
            override fun onImageError(code: Int, msg: String?) {
                if (isCurrent()) imageStreamCbk?.onImageError(code, msg)
            }
        }
        val customCmd = object : ICustomCmdCallback.Stub() {
            override fun onCustomCmdResult(key: String, payload: ByteArray) {
                if (isCurrent()) customCmdCbk?.onCustomCmdResult(key, payload)
            }
        }
        val aiEvent = object : IAiEventCallback.Stub() {
            override fun onAiKeyDown() = onMain { linkCbk?.onGlassAiAssistStart() }
            override fun onAiKeyUp() = onMain { linkCbk?.onGlassAiAssistStop() }
            override fun onAiExit() = onMain { linkCbk?.onGlassAiAssistStop() }
            override fun onInterruptAiWake(interrupted: Boolean) = onMain {
                linkCbk?.onGlassAiInterrupt(interrupted)
            }
            override fun onGlassAppResumeChange(from: String, to: String) {
                if (!isCurrent()) return
                // 本家は (Boolean) のみで session の customAppPackageName と一致するかを返す。
                val target = session.customAppPackageName ?: return
                val wasMine = (from == target)
                val isMine = (to == target)
                if (wasMine != isMine) glassAppCbk?.onGlassAppResume(isMine)
            }
        }
        val glassApp = object : IGlassAppCallback.Stub() {
            override fun onInstallAppResult(success: Boolean) {
                if (isCurrent()) glassAppCbk?.onInstallAppResult(success)
            }
            override fun onUnInstallAppResult(success: Boolean) {
                if (isCurrent()) glassAppCbk?.onUnInstallAppResult(success)
            }
            override fun onOpenAppResult(success: Boolean) {
                if (isCurrent()) glassAppCbk?.onOpenAppResult(success)
            }
            override fun onStopAppResult(success: Boolean) {
                if (isCurrent()) glassAppCbk?.onStopAppResult(success)
            }
            override fun onQueryAppResult(pkg: String, installed: Boolean) {
                // 本家互換のため pkg は捨てる (session で 1 アプリに絞る前提)。
                if (isCurrent()) glassAppCbk?.onQueryAppResult(installed)
            }
        }
    }

    @Volatile private var current: Bind? = null
    @Volatile private var glassConnected = false

    private val service: IMediaStreamService?
        get() = liveBind()?.service

    private fun liveBind(): Bind? = current?.takeIf { !it.retired }

    private var session: CxrDefs.CXRSession = CxrDefs.CXRSession(CxrDefs.CXRSessionType.NONE)

    private var linkCbk: ICXRLinkCbk? = null
    private var customViewCbk: ICustomViewCbk? = null
    private var audioStreamCbk: IAudioStreamCbk? = null
    private var imageStreamCbk: IImageStreamCbk? = null
    private var customCmdCbk: ICustomCmdCbk? = null

    // IGlassAppCbk は本家踏襲で per-call。各 app* 呼び出しで上書きされる。
    private var glassAppCbk: IGlassAppCbk? = null

    // Main thread, like every ServiceConnection callback. Each setup call runs only while the bind
    // is still current; a server disconnect or dead binder during setup stops it, and the bind is
    // then torn down without ever having reported the link up.
    private fun onBindConnected(bind: Bind, binder: IBinder?) {
        if (!bind.isCurrent()) return
        if (binder == null) {
            retire(bind, "null binder")
            return
        }
        val svc = IMediaStreamService.Stub.asInterface(binder)
        bind.service = svc
        val setup: List<() -> Any?> = listOf(
            { svc.sendSdkVersion(CXRL_CLIENT_SDK_VERSION) },
            { svc.registerDeviceStatusCallback(bind.deviceStatus) },
            { svc.registerCustomViewCallback(bind.customView) },
            { svc.registerAudioCallback(bind.audio) },
            { svc.registerImageCallback(bind.image) },
            { svc.registerCustomCmdCallback(bind.customCmd) },
            { svc.registAiEventCallback(bind.aiEvent) },
        )
        for (call in setup) {
            if (!bind.isCurrent()) return
            callFor(bind, call)
        }
        if (!bind.isCurrent()) return
        val deviceConnected = callFor(bind) { svc.isDeviceConnected } ?: false
        if (!bind.isCurrent()) return
        glassConnected = deviceConnected
        bind.reportedUp = true
        Log.i(LOG_TAG, "service connected glassConnected=$glassConnected")
        deliver { linkCbk?.onCXRLConnected(true) }
        deliver { linkCbk?.onGlassBtConnected(glassConnected) }
    }

    // On a binder thread an escaping exception is caught by Binder; on the main looper it would end
    // the process, so callbacks delivered there are contained the same way.
    private fun deliver(callback: () -> Unit) {
        runCatching(callback).onFailure { Log.w(LOG_TAG, "callback failed", it) }
    }

    private fun retire(bind: Bind, reason: String) {
        bind.retired = true
        if (Looper.myLooper() == Looper.getMainLooper()) {
            finishRetire(bind, reason)
        } else {
            mainHandler.post { finishRetire(bind, reason) }
        }
    }

    // Main thread only. Runs once per bind: whoever clears `current` first owns the teardown.
    private fun finishRetire(bind: Bind, reason: String) {
        if (current !== bind) return
        current = null
        val wasGlassConnected = glassConnected
        glassConnected = false
        runCatching { context.unbindService(bind) }
        if (bind.reportedUp) {
            Log.w(LOG_TAG, "service disconnected: $reason")
        } else {
            Log.i(LOG_TAG, "pending bind dropped: $reason")
        }
        if (bind.closedByServer) deliver { linkCbk?.onCXRLDisconnectedByServer() }
        if (!bind.reportedUp) return
        deliver { linkCbk?.onCXRLConnected(false) }
        if (wasGlassConnected) deliver { linkCbk?.onGlassBtConnected(false) }
    }

    private fun tryUnregisterAllCallbacks(bind: Bind, svc: IMediaStreamService) {
        runCatching { svc.unregisterDeviceStatusCallback(bind.deviceStatus) }
        runCatching { svc.unregisterCustomViewCallback(bind.customView) }
        runCatching { svc.unregisterAudioCallback(bind.audio) }
        runCatching { svc.unregisterImageCallback(bind.image) }
        runCatching { svc.unregisterCustomCmdCallback(bind.customCmd) }
        runCatching { svc.unregistAiEventCallback(bind.aiEvent) }
    }

    private inline fun <T> callFor(bind: Bind?, block: () -> T): T? = try {
        block()
    } catch (t: Throwable) {
        Log.w(LOG_TAG, "AIDL call failed: ${t.message}")
        if (t is DeadObjectException && bind != null) retire(bind, "dead binder")
        null
    }

    private inline fun <T> tryCall(block: () -> T): T? = callFor(current, block)

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

    // ---- connection lifecycle (call on the main thread) ----
    fun connect(token: String): Boolean {
        if (service != null) return true
        // A bind still pending, or retired on a binder thread with its teardown queued, is finished
        // here, so its loss is reported before the replacement exists.
        current?.let { stale ->
            stale.retired = true
            finishRetire(stale, "replaced")
        }
        val intent = Intent(MEDIA_STREAM_ACTION)
            .setPackage(GLOBAL_PKG)
            .putExtra(EXTRA_AUTH_TOKEN, token)
            .putExtra(EXTRA_AUTH_PACKAGE, context.packageName)
        val bind = Bind()
        current = bind
        val bound = runCatching {
            context.bindService(intent, bind, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!bound) {
            bind.retired = true
            current = null
            runCatching { context.unbindService(bind) }
            Log.w(LOG_TAG, "bindService returned false; is Hi Rokid (global) installed and authorized?")
        }
        return bound
    }

    fun disconnect() {
        val bind = current ?: return
        bind.retired = true
        current = null
        bind.service?.let { tryUnregisterAllCallbacks(bind, it) }
        runCatching { context.unbindService(bind) }
        glassConnected = false
    }

    fun isServiceConnected(): Boolean = service != null

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

    fun setBrightness(level: Int): Boolean =
        tryCall { service?.setBrightness(level) ?: false } ?: false

    fun setVolume(level: Int): Boolean =
        tryCall { service?.setVolume(level) ?: false } ?: false

    fun startAudioStream(codeType: Int): Boolean =
        tryCall { service?.startAudioStream(codeType) ?: false } ?: false

    fun stopAudioStream(): Boolean = tryCall { service?.stopAudioStream() ?: false } ?: false

    fun setInterruptAiWake(interrupt: Boolean): Boolean =
        tryCall { service?.interruptAiWake(interrupt) ?: false } ?: false

    fun getGlassDeviceInfo() {
        tryCall { service?.getDeviceInfo() }
    }

    fun isWearingCheckOn(): Boolean = tryCall { service?.wearingSwitch ?: false } ?: false

    fun sendExit(playSound: Boolean): Boolean =
        tryCall { service?.sendExit(playSound) ?: false } ?: false

    // ---- CustomCMD ----
    fun sendCustomCmd(key: String, payload: ByteArray): Int? =
        tryCall { service?.sendCustomCmd(key, payload) }

    fun sendCustomCmd(key: String, payload: Caps): Int? =
        sendCustomCmd(key, payload.serialize())

    fun sendCustomCmd(key: String, payload: Caps, stream: ByteArray): Int? =
        tryCall { service?.sendCustomCmdStream(key, payload.serialize(), stream) }

    // ---- App control (CUSTOMAPP セッション必須, package は session 由来, cbk は per-call) ----
    fun appUploadAndInstall(filePath: String, cbk: IGlassAppCbk) {
        if (!requireSession(CxrDefs.CXRSessionType.CUSTOMAPP)) return
        glassAppCbk = cbk
        val bind = liveBind()
        val svc = bind?.service
        if (bind == null || svc == null) {
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
            svc.uploadAndInstallApk(file.name, fd, bind.glassApp)
        } catch (t: Throwable) {
            Log.e(LOG_TAG, "appUploadAndInstall failed", t)
            cbk.onInstallAppResult(false)
        }
    }

    fun appUninstall(cbk: IGlassAppCbk) {
        if (!requireSession(CxrDefs.CXRSessionType.CUSTOMAPP)) return
        glassAppCbk = cbk
        val pkg = session.customAppPackageName ?: return
        val bind = liveBind()
        val svc = bind?.service
        if (bind == null || svc == null) {
            Log.e(LOG_TAG, "appUninstall: service is null")
            cbk.onUnInstallAppResult(false)
            return
        }
        tryCall { svc.uninstallApp(pkg, bind.glassApp) }
    }

    fun appStart(activityName: String, cbk: IGlassAppCbk) {
        appStart(activityName, interruptAiWake = false, cbk)
    }

    fun appStart(activityName: String, interruptAiWake: Boolean, cbk: IGlassAppCbk) {
        if (!requireSession(CxrDefs.CXRSessionType.CUSTOMAPP)) return
        glassAppCbk = cbk
        val pkg = session.customAppPackageName ?: return
        val bind = liveBind()
        val svc = bind?.service
        if (bind == null || svc == null) {
            Log.e(LOG_TAG, "appStart: service is null")
            cbk.onOpenAppResult(false)
            return
        }
        tryCall { svc.openApp(pkg, activityName, bind.glassApp) }
        if (interruptAiWake) setInterruptAiWake(true)
    }

    fun appStop(cbk: IGlassAppCbk) {
        if (!requireSession(CxrDefs.CXRSessionType.CUSTOMAPP)) return
        glassAppCbk = cbk
        val pkg = session.customAppPackageName ?: return
        val bind = liveBind()
        val svc = bind?.service
        if (bind == null || svc == null) {
            Log.e(LOG_TAG, "appStop: service is null")
            cbk.onStopAppResult(false)
            return
        }
        tryCall { svc.stopApp(pkg, bind.glassApp) }
        setInterruptAiWake(false)
    }

    fun appIsInstalled(cbk: IGlassAppCbk) {
        if (!requireSession(CxrDefs.CXRSessionType.CUSTOMAPP)) return
        glassAppCbk = cbk
        val pkg = session.customAppPackageName ?: return
        val bind = liveBind()
        val svc = bind?.service
        if (bind == null || svc == null) {
            Log.e(LOG_TAG, "appIsInstalled: service is null")
            cbk.onQueryAppResult(false)
            return
        }
        tryCall { svc.queryGlassAppInstalled(pkg, bind.glassApp) }
    }
}
