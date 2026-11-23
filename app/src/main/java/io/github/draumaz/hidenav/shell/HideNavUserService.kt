package io.github.draumaz.hidenav.shell

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import io.github.draumaz.hidenav.IForegroundListener
import io.github.draumaz.hidenav.IHideNavService
import java.lang.reflect.Proxy
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
    private val windowManager: Any by lazy { service("window", "android.view.IWindowManager") }
    private val activityTasks: Any by lazy { service("activity_task", "android.app.IActivityTaskManager") }
    private val capture by lazy { DisplayCapture(windowManager) }
    // Looked up once: Class.getMethods() copies the whole (large) method table on every call,
    // and these run on every task change and keyguard event.
    private val isKeyguardLockedMethod by lazy { method(windowManager, "isKeyguardLocked") }
    private val focusedRootTaskInfoMethod by lazy { method(activityTasks, "getFocusedRootTaskInfo") }
    private val topActivityTypeField by lazy {
        Class.forName("android.app.TaskInfo").getField("topActivityType")
    }

    // Only touched on [handler]'s thread.
    private var foregroundListener: IForegroundListener? = null
    private var showOnHome = false
    private var reportedAppInFront: Boolean? = null
    private var watching = false
    private val recheck = Runnable {
        checkForeground()
        if (appDied) scheduleRespawn()
    }

    // The app process, from [watchApp]. Only touched on [handler]'s thread.
    private var appToken: IBinder? = null
    private var appDeath: IBinder.DeathRecipient? = null
    private var appPackage: String? = null
    private var appAttachedAt = 0L
    /** The watched app's process died and hasn't re-attached via [watchApp] yet. */
    private var appDied = false
    private var respawnDelayMs = RESPAWN_MIN_MS
    private val respawn = Runnable { respawnApp() }
    /** Fires if a wake succeeded at the shell level but the app never called [watchApp] back. */
    private val attachTimeout = Runnable { onAttachTimeout() }

    init {
        killOtherInstances()
    }

    /**
     * Kills any older copy of this service. Each Shizuku server starts its own daemon, so if
     * Shizuku ends up running twice (seen with Nightzuku after an OTA), the app talks to
     * one daemon while the other keeps its own DISABLE_HOME record. The Circle to Search
     * replay only lifts this daemon's record, so Launcher keeps refusing the long press.
     * Killing the stale copy drops its binder token and with it the record. The app just
     * bound this instance, so the newest one wins.
     */
    private fun killOtherInstances() {
        val myPid = android.os.Process.myPid()
        val myName = runCatching { processName(myPid) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return
        java.io.File("/proc").listFiles()?.forEach { dir ->
            val pid = dir.name.toIntOrNull() ?: return@forEach
            if (pid == myPid) return@forEach
            val name = runCatching { processName(pid) }.getOrNull() ?: return@forEach
            if (name != myName) return@forEach
            Log.w(TAG, "killing stale $name (pid $pid)")
            android.os.Process.killProcess(pid)
        }
    }

    private fun processName(pid: Int): String =
        java.io.File("/proc/$pid/cmdline").readText().substringBefore('\u0000')

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

    override fun grantOverlayPermission(packageName: String): Boolean =
        runShell("appops", "set", packageName, "SYSTEM_ALERT_WINDOW", "allow")

    override fun grantBackgroundExemption(packageName: String): Boolean {
        val idle = runShell("cmd", "deviceidle", "whitelist", "+$packageName")
        val anyBg = runShell("appops", "set", packageName, "RUN_ANY_IN_BACKGROUND", "allow")
        val bg = runShell("appops", "set", packageName, "RUN_IN_BACKGROUND", "allow")
        return idle && anyBg && bg
    }

    override fun setShowOnHome(enabled: Boolean) {
        handler.post {
            showOnHome = enabled
            if (enabled) startWatching()
        }
    }

    private fun runShell(vararg cmd: String): Boolean = runCatching {
        ProcessBuilder(*cmd).redirectErrorStream(true).start().waitFor() == 0
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

    override fun setForegroundListener(listener: IForegroundListener?) {
        handler.post {
            foregroundListener = listener
            reportedAppInFront = null
            if (listener != null) {
                startWatching()
                checkForeground()
            }
        }
    }

    override fun watchApp(appToken: IBinder?, packageName: String?) {
        handler.post {
            val old = this.appToken
            val oldDeath = appDeath
            if (old != null && oldDeath != null) runCatching { old.unlinkToDeath(oldDeath, 0) }
            this.appToken = null
            appDeath = null
            appDied = false
            handler.removeCallbacks(respawn)
            handler.removeCallbacks(attachTimeout)
            if (appToken == null || packageName == null) {
                appPackage = null
                return@post
            }
            appPackage = packageName
            appAttachedAt = SystemClock.elapsedRealtime()
            val death = IBinder.DeathRecipient { handler.post { onAppDied(appToken) } }
            this.appToken = appToken
            appDeath = death
            startWatching()
            try {
                appToken.linkToDeath(death, 0)
            } catch (e: RemoteException) {
                onAppDied(appToken)
            }
        }
    }

    private fun onAppDied(token: IBinder) {
        if (appToken !== token) return
        appToken = null
        appDeath = null
        appDied = true
        // A process that ran a while was killed for memory; one that died straight after a
        // restart is probably crashing, so back off instead of restarting it in a loop.
        respawnDelayMs = if (SystemClock.elapsedRealtime() - appAttachedAt > STABLE_MS) {
            RESPAWN_MIN_MS
        } else {
            (respawnDelayMs * 2).coerceAtMost(RESPAWN_MAX_MS)
        }
        Log.d(TAG, "app died; restarting it in $respawnDelayMs ms once unlocked")
        scheduleRespawn()
    }

    /** Restarts the app after [respawnDelayMs], or waits for the next unlock (via [recheck]). */
    private fun scheduleRespawn() {
        if (handler.hasCallbacks(respawn) || handler.hasCallbacks(attachTimeout)) return
        val locked = runCatching { isKeyguardLocked() }.getOrDefault(false)
        if (!locked) handler.postDelayed(respawn, respawnDelayMs)
    }

    private fun respawnApp() {
        val pkg = appPackage ?: return
        if (!appDied || runCatching { isKeyguardLocked() }.getOrDefault(false)) return
        if (isForceStopped(pkg)) {
            // The user stopped it on purpose; it registers again when they open it.
            Log.d(TAG, "app was force-stopped; not restarting it")
            appDied = false
            appPackage = null
            return
        }
        // Start the foreground service directly so the respawned process immediately gets
        // perceptible priority instead of starting cached. Fall back to waking the Shizuku
        // provider if that fails.
        val ok = runShell("am", "start-foreground-service", "-n", "$pkg/$KEEP_ALIVE_SERVICE") ||
            runShell("content", "call", "--uri", "content://$pkg.shizuku", "--method", "hidenav_wake")
        Log.d(TAG, "restarted app: $ok")
        if (ok) {
            // Keep appDied = true until watchApp() confirms the process actually reconnected.
            handler.postDelayed(attachTimeout, ATTACH_TIMEOUT_MS)
        } else {
            respawnDelayMs = (respawnDelayMs * 2).coerceAtMost(RESPAWN_MAX_MS)
            scheduleRespawn()
        }
    }

    private fun onAttachTimeout() {
        if (!appDied) return
        Log.w(TAG, "app did not re-attach within $ATTACH_TIMEOUT_MS ms; retrying")
        respawnDelayMs = (respawnDelayMs * 2).coerceAtMost(RESPAWN_MAX_MS)
        scheduleRespawn()
    }

    private fun isForceStopped(pkg: String): Boolean = runCatching {
        val pm = service("package", "android.content.pm.IPackageManager")
        val userId = android.os.UserHandle::class.java.getMethod("myUserId").invoke(null) as Int
        pm.javaClass.getMethod("isPackageStoppedForUser", String::class.java, Int::class.java)
            .invoke(pm, pkg, userId) as Boolean
    }.onFailure { Log.w(TAG, "isPackageStoppedForUser failed", it) }.getOrDefault(false)

    /**
     * Listens for task changes (an app opening, going home) and the keyguard locking or
     * unlocking. Both callbacks just trigger a fresh look at the current state, so the
     * listeners don't need to decode any arguments. They stay registered for the life of
     * this process; with no [foregroundListener] they cost one cheap check per event.
     */
    private fun startWatching() {
        if (watching) return
        watching = true
        val onEvent: () -> Unit = {
            handler.removeCallbacks(recheck)
            handler.post(recheck)
        }
        runCatching {
            method(activityTasks, "registerTaskStackListener")
                .invoke(activityTasks, eventListener("android.app.ITaskStackListener", onEvent))
        }.onFailure { Log.w(TAG, "registerTaskStackListener failed", it) }
        runCatching {
            method(windowManager, "addKeyguardLockedStateListener").invoke(
                windowManager,
                eventListener("com.android.internal.policy.IKeyguardLockedStateListener", onEvent),
            )
        }.onFailure { Log.w(TAG, "addKeyguardLockedStateListener failed", it) }
    }

    private fun checkForeground() {
        val listener = foregroundListener
        if (listener == null && !showOnHome) return
        val appInFront = try {
            !isKeyguardLocked() && !isHomeInFront()
        } catch (t: Throwable) {
            Log.w(TAG, "foreground check failed", t)
            return
        }
        if (appInFront == reportedAppInFront) return
        reportedAppInFront = appInFront
        Log.d(TAG, "appInFront=$appInFront")
        if (listener != null) {
            try {
                listener.onAppInFrontChanged(appInFront)
                return
            } catch (e: RemoteException) {
                // The app's process is gone; it registers again when it comes back.
                foregroundListener = null
            }
        }
        // App process is gone while "show outside apps" is on: toggle the flag directly
        // (without the fade animation) so the pill never gets stuck in the wrong state.
        if (showOnHome && hidden != appInFront) {
            hidden = appInFront
            runCatching { applyFlag(appInFront) }
                .onFailure { Log.w(TAG, "fallback applyFlag failed", it) }
        }
    }

    private fun isKeyguardLocked(): Boolean =
        isKeyguardLockedMethod.invoke(windowManager) as Boolean

    /** Whether the focused task is Launcher's home or Recents, rather than an app. */
    private fun isHomeInFront(): Boolean {
        val info = focusedRootTaskInfoMethod.invoke(activityTasks) ?: return false
        val type = topActivityTypeField.getInt(info)
        return type == ACTIVITY_TYPE_HOME || type == ACTIVITY_TYPE_RECENTS
    }

    /**
     * A stand-in for a hidden oneway AIDL listener interface: a plain Binder that runs
     * [onEvent] for every call, wrapped in a proxy of the interface so the hidden
     * register method accepts it (its Stub.Proxy only needs asBinder()).
     */
    private fun eventListener(iface: String, onEvent: () -> Unit): Any {
        val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code in FIRST_CALL_TRANSACTION..LAST_CALL_TRANSACTION) {
                    onEvent()
                    return true
                }
                return super.onTransact(code, data, reply, flags)
            }
        }
        val type = Class.forName(iface)
        return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, m, args ->
            when (m.name) {
                "asBinder" -> binder
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "HideNav $iface"
                else -> null
            }
        }
    }

    private fun method(target: Any, name: String) = target.javaClass.methods.first { it.name == name }

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
        private val newBuilder = argsBuilder.getConstructor()
        private val setSourceCrop = argsBuilder.getMethod("setSourceCrop", Rect::class.java)
        private val build = argsBuilder.getMethod("build")
        private val createSyncCaptureListener = internal.getMethod("createSyncCaptureListener")
        private val getBuffer = syncListenerClass.getMethod("getBuffer")
        private val asBitmap = bufferClass.getMethod("asBitmap")
        private val close = bufferClass.getMethod("close")

        fun luminance(crop: Rect): Float {
            val builder = newBuilder.newInstance()
            setSourceCrop.invoke(builder, crop)
            val args = build.invoke(builder)
            val listener = createSyncCaptureListener.invoke(null)
            captureDisplay.invoke(windowManager, DEFAULT_DISPLAY, args, listener)
            val buffer = getBuffer.invoke(listener) ?: error("no buffer")
            try {
                val hardware = asBitmap.invoke(buffer) as Bitmap
                val bitmap = hardware.copy(Bitmap.Config.ARGB_8888, false)
                hardware.recycle()
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                bitmap.recycle()
                // Rec. 709 luma on the encoded values, as SurfaceFlinger's region sampling
                // (and so Launcher's pill colour) uses.
                // A plain loop: map/average would box every pixel.
                if (pixels.isEmpty()) error("empty capture")
                var sum = 0.0
                for (px in pixels) {
                    sum += 0.2126f * Color.red(px) + 0.7152f * Color.green(px) + 0.0722f * Color.blue(px)
                }
                return (sum / pixels.size / 255.0).toFloat()
            } finally {
                close.invoke(buffer)
            }
        }
    }

    private companion object {
        const val TAG = "HideNavShell"
        const val DEFAULT_DISPLAY = 0
        /** WindowConfiguration.ACTIVITY_TYPE_HOME and _RECENTS. */
        const val ACTIVITY_TYPE_HOME = 2
        const val ACTIVITY_TYPE_RECENTS = 3
        const val SHELL_PACKAGE = "com.android.shell"
        const val KEEP_ALIVE_SERVICE = "io.github.draumaz.hidenav.KeepAliveService"
        const val RESPAWN_MIN_MS = 500L
        const val RESPAWN_MAX_MS = 5 * 60_000L
        /** How long to wait for a respawned app to call [watchApp] before retrying. */
        const val ATTACH_TIMEOUT_MS = 10_000L
        /** An app process that lived this long wasn't crashing. */
        const val STABLE_MS = 60_000L
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
