package io.github.pierrebog.lasermediaplayer.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import io.github.pierrebog.lasermediaplayer.disc.DiscInfo
import io.github.pierrebog.lasermediaplayer.disc.DiscKind
import io.github.pierrebog.lasermediaplayer.ui.theme.BodyFamily
import io.github.pierrebog.lasermediaplayer.ui.theme.DisplayFamily

/*
 * The replacement poster, when no poster or cover was found: "D6 · Jaquette
 * et reflet" of the mockups, dark theme. A sleeve with the disc's metal and a
 * rainbow reflection, a band in the colour of the kind of disc at the top,
 * the title at the bottom.
 *
 * Two formats, each drawn in the mockups' units and scaled to the size given:
 * a 2:3 poster (260 x 390) for a Blu-ray, a DVD-Video or a DVD-Audio, and a
 * square jewel case with its spine (190 x 190) for an audio CD or a Video CD.
 */

/** The band's colour and the accent line's, per kind of disc. */
private fun bandColors(kind: DiscKind): Pair<Color, Color> = when (kind) {
    DiscKind.BLURAY -> Color(0xFF1F5FBF) to Color(0xFF8FB4FF)
    DiscKind.DVD_VIDEO -> Color(0xFFA8382E) to Color(0xFFE5554A)
    DiscKind.DVD_AUDIO -> Color(0xFF6A4BB0) to Color(0xFFC3B0FF)
    DiscKind.VIDEO_CD -> Color(0xFF1F7A72) to Color(0xFF7FD1C4)
    DiscKind.CD_AUDIO -> Color(0xFFA8741E) to Color(0xFFF0C478)
}

/** The jewel case's square, for discs sold in one; else the 2:3 poster. */
fun generatedCoverIsSquare(kind: DiscKind): Boolean =
    kind == DiscKind.CD_AUDIO || kind == DiscKind.VIDEO_CD

private val Sleeve = Color(0xFF1A1F26)
private val Ink = Color(0xFFF2EEE6)
private val BandInk = Color(0xFFECEFF2)

/* The disc's metal, from the mockups' conic gradient, from 200 degrees. */
private val Metal = listOf(
    Color(0xFF4A5464), Color(0xFF222A35), Color(0xFF5A6474), Color(0xFF1E252F),
    Color(0xFF48515E), Color(0xFF222A35), Color(0xFF4A5464),
)

/* The rainbow reflection, from 290 degrees: two arcs across the disc. */
private fun reflection(alpha: Float): Array<Pair<Float, Color>> {
    val red = Color(0xFFFF7A7A).copy(alpha = alpha)
    val yellow = Color(0xFFFFD166).copy(alpha = alpha)
    val green = Color(0xFF6EE7B7).copy(alpha = alpha)
    val blue = Color(0xFF7B93FF).copy(alpha = alpha)
    val purple = Color(0xFFC084FC).copy(alpha = alpha * 0.8f)
    val none = Color.Transparent
    fun at(deg: Float) = deg / 360f
    return arrayOf(
        at(0f) to none, at(11f) to red, at(22f) to yellow, at(33f) to green,
        at(44f) to blue, at(55f) to purple, at(70f) to none,
        at(180f) to none, at(191f) to red, at(202f) to yellow, at(213f) to green,
        at(224f) to blue, at(235f) to purple, at(250f) to none, at(360f) to none,
    )
}

/**
 * The disc, centred at [center] with [radius], in the mockups' units times
 * [unit] pixels: the metal, the reflection, its edge, the hub and the hole.
 */
private fun DrawScope.disc(center: Offset, radius: Float, hub: Float, ring: Float,
                           hole: Float, unit: Float, reflectionAlpha: Float) {
    val hairline = 1f * unit
    /* A CSS conic gradient starts at 12 o'clock, Compose's sweep at 3. */
    rotate(200f - 90f, center) {
        drawCircle(Brush.sweepGradient(Metal, center), radius, center, alpha = 0.55f)
    }
    rotate(290f - 90f, center) {
        drawCircle(Brush.sweepGradient(*reflection(reflectionAlpha), center = center),
                   radius, center)
    }
    drawCircle(Color.White.copy(alpha = 0.22f), radius, center, style = Stroke(hairline))
    drawCircle(Sleeve, hub, center)
    drawCircle(Color.White.copy(alpha = 0.2f), hub, center, style = Stroke(hairline))
    drawCircle(Color.White.copy(alpha = 0.14f), ring, center, style = Stroke(hairline))
    drawCircle(Color(0xFF05070A).copy(alpha = 0.6f), hole, center)
}

