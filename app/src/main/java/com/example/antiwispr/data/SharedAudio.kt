package com.example.antiwispr.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.example.antiwispr.core.AppLog
import java.io.File
import java.io.FileNotFoundException

/**
 * Materializes an ACTION_SEND audio stream into a real file so the File-keyed pipeline
 * (Transcripts.keyFor = path|mtime|size, cloud upload, MediaPlayer playback) can use it — and
 * keep using it after restarts, which is why copies land in filesDir/shared/, never cacheDir.
 * When the shared stream is a WhatsApp voice note that already exists on disk (same name and
 * size under a known voice-notes folder), that original is returned instead of a copy, so the
 * transcript attaches to the real note. Blocking — worker threads only.
 */
object SharedAudio {

    sealed interface Result {
        /** [reused] = an existing file (WhatsApp original or an earlier copy) was matched;
         *  the store key is identical, so the router's cache can short-circuit. */
        data class Ok(val file: File, val reused: Boolean) : Result

        /** Product-language message, ready to render. */
        data class Fail(val message: String) : Result
    }

    private const val MAX_BYTES = 200L * 1024 * 1024

    private val audioExtensions = setOf("opus", "ogg", "oga", "m4a", "mp3", "wav", "aac", "amr", "flac")

    fun dir(context: Context): File = File(context.filesDir, "shared")

    /** True when a transcript's audio lives in shared/ — TACIT owns it and may delete it
     *  alongside the record (WhatsApp originals are deliberately never touched). */
    fun owns(context: Context, path: String): Boolean =
        path.startsWith(dir(context).absolutePath + File.separator)

    fun materialize(context: Context, uri: Uri): Result {
        val resolver = context.applicationContext.contentResolver
        val mime = resolver.getType(uri)

        // Display name + declared size in one cursor query; both may be missing (file:// URIs
        // have no cursor at all), so fall back to the URI path and an epoch-stamped name.
        var displayName: String? = null
        var declaredSize = -1L
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) displayName = c.getString(nameIdx)
                    if (sizeIdx >= 0 && !c.isNull(sizeIdx)) declaredSize = c.getLong(sizeIdx)
                }
            }
        } catch (e: Exception) {
            AppLog.w("[share] metadata query failed: ${e.message}")
        }
        if (displayName.isNullOrBlank()) displayName = uri.lastPathSegment?.substringAfterLast('/')

        val name = buildFileName(displayName, mime)
        if (!looksLikeAudio(mime, name)) {
            AppLog.w("[share] rejected non-audio share: mime=$mime name=$name")
            return Result.Fail("That doesn't look like an audio file.")
        }

        // The user's own scenario: the shared audio IS a WhatsApp voice note already on this
        // phone (the overlay just failed to identify it). Transcribing the original in place
        // keeps one record per note and matches the fingerprint index's key.
        if (declaredSize > 0 && Environment.isExternalStorageManager()) {
            displayName?.let { shared ->
                findOriginal(shared, declaredSize)?.let {
                    AppLog.i("[share] matched WhatsApp original ${it.absolutePath}, no copy needed.")
                    return Result.Ok(it, reused = true)
                }
            }
        }

        val dir = dir(context)
        if (!dir.isDirectory && !dir.mkdirs()) return Result.Fail("TACIT couldn't save that audio. Try again.")
        dir.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() } // stale, killed mid-copy

        // Re-share of something already brought in: same name + size = same file, reuse it so
        // the key matches and the reader opens instantly from cache.
        var target = File(dir, name)
        if (target.exists()) {
            if (declaredSize <= 0 || target.length() == declaredSize) {
                AppLog.i("[share] reusing earlier copy ${target.name}.")
                return Result.Ok(target, reused = true)
            }
            target = File(dir, "${name.substringBeforeLast('.')}-${System.currentTimeMillis()}.${name.substringAfterLast('.')}")
        }

        val part = File(dir, "${target.name}.part")
        var copied = 0L
        try {
            val input = resolver.openInputStream(uri)
                ?: return Result.Fail("TACIT couldn't read that audio. Try again.")
            input.use { src ->
                part.outputStream().use { out ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = src.read(buf)
                        if (n < 0) break
                        copied += n
                        if (copied > MAX_BYTES) {
                            part.delete()
                            return Result.Fail("That file is too large.")
                        }
                        out.write(buf, 0, n)
                    }
                }
            }
        } catch (e: FileNotFoundException) {
            part.delete()
            AppLog.w("[share] stream unavailable: ${e.message}")
            return Result.Fail("TACIT couldn't read that audio. Try again.")
        } catch (e: SecurityException) {
            part.delete()
            AppLog.w("[share] read permission missing: ${e.message}")
            return Result.Fail("TACIT couldn't read that audio. Try sharing it again.")
        } catch (e: Exception) {
            part.delete()
            AppLog.w("[share] copy failed: ${e.message}")
            return Result.Fail("TACIT couldn't save that audio. Try again.")
        }
        if (copied == 0L) {
            part.delete()
            return Result.Fail("That audio file is empty.")
        }
        if (!part.renameTo(target)) {
            part.delete()
            return Result.Fail("TACIT couldn't save that audio. Try again.")
        }
        AppLog.i("[share] materialized ${target.name} ($copied B) from $uri")
        return Result.Ok(target, reused = false)
    }

    /** A safe on-disk name: the sender's display name when usable, cleaned of the store-key
     *  delimiter '|' and path characters; otherwise an epoch-stamped fallback. */
    private fun buildFileName(displayName: String?, mime: String?): String {
        val cleaned = displayName
            ?.replace(Regex("""[|/\\\p{Cntrl}]"""), "_")
            ?.trim()?.trimStart('.')
            ?.takeIf { it.isNotBlank() }
        if (cleaned != null) return cleaned
        val ext = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) } ?: "opus"
        return "shared-${System.currentTimeMillis()}.$ext"
    }

    private fun looksLikeAudio(mime: String?, name: String): Boolean = when {
        mime != null && mime.startsWith("audio/") -> true
        mime == "application/ogg" || mime == "application/octet-stream" -> true
        else -> name.substringAfterLast('.', "").lowercase() in audioExtensions
    }

    /** The shared file's original under a WhatsApp voice-notes folder, matched by exact name +
     *  byte length (WA filenames are unique per media, so this pairing is unambiguous). */
    private fun findOriginal(name: String, size: Long): File? {
        for (root in VoiceNotes.resolveFolders()) {
            findIn(root, name, size)?.let { return it }
        }
        return null
    }

    private fun findIn(dir: File, name: String, size: Long): File? {
        val children = dir.listFiles() ?: return null
        for (c in children) {
            if (c.isDirectory) findIn(c, name, size)?.let { return it }
            else if (c.name == name && c.length() == size) return c
        }
        return null
    }
}
