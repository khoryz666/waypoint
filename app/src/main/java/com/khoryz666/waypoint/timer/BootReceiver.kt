package com.khoryz666.waypoint.timer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.khoryz666.waypoint.appContainer

/** Brings the timer notification and scheduled work back after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            TimerService.start(context)
            context.appContainer.scheduleWork()
        }
    }
}
