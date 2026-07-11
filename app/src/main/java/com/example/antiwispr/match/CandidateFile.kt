package com.example.antiwispr.match

import java.io.File

/** A voice-note file on disk that might be the one that just played. */
data class CandidateFile(
    val file: File,
    val name: String,
    val durationSec: Double,   // <0 if unknown
    val lastModified: Long,
    val score: Double = Double.NaN,    // acoustic match score (aligned-hash count); NaN if unscored
    val whatsAppDate: Int? = null,     // yyyymmdd parsed from filename
    val seq: Int? = null               // WhatsApp media sequence (WA####) parsed from filename
)
