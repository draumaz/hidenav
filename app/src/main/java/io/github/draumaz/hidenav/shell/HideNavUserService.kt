package io.github.draumaz.hidenav.shell

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
        // flag, give SystemUI -> Launcher a moment to see it, replay the press, then hide
        // the pill again once Launcher has committed to the long press. Launcher's own
        // timeout (450 ms, DeviceConfig launcher/LPNH_TIMEOUT_MS) runs from the replayed DOWN.
        applyFlag(false)
        handler.postDelayed({
            val down = SystemClock.uptimeMillis()
            injectTouch(down, down, MotionEvent.ACTION_DOWN, x, y)
            handler.postDelayed({ if (hidden) applyFlag(true) }, RESTORE_AFTER_MS)
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

    private companion object {
        const val TAG = "HideNavShell"
        const val SHELL_PACKAGE = "com.android.shell"
        /** StatusBarManager.DISABLE_HOME: Launcher fades the stashed handle out while it's set. */
        const val DISABLE_HOME = 0x00200000
        /** InputManager.INJECT_INPUT_EVENT_MODE_ASYNC */
        const val INJECT_MODE_ASYNC = 0
        const val PROPAGATE_MS = 100L
        const val RESTORE_AFTER_MS = 250L
        const val RELEASE_AFTER_MS = 650L
    }
}
