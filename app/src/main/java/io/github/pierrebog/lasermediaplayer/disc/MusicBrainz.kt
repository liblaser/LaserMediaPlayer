package io.github.pierrebog.lasermediaplayer.disc

import android.os.SystemClock
import android.util.Base64
import android.util.Log
import io.github.pierrebog.lasermediaplayer.BuildConfig
import io.github.pierrebog.lasermediaplayer.LASER_TAG
import io.github.pierrebog.lasermediaplayer.ui.options.Credits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

/** What MusicBrainz says of a CD or a DVD-Audio, and where its cover may be. */
data class MusicBrainzMatch(val album: AlbumInfo, val coverUrls: List<String>)

/**
 * Looks an audio CD up on MusicBrainz from its track lengths - a DVD-Audio
 * from its label, see [searchDvdAudio].
 *
 * The table of contents is rebuilt from the lengths the cdda module gives:
 * a length is a whole number of sectors, so the sector counts come back
 * exactly, and the tracks are assumed to follow each other from the start
 * of the disc, as on a plain audio CD. One request asks for the disc ID and
 * passes the table of contents too: MusicBrainz answers the releases
 * carrying that exact disc, else those whose track lengths are close. VLC's
 * cdda asks for the disc ID alone, and finds nothing when that pressing's
 * ID was never submitted.
 *
 * On a release of several discs, the medium holding this disc is the one
 * used - the one carrying its ID, else the first with as many tracks.
 */
object MusicBrainz {

    private const val SERVER = "https://musicbrainz.org/ws/2"
    private const val COVERS = "https://coverartarchive.org"
    private const val TIMEOUT_MS = 10_000
    /* A DVD-Audio's search: releases looked at, and their tolerance. */
    private const val CANDIDATES = 5
    /* MusicBrainz asks for a request a second at most. */
    private const val REQUEST_GAP_MS = 1_100L
    private const val RETRIES = 3
    private const val RETRY_WAIT_MS = 2_000L
    private const val MAX_RETRY_WAIT_MS = 10_000L
    private const val TRACK_TOLERANCE_MS = 5_000L
    private const val TRACK_TOLERANCE_PERCENT = 5
    /* MusicBrainz asks clients to identify themselves, with a contact. */
    private val USER_AGENT = "LaserMediaPlayer/${BuildConfig.VERSION_NAME} ( ${Credits.SOURCE} )"

    suspend fun lookUp(lengthsMs: List<Long>): MusicBrainzMatch? = withContext(Dispatchers.IO) {
        if (lengthsMs.isEmpty()) return@withContext null
        val sectors = lengthsMs.map { (it * 75.0 / 1000).roundToLong() }
        val offsets = sectors.runningFold(150L) { at, length -> at + length }
        val leadOut = offsets.last()
        val starts = offsets.dropLast(1)
        val discId = discId(1, starts.size, leadOut, starts)
        val toc = (listOf(1L, starts.size.toLong(), leadOut) + starts).joinToString("+")
        val url = "$SERVER/discid/$discId?toc=$toc&fmt=json&cdstubs=no" +
                  "&inc=artist-credits+recordings+release-groups"
        try {
            val json = get(url) ?: return@withContext null
            matchOf(JSONObject(json), discId, starts.size).also {
                Log.d(LASER_TAG, "MusicBrainz $discId: ${it?.album?.title ?: "no match"}")
            }
        } catch (e: Exception) {
            Log.w(LASER_TAG, "MusicBrainz lookup failed", e)
            null
        }
    }

