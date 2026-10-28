package io.github.pierrebog.lasermediaplayer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.pierrebog.lasermediaplayer.R

/** The colours of one theme. */
data class Palette(
    val dark: Boolean,
    val background: Color,
    val surface: Color,
    val sheet: Color,
    /** The rows of the options screen. */
    val card: Color,
    val border: Color,
    val caseFrame: Color,
    /** The case's edge, the spinner's hub, the selected tab's border. */
    val caseEdge: Color,
    val track: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val accentSurface: Color,
    val scrim: Color,
)

/** The palette of the C′ mockups ("Boîtier sombre"). */
val DarkPalette = Palette(
    dark = true,
    background = Color(0xFF101317),
    surface = Color(0xFF1E232A),
    sheet = Color(0xFF1A1F25),
    card = Color(0xFF161A1F),
    border = Color(0xFF3F4650),
    caseFrame = Color(0xFF3A4048),
    caseEdge = Color(0xFF535B65),
    track = Color(0xFF2A3037),
    text = Color(0xFFECEFF2),
    muted = Color(0xFF99A1AA),
    accent = Color(0xFF7B93FF),
    accentSurface = Color(0xFF1E2433),
    scrim = Color(0xB805070A),
)

/** The palette of the "Thème clair" mockups. */
val LightPalette = Palette(
    dark = false,
    background = Color(0xFFF4F5F7),
    surface = Color(0xFFFFFFFF),
    sheet = Color(0xFFFFFFFF),
    card = Color(0xFFFFFFFF),
    border = Color(0xFFC4C9CF),
    caseFrame = Color(0xFFC4C9CF),
    caseEdge = Color(0xFFAEB4BB),
    track = Color(0xFFDDE1E6),
    text = Color(0xFF15191E),
    muted = Color(0xFF5A626B),
    accent = Color(0xFF3355E0),
    accentSurface = Color(0xFFE6EBFF),
    scrim = Color(0x8015191E),
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }

/**
 * The colours of a control with the remote's focus: filled with the text's
 * colour, its content in the background's - white with dark text in the dark
 * theme, dark with light text in the light one. The other theme's palette,
 * its text and fills swapped for this one's.
 */
fun Palette.focused(): Palette =
    (if (dark) LightPalette else DarkPalette).copy(
        background = text, surface = text, sheet = text, card = text, text = background)

/** Draws [content] in the focused colours when [focused]: see [focused]. */
@Composable
fun FocusColors(focused: Boolean, content: @Composable () -> Unit) {
    if (focused) CompositionLocalProvider(LocalPalette provides LocalPalette.current.focused(),
                                          content = content)
    else content()
}

/**
 * True while the app is driven by keys - a remote, a keyboard - rather than
 * by touch. Then the focused control alone is filled: a primary button is
 * filled only by the focus, which starts on it.
 */
@Composable
fun keyNavigation(): Boolean = LocalInputModeManager.current.inputMode == InputMode.Keyboard

/** The current theme's colours: the light or dark palette, the dark one
 * always in the video player. */
object LaserColors {
    val Background: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.background
    val Surface: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.surface
    val Sheet: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.sheet
    val Card: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.card
    val Border: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.border
    val CaseFrame: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.caseFrame
    val CaseEdge: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.caseEdge
    val Track: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.track
    val Text: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.text
    val Muted: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.muted
    val Accent: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.accent
    val AccentSurface: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.accentSurface
    val Scrim: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.scrim
}

/**
 * Display and body faces: Instrument Serif and Instrument Sans (SIL Open
 * Font License), in res/font, from Google Fonts. Instrument Sans is a
 * variable font there; the four weights used here are static instances of
 * it, at its normal width, cut with fontTools' instancer: variable fonts
 * only vary from API 26.
 */
val DisplayFamily: FontFamily = FontFamily(
    Font(R.font.instrument_serif_regular, FontWeight.Normal, FontStyle.Normal),
    Font(R.font.instrument_serif_italic, FontWeight.Normal, FontStyle.Italic),
)
val BodyFamily: FontFamily = FontFamily(
    Font(R.font.instrument_sans_regular, FontWeight.Normal),
    Font(R.font.instrument_sans_medium, FontWeight.Medium),
    Font(R.font.instrument_sans_semibold, FontWeight.SemiBold),
    Font(R.font.instrument_sans_bold, FontWeight.Bold),
)

/** Material's styles - dialogs, buttons - in the body face. */
private fun Typography.inFamily(family: FontFamily) = Typography(
    displayLarge = displayLarge.copy(fontFamily = family),
    displayMedium = displayMedium.copy(fontFamily = family),
    displaySmall = displaySmall.copy(fontFamily = family),
    headlineLarge = headlineLarge.copy(fontFamily = family),
    headlineMedium = headlineMedium.copy(fontFamily = family),
    headlineSmall = headlineSmall.copy(fontFamily = family),
    titleLarge = titleLarge.copy(fontFamily = family),
    titleMedium = titleMedium.copy(fontFamily = family),
    titleSmall = titleSmall.copy(fontFamily = family),
    bodyLarge = bodyLarge.copy(fontFamily = family),
    bodyMedium = bodyMedium.copy(fontFamily = family),
    bodySmall = bodySmall.copy(fontFamily = family),
    labelLarge = labelLarge.copy(fontFamily = family),
    labelMedium = labelMedium.copy(fontFamily = family),
    labelSmall = labelSmall.copy(fontFamily = family),
)

object LaserType {
    val Brand: TextStyle @Composable @ReadOnlyComposable get() =
        TextStyle(fontFamily = DisplayFamily, fontStyle = FontStyle.Italic,
                  fontSize = 26.sp, color = LaserColors.Text)
    val DiscTitle: TextStyle @Composable @ReadOnlyComposable get() =
        TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight.Normal,
                  fontSize = 34.sp, lineHeight = 36.sp, color = LaserColors.Text)
    val SheetTitle: TextStyle @Composable @ReadOnlyComposable get() =
        TextStyle(fontFamily = DisplayFamily, fontSize = 30.sp, color = LaserColors.Text)
    val Body: TextStyle @Composable @ReadOnlyComposable get() =
        TextStyle(fontFamily = BodyFamily, fontSize = 17.sp, color = LaserColors.Text)
    val Small: TextStyle @Composable @ReadOnlyComposable get() =
        TextStyle(fontFamily = BodyFamily, fontSize = 14.sp, color = LaserColors.Muted)
}

/** The app's theme; [palette] is the light or the dark one. */
@Composable
fun LaserTheme(palette: Palette = DarkPalette, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPalette provides palette) {
        val scheme = if (palette.dark) darkColorScheme(
            primary = palette.text,
            onPrimary = palette.background,
            secondary = palette.accent,
            background = palette.background,
            onBackground = palette.text,
            surface = palette.background,
            onSurface = palette.text,
            surfaceVariant = palette.surface,
            onSurfaceVariant = palette.muted,
            outline = palette.border,
        ) else lightColorScheme(
            primary = palette.text,
            onPrimary = palette.background,
            secondary = palette.accent,
            background = palette.background,
            onBackground = palette.text,
            surface = palette.background,
            onSurface = palette.text,
            surfaceVariant = palette.surface,
            onSurfaceVariant = palette.muted,
            outline = palette.border,
        )
        MaterialTheme(
            colorScheme = scheme,
            typography = Typography().inFamily(BodyFamily).copy(bodyLarge = LaserType.Body),
            content = content,
        )
    }
}