/**
 * The replacement poster of [disc], [width] wide: 2:3, or square for a disc
 * sold in a jewel case.
 */
@Composable
fun GeneratedCover(disc: DiscInfo, width: Dp, modifier: Modifier = Modifier) {
    val kind = disc.kind
    val square = generatedCoverIsSquare(kind)
    val title = discTitle(disc)
    val label = kindLabel(kind).uppercase()
    /* An album's artist; a Video CD's length, when known. */
    val subtitle = when (kind) {
        DiscKind.CD_AUDIO, DiscKind.DVD_AUDIO -> disc.album?.artist?.takeIf { it.isNotBlank() }
        DiscKind.VIDEO_CD -> disc.mainLengthMs.takeIf { it > 0 }?.let { formatDuration(it) }
        else -> null
    }
    val (band, accent) = bandColors(kind)

    /* The mockups' unit, in dp: the poster is 260 wide, the case 190. */
    val u = width / if (square) 190f else 260f
    val density = LocalDensity.current
    fun sp(units: Float): TextUnit = with(density) { (u * units).toSp() }

    Box(
        modifier
            .size(width, if (square) width else width * 1.5f)
            .clip(RoundedCornerShape(3.dp))
            .background(Sleeve)
            .semantics { contentDescription = title },
    ) {
        if (square) {
            Canvas(Modifier.fillMaxSize()) {
                val unit = size.width / 190f
                /* The spine: ridges every 5 units, a shadow at its edge. */
                var y = 0f
                while (y < size.height) {
                    drawRect(Color(0xFFC9CDD2), Offset(0f, y), Size(14f * unit, 2f * unit))
                    drawRect(Color(0xFFA9AEB5), Offset(0f, y + 2f * unit), Size(14f * unit, 3f * unit))
                    y += 5f * unit
                }
                drawRect(Color.Black.copy(alpha = 0.25f), Offset(14f * unit, 0f),
                         Size(1f * unit, size.height))
                /* The disc, partly out of the case at the top right. */
                clipRect(left = 14f * unit) {
                    disc(Offset(155f * unit, 68f * unit), 80f * unit, 29f * unit, 18f * unit,
                         8f * unit, unit, reflectionAlpha = 0.315f)
                }
            }
        } else {
            Canvas(Modifier.fillMaxSize()) {
                val unit = size.width / 260f
                disc(Offset(192f * unit, 156f * unit), 135f * unit, 49f * unit, 30f * unit,
                     14f * unit, unit, reflectionAlpha = 0.35f)
            }
        }

        /* The band, in the kind's colour. */
        val inset = if (square) u * 14f else 0.dp
        Box(
            Modifier
                .padding(start = inset)
                .fillMaxWidth()
                .height(u * if (square) 16f else 33f)
                .background(band),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, color = BandInk, fontFamily = BodyFamily, fontWeight = FontWeight.SemiBold,
                 fontSize = sp(if (square) 9f else 11f), letterSpacing = 0.22.em,
                 maxLines = 1, overflow = TextOverflow.Clip, textAlign = TextAlign.Center)
        }

        /* The title, under an accent line, at the bottom. */
        val side = u * if (square) 13f else 19f
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = if (square) u * 27f else side, end = side, bottom = side),
        ) {
            Box(Modifier
                .width(u * if (square) 18f else 24f)
                .height(u * 2f)
                .background(accent))
            Text(title, color = Ink, fontFamily = DisplayFamily,
                 fontSize = sp(if (square) 25f else 36f), lineHeight = sp(if (square) 25f else 36f),
                 maxLines = if (square) 3 else 4, overflow = TextOverflow.Ellipsis,
                 modifier = Modifier.padding(top = u * if (square) 6f else 10f))
            if (subtitle != null)
                Text(subtitle, color = Ink.copy(alpha = 0.75f), fontFamily = BodyFamily,
                     fontSize = sp(if (square) 8.9f else 11.3f), maxLines = 1,
                     overflow = TextOverflow.Ellipsis,
                     modifier = Modifier.padding(top = u * if (square) 4f else 5f))
        }

        /* The jewel case's gloss. */
        if (square) Box(
            Modifier.fillMaxSize().background(
                Brush.linearGradient(
                    0f to Color.White.copy(alpha = 0.22f),
                    0.35f to Color.White.copy(alpha = 0f),
                    0.7f to Color.White.copy(alpha = 0f),
                    1f to Color.White.copy(alpha = 0.08f),
                    start = Offset.Zero, end = Offset.Infinite,
                )
            )
        )
    }
}
