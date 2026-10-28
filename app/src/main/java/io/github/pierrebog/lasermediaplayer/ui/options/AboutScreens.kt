package io.github.pierrebog.lasermediaplayer.ui.options

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import io.github.pierrebog.lasermediaplayer.BuildConfig
import io.github.pierrebog.lasermediaplayer.R
import io.github.pierrebog.lasermediaplayer.ui.home.CircleIconButton
import io.github.pierrebog.lasermediaplayer.ui.theme.BodyFamily
import io.github.pierrebog.lasermediaplayer.ui.theme.DisplayFamily
import io.github.pierrebog.lasermediaplayer.ui.theme.FocusColors
import io.github.pierrebog.lasermediaplayer.ui.theme.LaserColors

/** The pages the About tab opens, over the options. */
enum class AboutPage { LICENCE, LIBRARIES }

/* The logos' plate: light in both themes, as the logos are drawn for it. */
private val LogoPlate = Color(0xFFECEFF2)

/**
 * About: the app, its licence, the libraries it is made of, and the
 * metadata services' credit. A section of the phone's page, or a tab.
 */
@Composable
internal fun AboutTab(metrics: OptionsMetrics, onOpen: (AboutPage) -> Unit) {
    val tabs = LocalTabs.current
    val libraries: @Composable () -> Unit = {
        Rows(
            { LibraryRow("libvlc", Credits.LIBVLC_VERSION, Licence.LGPL21_PLUS, metrics) },
            { LibraryRow("liblaser", Credits.LIBLASER_VERSION, Licence.LGPL21_PLUS, metrics) },
            { LibraryRow("libusb", Credits.LIBUSB_VERSION, Licence.LGPL21_PLUS, metrics) },
            { LibraryRow("OpenJDK", Credits.OPENJDK_VERSION, Licence.GPL2_CLASSPATH, metrics) },
            { ValueRow(stringResource(R.string.about_other_libraries), "", metrics) {
                onOpen(AboutPage.LIBRARIES) } },
        )
    }
    val logos: @Composable RowScope.() -> Unit = {
        LogoPlate(R.drawable.logo_tmdb, "TMDB", if (metrics.big) 18.dp else 15.dp, metrics,
                  if (tabs) Modifier else Modifier.weight(1f))
        LogoPlate(R.drawable.logo_musicbrainz, "MusicBrainz", if (metrics.big) 22.dp else 19.dp,
                  metrics, if (tabs) Modifier else Modifier.weight(1f))
    }
    val disclaimer = stringResource(R.string.about_disclaimer)

    if (tabs) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            AppInfo(metrics, Modifier.weight(1f))
            LicenceButton(metrics, fill = false) { onOpen(AboutPage.LICENCE) }
        }
        Section(stringResource(R.string.about_libraries), metrics) { libraries() }
        Section(stringResource(R.string.about_metadata), metrics) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { logos() }
            Text(disclaimer, color = LaserColors.Muted, fontFamily = BodyFamily,
                 fontSize = metrics.hint, lineHeight = metrics.hint * 1.45f,
                 modifier = Modifier.padding(horizontal = 4.dp))
        }
    } else {
        Section(stringResource(R.string.options_about), metrics) {
            AppInfo(metrics, Modifier.padding(horizontal = 4.dp))
            LicenceButton(metrics, fill = true) { onOpen(AboutPage.LICENCE) }
            libraries()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) { logos() }
            Text(disclaimer, color = LaserColors.Muted, fontFamily = BodyFamily, fontSize = 13.sp,
                 lineHeight = 19.sp, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

/** Name, version and licence; the author is on the licence's page. */
@Composable
private fun AppInfo(metrics: OptionsMetrics, modifier: Modifier) {
    val big = metrics.big
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (big) 4.dp else 3.dp)) {
        Text(stringResource(R.string.app_name), color = LaserColors.Text, fontFamily = DisplayFamily,
             fontStyle = FontStyle.Italic, fontSize = if (big) 36.sp else 26.sp,
             lineHeight = if (big) 38.sp else 29.sp)
        Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME,
                            licenceLabel(Licence.GPL2_PLUS)),
             color = LaserColors.Muted, fontFamily = BodyFamily, fontSize = if (big) 18.sp else 14.sp)
    }
}

/** Opens the licence's text: a button beside the app's name on a TV, a
 * row of its own on the phone's page. */
