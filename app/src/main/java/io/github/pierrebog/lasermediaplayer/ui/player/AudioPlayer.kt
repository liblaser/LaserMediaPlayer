package io.github.pierrebog.lasermediaplayer.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pierrebog.lasermediaplayer.R
import io.github.pierrebog.lasermediaplayer.ui.FormFactor
import io.github.pierrebog.lasermediaplayer.ui.home.PlaybackRequest
import io.github.pierrebog.lasermediaplayer.ui.home.discTitle
import io.github.pierrebog.lasermediaplayer.ui.home.formatDuration
import io.github.pierrebog.lasermediaplayer.ui.home.formatTime
import io.github.pierrebog.lasermediaplayer.ui.theme.LaserColors
import java.io.File

/**
 * The audio player: CD audio, and DVD-Audio played through its audio zone.
 * "Lecture audio" in the C′ mockups: the album, the track list with the one
 * playing marked, and the transport. TV and landscape: album and transport on
 * the left, list on the right; portrait: album on top, list, transport in a
 * panel at the bottom.
 *
 * The album's name, artist and cover, and the tracks' names, come from the
 * disc lookup (AlbumLookup); without it, the disc's title, a placeholder
 * cover and "Track N". With the lookup turned off ([pictures] false), no
 * cover at all - "sans pochette" in the mockups.
 */
