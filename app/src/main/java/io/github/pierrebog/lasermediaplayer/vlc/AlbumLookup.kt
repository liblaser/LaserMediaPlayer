package io.github.pierrebog.lasermediaplayer.vlc

import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import io.github.pierrebog.lasermediaplayer.LASER_TAG
import io.github.pierrebog.lasermediaplayer.disc.AlbumInfo
import io.github.pierrebog.lasermediaplayer.disc.AlbumStore
import io.github.pierrebog.lasermediaplayer.disc.AlbumTrack
import io.github.pierrebog.lasermediaplayer.disc.DiscInfo
import io.github.pierrebog.lasermediaplayer.disc.DiscKind
import io.github.pierrebog.lasermediaplayer.disc.FilmLookup
import io.github.pierrebog.lasermediaplayer.disc.MusicBrainz
import io.github.pierrebog.lasermediaplayer.disc.dvdaId
import io.github.pierrebog.lasermediaplayer.disc.tocId
import io.github.pierrebog.lasermediaplayer.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.videolan.libvlc.Media
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.MediaBrowser
import kotlin.coroutines.resume

/**
 * Names an audio CD or a DVD-Audio: album, artist, cover, track titles.
 *
 * VLC's cdda module lists the disc's tracks - their lengths, which identify
 * the disc, and the CD-Text if there is any. MusicBrainz is asked by the app
 * (see [MusicBrainz]) rather than through cdda, whose lookup only tries the
 * exact disc ID. The result and the cover are kept, so that a CD seen
 * before needs no network.
 *
 * Runs during classification, while the drive's connection is open: the
 * cdda module reads the table of contents through it.
 *
 * MusicBrainz is asked only when the lookup is on; what is kept is used
 * either way. The cdda module never goes on the network itself.
 *
 * A DVD-Audio needs no drive: laser.c lists its tracks at classification,
 * and MusicBrainz is searched by its label (see [MusicBrainz.searchDvdAudio]).
 */
