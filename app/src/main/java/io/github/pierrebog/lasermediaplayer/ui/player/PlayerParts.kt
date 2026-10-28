package io.github.pierrebog.lasermediaplayer.ui.player

import android.annotation.SuppressLint
import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.StartOffsetType
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import io.github.pierrebog.lasermediaplayer.R
import io.github.pierrebog.lasermediaplayer.ui.rememberCover
import io.github.pierrebog.lasermediaplayer.ui.theme.LaserColors
import io.github.pierrebog.lasermediaplayer.ui.theme.LaserType
import java.io.File

/* Pieces shared by the audio and video players - "Lecture audio" and
 * "Lecture vidéo" in the C′ mockups. */

/** Back button, the app's name and, on a TV, the drive. */
@Composable
fun PlayerHeader(onBack: () -> Unit, driveLabel: String?, tv: Boolean,
                 modifier: Modifier = Modifier) {
    Row(modifier.height(if (tv) 56.dp else 44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (tv) 24.dp else 12.dp)) {
        CircleButton(R.drawable.ic_chevron_left, stringResource(R.string.back),
                     size = if (tv) 56.dp else 44.dp, onClick = onBack, outlined = true)
        Text(stringResource(R.string.app_name), maxLines = 1,
             style = LaserType.Brand.copy(fontSize = LaserType.Brand.fontSize * if (tv) 1.4f else 0.95f))
        if (tv && driveLabel != null)
            Text(driveLabel, style = LaserType.Small.copy(fontSize = LaserType.Small.fontSize * 1.3f),
                 maxLines = 1)
    }
}

/**
 * A round button. [primary]: the play/pause button, filled light. The focus
 * (remote) is a light ring, the mockups' "focus télécommande". A disabled
 * button is faded, and can be neither tapped nor focused.
 */
@Composable
fun CircleButton(
    @DrawableRes icon: Int,
    description: String,
    size: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    outlined: Boolean = false,
    translucent: Boolean = false,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val background = when {
        primary -> LaserColors.Text
        outlined -> Color.Transparent
        translucent -> LaserColors.Surface.copy(alpha = 0.85f)
        else -> LaserColors.Surface
    }
    Box(
        modifier
            .size(size)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clip(CircleShape)
            .background(background)
            .border(if (focused) 3.dp else 1.dp,
                    when {
                        focused -> if (primary) LaserColors.Accent else LaserColors.Text
                        primary -> LaserColors.Text
                        else -> LaserColors.Border
                    }, CircleShape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled,
                       onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null,
             tint = if (primary) LaserColors.Background else LaserColors.Text,
             modifier = Modifier.size(size * 0.42f))
    }
}

private const val DISABLED_ALPHA = 0.35f

/** A labelled button: audio track, subtitles, disc menu, filling the screen. */
@Composable
fun PillButton(
    @DrawableRes icon: Int,
    text: String,
    description: String,
    height: Dp,
    fontSize: TextUnit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    translucent: Boolean = false,
    /** A switch's state: on, it is outlined in the accent. Null for a
     * plain button. */
    selected: Boolean? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)
    val on = selected == true
    Row(
        modifier
            .height(height)
            .clip(shape)
            .background(when {
                focused -> LaserColors.Text
                translucent -> LaserColors.Surface.copy(alpha = 0.85f)
                else -> Color.Transparent
            })
            .border(1.dp, when {
                focused -> LaserColors.Text
                on -> LaserColors.Accent
                else -> LaserColors.Border
            }, shape)
            .clickable(interactionSource = interaction, indication = null,
                       onClickLabel = description, onClick = onClick)
            .semantics {
                contentDescription = description
                if (selected != null) this.selected = selected
            }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        val color = if (focused) LaserColors.Background else LaserColors.Text
        Icon(painterResource(icon), contentDescription = null,
             tint = if (on && !focused) LaserColors.Accent else color,
             modifier = Modifier.size(20.dp))
        Text(text, style = LaserType.Body.copy(fontSize = fontSize, color = color),
             maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Progress bar, with a tick at each chapter boundary. Tapping seeks; dragging
 * moves the bar and seeks once, where the finger is lifted - one seek, not
 * one per step, each showing its picture when paused. With the remote, left
 * and right seek by [stepMs] while it has the focus.
 */
@Composable
fun SeekBar(
    timeMs: Long,
    lengthMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    ticks: List<Long> = emptyList(),
    stepMs: Long = 10_000L,
    trackColor: Color = LaserColors.Track,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var width by remember { mutableIntStateOf(0) }
    /* Where a drag holds the bar, until it ends. */
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val fraction = dragFraction
        ?: if (lengthMs > 0) (timeMs.toFloat() / lengthMs).coerceIn(0f, 1f) else 0f
    /* Read when a gesture ends, not when it starts: a change of length must
     * not restart the gesture detectors, which would drop a drag under way. */
    val currentLength by rememberUpdatedState(lengthMs)
    val currentOnSeek by rememberUpdatedState(onSeek)
    val fractionAt = { x: Float -> if (width > 0) (x / width).coerceIn(0f, 1f) else 0f }
    val seekTo = { f: Float ->
        if (currentLength > 0 && width > 0) currentOnSeek((f * currentLength).toLong())
    }
    val description = stringResource(R.string.seek_bar)
    BoxWithConstraints(
        modifier
            .height(24.dp)
            .onSizeChanged { width = it.width }
            .semantics { contentDescription = description }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || lengthMs <= 0) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> { onSeek((timeMs - stepMs).coerceAtLeast(0)); true }
                    Key.DirectionRight -> { onSeek((timeMs + stepMs).coerceAtMost(lengthMs)); true }
                    else -> false
                }
            }
            .focusable(interactionSource = interaction)
            .pointerInput(Unit) { detectTapGestures { seekTo(fractionAt(it.x)) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragFraction = fractionAt(it.x) },
                    onDragEnd = { dragFraction?.let(seekTo); dragFraction = null },
                    onDragCancel = { dragFraction = null },
                ) { change, _ -> dragFraction = fractionAt(change.position.x) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        /* Read here: the inner boxes' scope hides BoxWithConstraints'. */
        val barWidth = maxWidth
        val barHeight = if (focused) 8.dp else 4.dp
        Box(Modifier.fillMaxWidth().height(barHeight).background(trackColor)) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(LaserColors.Accent))
            if (lengthMs > 0) for (tick in ticks) {
                val at = tick.toFloat() / lengthMs
                if (at <= 0f || at >= 1f) continue
                Box(Modifier.offset(x = barWidth * at).width(2.dp).fillMaxHeight()
                    .background(LaserColors.Background.copy(alpha = 0.8f)))
            }
        }
        if (focused)
            Box(Modifier.offset(x = barWidth * fraction - 8.dp).size(16.dp).clip(CircleShape)
                .background(LaserColors.Text))
    }
}

