package com.example.antiwispr

import java.io.File

/**
 * Turns a voice-note file into text. The real implementation (on-device ASR, or piping the
 * decoded PCM to a model) goes here later.
 */
interface Transcriber {
    fun transcribe(file: File): String
}

/**
 * ===================== STUB — NOT REAL TRANSCRIPTION =====================
 * Returns a placeholder string. Swap for a real Transcriber when ready.
 * =========================================================================
 */
class StubTranscriber : Transcriber {
    override fun transcribe(file: File): String {
        AppLog.i("[transcriber STUB] transcribe(${file.name}) — returning placeholder.")
        return "[transcript placeholder for ${file.name}]"
    }
}
