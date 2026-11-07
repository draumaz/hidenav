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
            combine(_service, prefs.settings) { service, settings -> service to settings }
                .collect { (service, settings) -> apply(service, settings) }
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

    private fun apply(service: IHideNavService?, settings: Settings) {
        if (service == null) {
            touchZone.hide()
            return
        }
        try {
            if (service.isPillHidden != settings.hidePill) service.setPillHidden(settings.hidePill)
        } catch (e: Exception) {
            Log.w(TAG, "setPillHidden failed", e)
        }
        // The touch zone only matters while the pill (and Launcher's own long press) is gone.
        if (settings.hidePill && settings.keepCircleToSearch && ensureOverlayPermission(service)) {
            touchZone.show()
        } else {
            touchZone.hide()
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
