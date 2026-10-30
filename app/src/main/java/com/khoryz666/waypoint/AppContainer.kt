package com.khoryz666.waypoint

import android.content.Context

/** The app's long-lived objects, created on first use. The only place dependencies are wired. */
class AppContainer(context: Context) {
    private val app = context.applicationContext
}
