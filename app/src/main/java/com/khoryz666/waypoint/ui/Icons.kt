package com.khoryz666.waypoint.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Material's "foundation" icon; the core icon set doesn't include it. */
val FoundationIcon: ImageVector = ImageVector.Builder("Foundation", 24.dp, 24.dp, 24f, 24f)
    .addPath(
        addPathNodes("M19,12h3L12,3L2,12h3v3H3v2h2v3h2v-3h4v3h2v-3h4v3h2v-3h2v-2h-2V12zM7,15v-4.81l4,-3.6V15H7zM13,15V6.59l4,3.6V15H13z"),
        fill = SolidColor(Color.Black),
    )
    .build()
