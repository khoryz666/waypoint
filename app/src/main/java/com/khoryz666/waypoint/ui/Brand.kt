package com.khoryz666.waypoint.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.khoryz666.waypoint.BuildConfig
import com.khoryz666.waypoint.R

/** The launcher mark (whale tail over a wave) as a rounded tile, for use inside the app. */
@Composable
fun BrandMark(size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(colorResource(R.color.launcher_background))) {
        // The adaptive-icon artwork sits in the middle of a 108dp canvas; zoom it to fill the tile.
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.5f; scaleY = 1.5f })
    }
}

/** Mark plus wordmark, as a screen header. */
@Composable
fun BrandHeader(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BrandMark(40.dp)
        Text("Waypoint", style = MaterialTheme.typography.headlineSmall)
    }
}

/** Centred mark, name and version, for the bottom of Settings. */
@Composable
fun BrandFooter(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BrandMark(48.dp)
        Text("Waypoint", style = MaterialTheme.typography.titleMedium)
        Muted(BuildConfig.VERSION_NAME)
    }
}
