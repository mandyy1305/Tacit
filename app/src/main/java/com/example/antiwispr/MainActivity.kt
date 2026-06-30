package com.example.antiwispr

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

/**
 * Control center for the transcription skeleton. It does NOT capture or match itself; it wires
 * up permissions and starts the long-lived projection session, then everything happens in the
 * services. Heavy on-screen log mirrors logcat (via AppLog).
 *
 * Components started/controlled from here:
 *   - ProjectionService           : MediaProjection + AudioPlaybackCapture rolling buffer.
 *   - WhatsAppAccessibilityService : node diagnostics + play-tap detection (enabled in Settings).
 *   - OverlayController            : floating result card + debug dump (needs SYSTEM_ALERT_WINDOW).
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val SAMPLE_RATE = 16000
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var statusView: TextView

    // ---- launchers --------------------------------------------------------------

    private val recordPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            AppLog.i("RECORD_AUDIO = ${if (granted) "granted" else "DENIED (capture cannot build AudioRecord)"}")
            refreshStatus()
        }

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            AppLog.i("POST_NOTIFICATIONS = ${if (granted) "granted" else "denied (FGS runs; notification hidden)"}")
            refreshStatus()
        }

    private val overlayLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            AppLog.i("overlay (SYSTEM_ALERT_WINDOW) now = ${Settings.canDrawOverlays(this)}")
            refreshStatus()
        }

    private val allFilesLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            AppLog.i("all-files access now = ${Environment.isExternalStorageManager()}")
            refreshStatus()
        }

    private val accessibilityLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            AppLog.i("returned from Accessibility settings; service enabled = ${isAccessibilityEnabled()}")
            refreshStatus()
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == RESULT_OK && res.data != null) {
                AppLog.i("projection consent GRANTED — starting ProjectionService (session stays open).")
                val svc = Intent(this, ProjectionService::class.java).apply {
                    action = ProjectionService.ACTION_START_SESSION
                    putExtra(ProjectionService.EXTRA_RESULT_CODE, res.resultCode)
                    putExtra(ProjectionService.EXTRA_RESULT_DATA, res.data)
                    putExtra(ProjectionService.EXTRA_RATE, SAMPLE_RATE)
                }
                ContextCompat.startForegroundService(this, svc)
                ui.postDelayed({ refreshStatus() }, 800)
            } else {
                AppLog.i("projection consent DENIED/cancelled (resultCode=${res.resultCode}).")
            }
        }

    // ---- lifecycle --------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())

        AppLog.i("=== Antiwispr transcription skeleton ===")
        AppLog.i("minSdk=30 (AudioPlaybackCapture needs API 29+; all-files needs 30). targetSdk=36.")
        AppLog.i("Flow: grant perms -> enable accessibility -> Start session (consent once) ->")
        AppLog.i("open a WhatsApp chat and tap a voice note's play button.")
        AppLog.i("MATCHING and TRANSCRIPTION are STUBS; everything else is real.")
    }

    override fun onResume() {
        super.onResume()
        // Mirror AppLog to the on-screen view, and repopulate from the buffer.
        logView.text = ""
        for (line in AppLog.snapshot()) logView.append(line + "\n")
        AppLog.setListener { line ->
            logView.append(line + "\n")
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
        refreshStatus()
    }

    override fun onPause() {
        AppLog.setListener(null)
        super.onPause()
    }

    // ---- UI ---------------------------------------------------------------------

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        statusView = TextView(this).apply {
            setTypeface(android.graphics.Typeface.MONOSPACE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, 0, 0, dp(6))
        }
        root.addView(statusView)

        root.addView(section("Permissions"))
        root.addView(button("Grant microphone (RECORD_AUDIO)") { requestRecord() })
        root.addView(button("Grant overlay (SYSTEM_ALERT_WINDOW)") { requestOverlay() })
        root.addView(button("Grant all-files access (read .opus)") { requestAllFiles() })
        root.addView(button("Grant notifications (FGS visibility)") { requestNotifications() })
        root.addView(button("Open Accessibility settings (enable service)") { openAccessibility() })

        root.addView(section("Projection session"))
        root.addView(button("Start transcription session (consent ONCE)") { startSession() })
        root.addView(button("Stop session (release projection)") { stopSession() })

        root.addView(section("Voice Notes folder"))
        root.addView(button("Verify Voice Notes folder on this device") { AppLog.i(VoiceNotes.report()) })

        root.addView(section("Toggles"))
        root.addView(checkbox("Diagnostic mode (dump node tree on tap)", Toggles.diagnosticMode) {
            Toggles.diagnosticMode = it; AppLog.i("diagnosticMode = $it")
        })
        root.addView(checkbox("Pause on play (ACTION_CLICK back on the node)", Toggles.pauseOnPlay) {
            Toggles.pauseOnPlay = it; AppLog.i("pauseOnPlay = $it")
        })
        root.addView(checkbox("Orchestration (capture -> match -> transcribe -> overlay)", Toggles.orchestrationEnabled) {
            Toggles.orchestrationEnabled = it; AppLog.i("orchestrationEnabled = $it")
        })

        root.addView(section("Log"))
        logView = TextView(this).apply {
            setTextIsSelectable(true)
            setTypeface(android.graphics.Typeface.MONOSPACE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        }
        logScroll = ScrollView(this).apply {
            addView(logView)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(logScroll)
        return root
    }

    private fun section(title: String) = TextView(this).apply {
        text = "— $title —"
        setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
        setPadding(0, dp(10), 0, dp(2))
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun checkbox(label: String, initial: Boolean, onChange: (Boolean) -> Unit) = CheckBox(this).apply {
        text = label
        isChecked = initial
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }

    // ---- permission actions -----------------------------------------------------

    private fun requestRecord() {
        if (hasRecord()) { AppLog.i("RECORD_AUDIO already granted."); return }
        recordPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun requestOverlay() {
        if (Settings.canDrawOverlays(this)) { AppLog.i("overlay already granted."); return }
        AppLog.i("opening overlay-permission settings…")
        overlayLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun requestAllFiles() {
        if (Environment.isExternalStorageManager()) { AppLog.i("all-files access already granted."); return }
        AppLog.i("opening all-files access settings…")
        try {
            allFilesLauncher.launch(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            allFilesLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) { AppLog.i("POST_NOTIFICATIONS not needed below API 33."); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            AppLog.i("POST_NOTIFICATIONS already granted."); return
        }
        notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun openAccessibility() {
        AppLog.i("opening Accessibility settings — enable \"Antiwispr\" (WhatsAppAccessibilityService).")
        accessibilityLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    // ---- projection session -----------------------------------------------------

    private fun startSession() {
        if (!hasRecord()) {
            AppLog.i("RECORD_AUDIO required first (AudioRecord can't build without it). Requesting…")
            recordPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (ProjectionService.sessionActive) {
            AppLog.i("projection session already active — reusing it.")
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        if (mpm == null) { AppLog.i("MediaProjectionManager unavailable."); return }
        AppLog.i("requesting projection consent (system dialog) — you only do this once per session.")
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun stopSession() {
        if (!ProjectionService.sessionActive) { AppLog.i("no active projection session."); return }
        AppLog.i("stopping projection session…")
        startService(Intent(this, ProjectionService::class.java).apply { action = ProjectionService.ACTION_STOP_SESSION })
        ui.postDelayed({ refreshStatus() }, 500)
    }

    // ---- status -----------------------------------------------------------------

    private fun refreshStatus() {
        val sb = StringBuilder()
        sb.append("mic=").append(yn(hasRecord()))
        sb.append("  overlay=").append(yn(Settings.canDrawOverlays(this)))
        sb.append("  files=").append(yn(Environment.isExternalStorageManager()))
        sb.append("  notif=").append(yn(notifGranted()))
        sb.append("\naccessibility=").append(yn(isAccessibilityEnabled()))
        sb.append("  session=").append(yn(ProjectionService.sessionActive))
        statusView.text = sb.toString()
    }

    private fun hasRecord() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun notifGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            val si = it.resolveInfo.serviceInfo
            si.packageName == packageName && si.name == WhatsAppAccessibilityService::class.java.name
        }
    }

    private fun yn(b: Boolean) = if (b) "✓" else "✗"

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
}
