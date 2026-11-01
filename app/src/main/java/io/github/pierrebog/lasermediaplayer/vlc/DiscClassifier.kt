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
import org.videolan.libvlc.Media
import org.videolan.libvlc.interfaces.IMedia
import kotlin.coroutines.resume

/** Why a drive has no disc to show, as laser.c says it. */
enum class NoDiscReason {
    /** Empty, tray closed - or a disc the drive could not read. */
    NO_MEDIUM,
    TRAY_OPEN,
    /** The drive did not become ready, nor said why. */
    NOT_READY,
    /** A disc, but none of the kinds played here: a data disc. */
    UNRECOGNISED,
}

/** What a classification found. */
sealed interface Classification {
    data class Disc(val disc: DiscInfo) : Classification
    data class NoDisc(val reason: NoDiscReason) : Classification
    /** The browse did not end in time. */
    data object TimedOut : Classification
}

/**
 * Classifies the disc behind an opened USB descriptor, by parsing
 * "laser-dir://<fd>" through VLC's laser access module: laser.c identifies
 * the disc with liblaser and lists one item per way of playing it, with the
 * title and, for a video disc, its tracks as extra metadata.
 *
 * Parsed, not browsed: the parse ends - with a status - whatever happens,
 * a drive laser.c cannot open included, and libVLC applies the timeout
 * itself, interrupting the parse, which laser.c and liblaser then give up.
 * MediaBrowser ends only with a list of items, and cannot stop its parse.
 *
 * One parse at a time; the caller (DriveRepository) classifies drives one
 * after the other. The media is created, read and released on [handler]'s
 * thread.
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
     * @return the disc; or why there is none - a device that is not an
     *         optical drive, which laser.c refuses, says nothing and counts
     *         as unrecognised; or a browse that timed out
     */
    suspend fun classify(fd: Int, deviceName: String): Classification {
        /* Touched on the handler thread only. */
        var media: Media? = null
        val collected = ArrayList<IMedia>()
        try {
            /* The parse's status, or null if libVLC never ended it. */
            val status = withTimeoutOrNull(SAFETY_TIMEOUT) {
                suspendCancellableCoroutine<Int> { cont ->
                    handler.post {
                        val m = Media(engine.libVlc, LaserMrl.classification(fd).toUri())
                        media = m
                        /* Any thread: only resumes the caller. */
                        m.setEventListener { event ->
                            if (event.type == IMedia.Event.ParsedChanged && cont.isActive)
                                cont.resume(event.parsedStatus)
                        }
                        if (!m.parseAsync(IMedia.Parse.ParseNetwork, CLASSIFY_TIMEOUT_MS) &&
                            cont.isActive)
                            cont.resume(IMedia.ParsedStatus.Failed)
                    }
                }
            }
            when (status) {
                /* libVLC's own timeout interrupts the parse and ends it; this
                 * one is only a safety net, which cannot stop it. */
                null -> {
                    Log.w(LASER_TAG, "classification of $deviceName never ended")
                    return Classification.TimedOut
                }
                IMedia.ParsedStatus.Timeout -> {
                    Log.w(LASER_TAG, "classification of $deviceName timed out")
                    return Classification.TimedOut
                }
                /* laser.c could not open the drive: its claim refused, the
                 * device gone, or not an optical drive. */
                IMedia.ParsedStatus.Failed -> {
                    Log.w(LASER_TAG, "classification of $deviceName failed to open the drive")
                    return Classification.NoDisc(NoDiscReason.NOT_READY)
                }
            }
            /* The rows laser.c listed, kept until discOf() has read them. */
            val medias = onHandler {
                val list = media?.subItems() ?: return@onHandler emptyList()
                try {
                    (0 until list.count).mapNotNull { list.getMediaAt(it) }.also { collected += it }
                } finally {
                    list.release()
                }
            }
            return discOf(medias, deviceName)
        } finally {
            /* NonCancellable: a cancelled caller must still release the
             * media, which holds the parse's input. */
            withContext(NonCancellable) {
                onHandler {
                    media?.setEventListener(null)
                    media?.release()
                    media = null
                    collected.forEach { it.release() }
                    collected.clear()
                }
            }
        }
    }

    /** Runs [block] on [handler]'s thread and returns its result - on the
     * calling thread if that one has quit. */
    private suspend fun <T> onHandler(block: () -> T): T =
        suspendCancellableCoroutine { cont ->
            val posted = handler.post {
                val result = runCatching(block)
                if (cont.isActive) cont.resumeWith(result)
            }
            if (!posted) {
                val result = runCatching(block)
                if (cont.isActive) cont.resumeWith(result)
            }
        }

    private fun discOf(medias: List<IMedia>, deviceName: String): Classification {
        /* Nothing to play: laser.c's one row saying why. */
        medias.firstNotNullOfOrNull { it.getMetaExtra(STATE_META) }?.let { state ->
            Log.d(LASER_TAG, "$deviceName: no disc ($state)")
            return Classification.NoDisc(when (state) {
                "no-medium" -> NoDiscReason.NO_MEDIUM
                "tray-open" -> NoDiscReason.TRAY_OPEN
                "not-ready" -> NoDiscReason.NOT_READY
                else -> NoDiscReason.UNRECOGNISED
            })
        }

        val rows = medias.mapNotNull { media ->
            val mrl = media.uri?.toString() ?: return@mapNotNull null
            LaserMrl.rowOf(mrl).also {
                if (it == null) Log.w(LASER_TAG, "unexpected row MRL '$mrl', ignored")
            }
        }
        if (rows.isEmpty()) return Classification.NoDisc(NoDiscReason.UNRECOGNISED)

        for (media in medias) {
            Log.d(LASER_TAG, "row ${media.uri}: audio=${media.getMetaExtra(TrackLists.AUDIO_META)
                ?.replace('\n', '|')} spu=${media.getMetaExtra(TrackLists.SPU_META)?.replace('\n', '|')}" +
                " id=${media.getMetaExtra(DISC_ID_META)} length=${media.getMetaExtra(MAIN_LENGTH_META)}" +
                " main=${media.getMetaExtra(MAIN_TITLE_META)}" +
                " dvda=${media.getMetaExtra(DVDA_TRACKS_META)?.replace('\n', '|')}" +
                " aacs=${media.getMetaExtra(AACS_META)}" +
                " versions=${media.getMetaExtra(TrackLists.VERSIONS_META)?.replace('\n', '|')}")
        }

        /* Every row carries the disc's title; the video rows carry the tracks. */
        val first = medias.first()
        val video = medias.firstOrNull { it.getMetaExtra(TrackLists.AUDIO_META) != null }
        return Classification.Disc(DiscInfo(
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
            mainTitle = medias.firstNotNullOfOrNull { it.getMetaExtra(MAIN_TITLE_META) }
                ?.toIntOrNull()?.takeIf { it >= 1 },
            dvdaTracks = medias.firstNotNullOfOrNull { it.getMetaExtra(DVDA_TRACKS_META) }
                .orEmpty().lineSequence().mapNotNull { it.trim().toLongOrNull() }.toList(),
            aacs = when (medias.firstNotNullOfOrNull { it.getMetaExtra(AACS_META) }) {
                "1" -> true
                "0" -> false
                else -> null
            },
        ))
    }

    private companion object {
        /* A Blu-ray reads every playlist and starts libaacs: generous.
         * libVLC's, in milliseconds. */
        const val CLASSIFY_TIMEOUT_MS = 60_000
        /* In case libVLC never ends the parse: past its own timeout, and
         * past liblaser's longest wait, which no interrupt cuts short. */
        val SAFETY_TIMEOUT = 90.seconds
        /* Set by laser.c: the DVD's, Blu-ray's or Video CD's disc ID, and
         * the main title's length in milliseconds. */
        const val DISC_ID_META = "laser-disc-id"
        const val MAIN_LENGTH_META = "laser-main-length"
        /* Set by laser.c on a DVD's video rows: the main title's number,
         * from 1. */
        const val MAIN_TITLE_META = "laser-main-title"
        /* Set by laser.c on a DVD-Audio row: its tracks' lengths in
         * milliseconds, one per line. */
        const val DVDA_TRACKS_META = "laser-dvda-tracks"
        /* Set by laser.c on a Blu-ray row: "1" if AACS-protected, "0" if
         * not. */
        const val AACS_META = "laser-aacs"
        /* Set by laser.c on its one row when there is no disc to play: why -
         * "no-medium", "tray-open", "not-ready" or "unrecognised". */
        const val STATE_META = "laser-state"
    }
}
