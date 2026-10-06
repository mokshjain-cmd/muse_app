package com.phonebridge.google

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Google access for Gmail and Calendar through Google Play services'
 * AuthorizationClient. Google matches this app to the Android OAuth client by
 * package name (com.phonebridge) and signing certificate SHA-1, so no client id
 * is embedded here.
 */
class GoogleAuth(context: Context) {

    class NeedsSignIn : Exception("Not signed in to Google. Open Phone Bridge and tap Sign in with Google.")

    private val app = context.applicationContext
    private val client = Identity.getAuthorizationClient(app)
    private val prefs = app.getSharedPreferences("google", Context.MODE_PRIVATE)

    /** True once the user has granted access (the app may still need to refresh the token). */
    var connected: Boolean
        get() = prefs.getBoolean("connected", false)
        private set(v) = prefs.edit { putBoolean("connected", v) }

    private val request = AuthorizationRequest.builder()
        .setRequestedScopes(SCOPES.map(::Scope))
        .build()

    /**
     * Starts sign-in. Returns the consent screen to launch, or null if access
     * was already granted (then [connected] is true).
     */
    suspend fun begin(): PendingIntent? {
        val result = authorize()
        if (result.hasResolution()) return result.pendingIntent
        connected = result.accessToken != null
        return null
    }

    /** Completes sign-in with the consent screen's result. */
    fun finish(data: Intent?): Boolean {
        val result = runCatching { client.getAuthorizationResultFromIntent(data) }.getOrNull()
        connected = result?.accessToken != null
        return connected
    }

    /** A fresh access token, obtained silently. Throws [NeedsSignIn] if consent is needed. */
    suspend fun token(): String {
        val result = authorize()
        val token = result.accessToken
        if (result.hasResolution() || token == null) {
            connected = false
            throw NeedsSignIn()
        }
        return token
    }

    fun disconnect() {
        connected = false
    }

    private suspend fun authorize(): AuthorizationResult = suspendCancellableCoroutine { cont ->
        client.authorize(request)
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    companion object {
        val SCOPES = listOf(
            "https://www.googleapis.com/auth/gmail.readonly",
            "https://www.googleapis.com/auth/gmail.compose",   // drafts and send
            "https://www.googleapis.com/auth/calendar.events",
        )
    }
}
