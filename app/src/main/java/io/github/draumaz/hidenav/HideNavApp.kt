package io.github.draumaz.hidenav

import android.app.Application

class HideNavApp : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var bridge: ShellBridge
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        // Shizuku hands its binder to every granted app when it starts (which launches this
        // process if needed), so this is also how the settings come back after a reboot.
        bridge = ShellBridge(this, prefs).also { it.start() }
    }
}
