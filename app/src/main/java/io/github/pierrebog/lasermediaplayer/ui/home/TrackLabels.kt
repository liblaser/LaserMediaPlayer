package io.github.pierrebog.lasermediaplayer.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.pierrebog.lasermediaplayer.R
import io.github.pierrebog.lasermediaplayer.disc.AudioTrackInfo
import io.github.pierrebog.lasermediaplayer.disc.DiscInfo
import io.github.pierrebog.lasermediaplayer.disc.DiscKind
import io.github.pierrebog.lasermediaplayer.disc.SubtitleTrackInfo
import io.github.pierrebog.lasermediaplayer.settings.languageLocale
import java.text.Collator
import java.util.Locale

/** The language's name in the user's language, capitalized, or null. */
fun languageName(code: String): String? {
    if (code.isEmpty()) return null
    val name = languageLocale(code)?.getDisplayLanguage(Locale.getDefault()) ?: return null
    if (name.isEmpty() || name.equals(code, ignoreCase = true)) return null
    return name.replaceFirstChar { it.titlecase(Locale.getDefault()) }
}

/** A track with its label, for the track lists; [name] is the label
 * without the track number two alike tracks get. */
data class LabelledTrack<T>(
    val track: T, val label: String, val named: Boolean, val name: String = label,
)

/** Tracks with a language alphabetically, then the others in disc order.
 * Sorted by [LabelledTrack.name], not by the label: "(track 10)" would
 * come before "(track 2)". The sort is stable, so tracks of the same name
 * keep the disc's order, that of their numbers. */
fun <T> List<LabelledTrack<T>>.alphabetical(): List<LabelledTrack<T>> {
    val collator = Collator.getInstance(Locale.getDefault())
    return filter { it.named }.sortedWith(compareBy(collator) { it.name }) +
           filter { !it.named }
}

@Composable
fun audioTrackLabel(track: AudioTrackInfo, position: Int): String {
    val language = languageName(track.language)
        ?: stringResource(R.string.track_n, position + 1)
    val extension = when (track.extension) {
        2 -> stringResource(R.string.audio_ext_impaired)
        3 -> stringResource(R.string.audio_ext_commentary)
        4 -> stringResource(R.string.audio_ext_commentary_alt)
        else -> null
    }
    return if (extension != null) "$language, $extension" else language
}

/** "Dolby Digital 5.1", "DTS-HD Master Audio multicanal"... */
@Composable
fun audioTrackDetail(track: AudioTrackInfo): String {
    val codec = when (track.codec) {
        "ac3" -> "Dolby Digital"
        "eac3" -> "Dolby Digital Plus"
        "truehd" -> "Dolby TrueHD"
        "dts" -> "DTS"
        "dtshd" -> "DTS-HD"
        "dtshd-ma" -> "DTS-HD Master Audio"
        "lpcm" -> "PCM"
        "mpga" -> "MPEG"
        else -> track.codec.uppercase(Locale.ROOT)
    }
    val channels = when (val c = track.channels) {
        "" -> null
        "multi" -> stringResource(R.string.channels_multi)
        "1" -> stringResource(R.string.channels_mono)
        "2" -> stringResource(R.string.channels_stereo)
        "6" -> "5.1"
        "7" -> "6.1"
        "8" -> "7.1"
        else -> c.toIntOrNull()?.let { stringResource(R.string.channels_n, it) }
    }
    return listOfNotNull(codec.takeIf { it.isNotEmpty() }, channels).joinToString(" ")
}

/** Short form for the track button: "Français · 5.1". */
@Composable
fun audioTrackShort(track: AudioTrackInfo, position: Int): String {
    val language = languageName(track.language)
        ?: stringResource(R.string.track_n, position + 1)
    val channels = when (track.channels) {
        "6" -> "5.1"
        "7" -> "6.1"
        "8" -> "7.1"
        "2" -> stringResource(R.string.channels_stereo)
        else -> null
    }
    return if (channels != null) "$language · $channels" else language
}

@Composable
fun subtitleLabel(track: SubtitleTrackInfo, position: Int): String {
    val language = languageName(track.language)
        ?: stringResource(R.string.track_n, position + 1)
    val extension = when (track.extension) {
        2 -> stringResource(R.string.sub_ext_large)
        3 -> stringResource(R.string.sub_ext_children)
        5, 6, 7 -> stringResource(R.string.sub_ext_captions)
        9 -> stringResource(R.string.sub_ext_forced)
        13, 14, 15 -> stringResource(R.string.sub_ext_commentary)
        else -> null
    }
    return if (extension != null) "$language, $extension" else language
}

/*
 * The labels of all a disc's tracks, in disc order. Two tracks a disc does
 * not tell apart - same language, nothing else said - are numbered:
 * "Français (piste 2)", "Français (piste 3)".
 */

@Composable
fun audioTrackLabels(tracks: List<AudioTrackInfo>): List<String> =
    numberDuplicates(tracks.mapIndexed { i, t -> audioTrackLabel(t, i) })

@Composable
fun audioTrackShorts(tracks: List<AudioTrackInfo>): List<String> =
    numberDuplicates(tracks.mapIndexed { i, t -> audioTrackShort(t, i) })

@Composable
fun subtitleLabels(tracks: List<SubtitleTrackInfo>): List<String> =
    numberDuplicates(tracks.mapIndexed { i, t -> subtitleLabel(t, i) })

@Composable
private fun numberDuplicates(labels: List<String>): List<String> {
    val counts = labels.groupingBy { it }.eachCount()
    return labels.mapIndexed { i, label ->
        if ((counts[label] ?: 0) > 1) stringResource(R.string.track_numbered, label, i + 1) else label
    }
}

/** The disc's title as shown, with the "Disc 2" of its own title:
 * "Arcane – Saison 2 – Disc 2". */
@Composable
fun discTitle(disc: DiscInfo): String {
    val title = disc.displayTitle.ifEmpty { kindLabel(disc.kind) }
    return disc.discPart?.let { "$title – $it" } ?: title
}

@Composable
fun kindLabel(kind: DiscKind): String = stringResource(
    when (kind) {
        DiscKind.CD_AUDIO -> R.string.kind_cd
        DiscKind.DVD_VIDEO -> R.string.kind_dvd
        DiscKind.DVD_AUDIO -> R.string.kind_dvda
        DiscKind.BLURAY -> R.string.kind_bd
        DiscKind.VIDEO_CD -> R.string.kind_vcd
    }
)

/** "1:12:40", or "12:40" under an hour. */
fun formatTime(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** "2 h 04 min". */
fun formatDuration(ms: Long): String {
    val min = ms / 60_000
    return if (min >= 60) "%d h %02d min".format(min / 60, min % 60) else "$min min"
}
