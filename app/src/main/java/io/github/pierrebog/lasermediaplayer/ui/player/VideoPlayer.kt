package io.github.pierrebog.lasermediaplayer.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.pierrebog.lasermediaplayer.R
import io.github.pierrebog.lasermediaplayer.disc.DiscKind
import io.github.pierrebog.lasermediaplayer.ui.FormFactor
import io.github.pierrebog.lasermediaplayer.ui.home.AudioSheet
import io.github.pierrebog.lasermediaplayer.ui.home.ChannelSheet
import io.github.pierrebog.lasermediaplayer.ui.home.PlaybackRequest
import io.github.pierrebog.lasermediaplayer.ui.home.SubtitleSheet
import io.github.pierrebog.lasermediaplayer.ui.home.audioTrackShorts
import io.github.pierrebog.lasermediaplayer.ui.home.channelLabel
import io.github.pierrebog.lasermediaplayer.ui.home.discTitle
import io.github.pierrebog.lasermediaplayer.ui.home.formatTime
import io.github.pierrebog.lasermediaplayer.ui.home.subtitleLabels
import io.github.pierrebog.lasermediaplayer.ui.theme.LaserColors
import io.github.pierrebog.lasermediaplayer.ui.theme.LaserType
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt
import org.videolan.libvlc.util.VLCVideoLayout

private enum class Sheet { AUDIO, SUBTITLES }

/**
 * The video player: DVD-Video, Blu-ray, VCD. "Lecture vidéo" in the C′
 * mockups.
 *
 * TV and landscape: the picture full screen, the controls over it - title and
 * chapter on top; seek bar with the chapters, transport, and the fill,
 * audio, subtitle and disc menu buttons at the bottom - hidden after a few
 * seconds of playback. Portrait: the picture in a band at the top, the
 * controls below, always shown.
 *
 * "Remplir l'écran" crops the picture to the screen. It is offered only
 * full screen, when the picture's shape is not the screen's, and the choice
 * is remembered for the disc, like its tracks.
 *
 * The remote: while the controls are hidden, left/right seek and any other
 * key shows them; while a disc menu shows, the arrows and OK go to it. Media
 * keys work at any time.
 */
