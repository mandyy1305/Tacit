package com.example.antiwispr.audio

/**
 * What the orchestrator needs from the capture layer — keeps it decoupled from the service.
 */
interface AudioWindowSource {
    val isSessionActive: Boolean
    val sampleRate: Int
    /** Optional: invoked ~10x/sec with a normalized 0..1 amplitude of the latest ~100ms chunk,
     *  for the live listening waveform. Set by the Orchestrator while listening; null otherwise. */
    var onLevel: ((Float) -> Unit)?
    /** Blocking: returns the next [seconds] of audio starting at the moment of the call.
     *  MUST be called off the main thread (it waits in real time). */
    fun captureWindow(seconds: Double): ShortArray
    /** Non-blocking: current write position (total samples written since session start). */
    fun mark(): Long
    /** Non-blocking: returns samples from [mark] up to the current write head (clamped to the ring).
     *  For streaming: mark() at the start, then call repeatedly as audio accumulates. */
    fun readSince(mark: Long): ShortArray
}
