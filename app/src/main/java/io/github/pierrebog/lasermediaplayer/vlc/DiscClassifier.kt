package io.github.pierrebog.lasermediaplayer.vlc

import android.util.Log
import io.github.pierrebog.lasermediaplayer.LASER_TAG
import io.github.pierrebog.lasermediaplayer.disc.DiscInfo
import io.github.pierrebog.lasermediaplayer.disc.LaserMrl
import io.github.pierrebog.lasermediaplayer.disc.TrackLists
import kotlin.time.Duration.Companion.seconds
import org.videolan.libvlc.interfaces.IMedia

/** What a classification found. */
sealed interface Classification {
    data class Disc(val disc: DiscInfo) : Classification
    /** Nothing to play: an empty drive, an open tray, a drive that did not
     * become ready, a data disc. laser.c does not say which: the drive does
     * ([io.github.pierrebog.lasermediaplayer.drive.LaserDrive.poll]). */
    data object NoDisc : Classification
    /** laser.c could not open the drive: its claim refused, the device gone,
     * or not an optical drive. */
    data object Unopened : Classification
    /** The browse did not end in time. */
    data object TimedOut : Classification
}

/**
 * Classifies the disc behind an opened USB descriptor, by parsing
 * "laser-dir://<fd>" through VLC's laser access module: laser.c identifies
 * the disc with liblaser and lists one item per way of playing it, with the
 * title and, for a video disc, its tracks as extra metadata. See
 * [MediaParser].
 *
 * One classification at a time; the caller (DriveRepository) classifies
 * drives one after the other.
 */
class DiscClassifier(engine: VlcEngine) {

    private val parser = MediaParser(engine, "laser-classifier")

    /**
     * @param fd an opened, permission-granted USB descriptor, owned by the
     *           caller and kept open until this returns
     * @return the disc; or nothing to play; or a drive laser.c could not
     *         open; or a browse that timed out
     */
    suspend fun classify(fd: Int, deviceName: String): Classification =
        when (val parsed = parser.parse(LaserMrl.classification(fd), emptyList(),
                                        CLASSIFY_TIMEOUT_MS, SAFETY_TIMEOUT) { discOf(it, deviceName) }) {
            is Parsed.Done -> parsed.value
            /* libVLC's own timeout interrupts the parse and ends it; the
             * safety one only stops waiting for it. */
            Parsed.NeverEnded -> {
                Log.w(LASER_TAG, "classification of $deviceName never ended")
                Classification.TimedOut
            }
            Parsed.TimedOut -> {
                Log.w(LASER_TAG, "classification of $deviceName timed out")
                Classification.TimedOut
            }
            Parsed.Failed -> {
                Log.w(LASER_TAG, "classification of $deviceName failed to open the drive")
                Classification.Unopened
            }
        }

    private fun discOf(medias: List<IMedia>, deviceName: String): Classification {
        val rows = medias.mapNotNull { media ->
            val mrl = media.uri?.toString() ?: return@mapNotNull null
            LaserMrl.rowOf(mrl).also {
                if (it == null) Log.w(LASER_TAG, "unexpected row MRL '$mrl', ignored")
            }
        }
        if (rows.isEmpty()) {
            Log.d(LASER_TAG, "$deviceName: nothing to play")
            return Classification.NoDisc
        }

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
    }
}
