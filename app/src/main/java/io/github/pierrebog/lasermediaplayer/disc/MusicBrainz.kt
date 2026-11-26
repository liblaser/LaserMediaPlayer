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
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** What MusicBrainz says of a CD or a DVD-Audio, and where its cover may be. */
data class MusicBrainzMatch(val album: AlbumInfo, val coverUrls: List<String>)

/**
 * Looks an audio CD up on MusicBrainz from its table of contents - a
 * DVD-Audio from its label, see [searchDvdAudio].
 *
 * The table of contents is the cdda module's ([TOC_META]): where each track
 * starts, as the disc says, so the disc ID is the one MusicBrainz knows -
 * VLC's own lookup computes it the same way. Without it - a VLC that does
 * not give it - it is rebuilt from the track lengths: a length is a whole
 * number of sectors, so the sector counts come back exactly, but the tracks
 * are assumed to start right after the disc's two-second lead-in, which a
 * disc with a longer pregap before its first track does not follow: its
 * disc ID is then wrong. One request asks for the disc ID and passes the
 * table of contents too: MusicBrainz answers the releases carrying that
 * exact disc, else those whose track lengths are close. VLC's cdda asks for
 * the disc ID alone, and finds nothing when that pressing's ID was never
 * submitted.
 *
 * On a release of several discs, the medium holding this disc is the one
 * used - the one carrying its ID, else one with as many tracks, of lengths
 * close to the disc's. An album is also found in the box sets reissuing it:
 * the release of the fewest discs is taken, "The Division Bell" rather
 * than "Discovery", Pink Floyd's box of their studio albums.
 */
object MusicBrainz {

    /** The meta extra the cdda module sets on each track: the disc's table
     * of contents as MusicBrainz takes it - the first and last audio tracks,
     * the lead-out, then where each audio track starts, in sectors, joined by
     * '+'. */
    const val TOC_META = "MUSICBRAINZ_TOC"

    private const val SERVER = "https://musicbrainz.org/ws/2"
    private const val COVERS = "https://coverartarchive.org"
    /* A DVD-Audio's search: releases looked at, and their tolerance. */
    private const val CANDIDATES = 5
    /* MusicBrainz asks for a request a second at most. */
    private val REQUEST_GAP = 1_100.milliseconds
    private const val RETRIES = 3
    private val RETRY_WAIT = 2.seconds
    private val MAX_RETRY_WAIT = 10.seconds
    private const val TRACK_TOLERANCE_MS = 5_000L
    private const val TRACK_TOLERANCE_PERCENT = 5
    /* MusicBrainz asks clients to identify themselves, with a contact. */
    private const val USER_AGENT = "LaserMediaPlayer/${BuildConfig.VERSION_NAME} ( ${Credits.SOURCE} )"

    /**
     * The CD of [lengthsMs], its table of contents [toc] as the cdda module
     * gives it ([TOC_META]), if it does: used when it agrees with the
     * lengths, else rebuilt from them.
     */
    suspend fun lookUp(lengthsMs: List<Long>, toc: String?): MusicBrainzMatch? = withContext(Dispatchers.IO) {
        if (lengthsMs.isEmpty()) return@withContext null
        val given = toc?.let { Toc.parse(it, lengthsMs.size) }
        if (toc != null && given == null)
            Log.w(LASER_TAG, "MusicBrainz: table of contents \"$toc\" unusable, rebuilt from the lengths")
        val contents = given ?: Toc.of(lengthsMs)
        val discId = discId(contents)
        val url = "$SERVER/discid/$discId?toc=$contents&fmt=json&cdstubs=no" +
                  "&inc=artist-credits+recordings+release-groups"
        try {
            val json = get(url) ?: return@withContext null
            matchOf(JSONObject(json), discId, lengthsMs).also {
                Log.d(LASER_TAG, "MusicBrainz $discId: ${it?.album?.title ?: "no match"}")
            }
        } catch (e: Exception) {
            Log.w(LASER_TAG, "MusicBrainz lookup failed", e)
            null
        }
    }

    /** Downloads the first cover of [urls] that exists to [dest]. */
    suspend fun fetchCover(urls: List<String>, dest: File): File? = withContext(Dispatchers.IO) {
        urls.firstNotNullOfOrNull { Http.download(it, dest, USER_AGENT) }
    }

    /**
     * A CD's table of contents as MusicBrainz takes it: the first and last
     * track numbers, the lead-out, and where each track starts, in sectors
     * counted from the start of the lead-in - 150 for a first track right
     * after it.
     */
    private class Toc(val first: Int, val last: Int, val leadOut: Long, val starts: List<Long>) {

