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

/** One stored transcript, keyed by path|mtime|size (same scheme as the fingerprint index). */
data class StoredTranscript(
    val key: String,
    val path: String,
    val name: String,
    val waDate: Int,        // yyyymmdd, -1 if unknown
    val seq: Int,           // WhatsApp media sequence, -1 if unknown
    val durationSec: Double,
    val text: String,
    val updatedAt: Long
)

/**
 * Process-wide, on-disk transcript cache + in-memory search. Mirrors the IndexStore binary idiom
 * (magic/version/count/records/CRC32, atomic temp+rename). Loaded once via [get]; the in-memory map is
 * authoritative and re-persisted on each put (small file, short strings). Search is a case-insensitive
 * substring over the transcript text — plenty for hundreds/low-thousands of notes, no SQLite needed.
 */
object Transcripts {
    private const val MAGIC = 0x41575458 // "AWTX"
    private const val VERSION = 1

    @Volatile private var loaded = false
    private lateinit var file: File
    private val map = LinkedHashMap<String, StoredTranscript>()

    fun get(context: Context): Transcripts {
        if (!loaded) synchronized(this) {
            if (!loaded) {
                file = File(context.applicationContext.filesDir, "transcripts.bin")
                load()
                loaded = true
                AppLog.i("[transcripts] loaded ${map.size} transcript(s).")
            }
        }
        return this
    }

    fun keyFor(f: File): String = "${f.absolutePath}|${f.lastModified()}|${f.length()}"

    @Synchronized fun count(): Int = map.size

    /** All transcripts, newest first (for the Home recents list). */
    @Synchronized fun all(limit: Int = Int.MAX_VALUE): List<StoredTranscript> =
        map.values.sortedByDescending { it.updatedAt }.take(limit)

    /** Cached transcript text for this file, or null. */
    @Synchronized fun find(f: File): String? = map[keyFor(f)]?.text

    @Synchronized fun put(f: File, text: String) {
        val p = VoiceNotes.parseWhatsAppName(f.name)
        map[keyFor(f)] = StoredTranscript(
            key = keyFor(f), path = f.absolutePath, name = f.name,
            waDate = p?.dateYmd ?: -1, seq = p?.seq ?: -1,
            durationSec = -1.0, text = text, updatedAt = System.currentTimeMillis()
        )
        save()
    }

    /** Case-insensitive substring search over transcript text, newest first. */
    @Synchronized fun search(query: String): List<StoredTranscript> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return map.values.filter { it.text.contains(q, ignoreCase = true) }
            .sortedByDescending { it.updatedAt }
    }

    // ---- persistence ------------------------------------------------------------

    private fun load() {
        map.clear()
        if (!file.exists()) return
        val raw = try { file.readBytes() } catch (e: Exception) { AppLog.e("[transcripts] read failed: ${e.message}"); return }
        if (raw.size < 16) return
        val crc = CRC32(); crc.update(raw, 0, raw.size - 8)
        if (crc.value != ByteBuffer.wrap(raw, raw.size - 8, 8).long) { AppLog.w("[transcripts] CRC mismatch — ignoring store."); return }
        try {
            val din = DataInputStream(ByteArrayInputStream(raw, 0, raw.size - 8))
            if (din.readInt() != MAGIC || din.readInt() != VERSION) { AppLog.w("[transcripts] bad header — ignoring."); return }
            val n = din.readInt()
            repeat(n.coerceAtLeast(0)) {
                val key = din.readUTF(); val path = din.readUTF(); val name = din.readUTF()
                val waDate = din.readInt(); val seq = din.readInt(); val dur = din.readDouble()
                val text = din.readUTF(); val updated = din.readLong()
                map[key] = StoredTranscript(key, path, name, waDate, seq, dur, text, updated)
            }
        } catch (e: Exception) {
            AppLog.e("[transcripts] parse failed: ${e.message} — starting empty."); map.clear()
        }
    }

    private fun save() {
        val tmp = File(file.parentFile, file.name + ".tmp")
        val fos = FileOutputStream(tmp)
        try {
            val crc = CRC32()
            val dos = DataOutputStream(BufferedOutputStream(CrcOut(fos, crc)))
            dos.writeInt(MAGIC); dos.writeInt(VERSION); dos.writeInt(map.size)
            for (t in map.values) {
                dos.writeUTF(t.key); dos.writeUTF(t.path); dos.writeUTF(t.name)
                dos.writeInt(t.waDate); dos.writeInt(t.seq); dos.writeDouble(t.durationSec)
                dos.writeUTF(t.text); dos.writeLong(t.updatedAt)
            }
            dos.flush()
            DataOutputStream(fos).writeLong(crc.value)
            fos.fd.sync()
        } catch (e: Exception) {
            AppLog.e("[transcripts] save failed: ${e.message}", e); return
        } finally {
            try { fos.close() } catch (_: Exception) {}
        }
        file.delete(); if (!tmp.renameTo(file)) AppLog.e("[transcripts] rename failed.")
    }

    private class CrcOut(private val out: OutputStream, private val crc: CRC32) : OutputStream() {
        override fun write(b: Int) { out.write(b); crc.update(b) }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); crc.update(b, off, len) }
        override fun flush() { out.flush() }
        override fun close() { out.flush() }
    }
}
