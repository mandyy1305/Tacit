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
    val updatedAt: Long,
    val summary: String = "",  // raw LLM "SUMMARY:/ACTIONS:" text; "" = not generated (v2)
    val chatName: String = "", // WhatsApp chat title captured at play-tap; "" = unknown (v3)
    val source: String = ""    // transcription engine: "cloud" | "local"; "" = unknown/legacy (v4)
)

/**
 * Process-wide, on-disk transcript cache + in-memory search. Mirrors the IndexStore binary idiom
 * (magic/version/count/records/CRC32, atomic temp+rename). Loaded once via [get]; the in-memory map is
 * authoritative and re-persisted on each put (small file, short strings). Search is a case-insensitive
 * substring over the transcript text — plenty for hundreds/low-thousands of notes, no SQLite needed.
 */
object Transcripts {
    private const val MAGIC = 0x41575458 // "AWTX"
    private const val VERSION = 4 // v2 added summary; v3 chatName; v4 source; older stores migrate with ""

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

    /** Records updated after [sinceMs], oldest first (cloud-sync push cursor). */
    @Synchronized fun changedSince(sinceMs: Long): List<StoredTranscript> =
        map.values.filter { it.updatedAt > sinceMs }.sortedBy { it.updatedAt }

    /**
     * Merge remote state (cloud-sync pull): last-write-wins upserts + tombstone deletes.
     * Deletes are LWW too — a tombstone only wins over a LOCAL record that is older than the
     * deletion, so a note re-transcribed after its delete can't be re-killed by a stale
     * tombstone echoing back from the server. One save() for the whole batch.
     * Returns how many records changed.
     */
    @Synchronized fun applyRemote(upserts: List<StoredTranscript>, deletions: List<Pair<String, Long>>): Int {
        var changed = 0
        for (t in upserts) {
            val cur = map[t.key]
            if (cur == null || cur.updatedAt < t.updatedAt) {
                map[t.key] = t
                changed++
            }
        }
        for ((k, deletedAtMs) in deletions) {
            val cur = map[k] ?: continue
            if (cur.updatedAt < deletedAtMs && map.remove(k) != null) changed++
        }
        if (changed > 0) {
            save()
            AppLog.i("[transcripts] merged $changed change(s) from cloud.")
        }
        return changed
    }

    /** Cached transcript text for this file, or null. */
    @Synchronized fun find(f: File): String? = map[keyFor(f)]?.text

    /** Full stored record for this file (transcript + summary), or null. */
    @Synchronized fun entry(f: File): StoredTranscript? = map[keyFor(f)]

    /** Full stored record by its key (path|mtime|size). Used by the FCM push handler, which
     *  has the key from the push payload but not a File. */
    @Synchronized fun byKey(key: String): StoredTranscript? = map[key]

    /** Attach/replace the LLM summary on an existing record. No-op if the record is gone.
     *  Bumps updatedAt — the sync push cursor is changedSince(updatedAt), so a summary attached
     *  after its transcript was pushed would otherwise never reach the cloud. */
    @Synchronized fun putSummary(key: String, summary: String) {
        val t = map[key] ?: return
        map[key] = t.copy(summary = summary, updatedAt = System.currentTimeMillis())
        save()
    }

    @Synchronized fun put(f: File, text: String, chatName: String = "", keepSummary: Boolean = true, source: String = "") {
        val p = VoiceNotes.parseWhatsAppName(f.name)
        val existing = map[keyFor(f)]
        map[keyFor(f)] = StoredTranscript(
            key = keyFor(f), path = f.absolutePath, name = f.name,
            waDate = p?.dateYmd ?: -1, seq = p?.seq ?: -1,
            durationSec = -1.0, text = text, updatedAt = System.currentTimeMillis(),
            summary = if (keepSummary) existing?.summary ?: "" else "", // re-transcribe voids it
            chatName = chatName.ifEmpty { existing?.chatName ?: "" }, // never downgrade a known chat
            source = source.ifEmpty { existing?.source ?: "" }, // keep the known engine on re-stamps
        )
        save()
    }

    /** Delete a stored transcript so the note gets re-transcribed on-demand next play. */
    @Synchronized fun remove(key: String): Boolean {
        val removed = map.remove(key) != null
        if (removed) {
            save()
            AppLog.i("[transcripts] removed $key — will re-transcribe on next play.")
        }
        return removed
    }

    // NOTE: search lives in SearchEngine (Search.kt) — it needs cross-script normalization
    // and must scan a snapshot (via all()) so it never holds this store's lock.

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
            if (din.readInt() != MAGIC) { AppLog.w("[transcripts] bad magic — ignoring."); return }
            val version = din.readInt()
            if (version !in 1..VERSION) { AppLog.w("[transcripts] unknown version $version — ignoring."); return }
            val n = din.readInt()
            repeat(n.coerceAtLeast(0)) {
                val key = din.readUTF(); val path = din.readUTF(); val name = din.readUTF()
                val waDate = din.readInt(); val seq = din.readInt(); val dur = din.readDouble()
                val text = din.readUTF(); val updated = din.readLong()
                val summary = if (version >= 2) din.readUTF() else "" // v1 → migrate with no summary
                val chatName = if (version >= 3) din.readUTF() else "" // v1/v2 → unknown chat
                val source = if (version >= 4) din.readUTF() else "" // v1-v3 → unknown engine
                map[key] = StoredTranscript(key, path, name, waDate, seq, dur, text, updated, summary, chatName, source)
            }
            if (version < VERSION) AppLog.i("[transcripts] migrated store v$version → v$VERSION (${map.size} records).")
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
                dos.writeUTF(t.summary)
                dos.writeUTF(t.chatName)
                dos.writeUTF(t.source)
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
