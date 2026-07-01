package com.example.antiwispr

import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32

/** One file's persisted fingerprint (hashes stored as int — 26 bits fits). */
class StoredFile(
    val path: String,
    val mtime: Long,
    val size: Long,
    val durationSec: Double,
    val name: String,
    val hashes: IntArray,
    val times: IntArray
)

/**
 * Custom binary persistence for the fingerprint index (no external serialization dependency).
 * Layout: MAGIC, FORMAT_VERSION, PARAMS_SIGNATURE, fileCount, [per-file...], then CRC32 over the
 * preceding bytes. Save is atomic (temp + fsync + rename). Load returns null on any corruption /
 * version / params mismatch so the caller can rebuild from scratch.
 */
object IndexStore {
    private const val MAGIC = 0x41575058 // "AWPX"
    private const val FORMAT_VERSION = 1

    fun save(file: File, files: List<StoredFile>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        val fos = FileOutputStream(tmp)
        try {
            val crc = CRC32()
            val dos = DataOutputStream(BufferedOutputStream(CrcOut(fos, crc)))
            dos.writeInt(MAGIC)
            dos.writeInt(FORMAT_VERSION)
            dos.writeInt(IndexConfig.paramsSignature())
            dos.writeInt(files.size)
            for (f in files) {
                dos.writeUTF(f.path)
                dos.writeLong(f.mtime)
                dos.writeLong(f.size)
                dos.writeDouble(f.durationSec)
                dos.writeUTF(f.name)
                dos.writeInt(f.hashes.size)
                for (h in f.hashes) dos.writeInt(h)
                for (t in f.times) dos.writeInt(t)
            }
            dos.flush()
            val crcVal = crc.value
            // append CRC directly to the underlying stream (not counted in the checksum)
            val tail = DataOutputStream(fos)
            tail.writeLong(crcVal)
            tail.flush()
            fos.fd.sync()
        } finally {
            try { fos.close() } catch (_: Exception) {}
        }
        if (!tmp.renameTo(file)) {
            // renameTo can fail if target exists on some FS; replace explicitly
            file.delete()
            if (!tmp.renameTo(file)) AppLog.e("[indexstore] rename failed for ${file.name}")
        }
        AppLog.i("[indexstore] saved ${files.size} fingerprints (${file.length()} bytes).")
    }

    fun load(file: File): List<StoredFile>? {
        if (!file.exists()) return null
        val raw = try { file.readBytes() } catch (e: Exception) {
            AppLog.e("[indexstore] read failed: ${e.message}"); return null
        }
        if (raw.size < 24) { AppLog.w("[indexstore] file too small — ignoring."); return null }

        // verify trailing CRC over the body
        val crc = CRC32(); crc.update(raw, 0, raw.size - 8)
        val storedCrc = ByteBuffer.wrap(raw, raw.size - 8, 8).long
        if (crc.value != storedCrc) { AppLog.w("[indexstore] CRC mismatch — index corrupt, rebuilding."); return null }

        return try {
            val din = DataInputStream(ByteArrayInputStream(raw, 0, raw.size - 8))
            if (din.readInt() != MAGIC) { AppLog.w("[indexstore] bad magic."); return null }
            if (din.readInt() != FORMAT_VERSION) { AppLog.w("[indexstore] version mismatch — rebuilding."); return null }
            if (din.readInt() != IndexConfig.paramsSignature()) { AppLog.w("[indexstore] fingerprint params changed — rebuilding."); return null }
            val count = din.readInt()
            if (count < 0 || count > 1_000_000) { AppLog.w("[indexstore] insane file count $count."); return null }
            val out = ArrayList<StoredFile>(count)
            for (i in 0 until count) {
                val path = din.readUTF()
                val mtime = din.readLong()
                val size = din.readLong()
                val dur = din.readDouble()
                val name = din.readUTF()
                val hc = din.readInt()
                if (hc < 0 || hc > 50_000_000) { AppLog.w("[indexstore] insane hashCount."); return null }
                val hashes = IntArray(hc) { din.readInt() }
                val times = IntArray(hc) { din.readInt() }
                out.add(StoredFile(path, mtime, size, dur, name, hashes, times))
            }
            AppLog.i("[indexstore] loaded ${out.size} fingerprints from disk.")
            out
        } catch (e: Exception) {
            AppLog.e("[indexstore] parse failed: ${e.message} — rebuilding.")
            null
        }
    }

    /** OutputStream wrapper that updates a CRC32 as bytes pass through. */
    private class CrcOut(private val out: java.io.OutputStream, private val crc: CRC32) : java.io.OutputStream() {
        override fun write(b: Int) { out.write(b); crc.update(b) }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); crc.update(b, off, len) }
        override fun flush() { out.flush() }
        override fun close() { out.flush() } // do not close underlying; caller owns fos
    }
}