@Composable
fun VideoPlayer(
    request: PlaybackRequest,
    ui: PlayerUi,
    controller: PlayerController,
    form: FormFactor,
    driveLabel: String?,
    onBack: () -> Unit,
) {
    val overlay = form != FormFactor.PORTRAIT
    var controls by remember { mutableStateOf(true) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    /* Bumped on every key or tap: the controls stay while the user acts. */
    var activity by remember { mutableIntStateOf(0) }
    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }

    if (overlay) FullScreen()
    BackHandler {
        when {
            sheet != null -> sheet = null
            overlay && controls && form == FormFactor.TV && ui.playing -> controls = false
            else -> onBack()
        }
    }
    /* Controls hide themselves while playing, in the overlay only. */
    LaunchedEffect(controls, ui.playing, activity, sheet, overlay) {
        if (overlay && controls && ui.playing && sheet == null) {
            delay(CONTROLS_TIMEOUT_MS)
            controls = false
        }
    }
    /* The remote's focus: on play/pause with the controls, else on the screen
     * itself, which takes the keys. */
    LaunchedEffect(controls, ui.inMenu) {
        runCatching {
            if (controls && !ui.inMenu) playFocus.requestFocus() else rootFocus.requestFocus()
        }
    }

    /* One picture view for both layouts: a new view on rotation loses the
     * video. In portrait, it is placed over the band's slot. */
    var rootAt by remember { mutableStateOf<Offset?>(null) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    var slot by remember { mutableStateOf<Rect?>(null) }
    val root = rootAt
    val at = slot
    val band = if (!overlay && root != null && at != null) at.translate(-root) else null
    val density = LocalDensity.current
    val videoModifier = when {
        overlay -> Modifier.fillMaxSize()
        band != null -> with(density) {
            Modifier.offset { IntOffset(band.left.roundToInt(), band.top.roundToInt()) }
                .size(band.width.toDp(), band.height.toDp())
        }
        else -> Modifier.fillMaxWidth().aspectRatio(16f / 9f)
    }

    /* Black bars to fill: full screen, a picture of another shape. Never in
     * a disc menu, which has no control to undo it and whose buttons may be
     * cropped away: the film's choice comes back with the film. */
    val canFill = overlay && ui.videoAspect > 0f && rootSize.width > 0 && rootSize.height > 0 &&
        abs(ui.videoAspect * rootSize.height / rootSize.width - 1f) > FILL_THRESHOLD
    val cropped = canFill && ui.fill && !ui.inMenu
    LaunchedEffect(cropped) { controller.setCropped(cropped) }

    val showControls: () -> Unit = { controls = true; activity++ }
    Box(
        Modifier
            .fillMaxSize()
            .background(if (overlay) Color.Black else LaserColors.Background)
            .onGloballyPositioned { rootAt = it.positionInRoot(); rootSize = it.size }
            /* Any touch - on the seek bar, a button, a drag - keeps the
             * controls up for another while, as a key does: seen before the
             * controls take it, and left to them. */
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        activity++
                    }
                }
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || sheet != null) return@onPreviewKeyEvent false
                activity++
                handleKey(event.key, ui, controller, controlsShown = controls || !overlay,
                          showControls = showControls)
            }
            .focusRequester(rootFocus)
            .focusable(),
    ) {
        Video(controller, videoModifier)
        if (overlay) {
            /* A tap shows or hides the controls, or in a disc menu presses
             * its button; over the picture, whose view would take the
             * touches. */
            if (ui.inMenu)
                MenuTouch(controller, Modifier.fillMaxSize())
            else
                Box(Modifier.fillMaxSize().clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    if (controls) controls = false else showControls()
                })
            if (ui.inMenu && form != FormFactor.TV && controller.menuArrows) {
                MenuPad(controller, Modifier.align(Alignment.BottomEnd).padding(24.dp))
                /* The controls, and their back button, do not show over a
                 * menu: its own, where the controls' would be, with the
                 * arrows - both off, the menu is left alone. */
                CircleButton(R.drawable.ic_chevron_left, stringResource(R.string.back), 44.dp,
                             onClick = onBack, translucent = true,
                             modifier = Modifier.align(Alignment.TopStart).safeDrawingPadding()
                                 .padding(horizontal = 24.dp, vertical = 16.dp))
            }
            if (controls && !ui.error && !ui.inMenu)
                Overlay(request, ui, controller, form, playFocus, onBack, canFill,
                        onAudio = { sheet = Sheet.AUDIO }, onSubtitles = { sheet = Sheet.SUBTITLES })
        } else {
            Portrait(request, ui, controller, driveLabel, playFocus, onBack,
                     onAudio = { sheet = Sheet.AUDIO }, onSubtitles = { sheet = Sheet.SUBTITLES },
                     onBand = { slot = it.boundsInRoot() })
            if (ui.inMenu) MenuTouch(controller, videoModifier)
        }

        if (ui.error)
            Text(stringResource(if (ui.aacsError) R.string.player_error_aacs else R.string.player_error),
                 style = LaserType.Body, textAlign = TextAlign.Center,
                 modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp))

        Sheets(request, ui, controller, form, sheet) { sheet = null }
    }
}

