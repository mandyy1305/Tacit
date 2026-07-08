package com.example.antiwispr

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Central logging used by EVERY component (activity, services, overlay). Writes to logcat
 * (tag "TACIT") AND mirrors each line to an in-memory ring buffer + an optional UI listener
 * so the developer log viewer can show the same stream on screen.
 */
object AppLog {
    const val TAG = "TACIT"
    private const val MAX_LINES = 3000

    private val lines = ArrayDeque<String>()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var listener: ((String) -> Unit)? = null

    /** MainActivity registers here to receive live lines; pass null to detach. */
    fun setListener(l: ((String) -> Unit)?) { listener = l }

    /** Snapshot of recent lines (e.g. to repopulate the UI on resume). */
    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    fun i(msg: String) { Log.i(TAG, msg); append(msg) }
    fun w(msg: String) { Log.w(TAG, msg); append("⚠ $msg") }
    fun e(msg: String, tr: Throwable? = null) { Log.e(TAG, msg, tr); append("‼ $msg") }

    private fun append(line: String) {
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        val l = listener ?: return
        main.post { l(line) }
    }
}
