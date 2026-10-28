package io.github.pierrebog.lasermediaplayer.ui.options

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.ConfigurationCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.pierrebog.lasermediaplayer.R
import io.github.pierrebog.lasermediaplayer.settings.AppLanguage
import io.github.pierrebog.lasermediaplayer.settings.AppSettings
import io.github.pierrebog.lasermediaplayer.settings.KeyStatus
import io.github.pierrebog.lasermediaplayer.settings.ThemeMode
import io.github.pierrebog.lasermediaplayer.settings.TrackPref
import io.github.pierrebog.lasermediaplayer.settings.languageNameInUi
import io.github.pierrebog.lasermediaplayer.settings.trackLanguages
import io.github.pierrebog.lasermediaplayer.storage.AacsKeys
import io.github.pierrebog.lasermediaplayer.ui.FormFactor
import io.github.pierrebog.lasermediaplayer.ui.home.CircleIconButton
import io.github.pierrebog.lasermediaplayer.ui.home.KeyFileErrorDialog
import io.github.pierrebog.lasermediaplayer.ui.home.KeyFileLoadingDialog
import io.github.pierrebog.lasermediaplayer.ui.home.TrackOption
import io.github.pierrebog.lasermediaplayer.ui.home.TrackSheet
import io.github.pierrebog.lasermediaplayer.ui.home.rememberKeyFilePicker
import io.github.pierrebog.lasermediaplayer.ui.rememberFormFactor
import io.github.pierrebog.lasermediaplayer.ui.theme.BodyFamily
import io.github.pierrebog.lasermediaplayer.ui.theme.DisplayFamily
import io.github.pierrebog.lasermediaplayer.ui.theme.FocusColors
import io.github.pierrebog.lasermediaplayer.ui.theme.LaserColors
import io.github.pierrebog.lasermediaplayer.ui.theme.keyNavigation
import io.github.pierrebog.lasermediaplayer.ui.theme.LocalPalette
import io.github.pierrebog.lasermediaplayer.vlc.NativeEnvironment
import java.text.DateFormat
import java.util.Date

/** The tabs of the TV layout, the sections of the phone's page. */
private enum class OptionsTab(@param:StringRes val label: Int, @param:DrawableRes val icon: Int) {
    INTERFACE(R.string.options_interface, R.drawable.ic_tab_interface),
    PLAYBACK(R.string.options_playback, R.drawable.ic_tab_playback),
    COVERS(R.string.options_covers, R.drawable.ic_tab_covers),
    ABOUT(R.string.options_about, R.drawable.ic_tab_about),
}

/** The choices opened from a row. */
private enum class Picker { LANGUAGE, THEME, AUDIO, SUBTITLES, TMDB_KEY }

/** Sizes of the TV mockups, and of the phone's. */
internal class OptionsMetrics(val big: Boolean) {
    val title: TextUnit = if (big) 44.sp else 32.sp
    val back: Dp = if (big) 56.dp else 44.dp
    val section: TextUnit = if (big) 15.sp else 13.sp
    val hint: TextUnit = if (big) 16.sp else 14.sp
    val row: Dp = if (big) 60.dp else 56.dp
    val rowText: TextUnit = if (big) 20.sp else 16.sp
    val value: TextUnit = if (big) 18.sp else 15.sp
    val detail: TextUnit = if (big) 16.sp else 13.sp
    val chevron: Dp = if (big) 20.dp else 18.dp
    val switchWidth: Dp = if (big) 52.dp else 48.dp
    val switchHeight: Dp = if (big) 30.dp else 28.dp
}

/* The tabs' pages (TV, phone in landscape), rather than the phone's single
 * page: the tab already says "Lecture", the section titles need not. */
internal val LocalTabs = compositionLocalOf { false }

/**
 * The options: a page per tab beside the tabs on a TV and a phone in
 * landscape, one scrolling page in portrait.
 */
