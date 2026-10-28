package io.github.pierrebog.lasermediaplayer.ui.home

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.res.stringResource
import io.github.pierrebog.lasermediaplayer.R
import io.github.pierrebog.lasermediaplayer.disc.DiscInfo
import io.github.pierrebog.lasermediaplayer.disc.VersionInfo
import io.github.pierrebog.lasermediaplayer.resume.AudioChannel
import io.github.pierrebog.lasermediaplayer.resume.TrackChoice
import io.github.pierrebog.lasermediaplayer.ui.FormFactor

/* The track sheets, shared by the disc screen and the player. */

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
