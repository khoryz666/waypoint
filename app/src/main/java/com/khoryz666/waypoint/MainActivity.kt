package com.khoryz666.waypoint

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.khoryz666.waypoint.ingest.Inbox
import com.khoryz666.waypoint.timer.Destination
import com.khoryz666.waypoint.timer.Notifications
import com.khoryz666.waypoint.timer.TimerService
import com.khoryz666.waypoint.ui.Locales
import com.khoryz666.waypoint.ui.WaypointNavigation
import com.khoryz666.waypoint.ui.theme.WaypointTheme
import com.khoryz666.waypoint.work.CommitSyncWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val destination = MutableStateFlow<Destination?>(null)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Locales.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val c = appContainer
        lifecycleScope.launch {
            if (c.settings.current().onboarded) {
                TimerService.start(this@MainActivity)
                c.scheduleWork()
                c.ensureDailyTarget()
                // Commit sync also runs at app open (spec 6.4).
                CommitSyncWorker.now(this@MainActivity)
            }
        }
        if (savedInstanceState == null) handle(intent)
        setContent {
            WaypointTheme {
                WaypointNavigation(c, destination)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** Share-to-app (spec 5.2) and notification taps (spec 5.5, 7.x). */
    private fun handle(intent: Intent?) {
        intent ?: return
        val c = appContainer
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                val image = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                c.inbox.value = Inbox(text = text, images = listOfNotNull(image))
                destination.value = Destination.INPUT
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val images = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                c.inbox.value = Inbox(images = images)
                destination.value = Destination.INPUT
            }
            else -> {
                val target = intent.getStringExtra(Notifications.EXTRA_DESTINATION)?.let { runCatching { Destination.valueOf(it) }.getOrNull() } ?: return
                if (target == Destination.INPUT) {
                    c.inbox.value = Inbox(
                        trackId = intent.getLongExtra(Notifications.EXTRA_TRACK, -1).takeIf { it >= 0 },
                        sessionId = intent.getLongExtra(Notifications.EXTRA_SESSION, -1).takeIf { it >= 0 },
                    )
                }
                destination.value = target
            }
        }
    }
}
