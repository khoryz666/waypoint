package com.khoryz666.waypoint.ui.today

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.khoryz666.waypoint.R
import com.khoryz666.waypoint.data.Track
import com.khoryz666.waypoint.ingest.Inbox
import com.khoryz666.waypoint.ui.AttachIcon
import com.khoryz666.waypoint.ui.CameraIcon
import com.khoryz666.waypoint.ui.ChoiceChip
import com.khoryz666.waypoint.ui.Muted
import java.io.File

/**
 * The one input box (spec 5.2): text, up to 7 images from the Photo Picker or the camera, and a track chip
 * that defaults to Auto. Shared content and "做了什么?" taps arrive through [inbox].
 */
@Composable
fun InputBar(tracks: List<Track>, inbox: Inbox?, onSubmit: (String, List<Uri>, Long?, Long?) -> Unit) {
    var text by remember { mutableStateOf("") }
    var images by remember { mutableStateOf(emptyList<Uri>()) }
    var trackId by remember { mutableStateOf<Long?>(null) }
    var sessionId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(inbox) {
        inbox ?: return@LaunchedEffect
        if (inbox.text.isNotEmpty()) text = inbox.text
        if (inbox.images.isNotEmpty()) images = inbox.images.take(7)
        trackId = inbox.trackId
        sessionId = inbox.sessionId
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(7)) { picked -> if (picked.isNotEmpty()) images = (images + picked).take(7) }
    val context = LocalContext.current
    var shot by remember { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved -> shot?.takeIf { saved }?.let { images = (images + it).take(7) } }
    val autoLabel = stringResource(R.string.track_auto)
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ChoiceChip(
                    label = tracks.find { it.id == trackId }?.title ?: autoLabel,
                    options = listOf<Track?>(null) + tracks,
                    optionLabel = { it?.title ?: autoLabel },
                    onChoose = { trackId = it?.id },
                    selected = trackId != null,
                )
                if (images.isNotEmpty()) AssistChip(onClick = { images = emptyList() }, label = { Text(stringResource(R.string.images_attached, images.size)) })
                if (sessionId != null) Text(stringResource(R.string.answering_session), style = MaterialTheme.typography.labelSmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = images.size < 7) {
                    Icon(AttachIcon, contentDescription = stringResource(R.string.add_images))
                }
                IconButton(onClick = { cameraFile(context).also { shot = it }.let(camera::launch) }, enabled = images.size < 7) {
                    Icon(CameraIcon, contentDescription = stringResource(R.string.take_photo))
                }
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.input_hint)) }, maxLines = 5,
                )
                IconButton(
                    onClick = {
                        onSubmit(text, images, trackId, sessionId)
                        text = ""; images = emptyList(); trackId = null; sessionId = null
                    },
                    enabled = text.isNotBlank() || images.isNotEmpty(),
                ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.send)) }
            }
            if (text.isBlank() && images.isEmpty()) Muted(stringResource(R.string.money_hint))
        }
    }
}

/** A fresh cache file for the camera app to write to; ImageStore copies it on submit. */
private fun cameraFile(context: Context): Uri {
    val file = File(context.cacheDir, "camera").apply { mkdirs() }.resolve("${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
}
