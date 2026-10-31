package com.khoryz666.waypoint.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Material's "attach_file" icon. */
val AttachIcon: ImageVector = ImageVector.Builder("Attach", 24.dp, 24.dp, 24f, 24f)
    .addPath(
        addPathNodes("M16.5,6v11.5c0,2.21 -1.79,4 -4,4s-4,-1.79 -4,-4V5c0,-1.38 1.12,-2.5 2.5,-2.5s2.5,1.12 2.5,2.5v10.5c0,0.55 -0.45,1 -1,1s-1,-0.45 -1,-1V6H10v9.5c0,1.38 1.12,2.5 2.5,2.5s2.5,-1.12 2.5,-2.5V5c0,-2.21 -1.79,-4 -4,-4S7,2.79 7,5v12.5c0,3.04 2.46,5.5 5.5,5.5s5.5,-2.46 5.5,-5.5V6h-1.5z"),
        fill = SolidColor(Color.Black),
    )
    .build()

/** Material's "photo_camera" icon. */
val CameraIcon: ImageVector = ImageVector.Builder("Camera", 24.dp, 24.dp, 24f, 24f)
    .addPath(
        addPathNodes("M12,9a3.2,3.2 0,1 0,0 6.4a3.2,3.2 0,1 0,0 -6.4zM9,2L7.17,4H4c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.9,-2 -2,-2h-3.17L15,2H9zM12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5z"),
        fill = SolidColor(Color.Black),
    )
    .build()

/** Material's "dark_mode" icon, for the daily close. */
val MoonIcon: ImageVector = ImageVector.Builder("Moon", 24.dp, 24.dp, 24f, 24f)
    .addPath(
        addPathNodes("M12,3c-4.97,0 -9,4.03 -9,9s4.03,9 9,9s9,-4.03 9,-9c0,-0.46 -0.04,-0.92 -0.1,-1.36c-0.98,1.37 -2.58,2.26 -4.4,2.26c-2.98,0 -5.4,-2.42 -5.4,-5.4c0,-1.81 0.89,-3.42 2.26,-4.4C12.92,3.04 12.46,3 12,3z"),
        fill = SolidColor(Color.Black),
    )
    .build()
