package io.github.pierrebog.lasermediaplayer.ui.home

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.pierrebog.lasermediaplayer.R
import io.github.pierrebog.lasermediaplayer.disc.DiscInfo
import io.github.pierrebog.lasermediaplayer.disc.DiscKind
import io.github.pierrebog.lasermediaplayer.disc.withTracksOf
import io.github.pierrebog.lasermediaplayer.drive.Drive
import io.github.pierrebog.lasermediaplayer.drive.DriveStatus
import io.github.pierrebog.lasermediaplayer.resume.TrackChoice
import io.github.pierrebog.lasermediaplayer.storage.AacsKeys
import io.github.pierrebog.lasermediaplayer.vlc.NativeEnvironment
import io.github.pierrebog.lasermediaplayer.ui.FormFactor
import io.github.pierrebog.lasermediaplayer.ui.rememberFormFactor
import io.github.pierrebog.lasermediaplayer.ui.theme.BodyFamily
import io.github.pierrebog.lasermediaplayer.ui.theme.FocusColors
import io.github.pierrebog.lasermediaplayer.ui.theme.keyNavigation
import io.github.pierrebog.lasermediaplayer.ui.theme.LaserColors
import io.github.pierrebog.lasermediaplayer.ui.theme.LaserType

private enum class Sheet { AUDIO, SUBTITLES, VERSION }

/**
 * The only screen besides the player: the drives, and the disc in the one
 * selected - or why there is none.
 */
