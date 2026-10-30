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
 * @param audioIds the audio tracks its program chain carries, by id; null
 *                 when the disc does not say
 * @param subtitleIds likewise, its subtitle tracks
 * @param variants the titles of this same cut localised for several
 *                 languages - its own credits and audio - itself included;
 *                 empty when it has none
 */
data class VersionInfo(
    val title: Int,
    val lengthMs: Long,
    val chapters: Int,
    val audioIds: Set<Int>? = null,
    val subtitleIds: Set<Int>? = null,
    val variants: List<VersionInfo> = emptyList(),
) {
    /** The audio tracks this version carries, its variants' together; null
     * when the disc does not say. */
    val audioCarried: Set<Int>? get() = carried { it.audioIds }
    val subtitlesCarried: Set<Int>? get() = carried { it.subtitleIds }

    private fun carried(ids: (VersionInfo) -> Set<Int>?): Set<Int>? {
        val sets = variants.ifEmpty { listOf(this) }.map(ids)
        if (sets.any { it == null }) return null
        return sets.flatMapTo(HashSet()) { it.orEmpty() }
    }
}

/**
 * Of one cut's localised titles, the one carrying [audioId] - and
 * [subtitleId] too, if one does: the version of the film in that language.
 * Null when nothing tells them apart: no audio track chosen, a disc that
 * does not say what each carries, or every one of them carrying the tracks.
 */
fun pickVariant(variants: List<VersionInfo>, audioId: Int?, subtitleId: Int?): VersionInfo? {
    if (variants.size < 2 || audioId == null) return null
    val withAudio = variants.filter { it.audioIds?.contains(audioId) == true }
    if (withAudio.isEmpty() || variants.any { it.audioIds == null }) return null
    val withBoth = if (subtitleId == null) emptyList()
                   else withAudio.filter { it.subtitleIds?.contains(subtitleId) == true }
    val best = withBoth.ifEmpty { withAudio }
    if (best.size == variants.size) return null
    return best.minByOrNull { it.title }
}

/**
 * [this] disc with only the tracks [version] carries, for the disc screen
 * and the default tracks: a version may lack another's commentary, a
 * localised one has its own audio. Unchanged when the disc does not say,
 * or when no track would be left.
 */
fun DiscInfo.withTracksOf(version: VersionInfo?): DiscInfo {
    version ?: return this
    val audio = version.audioCarried
        ?.let { ids -> audioTracks.filter { it.id in ids } }?.takeIf { it.isNotEmpty() }
        ?: audioTracks
    val subtitles = version.subtitlesCarried
        ?.let { ids -> subtitleTracks.filter { it.id in ids } }?.takeIf { it.isNotEmpty() }
        ?: subtitleTracks
    return if (audio.size == audioTracks.size && subtitles.size == subtitleTracks.size) this
           else copy(audioTracks = audio, subtitleTracks = subtitles)
}

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
     * more of different lengths, the user's choice. The main title's length
     * is the longest's. */
    val versions: List<VersionInfo> = emptyList(),
    /** Without [versions], the titles of the film's one cut localised for
     * several languages, played by the audio track chosen: none, or two or
     * more. */
    val variants: List<VersionInfo> = emptyList(),
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
    /* title,length,chapters,audio,spu, one version per line, the shortest
     * first; audio and spu are hexadecimal masks of the track ids */
    const val VERSIONS_META = "laser-versions"
    /* Versions closer in length than this, in percent of the longest, are
     * one version for the user: one cut, localised. */
    private const val VERSION_SAME_PERCENT = 3

    /** The versions to choose from: two or more of different lengths, else
     * none. Titles of about one length - within VERSION_SAME_PERCENT of each
     * other - are one cut localised for several languages: one version, the
     * lowest-numbered title, holding them as its variants. */
    fun parseVersions(value: String?): List<VersionInfo> {
        val cuts = parseCuts(value)
        return if (cuts.size >= 2) cuts else emptyList()
    }

    /** With one cut only, its localised titles: two or more, else none. */
    fun parseVariants(value: String?): List<VersionInfo> =
        parseCuts(value).singleOrNull()?.variants.orEmpty()

    private fun parseCuts(value: String?): List<VersionInfo> {
        val titles = value.orEmpty().lineSequence().mapNotNull { line ->
            val f = line.split(',')
            if (f.size < 3) return@mapNotNull null
            val title = f[0].toIntOrNull()?.takeIf { it >= 1 } ?: return@mapNotNull null
            val length = f[1].toLongOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            VersionInfo(title, length, f[2].toIntOrNull() ?: 0,
                        audioIds = maskIds(f.getOrNull(3)), subtitleIds = maskIds(f.getOrNull(4)))
        }.sortedBy { it.lengthMs }.toList()
        if (titles.size < 2) return emptyList()
        /* By length, the shortest first: a title within the percentage of
         * its group's shortest is one of its variants. */
        val groups = ArrayList<MutableList<VersionInfo>>()
        for (t in titles) {
            val group = groups.lastOrNull()
            if (group != null &&
                (t.lengthMs - group.first().lengthMs) * 100 <= t.lengthMs * VERSION_SAME_PERCENT)
                group += t
            else
                groups += mutableListOf(t)
        }
        return groups.map { group ->
            val first = group.minBy { it.title }
            if (group.size < 2) first
            else first.copy(lengthMs = group.maxOf { it.lengthMs }, variants = group.sortedBy { it.title })
        }
    }

    /* A hexadecimal mask of track ids, bit 0 for id 0; null when absent or
     * none: the disc does not say. */
    private fun maskIds(value: String?): Set<Int>? {
        val mask = value?.trim()?.takeIf { it.isNotEmpty() }?.toLongOrNull(16) ?: return null
        if (mask == 0L) return null
        return (0 until 32).filterTo(HashSet()) { mask and (1L shl it) != 0L }
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
