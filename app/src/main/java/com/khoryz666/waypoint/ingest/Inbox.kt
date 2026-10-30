package com.khoryz666.waypoint.ingest

import android.net.Uri

/** Content handed to the input box from outside: a share intent or a notification (spec 5.2, 5.5). */
data class Inbox(
    val text: String = "",
    val images: List<Uri> = emptyList(),
    val trackId: Long? = null,
    val sessionId: Long? = null,
)
