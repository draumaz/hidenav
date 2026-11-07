package io.github.draumaz.hidenav

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.draumaz.hidenav.ui.HomeScreen
import io.github.draumaz.hidenav.ui.theme.HideNavTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
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
