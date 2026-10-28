package io.github.pierrebog.lasermediaplayer.disc

import android.content.Context
import android.util.Log
import io.github.pierrebog.lasermediaplayer.LASER_TAG
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** A CD track as the lookup names it; null where nothing is known. */
data class AlbumTrack(val title: String?, val artist: String?)

/**
 * What the disc lookup found for an audio CD - its CD-Text, or MusicBrainz -
 * or for a DVD-Audio - MusicBrainz.
 *
 * @param tracks in disc order; may be shorter than the disc
 * @param cover the downloaded front cover, if there is one
 */
data class AlbumInfo(
    val title: String?,
    val artist: String?,
    val year: String?,
    val tracks: List<AlbumTrack>,
    val cover: File?,
)

/**
 * Identifies a CD by its table of contents: the length of each track, as
 * the cdda module computes it from the sectors. Not MusicBrainz's disc ID,
 * which the app cannot compute without the sector offsets, but stable for a
 * given pressing.
 */
fun tocId(lengthsMs: List<Long>): String {
    val digest = MessageDigest.getInstance("SHA-1")
        .digest(lengthsMs.joinToString(",").toByteArray())
    return digest.take(10).joinToString("") { "%02x".format(it) }
}

/** Identifies a DVD-Audio by its tracks' lengths, as laser.c reads them;
 * apart from the CDs' ids. */
fun dvdaId(lengthsMs: List<Long>): String = "dvda-" + tocId(lengthsMs)

/**
 * What was found for each CD or DVD-Audio, so that a disc seen before needs
 * no network: albums/<id>.json, and its cover in albums/<id>.jpg.
 */
class AlbumStore(context: Context) {

    private val dir = File(context.filesDir, "albums")

    fun coverFile(id: String): File = File(dir.apply { mkdirs() }, "$id.jpg")

    fun load(id: String): AlbumInfo? {
        val file = File(dir, "$id.json")
        if (!file.isFile) return null
        return try {
            val o = JSONObject(file.readText())
            val array = o.optJSONArray("tracks") ?: JSONArray()
            val tracks = (0 until array.length()).map { i ->
                val t = array.getJSONObject(i)
                AlbumTrack(t.stringOrNull("title"), t.stringOrNull("artist"))
            }
            AlbumInfo(o.stringOrNull("title"), o.stringOrNull("artist"), o.stringOrNull("year"),
                      tracks, coverFile(id).takeIf { it.isFile })
        } catch (e: Exception) {
            Log.w(LASER_TAG, "unreadable album $id", e)
            null
        }
    }

    fun save(id: String, album: AlbumInfo) {
        val o = JSONObject()
            .put("title", album.title ?: JSONObject.NULL)
            .put("artist", album.artist ?: JSONObject.NULL)
            .put("year", album.year ?: JSONObject.NULL)
            .put("tracks", JSONArray().apply {
                album.tracks.forEach {
                    put(JSONObject().put("title", it.title ?: JSONObject.NULL)
                                    .put("artist", it.artist ?: JSONObject.NULL))
                }
            })
        try {
            dir.mkdirs()
            val tmp = File(dir, "$id.json.tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(File(dir, "$id.json"))) tmp.delete()
        } catch (e: Exception) {
            Log.w(LASER_TAG, "cannot save album $id", e)
        }
    }

    private fun JSONObject.stringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
}