/** The remote's keys. Returns whether the key was used. */
private fun handleKey(key: Key, ui: PlayerUi, controller: PlayerController,
                      controlsShown: Boolean, showControls: () -> Unit): Boolean {
    when (key) {
        Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> { controller.togglePause(); return true }
        Key.MediaNext -> { controller.nextChapter(); return true }
        Key.MediaPrevious -> { controller.previousChapter(); return true }
        Key.MediaFastForward -> { controller.seekBy(SEEK_STEP_MS); showControls(); return true }
        Key.MediaRewind -> { controller.seekBy(-SEEK_STEP_MS); showControls(); return true }
    }
    if (ui.inMenu) {
        val action = when (key) {
            Key.DirectionUp -> NavAction.UP
            Key.DirectionDown -> NavAction.DOWN
            Key.DirectionLeft -> NavAction.LEFT
            Key.DirectionRight -> NavAction.RIGHT
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> NavAction.ACTIVATE
            else -> null
        }
        if (action != null) {
            controller.navigate(action)
            return true
        }
        return false
    }
    /* The controls are up: the focus moves between them. */
    if (controlsShown) return false
    when (key) {
        Key.DirectionLeft -> controller.seekBy(-SEEK_STEP_MS)
        Key.DirectionRight -> controller.seekBy(SEEK_STEP_MS)
        Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.DirectionUp, Key.DirectionDown -> {}
        else -> return false
    }
    showControls()
    return true
}

/** The picture. The black is drawn under the view, which shows through it. */
@Composable
private fun Video(controller: PlayerController, modifier: Modifier) {
    AndroidView(
        factory = { context ->
            VLCVideoLayout(context).also {
                it.keepScreenOn = true
                controller.attach(it)
                controller.start(it)
            }
        },
        onRelease = { controller.detach(it) },
        modifier = modifier.background(Color.Black),
    )
}

/** Where the touch layer is, for its taps' window coordinates: not state,
 * nothing is drawn from it. */
private class Placement { var coordinates: LayoutCoordinates? = null }

/** Over the picture while a disc menu shows: a tap presses the button under
 * it. */
@Composable
private fun MenuTouch(controller: PlayerController, modifier: Modifier) {
    val placement = remember { Placement() }
    Box(
        modifier
            .onGloballyPositioned { placement.coordinates = it }
            .pointerInput(controller) {
                detectTapGestures { tap ->
                    val coordinates = placement.coordinates?.takeIf { it.isAttached }
                        ?: return@detectTapGestures
                    val at = coordinates.localToWindow(tap)
                    controller.tapMenu(at.x, at.y)
                }
            },
    )
}

/* ---- TV and landscape ------------------------------------------------------ */

@Composable
private fun BoxScope.Overlay(
    request: PlaybackRequest, ui: PlayerUi, controller: PlayerController, form: FormFactor,
    playFocus: FocusRequester, onBack: () -> Unit, canFill: Boolean,
    onAudio: () -> Unit, onSubtitles: () -> Unit,
) {
    val tv = form == FormFactor.TV
    val scrim = Color(0xFF05070A)
    Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(if (tv) 160.dp else 100.dp)
            .background(Brush.verticalGradient(listOf(scrim.copy(alpha = 0.8f), Color.Transparent))))
    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(if (tv) 320.dp else 190.dp)
            .background(Brush.verticalGradient(listOf(Color.Transparent, scrim.copy(alpha = 0.7f),
                                                     scrim.copy(alpha = 0.95f)))))
    Column(
        Modifier
            .fillMaxSize()
            .then(if (tv) Modifier else Modifier.safeDrawingPadding())
            .padding(horizontal = if (tv) 72.dp else 24.dp, vertical = if (tv) 40.dp else 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (tv) 24.dp else 14.dp)) {
            CircleButton(R.drawable.ic_chevron_left, stringResource(R.string.back),
                         if (tv) 56.dp else 44.dp, onClick = onBack, translucent = true)
            Titles(request, ui, if (tv) 40.sp else 24.sp, if (tv) 19.sp else 13.sp)
        }
        Spacer(Modifier.weight(1f))
        Column(verticalArrangement = Arrangement.spacedBy(if (tv) 24.dp else 14.dp)) {
            TimeRow(ui, controller, if (tv) 19.sp else 13.sp, Color(0xFFC2C8CF),
                    trackColor = LaserColors.Text.copy(alpha = 0.25f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Transport(ui, controller, if (tv) 56.dp else 46.dp, if (tv) 68.dp else 56.dp,
                          if (tv) 18.dp else 14.dp, playFocus, translucent = true)
                Row(horizontalArrangement = Arrangement.spacedBy(if (tv) 12.dp else 8.dp)) {
                    if (canFill) {
                        val fill = stringResource(R.string.fill_screen)
                        PillButton(R.drawable.ic_fill_screen, fill, fill, if (tv) 52.dp else 44.dp,
                                   if (tv) 18.sp else 14.sp, onClick = { controller.chooseFill(!ui.fill) },
                                   translucent = true, selected = ui.fill)
                    }
                    TrackButtons(request, ui, controller, if (tv) 52.dp else 44.dp,
                                 if (tv) 18.sp else 14.sp, onAudio, onSubtitles, translucent = true)
                }
            }
        }
    }
}

