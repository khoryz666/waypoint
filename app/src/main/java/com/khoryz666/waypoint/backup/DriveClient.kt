package com.khoryz666.waypoint.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Drive said no: 401/403 means authorization was revoked or expired (spec 10.1). */
class DriveException(val code: Int, message: String) : Exception(message) {
    val unauthorized: Boolean get() = code == 401 || code == 403
}

/** Drive REST v3 over OkHttp, limited to the hidden appDataFolder (spec 9.3). */
class DriveClient(
    private val baseUrl: String = "https://www.googleapis.com",
    http: OkHttpClient = OkHttpClient(),
) {
    private val client = http.newBuilder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun list(token: String): List<BackupFile> {
        val url = "$baseUrl/drive/v3/files?spaces=appDataFolder&orderBy=createdTime%20desc&pageSize=200&fields=files(id,name,size,createdTime)"
        val body = call(token, Request.Builder().url(url).get())
        return json.decodeFromString(FileList.serializer(), body.decodeToString()).files.map {
            BackupFile(it.id, it.name, Instant.parse(it.createdTime).toEpochMilli(), it.size?.toLongOrNull() ?: 0)
        }
    }

    suspend fun upload(token: String, name: String, bytes: ByteArray): String {
        val metadata = """{"name":"$name","parents":["appDataFolder"]}""".toRequestBody("application/json; charset=UTF-8".toMediaType())
        val multipart = MultipartBody.Builder().setType("multipart/related".toMediaType())
            .addPart(metadata)
            .addPart(bytes.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        val body = call(token, Request.Builder().url("$baseUrl/upload/drive/v3/files?uploadType=multipart&fields=id").post(multipart))
        return json.decodeFromString(Created.serializer(), body.decodeToString()).id
    }

    suspend fun download(token: String, id: String): ByteArray = call(token, Request.Builder().url("$baseUrl/drive/v3/files/$id?alt=media").get())

    suspend fun delete(token: String, id: String) {
        call(token, Request.Builder().url("$baseUrl/drive/v3/files/$id").delete())
    }

    private suspend fun call(token: String, builder: Request.Builder): ByteArray = withContext(Dispatchers.IO) {
        try {
            client.newCall(builder.header("Authorization", "Bearer $token").build()).execute().use { response ->
                if (!response.isSuccessful) throw DriveException(response.code, "Drive HTTP ${response.code}")
                response.body?.bytes() ?: ByteArray(0)
            }
        } catch (e: IOException) {
            throw DriveException(0, "network: ${e.message}")
        }
    }

    @Serializable private data class FileList(val files: List<DriveFile> = emptyList())
    @Serializable private data class DriveFile(val id: String, val name: String, val size: String? = null, val createdTime: String)
    @Serializable private data class Created(val id: String)
}
