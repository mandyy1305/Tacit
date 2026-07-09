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
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.ContextCompat
import com.example.antiwispr.AppLog
import com.example.antiwispr.BackfillTranscriber
import com.example.antiwispr.ChainMemberships
import com.example.antiwispr.ChainOverrides
import com.example.antiwispr.ChainSummaries
import com.example.antiwispr.Chains
import com.example.antiwispr.DetectionHealth
import com.example.antiwispr.IndexHolder
import com.example.antiwispr.LlmModel
import com.example.antiwispr.ProjectionService
import com.example.antiwispr.SearchEngine
import com.example.antiwispr.SearchFilters
import com.example.antiwispr.StoredTranscript
import com.example.antiwispr.Summarizer
import com.example.antiwispr.parseAskAnswer
import com.example.antiwispr.Toggles
import com.example.antiwispr.TranscribeRouter
import com.example.antiwispr.Transcripts
import com.example.antiwispr.VoiceNoteWatcher
import com.example.antiwispr.VoiceNotes
import com.example.antiwispr.WhatsAppAccessibilityService
import com.example.antiwispr.WhisperModel
import com.example.antiwispr.cloud.AskLanguage
import com.example.antiwispr.cloud.CloudClient
import com.example.antiwispr.cloud.CloudPrefs
import com.example.antiwispr.cloud.CloudSttLanguage
import com.example.antiwispr.cloud.CloudSttMode
import com.example.antiwispr.cloud.FcmRegistrar
import com.example.antiwispr.cloud.FirebaseBootstrap
import com.example.antiwispr.cloud.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Everything the UI needs to know about permissions, model, index, and session state. */
data class SetupStatus(
    val tacitEnabled: Boolean = true,
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
    val sttMode: CloudSttMode = CloudSttMode.DEFAULT,
    val sttLanguage: CloudSttLanguage = CloudSttLanguage.DEFAULT,
    val askLanguage: AskLanguage = AskLanguage.DEFAULT,
    val backfillRunning: Boolean = false,
    val backfillProgress: Float? = null,
    val backfillStatus: String = "",
    val sessionActive: Boolean = false,
    val transcriptCount: Int = 0,
    val detection: String = "",
    val autoRead: Boolean = true,       // mirror of Toggles.orchestrationEnabled (for observability)
    val whatsAppInstalled: Boolean = true,
) {
    /** Notifications are deliberately optional (capture works without the FGS notice).
     *  A signed-in user can run cloud-only — the offline pack is then optional too. */
    val setupComplete: Boolean
        get() = mic && overlay && files && accessibility && (modelReady || signedIn) && indexReady

    /** The four grants TACIT cannot work without. */
    val coreGrantsOk: Boolean get() = mic && overlay && files && accessibility

    /** Something can transcribe: the offline pack, or a usable cloud path (signed in + cloud on).
     *  When entitlements ship this tightens to "entitled", not merely "signed in" (doc 01 §0). */
    val engineReady: Boolean get() = modelReady || (signedIn && cloudTranscription)

    /** True while an engine is on its way (pack download) so a missing engine reads as
     *  "getting ready" rather than "broken". */
    private val engineArriving: Boolean get() = modelDownloading

    val detectionDegraded: Boolean get() = detection.startsWith("⚠")

    /** The single readiness model consumed by Home, onboarding, and notifications (doc 01 §0).
     *  Priority ordered: OFF, NEEDS_SETUP, ATTENTION, GETTING_READY, READY. */
    val health: SetupHealth
        get() = when {
            !tacitEnabled -> SetupHealth.OFF
            !coreGrantsOk -> SetupHealth.NEEDS_SETUP
            (!engineReady && !engineArriving) || detectionDegraded -> SetupHealth.ATTENTION
            modelDownloading || llmDownloading || (indexBuilding && !indexReady) -> SetupHealth.GETTING_READY
            else -> SetupHealth.READY
        }
}

/** The five macro states of readiness (doc 01 §0). Home renders exactly one. */
enum class SetupHealth { OFF, NEEDS_SETUP, ATTENTION, GETTING_READY, READY }

enum class AskRole { User, Assistant }

/** One turn in the Ask tab's chat thread. Assistant turns may carry the notes the answer
 *  was drawn from (rendered as source cards) and a [pending] flag while the model works. */
