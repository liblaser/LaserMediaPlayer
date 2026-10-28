package io.github.pierrebog.lasermediaplayer.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.pierrebog.lasermediaplayer.LaserApp
import io.github.pierrebog.lasermediaplayer.disc.DiscKind
import io.github.pierrebog.lasermediaplayer.ui.home.PlaybackRequest
import io.github.pierrebog.lasermediaplayer.ui.rememberFormFactor
import io.github.pierrebog.lasermediaplayer.ui.theme.DarkPalette
import io.github.pierrebog.lasermediaplayer.ui.theme.LaserTheme

/**
 * Playback: the audio screen for a CD or a DVD-Audio played through its
 * audio zone, the video screen for everything else - a universal disc's
 * video part included. One [PlayerController] for both: they differ only in
 * what they show.
 */
@Composable
fun PlayerScreen(request: PlaybackRequest, app: LaserApp, onExit: () -> Unit) {
    val controller = remember(request) { PlayerController(app, request) }
    val ui by controller.state.collectAsStateWithLifecycle()
    val form = rememberFormFactor()
    val drives by app.drives.drives.collectAsStateWithLifecycle()
    val driveLabel = drives.firstOrNull { it.deviceName == request.disc.deviceName }?.label

    /* Saved (close) before the disc screen is told, which then reads the
     * resume point; close() again on dispose does nothing. The screen is
     * left once libVLC has stopped: its video output draws on this screen's
     * surface until then. */
    val exit: () -> Unit = { controller.close(onStopped = onExit) }
    DisposableEffect(controller) { onDispose { controller.close() } }
    LaunchedEffect(ui.ended) { if (ui.ended) exit() }
    /* The drive's eject button, its tray locked: back to the disc screen,
     * the tray opening once playback has stopped. */
    LaunchedEffect(controller) {
        app.drives.ejectRequests.collect { deviceName ->
            if (deviceName == request.disc.deviceName)
                controller.closeAndEject(onStopped = onExit)
        }
    }

    val audio = request.row.kind == DiscKind.CD_AUDIO || request.row.kind == DiscKind.DVD_AUDIO
    if (audio) {
        BackHandler(onBack = exit)
        val lookups by app.settings.lookups.collectAsStateWithLifecycle()
        AudioPlayer(request, ui, controller, form, driveLabel,
                    pictures = lookups.showsPicture(request.row.kind), onBack = exit)
    } else {
        /* Always dark, whatever the theme: the picture comes first. */
        LaserTheme(DarkPalette) {
            VideoPlayer(request, ui, controller, form, driveLabel, exit)
        }
    }
}
