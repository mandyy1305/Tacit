package com.example.antiwispr.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.ContextCompat
import com.example.antiwispr.AppLog
import com.example.antiwispr.Chains
import com.example.antiwispr.DetectionHealth
import com.example.antiwispr.IndexHolder
import com.example.antiwispr.LlmModel
import com.example.antiwispr.ProjectionService
import com.example.antiwispr.StoredTranscript
import com.example.antiwispr.Toggles
import com.example.antiwispr.Transcripts
import com.example.antiwispr.VoiceNoteWatcher
import com.example.antiwispr.VoiceNotes
import com.example.antiwispr.WhatsAppAccessibilityService
import com.example.antiwispr.WhisperModel
import com.example.antiwispr.cloud.CloudClient
import com.example.antiwispr.cloud.CloudPrefs
import com.example.antiwispr.cloud.FirebaseBootstrap
import com.example.antiwispr.cloud.SyncEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Everything the UI needs to know about permissions, model, index, and session state. */
data class SetupStatus(
    val mic: Boolean = false,
    val overlay: Boolean = false,
    val files: Boolean = false,
    val notifications: Boolean = false,
    val accessibility: Boolean = false,
    val modelReady: Boolean = false,
    val modelDownloading: Boolean = false,
    val modelStatus: String = "",
    val modelProgress: Float? = null,
    val indexReady: Boolean = false,
    val indexBuilding: Boolean = false,
    val indexStatus: String = "",
    val indexCount: Int = 0,
    val indexProgress: Float? = null,
    val llmReady: Boolean = false,
    val llmDownloading: Boolean = false,
    val llmStatus: String = "",
    val llmProgress: Float? = null,
    val cloudConfigured: Boolean = false,
    val signedIn: Boolean = false,
    val accountEmail: String? = null,
    val syncing: Boolean = false,
    val lastSyncMs: Long = 0L,
    val cloudBaseUrl: String = "",
    val cloudTranscription: Boolean = true,
    val cloudSummaries: Boolean = true,
    val sessionActive: Boolean = false,
    val transcriptCount: Int = 0,
    val detection: String = "",
) {
    /** Notifications are deliberately optional (capture works without the FGS notice).
     *  A signed-in user can run cloud-only — local Whisper is then optional too. */
    val setupComplete: Boolean
        get() = mic && overlay && files && accessibility && (modelReady || signedIn) && indexReady
}

enum class ToggleKey { DiagnosticMode, PauseOnPlay, Orchestration, MicFallback, PauseOnMatch }

