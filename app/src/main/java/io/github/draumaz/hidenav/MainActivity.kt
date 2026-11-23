package io.github.draumaz.hidenav

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.draumaz.hidenav.ui.HomeScreen
import io.github.draumaz.hidenav.ui.theme.HideNavTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        window.isNavigationBarContrastEnforced = false
        super.onCreate(savedInstanceState)
        val app = application as HideNavApp
        setContent {
            HideNavTheme { HomeScreen(app.bridge, app.prefs) }
        }
    }

    override fun onResume() {
        super.onResume()
        // Shizuku may have been started or granted while we were away.
        (application as HideNavApp).bridge.refresh()
    }
}
