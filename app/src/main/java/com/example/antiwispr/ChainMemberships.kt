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
 * Persisted (local-only) chain MEMBERSHIP as detected by the live overlay from the accessibility
 * tree — the one place that sees the whole burst, including notes not transcribed yet. The app
 * reader reads this to show the full chain and offer "Transcribe all N", which the store-derived
 * path (transcribed notes only) can't do. Member paths are device-local (used for on-device
 * transcription/playback), so this store is not synced. Mirrors the [ChainSummaries] binary idiom.
 */
object ChainMemberships {

    /** One member of a burst. [id] is "waDate:seq" (stable); [path] is this device's file path. */
    data class Member(val id: String, val path: String, val waDate: Int, val seq: Int)

    private const val MAGIC = 0x4157434D // "AWCM"
    private const val VERSION = 1

    @Volatile private var loaded = false
    @Volatile private var gen = 0
    private lateinit var file: File
    private var chains = ArrayList<List<Member>>()      // each = one burst (>=2 members, in chat order)
    private val index = HashMap<String, List<Member>>() // noteId -> its burst

    fun get(context: Context): ChainMemberships {
        if (!loaded) synchronized(this) {
            if (!loaded) {
                file = File(context.applicationContext.filesDir, "chainmembers.bin")
                load(); reindex(); loaded = true
                AppLog.i("[chains] loaded ${chains.size} persisted chain(s).")
            }
        }
        return this
    }

    /** Change counter for cache invalidation (bumps on record). */
    @Synchronized fun generation(): Int = gen

    /** The burst (>=2 members) that [noteId] belongs to, or null. */
    @Synchronized fun forNote(noteId: String): List<Member>? = index[noteId]

    /** Upsert a burst detected by the overlay: its members leave any prior burst, then form this
     *  one. Idempotent when the same burst is re-detected. */
    @Synchronized fun record(members: List<Member>) {
        if (members.size < 2) return
        val ids = members.mapTo(HashSet()) { it.id }
        // Already recorded identically? Skip the write.
        if (chains.any { it.size == members.size && it.map { m -> m.id } == members.map { m -> m.id } }) return
        val next = ArrayList<List<Member>>(chains.size + 1)
        for (c in chains) {
            val kept = c.filter { it.id !in ids }
            if (kept.size >= 2) next.add(kept)
        }
        next.add(members)
        chains = next
        reindex(); gen++; save()
    }

    private fun reindex() {
        index.clear()
        for (c in chains) for (m in c) index[m.id] = c
    }

    // ---- persistence (same idiom as ChainStore/TranscriptStore) ------------------

    private fun load() {
        chains = ArrayList()
        if (!file.exists()) return
        val raw = try { file.readBytes() } catch (e: Exception) { AppLog.e("[chains] members read failed: ${e.message}"); return }
        if (raw.size < 16) return
        val crc = CRC32(); crc.update(raw, 0, raw.size - 8)
        if (crc.value != ByteBuffer.wrap(raw, raw.size - 8, 8).long) { AppLog.w("[chains] members CRC mismatch — ignoring."); return }
        try {
            val din = DataInputStream(ByteArrayInputStream(raw, 0, raw.size - 8))
            if (din.readInt() != MAGIC || din.readInt() != VERSION) { AppLog.w("[chains] members bad header — ignoring."); return }
            repeat(din.readInt().coerceAtLeast(0)) {
                val n = din.readInt().coerceAtLeast(0)
                val ms = ArrayList<Member>(n)
                repeat(n) { ms.add(Member(din.readUTF(), din.readUTF(), din.readInt(), din.readInt())) }
                if (ms.size >= 2) chains.add(ms)
            }
        } catch (e: Exception) {
            AppLog.e("[chains] members parse failed: ${e.message} — starting empty."); chains = ArrayList()
        }
    }

    private fun save() {
        val tmp = File(file.parentFile, file.name + ".tmp")
        val fos = FileOutputStream(tmp)
        try {
            val crc = CRC32()
            val dos = DataOutputStream(BufferedOutputStream(CrcOut(fos, crc)))
            dos.writeInt(MAGIC); dos.writeInt(VERSION); dos.writeInt(chains.size)
            for (c in chains) {
                dos.writeInt(c.size)
                for (m in c) { dos.writeUTF(m.id); dos.writeUTF(m.path); dos.writeInt(m.waDate); dos.writeInt(m.seq) }
            }
            dos.flush()
            DataOutputStream(fos).writeLong(crc.value)
            fos.fd.sync()
        } catch (e: Exception) {
            AppLog.e("[chains] members save failed: ${e.message}", e); return
        } finally {
            try { fos.close() } catch (_: Exception) {}
        }
        file.delete(); if (!tmp.renameTo(file)) AppLog.e("[chains] members rename failed.")
    }

    private class CrcOut(private val out: OutputStream, private val crc: CRC32) : OutputStream() {
        override fun write(b: Int) { out.write(b); crc.update(b) }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); crc.update(b, off, len) }
        override fun flush() { out.flush() }
        override fun close() { out.flush() }
    }
}
