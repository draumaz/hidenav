package io.github.draumaz.hidenav

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the user asked for. [ShellBridge] makes the device match it whenever Shizuku is up. */
data class Settings(
    val hidePill: Boolean = false,
    val keepCircleToSearch: Boolean = false,
    /** Like an iPhone: keep the pill on the home and lock screen, hide it inside apps. */
    val showOnHome: Boolean = false,
)

class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    fun update(transform: (Settings) -> Settings) {
        val next = transform(_settings.value)
        prefs.edit()
            .putBoolean(KEY_HIDE, next.hidePill)
            .putBoolean(KEY_CTS, next.keepCircleToSearch)
            .putBoolean(KEY_SHOW_ON_HOME, next.showOnHome)
            .apply()
        _settings.value = next
    }

    private fun read() = Settings(
        hidePill = prefs.getBoolean(KEY_HIDE, false),
        keepCircleToSearch = prefs.getBoolean(KEY_CTS, false),
        showOnHome = prefs.getBoolean(KEY_SHOW_ON_HOME, false),
    )

    private companion object {
        const val KEY_HIDE = "hide_pill"
        const val KEY_CTS = "keep_circle_to_search"
        const val KEY_SHOW_ON_HOME = "show_on_home"
    }
}