@Composable
fun AudioPlayer(
    request: PlaybackRequest,
    ui: PlayerUi,
    controller: PlayerController,
    form: FormFactor,
    driveLabel: String?,
    pictures: Boolean,
    onBack: () -> Unit,
) {
    LaunchedEffect(controller) { controller.start(null) }
    val playFocus = remember { FocusRequester() }
    /* The remote starts on play/pause. */
    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }

    val title = discTitle(request.disc)
    val artist = request.disc.album?.artist
    val cover = request.disc.album?.cover
    val modifier = Modifier
        .fillMaxSize()
        .background(LaserColors.Background)
        .onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
            when (event.key) {
                Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> controller.togglePause()
                Key.MediaNext -> controller.nextItem()
                Key.MediaPrevious -> controller.previousItem()
                else -> return@onKeyEvent false
            }
            true
        }

    when (form) {
        FormFactor.TV -> Column(
            modifier.padding(start = 72.dp, end = 72.dp, top = 40.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            PlayerHeader(onBack, driveLabel, tv = true)
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(64.dp)) {
                Column(Modifier.width(340.dp).fillMaxHeight(),
                       verticalArrangement = if (pictures) Arrangement.spacedBy(14.dp)
                                             else Arrangement.spacedBy(22.dp, Alignment.CenterVertically)) {
                    /* With a cover, all the room the controls leave, as in
                     * landscape; without, the name alone, centred. */
                    if (pictures)
                        FittedAlbum(title, artist, cover, true, ui, titleSize = 40.sp,
                                    artistScale = 0.5f, gap = 14.dp, modifier = Modifier.weight(1f))
                    else
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            OneLine(title, 60.sp)
                            artist?.let { OneLine(it, 24.sp, LaserColors.Muted) }
                        }
                    ItemProgress(ui, controller, 17.sp)
                    Transport(ui, controller, small = 56.dp, big = 72.dp, playFocus = playFocus)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ListHeader(ui, 17.sp)
                    TrackList(ui, controller, rowHeight = 40.dp, fontSize = 19.sp,
                              modifier = Modifier.weight(1f))
                }
            }
        }

        FormFactor.LANDSCAPE -> Row(
            modifier.safeDrawingPadding().padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Column(Modifier.width(320.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PlayerHeader(onBack, driveLabel, tv = false)
                /* All the room the controls leave, for the album. */
                FittedAlbum(title, artist, cover.takeIf { pictures }, pictures, ui,
                            titleSize = if (pictures) 28.sp else 36.sp,
                            modifier = Modifier.weight(1f))
                NowPlaying(ui, 15.sp)
                ItemProgress(ui, controller, 13.sp)
                Transport(ui, controller, small = 48.dp, big = 60.dp, playFocus = playFocus)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ListHeader(ui, 14.sp)
                TrackList(ui, controller, rowHeight = 44.dp, fontSize = 15.sp,
                          modifier = Modifier.weight(1f))
            }
        }

        FormFactor.PORTRAIT -> Column(modifier.safeDrawingPadding()) {
            Column(Modifier.weight(1f).padding(start = 20.dp, end = 20.dp, top = 12.dp),
                   verticalArrangement = Arrangement.spacedBy(16.dp)) {
                PlayerHeader(onBack, driveLabel, tv = false)
                Album(title, artist, cover.takeIf { pictures }, pictures, ui, coverSize = 112.dp,
                      titleSize = if (pictures) 30.sp else 40.sp, withCount = true)
                TrackList(ui, controller, rowHeight = 48.dp, fontSize = 16.sp,
                          modifier = Modifier.weight(1f))
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(LaserColors.Sheet)
                    .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 20.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                NowPlaying(ui, 16.sp)
                ItemProgress(ui, controller, 13.sp)
                Transport(ui, controller, small = 52.dp, big = 64.dp, playFocus = playFocus)
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Album(title: String, artist: String?, cover: File?, showCover: Boolean, ui: PlayerUi,
                  coverSize: Dp, titleSize: TextUnit, withCount: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        if (showCover) Cover(cover, coverSize)
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            OneLine(title, titleSize)
            artist?.let { OneLine(it, titleSize * 0.55f, LaserColors.Muted) }
            if (withCount && ui.playlist.isNotEmpty())
                OneLine(pluralStringResource(R.plurals.tracks_count, ui.playlist.size, ui.playlist.size) +
                        totalDuration(ui)?.let { " · $it" }.orEmpty(), 14.sp, LaserColors.Muted)
        }
    }
}

/**
 * The album in landscape and on TV, in the room [modifier] gives it: the
 * cover as large as that room allows, with the name and artist below it -
 * unless that would make it no larger than the small cover the name sits
 * beside, which is then used. The controls below always keep their room.
 */
@Composable
private fun FittedAlbum(title: String, artist: String?, cover: File?, showCover: Boolean,
                        ui: PlayerUi, titleSize: TextUnit, modifier: Modifier,
                        artistScale: Float = 0.55f, gap: Dp = 12.dp) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        /* The name and artist under the cover: their lines and the gaps. */
        val textHeight = with(LocalDensity.current) {
            (titleSize * LINE_HEIGHT).toDp() +
                (artist?.let { (titleSize * artistScale * LINE_HEIGHT).toDp() + 3.dp } ?: 0.dp)
        } + gap
        val coverSize = minOf(maxWidth, maxHeight - textHeight)
        if (showCover && coverSize > SIDE_COVER) {
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                Cover(cover, coverSize)
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    OneLine(title, titleSize)
                    artist?.let { OneLine(it, titleSize * artistScale, LaserColors.Muted) }
                }
            }
        } else {
            Album(title, artist, cover, showCover, ui, coverSize = SIDE_COVER, titleSize = titleSize)
        }
    }
}

/* The cover beside the album's name, when there is no room for a larger one. */
private val SIDE_COVER = 104.dp
/* A line of text, as a multiple of its font size. */
private const val LINE_HEIGHT = 1.35f

/** "12 pistes" and "58 min", above the list. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ListHeader(ui: PlayerUi, size: TextUnit) {
    if (ui.playlist.isEmpty()) return
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween) {
        OneLine(pluralStringResource(R.plurals.tracks_count, ui.playlist.size, ui.playlist.size),
                size, LaserColors.Muted)
        totalDuration(ui)?.let { OneLine(it, size, LaserColors.Muted) }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(LaserColors.Track))
}

private fun totalDuration(ui: PlayerUi): String? =
    ui.playlist.sumOf { it.durationMs }.takeIf { it > 0 && ui.playlist.all { t -> t.durationMs > 0 } }
        ?.let(::formatDuration)

/** The track playing, and its number. */
@Composable
private fun NowPlaying(ui: PlayerUi, size: TextUnit) {
    if (ui.error) {
        OneLine(stringResource(R.string.player_error), size, LaserColors.Muted)
        return
    }
    if (ui.current < 0) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        OneLine(itemTitle(ui, ui.current), size, bold = true, modifier = Modifier.weight(1f))
        OneLine(stringResource(R.string.track_n, ui.current + 1), size * 0.85f, LaserColors.Muted)
    }
}