@Composable
fun OptionsScreen(settings: AppSettings, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val form = rememberFormFactor()
    val tv = form == FormFactor.TV
    val metrics = OptionsMetrics(tv)
    /* Kept when a change of language recreates the activity. */
    var tab by rememberSaveable { mutableStateOf(OptionsTab.INTERFACE) }
    var picker by rememberSaveable { mutableStateOf<Picker?>(null) }
    val open: (Picker) -> Unit = { picker = it }
    /* The licence's text, the list of libraries: over the options. */
    var page by rememberSaveable { mutableStateOf<AboutPage?>(null) }
    page?.let { shown ->
        AboutPageScreen(shown, metrics, onBack = { page = null })
        return
    }

    Box(Modifier.fillMaxSize().background(LaserColors.Background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = if (tv) 72.dp else 20.dp, vertical = if (tv) 40.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (tv) 28.dp else 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (tv) 24.dp else 12.dp)) {
                CircleIconButton(R.drawable.ic_chevron_left, stringResource(R.string.back),
                                 metrics.back, onBack)
                Text(stringResource(R.string.options_title), color = LaserColors.Text,
                     fontFamily = DisplayFamily, fontSize = metrics.title, lineHeight = metrics.title)
            }

            if (form == FormFactor.PORTRAIT) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    InterfacePage(settings, metrics, open)
                    PlaybackPage(settings, metrics, open)
                    CoversPage(settings, metrics, open)
                    AboutTab(metrics, onOpen = { page = it })
                }
            } else {
                val firstTab = remember { FocusRequester() }
                val keys = keyNavigation()
                LaunchedEffect(keys) { if (tv || keys) runCatching { firstTab.requestFocus() } }
                Row(Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(if (tv) 48.dp else 28.dp)) {
                    Column(
                        Modifier.width(if (tv) 300.dp else 230.dp).fillMaxHeight().selectableGroup(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        OptionsTab.entries.forEach { t ->
                            TabButton(t, selected = t == tab, big = tv, onSelect = { tab = t },
                                      modifier = if (t == tab) Modifier.focusRequester(firstTab)
                                                 else Modifier)
                        }
                    }
                    Column(
                        Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(if (tv) 24.dp else 20.dp),
                    ) {
                        CompositionLocalProvider(LocalTabs provides true) {
                            when (tab) {
                                OptionsTab.INTERFACE -> InterfacePage(settings, metrics, open)
                                OptionsTab.PLAYBACK -> PlaybackPage(settings, metrics, open)
                                OptionsTab.COVERS -> CoversPage(settings, metrics, open)
                                OptionsTab.ABOUT -> AboutTab(metrics, onOpen = { page = it })
                            }
                        }
                    }
                }
            }
        }

        when (picker) {
            Picker.LANGUAGE -> {
                val current = settings.language
                val languages = AppLanguage.entries
                TrackSheet(
                    title = stringResource(R.string.options_language),
                    options = languages.map { TrackOption(languageLabel(it), null) },
                    selected = languages.indexOf(current),
                    form = form,
                    onPick = { i ->
                        picker = null
                        /* Recreates the activity when the language changes. */
                        if (languages[i] != current) settings.setLanguage(languages[i])
                    },
                    onDismiss = { picker = null },
                )
            }
            Picker.THEME -> {
                val theme by settings.theme.collectAsStateWithLifecycle()
                val modes = THEME_MODES
                TrackSheet(
                    title = stringResource(R.string.options_theme),
                    options = modes.map { TrackOption(themeLabel(it), null) },
                    selected = modes.indexOf(theme),
                    form = form,
                    onPick = { i -> picker = null; settings.setTheme(modes[i]) },
                    onDismiss = { picker = null },
                )
            }
            Picker.AUDIO, Picker.SUBTITLES -> {
                val audio = picker == Picker.AUDIO
                val playback by settings.playback.collectAsStateWithLifecycle()
                val values = (if (audio) listOf(TrackPref.ORIGINAL, TrackPref.INTERFACE)
                              else listOf(TrackPref.NONE, TrackPref.INTERFACE)) + trackLanguages()
                val current = if (audio) playback.audio else playback.subtitles
                TrackSheet(
                    title = stringResource(if (audio) R.string.options_audio_language
                                           else R.string.subtitles),
                    options = values.map { TrackOption(trackPrefLabel(it), null) },
                    selected = values.indexOf(current),
                    form = form,
                    onPick = { i ->
                        picker = null
                        settings.updatePlayback {
                            if (audio) it.copy(audio = values[i]) else it.copy(subtitles = values[i])
                        }
                    },
                    onDismiss = { picker = null },
                )
            }
            Picker.TMDB_KEY -> KeySheet(
                current = settings.lookups.value.tmdbKey,
                form = form,
                onSave = { picker = null; settings.setTmdbKey(it) },
                onDelete = { picker = null; settings.setTmdbKey("") },
                onDismiss = { picker = null },
            )
            null -> {}
        }
    }
}

