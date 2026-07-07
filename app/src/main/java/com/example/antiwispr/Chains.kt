package com.example.antiwispr

import android.content.Context
import java.io.File
import kotlin.math.abs

/**
 * The consecutive same-sender voice-note run around the played note, read from the live chat
 * (the accessibility tree). [durations] are per-bubble lengths in SECONDS in chat order; the
 * played note sits at [anchorIndex] (its own entry may be null while it is playing — the anchor
 * file is identified acoustically regardless). Membership is already chat-scoped and
 * sender-filtered by the reader, so a run never spans different chats.
 */
data class VoiceRun(val durations: List<Double?>, val anchorIndex: Int)

/**
 * A burst of consecutive voice notes from ONE sender in ONE chat. Membership comes from the chat
 * UI (the live overlay via [Chains.chainFrom]) or, offline in the app, from the transcript store
 * (via [Chains.storeChainFor]) — NOT from the old device-global WA#### + mtime heuristic, which
 * merged notes from different chats. Users can detach a note (see [ChainOverrides]).
 */
data class Chain(
    val files: List<File>,
    val date: Int,
    val firstSeq: Int,
    val lastSeq: Int,
    val chatName: String?,
) {
    val id: String get() = "$date:$firstSeq-$lastSeq"
    val size: Int get() = files.size
    fun partIndexOf(file: File): Int =
        files.indexOfFirst { it.absolutePath == file.absolutePath } + 1
}

object Chains {

    private const val DURATION_TOLERANCE_SEC = 2.0
    // Interleaved non-matching files (other chats' media that bumped the global WA#### counter)
    // to skip while looking for the next run member before giving up that direction.
    private const val MAX_SKIP = 6
    // Max download-time gap between neighbors to still count as one burst. Used chat-scoped, as a
    // proximity proxy only (never compared to the chat clock — file mtime is download time).
    private const val MAX_NEIGHBOR_GAP_MS = 10 * 60 * 1000L

    // ---- live overlay path (accessibility run + acoustic anchor) ----------------

    /**
     * The chain [anchorFile] belongs to, derived from the chat [run] read at play-tap. The run is
     * the source of truth for membership (which notes, order, per-member duration, all one sender
     * in one chat, already time-gap filtered); we only map each non-anchor member to a file.
     *
     * Mapping uses ORDER + DURATION, never absolute time (chat timestamp = arrival, file mtime =
     * download). We order on-disk voice notes by download order, anchor on the acoustically-
     * identified played file, and walk outward matching durations, skipping interleaved foreign
     * media and stopping at a user-detached note. Returns null for a lone note or a detached anchor.
     */
    fun chainFrom(context: Context, anchorFile: File, run: VoiceRun?): Chain? {
        if (run == null || run.durations.size < 2) return null
        val anchorParsed = VoiceNotes.parseWhatsAppName(anchorFile.name) ?: return null
        val overrides = ChainOverrides.get(context)
        if (overrides.isDetached(ChainOverrides.idOf(anchorParsed.dateYmd, anchorParsed.seq))) return null

        val files = VoiceNotes.listOpusFiles()
            .filter { VoiceNotes.parseWhatsAppName(it.name) != null }
            .sortedBy { it.lastModified() } // download order (self-consistent, not arrival order)
        val anchorPos = files.indexOfFirst { it.absolutePath == anchorFile.absolutePath }
        if (anchorPos < 0) return null

        val slots = arrayOfNulls<File>(run.durations.size)
        slots[run.anchorIndex] = anchorFile
        mapOutward(run, anchorPos, files, slots, overrides, forward = false) // earlier members
        mapOutward(run, anchorPos, files, slots, overrides, forward = true)  // later members

        val members = slots.filterNotNull() // contiguous run around the anchor (gaps only at ends)
        if (members.size < 2) return null

        val seqs = members.mapNotNull { VoiceNotes.parseWhatsAppName(it.name)?.seq }
        val store = Transcripts.get(context)
        val chat = members.firstNotNullOfOrNull { store.entry(it)?.chatName?.ifEmpty { null } }
        return Chain(members, anchorParsed.dateYmd, seqs.minOrNull() ?: anchorParsed.seq,
            seqs.maxOrNull() ?: anchorParsed.seq, chat)
    }

    private fun mapOutward(
        run: VoiceRun, anchorPos: Int, files: List<File>, slots: Array<File?>,
        overrides: ChainOverrides, forward: Boolean,
    ) {
        val memberIdxs = if (forward) (run.anchorIndex + 1 until run.durations.size).toList()
        else (run.anchorIndex - 1 downTo 0).toList()
        var pos = anchorPos
        for (mi in memberIdxs) {
            val target = run.durations[mi] ?: return // no duration for this member → can't map → stop
            var skip = 0
            var found: File? = null
            while (skip <= MAX_SKIP) {
                pos += if (forward) 1 else -1
                if (pos < 0 || pos >= files.size) break
                val d = VoiceNotes.readDurationSec(files[pos])
                if (d > 0 && abs(d - target) <= DURATION_TOLERANCE_SEC) {
                    val p = VoiceNotes.parseWhatsAppName(files[pos].name)
                    if (p != null && overrides.isDetached(ChainOverrides.idOf(p.dateYmd, p.seq))) return // detached member = boundary
                    found = files[pos]; break
                }
                skip++
            }
            if (found == null) return // couldn't place this member → chain ends here on this side
            slots[mi] = found
        }
    }

