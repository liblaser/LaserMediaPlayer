package io.github.pierrebog.lasermediaplayer.ui.home

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.res.stringResource
import io.github.pierrebog.lasermediaplayer.R
import io.github.pierrebog.lasermediaplayer.disc.DiscInfo
import io.github.pierrebog.lasermediaplayer.disc.DiscKind
import io.github.pierrebog.lasermediaplayer.disc.VersionInfo
import io.github.pierrebog.lasermediaplayer.resume.AudioChannel
import io.github.pierrebog.lasermediaplayer.resume.TrackChoice
import io.github.pierrebog.lasermediaplayer.settings.languageName
import io.github.pierrebog.lasermediaplayer.ui.FormFactor

/* The track sheets, shared by the disc screen and the player. */

/** The track sheets a disc's buttons open. */
enum class TrackSheetKind { AUDIO, SUBTITLES }

/**
 * The audio sheet - a Video CD's channels - or the subtitle sheet, showing
 * [choice]. A pick gives the new choice, which the caller applies and saves,
 * then closes the sheet.
 */
@Composable
fun BoxScope.TrackSheets(
    disc: DiscInfo, choice: TrackChoice, sheet: TrackSheetKind, form: FormFactor,
    onChoice: (TrackChoice) -> Unit, onDismiss: () -> Unit,
) {
    when (sheet) {
        TrackSheetKind.AUDIO -> if (disc.kind == DiscKind.VIDEO_CD)
            ChannelSheet(choice, form, onPick = { onChoice(choice.copy(channel = it)) },
                         onDismiss = onDismiss)
        else
            AudioSheet(disc, choice, form, onPick = { onChoice(choice.copy(audioId = it)) },
                       onDismiss = onDismiss)
        TrackSheetKind.SUBTITLES -> SubtitleSheet(disc, choice, form,
            onPick = { onChoice(choice.copy(subtitleId = it, subtitlesOff = it == null)) },
            onDismiss = onDismiss)
    }
}

/**
 * What the audio and subtitle buttons say for [choice]: the audio track -
 * "Français · 5.1", or a Video CD's channel - and the subtitles, or "None".
 * Null for a button the disc has nothing to choose with.
 */
@Composable
fun trackButtonTexts(disc: DiscInfo, choice: TrackChoice): Pair<String?, String?> {
    if (disc.kind == DiscKind.VIDEO_CD) return channelLabel(choice.channel) to null
    /* A new disc plays its first track, as the disc orders them. */
    val audio = if (disc.audioTracks.isEmpty()) null else
        audioTrackShorts(disc.audioTracks)[
            disc.audioTracks.indexOfFirst { it.id == choice.audioId }.coerceAtLeast(0)]
    val subtitles = if (disc.subtitleTracks.isEmpty()) null else {
        val i = disc.subtitleTracks.indexOfFirst { it.id == choice.subtitleId }
        if (i >= 0) subtitleLabels(disc.subtitleTracks)[i] else stringResource(R.string.subtitles_off)
    }
    return audio to subtitles
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun BoxScope.AudioSheet(
    disc: DiscInfo, choice: TrackChoice, form: FormFactor,
    onPick: (Int?) -> Unit, onDismiss: () -> Unit,
) {
    val labels = audioTrackLabels(disc.audioTracks)
    val tracks = disc.audioTracks.mapIndexed { i, t ->
        LabelledTrack(t, labels[i], languageName(t.language) != null, audioTrackLabel(t, i))
    }.alphabetical()
    val options = tracks.map { TrackOption(it.label, audioTrackDetail(it.track)) }
    val selected = tracks.indexOfFirst { it.track.id == choice.audioId }
    TrackSheet(
        title = stringResource(R.string.audio_track),
        options = options, selected = selected, form = form,
        onPick = { i -> onPick(tracks[i].track.id) },
        onDismiss = onDismiss,
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun BoxScope.SubtitleSheet(
    disc: DiscInfo, choice: TrackChoice, form: FormFactor,
    onPick: (Int?) -> Unit, onDismiss: () -> Unit,
) {
    val labels = subtitleLabels(disc.subtitleTracks)
    val tracks = disc.subtitleTracks.mapIndexed { i, t ->
        LabelledTrack(t, labels[i], languageName(t.language) != null, subtitleLabel(t, i))
    }.alphabetical()
    val options = listOf(TrackOption(stringResource(R.string.subtitles_off), null)) +
        tracks.map { TrackOption(it.label, null) }
    val selected = tracks.indexOfFirst { it.track.id == choice.subtitleId } + 1
    TrackSheet(
        title = stringResource(R.string.subtitles),
        options = options, selected = selected, form = form,
        onPick = { i -> onPick(if (i == 0) null else tracks[i - 1].track.id) },
        onDismiss = onDismiss,
    )
}

/** A version's name: short and long when there are two, else its length. */
@Composable
fun versionLabel(version: VersionInfo, versions: List<VersionInfo>): String =
    if (versions.size == 2)
        stringResource(if (version == versions.minByOrNull { it.lengthMs }) R.string.version_short
                       else R.string.version_long)
    else formatTime(version.lengthMs)

/** The versions of the film, the shortest first, with their lengths. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun BoxScope.VersionSheet(
    disc: DiscInfo, version: VersionInfo?, form: FormFactor,
    onPick: (VersionInfo) -> Unit, onDismiss: () -> Unit,
) {
    val versions = disc.versions
    TrackSheet(
        title = stringResource(R.string.version),
        options = versions.map {
            TrackOption(versionLabel(it, versions),
                        if (versions.size == 2) formatTime(it.lengthMs) else null)
        },
        selected = versions.indexOf(version), form = form,
        onPick = { i -> onPick(versions[i]) },
        onDismiss = onDismiss,
    )
}

@Composable
fun channelLabel(channel: AudioChannel): String = stringResource(
    when (channel) {
        AudioChannel.STEREO -> R.string.channel_stereo
        AudioChannel.LEFT -> R.string.channel_left
        AudioChannel.RIGHT -> R.string.channel_right
    }
)

/** VCD: some discs carry one language per channel. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun BoxScope.ChannelSheet(
    choice: TrackChoice, form: FormFactor,
    onPick: (AudioChannel) -> Unit, onDismiss: () -> Unit,
) {
    val channels = AudioChannel.entries
    val both = stringResource(R.string.channel_all_speakers)
    TrackSheet(
        title = stringResource(R.string.audio_track),
        options = channels.map {
            TrackOption(channelLabel(it), if (it == AudioChannel.STEREO) null else both)
        },
        selected = channels.indexOf(choice.channel), form = form,
        onPick = { i -> onPick(channels[i]) },
        onDismiss = onDismiss,
    )
}
