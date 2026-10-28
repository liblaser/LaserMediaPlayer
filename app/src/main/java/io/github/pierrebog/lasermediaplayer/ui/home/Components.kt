package io.github.pierrebog.lasermediaplayer.ui.home

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pierrebog.lasermediaplayer.R
import io.github.pierrebog.lasermediaplayer.disc.DiscInfo
import io.github.pierrebog.lasermediaplayer.disc.DiscKind
import io.github.pierrebog.lasermediaplayer.ui.rememberCover
import io.github.pierrebog.lasermediaplayer.ui.player.animationsEnabled
import io.github.pierrebog.lasermediaplayer.ui.theme.BodyFamily
import io.github.pierrebog.lasermediaplayer.ui.theme.DisplayFamily
import io.github.pierrebog.lasermediaplayer.ui.theme.FocusColors
import io.github.pierrebog.lasermediaplayer.ui.theme.keyNavigation
import io.github.pierrebog.lasermediaplayer.ui.theme.LaserColors

/*
 * Building blocks of the mockups. Every clickable shows the remote's focus
 * by turning to the text's colour, its content to the background's (see
 * FocusColors), which is all the remote needs: D-pad focus moves between
 * clickables on its own.
 */

/** Sizes of one form factor, from the phone and TV mockups. */
data class Metrics(
    val buttonHeight: Dp,
    val trackButtonHeight: Dp,
    val textSize: TextUnit,
    val iconSize: Dp,
)

val PhoneMetrics = Metrics(52.dp, 48.dp, 17.sp, 22.dp)
val TvMetrics = Metrics(60.dp, 52.dp, 22.sp, 24.dp)

@Composable
fun PlayButton(
    text: String,
    primary: Boolean,
    metrics: Metrics,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int = R.drawable.ic_play,
    trailingChevron: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)
    /* With the remote, only the focus fills a button: it starts on the
     * primary one. */
    val filled = focused || (primary && !keyNavigation())
    val content = if (filled) LaserColors.Background else LaserColors.Text
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(metrics.buttonHeight)
            .clip(shape)
            .background(if (filled) LaserColors.Text else LaserColors.Surface)
            .border(1.dp, if (filled) Color.Transparent else LaserColors.Border, shape)
            .clickable(interactionSource = interaction, indication = null,
                       role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = content,
             modifier = Modifier.size(metrics.iconSize))
        Text(text, color = content, fontFamily = BodyFamily, fontSize = metrics.textSize,
             fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal,
             modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (trailingChevron)
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null,
                 tint = content, modifier = Modifier.size(metrics.iconSize))
    }
}

/** Audio or subtitle choice, opening the track sheet. */
@Composable
fun TrackButton(
    @DrawableRes icon: Int,
    text: String,
    contentDescription: String,
    metrics: Metrics,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = modifier
            .height(metrics.trackButtonHeight)
            .clip(shape)
            .background(if (focused) LaserColors.Text else Color.Transparent)
            .border(1.dp, if (focused) Color.Transparent else LaserColors.Border, shape)
            .clickable(interactionSource = interaction, indication = null,
                       role = Role.Button, onClickLabel = contentDescription, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FocusColors(focused) {
            Icon(painterResource(icon), contentDescription = null, tint = LaserColors.Muted,
                 modifier = Modifier.size(20.dp))
            Text(text, color = LaserColors.Text, fontFamily = BodyFamily,
                 fontSize = metrics.textSize * 0.88f, maxLines = 1,
                 overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(painterResource(R.drawable.ic_chevron_down), contentDescription = null,
                 tint = LaserColors.Muted, modifier = Modifier.size(16.dp))
        }
    }
}

/** Round outlined icon button of the header. */
@Composable
fun CircleIconButton(
    @DrawableRes icon: Int,
    contentDescription: String,
    size: Dp,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (focused) LaserColors.Text else Color.Transparent)
            .border(1.dp, if (focused) Color.Transparent else LaserColors.CaseFrame, CircleShape)
            .clickable(interactionSource = interaction, indication = null,
                       role = Role.Button, onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = contentDescription,
             tint = if (focused) LaserColors.Background else LaserColors.Text,
             modifier = Modifier.size(size * 0.5f))
    }
}

/**
 * The width to give [DiscCase] for it to be [height] tall: what it shows, in
 * its 8 dp frame - square, or 2:3 - decides. Changes, with what it shows, as
 * the lookup ends.
 */
fun discCaseWidth(disc: DiscInfo, height: Dp): Dp {
    val square = if (disc.lookupDone && disc.cover == null)
        generatedCoverIsSquare(disc.kind)
    else
        disc.kind == DiscKind.CD_AUDIO || disc.kind == DiscKind.DVD_AUDIO
    val inner = (height - 16.dp).coerceAtLeast(0.dp)
    return (if (square) inner else inner / 1.5f) + 16.dp
}