/* ---- Portrait ----------------------------------------------------------------- */

@Composable
private fun Portrait(
    request: PlaybackRequest, ui: PlayerUi, controller: PlayerController, driveLabel: String?,
    playFocus: FocusRequester, onBack: () -> Unit,
    onAudio: () -> Unit, onSubtitles: () -> Unit,
    onBand: (LayoutCoordinates) -> Unit,
) {
    /* The picture spans the screen's width; the rest has margins. */
    val margins = Modifier.padding(horizontal = 20.dp)
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PlayerHeader(onBack, driveLabel, tv = false, modifier = margins)
        /* The picture's slot: empty, the view is placed over it. */
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).onGloballyPositioned(onBand))
        Box(margins) { Titles(request, ui, 30.sp, 15.sp) }
        /* No time in a disc's menu. */
        if (!ui.inMenu)
            Box(margins) { TimeRow(ui, controller, 14.sp, LaserColors.Muted, below = true) }
        Spacer(Modifier.weight(1f))
        if (ui.inMenu) {
            if (controller.menuArrows)
                MenuPad(controller, Modifier.align(Alignment.CenterHorizontally))
        } else {
            Column(margins, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Transport(ui, controller, 52.dp, 64.dp, 28.dp, playFocus)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TrackButtons(request, ui, controller, 48.dp, 15.sp, onAudio, onSubtitles,
                                 weighted = true, withMenu = false)
                }
                if (controller.hasDiscMenu)
                    PillButton(R.drawable.ic_menu, stringResource(R.string.disc_menu),
                               stringResource(R.string.disc_menu), 48.dp, 15.sp,
                               onClick = controller::openDiscMenu, modifier = Modifier.fillMaxWidth(),
                               translucent = true)
            }
        }
    }
}

/* ---- shared pieces ----------------------------------------------------------- */

/** The disc's title, and the chapter's name - "Le phare" - or, for a
 * chapter without one, "Chapitre 9 sur 18". None in a menu. */
@Composable
private fun Titles(request: PlaybackRequest, ui: PlayerUi, titleSize: TextUnit, lineSize: TextUnit) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        OneLine(discTitle(request.disc),
                titleSize)
        if (!ui.inMenu && ui.chapters.size > 1 && ui.chapter >= 0) {
            val name = ui.chapters[ui.chapter].name
            /* A VCD's chapters are its tracks. */
            val line = name ?: stringResource(
                if (request.row.kind == DiscKind.VIDEO_CD) R.string.track_n_of else R.string.chapter_n_of,
                ui.chapter + 1, ui.chapters.size)
            OneLine(line, lineSize, Color(0xFFC2C8CF))
        }
    }
}

/** Elapsed time, seek bar with the chapters, total time - side by side, or
 * the times below the bar. */