@Composable
private fun LicenceButton(metrics: OptionsMetrics, fill: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(if (fill) 12.dp else 10.dp)
    Row(
        modifier = (if (fill) Modifier.fillMaxWidth() else Modifier)
            .height(56.dp)
            .clip(shape)
            .background(when {
                focused -> LaserColors.Text
                fill -> LaserColors.Card
                else -> LaserColors.Surface
            })
            .border(1.dp, when {
                focused -> Color.Transparent
                fill -> LaserColors.Track
                else -> LaserColors.Border
            }, shape)
            .clickable(interactionSource = interaction, indication = null,
                       role = Role.Button, onClick = onClick)
            .padding(horizontal = if (fill) 16.dp else 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (fill) 10.dp else 12.dp),
    ) {
        FocusColors(focused) {
            Icon(painterResource(R.drawable.ic_licence), contentDescription = null,
                 tint = if (fill) LaserColors.Muted else LaserColors.Text,
                 modifier = Modifier.size(if (fill) 20.dp else 22.dp))
            Text(stringResource(R.string.about_licence), color = LaserColors.Text, fontFamily = BodyFamily,
                 fontSize = if (metrics.big) 19.sp else 16.sp,
                 modifier = if (fill) Modifier.weight(1f) else Modifier)
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null,
                 tint = LaserColors.Muted, modifier = Modifier.size(metrics.chevron))
        }
    }
}

/** A library with its version and its licence. */
@Composable
private fun LibraryRow(name: String, version: String, licence: Licence, metrics: OptionsMetrics) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = if (metrics.big) 58.dp else 60.dp)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(name, color = LaserColors.Text, fontFamily = BodyFamily, fontSize = metrics.rowText,
             modifier = Modifier.weight(1f))
        if (metrics.big) {
            Text(version, color = LaserColors.Muted, fontFamily = BodyFamily, fontSize = metrics.value)
            LicenceBadge(licence, 15.sp)
        } else {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(version, color = LaserColors.Muted, fontFamily = BodyFamily, fontSize = 14.sp)
                LicenceBadge(licence, 12.sp)
            }
        }
    }
}

@Composable
private fun LicenceBadge(licence: Licence, size: TextUnit, pill: Boolean = false) {
    val shape = RoundedCornerShape(if (pill) 14.dp else 6.dp)
    Text(licenceLabel(licence), color = LaserColors.Text, fontFamily = BodyFamily, fontSize = size,
         maxLines = 1,
         modifier = Modifier.border(1.dp, LaserColors.Border, shape)
             .padding(horizontal = if (pill) 12.dp else 8.dp, vertical = if (pill) 4.dp else 2.dp))
}

@Composable
private fun LogoPlate(@DrawableRes logo: Int, name: String, height: Dp, metrics: OptionsMetrics,
                      modifier: Modifier) {
    Box(
        modifier.height(if (metrics.big) 52.dp else 48.dp).clip(RoundedCornerShape(8.dp))
            .background(LogoPlate).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(logo), contentDescription = name, modifier = Modifier.height(height))
    }
}

/**
 * The licence's text, or the list of the other libraries: a page of its
 * own, with a back button. Each paragraph or library takes the remote's
 * focus, which scrolls the page.
 */
@Composable
internal fun AboutPageScreen(page: AboutPage, metrics: OptionsMetrics, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val big = metrics.big
    val first = remember { FocusRequester() }
    LaunchedEffect(page) { if (big) runCatching { first.requestFocus() } }

    Column(
        Modifier.fillMaxSize().background(LaserColors.Background).safeDrawingPadding()
            .padding(start = if (big) 72.dp else 20.dp, end = if (big) 72.dp else 20.dp,
                     top = if (big) 40.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(if (big) 24.dp else 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (big) 24.dp else 12.dp)) {
            CircleIconButton(R.drawable.ic_chevron_left, stringResource(R.string.back),
                             metrics.back, onBack)
            Text(stringResource(if (page == AboutPage.LICENCE) R.string.about_licence
                                else R.string.about_other_libraries),
                 color = LaserColors.Text, fontFamily = DisplayFamily,
                 fontSize = if (big) 44.sp else 30.sp, lineHeight = if (big) 46.sp else 32.sp)
        }
        when (page) {
            AboutPage.LICENCE -> LicenceText(big, first)
            AboutPage.LIBRARIES -> LibraryList(big, first)
        }
    }
}

/* The licence's paragraphs, out of the text's fixed lines: a heading is
 * centred there, with leading spaces. */
private data class Paragraph(val text: String, val heading: Boolean)