/** A tab: selected on a click, or when the remote's focus reaches it. */
@Composable
private fun TabButton(
    tab: OptionsTab,
    selected: Boolean,
    big: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(if (big) 56.dp else 48.dp)
            .clip(shape)
            .background(when {
                focused -> LaserColors.Text
                selected -> LaserColors.Track
                else -> Color.Transparent
            })
            .border(1.dp, if (selected && !focused) LaserColors.CaseEdge else Color.Transparent, shape)
            .onFocusChanged { if (it.isFocused && !selected) onSelect() }
            .selectable(selected = selected, interactionSource = interaction, indication = null,
                        role = Role.Tab, onClick = onSelect)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        FocusColors(focused) {
            val color = if (selected) LaserColors.Text else LaserColors.Muted
            Icon(painterResource(tab.icon), contentDescription = null, tint = color,
                 modifier = Modifier.size(22.dp))
            Text(stringResource(tab.label), color = color, fontFamily = BodyFamily,
                 fontSize = if (big) 20.sp else 16.sp, maxLines = 1,
                 fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}

/** Language and theme, under one INTERFACE title. */
@Composable
private fun InterfacePage(settings: AppSettings, metrics: OptionsMetrics, open: (Picker) -> Unit) {
    val theme by settings.theme.collectAsStateWithLifecycle()
    val language = languageLabel(settings.language)
    val themeValue = themeLabel(theme)
    Section(stringResource(R.string.options_interface), metrics) {
        Rows(
            { ValueRow(stringResource(R.string.options_language_short), language, metrics) {
                open(Picker.LANGUAGE) } },
            { ValueRow(stringResource(R.string.options_theme), themeValue, metrics) {
                open(Picker.THEME) } },
        )
    }
}

/** Default tracks, hardware decoding per kind of disc, the other options,
 * and the Blu-ray's key file. */
@Composable
private fun PlaybackPage(settings: AppSettings, metrics: OptionsMetrics, open: (Picker) -> Unit) {
    val playback by settings.playback.collectAsStateWithLifecycle()
    val tabs = LocalTabs.current
    Section(stringResource(if (tabs) R.string.options_tracks else R.string.options_playback_tracks),
            metrics) {
        Rows(
            { ValueRow(stringResource(R.string.options_audio_language), trackPrefLabel(playback.audio),
                       metrics) { open(Picker.AUDIO) } },
            { ValueRow(stringResource(R.string.subtitles), trackPrefLabel(playback.subtitles),
                       metrics) { open(Picker.SUBTITLES) } },
            { SwitchRow(stringResource(R.string.options_no_subs_same), playback.noSubtitlesInInterfaceLanguage,
                        metrics) { on -> settings.updatePlayback { it.copy(noSubtitlesInInterfaceLanguage = on) } } },
        )
    }
    Section(stringResource(if (tabs) R.string.options_hw else R.string.options_playback_hw),
            metrics, hint = stringResource(R.string.options_hw_hint)) {
        Rows(
            { SwitchRow("VCD/SVCD", playback.hwVcd, metrics) { on ->
                settings.updatePlayback { it.copy(hwVcd = on) } } },
            { SwitchRow("DVD", playback.hwDvd, metrics) { on ->
                settings.updatePlayback { it.copy(hwDvd = on) } } },
            { SwitchRow("Blu-ray", playback.hwBluray, metrics) { on ->
                settings.updatePlayback { it.copy(hwBluray = on) } } },
        )
    }
    /* The arrow pad shows on touch screens only: nothing to change on a TV. */
    if (!metrics.big)
        Section(stringResource(if (tabs) R.string.options_other else R.string.options_playback_other),
                metrics) {
            Rows(
                { SwitchRow(stringResource(R.string.options_menu_arrows), playback.menuArrows,
                            metrics) { on -> settings.updatePlayback { it.copy(menuArrows = on) } } },
            )
        }
    BlurayKeysSection(metrics)
}

/**
 * libaacs's key file, only with the libaacs provider installed - looked for
 * when the screen opens: its date, and a newer version to load. The date is
 * the original file's, when the picker or the archive gave it.
 */
@Composable
private fun BlurayKeysSection(metrics: OptionsMetrics) {
    val context = LocalContext.current
    val provider = remember { NativeEnvironment.libaacsProviderInstalled(context) }
    if (!provider) return
    var date by remember { mutableStateOf(AacsKeys.date(context)) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<AacsKeys.Result?>(null) }
    val pick = rememberKeyFilePicker(
        onLoading = { loading = true },
        onResult = { result ->
            loading = false
            when (result) {
                null -> {}
                AacsKeys.Result.LOADED -> date = AacsKeys.date(context)
                else -> error = result
            }
        },
    )
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0]
    val value = date?.let {
        (if (locale != null) DateFormat.getDateInstance(DateFormat.MEDIUM, locale)
         else DateFormat.getDateInstance(DateFormat.MEDIUM)).format(Date(it))
    } ?: stringResource(R.string.keys_none)
    Section(if (LocalTabs.current) "Blu-ray" else stringResource(R.string.options_playback_bluray),
            metrics) {
        Rows(
            { ValueRow(stringResource(R.string.keys_title), value, metrics,
                       detail = stringResource(R.string.keys_load), onClick = pick) },
        )
    }
    if (loading) KeyFileLoadingDialog()
    error?.let { KeyFileErrorDialog(it) { error = null } }
}

/** The online lookups, and the TMDB key: in two sections in the tabs, one
 * on the phone's page. */
@Composable
private fun CoversPage(settings: AppSettings, metrics: OptionsMetrics, open: (Picker) -> Unit) {
    val lookups by settings.lookups.collectAsStateWithLifecycle()
    val albums: @Composable () -> Unit = {
        SwitchRow(stringResource(R.string.lookup_albums), lookups.albums, metrics,
                  detail = stringResource(R.string.lookup_albums_detail), onChange = settings::setAlbumLookup)
    }
    val films: @Composable () -> Unit = {
        SwitchRow(stringResource(R.string.lookup_films), lookups.films, metrics,
                  detail = stringResource(R.string.lookup_films_detail), onChange = settings::setFilmLookup)
    }
    val status = stringResource(when (lookups.keyStatus) {
        KeyStatus.NONE -> R.string.key_none
        KeyStatus.CHECKING -> R.string.key_checking
        KeyStatus.VALID -> R.string.key_valid
        KeyStatus.REFUSED -> R.string.key_refused
        KeyStatus.UNVERIFIED -> R.string.key_unverified
    })
    /* The key's last characters only. */
    val masked = lookups.tmdbKey.takeIf { it.isNotEmpty() }?.let { "•••• •••• " + it.takeLast(4) }.orEmpty()
    val key: @Composable () -> Unit = {
        ValueRow(stringResource(R.string.tmdb_key), masked, metrics, detail = status,
                 detailOk = lookups.keyStatus == KeyStatus.VALID) { open(Picker.TMDB_KEY) }
    }
    val hint = stringResource(R.string.tmdb_key_hint)
    if (LocalTabs.current) {
        Section(stringResource(R.string.options_online), metrics) { Rows(albums, films) }
        Section("TMDB", metrics, footer = hint) { Rows(key) }
    } else {
        Section(stringResource(R.string.options_covers_section), metrics, footer = hint) {
            Rows(albums, films, key)
        }
    }
}

@Composable
internal fun Section(
    title: String,
    metrics: OptionsMetrics,
    hint: String? = null,
    footer: String? = null,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title.uppercase(), color = LaserColors.Muted, fontFamily = BodyFamily,
                 fontSize = metrics.section, fontWeight = FontWeight.SemiBold,
                 letterSpacing = metrics.section * 0.08f)
            if (hint != null)
                Text(hint, color = LaserColors.Muted, fontFamily = BodyFamily, fontSize = metrics.hint,
                     lineHeight = metrics.hint * 1.4f)
        }
        content()
        if (footer != null)
            Text(footer, color = LaserColors.Muted, fontFamily = BodyFamily, fontSize = metrics.hint,
                 lineHeight = metrics.hint * 1.4f, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

/** A section's rows, in one rounded block with dividers. */
@Composable
internal fun Rows(vararg rows: @Composable () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(LaserColors.Card)
               .border(1.dp, LaserColors.Track, shape)) {
        rows.forEachIndexed { i, row ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(LaserColors.Track))
            row()
        }
    }
}