@Composable
private fun TimeRow(ui: PlayerUi, controller: PlayerController, size: TextUnit, color: Color,
                    below: Boolean = false, trackColor: Color = LaserColors.Track) {
    val ticks = ui.chapters.map { it.startMs }
    if (below) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SeekBar(ui.timeMs, ui.lengthMs, controller::seekTo, Modifier.fillMaxWidth(), ticks,
                    trackColor = trackColor)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                OneLine(formatTime(ui.timeMs), size, color, tabular = true)
                if (ui.lengthMs > 0) OneLine(formatTime(ui.lengthMs), size, color, tabular = true)
            }
        }
    } else {
        /* The elapsed time takes the room of the longest it can be, the
         * length's: the bar between keeps its width, and its chapter ticks
         * their place, as the seconds change. */
        val measurer = rememberTextMeasurer()
        val timeWidth = with(LocalDensity.current) {
            measurer.measure(formatTime(maxOf(ui.timeMs, ui.lengthMs)),
                             LaserType.Body.copy(fontSize = size, fontFeatureSettings = "tnum"))
                .size.width.toDp()
        }
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(Modifier.width(timeWidth), contentAlignment = Alignment.CenterEnd) {
                OneLine(formatTime(ui.timeMs), size, color, tabular = true)
            }
            SeekBar(ui.timeMs, ui.lengthMs, controller::seekTo, Modifier.weight(1f), ticks,
                    trackColor = trackColor)
            if (ui.lengthMs > 0) OneLine(formatTime(ui.lengthMs), size, color, tabular = true)
        }
    }
}

@Composable
private fun Transport(ui: PlayerUi, controller: PlayerController, small: Dp, big: Dp, gap: Dp,
                      playFocus: FocusRequester, translucent: Boolean = false) {
    val vcd = controller.isVcd
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(gap)) {
        /* Chapter buttons only when there are chapters to go to. */
        val chapters = ui.chapters.size > 1
        if (chapters)
            CircleButton(R.drawable.ic_skip_previous, stringResource(if (vcd) R.string.previous_track else R.string.previous_chapter), small,
                         onClick = controller::previousChapter, translucent = translucent,
                         enabled = ui.chapter > 0 || canRestartChapter(ui))
        CircleButton(if (ui.playing) R.drawable.ic_pause else R.drawable.ic_play,
                     stringResource(if (ui.playing) R.string.player_pause else R.string.player_play),
                     big, onClick = controller::togglePause, primary = true,
                     modifier = Modifier.focusRequester(playFocus))
        if (chapters)
            CircleButton(R.drawable.ic_skip_next, stringResource(if (vcd) R.string.next_track else R.string.next_chapter), small,
                         onClick = controller::nextChapter, translucent = translucent,
                         enabled = ui.chapter + 1 < ui.chapters.size)
    }
}

/** Far enough into the chapter for "previous" to go back to its start. */
private fun canRestartChapter(ui: PlayerUi): Boolean {
    val chapter = ui.chapters.getOrNull(ui.chapter) ?: return false
    return ui.timeMs - chapter.startMs > RESTART_THRESHOLD_MS
}

/**
 * Audio, subtitles, disc menu - each only when the disc has some. A VCD's
 * audio button is the channel choice.
 */
