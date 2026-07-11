package com.example.antiwispr.match

import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import com.example.antiwispr.core.AppLog
import com.example.antiwispr.data.VoiceNotes
import java.io.File

/**
 * Best-effort watcher over the WhatsApp Voice Notes folder(s). When WhatsApp writes a new .opus, it
 * fires [onNew] (debounced) so the index can fingerprint + auto-transcribe it BEFORE you tap play.
 *
 * WhatsApp stores notes in dated subfolders (…/WhatsApp Voice Notes/202627/…), so we watch each root
 * and its current subdirectories, and add a watcher when a new subdirectory appears. It doesn't have to
 * be perfect — the on-play-tap refresh is the reliable backstop; this just makes new notes ready sooner.
 */
object VoiceNoteWatcher {

    @Volatile private var started = false
    private val observers = ArrayList<FileObserver>()
    private val watched = HashSet<String>()
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var onNew: (() -> Unit)? = null

    private val EVENTS = FileObserver.CREATE or FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO

    /** Idempotent; safe to call from several places. Only actually starts once folders exist. */
    fun ensureStarted(onNew: () -> Unit) {
        if (started) return
        synchronized(this) {
            if (started) return
            this.onNew = onNew
            val roots = VoiceNotes.resolveFolders()
            if (roots.isEmpty()) return // no folder yet (e.g. all-files not granted) — retry on a later call
            for (root in roots) {
                watchDir(root)
                root.listFiles()?.forEach { if (it.isDirectory) watchDir(it) }
            }
            if (observers.isEmpty()) return
            started = true
            AppLog.i("[watcher] watching ${observers.size} folder(s) for new voice notes.")
        }
    }

    private fun watchDir(dir: File) {
        if (!dir.isDirectory || !watched.add(dir.absolutePath)) return
        val obs = object : FileObserver(dir, EVENTS) {
            override fun onEvent(event: Int, path: String?) {
                if (path == null) return
                val f = File(dir, path)
                if (f.isDirectory) { watchDir(f); scheduleRefresh() }      // new dated subfolder
                else if (path.endsWith(".opus", true) || path.endsWith(".ogg", true)) scheduleRefresh()
            }
        }
        try { obs.startWatching(); observers.add(obs) } catch (e: Exception) {
            AppLog.w("[watcher] couldn't watch ${dir.name}: ${e.message}")
        }
    }

    private val refresh = Runnable {
        AppLog.i("[watcher] new voice note detected — refreshing index.")
        onNew?.invoke()
    }

    /** Debounce bursty write events (CREATE + MODIFY + CLOSE_WRITE) into one refresh. */
    private fun scheduleRefresh() {
        handler.removeCallbacks(refresh)
        handler.postDelayed(refresh, 800)
    }
}