class AlbumLookup(
    private val engine: VlcEngine,
    private val store: AlbumStore,
    private val settings: AppSettings,
) {

    private val thread = HandlerThread(
        "laser-album",
        Process.THREAD_PRIORITY_DEFAULT + Process.THREAD_PRIORITY_LESS_FAVORABLE
    ).apply { start() }
    private val handler = Handler(thread.looper)

    /** One track as the cdda module lists it. */
    private data class Entry(
        val durationMs: Long,
        val title: String?,
        val artist: String?,
        val album: String?,
        val albumArtist: String?,
    )

    /**
     * [disc] with its table of contents and what is known of it; [disc]
     * itself if the tracks cannot be listed. Never throws.
     */
    suspend fun complete(disc: DiscInfo): DiscInfo {
        val mrl = disc.rows.firstOrNull { it.kind == DiscKind.CD_AUDIO }?.mrl ?: return disc
        return try {
            lookUp(disc, mrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LASER_TAG, "album lookup failed", e)
            disc
        }
    }

    private suspend fun lookUp(disc: DiscInfo, mrl: String): DiscInfo {
        val entries = browse(mrl)
        if (entries.isNullOrEmpty()) return disc
        val lengths = entries.map { it.durationMs }
        val id = tocId(lengths)
        val withToc = disc.copy(cdTracks = lengths)

        store.load(id)?.let {
            Log.d(LASER_TAG, "album $id: known, \"${it.title}\"")
            return withToc.copy(album = it)
        }

        val fromDisc = cdText(entries)
        return online(withToc) ?: withToc.copy(album = fromDisc)
    }

    /**
     * [disc] - its table of contents known - named by MusicBrainz, or null
     * if the lookup is off or found nothing. No drive needed: the lookup
     * turned on later names a CD already classified. A DVD-Audio is looked
     * up by its label and its tracks' lengths.
     */
    suspend fun online(disc: DiscInfo): DiscInfo? {
        if (!settings.lookups.value.albumsOnline) return null
        /* A CD by its table of contents, a DVD-Audio by its label. */
        val query: String?
        val id = when {
            disc.cdTracks.isNotEmpty() -> {
                query = null
                tocId(disc.cdTracks)
            }
            disc.dvdaTracks.isNotEmpty() -> {
                query = dvdAudioQuery(disc.title) ?: return null
                dvdaId(disc.dvdaTracks)
            }
            else -> return null
        }
        /* Not kept when nothing is found: asked again next time, the network
         * may be back, or the disc submitted. */
        val match = try {
            if (query == null) MusicBrainz.lookUp(disc.cdTracks)
            else MusicBrainz.searchDvdAudio(query, disc.dvdaTracks)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LASER_TAG, "album lookup failed", e)
            null
        } ?: return null
        val cover = MusicBrainz.fetchCover(match.coverUrls, store.coverFile(id))
        val album = match.album.copy(cover = cover)
        store.save(id, album)
        Log.d(LASER_TAG, "album $id: \"${album.title}\" by ${album.artist}, cover=${cover != null}")
        return disc.copy(album = album)
    }

    /**
     * A DVD-Audio named from what is kept, else by MusicBrainz; [disc]
     * itself if neither knows it. Its tracks come from laser.c: no drive
     * needed. Never throws.
     */
    suspend fun dvdAudio(disc: DiscInfo): DiscInfo {
        if (disc.dvdaTracks.isEmpty()) return disc
        val id = dvdaId(disc.dvdaTracks)
        store.load(id)?.let {
            Log.d(LASER_TAG, "album $id: known, \"${it.title}\"")
            return disc.copy(album = it)
        }
        return try {
            online(disc) ?: disc
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LASER_TAG, "album lookup failed", e)
            disc
        }
    }

    /** A DVD-Audio's label written as a query, or null if it names
     * nothing: no label, or one that only says what the disc is. */
    private fun dvdAudioQuery(title: String): String? =
        FilmLookup.queryOf(title)?.takeIf { it !in GENERIC_DVDA }

    /** What the CD-Text says, or null if the disc has none. */
    private fun cdText(entries: List<Entry>): AlbumInfo? {
        /* Without a title, libVLC answers with the item's name, the cdda
         * module's "Audio CD - Track 01": not a title. */
        val named = !defaultNames(entries.map { it.title })
        val albumArtist = entries.firstNotNullOfOrNull { it.albumArtist }
        val tracks = entries.map { e ->
            AlbumTrack(e.title.takeIf { named }, e.artist?.takeIf { it != albumArtist })
        }
        val album = AlbumInfo(
            title = entries.firstNotNullOfOrNull { it.album },
            artist = albumArtist ?: entries.map { it.artist }.distinct().singleOrNull(),
            year = null,
            tracks = tracks,
            cover = null,
        )
        val empty = album.title == null && album.artist == null && tracks.all { it.title == null }
        return if (empty) null else album
    }

    /** True if every title is the same text ending in the track's number. */
    private fun defaultNames(titles: List<String?>): Boolean {
        if (titles.isEmpty() || titles.any { it == null }) return titles.all { it == null }
        val prefix = titles[0]!!.removeSuffix("01")
        return titles.withIndex().all { (i, t) -> t == prefix + "%02d".format(i + 1) }
    }

    /**
     * Lists the CD's tracks through the cdda module.
     *
     * @return the tracks, or null if the listing failed or timed out
     */
    private suspend fun browse(mrl: String): List<Entry>? {
        var browser: MediaBrowser? = null
        var media: Media? = null
        val collected = ArrayList<Entry>()
        try {
            return withTimeoutOrNull(BROWSE_TIMEOUT_MS) {
                suspendCancellableCoroutine<List<Entry>> { cont ->
                    handler.post {
                        val listener = object : MediaBrowser.EventListener {
                            override fun onMediaAdded(index: Int, media: IMedia) {
                                collected.add(entryOf(media))
                            }

                            override fun onMediaRemoved(index: Int, media: IMedia) {}

                            override fun onBrowseEnd() {
                                if (cont.isActive) cont.resume(ArrayList(collected))
                            }
                        }
                        /* The tracks and their CD-Text only: the cdda module
                         * would otherwise ask MusicBrainz itself. */
                        media = Media(engine.libVlc, Uri.parse(mrl)).apply {
                            addOption(":no-metadata-network-access")
                        }
                        browser = MediaBrowser(engine.libVlc, listener, handler).also {
                            it.browse(media!!, 0)
                        }
                    }
                }
            }.also { if (it == null) Log.w(LASER_TAG, "listing $mrl timed out") }
        } finally {
            /* Released before the caller closes the drive's connection. */
            withContext(NonCancellable) {
                suspendCancellableCoroutine<Unit> { cont ->
                    val posted = handler.post {
                        runCatching { browser?.release() }
                        browser = null
                        runCatching { media?.release() }
                        media = null
                        if (cont.isActive) cont.resume(Unit)
                    }
                    if (!posted && cont.isActive) cont.resume(Unit)
                }
            }
        }
    }

    private fun entryOf(media: IMedia): Entry {
        fun meta(m: Int) = media.getMeta(m)?.trim()?.takeIf { it.isNotEmpty() }
        return Entry(
            durationMs = media.duration.coerceAtLeast(0),
            title = meta(IMedia.Meta.Title),
            artist = meta(IMedia.Meta.Artist),
            album = meta(IMedia.Meta.Album),
            albumArtist = meta(IMedia.Meta.AlbumArtist),
        )
    }

    private companion object {
        const val BROWSE_TIMEOUT_MS = 15_000L
        /* laser.c's own name for a disc without a label among them. */
        val GENERIC_DVDA = setOf("DVD", "DVD AUDIO", "DVDAUDIO", "AUDIO", "AUDIO TS", "DVDA")
    }
}
