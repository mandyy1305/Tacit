package com.example.antiwispr

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.concurrent.Executors

/** Process-wide single owner of the [Indexer] (MainActivity and the a11y service share it). */
object IndexHolder {
    @Volatile private var instance: Indexer? = null
    fun get(context: Context): Indexer {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            return Indexer(context.applicationContext).also { instance = it }
        }
    }
}

/**
 * Builds and holds the fingerprint index. Each voice note is decoded + fingerprinted ONCE (first
 * [IndexConfig.INDEX_SECONDS]) and persisted; subsequent launches load from disk and only fingerprint
 * new/changed files (keyed by path|mtime|size). The query [snapshot] is published as a single
 * immutable object via a volatile, so readers are lock-free.
 */
class Indexer(private val appContext: Context) {

    @Volatile var snapshot: IndexSnapshot? = null
        private set
    @Volatile var building: Boolean = false
        private set
    @Volatile var status: String = "not built"
        private set

    // Determinate build progress for the UI (files processed / files to process); 0/0 when idle.
    @Volatile var progressDone: Int = 0
        private set
    @Volatile var progressTotal: Int = 0
        private set

    private val indexFile = File(appContext.filesDir, "fpindex.bin")
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "indexer").apply { isDaemon = true } }
    private val transcriber by lazy { WhisperTranscriber(appContext) } // only used for new-note auto-transcribe

    private fun key(path: String, mtime: Long, size: Long) = "$path|$mtime|$size"

    /** Load persisted index (fast), then incrementally fingerprint any new/changed files. */
    fun loadOrBuild(onProgress: (String) -> Unit) {
        if (building) { onProgress("[indexer] already building…"); return }
        exec.execute {
            building = true
            try {
                doBuild(onProgress)
            } catch (e: Exception) {
                AppLog.e("[indexer] build failed: ${e.message}", e)
                status = "build failed: ${e.message}"
            } finally {
                building = false
                progressDone = 0
                progressTotal = 0
            }
        }
    }

    private fun doBuild(onProgress: (String) -> Unit) {
        val persisted = IndexStore.load(indexFile)
        val firstBuild = persisted == null // don't auto-transcribe the whole backlog on the very first build
        val byKey = HashMap<String, StoredFile>()
        persisted?.forEach { byKey[key(it.path, it.mtime, it.size)] = it }

        val files = VoiceNotes.listOpusFiles()
        onProgress("[indexer] found ${files.size} voice notes; persisted=${persisted?.size ?: 0}.")
        if (files.size > IndexConfig.MAX_FILES) {
            AppLog.w("[indexer] ${files.size} files exceeds cap ${IndexConfig.MAX_FILES}; indexing first ${IndexConfig.MAX_FILES}.")
        }

        progressTotal = minOf(files.size, IndexConfig.MAX_FILES)
        progressDone = 0

        val result = ArrayList<StoredFile>(files.size)
        var decoded = 0
        var reused = 0
        for ((i, f) in files.withIndex()) {
            if (result.size >= IndexConfig.MAX_FILES) break
            progressDone = minOf(i + 1, progressTotal)
            val k = key(f.absolutePath, f.lastModified(), f.length())
            val existing = byKey[k]
            if (existing != null) {
                result.add(existing); reused++
            } else {
                val pcm = try {
                    OpusDecoder.decodeToMono16k(appContext, Uri.fromFile(f), IndexConfig.INDEX_SECONDS) { }
                } catch (e: Exception) {
                    AppLog.w("[indexer] decode failed ${f.name}: ${e.message}"); FloatArray(0)
                }
                if (pcm.isEmpty()) continue
                val fp = Fingerprinter.fingerprint(pcm)
                val hashesInt = IntArray(fp.size) { fp.hashes[it].toInt() } // 26-bit -> int
                result.add(StoredFile(f.absolutePath, f.lastModified(), f.length(), -1.0, f.name, hashesInt, fp.times))
                decoded++
                if (decoded % 20 == 0) onProgress("[indexer] fingerprinting ${i + 1}/${files.size} (new=$decoded reused=$reused)…")

                // New notes only: auto-transcribe genuinely-new arrivals (NOT the first-ever backlog),
                // if the model is ready and it isn't already cached. Old notes stay on-demand.
                if (!firstBuild && WhisperModel.isReady(appContext) && Transcripts.get(appContext).find(f) == null) {
                    try {
                        val text = transcriber.transcribe(f)
                        if (!text.startsWith("[")) {
                            Transcripts.get(appContext).put(f, text)
                            onProgress("[indexer] auto-transcribed new note ${f.name}.")
                        }
                    } catch (e: Exception) { AppLog.w("[indexer] auto-transcribe failed ${f.name}: ${e.message}") }
                }
            }
        }
        // Nothing new/removed and we already have a live snapshot? Skip the (3.7M-entry) re-sort +
        // save entirely — this makes the on-every-play-tap refresh near-free unless a note actually arrived.
        val existing = snapshot
        if (decoded == 0 && existing != null && existing.fileCount == result.size) {
            onProgress("[indexer] no new/changed notes — index unchanged (${result.size}).")
            return
        }

        onProgress("[indexer] fingerprinted: $decoded new, $reused reused. Building index…")

        val snap = buildSnapshot(result)
        snapshot = snap // single volatile publish — readers see old or new, never partial
        status = "indexed ${result.size} notes (${snap.hashCount} hashes)"

        // persist only if anything changed (avoid rewriting on pure reuse)
        if (decoded > 0 || persisted == null || persisted.size != result.size) {
            try { IndexStore.save(indexFile, result) } catch (e: Exception) { AppLog.e("[indexer] save failed: ${e.message}", e) }
        }
        onProgress("[indexer] ✅ ready: ${result.size} notes, ${snap.hashCount} hashes.")
    }

    private fun buildSnapshot(files: List<StoredFile>): IndexSnapshot {
        val metas = Array(files.size) { i ->
            val f = files[i]
            FileMeta(File(f.path), f.name, f.mtime, f.size, f.durationSec, f.hashes.size)
        }
        var total = 0L
        for (f in files) total += f.hashes.size
        val packed = LongArray(total.toInt())
        var idx = 0
        for (fileId in files.indices) {
            val hs = files[fileId].hashes
            val ts = files[fileId].times
            for (j in hs.indices) {
                packed[idx++] = IndexConfig.pack(hs[j].toLong() and 0xFFFFFFFFL, fileId, ts[j])
            }
        }
        java.util.Arrays.sort(packed) // sorts by hash (high bits)
        return IndexSnapshot(packed, metas)
    }
}