    /** Downloads the first cover of [urls] that exists to [dest]. */
    suspend fun fetchCover(urls: List<String>, dest: File): File? = withContext(Dispatchers.IO) {
        for (url in urls) {
            val tmp = File(dest.path + ".tmp")
            try {
                val connection = open(url, json = false)
                try {
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) continue
                    connection.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                } finally {
                    connection.disconnect()
                }
                if (tmp.renameTo(dest)) return@withContext dest
            } catch (e: Exception) {
                Log.w(LASER_TAG, "cannot fetch cover $url", e)
            } finally {
                tmp.delete()
            }
        }
        null
    }

    /**
     * The MusicBrainz disc ID, as libdiscid computes it: SHA-1 of the first
     * and last track numbers and the 100 offsets (lead-out first), in
     * upper-case hexadecimal, in base 64 with "._-" for "+/=".
     */
    private fun discId(first: Int, last: Int, leadOut: Long, starts: List<Long>): String {
        val text = StringBuilder()
            .append("%02X".format(first))
            .append("%02X".format(last))
            .append("%08X".format(leadOut))
        for (i in 0 until 99) text.append("%08X".format(starts.getOrElse(i) { 0L }))
        val sha = MessageDigest.getInstance("SHA-1").digest(text.toString().toByteArray())
        return Base64.encodeToString(sha, Base64.NO_WRAP)
            .replace('+', '.').replace('/', '_').replace('=', '-')
    }

    private fun matchOf(root: JSONObject, discId: String, trackCount: Int): MusicBrainzMatch? {
        val releases = root.optJSONArray("releases") ?: return null
        /* The medium carrying this very disc, else one with as many tracks. */
        var chosen: Pair<JSONObject, JSONObject>? = null
        loop@ for (exact in listOf(true, false)) {
            for (release in releases.objects()) {
                for (medium in release.optJSONArray("media").objects()) {
                    val matches = if (exact)
                        medium.optJSONArray("discs").objects().any { it.optString("id") == discId }
                    else
                        medium.optInt("track-count", -1) == trackCount
                    if (matches) {
                        chosen = release to medium
                        break@loop
                    }
                }
            }
        }
        val (release, medium) = chosen ?: return null
        return matchOf(release, medium)
    }

    /**
     * Looks a DVD-Audio up: it has no disc ID, so its label, written as a
     * query, is searched among the releases with a DVD-Audio medium, and the
     * tracks of the closest lengths are taken - each within max(5 s, 5 %),
     * the disc's lengths being worked out from sectors. Null when none is
     * that close: no album rather than a wrong one.
     *
     * The disc's tracks are those of its first title, while MusicBrainz
     * lists every track of the medium: the stereo and the multichannel
     * programs one after the other, 52 tracks for a 26-track album. So a
     * medium of n times as many tracks is taken as n programs, and the
     * disc's tracks are compared with each.
     *
     * The search answers no track lengths: up to [CANDIDATES] releases with
     * such a medium are asked one by one, a second apart (see [get]).
     */
    suspend fun searchDvdAudio(query: String, lengthsMs: List<Long>): MusicBrainzMatch? =
        withContext(Dispatchers.IO) {
            if (lengthsMs.isEmpty()) return@withContext null
            /* Lower case: an upper-case AND, OR or NOT would be an operator. */
            val words = wordsOf(query)
            if (words.isEmpty()) return@withContext null
            val lucene = "release:(${words.joinToString(" ")}) AND format:\"DVD-Audio\""
            try {
                val json = get("$SERVER/release?query=${URLEncoder.encode(lucene, "UTF-8")}" +
                               "&limit=100&fmt=json") ?: return@withContext null
                val ids = JSONObject(json).optJSONArray("releases").objects()
                    .filter { release ->
                        release.optJSONArray("media").objects().any { it.isDvdAudio(lengthsMs.size) }
                    }
                    /* A short label matches many titles: the releases named
                     * exactly by it first, in the search's order otherwise. */
                    .sortedBy { if (wordsOf(it.text("title").orEmpty()) == words) 0 else 1 }
                    .mapNotNull { it.text("id") }
                    .take(CANDIDATES)

                var best: Pair<Long, MusicBrainzMatch>? = null
                for (id in ids) {
                    val release = get("$SERVER/release/$id?fmt=json" +
                                      "&inc=artist-credits+recordings+release-groups")
                        ?.let(::JSONObject) ?: continue
                    for (medium in release.optJSONArray("media").objects()) {
                        if (!medium.isDvdAudio(lengthsMs.size)) continue
                        val (gap, first) = gapOf(medium, lengthsMs) ?: continue
                        if (best == null || gap < best.first)
                            best = gap to matchOf(release, medium, first, lengthsMs.size)
                    }
                }
                best?.second.also {
                    Log.d(LASER_TAG, "MusicBrainz DVD-Audio \"$query\": " +
                          "${ids.size} candidates, ${it?.album?.title ?: "no match"}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(LASER_TAG, "MusicBrainz DVD-Audio lookup failed", e)
                null
            }
        }

    private fun wordsOf(text: String): List<String> =
        text.lowercase().split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotEmpty() }

    private fun JSONObject.isDvdAudio(trackCount: Int): Boolean =
        optString("format") == "DVD-Audio" &&
            optInt("track-count", -1).let { it > 0 && it % trackCount == 0 }

    /**
     * The program of [medium] closest to the disc's tracks, as the gap summed
     * over the tracks whose length MusicBrainz knows and the program's first
     * track; null if every program has a track too far, or knows none - the
     * track count alone is too weak a match.
     */
    private fun gapOf(medium: JSONObject, lengthsMs: List<Long>): Pair<Long, Int>? {
        val tracks = medium.optJSONArray("tracks").objects()
        var best: Pair<Long, Int>? = null
        if (tracks.isEmpty() || tracks.size % lengthsMs.size != 0) return null
        run@ for (first in tracks.indices step lengthsMs.size) {
            var gap = 0L
            var known = 0
            for ((i, length) in lengthsMs.withIndex()) {
                val track = tracks[first + i]
                if (track.isNull("length")) continue
                val expected = track.optLong("length", -1).takeIf { it > 0 } ?: continue
                val diff = abs(expected - length)
                if (diff > max(TRACK_TOLERANCE_MS, expected * TRACK_TOLERANCE_PERCENT / 100)) continue@run
                gap += diff
                known++
            }
            if (known > 0 && (best == null || gap < best.first)) best = gap to first
        }
        return best
    }

    /** The album [medium] of [release] holds - [count] tracks from [first]
     * on, all of them by default - and where its cover may be. */
    private fun matchOf(release: JSONObject, medium: JSONObject, first: Int = 0,
                        count: Int = Int.MAX_VALUE): MusicBrainzMatch {
        val albumArtist = credit(release.optJSONArray("artist-credit"))
        val tracks = medium.optJSONArray("tracks").objects().drop(first).take(count).map { track ->
            val artist = credit(track.optJSONArray("artist-credit"))
            AlbumTrack(track.text("title"), artist?.takeIf { it != albumArtist })
        }
        val album = AlbumInfo(
            title = release.text("title"),
            artist = albumArtist,
            year = release.text("date")?.take(4),
            tracks = tracks,
            cover = null,
        )
        val covers = listOfNotNull(
            release.text("id")?.let { "$COVERS/release/$it/front-500" },
            release.optJSONObject("release-group")?.text("id")?.let { "$COVERS/release-group/$it/front-500" },
        )
        return MusicBrainzMatch(album, covers)
    }

    /** "Artist feat. Other": the names with their join phrases. */
    private fun credit(array: JSONArray?): String? {
        val text = array.objects().joinToString("") {
            (it.text("name") ?: "") + it.optString("joinphrase", "")
        }.trim()
        return text.ifEmpty { null }
    }

    /* Every request, however many lookups run, is spaced by [REQUEST_GAP_MS]
     * from the one before. */
    private val gate = Mutex()
    private var lastRequest = 0L

    /**
     * The answer to [url], or null: not found, or an error. A 503 is
     * MusicBrainz turning a request down for coming too soon: asked again
     * after the wait it gives, or after a wait doubling from two seconds -
     * its limit is sometimes well below a request a second - up to
     * [RETRIES] times.
     */
    private suspend fun get(url: String): String? = gate.withLock {
        repeat(RETRIES + 1) { attempt ->
            delay(lastRequest + REQUEST_GAP_MS - SystemClock.elapsedRealtime())
            val connection = open(url, json = true)
            var retryAfterMs = 0L
            try {
                val code = connection.responseCode
                lastRequest = SystemClock.elapsedRealtime()
                when (code) {
                    HttpURLConnection.HTTP_OK ->
                        return@withLock connection.inputStream.bufferedReader().use { it.readText() }
                    /* No release for this disc. */
                    HttpURLConnection.HTTP_NOT_FOUND -> return@withLock null
                    HttpURLConnection.HTTP_UNAVAILABLE -> {
                        retryAfterMs = (connection.getHeaderField("Retry-After")?.trim()?.toLongOrNull()
                            ?.times(1000) ?: (RETRY_WAIT_MS shl attempt)).coerceIn(RETRY_WAIT_MS, MAX_RETRY_WAIT_MS)
                        Log.w(LASER_TAG, "MusicBrainz: HTTP 503, attempt ${attempt + 1}: $url")
                    }
                    else -> {
                        Log.w(LASER_TAG, "MusicBrainz: HTTP $code: $url")
                        return@withLock null
                    }
                }
            } finally {
                connection.disconnect()
            }
            delay(retryAfterMs)
        }
        null
    }

    private fun open(url: String, json: Boolean): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            ExtraRoots.apply(this)
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            if (json) setRequestProperty("Accept", "application/json")
        }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONObject.text(key: String): String? =
        if (isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }
}
