package com.example.antiwispr.cloud

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.example.antiwispr.AppLog
import com.google.android.gms.tasks.Task
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Google sign-in via Credential Manager → Firebase Auth. Needs an ACTIVITY context
 * (Credential Manager shows a bottom sheet). Call from a coroutine (UI scope).
 */
object CloudAuth {

    suspend fun signIn(activityContext: Context): Result<String> {
        FirebaseBootstrap.ensureInitialized(activityContext)
        if (!FirebaseBootstrap.available) {
            return Result.failure(IllegalStateException(
                "Cloud isn't configured — add google-services.json to app/src/main/assets and rebuild."))
        }
        val webClientId = FirebaseBootstrap.webClientId
            ?: return Result.failure(IllegalStateException(
                "No web client id in google-services.json — enable the Google provider in Firebase and re-download it."))
        return try {
            val credentialManager = CredentialManager.create(activityContext)
            val option = GetGoogleIdOption.Builder()
                .setServerClientId(webClientId)
                .setFilterByAuthorizedAccounts(false)
                .build()
            val response = credentialManager.getCredential(
                activityContext,
                GetCredentialRequest.Builder().addCredentialOption(option).build(),
            )
            val credential = response.credential
            if (credential !is CustomCredential ||
                credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                return Result.failure(IllegalStateException("Unexpected credential type ${credential.type}"))
            }
            val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
            val auth = FirebaseAuth.getInstance()
                .signInWithCredential(GoogleAuthProvider.getCredential(idToken, null))
                .awaitTask()
            val email = auth.user?.email.orEmpty()
            AppLog.i("[cloud] signed in as $email")
            Result.success(email)
        } catch (e: Exception) {
            AppLog.w("[cloud] sign-in failed/cancelled: ${e.message}")
            Result.failure(e)
        }
    }
}

private suspend fun Task<AuthResult>.awaitTask(): AuthResult =
    suspendCancellableCoroutine { cont ->
        addOnCompleteListener { task ->
            if (task.isSuccessful) cont.resume(task.result)
            else cont.resumeWithException(task.exception ?: RuntimeException("sign-in task failed"))
        }
    }