data class AskMessage(
    val role: AskRole,
    val text: String,
    val sources: List<StoredTranscript> = emptyList(),
    val pending: Boolean = false,
    val error: Boolean = false,
)

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

    /** The full library, newest first (History screen): synced-from-cloud + locally made. */
    private val _history = MutableStateFlow<List<StoredTranscript>>(emptyList())
    val history = _history.asStateFlow()

    /** Keys of transcripts that belong to a chain (for list badges). */
    private val _chainKeys = MutableStateFlow<Set<String>>(emptySet())
    val chainKeys = _chainKeys.asStateFlow()
    // Chain membership is mtime-based now, so recompute only when the library or the user's
    // chain overrides change — not on every poll tick.
    private var chainKeysSig = ""

    var searchQuery by mutableStateOf("")

    /** The Ask tab's chat thread. Hoisted here (not screen-local) so it survives tab switches
     *  and recomposition; each turn is answered independently from the note library. */
    val askMessages = mutableStateListOf<AskMessage>()

    /** True while an Ask answer is being retrieved + generated (composer disabled meanwhile). */
    var askBusy by mutableStateOf(false)
        private set

    /** Detail-screen selection. Store keys contain '/' and '|' — never a nav argument. */
    var selectedTranscript by mutableStateOf<StoredTranscript?>(null)

    /** Set by MainActivity when launched/resumed from a "transcript ready" notification;
     *  AppRoot observes it, resolves the record, and navigates to that note's detail screen. */
    var pendingOpenKey by mutableStateOf<String?>(null)

    /** Set by MainActivity from a launcher shortcut (search / ask / library); AppRoot navigates. */
    var pendingDest by mutableStateOf<String?>(null)

    init {
        AppLog.i("=== TACIT — every voice note, read ===")
        val ctx = app.applicationContext
        Toggles.load(ctx) // master switch persists across restarts
        FirebaseBootstrap.ensureInitialized(ctx)
        // Parity with the old MainActivity.onCreate: warm the index, start the folder watcher.
        IndexHolder.get(ctx).loadOrBuild { AppLog.i(it); refresh() }
        VoiceNoteWatcher.ensureStarted { IndexHolder.get(ctx).loadOrBuild { AppLog.i(it); refresh() } }
        SyncEngine.requestSync(ctx) // no-op unless configured + signed in
        FcmRegistrar.register(ctx)  // keep the server's push target fresh (no-op unless signed in)
        // Pre-warm the search engine's normalized-text cache so the first search doesn't
        // pay the one-time transliteration pass (Devanagari-heavy libraries can take ~1-2 s).
        viewModelScope.launch(Dispatchers.Default) {
            SearchEngine.prewarm(Transcripts.get(ctx).all())
        }
        viewModelScope.launch {
            while (isActive) {
                refresh()
                val busy = _setup.value.modelDownloading || _setup.value.indexBuilding ||
                    _setup.value.llmDownloading || _setup.value.backfillRunning
                delay(if (busy) 400L else 2500L)
            }
        }
    }

    fun refresh() {
        val ctx = getApplication<Application>().applicationContext
        val idx = IndexHolder.get(ctx)
        val snap = idx.snapshot
        _setup.value = SetupStatus(
            tacitEnabled = Toggles.tacitEnabled,
            mic = hasRecord(ctx),
            overlay = Settings.canDrawOverlays(ctx),
            files = hasAudioAccess(ctx),
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
            sttMode = CloudSttMode.fromWire(CloudPrefs.sttMode(ctx)),
            sttLanguage = CloudSttLanguage.fromWire(CloudPrefs.sttLanguage(ctx)),
            askLanguage = AskLanguage.fromWire(CloudPrefs.askLanguage(ctx)),
            backfillRunning = BackfillTranscriber.running,
            backfillProgress = if (BackfillTranscriber.running && BackfillTranscriber.progressTotal > 0)
                BackfillTranscriber.progressDone.toFloat() / BackfillTranscriber.progressTotal else null,
            backfillStatus = BackfillTranscriber.status,
            sessionActive = ProjectionService.sessionActive,
            transcriptCount = Transcripts.get(ctx).count(),
            detection = DetectionHealth.summary(),
            autoRead = Toggles.orchestrationEnabled,
            whatsAppInstalled = isWhatsAppInstalled(ctx),
        )
        val all = Transcripts.get(ctx).all()
        _recents.value = all.take(20)
        _history.value = all
        val sig = "${all.size}:${all.maxOfOrNull { it.updatedAt } ?: 0L}:${ChainOverrides.get(ctx).generation()}:${ChainMemberships.get(ctx).generation()}"
        if (sig != chainKeysSig) {
            chainKeysSig = sig
            _chainKeys.value = try { Chains.memberKeys(ctx) } catch (_: Exception) { emptySet() }
        }
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
        FcmRegistrar.register(ctx) // register this device for "transcript ready" pushes
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

    fun setSttMode(m: CloudSttMode) {
        CloudPrefs.setSttMode(getApplication<Application>().applicationContext, m.wire)
        AppLog.i("sttMode = ${m.wire}"); refresh()
    }

    fun setSttLanguage(l: CloudSttLanguage) {
        CloudPrefs.setSttLanguage(getApplication<Application>().applicationContext, l.wire)
        AppLog.i("sttLanguage = ${l.wire}"); refresh()
    }

    fun setAskLanguage(l: AskLanguage) {
        CloudPrefs.setAskLanguage(getApplication<Application>().applicationContext, l.wire)
        AppLog.i("askLanguage = ${l.wire}"); refresh()
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
        if (!hasAudioAccess(ctx))
            AppLog.i("note: grant voice-note (audio) access first, or the index will find 0 files.")
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

    /** Master switch — persisted; when off the a11y service ignores WhatsApp entirely. */
    fun setTacitEnabled(v: Boolean) {
        Toggles.setTacitEnabled(getApplication<Application>().applicationContext, v)
        AppLog.i("tacitEnabled = $v")
        refresh()
    }

    /** Persisted once the onboarding Done screen is reached; the app then always starts on Home. */
    fun markOnboardingDone() {
        Toggles.setOnboardingDone(getApplication<Application>().applicationContext, true)
    }

    fun setToggle(key: ToggleKey, value: Boolean) {
        val ctx = getApplication<Application>().applicationContext
        when (key) {
            ToggleKey.DiagnosticMode -> Toggles.diagnosticMode = value          // session-scoped
            ToggleKey.PauseOnPlay -> Toggles.pauseOnPlay = value                 // session-scoped
            ToggleKey.Orchestration -> Toggles.setOrchestration(ctx, value)      // persisted
            ToggleKey.MicFallback -> Toggles.setMicFallback(ctx, value)          // persisted
            ToggleKey.PauseOnMatch -> Toggles.setPauseOnMatch(ctx, value)        // persisted
        }
        AppLog.i("$key = $value")
        refresh()
    }

    /** True while a forced re-transcription runs (reader shows a spinner on the refresh icon). */
    var retranscribing by mutableStateOf(false)
        private set

    /** Redo an existing transcript with the CURRENT STT settings (cloud mode/language or local).
     *  The refresh action is only offered when the audio file is still on this phone. */
    fun retranscribe(t: StoredTranscript) {
        if (retranscribing) return
        val ctx = getApplication<Application>().applicationContext
        val file = File(t.path)
        if (!file.exists()) { AppLog.w("[retranscribe] ${t.name} — audio missing, skipping."); return }
        retranscribing = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                TranscribeRouter.transcribe(ctx, file, t.chatName.ifEmpty { null }, force = true)
            }
            if (result.startsWith("[")) {
                AppLog.w("[retranscribe] ${t.name} failed: $result — keeping the old transcript.")
                Toast.makeText(ctx, "Couldn't re-transcribe. Try again.", Toast.LENGTH_LONG).show()
            } else {
                dropStaleChainGists(t) // gist was built from the old text
                if (selectedTranscript?.key == t.key) selectedTranscript = Transcripts.get(ctx).entry(file)
            }
            retranscribing = false
            refresh()
        }
    }

    /** Backfill: transcribe the untranscribed notes of the last [days] days (searchable history). */
    fun startBackfill(days: Int) {
        val ctx = getApplication<Application>().applicationContext
        BackfillTranscriber.start(ctx, System.currentTimeMillis() - days * 86_400_000L)
        refresh()
    }

    fun cancelBackfill() {
        BackfillTranscriber.cancel()
        refresh()
    }

    /** Deletes a cached transcript; the note is re-transcribed on-demand next time it plays. */
    fun deleteTranscript(t: StoredTranscript) {
        val ctx = getApplication<Application>().applicationContext
        Transcripts.get(ctx).remove(t.key)
        SyncEngine.queueDeletion(ctx, t.key) // tombstone so the cloud copy dies too
        dropStaleChainGists(t) // any burst gist built from this note is stale now
        if (selectedTranscript?.key == t.key) selectedTranscript = null
        refresh()
    }

    // ---- Library multi-select soft-delete (undo window owned by the snackbar) ------------------
    // Records are removed from the local store immediately (they vanish from the list) but the
    // cloud tombstone is deferred: Undo re-inserts them; dismissing the snackbar commits the
    // tombstones. A new soft-delete or process teardown flushes any still-pending batch.
    private var pendingDeletes: List<StoredTranscript> = emptyList()

    fun softDelete(records: List<StoredTranscript>) {
        if (records.isEmpty()) return
        commitPendingDeletes() // flush any prior batch before starting a new one
        val ctx = getApplication<Application>().applicationContext
        val store = Transcripts.get(ctx)
        records.forEach { store.remove(it.key); dropStaleChainGists(it) }
        if (records.any { it.key == selectedTranscript?.key }) selectedTranscript = null
        pendingDeletes = records
        refresh()
    }

    fun undoDelete() {
        if (pendingDeletes.isEmpty()) return
        val ctx = getApplication<Application>().applicationContext
        Transcripts.get(ctx).applyRemote(pendingDeletes, emptyList()) // re-insert (map[key] is gone → upsert)
        pendingDeletes = emptyList()
        refresh()
    }

    /** Turns the pending soft-deletes into real cloud tombstones. Called when the undo window ends. */
    fun commitPendingDeletes() {
        if (pendingDeletes.isEmpty()) return
        val ctx = getApplication<Application>().applicationContext
        pendingDeletes.forEach { SyncEngine.queueDeletion(ctx, it.key) }
        pendingDeletes = emptyList()
    }

    override fun onCleared() {
        commitPendingDeletes()
        super.onCleared()
    }

    /** A chain gist is built from its members' transcripts — when a member is deleted or
     *  re-transcribed, the cached gist no longer matches and must regenerate. */
    private fun dropStaleChainGists(t: StoredTranscript) {
        if (t.waDate > 0 && t.seq >= 0) {
            val ctx = getApplication<Application>().applicationContext
            ChainSummaries.get(ctx).removeContaining(t.waDate, t.seq)
        }
    }

    fun folderReport(): String = VoiceNotes.report()

    // ---- Ask (chat tab) ------------------------------------------------------------
    // Each question is answered independently: retrieve the most relevant notes, then let
    // the LLM (cloud gpt-4o-mini or on-device Qwen) answer from them. Messages accumulate in
    // [askMessages] so the tab reads as a running conversation.

    fun ask(input: String) {
        val q = input.trim()
        if (q.length < 3 || askBusy) return
        val ctx = getApplication<Application>().applicationContext
        askMessages.add(AskMessage(AskRole.User, q))
        val slot = askMessages.size
        askMessages.add(AskMessage(AskRole.Assistant, "Finding the right notes…", pending = true))
        askBusy = true
        viewModelScope.launch {
            val hits = withContext(Dispatchers.Default) {
                SearchEngine.retrieveForAsk(Transcripts.get(ctx).all(), q, SearchFilters())
            }
            if (slot < askMessages.size) {
                val n = hits.size
                askMessages[slot] = AskMessage(
                    AskRole.Assistant,
                    "Reading $n note${if (n == 1) "" else "s"}…",
                    pending = true,
                )
            }
            val raw = withContext(Dispatchers.IO) { Summarizer.askBlocking(ctx, q, hits) }
            val answer = if (raw.startsWith("[")) {
                AskMessage(AskRole.Assistant, raw.trim('[', ']'), error = true)
            } else {
                val parsed = parseAskAnswer(raw)
                // Show ONLY the notes the model actually cited. No fallback to "all retrieved" —
                // a negative answer ("SOURCES: none") must show no source cards, not the whole pile.
                val cited = parsed.sources.mapNotNull { hits.getOrNull(it - 1)?.transcript }
                    .distinctBy { it.key }
                AskMessage(AskRole.Assistant, parsed.answer, sources = cited)
            }
            if (slot < askMessages.size) askMessages[slot] = answer
            askBusy = false
        }
    }

    fun clearAsk() {
        if (askBusy) return
        askMessages.clear()
    }

    // ---- checks (ported verbatim from the old MainActivity) ------------------------

    private fun hasRecord(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun isWhatsAppInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo("com.whatsapp", 0); true
    } catch (_: PackageManager.NameNotFoundException) { false } catch (_: Exception) { true }

    /** Voice-note access. Direct file reads are the only reliable way to see WhatsApp voice notes
     *  (MediaStore skips the .nomedia'd Voice Notes folder), so all-files access is required on 11+. */
    fun hasAudioAccess(ctx: Context): Boolean = Environment.isExternalStorageManager()

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
