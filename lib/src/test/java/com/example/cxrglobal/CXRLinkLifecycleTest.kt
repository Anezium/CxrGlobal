package com.example.cxrglobal

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Looper
import com.example.cxrglobal.callbacks.ICXRLinkCbk
import com.rokid.sprite.aiapp.externalapp.IAiEventCallback
import com.rokid.sprite.aiapp.externalapp.IAudioStreamCallback
import com.rokid.sprite.aiapp.externalapp.ICustomCmdCallback
import com.rokid.sprite.aiapp.externalapp.ICustomViewCallback
import com.rokid.sprite.aiapp.externalapp.IDeviceStatusCallback
import com.rokid.sprite.aiapp.externalapp.IImageStreamCallback
import com.rokid.sprite.aiapp.externalapp.IMediaStreamService
import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class CXRLinkLifecycleTest {
    private lateinit var app: Application
    private lateinit var service: FakeHiRokid
    private lateinit var link: CXRLink
    private val events = Collections.synchronizedList(mutableListOf<String>())

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        service = FakeHiRokid()
        shadowOf(app).setComponentNameAndServiceForBindServiceForIntent(
            Intent(MEDIA_STREAM_ACTION).setPackage(GLOBAL_PKG),
            ComponentName(GLOBAL_PKG, "MediaStreamService"),
            service,
        )
        link = CXRLink(app)
        link.setCXRLinkCbk(object : ICXRLinkCbk {
            override fun onCXRLConnected(connected: Boolean) { record("link:$connected") }
            override fun onCXRLDisconnectedByServer() { record("closedByServer") }
            override fun onGlassBtConnected(connected: Boolean) { record("bt:$connected") }
            override fun onGlassCurrentScenesNotify(scenesJson: String?) { record("scenes:$scenesJson") }
            override fun onGlassAiAssistStart() { record("aiStart") }
        })
    }

    @Test
    fun `setup announces the sdk version, registers every callback, probes, then reports the link up`() {
        connect()

        assertEquals(
            listOf(
                "sendSdkVersion:112",
                "registerDeviceStatus",
                "registerCustomView",
                "registerAudio",
                "registerImage",
                "registerCustomCmd",
                "registerAiEvent",
                "isDeviceConnected",
            ),
            service.calls,
        )
        assertEquals(listOf("link:true", "bt:true"), events)
        assertTrue(link.isServiceConnected())
    }

    @Test
    fun `a server disconnect reports its reason, then the link down, once`() {
        connect()
        events.clear()

        service.deviceStatus.last().onDisconnectByServer()
        service.deviceStatus.last().onDisconnectByServer()
        idle()

        assertEquals(listOf("closedByServer", "link:false", "bt:false"), events)
        assertFalse(link.isServiceConnected())
        assertEquals(1, shadowOf(app).unboundServiceConnections.size)
    }

    @Test
    fun `a server disconnect on a binder thread retires the bind at once and tears down on main`() {
        connect()
        events.clear()

        onBinderThread { service.deviceStatus.last().onDisconnectByServer() }

        assertFalse(link.isServiceConnected())
        assertEquals(emptyList<String>(), events)
        idle()
        assertEquals(listOf("closedByServer", "link:false", "bt:false"), events)
    }

    @Test
    fun `a server disconnect during the first registration stops setup there`() {
        service.afterDeviceStatusRegistered = {
            onBinderThread { service.deviceStatus.last().onDisconnectByServer() }
        }

        connect()

        assertEquals(listOf("sendSdkVersion:112", "registerDeviceStatus"), service.calls)
        assertEquals(listOf("closedByServer"), events)
        assertFalse(link.isServiceConnected())
    }

    @Test
    fun `a BT change reported during registration is ordered before the newer probe`() {
        service.afterDeviceStatusRegistered = {
            service.deviceStatus.last().onDeviceConnectChanged(true)
        }
        service.deviceConnected = false

        connect()

        assertEquals(listOf("bt:true", "link:true", "bt:false"), events)
        assertFalse(link.isGlassBtConnected())
    }

    @Test
    fun `a dead binder during the version handshake stops setup`() {
        service.sdkVersionFailure = DeadObjectException()

        connect()

        assertEquals(listOf("sendSdkVersion:112"), service.calls)
        assertEquals(emptyList<String>(), events)
        assertFalse(link.isServiceConnected())
    }

    @Test
    fun `a dead binder during a registration stops setup`() {
        service.customViewFailure = DeadObjectException()

        connect()

        assertEquals(
            listOf("sendSdkVersion:112", "registerDeviceStatus", "registerCustomView"),
            service.calls,
        )
        assertEquals(emptyList<String>(), events)
        assertFalse(link.isServiceConnected())
    }

    @Test
    fun `a replacement reports the old bind's loss first and survives its queued teardown`() {
        connect()
        onBinderThread { service.deviceStatus.last().onDisconnectByServer() }
        events.clear()

        link.connect("token")

        assertEquals(listOf("closedByServer", "link:false", "bt:false"), events)
        idle()
        assertEquals(
            listOf("closedByServer", "link:false", "bt:false", "link:true", "bt:true"),
            events,
        )
        assertTrue(link.isServiceConnected())
        assertEquals(1, shadowOf(app).unboundServiceConnections.size)
    }

    @Test
    fun `callbacks of a replaced bind reach neither the new bind nor the hub`() {
        connect()
        val oldDeviceStatus = service.deviceStatus.last()
        val oldAiEvent = service.aiEvent.last()
        oldDeviceStatus.onDisconnectByServer()
        connect()
        events.clear()

        onBinderThread {
            oldDeviceStatus.onCurrentScenesNotify("""["phoneCallRunning"]""")
            oldDeviceStatus.onDeviceConnectChanged(false)
            oldAiEvent.onAiKeyDown()
            oldDeviceStatus.onDisconnectByServer()
        }
        idle()

        assertEquals(emptyList<String>(), events)
        assertTrue(link.isServiceConnected())
    }

    @Test
    fun `scene reports arrive on the main thread, and one queued across a teardown is dropped`() {
        connect()
        events.clear()

        onBinderThread { service.deviceStatus.last().onCurrentScenesNotify("""["otaRunning"]""") }
        assertEquals(emptyList<String>(), events)
        idle()
        assertEquals(listOf("scenes:[\"otaRunning\"]@main"), events)

        events.clear()
        onBinderThread { service.deviceStatus.last().onCurrentScenesNotify("""["phoneCallRunning"]""") }
        link.disconnect()
        idle()
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `a pending bind replaced before it connects reports nothing of its own`() {
        assertTrue(link.connect("token"))
        assertTrue(link.connect("token"))
        idle()

        assertEquals(listOf("link:true", "bt:true"), events)
        assertEquals(1, shadowOf(app).unboundServiceConnections.size)
    }

    @Test
    fun `an explicit disconnect unbinds without reporting`() {
        connect()
        events.clear()

        link.disconnect()
        idle()

        assertEquals(emptyList<String>(), events)
        assertFalse(link.isServiceConnected())
        assertEquals(1, shadowOf(app).unboundServiceConnections.size)
    }

    private fun record(event: String) {
        val onMain = Looper.myLooper() == Looper.getMainLooper()
        events += if (event.startsWith("scenes:")) "$event@${if (onMain) "main" else "binder"}" else event
    }

    private fun connect() {
        assertTrue(link.connect("token"))
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun onBinderThread(block: () -> Unit) {
        Thread(block).apply { start() }.join()
    }

    private class FakeHiRokid :
        IMediaStreamService.Stub(),
        IMediaStreamService by IMediaStreamService.Default() {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val deviceStatus = mutableListOf<IDeviceStatusCallback>()
        val aiEvent = mutableListOf<IAiEventCallback>()
        var sdkVersionFailure: DeadObjectException? = null
        var customViewFailure: DeadObjectException? = null
        var afterDeviceStatusRegistered: () -> Unit = {}
        var deviceConnected = true

        override fun asBinder(): IBinder = this

        override fun sendSdkVersion(version: Int) {
            calls += "sendSdkVersion:$version"
            sdkVersionFailure?.let { throw it }
        }

        override fun registerDeviceStatusCallback(callback: IDeviceStatusCallback): Boolean {
            calls += "registerDeviceStatus"
            deviceStatus += callback
            afterDeviceStatusRegistered()
            return true
        }

        override fun registerCustomViewCallback(callback: ICustomViewCallback?): Boolean {
            calls += "registerCustomView"
            customViewFailure?.let { throw it }
            return true
        }

        override fun registerAudioCallback(callback: IAudioStreamCallback?): Boolean {
            calls += "registerAudio"
            return true
        }

        override fun registerImageCallback(callback: IImageStreamCallback?): Boolean {
            calls += "registerImage"
            return true
        }

        override fun registerCustomCmdCallback(callback: ICustomCmdCallback?): Boolean {
            calls += "registerCustomCmd"
            return true
        }

        override fun registAiEventCallback(callback: IAiEventCallback): Boolean {
            calls += "registerAiEvent"
            aiEvent += callback
            return true
        }

        override fun isDeviceConnected(): Boolean {
            calls += "isDeviceConnected"
            return deviceConnected
        }
    }
}
