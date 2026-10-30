package com.khoryz666.waypoint

import android.app.Application
import android.content.Context

class WaypointApplication : Application() {
    val container by lazy { AppContainer(this) }
}

val Context.appContainer: AppContainer get() = (applicationContext as WaypointApplication).container