/**
 * The "now playing" bars of a playlist row: moving while the track plays,
 * still while it is paused - and when the system turns animations off.
 */
@Composable
fun PlayingIcon(size: Dp, animated: Boolean = false) {
    val description = stringResource(R.string.now_playing)
    if (!animated || !animationsEnabled()) {
        Icon(painterResource(R.drawable.ic_playing), contentDescription = description,
             tint = LaserColors.Accent, modifier = Modifier.size(size))
        return
    }
    /* The mockups' equaliser: three bars between 30 and 100 % of their
     * height, each at its own pace. Period, and how far into it a bar starts. */
    val transition = rememberInfiniteTransition(label = "equaliser")
    val heights = listOf(900 to 0, 700 to 350, 1150 to 600).map { (period, offset) ->
        transition.animateFloat(
            initialValue = 0.3f, targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(period / 2, easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)),
                RepeatMode.Reverse, StartOffset(offset, StartOffsetType.FastForward)),
            label = "bar",
        )
    }
    val accent = LaserColors.Accent
    Canvas(Modifier.size(size).semantics { contentDescription = description }) {
        val unit = this.size.width / 24f
        heights.forEachIndexed { i, height ->
            val barHeight = 16f * unit * height.value
            drawRoundRect(
                accent,
                topLeft = Offset((4f + 6.25f * i) * unit, 20f * unit - barHeight),
                size = Size(3.5f * unit, barHeight),
                cornerRadius = CornerRadius(unit),
            )
        }
    }
}

/** False when the system's animator duration scale is 0 (animations off). */
@Composable
fun animationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver,
                                 Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
}

/** The disc's cover, or the placeholder while there is none. */
@Composable
fun Cover(file: File?, size: Dp, modifier: Modifier = Modifier) {
    val image by rememberCover(file)
    val bitmap = image
    if (bitmap == null) {
        CoverPlaceholder(size, modifier)
        return
    }
    Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop,
          modifier = modifier.size(size).clip(RoundedCornerShape(6.dp))
              .border(1.dp, LaserColors.Border, RoundedCornerShape(6.dp)))
}

/** The cover until the disc's own is looked up: the case, with a disc. */
@Composable
fun CoverPlaceholder(size: Dp, modifier: Modifier = Modifier) {
    val frame = size * 0.03f + 4.dp
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(6.dp))
            .background(LaserColors.CaseFrame)
            .border(1.dp, LaserColors.Border, RoundedCornerShape(6.dp))
            .padding(frame),
    ) {
        Box(Modifier.size(size - frame * 2).clip(RoundedCornerShape(2.dp))
                .background(LaserColors.AccentSurface),
            contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_disc), contentDescription = null,
                 tint = LaserColors.Muted, modifier = Modifier.size(size * 0.4f))
        }
    }
}

/** A line of text that never wraps. */
/* The colour, the argument most callers give, comes before the modifier, so
 * that it stays positional. */
@SuppressLint("ModifierParameter")
@Composable
fun OneLine(text: String, size: TextUnit, color: Color = LaserColors.Text,
            bold: Boolean = false, modifier: Modifier = Modifier, tabular: Boolean = false) {
    Text(text, modifier = modifier, maxLines = 1, overflow = TextOverflow.Ellipsis,
         style = LaserType.Body.copy(fontSize = size, color = color,
                                     fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
                                     /* digits of one width: a time keeps its width */
                                     fontFeatureSettings = if (tabular) "tnum" else null))
}
