package com.khoryz666.waypoint.ui.tracks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.khoryz666.waypoint.AppContainer
import com.khoryz666.waypoint.R
import com.khoryz666.waypoint.data.Track
import com.khoryz666.waypoint.domain.TrackStatus
import com.khoryz666.waypoint.ui.Muted
import com.khoryz666.waypoint.ui.SectionCard
import com.khoryz666.waypoint.ui.domainLabel
import com.khoryz666.waypoint.ui.statusLabel

/** Tracks grouped by status, then domain (spec 5.1). */
@Composable
fun TracksScreen(c: AppContainer, onOpen: (Long) -> Unit, onNew: () -> Unit) {
    val tracks by c.tracks.all.collectAsState(initial = emptyList())
    val order = listOf(TrackStatus.ACTIVE, TrackStatus.DRAFT, TrackStatus.PAUSED, TrackStatus.COMPLETED, TrackStatus.ABORTED)
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_tracks)) }) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = onNew, icon = { Icon(Icons.Filled.Add, null) }, text = { Text(stringResource(R.string.new_track)) }) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (tracks.isEmpty()) item { Muted(stringResource(R.string.no_tracks)) }
            order.forEach { status ->
                val group = tracks.filter { it.status == status }.sortedWith(compareBy({ it.domain }, { it.title }))
                if (group.isNotEmpty()) item(key = status.name) {
                    SectionCard(title = statusLabel(status) + " · ${group.size}") {
                        group.forEach { TrackRow(it) { onOpen(it.id) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackRow(track: Track, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(track.title, style = MaterialTheme.typography.titleMedium)
            Muted(domainLabel(track.domain) + " · " + track.goal.lineSequence().firstOrNull().orEmpty())
            track.syncError?.let { Text(stringResource(R.string.sync_failed, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