    // ---- offline app path (transcript store) ------------------------------------

    /**
     * The chain a STORED note belongs to, reconstructed from the transcript store — for the app
     * reader (offline, no live chat). Groups the note's chat into bursts (see [clusterByTime]) and
     * returns the burst containing [note], or null for a lone/detached note. Only transcribed
     * notes are visible here.
     */
    fun storeChainFor(context: Context, note: StoredTranscript): Chain? {
        if (note.waDate <= 0 || note.seq < 0) return null
        val overrides = ChainOverrides.get(context)
        if (overrides.isDetached(ChainOverrides.idOf(note.waDate, note.seq))) return null
        val store = Transcripts.get(context)
        val sameChat = store.all().filter {
            it.seq >= 0 && it.waDate > 0 && it.chatName == note.chatName &&
                !overrides.isDetached(ChainOverrides.idOf(it.waDate, it.seq))
        }
        val cluster = clusterByTime(sameChat).firstOrNull { c -> c.any { it.seq == note.seq } } ?: return null
        if (cluster.size < 2) return null
        val seqs = cluster.map { it.seq }
        return Chain(
            files = cluster.map { File(it.path) },
            date = note.waDate,
            firstSeq = seqs.min(),
            lastSeq = seqs.max(),
            chatName = note.chatName.ifEmpty { null },
        )
    }

    /**
     * The chain a note belongs to for the APP reader. Prefers the overlay-detected persisted burst
     * (includes notes not transcribed yet, so the reader can offer "Transcribe all"), falling back
     * to the store-derived cluster. Honors detach overrides. Null for a lone or detached note.
     */
    fun chainForNote(context: Context, note: StoredTranscript): Chain? {
        if (note.waDate <= 0 || note.seq < 0) return null
        val overrides = ChainOverrides.get(context)
        val noteId = ChainOverrides.idOf(note.waDate, note.seq)
        if (overrides.isDetached(noteId)) return null

        val persisted = ChainMemberships.get(context).forNote(noteId)
            ?.filter { !overrides.isDetached(it.id) }
        if (persisted != null && persisted.size >= 2 && persisted.any { it.id == noteId }) {
            val seqs = persisted.map { it.seq }
            return Chain(
                files = persisted.map { File(it.path) },
                date = note.waDate,
                firstSeq = seqs.min(),
                lastSeq = seqs.max(),
                chatName = note.chatName.ifEmpty { null },
            )
        }
        return storeChainFor(context, note)
    }

    /**
     * Keys of all stored transcripts that belong to some chain — for list badges. Unions
     * store-derived bursts (transcribed notes clustered by chat + time) with the overlay-detected
     * persisted bursts. Reads file mtimes, so callers should cache the result (see AppViewModel)
     * rather than recompute every poll.
     */
    fun memberKeys(context: Context): Set<String> {
        val store = Transcripts.get(context)
        val overrides = ChainOverrides.get(context)
        val memberships = ChainMemberships.get(context)
        val out = HashSet<String>()
        val notes = store.all().filter {
            it.seq >= 0 && it.waDate > 0 && !overrides.isDetached(ChainOverrides.idOf(it.waDate, it.seq))
        }
        // Store-derived clusters (transcribed-only bursts).
        for ((_, group) in notes.groupBy { it.chatName }) {
            for (cluster in clusterByTime(group)) {
                if (cluster.size >= 2) cluster.forEach { out.add(it.key) }
            }
        }
        // Overlay-detected bursts: badge any transcribed note that belongs to one (>=2 live members).
        for (n in notes) {
            val burst = memberships.forNote(ChainOverrides.idOf(n.waDate, n.seq))
                ?.filter { !overrides.isDetached(it.id) }
            if (burst != null && burst.size >= 2) out.add(n.key)
        }
        return out
    }

    /**
     * Split one chat's notes into bursts: sorted by WA#### seq (chronological within a chat), a
     * new burst starts wherever the download-time gap to the previous note exceeds the window.
     * Seq gaps are allowed (other chats' media interleave the global counter) — a burst is defined
     * by time proximity within the chat, not strict seq contiguity.
     */
    private fun clusterByTime(notes: List<StoredTranscript>): List<List<StoredTranscript>> {
        val sorted = notes.distinctBy { it.seq }.sortedBy { it.seq }
        val out = ArrayList<List<StoredTranscript>>()
        var cur = ArrayList<StoredTranscript>()
        for (t in sorted) {
            if (cur.isEmpty() || closeEnough(cur.last(), t)) cur.add(t)
            else { out.add(cur); cur = arrayListOf(t) }
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    /** Two neighbors are one burst only if their audio files landed within the gap (download-time
     *  proxy — the store has no arrival time). Missing files (a cloud-restored library without
     *  audio) skip the check and fall back to seq order within the chat. */
    private fun closeEnough(a: StoredTranscript, b: StoredTranscript): Boolean {
        val ma = File(a.path).lastModified()
        val mb = File(b.path).lastModified()
        if (ma <= 0L || mb <= 0L) return true
        return abs(ma - mb) <= MAX_NEIGHBOR_GAP_MS
    }
}