/**
 * Single state holder for the Compose UI. The pipeline singletons expose @Volatile fields,
 * not flows, so a light adaptive poller keeps [setup] fresh: 400 ms while something is
 * actively downloading/building, 2.5 s when idle. StateFlow dedupes identical values, so
 * an unchanged poll causes zero recomposition.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        const val SAMPLE_RATE = 16000
    }

    private val _setup = MutableStateFlow(SetupStatus())
    val setup = _setup.asStateFlow()

    private val _recents = MutableStateFlow<List<StoredTranscript>>(emptyList())
    val recents = _recents.asStateFlow()

    /** Keys of transcripts that belong to a chain (for list badges). */
    private val _chainKeys = MutableStateFlow<Set<String>>(emptySet())
    val chainKeys = _chainKeys.asStateFlow()

    var searchQuery by mutableStateOf("")

    /** Detail-screen selection. Store keys contain '/' and '|' — never a nav argument. */
    var selectedTranscript by mutableStateOf<StoredTranscript?>(null)

    init {
        AppLog.i("=== TACIT — every voice note, read ===")
        val ctx = app.applicationContext
        FirebaseBootstrap.ensureInitialized(ctx)
        // Parity with the old MainActivity.onCreate: warm the index, start the folder watcher.
        IndexHolder.get(ctx).loadOrBuild { AppLog.i(it); refresh() }
        VoiceNoteWatcher.ensureStarted { IndexHolder.get(ctx).loadOrBuild { AppLog.i(it); refresh() } }
        SyncEngine.requestSync(ctx) // no-op unless configured + signed in
        viewModelScope.launch {
            while (isActive) {
                refresh()
                val busy = _setup.value.modelDownloading || _setup.value.indexBuilding ||
                    _setup.value.llmDownloading
                delay(if (busy) 400L else 2500L)
            }
        }
    }

    fun refresh() {
        val ctx = getApplication<Application>().applicationContext
        val idx = IndexHolder.get(ctx)
        val snap = idx.snapshot
        _setup.value = SetupStatus(
            mic = hasRecord(ctx),
            overlay = Settings.canDrawOverlays(ctx),
            files = Environment.isExternalStorageManager(),
            notifications = notifGranted(ctx),
            accessibility = isAccessibilityEnabled(ctx),
            modelReady = WhisperModel.isReady(ctx),
            modelDownloading = WhisperModel.downloading,
            modelStatus = WhisperModel.status,
            modelProgress = if (WhisperModel.downloading && WhisperModel.totalBytes > 0)
                WhisperModel.downloadedBytes.toFloat() / WhisperModel.totalBytes else null,
            indexReady = snap != null,
            indexBuilding = idx.building,
            indexStatus = idx.status,
            indexCount = snap?.fileCount ?: 0,
            indexProgress = if (idx.building && idx.progressTotal > 0)
                idx.progressDone.toFloat() / idx.progressTotal else null,
            llmReady = LlmModel.isReady(ctx),
            llmDownloading = LlmModel.downloading,
            llmStatus = LlmModel.status,
            llmProgress = if (LlmModel.downloading && LlmModel.totalBytes > 0)
                LlmModel.downloadedBytes.toFloat() / LlmModel.totalBytes else null,
            cloudConfigured = FirebaseBootstrap.available,
            signedIn = CloudClient.isSignedIn(),
            accountEmail = CloudClient.accountEmail(),
            syncing = SyncEngine.syncing,
            lastSyncMs = CloudPrefs.lastSyncMs(ctx),
            cloudBaseUrl = CloudPrefs.baseUrl(ctx),
            cloudTranscription = CloudPrefs.cloudTranscription(ctx),
            cloudSummaries = CloudPrefs.cloudSummaries(ctx),
            sessionActive = ProjectionService.sessionActive,
            transcriptCount = Transcripts.get(ctx).count(),
            detection = DetectionHealth.summary(),
        )
        _recents.value = Transcripts.get(ctx).all(20)
        _chainKeys.value = try { Chains.memberKeys(Transcripts.get(ctx)) } catch (_: Exception) { emptySet() }
    }

    /** Called from AppRoot's resume hook — retries the watcher after all-files was granted. */
    fun onResumed() {
        val ctx = getApplication<Application>().applicationContext
        VoiceNoteWatcher.ensureStarted { IndexHolder.get(ctx).loadOrBuild { AppLog.i(it); refresh() } }
        SyncEngine.requestSync(ctx)
        refresh()
    }

    // ---- cloud account -------------------------------------------------------------

    fun onSignedIn() {
        val ctx = getApplication<Application>().applicationContext
        SyncEngine.requestSync(ctx)
        refresh()
    }

    fun signOut() {
        val ctx = getApplication<Application>().applicationContext
        CloudClient.signOut()
        CloudPrefs.resetSyncState(ctx)
        AppLog.i("[cloud] signed out.")
        refresh()
    }

    fun syncNow() {
        SyncEngine.requestSync(getApplication<Application>().applicationContext)
        refresh()
    }

    fun setCloudBaseUrl(url: String) {
        CloudPrefs.setBaseUrl(getApplication<Application>().applicationContext, url)
        refresh()
    }

    fun setCloudTranscription(v: Boolean) {
        CloudPrefs.setCloudTranscription(getApplication<Application>().applicationContext, v)
        AppLog.i("cloudTranscription = $v"); refresh()
    }

    fun setCloudSummaries(v: Boolean) {
        CloudPrefs.setCloudSummaries(getApplication<Application>().applicationContext, v)
        AppLog.i("cloudSummaries = $v"); refresh()
    }

    fun deleteWhisperModel() {
        val ctx = getApplication<Application>().applicationContext
        WhisperModel.delete(ctx)
        refresh()
    }

    fun deleteLlmModel() {
        val ctx = getApplication<Application>().applicationContext
        LlmModel.delete(ctx)
        refresh()
    }

    // ---- actions ------------------------------------------------------------------

    fun downloadModel() {
        val ctx = getApplication<Application>().applicationContext
        if (WhisperModel.isReady(ctx)) { AppLog.i("whisper model already present (ready)."); refresh(); return }
        AppLog.i("downloading whisper-small model (~360 MB, one-time, Wi-Fi recommended)…")
        WhisperModel.download(ctx) { AppLog.i(it); refresh() }
        refresh()
    }

    fun redownloadModel() {
        val ctx = getApplication<Application>().applicationContext
        if (WhisperModel.downloading) return
        WhisperModel.delete(ctx)
        refresh()
        downloadModel()
    }

    fun downloadLlm() {
        val ctx = getApplication<Application>().applicationContext
        if (LlmModel.isReady(ctx)) { AppLog.i("summary model already present (ready)."); refresh(); return }
        AppLog.i("downloading summary model (~1.6 GB, one-time, Wi-Fi strongly recommended)…")
        LlmModel.download(ctx) { AppLog.i(it); refresh() }
        refresh()
    }

    fun redownloadLlm() {
        val ctx = getApplication<Application>().applicationContext
        if (LlmModel.downloading) return
        LlmModel.delete(ctx)
        refresh()
        downloadLlm()
    }

    fun buildIndex() {
        val ctx = getApplication<Application>().applicationContext
        if (!Environment.isExternalStorageManager())
            AppLog.i("note: grant all-files access first, or the index will find 0 files.")
        IndexHolder.get(ctx).loadOrBuild { AppLog.i(it); refresh() }
        refresh()
    }

    /** MediaProjection consent result — same Intent wiring as the old MainActivity. */
    fun onProjectionResult(resultCode: Int, data: Intent?) {
        val ctx = getApplication<Application>().applicationContext
        if (resultCode == android.app.Activity.RESULT_OK && data != null) {
            AppLog.i("projection consent GRANTED — starting ProjectionService (session stays open).")
            val svc = Intent(ctx, ProjectionService::class.java).apply {
                action = ProjectionService.ACTION_START_SESSION
                putExtra(ProjectionService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ProjectionService.EXTRA_RESULT_DATA, data)
                putExtra(ProjectionService.EXTRA_RATE, SAMPLE_RATE)
            }
            ContextCompat.startForegroundService(ctx, svc)
            viewModelScope.launch { delay(800); refresh() }
        } else {
            AppLog.i("projection consent DENIED/cancelled (resultCode=$resultCode).")
        }
    }

    fun stopSession() {
        val ctx = getApplication<Application>().applicationContext
        if (!ProjectionService.sessionActive) { AppLog.i("no active projection session."); return }
        AppLog.i("stopping projection session…")
        ctx.startService(Intent(ctx, ProjectionService::class.java).apply {
            action = ProjectionService.ACTION_STOP_SESSION
        })
        viewModelScope.launch { delay(500); refresh() }
    }

    fun setToggle(key: ToggleKey, value: Boolean) {
        when (key) {
            ToggleKey.DiagnosticMode -> { Toggles.diagnosticMode = value; AppLog.i("diagnosticMode = $value") }
            ToggleKey.PauseOnPlay -> { Toggles.pauseOnPlay = value; AppLog.i("pauseOnPlay = $value") }
            ToggleKey.Orchestration -> { Toggles.orchestrationEnabled = value; AppLog.i("orchestrationEnabled = $value") }
            ToggleKey.MicFallback -> { Toggles.micFallbackEnabled = value; AppLog.i("micFallbackEnabled = $value") }
            ToggleKey.PauseOnMatch -> { Toggles.pauseOnMatch = value; AppLog.i("pauseOnMatch = $value") }
        }
        refresh()
    }

    /** Deletes a cached transcript; the note is re-transcribed on-demand next time it plays. */
    fun deleteTranscript(t: StoredTranscript) {
        val ctx = getApplication<Application>().applicationContext
        Transcripts.get(ctx).remove(t.key)
        SyncEngine.queueDeletion(ctx, t.key) // tombstone so the cloud copy dies too
        if (selectedTranscript?.key == t.key) selectedTranscript = null
        refresh()
    }

    fun folderReport(): String = VoiceNotes.report()

    // ---- checks (ported verbatim from the old MainActivity) ------------------------

    private fun hasRecord(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun notifGranted(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun isAccessibilityEnabled(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            val si = it.resolveInfo.serviceInfo
            si.packageName == ctx.packageName && si.name == WhatsAppAccessibilityService::class.java.name
        }
    }
}
