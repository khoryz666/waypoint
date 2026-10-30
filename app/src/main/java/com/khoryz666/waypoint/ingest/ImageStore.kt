package com.khoryz666.waypoint.ingest

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.File
import java.util.UUID

/**
 * Copies picked or shared images into app-private storage at intake, downscaled to 2000 px on
 * the long side as JPEG 85 (spec 8.1), so nothing depends on another app's URI permission.
 * Files are deleted once their card is saved or discarded (spec 5.3).
 */
class ImageStore(private val context: Context) {
    private val dir get() = File(context.filesDir, "inbox").apply { mkdirs() }

    fun import(uri: Uri): String? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val scale = MAX_SIDE.toFloat() / maxOf(decoded.width, decoded.height)
        val bitmap = if (scale < 1f) Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true) else decoded
        val file = File(dir, "${UUID.randomUUID()}.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
        file.absolutePath
    }.getOrNull()

    fun dataUrl(path: String): String? = runCatching {
        "data:image/jpeg;base64," + Base64.encodeToString(File(path).readBytes(), Base64.NO_WRAP)
    }.getOrNull()

    fun delete(paths: List<String>) = paths.forEach { File(it).delete() }

    private companion object {
        const val MAX_SIDE = 2000
        const val QUALITY = 85
    }
}
