package io.github.draumaz.hidenav

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Exists only to start the process after boot or an update; [HideNavApp.onCreate] does the
 * rest once Shizuku's binder arrives. If Shizuku isn't running yet, its own startup will
 * deliver the binder (and restart this process) later.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}