/**
 * The disc in its case. A Blu-ray or DVD-Video case is 2:3; a CD or a
 * DVD-Audio, an album, is square.
 *
 * The cover is the album's (CD) or the film's poster (DVD, Blu-ray), once
 * looked up; until then, a placeholder made of the kind and the title. When
 * the lookup is over and found no picture - a Video CD, which is not looked
 * up, a disc not found, or found without one - the replacement poster, whose
 * shape is its own: a jewel case for a CD or a Video CD, a poster for the
 * rest.
 */
@Composable
fun DiscCase(disc: DiscInfo, width: Dp, modifier: Modifier = Modifier) {
    val square = disc.kind == DiscKind.CD_AUDIO || disc.kind == DiscKind.DVD_AUDIO
    val inner = width - 16.dp
    val innerHeight = if (square) inner else inner * 1.5f
    Box(
        modifier = modifier
            .shadow(20.dp, RoundedCornerShape(6.dp))
            .clip(RoundedCornerShape(6.dp))
            .background(LaserColors.CaseFrame)
            .border(1.dp, LaserColors.CaseEdge, RoundedCornerShape(6.dp))
            .padding(8.dp),
    ) {
        /* The album's cover or the film's poster, once looked up, in the
         * case's frame. */
        val cover by rememberCover(disc.cover)
        val image = cover
        /* A picture known but still loading is not "no picture". */
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Crop,
                  modifier = Modifier.size(inner, innerHeight).clip(RoundedCornerShape(2.dp)))
        } else if (disc.lookupDone && disc.cover == null) {
            GeneratedCover(disc, inner)
        } else Column(
            modifier = Modifier
                .width(inner)
                .height(innerHeight)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(0xFF1F4E5A))
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(kindLabel(disc.kind).uppercase(), color = Color(0xFFF6EBDD),
                 fontFamily = BodyFamily, fontSize = 13.sp, letterSpacing = 1.sp)
            Icon(painterResource(R.drawable.ic_disc), contentDescription = null,
                 tint = Color(0xFFE8743B), modifier = Modifier
                     .size(inner * 0.5f)
                     .align(Alignment.CenterHorizontally))
            Text(discTitle(disc).uppercase(),
                 color = Color(0xFFF6EBDD), fontFamily = BodyFamily,
                 fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 20.sp,
                 maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Progress of a resumable disc: [label] at the left and the title's length
 * at the right, then the bar. */
@Composable
fun ResumeProgress(timeMs: Long, lengthMs: Long, label: String, textSize: TextUnit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Bottom) {
            Text(label, color = LaserColors.Muted, fontFamily = BodyFamily, fontSize = textSize,
                 maxLines = 2, modifier = Modifier.weight(1f))
            if (lengthMs > 0)
                Text(formatDuration(lengthMs), color = LaserColors.Muted, fontFamily = BodyFamily,
                     fontSize = textSize, maxLines = 1)
        }
        Box(Modifier.fillMaxWidth().height(3.dp).background(LaserColors.Track)) {
            if (lengthMs > 0)
                Box(Modifier
                    .fillMaxWidth((timeMs.toFloat() / lengthMs).coerceIn(0f, 1f))
                    .height(3.dp)
                    .background(LaserColors.Accent))
        }
    }
}

/** A message screen: no drive, no disc, identifying... */
@Composable
fun StateMessage(
    title: String,
    hint: String,
    action: String? = null,
    onAction: () -> Unit = {},
    busy: Boolean = false,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (busy) DiscSpinner(112.dp)
        else Icon(painterResource(R.drawable.ic_drive), contentDescription = null,
                  tint = LaserColors.Muted, modifier = Modifier.size(56.dp))
        Text(title, color = LaserColors.Text, fontFamily = DisplayFamily, fontSize = 30.sp)
        Text(hint, color = LaserColors.Muted, fontFamily = BodyFamily, fontSize = 16.sp)
        if (action != null) {
            Spacer(Modifier.height(8.dp))
            PlayButton(action, primary = false, metrics = PhoneMetrics, onClick = onAction,
                       icon = R.drawable.ic_refresh, modifier = Modifier.width(280.dp))
        }
    }
}

/**
 * The disc being read, from the mockups: a ring with an accent arc, turning
 * once every 1.4 s - still when the system turns animations off.
 */
@Composable
fun DiscSpinner(size: Dp) {
    val angle = if (animationsEnabled()) {
        val transition = rememberInfiniteTransition(label = "disc")
        val value by transition.animateFloat(
            initialValue = 0f, targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
            label = "angle",
        )
        value
    } else 0f
    val ring = LaserColors.Track
    val arc = LaserColors.Accent
    val hub = LaserColors.CaseEdge
    Canvas(Modifier.size(size).graphicsLayer { rotationZ = angle }) {
        val unit = this.size.width / 24f
        val stroke = Stroke(width = 0.9f * unit, cap = StrokeCap.Round)
        drawCircle(ring, radius = 10f * unit, style = stroke)
        drawArc(arc, startAngle = -90f, sweepAngle = 90f, useCenter = false,
                topLeft = Offset(2f * unit, 2f * unit), size = Size(20f * unit, 20f * unit),
                style = stroke)
        drawCircle(hub, radius = 2.5f * unit, style = stroke)
    }
}