@Composable
fun HomeScreen(vm: HomeViewModel, onPlay: (PlaybackRequest) -> Unit, onOptions: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val form = rememberFormFactor()
    val tv = form == FormFactor.TV
    var sheet by remember { mutableStateOf<Sheet?>(null) }

    /* A protected Blu-ray needs libaacs, from the provider APK, and its key
     * database: the first time, the user chooses the file (AacsKeys), and
     * the playback starts once it is loaded. */
    val context = LocalContext.current
    /* No libaacs at start: NativeEnvironment only looks for it then. */
    var libaacsMissing by remember { mutableStateOf(false) }
    var waitingForKeys by remember { mutableStateOf<PlaybackRequest?>(null) }
    var askKeys by remember { mutableStateOf(false) }
    var loadingKeys by remember { mutableStateOf(false) }
    var keysError by remember { mutableStateOf<AacsKeys.Result?>(null) }
    val pickKeys = rememberKeyFilePicker(
        onLoading = { loadingKeys = true },
        onResult = { result ->
            loadingKeys = false
            val request = waitingForKeys
            waitingForKeys = null
            when (result) {
                null -> {}
                AacsKeys.Result.LOADED -> request?.let(onPlay)
                else -> keysError = result
            }
        },
    )
    val play: (PlaybackRequest) -> Unit = { request ->
        /* Unknown counts as protected: laser.c only misses it on a disc
         * libbluray could not open. */
        if (request.disc.kind != DiscKind.BLURAY || request.disc.aacs == false)
            onPlay(request)
        else if (!NativeEnvironment.libaacsInstalled)
            libaacsMissing = true
        else if (!AacsKeys.present(context)) {
            waitingForKeys = request
            askKeys = true
        } else
            onPlay(request)
    }

    Box(Modifier.fillMaxSize().background(LaserColors.Background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(
                    horizontal = if (tv) 72.dp else 20.dp,
                    vertical = if (tv) 40.dp else 12.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(if (tv) 28.dp else 18.dp),
        ) {
            Header(state.selected, form, onRefresh = { vm.refresh(explicit = true) }, onOptions)
            if (state.drives.size > 1)
                DriveChips(state.drives, state.selected, onSelect = vm::select)

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when (val status = state.selected?.status) {
                    null ->
                        if (state.probing)
                            StateMessage(stringResource(R.string.state_probing),
                                         stringResource(R.string.state_identifying_hint), busy = true)
                        /* No refresh button: plugging a drive is noticed on its own. */
                        else
                            StateMessage(stringResource(R.string.state_no_drive),
                                         stringResource(R.string.state_no_drive_hint))
                    DriveStatus.Identifying ->
                        StateMessage(stringResource(R.string.state_identifying),
                                     stringResource(R.string.state_identifying_hint), busy = true)
                    /* No refresh button: a disc put in is noticed on its own. */
                    DriveStatus.NoDisc ->
                        StateMessage(stringResource(R.string.state_no_disc),
                                     stringResource(R.string.state_no_disc_hint))
                    /* Said as it is: why the drive is silent, and what would
                     * wake it, are not known for sure. */
                    DriveStatus.NotResponding ->
                        StateMessage(stringResource(R.string.state_not_responding),
                                     stringResource(R.string.state_not_responding_hint))
                    DriveStatus.PermissionDenied ->
                        StateMessage(stringResource(R.string.state_permission),
                                     stringResource(R.string.state_permission_hint),
                                     stringResource(R.string.allow_access), { vm.refresh(true) })
                    is DriveStatus.Ready ->
                        DiscPanel(
                            /* Its tracks, the version chosen's */
                            disc = status.disc.withTracksOf(state.version),
                            state = state,
                            form = form,
                            actions = vm.actionsFor(status.disc, state),
                            onAction = { action ->
                                vm.request(status.disc, action, state)?.let(play)
                            },
                            onAudio = { sheet = Sheet.AUDIO },
                            onSubtitles = { sheet = Sheet.SUBTITLES },
                            onVersion = { sheet = Sheet.VERSION },
                        )
                }
            }
        }

        val disc = (state.selected?.status as? DriveStatus.Ready)?.disc?.withTracksOf(state.version)
        if (disc != null) when (sheet) {
            Sheet.AUDIO -> if (disc.kind == DiscKind.VIDEO_CD)
                ChannelSheet(state.tracks, form,
                    onPick = { vm.chooseTracks(disc, state.tracks.copy(channel = it)); sheet = null },
                    onDismiss = { sheet = null })
            else
                AudioSheet(disc, state.tracks, form,
                    onPick = { vm.chooseTracks(disc, state.tracks.copy(audioId = it)); sheet = null },
                    onDismiss = { sheet = null })
            Sheet.SUBTITLES -> SubtitleSheet(disc, state.tracks, form,
                onPick = { vm.chooseTracks(disc, state.tracks.copy(subtitleId = it, subtitlesOff = it == null)); sheet = null },
                onDismiss = { sheet = null })
            Sheet.VERSION -> VersionSheet(disc, state.version, form,
                onPick = { vm.chooseVersion(disc, it); sheet = null },
                onDismiss = { sheet = null })
            null -> {}
        }

        if (libaacsMissing) AlertDialog(
            onDismissRequest = { libaacsMissing = false },
            title = { Text(stringResource(R.string.libaacs_missing_title)) },
            text = { Text(stringResource(R.string.libaacs_missing_text)) },
            confirmButton = {
                TextButton(onClick = { libaacsMissing = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )

        if (askKeys) AlertDialog(
            onDismissRequest = { askKeys = false; waitingForKeys = null },
            title = { Text(stringResource(R.string.keys_title)) },
            text = { Text(stringResource(R.string.keys_text)) },
            confirmButton = {
                TextButton(onClick = { askKeys = false; pickKeys() }) {
                    Text(stringResource(R.string.keys_choose))
                }
            },
            dismissButton = {
                TextButton(onClick = { askKeys = false; waitingForKeys = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
        if (loadingKeys) KeyFileLoadingDialog()
        keysError?.let { KeyFileErrorDialog(it) { keysError = null } }
    }
}

@Composable
private fun Header(selected: Drive?, form: FormFactor, onRefresh: () -> Unit, onOptions: () -> Unit) {
    val tv = form == FormFactor.TV
    val buttonSize = if (tv) 56.dp else 44.dp
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                FitText(stringResource(R.string.app_name), LaserType.Brand,
                        if (tv) 36.sp else 26.sp, Modifier.weight(1f, fill = false))
                if (tv && selected != null)
                    Text(selected.label, style = LaserType.Small, fontSize = 18.sp)
            }
            CircleIconButton(R.drawable.ic_refresh, stringResource(R.string.refresh),
                             buttonSize, onRefresh)
            CircleIconButton(R.drawable.ic_settings, stringResource(R.string.settings),
                             buttonSize, onOptions)
        }
        if (!tv && selected != null)
            Text(selected.label, style = LaserType.Small)
    }
}

/** One line, its size reduced until it fits the width, rather than cut. */
/* The scope's maxWidth is used, as a key: Android Studio's analysis misses
 * it, Gradle's lint does not. */
@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
private fun FitText(text: String, style: TextStyle, maxSize: TextUnit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        var size by remember(text, maxSize, maxWidth) { mutableStateOf(maxSize) }
        var fits by remember(text, maxSize, maxWidth) { mutableStateOf(false) }
        Text(text, style = style, fontSize = size, maxLines = 1, softWrap = false,
             modifier = Modifier.drawWithContent { if (fits) drawContent() },
             onTextLayout = { layout ->
                 if (layout.didOverflowWidth && size.value > MIN_FIT_SIZE) size *= 0.92f
                 else fits = true
             })
    }
}

private const val MIN_FIT_SIZE = 12f

@Composable
private fun DriveChips(drives: List<Drive>, selected: Drive?, onSelect: (Drive) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        drives.forEach { drive ->
            val interaction = remember { MutableInteractionSource() }
            val focused by interaction.collectIsFocusedAsState()
            val isSelected = drive.deviceName == selected?.deviceName
            val shape = RoundedCornerShape(20.dp)
            val status = when (val s = drive.status) {
                is DriveStatus.Ready -> discTitle(s.disc)
                DriveStatus.Identifying -> stringResource(R.string.drive_identifying)
                DriveStatus.NotResponding -> stringResource(R.string.drive_not_responding)
                else -> stringResource(R.string.drive_empty)
            }
            Column(
                Modifier
                    .clip(shape)
                    .background(when {
                        focused -> LaserColors.Text
                        isSelected -> LaserColors.AccentSurface
                        else -> LaserColors.Background
                    })
                    .border(1.dp, when {
                        focused -> Color.Transparent
                        isSelected -> LaserColors.Accent
                        else -> LaserColors.Border
                    }, shape)
                    .clickable(interactionSource = interaction, indication = null,
                               role = Role.Tab) { onSelect(drive) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                FocusColors(focused) {
                    Text(drive.label, color = LaserColors.Text, fontFamily = BodyFamily,
                         fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(status, style = LaserType.Small, maxLines = 1)
                }
            }
        }
    }
}

/** The disc screen: case, title, progress, tracks, play buttons. */
/* Both BoxWithConstraints use their scope's maxHeight and maxWidth: Android
 * Studio's analysis misses it, Gradle's lint does not. */
@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
private fun DiscPanel(
    disc: DiscInfo,
    state: HomeState,
    form: FormFactor,
    actions: List<PlayAction>,
    onAction: (PlayAction) -> Unit,
    onAudio: () -> Unit,
    onSubtitles: () -> Unit,
    onVersion: () -> Unit,
) {
    val metrics = if (form == FormFactor.TV) TvMetrics else PhoneMetrics
    /* Lookup off: no case, a larger title - "sans affiche" in the mockups. */
    val pictures = state.lookups.showsPicture(disc.kind)
    val titleSize = when (form) {
        FormFactor.TV -> if (pictures) 72.sp else 88.sp
        FormFactor.LANDSCAPE -> if (pictures) 34.sp else 40.sp
        FormFactor.PORTRAIT -> if (pictures) 34.sp else 48.sp
    }
    val firstButton = remember { FocusRequester() }
    /* With the remote - on a TV, or keys elsewhere - the focus starts on the
     * main button. */
    val keys = keyNavigation()
    LaunchedEffect(disc, form, keys) {
        if (form == FormFactor.TV || keys) runCatching { firstButton.requestFocus() }
    }

    val details: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(if (form == FormFactor.TV) 22.dp else 16.dp)) {
            /* The title, and close under it a line with a CD's artist and
             * year or a film's year, from the lookups, at the left and the
             * length at the right, then the progress bar - always shown, empty
             * until there is something to resume. The length is the resumed
             * title's, else the main title's, the CD's or the DVD-Audio's. */
            Column(verticalArrangement = Arrangement.spacedBy(if (form == FormFactor.TV) 6.dp else 4.dp)) {
                Text(discTitle(disc), style = LaserType.DiscTitle,
                     fontSize = titleSize, lineHeight = titleSize * 1.04f)
                val byline = listOfNotNull(disc.album?.artist, disc.year).joinToString(" · ")
                val length = state.resume?.lengthMs?.takeIf { it > 0 }
                    ?: state.version?.lengthMs
                    ?: disc.mainLengthMs.takeIf { it > 0 }
                    ?: disc.cdTracks.sum().takeIf { it > 0 }
                    ?: disc.dvdaTracks.sum()
                ResumeProgress(state.resume?.timeMs ?: 0, length, byline, metrics.textSize)
            }
            val tv = form == FormFactor.TV
            val gap = if (tv) 12.dp else 10.dp
            val playButton: @Composable (Int, PlayAction) -> Unit = { i, action ->
                PlayButton(
                    text = actionLabel(action, state),
                    primary = i == 0,
                    metrics = metrics,
                    onClick = { onAction(action) },
                    icon = if (action == PlayAction.MENUS) R.drawable.ic_menu else R.drawable.ic_play,
                    trailingChevron = i == 0 && tv,
                    modifier = if (i == 0) Modifier.focusRequester(firstButton) else Modifier,
                )
            }
            /* The version and the tracks apply only without the disc's menus,
             * where the menus do not decide: with a disc that has some, they
             * are grouped with the buttons playing without them - resuming,
             * which skips them, and from the start - under a heading, and
             * playing with the menus comes after, apart. */
            val tracks: @Composable () -> Unit = {
                state.version?.let { version ->
                    val text = versionLabel(version, disc.versions).let {
                        if (disc.versions.size == 2) "$it · ${formatTime(version.lengthMs)}" else it
                    }
                    TrackButton(R.drawable.ic_versions, text,
                                stringResource(R.string.version) + ", " + text,
                                metrics, onVersion, Modifier.fillMaxWidth())
                }
                TrackRow(disc, state.tracks, metrics, onAudio, onSubtitles)
            }
            /* Played apart, after the group: with the disc's menus, or a
             * DVD's audio part, its video part being grouped. */
            val apart = when {
                PlayAction.MENUS in actions -> PlayAction.MENUS
                PlayAction.AUDIO_PART in actions -> PlayAction.AUDIO_PART
                else -> null
            }
            if (apart != null) {
                val inset = if (tv) 16.dp else 12.dp
                val shape = RoundedCornerShape(12.dp)
                /* A card with its heading: each way of playing the disc in
                 * its own. */
                val section: @Composable (Int, @Composable () -> Unit) -> Unit = { heading, content ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(LaserColors.Card)
                            .border(1.dp, LaserColors.Track, shape)
                            .padding(inset),
                        verticalArrangement = Arrangement.spacedBy(gap),
                    ) {
                        Text(stringResource(heading).uppercase(),
                             fontFamily = BodyFamily, fontWeight = FontWeight.SemiBold,
                             fontSize = if (tv) 15.sp else 12.sp, letterSpacing = 0.08.em,
                             color = LaserColors.Muted)
                        content()
                    }
                }
                section(if (apart == PlayAction.MENUS) R.string.without_menus_heading
                        else R.string.video_part_heading) {
                    tracks()
                    actions.forEachIndexed { i, action ->
                        if (action != apart) playButton(i, action)
                    }
                }
                /* With the menus, in a section of its own too; a DVD's audio
                 * part, a single button, stays apart below. */
                if (apart == PlayAction.MENUS)
                    section(R.string.with_menus_heading) {
                        actions.forEachIndexed { i, action ->
                            if (action == apart) playButton(i, action)
                        }
                    }
                else
                    Column(Modifier.padding(horizontal = inset),
                           verticalArrangement = Arrangement.spacedBy(gap)) {
                        actions.forEachIndexed { i, action ->
                            if (action == apart) playButton(i, action)
                        }
                    }
            } else {
                tracks()
                Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                    actions.forEachIndexed { i, action -> playButton(i, action) }
                }
            }
        }
    }

    if (!pictures) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val height = maxHeight
            Box(Modifier
                .widthIn(max = if (form == FormFactor.TV) 780.dp else 560.dp)
                .verticalScroll(rememberScrollState())
                /* Low on a phone's page, centred beside nothing elsewhere. */
                .heightIn(min = height),
                contentAlignment = if (form == FormFactor.PORTRAIT) Alignment.BottomStart
                                   else Alignment.CenterStart) {
                details()
            }
        }
        return
    }

    when (form) {
        FormFactor.PORTRAIT ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                DiscCase(disc, 216.dp, Modifier.align(Alignment.CenterHorizontally))
                details()
            }
        FormFactor.LANDSCAPE, FormFactor.TV -> BoxWithConstraints(Modifier.fillMaxSize()) {
            val tv = form == FormFactor.TV
            val gap = if (tv) 80.dp else 28.dp
            /* The whole height, but for the case's shadow, whatever the
             * screen's shape - a poster and an album alike; no more than half
             * the width, for the buttons. */
            val caseWidth = minOf(discCaseWidth(disc, maxHeight - 16.dp),
                                  (maxWidth - gap) / 2)
            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DiscCase(disc, caseWidth)
                Box(Modifier.weight(1f).verticalScroll(rememberScrollState())) { details() }
            }
        }
    }
}

