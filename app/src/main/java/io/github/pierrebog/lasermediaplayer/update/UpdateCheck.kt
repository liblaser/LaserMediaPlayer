package io.github.pierrebog.lasermediaplayer.update

import android.util.Log
import io.github.pierrebog.lasermediaplayer.BuildConfig
import io.github.pierrebog.lasermediaplayer.LASER_TAG
import io.github.pierrebog.lasermediaplayer.disc.Http
import io.github.pierrebog.lasermediaplayer.ui.options.Credits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection

/** A release of the app newer than the one running: its version, as its tag
 * gives it without the "v", and its page. */
data class AppUpdate(val version: String, val url: String)

/**
 * Whether a newer version of the app is out: GitHub's latest release of the
 * app's repository - a regular one, drafts and pre-releases left out - whose
 * tag gives a higher version number than the one running. A debug build is
 * compared by its number too, without its "-debug".
 *
 * One request, without a token: GitHub allows sixty an hour from an address,
 * and the home screen asks once a day.
 */
object UpdateCheck {

    private const val LATEST = "https://api.github.com/repos/liblaser/LaserMediaPlayer/releases/latest"
    /* GitHub asks every request to name its client. */
    private const val USER_AGENT = "LaserMediaPlayer/${BuildConfig.VERSION_NAME} ( ${Credits.SOURCE} )"

    /** The newer release, or null: none, a tag that is not a version, or no
     * answer. Never throws but to cancel. */
    suspend fun newer(): AppUpdate? = withContext(Dispatchers.IO) {
        val running = versionOf(BuildConfig.VERSION_NAME) ?: return@withContext null
        try {
            val connection = Http.open(LATEST, USER_AGENT, json = true)
            val json = try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    /* 404: no release yet. */
                    Log.d(LASER_TAG, "update check: HTTP ${connection.responseCode}")
                    return@withContext null
                }
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
            val release = JSONObject(json)
            val tag = release.optString("tag_name").trim()
            val url = release.optString("html_url").trim()
            val latest = versionOf(tag)
            if (latest == null || url.isEmpty()) {
                Log.w(LASER_TAG, "update check: release \"$tag\" is not a version")
                return@withContext null
            }
            if (compare(latest, running) <= 0) return@withContext null
            AppUpdate(tag.removePrefix("v").removePrefix("V"), url)
                .also { Log.i(LASER_TAG, "update check: version ${it.version} is out") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(LASER_TAG, "update check failed: $e")
            null
        }
    }

    /** The numbers of "v1.2.3", of "1.2.3-debug": [1, 2, 3]; null if it does
     * not start with one. */
    internal fun versionOf(text: String): List<Int>? {
        val numbers = text.trim().removePrefix("v").removePrefix("V")
            .substringBefore('-').substringBefore('+')
            .split('.').map { it.toIntOrNull() ?: return null }
        return numbers.takeIf { it.isNotEmpty() }
    }

    /** Number by number, a missing one counting as 0: 1.2 is 1.2.0. */
    internal fun compare(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val diff = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }
}
