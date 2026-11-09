package io.github.draumaz.hidenav

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings as AndroidSettings
import android.util.Log
import io.github.draumaz.hidenav.overlay.TouchZone
import io.github.draumaz.hidenav.shell.HideNavUserService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

enum class ShizukuState { NOT_RUNNING, NEEDS_PERMISSION, CONNECTING, READY, UNSUPPORTED }

/**
 * Owns the connection to the shell-side [HideNavUserService] and keeps the device in sync
 * with [Prefs]: the disable flag lives in the service, the touch zone lives in this process.
 */
class ShellBridge(private val context: Context, private val prefs: Prefs) {

    private val _state = MutableStateFlow(ShizukuState.NOT_RUNNING)
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    private val _service = MutableStateFlow<IHideNavService?>(null)
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val touchZone = TouchZone(context) { _service.value }

    /** From the shell service, while "show outside apps" is on. */
    private val appInFront = MutableStateFlow(true)
    private val foregroundListener = object : IForegroundListener.Stub() {
        override fun onAppInFrontChanged(appInFront: Boolean) {
            main.post { this@ShellBridge.appInFront.value = appInFront }
        }
    }
    /** The service [foregroundListener] is registered with, if any. */
    private var listeningTo: IHideNavService? = null
    /** Handed to the service so it can tell when this process dies and restart it. */
    private val appToken = android.os.Binder()
    /** The service that's watching [appToken], if any. */
    private var watchedBy: IHideNavService? = null
    /** Whether the pill should be hidden right now; checked again once [TouchZone.coverPill] is done. */
    private var wantHidden = false
    private var covering = false

    private val args = Shizuku.UserServiceArgs(ComponentName(context, HideNavUserService::class.java))
        // Daemon: the service (and with it the hidden pill) outlives this app's process.
        .daemon(true)
        .processNameSuffix("shell")
        .tag("hidenav")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) return
            _service.value = IHideNavService.Stub.asInterface(binder)
            _state.value = ShizukuState.READY
        }

        override fun onServiceDisconnected(name: ComponentName) {
            _service.value = null
            refresh()
        }
    }

    fun start() {
        Shizuku.addBinderReceivedListenerSticky { main.post(::refresh) }
        Shizuku.addBinderDeadListener {
            main.post {
                _service.value = null
                refresh()
            }
        }
        Shizuku.addRequestPermissionResultListener { _, result ->
            if (result == PackageManager.PERMISSION_GRANTED) main.post(::refresh)
        }
        scope.launch {
            combine(_service, prefs.settings, appInFront) { service, settings, inFront -> Triple(service, settings, inFront) }
                .collect { (service, settings, appInFront) -> apply(service, settings, appInFront) }
        }
    }

    fun requestPermission() {
        if (Shizuku.pingBinder() && !Shizuku.isPreV11()) Shizuku.requestPermission(REQUEST_CODE)
    }

    /** Re-reads Shizuku's state; called on binder events and whenever the UI resumes. */
    fun refresh() {
        _state.value = when {
            !Shizuku.pingBinder() -> ShizukuState.NOT_RUNNING
            Shizuku.isPreV11() -> ShizukuState.UNSUPPORTED
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> ShizukuState.NEEDS_PERMISSION
            _service.value != null -> ShizukuState.READY
            else -> {
                runCatching { Shizuku.bindUserService(args, connection) }
                    .onFailure { Log.w(TAG, "bindUserService failed", it) }
                ShizukuState.CONNECTING
            }
        }
    }

    private fun apply(service: IHideNavService?, settings: Settings, appInFront: Boolean) {
        if (service == null) {
            listeningTo = null
            watchedBy = null
            wantHidden = false
            touchZone.hide()
            return
        }
        val showOnHome = settings.hidePill && settings.showOnHome
        // This process only matters while it has the touch zone up. The overlay keeps it at
        // perceptible priority, but the lock screen hides the overlay, and then Android
        // kills it like any cached app. Have the service bring it back after unlock.
        val needed = showOnHome || (settings.hidePill && settings.keepCircleToSearch)
        if (needed != (watchedBy === service)) {
            try {
                service.watchApp(if (needed) appToken else null, context.packageName)
                watchedBy = if (needed) service else null
            } catch (e: Exception) {
                Log.w(TAG, "watchApp failed", e)
            }
        }
        if (showOnHome != (listeningTo === service)) {
            try {
                service.setForegroundListener(if (showOnHome) foregroundListener else null)
                listeningTo = if (showOnHome) service else null
                // Until the service reports, treat it like before: hidden everywhere.
                if (!showOnHome) this.appInFront.value = true
            } catch (e: Exception) {
                Log.w(TAG, "setForegroundListener failed", e)
            }
        }
        val hide = settings.hidePill && (!showOnHome || appInFront)
        wantHidden = hide

        // The strip draws the hand-over animation, and with Circle to Search kept it takes
        // touches too, but only while the pill (and Launcher's own long press) is gone.
        touchZone.interactive = hide && settings.keepCircleToSearch
        if ((showOnHome || (settings.hidePill && settings.keepCircleToSearch)) && ensureOverlayPermission(service)) {
            touchZone.show()
        } else {
            touchZone.hide()
        }

        if (!hide) touchZone.uncover()
        val hidden = try {
            service.isPillHidden
        } catch (e: Exception) {
            Log.w(TAG, "isPillHidden failed", e)
            return
        }
        when {
            hide == hidden -> Unit
            // Going into an app: fade the pill out the way a Circle to Search hold ends.
            hide && showOnHome -> if (!covering) {
                covering = true
                touchZone.coverPill {
                    covering = false
                    if (wantHidden && _service.value === service) setPillHidden(service, true)
                }
            }
            hide -> setPillHidden(service, true)
            else -> setPillHidden(service, false)
        }
    }

    private fun setPillHidden(service: IHideNavService, hidden: Boolean) {
        try {
            service.setPillHidden(hidden)
        } catch (e: Exception) {
            Log.w(TAG, "setPillHidden failed", e)
        }
    }

    private fun ensureOverlayPermission(service: IHideNavService): Boolean {
        if (AndroidSettings.canDrawOverlays(context)) return true
        runCatching { service.grantOverlayPermission(context.packageName) }
        return AndroidSettings.canDrawOverlays(context)
    }

    private companion object {
        const val TAG = "HideNav"
        const val REQUEST_CODE = 7
    }
}