@Composable
private fun TrackRow(
    disc: DiscInfo,
    choice: TrackChoice,
    metrics: Metrics,
    onAudio: () -> Unit,
    onSubtitles: () -> Unit,
) {
    if (disc.kind == DiscKind.VIDEO_CD) {
        val text = channelLabel(choice.channel)
        Row {
            TrackButton(R.drawable.ic_audio, text,
                        stringResource(R.string.audio_track) + ", " + text,
                        metrics, onAudio, Modifier.weight(1f))
        }
        return
    }
    if (disc.audioTracks.isEmpty() && disc.subtitleTracks.isEmpty()) return
    /* A new disc plays its first track, as the disc orders them. */
    val audioIndex = disc.audioTracks.indexOfFirst { it.id == choice.audioId }.coerceAtLeast(0)
    val subIndex = disc.subtitleTracks.indexOfFirst { it.id == choice.subtitleId }
    val audioText = audioTrackShorts(disc.audioTracks).getOrNull(audioIndex).orEmpty()
    val subText = if (subIndex >= 0) subtitleLabels(disc.subtitleTracks)[subIndex]
                  else stringResource(R.string.subtitles_off)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (disc.audioTracks.isNotEmpty())
            TrackButton(R.drawable.ic_audio, audioText,
                        stringResource(R.string.audio_track) + ", " + audioText,
                        metrics, onAudio, Modifier.weight(1f))
        if (disc.subtitleTracks.isNotEmpty())
            TrackButton(R.drawable.ic_subtitles, subText,
                        stringResource(R.string.subtitles) + ", " + subText,
                        metrics, onSubtitles, Modifier.weight(1f))
    }
}

@Composable
private fun actionLabel(action: PlayAction, state: HomeState): String = when (action) {
    PlayAction.RESUME -> stringResource(R.string.resume_at, formatTime(state.resume?.timeMs ?: 0))
    PlayAction.PLAY -> stringResource(if (state.resume != null) R.string.play_from_start else R.string.play)
    PlayAction.MENUS -> stringResource(R.string.play_with_menus)
    /* Under the "without the disc's menus" heading. */
    PlayAction.NO_MENUS -> stringResource(R.string.play_from_start)
    PlayAction.AUDIO_PART -> stringResource(R.string.play_audio_part)
    PlayAction.VIDEO_PART -> stringResource(R.string.play_video_part)
}
