package com.example.antiwispr.transcribe

import java.io.File

/**
 * Turns a voice-note file into text. The real implementation (on-device ASR, or piping the
 * decoded PCM to a model) goes here later.
 */
interface Transcriber {
    fun transcribe(file: File): String
}