@Composable
private fun RowScope.TrackButtons(
    request: PlaybackRequest, ui: PlayerUi, controller: PlayerController,
    height: Dp, fontSize: TextUnit, onAudio: () -> Unit, onSubtitles: () -> Unit,
    translucent: Boolean = false, weighted: Boolean = false, withMenu: Boolean = true,
) {
    val disc = request.disc
    val audioLabel = stringResource(R.string.audio_track)
    val subtitlesLabel = stringResource(R.string.subtitles)
    val each = if (weighted) Modifier.weight(1f) else Modifier
    val audioText: String? = when {
        request.row.kind == DiscKind.VIDEO_CD -> channelLabel(ui.tracks.channel)
        disc.audioTracks.isNotEmpty() -> {
            val i = disc.audioTracks.indexOfFirst { it.id == ui.selected.audioId }.coerceAtLeast(0)
            audioTrackShorts(disc.audioTracks)[i]
        }
        else -> null
    }
    val subtitleText: String? = if (disc.subtitleTracks.isNotEmpty()) {
        val i = disc.subtitleTracks.indexOfFirst { it.id == ui.selected.subtitleId }
        if (i >= 0) subtitleLabels(disc.subtitleTracks)[i] else stringResource(R.string.subtitles_off)
    } else null

    if (audioText != null)
        PillButton(R.drawable.ic_audio, audioText, "$audioLabel, $audioText", height, fontSize,
                   onAudio, each, translucent)
    if (subtitleText != null)
        PillButton(R.drawable.ic_subtitles, subtitleText, "$subtitlesLabel, $subtitleText", height,
                   fontSize, onSubtitles, each, translucent)
    if (withMenu && controller.hasDiscMenu)
        PillButton(R.drawable.ic_menu, stringResource(R.string.disc_menu),
                   stringResource(R.string.disc_menu), height, fontSize,
                   controller::openDiscMenu, translucent = translucent)
}

/** The track sheets, as on the disc screen; a choice applies at once and is
 * saved for the disc. */
@Composable
private fun BoxScope.Sheets(request: PlaybackRequest, ui: PlayerUi, controller: PlayerController,
                            form: FormFactor, sheet: Sheet?, onClose: () -> Unit) {
    val disc = request.disc
    when (sheet) {
        Sheet.AUDIO -> if (request.row.kind == DiscKind.VIDEO_CD)
            ChannelSheet(ui.tracks, form,
                onPick = { controller.chooseTracks(ui.tracks.copy(channel = it)); onClose() },
                onDismiss = onClose)
        else
            AudioSheet(disc, ui.selected, form,
                onPick = { controller.chooseTracks(ui.selected.copy(audioId = it)); onClose() },
                onDismiss = onClose)
        Sheet.SUBTITLES -> SubtitleSheet(disc, ui.selected, form,
            onPick = { controller.chooseTracks(ui.selected.copy(subtitleId = it, subtitlesOff = it == null)); onClose() },
            onDismiss = onClose)
        null -> {}
    }
}

/** Arrow pad for a disc menu on a touch screen. */
@Composable
private fun MenuPad(controller: PlayerController, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally,
           verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CircleButton(R.drawable.ic_chevron_up, stringResource(R.string.nav_up), 52.dp,
                     onClick = { controller.navigate(NavAction.UP) }, translucent = true)
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CircleButton(R.drawable.ic_chevron_left, stringResource(R.string.nav_left), 52.dp,
                         onClick = { controller.navigate(NavAction.LEFT) }, translucent = true)
            CircleButton(R.drawable.ic_ok, stringResource(R.string.nav_ok), 52.dp,
                         onClick = { controller.navigate(NavAction.ACTIVATE) }, translucent = true)
            CircleButton(R.drawable.ic_chevron_right, stringResource(R.string.nav_right), 52.dp,
                         onClick = { controller.navigate(NavAction.RIGHT) }, translucent = true)
        }
        CircleButton(R.drawable.ic_chevron_down, stringResource(R.string.nav_down), 52.dp,
                     onClick = { controller.navigate(NavAction.DOWN) }, translucent = true)
    }
}

/** Hides the system bars while the full-screen player is shown. */
@Composable
private fun FullScreen() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        val insets = window?.let { WindowCompat.getInsetsController(it, view) }
        insets?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { insets?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private const val SEEK_STEP_MS = 10_000L
private const val CONTROLS_TIMEOUT_MS = 4_000L
/* Shapes closer than this are the same: a DVD's 720 pixels hold a few
 * columns of margin. */
private const val FILL_THRESHOLD = 0.03f
