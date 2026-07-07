package com.example.antiwispr

import android.content.Context
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * Persisted, local-only user overrides for chain membership. Currently: the set of notes the
 * user DETACHED from their auto-detected chain (a detached note stays standalone and is never
 * auto-grouped, in the overlay or the app). Keyed by "waDate:seq" — a note's logical identity,
 * which is stable across devices / a cloud restore (unlike the device-local transcript key
 * path|mtime|size). Mirrors the [ChainSummaries]/[Transcripts] binary idiom
 * (magic/version/count/records/CRC32, atomic temp+rename). Not synced — like chain summaries.
 */
object ChainOverrides {
    private const val MAGIC = 0x41574F56 // "AWOV"
    private const val VERSION = 1

    @Volatile private var loaded = false
    // Bumps on every change; callers cache chain-membership computations against this so a
    // detach/re-attach invalidates them without recomputing on every poll.
    @Volatile private var gen = 0
    private lateinit var file: File
    private val detached = LinkedHashSet<String>()

    fun get(context: Context): ChainOverrides {
        if (!loaded) synchronized(this) {
            if (!loaded) {
                file = File(context.applicationContext.filesDir, "chainoverrides.bin")
                load()
                loaded = true
                AppLog.i("[chains] loaded ${detached.size} detached override(s).")
            }
        }
        return this
    }

    /** Stable, sync-safe id for a note from its WhatsApp date + media sequence. */
    fun idOf(waDate: Int, seq: Int): String = "$waDate:$seq"

    /** Change counter for cache invalidation (bumps on detach/reattach). */
    @Synchronized fun generation(): Int = gen

    @Synchronized fun isDetached(id: String): Boolean = id in detached

    /** Detach a note from its chain — it stays standalone and won't auto-group. */
    @Synchronized fun detach(id: String) {
        if (detached.add(id)) { gen++; save(); AppLog.i("[chains] detached $id.") }
    }

    /** Re-attach a previously detached note so it can auto-group again ("chain back"). */
    @Synchronized fun reattach(id: String) {
        if (detached.remove(id)) { gen++; save(); AppLog.i("[chains] re-attached $id.") }
    }

    // ---- persistence (same idiom as ChainStore/TranscriptStore) ------------------

    private fun load() {
        detached.clear()
        if (!file.exists()) return
        val raw = try { file.readBytes() } catch (e: Exception) { AppLog.e("[chains] overrides read failed: ${e.message}"); return }
        if (raw.size < 16) return
        val crc = CRC32(); crc.update(raw, 0, raw.size - 8)
        if (crc.value != ByteBuffer.wrap(raw, raw.size - 8, 8).long) { AppLog.w("[chains] overrides CRC mismatch — ignoring."); return }
        try {
            val din = DataInputStream(ByteArrayInputStream(raw, 0, raw.size - 8))
            if (din.readInt() != MAGIC || din.readInt() != VERSION) { AppLog.w("[chains] overrides bad header — ignoring."); return }
            repeat(din.readInt().coerceAtLeast(0)) { detached.add(din.readUTF()) }
        } catch (e: Exception) {
            AppLog.e("[chains] overrides parse failed: ${e.message} — starting empty."); detached.clear()
        }
    }

    private fun save() {
        val tmp = File(file.parentFile, file.name + ".tmp")
        val fos = FileOutputStream(tmp)
        try {
            val crc = CRC32()
            val dos = DataOutputStream(BufferedOutputStream(CrcOut(fos, crc)))
            dos.writeInt(MAGIC); dos.writeInt(VERSION); dos.writeInt(detached.size)
            for (id in detached) dos.writeUTF(id)
            dos.flush()
            DataOutputStream(fos).writeLong(crc.value)
            fos.fd.sync()
        } catch (e: Exception) {
            AppLog.e("[chains] overrides save failed: ${e.message}", e); return
        } finally {
            try { fos.close() } catch (_: Exception) {}
        }
        file.delete(); if (!tmp.renameTo(file)) AppLog.e("[chains] overrides rename failed.")
    }

    private class CrcOut(private val out: OutputStream, private val crc: CRC32) : OutputStream() {
        override fun write(b: Int) { out.write(b); crc.update(b) }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); crc.update(b, off, len) }
        override fun flush() { out.flush() }
        override fun close() { out.flush() }
    }
}
