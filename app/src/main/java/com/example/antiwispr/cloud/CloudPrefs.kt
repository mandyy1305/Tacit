package com.example.antiwispr.cloud

import android.content.Context
import android.content.SharedPreferences

/** Cloud settings + sync bookkeeping (SharedPreferences; survives process death). */
object CloudPrefs {

    private const val FILE = "tacit_cloud"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun baseUrl(ctx: Context): String =
        prefs(ctx).getString("base_url", "")!!.trim().trimEnd('/')

    fun setBaseUrl(ctx: Context, url: String) =
        prefs(ctx).edit().putString("base_url", url.trim()).apply()

    fun cloudTranscription(ctx: Context): Boolean = prefs(ctx).getBoolean("cloud_transcription", true)
    fun setCloudTranscription(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("cloud_transcription", v).apply()

    fun cloudSummaries(ctx: Context): Boolean = prefs(ctx).getBoolean("cloud_summaries", true)
    fun setCloudSummaries(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("cloud_summaries", v).apply()

    fun lastPullMs(ctx: Context): Long = prefs(ctx).getLong("last_pull_ms", 0L)
    fun setLastPullMs(ctx: Context, v: Long) = prefs(ctx).edit().putLong("last_pull_ms", v).apply()

    fun lastPushMs(ctx: Context): Long = prefs(ctx).getLong("last_push_ms", 0L)
    fun setLastPushMs(ctx: Context, v: Long) = prefs(ctx).edit().putLong("last_push_ms", v).apply()

    fun lastSyncMs(ctx: Context): Long = prefs(ctx).getLong("last_sync_ms", 0L)
    fun setLastSyncMs(ctx: Context, v: Long) = prefs(ctx).edit().putLong("last_sync_ms", v).apply()

    /** Deletions waiting to be pushed as tombstones, encoded "key|deletedAtMs". */
    fun pendingTombstones(ctx: Context): Set<String> =
        prefs(ctx).getStringSet("pending_tombstones", emptySet())!!.toSet()

    fun addTombstone(ctx: Context, key: String, deletedAtMs: Long) {
        val cur = pendingTombstones(ctx).toMutableSet()
        cur += "$key|$deletedAtMs"
        prefs(ctx).edit().putStringSet("pending_tombstones", cur).apply()
    }

    fun clearTombstones(ctx: Context, entries: Set<String>) {
        val cur = pendingTombstones(ctx).toMutableSet()
        cur -= entries
        prefs(ctx).edit().putStringSet("pending_tombstones", cur).apply()
    }

    /** Reset sync cursors (on sign-out or account switch) so the next sync is full. */
    fun resetSyncState(ctx: Context) {
        prefs(ctx).edit()
            .remove("last_pull_ms").remove("last_push_ms").remove("last_sync_ms")
            .remove("pending_tombstones")
            .apply()
    }
}