        /** As the "toc" parameter of a request writes it. */
        override fun toString(): String =
            (listOf(first.toLong(), last.toLong(), leadOut) + starts).joinToString("+")

        companion object {
            /** [text], the cdda module's; null if malformed or not of
             * [count] tracks - those whose lengths are known. */
            fun parse(text: String, count: Int): Toc? {
                val values = text.split('+').map { it.trim().toLongOrNull() ?: return null }
                if (values.size < 4) return null
                val first = values[0].toInt()
                val last = values[1].toInt()
                val leadOut = values[2]
                val starts = values.drop(3)
                if (first !in 1..99 || last != first + starts.size - 1 || last > 99 ||
                    starts.size != count) return null
                /* Each track after the one before, the lead-out after them. */
                if ((starts + leadOut).zipWithNext().any { (a, b) -> b <= a } || starts[0] < 0)
                    return null
                return Toc(first, last, leadOut, starts)
            }

            /** Rebuilt from the tracks' lengths: one after the other, from
             * just after the lead-in. */
            fun of(lengthsMs: List<Long>): Toc {
                val sectors = lengthsMs.map { (it * 75.0 / 1000).roundToLong() }
                val offsets = sectors.runningFold(150L) { at, length -> at + length }
                return Toc(1, lengthsMs.size, offsets.last(), offsets.dropLast(1))
            }
        }
    }

    /**
     * The MusicBrainz disc ID, as libdiscid computes it: SHA-1 of the first
     * and last track numbers and the 100 offsets - the lead-out's, then each
     * track's by its number, 0 for a number the disc has not - in upper-case
     * hexadecimal, in base 64 with "._-" for "+/=".
     */
    private fun discId(toc: Toc): String {
        val text = StringBuilder()
            .append("%02X".format(toc.first))
            .append("%02X".format(toc.last))
            .append("%08X".format(toc.leadOut))
        for (track in 1..99) text.append("%08X".format(toc.starts.getOrElse(track - toc.first) { 0L }))
        val sha = MessageDigest.getInstance("SHA-1").digest(text.toString().toByteArray())
        return Base64.encodeToString(sha, Base64.NO_WRAP)
            .replace('+', '.').replace('/', '_').replace('=', '-')
    }

    private fun matchOf(root: JSONObject, discId: String, lengthsMs: List<Long>): MusicBrainzMatch? {
        val media = root.optJSONArray("releases").objects().flatMap { release ->
            release.optJSONArray("media").objects().map { release to it }
        }
        /* The media carrying this very disc, else those with as many tracks. */
        val candidates = media.filter { (_, medium) ->
            medium.optJSONArray("discs").objects().any { it.optString("id") == discId }
        }.ifEmpty {
            media.filter { (_, medium) -> medium.optInt("track-count", -1) == lengthsMs.size }
        }
        if (candidates.isEmpty()) return null
        /* Of a box set's discs, several may have as many tracks: those whose
         * lengths are close, when MusicBrainz knows them. Then the release
         * of the fewest discs - the album rather than a box reissuing it -
         * and the closest lengths; MusicBrainz's order otherwise. */
        val close = candidates.mapNotNull { c -> gapOf(c.second, lengthsMs)?.let { c to it.first } }
        val (release, medium) = if (close.isNotEmpty())
            close.minWith(compareBy({ mediaCount(it.first.first) }, { it.second })).first
        else
            candidates.minBy { mediaCount(it.first) }
        return matchOf(release, medium)
    }

    private fun mediaCount(release: JSONObject): Int =
        release.optJSONArray("media")?.length()?.takeIf { it > 0 } ?: 1

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

    /* Every request, however many lookups run, is spaced by [REQUEST_GAP]
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
            delay(REQUEST_GAP - (SystemClock.elapsedRealtime() - lastRequest).milliseconds)
            val connection = Http.open(url, USER_AGENT, json = true)
            val retryAfter: Duration
            try {
                val code = connection.responseCode
                lastRequest = SystemClock.elapsedRealtime()
                when (code) {
                    HttpURLConnection.HTTP_OK ->
                        return@withLock connection.inputStream.bufferedReader().use { it.readText() }
                    /* No release for this disc. */
                    HttpURLConnection.HTTP_NOT_FOUND -> return@withLock null
                    HttpURLConnection.HTTP_UNAVAILABLE -> {
                        retryAfter = (connection.getHeaderField("Retry-After")?.trim()?.toLongOrNull()
                            ?.seconds ?: (RETRY_WAIT * (1 shl attempt))).coerceIn(RETRY_WAIT, MAX_RETRY_WAIT)
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
            delay(retryAfter)
        }
        null
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONObject.text(key: String): String? =
        if (isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }
}
