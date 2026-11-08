package io.github.draumaz.hidenav.shell

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import io.github.draumaz.hidenav.IHideNavService
import kotlin.system.exitProcess

/**
 * Runs in a separate process that Shizuku starts as the shell UID, so it holds the shell's
 * STATUS_BAR and INJECT_EVENTS permissions. Everything here goes through hidden binder
 * interfaces by reflection; the shell process doesn't enforce hidden-API restrictions.
 *
 * The disable flag is tied to [token]: if this process dies, system_server drops the flag
 * and the pill comes back on its own, so a crash can never strand the user without it.
 */
class HideNavUserService : IHideNavService.Stub() {

    private val token: IBinder = Binder()
    private val handler = Handler(HandlerThread("hidenav-shell").apply { start() }.looper)

    @Volatile private var hidden = false
    @Volatile private var replaying = false

    private val statusBar: Any by lazy { service("statusbar", "com.android.internal.statusbar.IStatusBarService") }
    private val input: Any by lazy { service("input", "android.hardware.input.IInputManager") }
    private val disable by lazy {
        statusBar.javaClass.getMethod("disable", Int::class.java, IBinder::class.java, String::class.java)
    }
    private val inject by lazy {
        input.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.java)
    }
    private val capture by lazy { DisplayCapture(service("window", "android.view.IWindowManager")) }

    override fun destroy() {
        runCatching { applyFlag(false) }
        exitProcess(0)
    }

    override fun setPillHidden(hidden: Boolean) {
        this.hidden = hidden
        applyFlag(hidden)
    }

    override fun isPillHidden(): Boolean = hidden

    override fun longPressHandle(x: Float, y: Float) {
        if (replaying) return
        replaying = true
        // Launcher picks its input consumer on ACTION_DOWN from the SystemUI state at that
        // moment, and refuses nav-handle long presses while home is disabled. So: lift the
        // flag, give SystemUI -> Launcher a moment to see it, replay the press, and hide the
        // pill again straight away: once the consumer is chosen, Launcher doesn't re-check
        // the flag. Launcher's own timeout (450 ms, DeviceConfig launcher/LPNH_TIMEOUT_MS,
        // which the shell isn't allowed to change) runs from the replayed DOWN.
        applyFlag(false)
        handler.postDelayed({
            val down = SystemClock.uptimeMillis()
            injectTouch(down, down, MotionEvent.ACTION_DOWN, x, y)
            if (hidden) applyFlag(true)
            handler.postDelayed({
                injectTouch(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y)
                replaying = false
            }, RELEASE_AFTER_MS)
        }, PROPAGATE_MS)
    }

    override fun tap(x: Float, y: Float, delayMs: Long) {
        handler.postDelayed({
            val down = SystemClock.uptimeMillis()
            injectTouch(down, down, MotionEvent.ACTION_DOWN, x, y)
            injectTouch(down, down + 30, MotionEvent.ACTION_UP, x, y)
        }, delayMs)
    }

    override fun grantOverlayPermission(packageName: String): Boolean = runCatching {
        ProcessBuilder("appops", "set", packageName, "SYSTEM_ALERT_WINDOW", "allow")
            .redirectErrorStream(true).start().waitFor() == 0
    }.getOrDefault(false)

    override fun sampleLuminance(left: Int, top: Int, right: Int, bottom: Int): Float = try {
        val start = SystemClock.uptimeMillis()
        capture.luminance(Rect(left, top, right, bottom)).also {
            Log.d(TAG, "sampleLuminance=$it in ${SystemClock.uptimeMillis() - start} ms")
        }
    } catch (t: Throwable) {
        Log.w(TAG, "sampleLuminance failed", t)
        -1f
    }

    private fun applyFlag(hide: Boolean) {
        disable.invoke(statusBar, if (hide) DISABLE_HOME else 0, token, SHELL_PACKAGE)
    }

    private fun injectTouch(downTime: Long, eventTime: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            inject.invoke(input, event, INJECT_MODE_ASYNC)
        } catch (t: Throwable) {
            Log.w(TAG, "inject failed", t)
        } finally {
            event.recycle()
        }
    }

    private fun service(name: String, iface: String): Any {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java).invoke(null, name) as IBinder
        return Class.forName("$iface\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
    }

    /**
     * Captures part of the screen through IWindowManager.captureDisplay, which the shell may
     * call because it holds READ_FRAME_BUFFER. Written against Android 17's
     * android.window.ScreenCaptureInternal; Launcher samples the same area to choose its
     * pill colour.
     */
    private class DisplayCapture(private val windowManager: Any) {
        private val internal = Class.forName("android.window.ScreenCaptureInternal")
        private val argsBuilder = Class.forName("android.window.ScreenCaptureInternal\$CaptureArgs\$Builder")
        private val argsClass = Class.forName("android.window.ScreenCaptureInternal\$CaptureArgs")
        private val listenerClass = Class.forName("android.window.ScreenCaptureInternal\$ScreenCaptureListener")
        private val syncListenerClass =
            Class.forName("android.window.ScreenCaptureInternal\$SynchronousScreenCaptureListener")
        private val bufferClass = Class.forName("android.window.ScreenCaptureInternal\$ScreenshotHardwareBuffer")
        private val captureDisplay = windowManager.javaClass.getMethod(
            "captureDisplay", Int::class.java, argsClass, listenerClass,
        )

        fun luminance(crop: Rect): Float {
            val builder = argsBuilder.getConstructor().newInstance()
            argsBuilder.getMethod("setSourceCrop", Rect::class.java).invoke(builder, crop)
            val args = argsBuilder.getMethod("build").invoke(builder)
            val listener = internal.getMethod("createSyncCaptureListener").invoke(null)
            captureDisplay.invoke(windowManager, DEFAULT_DISPLAY, args, listener)
            val buffer = syncListenerClass.getMethod("getBuffer").invoke(listener)
                ?: error("no buffer")
            try {
                val hardware = bufferClass.getMethod("asBitmap").invoke(buffer) as Bitmap
                val bitmap = hardware.copy(Bitmap.Config.ARGB_8888, false)
                hardware.recycle()
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                bitmap.recycle()
                // Rec. 709 luma on the encoded values, as SurfaceFlinger's region sampling
                // (and so Launcher's pill colour) uses.
                return pixels.map {
                    (0.2126f * Color.red(it) + 0.7152f * Color.green(it) + 0.0722f * Color.blue(it)) / 255f
                }.average().toFloat()
            } finally {
                bufferClass.getMethod("close").invoke(buffer)
            }
        }
    }

    private companion object {
        const val TAG = "HideNavShell"
        const val DEFAULT_DISPLAY = 0
        const val SHELL_PACKAGE = "com.android.shell"
        /** StatusBarManager.DISABLE_HOME: Launcher fades the stashed handle out while it's set. */
        const val DISABLE_HOME = 0x00200000
        /** InputManager.INJECT_INPUT_EVENT_MODE_ASYNC */
        const val INJECT_MODE_ASYNC = 0
        /**
         * Head start for the lifted flag to reach Launcher before the replayed DOWN does.
         * On a Pixel 9 (Android 17) even 0 ms worked, but only by winning that race; this
         * keeps a margin. It's also roughly how long the pill flashes.
         */
        const val PROPAGATE_MS = 25L
        const val RELEASE_AFTER_MS = 650L
    }
}
