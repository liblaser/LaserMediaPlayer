package io.github.pierrebog.lasermediaplayer.disc

import android.util.Log
import io.github.pierrebog.lasermediaplayer.LASER_TAG
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/* What the disc lookups share: their HTTP, and their stores' files and ids. */

/** The lookups' HTTP: TMDB, MusicBrainz and the Cover Art Archive. */
object Http {
    private const val TIMEOUT_MS = 10_000

    /** A connection to [url], trusting [ExtraRoots], with timeouts; it names
     * itself [userAgent] if there is one, and asks for JSON if [json]. */
    fun open(url: String, userAgent: String? = null, json: Boolean = false): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            ExtraRoots.apply(this)
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            if (userAgent != null) setRequestProperty("User-Agent", userAgent)
            if (json) setRequestProperty("Accept", "application/json")
        }

    /** Downloads [url] to [dest], through a temporary file: [dest] is whole,
     * or untouched. Null if there is nothing there, or it cannot be had. */
    fun download(url: String, dest: File, userAgent: String? = null): File? {
        val tmp = File(dest.path + ".tmp")
        return try {
            val connection = open(url, userAgent)
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
                connection.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            } finally {
                connection.disconnect()
            }
            if (tmp.renameTo(dest)) dest else null
        } catch (e: Exception) {
            Log.w(LASER_TAG, "cannot download $url", e)
            null
        } finally {
            tmp.delete()
        }
    }
}

/**
 * Writes [text] to [file] through a temporary file, so that a reader never
 * sees half of it; creates the directory.
 *
 * @throws IOException if it cannot
 */
fun writeAtomically(file: File, text: String) {
    file.parentFile?.mkdirs()
    val tmp = File(file.path + ".tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(file)) {
        tmp.delete()
        throw IOException("cannot rename $tmp to $file")
    }
}

/** A short id for [text]: the start of its SHA-1, 20 hexadecimal digits. */
fun shortHash(text: String): String =
    MessageDigest.getInstance("SHA-1").digest(text.toByteArray())
        .take(10).joinToString("") { "%02x".format(it) }
