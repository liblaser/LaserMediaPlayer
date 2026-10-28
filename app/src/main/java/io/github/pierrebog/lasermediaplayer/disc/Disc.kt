package io.github.pierrebog.lasermediaplayer.disc

import java.io.File

/*
 * What classification tells about a disc, and the MRLs laser.c's DirRead()
 * emits for it.
 *
 * Every row MRL ends with the descriptor of the CLASSIFICATION connection,
 * which is closed by the time anyone reads it: playback opens a connection
 * of its own and substitutes its descriptor through [LaserMrl.withFd].
 */

enum class DiscKind { CD_AUDIO, DVD_VIDEO, DVD_AUDIO, BLURAY, VIDEO_CD }

/** How a row plays the disc. A DVD-Video offers both menu modes. */
enum class PlayMode { MENUS, NO_MENUS, DIRECT }

/** One way of playing the disc, as DirRead() listed it. */
data class DiscRow(val mrl: String, val kind: DiscKind, val mode: PlayMode)

/**
 * One audio track of the main title, as laser.c read it from the disc.
 *
 * @param id the logical stream number (0-based) on a DVD, the PID on a
 *           Blu-ray, which is also the ES id of the bluray demuxer
 * @param language ISO 639-1 when there is one, else as the disc writes it;
 *                 empty if unknown
 * @param channels a count, or "multi" (Blu-ray, more than two), or empty
 * @param extension the DVD code extension: 2 visually impaired, 3 and 4
 *                  director's comments; 0 elsewhere
 */
data class AudioTrackInfo(
    val id: Int,
    val language: String,
    val codec: String,
    val channels: String,
    val extension: Int,
)

/** One subtitle track; [extension] is the DVD code extension (9 forced...). */
data class SubtitleTrackInfo(val id: Int, val language: String, val extension: Int)

/**
 * One version of the film on a DVD - a cut, by seamless branching - as
 * laser.c found it: a title sharing much of the longest one's content.
 *
 * @param title its number in the disc's title table, from 1, as dvdread
 *              numbers its titles
 */
data class VersionInfo(val title: Int, val lengthMs: Long, val chapters: Int)

/** The disc in one drive. */
data class DiscInfo(
    val deviceName: String,
    val title: String,
    val rows: List<DiscRow>,
    val audioTracks: List<AudioTrackInfo>,
    val subtitleTracks: List<SubtitleTrackInfo>,
    /** An audio CD's track lengths, from its table of contents. */
    val cdTracks: List<Long> = emptyList(),
    /** A DVD-Audio's track lengths, those of its Audio zone's first title,
     * from laser.c. */
    val dvdaTracks: List<Long> = emptyList(),
    /** What the disc lookup found: album, artist, cover, track names. */
    val album: AlbumInfo? = null,
    /** The DVD's libdvdread disc ID, the Blu-ray's AACS one, or a Video CD's
     * table of contents MD5, from laser.c. */
    val discId: String? = null,
    /** The main title's length, from laser.c; 0 if unknown. */
    val mainLengthMs: Long = 0,
    /** A Blu-ray's AACS protection, from laser.c; null if unknown. */
    val aacs: Boolean? = null,
    /** A DVD's versions of the film, the shortest first: none, or two or
     * more. The main title's tracks and length are the longest's. */
    val versions: List<VersionInfo> = emptyList(),
    /** The film on a video disc, from TMDB. */
    val film: FilmInfo? = null,
    /** The lookup of its name and picture is over, found or not - or there
     * is none for it (Video CD): what is shown is what there will be. */
    val lookupDone: Boolean = false,
) {
    /** The album's or the film's name when the lookup found one, else the
     * disc's title, a volume label written as a title. */
    val displayTitle: String
        get() = album?.title?.takeIf { it.isNotBlank() } ?: film?.title
            ?: FilmLookup.displayTitle(title)

    /** The title's "Disc 2", shown after the title, found or not. */
    val discPart: String?
        get() = FilmLookup.discPartOf(title)

    /** The album's cover or the film's poster. */
    val cover: File?
        get() = album?.cover ?: film?.poster

    val year: String?
        get() = album?.year ?: film?.year

    /** The kind shown on the disc screen. A universal DVD - an Audio zone and
     * a Video zone - is a DVD-Audio with a video part. */
    val kind: DiscKind
        get() = if (rows.any { it.kind == DiscKind.DVD_AUDIO }) DiscKind.DVD_AUDIO
                else rows.first().kind

    val hasVideoPart: Boolean
        get() = rows.any { it.kind == DiscKind.DVD_VIDEO || it.kind == DiscKind.BLURAY ||
                           it.kind == DiscKind.VIDEO_CD }

    /**
     * Key for what is remembered about the disc - resume position, chosen
     * tracks. A DVD, a Blu-ray or a Video CD is known by the disc ID laser.c
     * reads, an audio CD by its table of contents; failing that, by its
     * title - the volume label or the Blu-ray's own - which two discs may
     * share.
     */
    val key: String
        get() = when {
            discId != null -> "$kind|$discId"
            cdTracks.isNotEmpty() -> "$kind|${tocId(cdTracks)}"
            else -> "$kind|$title"
        }
}

