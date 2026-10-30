package com.khoryz666.waypoint.backup

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPasswordOption
import androidx.credentials.PasswordCredential
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Drive access needs the user to approve it in a Google screen (first time, or after revocation). */
class NeedsAuthorization(val pendingIntent: PendingIntent?) : Exception("Google Drive authorization needed")

/**
 * Google Drive authorization with only the drive.appdata scope (spec 9.3), via the Identity
 * AuthorizationClient, and the backup passphrase in Google Password Manager (spec 9.4).
 */
class GoogleAuth(private val context: Context) {
    private val request = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DRIVE_APPDATA))).build()

    /** An access token without UI, or [NeedsAuthorization] carrying the consent screen. */
    suspend fun token(): String {
        val result = Identity.getAuthorizationClient(context).authorize(request).await()
        if (result.hasResolution()) throw NeedsAuthorization(result.pendingIntent)
        return result.accessToken ?: throw NeedsAuthorization(null)
    }

    /** Reads the token from the consent screen's result intent. */
    fun tokenFrom(data: Intent?): String? = runCatching {
        Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data).accessToken
    }.getOrNull()

    suspend fun savePassphrase(activity: Activity, passphrase: String): Boolean = runCatching {
        CredentialManager.create(activity).createCredential(activity, CreatePasswordRequest(PASSWORD_ID, passphrase))
    }.isSuccess

    suspend fun readPassphrase(activity: Activity): String? = runCatching {
        val response = CredentialManager.create(activity).getCredential(activity, GetCredentialRequest(listOf(GetPasswordOption())))
        (response.credential as? PasswordCredential)?.takeIf { it.id == PASSWORD_ID }?.password
    }.getOrNull()

    companion object {
        const val DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
        const val PASSWORD_ID = "Waypoint backup"
    }
}

private suspend fun Task<AuthorizationResult>.await(): AuthorizationResult = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
}
