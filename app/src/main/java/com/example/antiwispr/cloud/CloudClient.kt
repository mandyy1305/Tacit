package com.example.antiwispr.cloud

import android.content.Context
import com.example.antiwispr.AppLog
import com.example.antiwispr.StoredTranscript
import com.example.antiwispr.Transcripts
import com.example.antiwispr.VoiceNotes
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** One record as it travels over the sync API (superset of StoredTranscript). */
data class RemoteTranscript(
    val key: String,
    val path: String,
    val name: String,
    val waDate: Int,
    val seq: Int,
    val text: String,
    val summary: String,
    val chatName: String,
    val updatedAtMs: Long,
    val deletedAtMs: Long, // 0 = alive; >0 = tombstone
    val source: String = "", // transcription engine: "cloud" | "local"; "" = unknown/legacy
)

/**
 * HTTP client for the tacit-cloud Go server. Bearer token = Firebase ID token
 * (the Firebase SDK caches and refreshes it; we fetch per call on background threads).
 * Every method is BLOCKING — call from worker threads only. Failures return null
 * (callers fall back to the local path) and log the reason.
 */
object CloudClient {

    private val json = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS) // transcription of long notes takes a while
        .build()

    // OkHttp's top-level message ("Failed to connect to …") hides the reason; the cause
    // distinguishes refused (server down) from unreachable/timeout (network path broken).
    private fun Exception.detail(): String {
        val msg = message ?: javaClass.simpleName
        val why = cause?.message?.takeIf { it != msg } ?: return msg
        return "$msg ($why)"
    }

    // FirebaseAuth.getInstance() throws when no FirebaseApp exists (no google-services.json
    // in assets) — every entry point must check FirebaseBootstrap.available first.
    fun isSignedIn(): Boolean =
        FirebaseBootstrap.available && FirebaseAuth.getInstance().currentUser != null

    fun accountEmail(): String? =
        if (FirebaseBootstrap.available) FirebaseAuth.getInstance().currentUser?.email else null

    fun signOut() {
        if (FirebaseBootstrap.available) FirebaseAuth.getInstance().signOut()
    }

    /** Configured AND signed in AND a base URL is set — the gate for all cloud paths. */
    fun ready(ctx: Context): Boolean =
        FirebaseBootstrap.available && isSignedIn() && CloudPrefs.baseUrl(ctx).isNotEmpty()

    private fun bearer(): String? = try {
        val user = FirebaseAuth.getInstance().currentUser ?: return null
        Tasks.await(user.getIdToken(false), 20, TimeUnit.SECONDS).token
    } catch (e: Exception) {
        AppLog.w("[cloud] token fetch failed: ${e.detail()}")
        null
    }

    private fun request(ctx: Context, path: String): Request.Builder? {
        val base = CloudPrefs.baseUrl(ctx)
        if (base.isEmpty()) return null
        val token = bearer() ?: return null
        return Request.Builder().url("$base$path").header("Authorization", "Bearer $token")
    }

    /** GET /v1/me — sign-in smoke test; returns the account email or null. */
    fun me(ctx: Context): String? {
        val req = request(ctx, "/v1/me")?.get()?.build() ?: return null
        return try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) { AppLog.w("[cloud] /me ${resp.code}"); return null }
                JSONObject(resp.body!!.string()).optString("email").ifEmpty { null }
            }
        } catch (e: Exception) {
            AppLog.w("[cloud] /me failed: ${e.detail()}"); null
        }
    }

    /** PUT /v1/transcripts — batch upsert (records + tombstones). True on success. */
    fun pushTranscripts(ctx: Context, records: List<RemoteTranscript>): Boolean {
        if (records.isEmpty()) return true
        val arr = JSONArray()
        for (r in records) {
            arr.put(JSONObject().apply {
                put("key", r.key); put("path", r.path); put("name", r.name)
                put("waDate", r.waDate); put("seq", r.seq)
                put("text", r.text); put("summary", r.summary)
                put("chatName", r.chatName)
                put("updatedAtMs", r.updatedAtMs)
                if (r.deletedAtMs > 0) put("deletedAtMs", r.deletedAtMs)
                if (r.source.isNotEmpty()) put("source", r.source)
            })
        }
        val req = request(ctx, "/v1/transcripts")
            ?.put(arr.toString().toRequestBody(json))?.build() ?: return false
        return try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) AppLog.w("[cloud] push ${resp.code}")
                resp.isSuccessful
            }
        } catch (e: Exception) {
            AppLog.w("[cloud] push failed: ${e.detail()}"); false
        }
    }

    /** GET /v1/transcripts?since= — incremental pull; null on failure. */
    fun pullTranscripts(ctx: Context, sinceMs: Long): List<RemoteTranscript>? {
        val req = request(ctx, "/v1/transcripts?since=$sinceMs")?.get()?.build() ?: return null
        return try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) { AppLog.w("[cloud] pull ${resp.code}"); return null }
                val arr = JSONObject(resp.body!!.string()).getJSONArray("records")
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    RemoteTranscript(
                        key = o.getString("key"),
                        path = o.optString("path"),
                        name = o.optString("name"),
                        waDate = o.optInt("waDate", -1),
                        seq = o.optInt("seq", -1),
                        text = o.optString("text"),
                        summary = o.optString("summary"),
                        chatName = o.optString("chatName"),
                        updatedAtMs = o.getLong("updatedAtMs"),
                        deletedAtMs = o.optLong("deletedAtMs", 0L),
                        source = o.optString("source"),
                    )
                }
            }
        } catch (e: Exception) {
            AppLog.w("[cloud] pull failed: ${e.detail()}"); null
        }
    }

    /** POST /v1/transcribe — Sarvam via the server; null → caller falls back to local. */
    fun transcribe(ctx: Context, file: File): String? {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody("audio/ogg".toMediaType()))
            .addFormDataPart("mode", CloudSttMode.fromWire(CloudPrefs.sttMode(ctx)).wire)
            .addFormDataPart("language_code", CloudSttLanguage.fromWire(CloudPrefs.sttLanguage(ctx)).wire)
            .build()
        val req = request(ctx, "/v1/transcribe")?.post(body)?.build() ?: return null
        return try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) { AppLog.w("[cloud] transcribe ${resp.code}"); return null }
                val text = JSONObject(resp.body!!.string()).optString("text").trim()
                text.ifEmpty { null }
            }
        } catch (e: Exception) {
            AppLog.w("[cloud] transcribe failed: ${e.detail()}"); null
        }
    }

    /**
     * POST /v1/transcribe for a LONG note (>30s): starts an asynchronous Sarvam batch
     * job and returns true if the server accepted it (202). The transcript is NOT
     * returned here — it arrives later via sync + an FCM push. The note identity travels
     * along so the server can write the finished record back to this account's store.
     */
    fun startBatch(ctx: Context, file: File, durationSec: Double, chatName: String?): Boolean {
        val p = VoiceNotes.parseWhatsAppName(file.name)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody("audio/ogg".toMediaType()))
            .addFormDataPart("mode", CloudSttMode.fromWire(CloudPrefs.sttMode(ctx)).wire)
            .addFormDataPart("language_code", CloudSttLanguage.fromWire(CloudPrefs.sttLanguage(ctx)).wire)
            .addFormDataPart("duration_seconds", durationSec.toString())
            .addFormDataPart("key", Transcripts.keyFor(file))
            .addFormDataPart("path", file.absolutePath)
            .addFormDataPart("name", file.name)
            .addFormDataPart("wa_date", (p?.dateYmd ?: -1).toString())
            .addFormDataPart("seq", (p?.seq ?: -1).toString())
            .apply { if (!chatName.isNullOrBlank()) addFormDataPart("chat_name", chatName) }
            .build()
        val req = request(ctx, "/v1/transcribe")?.post(body)?.build() ?: return false
        return try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) { AppLog.w("[cloud] startBatch ${resp.code}"); return false }
                AppLog.i("[cloud] batch job started for ${file.name} (${durationSec.toInt()}s).")
                true
            }
        } catch (e: Exception) {
            AppLog.w("[cloud] startBatch failed: ${e.detail()}"); false
        }
    }

    /** POST /v1/device-token — register this device's FCM token so the server can push
     *  "transcript ready" when an async batch job completes. */
    fun registerDeviceToken(ctx: Context, token: String): Boolean {
        val payload = JSONObject().put("token", token).toString().toRequestBody(json)
        val req = request(ctx, "/v1/device-token")?.post(payload)?.build() ?: return false
        return try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) AppLog.w("[cloud] device-token ${resp.code}")
                resp.isSuccessful
            }
        } catch (e: Exception) {
            AppLog.w("[cloud] device-token failed: ${e.detail()}"); false
        }
    }

    /** POST /v1/summarize with mode=chain — one gist for a whole burst of notes. */
    fun summarizeChain(ctx: Context, text: String, count: Int, sender: String?): String? {
        val payload = JSONObject()
            .put("text", text)
            .put("mode", "chain")
            .put("count", count)
            .apply { if (!sender.isNullOrBlank()) put("sender", sender) }
            .toString().toRequestBody(json)
        val req = request(ctx, "/v1/summarize")?.post(payload)?.build() ?: return null
        return try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) { AppLog.w("[cloud] summarize(chain) ${resp.code}"); return null }
                val raw = JSONObject(resp.body!!.string()).optString("raw").trim()
                raw.ifEmpty { null }
            }
        } catch (e: Exception) {
            AppLog.w("[cloud] summarize(chain) failed: ${e.detail()}"); null
        }
    }

    /** POST /v1/ask — Q&A over retrieved notes via the server; null → caller falls back to local. */
    fun ask(ctx: Context, question: String, notesContext: String, language: String): String? {
        val payload = JSONObject()
            .put("question", question)
            .put("context", notesContext)
            .put("language", language)
            .toString().toRequestBody(json)
        val req = request(ctx, "/v1/ask")?.post(payload)?.build() ?: return null
        return try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) { AppLog.w("[cloud] ask ${resp.code}"); return null }
                val raw = JSONObject(resp.body!!.string()).optString("raw").trim()
                raw.ifEmpty { null }
            }
        } catch (e: Exception) {
            AppLog.w("[cloud] ask failed: ${e.detail()}"); null
        }
    }

    /** POST /v1/summarize — gpt-4o-mini via the server; null → caller falls back to local. */
    fun summarize(ctx: Context, text: String): String? {
        val payload = JSONObject().put("text", text).toString().toRequestBody(json)
        val req = request(ctx, "/v1/summarize")?.post(payload)?.build() ?: return null
        return try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) { AppLog.w("[cloud] summarize ${resp.code}"); return null }
                val raw = JSONObject(resp.body!!.string()).optString("raw").trim()
                raw.ifEmpty { null }
            }
        } catch (e: Exception) {
            AppLog.w("[cloud] summarize failed: ${e.detail()}"); null
        }
    }
}

/** Conversion helpers between the store record and the wire record. */
fun StoredTranscript.toRemote(): RemoteTranscript = RemoteTranscript(
    key = key, path = path, name = name, waDate = waDate, seq = seq,
    text = text, summary = summary, chatName = chatName,
    updatedAtMs = updatedAt, deletedAtMs = 0L, source = source,
)
