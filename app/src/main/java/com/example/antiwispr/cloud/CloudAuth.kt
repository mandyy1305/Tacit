package com.example.antiwispr.cloud

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.example.antiwispr.core.AppLog
import com.google.android.gms.tasks.Task
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Sign-in via Firebase Auth: Google (Credential Manager, needs an ACTIVITY context because it
 * shows a bottom sheet) or email + password. Call from a coroutine (UI scope). Firebase is the
 * backend but is never named in user-facing copy — errors here come pre-translated to product
 * language via [friendlyGoogleError] / [friendlyEmailError].
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

    suspend fun signInWithEmail(context: Context, email: String, password: String): Result<String> =
        emailAuth(context, email) { auth, addr -> auth.signInWithEmailAndPassword(addr, password) }

    suspend fun createAccountWithEmail(context: Context, email: String, password: String): Result<String> =
        emailAuth(context, email) { auth, addr -> auth.createUserWithEmailAndPassword(addr, password) }

    private suspend fun emailAuth(
        context: Context,
        email: String,
        task: (FirebaseAuth, String) -> Task<AuthResult>,
    ): Result<String> {
        FirebaseBootstrap.ensureInitialized(context)
        if (!FirebaseBootstrap.available) {
            return Result.failure(IllegalStateException(
                "Cloud isn't configured — add google-services.json to app/src/main/assets and rebuild."))
        }
        return try {
            val result = task(FirebaseAuth.getInstance(), email.trim()).awaitTask()
            val addr = result.user?.email.orEmpty()
            AppLog.i("[cloud] signed in as $addr (email)")
            Result.success(addr)
        } catch (e: Exception) {
            AppLog.w("[cloud] email sign-in failed: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun sendPasswordReset(context: Context, email: String): Result<Unit> {
        FirebaseBootstrap.ensureInitialized(context)
        if (!FirebaseBootstrap.available) return Result.failure(IllegalStateException("Cloud isn't configured."))
        return try {
            FirebaseAuth.getInstance().sendPasswordResetEmail(email.trim()).awaitDone()
            AppLog.i("[cloud] password reset sent")
            Result.success(Unit)
        } catch (e: Exception) {
            AppLog.w("[cloud] password reset failed: ${e.message}")
            Result.failure(e)
        }
    }

    /** Product-language message for a failed Google sign-in; null when the user simply cancelled
     *  the account sheet (cancelling is not an error and shows nothing). */
    fun friendlyGoogleError(e: Throwable): String? = when {
        e is GetCredentialCancellationException -> null
        e is FirebaseNetworkException -> "You're offline. Connect and try again."
        else -> "Couldn't sign in. Check that a Google account is set up on this phone, then try again."
    }

    /** Product-language message for a failed email sign-in / account creation / reset. */
    fun friendlyEmailError(e: Throwable): String = when {
        e is FirebaseNetworkException -> "You're offline. Connect and try again."
        e is FirebaseAuthException -> when (e.errorCode) {
            "ERROR_INVALID_EMAIL" -> "That email address doesn't look right."
            "ERROR_WRONG_PASSWORD", "ERROR_INVALID_CREDENTIAL", "ERROR_INVALID_LOGIN_CREDENTIALS" ->
                "Email or password is incorrect."
            "ERROR_USER_NOT_FOUND" -> "No account with that email yet. Create one instead."
            "ERROR_EMAIL_ALREADY_IN_USE" -> "That email already has an account. Sign in instead."
            "ERROR_WEAK_PASSWORD" -> "Use a password of at least 6 characters."
            "ERROR_USER_DISABLED" -> "This account has been disabled."
            "ERROR_TOO_MANY_REQUESTS" -> "Too many tries. Wait a moment, then try again."
            "ERROR_OPERATION_NOT_ALLOWED" -> "Email sign-in isn't switched on for this build yet."
            else -> "Couldn't sign in. Try again."
        }
        else -> "Couldn't sign in. Try again."
    }
}

private suspend fun Task<AuthResult>.awaitTask(): AuthResult =
    suspendCancellableCoroutine { cont ->
        addOnCompleteListener { task ->
            if (task.isSuccessful) cont.resume(task.result)
            else cont.resumeWithException(task.exception ?: RuntimeException("sign-in task failed"))
        }
    }

/** Await a Task whose result carries no payload (e.g. the password-reset Task<Void>). */
private suspend fun Task<*>.awaitDone(): Unit =
    suspendCancellableCoroutine { cont ->
        addOnCompleteListener { task ->
            if (task.isSuccessful) cont.resume(Unit)
            else cont.resumeWithException(task.exception ?: RuntimeException("task failed"))
        }
    }