object LaserMrl {
    private const val ACCESS = "laser"

    /** [mrl] with its trailing descriptor replaced by [fd], or null if it
     * does not end in one. */
    fun withFd(mrl: String, fd: Int): String? {
        val prefix = mrl.dropLastWhile { it.isDigit() }
        if (prefix.length == mrl.length) return null
        return "$prefix$fd"
    }

    /** The classification MRL laser.c's OpenDir() answers to. */
    fun classification(fd: Int): String = "$ACCESS-dir://$fd"

    fun rowOf(mrl: String): DiscRow? {
        val (kind, mode) = when {
            mrl.startsWith("cdda://$ACCESS/") -> DiscKind.CD_AUDIO to PlayMode.DIRECT
            mrl.startsWith("$ACCESS/dvd://") -> DiscKind.DVD_VIDEO to PlayMode.MENUS
            mrl.startsWith("$ACCESS/dvdsimple://") -> DiscKind.DVD_VIDEO to PlayMode.NO_MENUS
            mrl.startsWith("$ACCESS/dvda://") -> DiscKind.DVD_AUDIO to PlayMode.DIRECT
            mrl.startsWith("$ACCESS/bluray://") -> DiscKind.BLURAY to PlayMode.MENUS
            mrl.startsWith("vcd://$ACCESS/") -> DiscKind.VIDEO_CD to PlayMode.DIRECT
            mrl.startsWith("svcd://$ACCESS/") -> DiscKind.VIDEO_CD to PlayMode.DIRECT
            else -> return null
        }
        if (!mrl.last().isDigit()) return null
        return DiscRow(mrl, kind, mode)
    }
}

/**
 * Parses the extra metadata laser.c sets on video rows:
 *   laser-audio-tracks  id,lang,codec,channels,ext   one track per line
 *   laser-spu-tracks    id,lang,ext
 * A malformed line is skipped rather than rejecting the list, and so is a
 * track that says nothing about itself - a subtitle track without a
 * language, an audio track without a language or a codec: discs declare
 * such entries without any stream behind them, and VLC does not offer them.
 */
object TrackLists {
    const val AUDIO_META = "laser-audio-tracks"
    const val SPU_META = "laser-spu-tracks"
    /* title,length,chapters, one version per line */
    const val VERSIONS_META = "laser-versions"
    /* Versions closer in length than this, in percent of the longest, are
     * one version for the user. */
    private const val VERSION_SAME_PERCENT = 3

    /** Two versions or more, else none. Versions all of about one length -
     * the shortest within VERSION_SAME_PERCENT of the longest - offer no
     * choice: nothing worth choosing tells them apart. */
    fun parseVersions(value: String?): List<VersionInfo> {
        val versions = value.orEmpty().lineSequence().mapNotNull { line ->
            val f = line.split(',')
            if (f.size < 3) return@mapNotNull null
            val title = f[0].toIntOrNull()?.takeIf { it >= 1 } ?: return@mapNotNull null
            val length = f[1].toLongOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            VersionInfo(title, length, f[2].toIntOrNull() ?: 0)
        }.toList()
        if (versions.size < 2) return emptyList()
        val longest = versions.maxOf { it.lengthMs }
        val shortest = versions.minOf { it.lengthMs }
        if ((longest - shortest) * 100 <= longest * VERSION_SAME_PERCENT) return emptyList()
        return versions
    }

    fun parseAudio(value: String?): List<AudioTrackInfo> =
        value.orEmpty().lineSequence().mapNotNull { line ->
            val f = line.split(',')
            if (f.size < 5) return@mapNotNull null
            val id = f[0].toIntOrNull() ?: return@mapNotNull null
            if (f[1].isEmpty() && f[2].isEmpty()) return@mapNotNull null
            AudioTrackInfo(id, f[1], f[2], f[3], f[4].toIntOrNull() ?: 0)
        }.toList()

    fun parseSubtitles(value: String?): List<SubtitleTrackInfo> =
        value.orEmpty().lineSequence().mapNotNull { line ->
            val f = line.split(',')
            if (f.size < 3) return@mapNotNull null
            val id = f[0].toIntOrNull() ?: return@mapNotNull null
            if (f[1].isEmpty()) return@mapNotNull null
            SubtitleTrackInfo(id, f[1], f[2].toIntOrNull() ?: 0)
        }.toList()
}