@Composable
private fun itemTitle(ui: PlayerUi, index: Int): String =
    ui.playlist.getOrNull(index)?.title ?: stringResource(R.string.track_n, index + 1)

/** Position in the track playing. */
@Composable
private fun ItemProgress(ui: PlayerUi, controller: PlayerController, size: TextUnit) {
    val start = if (ui.byChapter) ui.chapters.getOrNull(ui.current)?.startMs ?: 0L else 0L
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SeekBar(ui.itemTimeMs, ui.itemLengthMs, onSeek = { controller.seekTo(start + it) },
                modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            OneLine(formatTime(ui.itemTimeMs), size, LaserColors.Muted)
            if (ui.itemLengthMs > 0) OneLine(formatTime(ui.itemLengthMs), size, LaserColors.Muted)
        }
    }
}

@Composable
private fun Transport(ui: PlayerUi, controller: PlayerController, small: Dp, big: Dp,
                      playFocus: FocusRequester) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically) {
        /* Previous: faded at the very start of the first track, where it has
         * nothing to do; further in, it goes back to the start. Next: faded
         * on the last track. */
        CircleButton(R.drawable.ic_skip_previous, stringResource(R.string.previous_track), small,
                     onClick = controller::previousItem,
                     enabled = ui.current > 0 || ui.itemTimeMs > RESTART_THRESHOLD_MS)
        CircleButton(if (ui.playing) R.drawable.ic_pause else R.drawable.ic_play,
                     stringResource(if (ui.playing) R.string.player_pause else R.string.player_play),
                     big, onClick = controller::togglePause, primary = true,
                     modifier = Modifier.focusRequester(playFocus))
        CircleButton(R.drawable.ic_skip_next, stringResource(R.string.next_track), small,
                     onClick = controller::nextItem,
                     enabled = ui.current >= 0 && ui.current + 1 < ui.playlist.size)
    }
}

/** The tracks, the one playing marked; choosing one plays it. */
@Composable
private fun TrackList(ui: PlayerUi, controller: PlayerController, rowHeight: Dp,
                      fontSize: TextUnit, modifier: Modifier) {
    val list = rememberLazyListState()
    /* Keeps the track playing in view, a couple of rows from the top. */
    LaunchedEffect(ui.current) {
        if (ui.current >= 0) list.animateScrollToItem((ui.current - 2).coerceAtLeast(0))
    }
    val label = stringResource(R.string.tracks_list)
    LazyColumn(modifier.semantics { contentDescription = label }, state = list,
               verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(ui.playlist.size) { i ->
            TrackRow(i, itemTitle(ui, i), ui.playlist[i].durationMs, i == ui.current,
                     ui.playing, rowHeight, fontSize) { controller.playItem(i) }
        }
    }
}

@Composable
private fun TrackRow(index: Int, title: String, durationMs: Long, current: Boolean,
                     playing: Boolean, height: Dp, fontSize: TextUnit, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val text = if (focused) LaserColors.Background else LaserColors.Text
    val muted = if (focused) LaserColors.CaseFrame else LaserColors.Muted
    Row(
        Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(8.dp))
            .background(when {
                focused -> LaserColors.Text
                current -> LaserColors.AccentSurface
                else -> Color.Transparent
            })
            .semantics { selected = current }
            .clickable(interactionSource = interaction, indication = null,
                       role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(fontSize.value.dp * 1.5f), contentAlignment = Alignment.CenterStart) {
            if (current) PlayingIcon(fontSize.value.dp * 1.05f, animated = playing)
            else OneLine("${index + 1}", fontSize, muted)
        }
        OneLine(title, fontSize, text, bold = current || focused, modifier = Modifier.weight(1f))
        if (durationMs > 0) OneLine(formatTime(durationMs), fontSize * 0.88f, muted)
    }
}
