package io.github.pierrebog.lasermediaplayer.vlc

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import androidx.core.net.toUri
import io.github.pierrebog.lasermediaplayer.LASER_TAG
import io.github.pierrebog.lasermediaplayer.disc.DiscInfo
import io.github.pierrebog.lasermediaplayer.disc.LaserMrl
import io.github.pierrebog.lasermediaplayer.disc.TrackLists
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.MediaBrowser
import kotlin.coroutines.resume

/**
 * Classifies the disc behind an opened USB descriptor, by browsing
 * "laser-dir://<fd>" through VLC's laser access module: laser.c identifies
 * the disc with liblaser and lists one item per way of playing it, with the
 * title and, for a video disc, its tracks as extra metadata.
 *
 * One browse at a time; the caller (DriveRepository) classifies drives one
 * after the other. All MediaBrowser access happens on [handler]'s thread,
 * where its callbacks arrive too.
 */
class DiscClassifier(private val engine: VlcEngine) {

    private val thread = HandlerThread(
        "laser-classifier",
        Process.THREAD_PRIORITY_DEFAULT + Process.THREAD_PRIORITY_LESS_FAVORABLE
    ).apply { start() }
    private val handler = Handler(thread.looper)

    /**
     * @param fd an opened, permission-granted USB descriptor, owned by the
     *           caller and kept open until this returns
     * @return the disc, or null for a data disc, an empty drive, a device
     *         that is not an optical drive, or a browse that timed out
     */
    suspend fun classify(fd: Int, deviceName: String): DiscInfo? {
        var browser: MediaBrowser? = null
        /* Touched on the handler thread only, until the browse has ended. */
        val collected = ArrayList<IMedia>()
        try {
            val medias = withTimeoutOrNull(CLASSIFY_TIMEOUT) {
                suspendCancellableCoroutine<List<IMedia>> { cont ->
                    handler.post {
                        val listener = object : MediaBrowser.EventListener {
                            override fun onMediaAdded(index: Int, media: IMedia) {
                                /* libVLC drops its reference on return. */
                                media.retain()
                                collected.add(media)
                            }

                            override fun onMediaRemoved(index: Int, media: IMedia) {}

                            override fun onBrowseEnd() {
                                if (cont.isActive) cont.resume(ArrayList(collected))
                            }
                        }
                        browser = MediaBrowser(engine.libVlc, listener, handler).also {
                            it.browse(LaserMrl.classification(fd).toUri(), 0)
                        }
                    }
                }
            }
            if (medias == null) {
                Log.w(LASER_TAG, "classification of $deviceName timed out")
                return null
            }
            return discOf(medias, deviceName)
        } finally {
            /* Released SYNCHRONOUSLY, so that laser.c's Close() has released
             * the token before the caller closes the descriptor. NonCancellable:
             * a cancelled caller must still wait for it, or it would close the
             * fd under a libusb handle with the interface claimed. */
            withContext(NonCancellable) {
                releaseSync {
                    browser?.release()
                    browser = null
                    collected.forEach { it.release() }
                    collected.clear()
                }
            }
        }
    }

    private suspend fun releaseSync(block: () -> Unit) {
        suspendCancellableCoroutine<Unit> { cont ->
            val posted = handler.post {
                try {
                    block()
                } catch (e: Exception) {
                    Log.w(LASER_TAG, "classification teardown threw", e)
                } finally {
                    if (cont.isActive) cont.resume(Unit)
                }
            }
            if (!posted) {
                block()
                if (cont.isActive) cont.resume(Unit)
            }
        }
    }

    private fun discOf(medias: List<IMedia>, deviceName: String): DiscInfo? {
        val rows = medias.mapNotNull { media ->
            val mrl = media.uri?.toString() ?: return@mapNotNull null
            LaserMrl.rowOf(mrl).also {
                if (it == null) Log.w(LASER_TAG, "unexpected row MRL '$mrl', ignored")
            }
        }
        if (rows.isEmpty()) return null

        for (media in medias) {
            Log.d(LASER_TAG, "row ${media.uri}: audio=${media.getMetaExtra(TrackLists.AUDIO_META)
                ?.replace('\n', '|')} spu=${media.getMetaExtra(TrackLists.SPU_META)?.replace('\n', '|')}" +
                " id=${media.getMetaExtra(DISC_ID_META)} length=${media.getMetaExtra(MAIN_LENGTH_META)}" +
                " dvda=${media.getMetaExtra(DVDA_TRACKS_META)?.replace('\n', '|')}" +
                " aacs=${media.getMetaExtra(AACS_META)}" +
                " versions=${media.getMetaExtra(TrackLists.VERSIONS_META)?.replace('\n', '|')}")
        }

        /* Every row carries the disc's title; the video rows carry the tracks. */
        val first = medias.first()
        val video = medias.firstOrNull { it.getMetaExtra(TrackLists.AUDIO_META) != null }
        return DiscInfo(
            deviceName = deviceName,
            title = first.getMeta(IMedia.Meta.Title).orEmpty(),
            rows = rows,
            audioTracks = TrackLists.parseAudio(video?.getMetaExtra(TrackLists.AUDIO_META)),
            subtitleTracks = TrackLists.parseSubtitles(video?.getMetaExtra(TrackLists.SPU_META)),
            versions = TrackLists.parseVersions(video?.getMetaExtra(TrackLists.VERSIONS_META)),
            variants = TrackLists.parseVariants(video?.getMetaExtra(TrackLists.VERSIONS_META)),
            discId = medias.firstNotNullOfOrNull { it.getMetaExtra(DISC_ID_META) },
            mainLengthMs = medias.firstNotNullOfOrNull { it.getMetaExtra(MAIN_LENGTH_META) }
                ?.toLongOrNull() ?: 0,
            dvdaTracks = medias.firstNotNullOfOrNull { it.getMetaExtra(DVDA_TRACKS_META) }
                .orEmpty().lineSequence().mapNotNull { it.trim().toLongOrNull() }.toList(),
            aacs = when (medias.firstNotNullOfOrNull { it.getMetaExtra(AACS_META) }) {
                "1" -> true
                "0" -> false
                else -> null
            },
        )
    }

    private companion object {
        /* A Blu-ray reads every playlist and starts libaacs: generous. */
        val CLASSIFY_TIMEOUT = 60.seconds
        /* Set by laser.c: the DVD's, Blu-ray's or Video CD's disc ID, and
         * the main title's length in milliseconds. */
        const val DISC_ID_META = "laser-disc-id"
        const val MAIN_LENGTH_META = "laser-main-length"
        /* Set by laser.c on a DVD-Audio row: its tracks' lengths in
         * milliseconds, one per line. */
        const val DVDA_TRACKS_META = "laser-dvda-tracks"
        /* Set by laser.c on a Blu-ray row: "1" if AACS-protected, "0" if
         * not. */
        const val AACS_META = "laser-aacs"
    }
}