@Composable
private fun LicenceText(big: Boolean, first: FocusRequester) {
    val context = LocalContext.current
    val paragraphs = remember {
        val text = context.resources.openRawResource(R.raw.gpl_2_0).bufferedReader().use { it.readText() }
        text.split(Regex("""\n\s*\n""")).filter { it.isNotBlank() }.map { block ->
            val lines = block.lines().filter { it.isNotBlank() }
            Paragraph(lines.joinToString(if (lines.first().startsWith("        ")) "\n" else " ") {
                          it.trim()
                      }.replace(Regex(" {2,}"), " "),
                      heading = lines.first().startsWith("        "))
        }
    }
    val size = if (big) 18.sp else 15.sp
    Text(stringResource(R.string.app_name) + " — Copyright © ${Credits.YEAR} ${Credits.AUTHOR} — " +
         licenceLabel(Licence.GPL2_PLUS),
         color = LaserColors.Muted, fontFamily = BodyFamily, fontSize = if (big) 20.sp else 15.sp,
         lineHeight = if (big) 28.sp else 21.sp, modifier = Modifier.padding(horizontal = 4.dp))
    SourceLink(big)
    val shape = if (big) RoundedCornerShape(12.dp) else RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
    LazyColumn(
        Modifier.fillMaxWidth().clip(shape).background(LaserColors.Card)
            .border(1.dp, LaserColors.Track, shape),
        contentPadding = PaddingValues(start = if (big) 32.dp else 18.dp, end = if (big) 32.dp else 18.dp,
                                       top = if (big) 28.dp else 20.dp, bottom = if (big) 28.dp else 32.dp),
        verticalArrangement = Arrangement.spacedBy(if (big) 14.dp else 12.dp),
    ) {
        itemsIndexed(paragraphs) { i, p ->
            Text(p.text, color = if (p.heading) LaserColors.Text else LaserColors.Text.copy(alpha = 0.8f),
                 fontFamily = BodyFamily, fontSize = size, lineHeight = size * 1.55f,
                 fontWeight = if (p.heading) FontWeight.SemiBold else FontWeight.Normal,
                 modifier = (if (i == 0) Modifier.focusRequester(first) else Modifier).focusable())
        }
    }
}

/** The app's repository, where its source is: opened in a browser, where
 * the device has one. */
@Composable
private fun SourceLink(big: Boolean) {
    val context = LocalContext.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        Modifier.clip(RoundedCornerShape(8.dp))
            .background(if (focused) LaserColors.Text else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button) {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Credits.SOURCE.toUri())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        FocusColors(focused) {
            Text(Credits.SOURCE.removePrefix("https://"), color = LaserColors.Text,
                 fontFamily = BodyFamily, fontSize = if (big) 20.sp else 15.sp,
                 textDecoration = TextDecoration.Underline)
        }
    }
}

@Composable
private fun LibraryList(big: Boolean, first: FocusRequester) {
    val sections = listOf(
        stringResource(R.string.about_in_libvlc, Credits.LIBVLC_VERSION) to Credits.inLibvlc,
        stringResource(R.string.about_in_app) to Credits.inApp,
    )
    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = if (big) 48.dp else 32.dp),
    ) {
        sections.forEachIndexed { s, (title, libraries) ->
            item(key = "title$s") {
                Text(title.uppercase(), color = LaserColors.Muted, fontFamily = BodyFamily,
                     fontSize = if (big) 15.sp else 13.sp, fontWeight = FontWeight.SemiBold,
                     letterSpacing = (if (big) 15.sp else 13.sp) * 0.08f,
                     modifier = Modifier.padding(start = 4.dp, top = if (s == 0) 0.dp else 24.dp,
                                                 bottom = 10.dp))
            }
            itemsIndexed(libraries, key = { _, l -> "$s:${l.name}" }) { i, library ->
                LibraryItem(library, big, position = i, count = libraries.size,
                            modifier = if (s == 0 && i == 0) Modifier.focusRequester(first) else Modifier)
            }
        }
    }
}

/** One library in its section's block: the block's rounded corners on its
 * first and last items, a divider between items. */
@Composable
private fun LibraryItem(library: Library, big: Boolean, position: Int, count: Int, modifier: Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val top = if (position == 0) 12.dp else 0.dp
    val bottom = if (position == count - 1) 12.dp else 0.dp
    val shape: Shape = RoundedCornerShape(topStart = top, topEnd = top,
                                          bottomStart = bottom, bottomEnd = bottom)
    Column(
        modifier.fillMaxWidth().clip(shape)
            .background(if (focused) LaserColors.Text else LaserColors.Card)
            .border(1.dp, if (focused) Color.Transparent else LaserColors.Track, shape)
            .focusable(interactionSource = interaction)
            .padding(horizontal = if (big) 24.dp else 16.dp, vertical = if (big) 16.dp else 14.dp),
        verticalArrangement = Arrangement.spacedBy(if (big) 4.dp else 6.dp),
    ) { FocusColors(focused) {
        if (big) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(library.name, color = LaserColors.Text, fontFamily = BodyFamily, fontSize = 20.sp,
                         fontWeight = FontWeight.SemiBold)
                    Text(library.copyright, color = LaserColors.Muted, fontFamily = BodyFamily,
                         fontSize = 16.sp, lineHeight = 23.sp)
                }
                LicenceBadge(library.licence, 16.sp, pill = true)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(library.name, color = LaserColors.Text, fontFamily = BodyFamily, fontSize = 16.sp,
                     fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                LicenceBadge(library.licence, 13.sp, pill = true)
            }
            Text(library.copyright, color = LaserColors.Muted, fontFamily = BodyFamily,
                 fontSize = 13.sp, lineHeight = 19.sp)
        }
    } }
}