/** A row in its block shows only the remote's focus: filled, its content
 * in FocusColors. */
@Composable
internal fun Modifier.rowFrame(focused: Boolean): Modifier =
    if (focused) this.clip(RoundedCornerShape(10.dp)).background(LaserColors.Text) else this

/** A setting showing its value, opening its choices. */
@Composable
internal fun ValueRow(
    label: String,
    value: String,
    metrics: OptionsMetrics,
    detail: String? = null,
    detailOk: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (detail != null) metrics.row + 12.dp else metrics.row)
            .rowFrame(focused)
            .clickable(interactionSource = interaction, indication = null,
                       role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        FocusColors(focused) {
            RowLabel(label, detail, detailOk, metrics, Modifier.weight(1f))
            Text(value, color = LaserColors.Muted, fontFamily = BodyFamily, fontSize = metrics.value,
                 maxLines = 1)
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null,
                 tint = LaserColors.Muted, modifier = Modifier.size(metrics.chevron))
        }
    }
}

/** A setting turned on or off; its label may take two lines. */
@Composable
private fun SwitchRow(
    label: String,
    on: Boolean,
    metrics: OptionsMetrics,
    detail: String? = null,
    onChange: (Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = metrics.row)
            .rowFrame(focused)
            .toggleable(value = on, interactionSource = interaction, indication = null,
                        role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        FocusColors(focused) {
            RowLabel(label, detail, false, metrics, Modifier.weight(1f))
            Switch(on, metrics)
        }
    }
}

