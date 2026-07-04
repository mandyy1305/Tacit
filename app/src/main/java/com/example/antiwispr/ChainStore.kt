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

/** One cached chain summary, keyed by "date:firstSeq-lastSeq". */
data class StoredChainSummary(val id: String, val raw: String, val count: Int, val updatedAt: Long)

/**
 * On-disk cache of chain summaries (chains.bin) — same binary idiom as [Transcripts]
 * (magic/version/count/records/CRC32, atomic temp+rename). Local-only: chain summaries
 * regenerate cheaply, so they're not synced (yet).
 */
object ChainSummaries {
    private const val MAGIC = 0x41574353 // "AWCS"
    private const val VERSION = 1

    @Volatile private var loaded = false
    private lateinit var file: File
    private val map = LinkedHashMap<String, StoredChainSummary>()

    fun get(context: Context): ChainSummaries {
        if (!loaded) synchronized(this) {
            if (!loaded) {
                file = File(context.applicationContext.filesDir, "chains.bin")
                load()
                loaded = true
                AppLog.i("[chains] loaded ${map.size} chain summar${if (map.size == 1) "y" else "ies"}.")
            }
        }
        return this
    }

    @Synchronized fun find(id: String): StoredChainSummary? = map[id]

    @Synchronized fun put(id: String, raw: String, count: Int) {
        map[id] = StoredChainSummary(id, raw, count, System.currentTimeMillis())
        save()
    }

    @Synchronized fun remove(id: String) {
        if (map.remove(id) != null) save()
    }

    // ---- persistence ------------------------------------------------------------

    private fun load() {
        map.clear()
        if (!file.exists()) return
        val raw = try { file.readBytes() } catch (e: Exception) { AppLog.e("[chains] read failed: ${e.message}"); return }
        if (raw.size < 16) return
        val crc = CRC32(); crc.update(raw, 0, raw.size - 8)
        if (crc.value != ByteBuffer.wrap(raw, raw.size - 8, 8).long) { AppLog.w("[chains] CRC mismatch — ignoring store."); return }
        try {
            val din = DataInputStream(ByteArrayInputStream(raw, 0, raw.size - 8))
            if (din.readInt() != MAGIC || din.readInt() != VERSION) { AppLog.w("[chains] bad header — ignoring."); return }
            repeat(din.readInt().coerceAtLeast(0)) {
                val id = din.readUTF(); val text = din.readUTF()
                val count = din.readInt(); val updated = din.readLong()
                map[id] = StoredChainSummary(id, text, count, updated)
            }
        } catch (e: Exception) {
            AppLog.e("[chains] parse failed: ${e.message} — starting empty."); map.clear()
        }
    }

    private fun save() {
        val tmp = File(file.parentFile, file.name + ".tmp")
        val fos = FileOutputStream(tmp)
        try {
            val crc = CRC32()
            val dos = DataOutputStream(BufferedOutputStream(CrcOut(fos, crc)))
            dos.writeInt(MAGIC); dos.writeInt(VERSION); dos.writeInt(map.size)
            for (c in map.values) {
                dos.writeUTF(c.id); dos.writeUTF(c.raw)
                dos.writeInt(c.count); dos.writeLong(c.updatedAt)
            }
            dos.flush()
            DataOutputStream(fos).writeLong(crc.value)
            fos.fd.sync()
        } catch (e: Exception) {
            AppLog.e("[chains] save failed: ${e.message}", e); return
        } finally {
            try { fos.close() } catch (_: Exception) {}
        }
        file.delete(); if (!tmp.renameTo(file)) AppLog.e("[chains] rename failed.")
    }

    private class CrcOut(private val out: OutputStream, private val crc: CRC32) : OutputStream() {
        override fun write(b: Int) { out.write(b); crc.update(b) }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); crc.update(b, off, len) }
        override fun flush() { out.flush() }
        override fun close() { out.flush() }
    }
}
