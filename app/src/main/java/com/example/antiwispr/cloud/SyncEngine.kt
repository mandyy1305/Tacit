package com.example.antiwispr.cloud

import android.content.Context
import com.example.antiwispr.AppLog
import com.example.antiwispr.StoredTranscript
import com.example.antiwispr.Transcripts
import java.util.concurrent.Executors

/**
 * Push/pull sync of the transcript library against tacit-cloud.
 * Push: records changed since the push cursor + queued deletion tombstones.
 * Pull: pages of records changed since the pull cursor, merged last-write-wins.
 * All work on one background thread; re-entrant calls collapse into one queued run.
 */
object SyncEngine {

    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "cloud-sync").apply { isDaemon = true } }

    @Volatile var syncing: Boolean = false
        private set
    @Volatile private var queued = false

    /** Fire-and-forget; safe to call from anywhere, any thread. */
    fun requestSync(ctx: Context) {
        if (!CloudClient.ready(ctx)) return
        if (syncing) { queued = true; return }
        syncing = true
        val app = ctx.applicationContext
        exec.execute {
            try {
                do {
                    queued = false
                    doSync(app)
                } while (queued)
            } finally {
                syncing = false
            }
        }
    }

    /**
     * Blocking sync on the CALLING thread — for the FCM push handler, which runs off the main
     * thread and needs the just-pushed record present before it decides overlay-vs-notification.
     * Reuses the same push+pull as the queued path; the store merge is @Synchronized, so running
     * alongside a queued sync is safe. Returns false if cloud isn't ready.
     */
    fun syncNow(ctx: Context): Boolean {
        if (!CloudClient.ready(ctx)) return false
        return try {
            doSync(ctx.applicationContext); true
        } catch (t: Throwable) {
            AppLog.w("[cloud] syncNow failed: ${t.message}"); false
        }
    }

    /** Record a local deletion so it tombstones on the next sync. */
    fun queueDeletion(ctx: Context, key: String) {
        CloudPrefs.addTombstone(ctx, key, System.currentTimeMillis())
        requestSync(ctx)
    }

    private fun doSync(ctx: Context) {
        val store = Transcripts.get(ctx)
        val t0 = System.currentTimeMillis()

        // ---- push --------------------------------------------------------------
        val changed = store.changedSince(CloudPrefs.lastPushMs(ctx))
        val tombstoneEntries = CloudPrefs.pendingTombstones(ctx)
        val tombstones = tombstoneEntries.mapNotNull { entry ->
            // The key itself contains '|' (path|mtime|size) — the deletion time is the LAST part.
            val ts = entry.substringAfterLast("|").toLongOrNull() ?: return@mapNotNull null
            val key = entry.substringBeforeLast("|")
            if (key.isEmpty()) return@mapNotNull null
            RemoteTranscript(
                key = key, path = "", name = "", waDate = -1, seq = -1,
                text = "", summary = "", chatName = "", updatedAtMs = ts, deletedAtMs = ts,
            )
        }
        val toPush = changed.map { it.toRemote() } + tombstones
        if (toPush.isNotEmpty()) {
            var ok = true
            toPush.chunked(500).forEach { chunk -> ok = ok && CloudClient.pushTranscripts(ctx, chunk) }
            if (ok) {
                changed.maxOfOrNull { it.updatedAt }?.let { CloudPrefs.setLastPushMs(ctx, it) }
                if (tombstoneEntries.isNotEmpty()) CloudPrefs.clearTombstones(ctx, tombstoneEntries)
                AppLog.i("[cloud] pushed ${changed.size} record(s), ${tombstones.size} tombstone(s).")
            } else {
                AppLog.w("[cloud] push incomplete — will retry next sync.")
                return
            }
        }

        // ---- pull --------------------------------------------------------------
        var since = CloudPrefs.lastPullMs(ctx)
        var totalMerged = 0
        while (true) {
            val page = CloudClient.pullTranscripts(ctx, since) ?: run {
                AppLog.w("[cloud] pull failed — will retry next sync.")
                return
            }
            if (page.isEmpty()) break
            val upserts = page.filter { it.deletedAtMs == 0L }.map { r ->
                StoredTranscript(
                    key = r.key, path = r.path, name = r.name,
                    waDate = r.waDate, seq = r.seq, durationSec = -1.0,
                    text = r.text, updatedAt = r.updatedAtMs, summary = r.summary,
                    chatName = r.chatName, source = r.source,
                )
            }
            val deletions = page.filter { it.deletedAtMs > 0L }.map { it.key to it.deletedAtMs }
            totalMerged += store.applyRemote(upserts, deletions)
            since = page.maxOf { it.updatedAtMs }
            CloudPrefs.setLastPullMs(ctx, since)
            if (page.size < 1000) break
        }

        CloudPrefs.setLastSyncMs(ctx, System.currentTimeMillis())
        AppLog.i("[cloud] sync done in ${System.currentTimeMillis() - t0} ms (merged $totalMerged).")
    }
}