/** A row's label, and under it a detail line; [ok] marks the detail with a
 * check. */
@Composable
private fun RowLabel(label: String, detail: String?, ok: Boolean, metrics: OptionsMetrics,
                     modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, color = LaserColors.Text, fontFamily = BodyFamily, fontSize = metrics.rowText,
             lineHeight = metrics.rowText * 1.3f)
        if (detail != null) Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (ok) Icon(painterResource(R.drawable.ic_check), contentDescription = null,
                         tint = LaserColors.Accent, modifier = Modifier.size(metrics.detail.value.dp))
            Text(detail, color = LaserColors.Muted, fontFamily = BodyFamily, fontSize = metrics.detail)
        }
    }
}

/** The mockups' switch: the accent track with the knob at the right when on. */
@Composable
private fun Switch(on: Boolean, metrics: OptionsMetrics) {
    val dark = LocalPalette.current.dark
    val track = if (on) LaserColors.Accent else LaserColors.CaseFrame
    val knob = when {
        on -> if (dark) LaserColors.Text else LaserColors.Surface
        else -> if (dark) LaserColors.Muted else LaserColors.Surface
    }
    Box(
        Modifier.size(metrics.switchWidth, metrics.switchHeight)
            .clip(CircleShape)
            .background(track)
            .padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(metrics.switchHeight - 6.dp).clip(CircleShape).background(knob))
    }
}

/* The themes, in the order of their menu. */
private val THEME_MODES = listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK)

@Composable
private fun themeLabel(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
    }
)

@Composable
private fun languageLabel(language: AppLanguage): String =
    if (language == AppLanguage.SYSTEM) stringResource(R.string.language_system)
    else language.nativeName

/** A default track's value: "Langue d'origine", "Aucun", "Allemand"... */
@Composable
private fun trackPrefLabel(value: String): String = when (value) {
    TrackPref.ORIGINAL -> stringResource(R.string.pref_original_language)
    TrackPref.INTERFACE -> stringResource(R.string.options_language)
    TrackPref.NONE -> stringResource(R.string.subtitles_off)
    else -> languageNameInUi(value)
}
