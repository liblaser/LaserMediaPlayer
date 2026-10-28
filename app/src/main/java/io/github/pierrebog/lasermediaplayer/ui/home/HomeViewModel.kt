package io.github.pierrebog.lasermediaplayer.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.pierrebog.lasermediaplayer.LaserApp
import io.github.pierrebog.lasermediaplayer.disc.DiscInfo
import io.github.pierrebog.lasermediaplayer.disc.DiscKind
import io.github.pierrebog.lasermediaplayer.disc.DiscRow
import io.github.pierrebog.lasermediaplayer.disc.PlayMode
import io.github.pierrebog.lasermediaplayer.disc.VersionInfo
import io.github.pierrebog.lasermediaplayer.drive.Drive
import io.github.pierrebog.lasermediaplayer.drive.DriveStatus
import io.github.pierrebog.lasermediaplayer.resume.ResumePoint
import io.github.pierrebog.lasermediaplayer.resume.TrackChoice
import io.github.pierrebog.lasermediaplayer.settings.LookupSettings
import io.github.pierrebog.lasermediaplayer.settings.PlaybackDefaults
import io.github.pierrebog.lasermediaplayer.settings.defaultTracks
import io.github.pierrebog.lasermediaplayer.vlc.BdjRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** What the player is asked to do. */
data class PlaybackRequest(
    val disc: DiscInfo,
    val row: DiscRow,
    /** Media options, e.g. ":no-bluray-menu". */
    val options: List<String>,
    /** Where to start, or null for the start (or the menus). */
    val resume: ResumePoint?,
    val tracks: TrackChoice,
    /** The libVLC title to start, from 0: the version chosen, played
     * without the menus. Null for the demuxer's own start. */
    val startTitle: Int? = null,
)

/** One play button of the disc screen. */
enum class PlayAction { RESUME, PLAY, MENUS, NO_MENUS, AUDIO_PART, VIDEO_PART }

data class HomeState(
    val drives: List<Drive> = emptyList(),
    val selected: Drive? = null,
    val probing: Boolean = false,
    /** For the selected drive's disc, if any. */
    val resume: ResumePoint? = null,
    val tracks: TrackChoice = TrackChoice(null, null),
    /** The version of the film to play, when the disc has several. */
    val version: VersionInfo? = null,
    /** Which lookups are on: without one, no cover or poster is shown. */
    val lookups: LookupSettings = LookupSettings(),
    /** The BD-J runtime is unpacked: Blu-ray menus can be offered. */
    val blurayMenus: Boolean = false,
)

class HomeViewModel(private val app: LaserApp) : ViewModel() {

    private val selectedName = MutableStateFlow<String?>(null)
    /* Bumped when something stored about the disc changes. */
    private val stored = MutableStateFlow(0)

    val state: StateFlow<HomeState> = combine(
        app.drives.drives, app.drives.probing, selectedName, stored,
        combine(app.settings.playback, app.settings.lookups, BdjRuntime.ready, ::Triple),
    ) { drives, probing, name, _, (defaults, lookups, blurayMenus) ->
        /* The chosen drive while it is there, else the first with a disc. */
        val selected = drives.firstOrNull { it.deviceName == name }
            ?: drives.firstOrNull { it.status is DriveStatus.Ready }
            ?: drives.firstOrNull()
        val disc = (selected?.status as? DriveStatus.Ready)?.disc
        val version = disc?.let { versionFor(it) }
        HomeState(
            drives = drives,
            selected = selected,
            probing = probing,
            /* With versions, the chosen one's: another's means nothing. A
             * point the disc cannot resume - one from its audio part - is
             * none. */
            resume = disc?.let { d ->
                app.resume.resumePoint(d, version?.title)?.takeIf { resumeRow(d, it) != null }
            },
            tracks = disc?.let { tracksFor(it, defaults) } ?: TrackChoice(null, null),
            version = version,
            lookups = lookups,
            blurayMenus = blurayMenus,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeState())

    /**
     * What is remembered for [disc]; for a new disc, the default tracks of
     * the options - shown on the disc screen, and played unless changed
     * there. A disc without a remembered audio track plays its first one, as
     * the disc orders them, not as libVLC would pick by language.
     */
    private fun tracksFor(disc: DiscInfo, defaults: PlaybackDefaults): TrackChoice {
        if (!app.resume.hasTrackChoice(disc) &&
            (disc.kind == DiscKind.DVD_VIDEO || disc.kind == DiscKind.BLURAY))
            return defaultTracks(disc, defaults)
        val choice = app.resume.trackChoice(disc)
        return if (choice.audioId != null) choice
               else choice.copy(audioId = disc.audioTracks.firstOrNull()?.id)
    }

    /** The version chosen for [disc], else the longest - the one whose
     * length and tracks the main title gives. */
    private fun versionFor(disc: DiscInfo): VersionInfo? {
        if (disc.versions.isEmpty()) return null
        val chosen = app.resume.version(disc)
        return disc.versions.firstOrNull { it.title == chosen }
            ?: disc.versions.maxByOrNull { it.lengthMs }
    }

    fun select(drive: Drive) {
        selectedName.value = drive.deviceName
    }

    fun refresh(explicit: Boolean = false) = app.drives.refresh(explicit)

    /** Back from the player, which saved where it stopped. */
    fun onPlaybackEnded() {
        stored.value++
    }

    fun chooseTracks(disc: DiscInfo, choice: TrackChoice) {
        app.resume.saveTrackChoice(disc, choice)
        stored.value++
    }

    fun chooseVersion(disc: DiscInfo, version: VersionInfo) {
        app.resume.saveVersion(disc, version.title)
        stored.value++
    }

    /**
     * The play buttons the disc offers, primary first. With menus: playback
     * without them first - resuming, then from the start - and with them
     * last; the disc screen groups the first ones with the tracks they use.
     * A DVD with both parts likewise: its video part - resuming, then from
     * the start - then its audio part, apart. A CD, and a DVD-Audio alone,
     * play from the start only.
     */
    fun actionsFor(disc: DiscInfo, state: HomeState): List<PlayAction> {
        val resume = state.resume
        val kinds = disc.rows.map { it.kind }.toSet()
        val modes = disc.rows.map { it.mode }.toSet()
        val actions = ArrayList<PlayAction>()
        if (resume != null && resumeRow(disc, resume) != null) actions += PlayAction.RESUME
        when {
            /* Menus need the BD-J runtime; without it, the film only. */
            disc.kind == DiscKind.BLURAY && state.blurayMenus -> {
                actions += PlayAction.NO_MENUS
                actions += PlayAction.MENUS
            }
            disc.kind == DiscKind.BLURAY -> actions += PlayAction.PLAY
            DiscKind.DVD_AUDIO in kinds && DiscKind.DVD_VIDEO in kinds -> {
                actions += PlayAction.VIDEO_PART
                actions += PlayAction.AUDIO_PART
            }
            PlayMode.MENUS in modes -> {
                actions += PlayAction.NO_MENUS
                actions += PlayAction.MENUS
            }
            else -> actions += PlayAction.PLAY
        }
        return actions
    }

    fun request(disc: DiscInfo, action: PlayAction, state: HomeState): PlaybackRequest? {
        val rows = disc.rows
        val (row, options, resume) = when (action) {
            PlayAction.RESUME -> Triple(state.resume?.let { resumeRow(disc, it) },
                                        state.resume?.options.orEmpty(), state.resume)
            PlayAction.PLAY -> if (disc.kind == DiscKind.BLURAY) noMenus(disc)
                               else Triple(rows.first(), emptyList(), null)
            PlayAction.MENUS -> Triple(rows.firstOrNull { it.mode == PlayMode.MENUS }, emptyList(), null)
            PlayAction.NO_MENUS -> noMenus(disc)
            PlayAction.AUDIO_PART -> Triple(rows.firstOrNull { it.kind == DiscKind.DVD_AUDIO }, emptyList(), null)
            PlayAction.VIDEO_PART -> Triple(rows.firstOrNull { it.kind == DiscKind.DVD_VIDEO }, emptyList(), null)
        }
        row ?: return null
        /* Without the menus, the version chosen: dvdread starts at the
         * disc's first title. A resumed point has its own title. */
        val startTitle = state.version
            ?.takeIf { resume == null && row.kind == DiscKind.DVD_VIDEO && row.mode == PlayMode.NO_MENUS }
            ?.let { it.title - 1 }
        return PlaybackRequest(disc, row, options, resume, state.tracks, startTitle)
    }

    /* A point is resumed the way it was saved: title numbers depend on it.
     * Audio - a CD, a DVD's audio part - is never resumed. */
    private fun resumeRow(disc: DiscInfo, point: ResumePoint): DiscRow? =
        disc.rows.firstOrNull {
            it.mode == point.mode && it.kind != DiscKind.DVD_AUDIO && it.kind != DiscKind.CD_AUDIO
        }

    private fun noMenus(disc: DiscInfo): Triple<DiscRow?, List<String>, ResumePoint?> =
        if (disc.kind == DiscKind.BLURAY)
            Triple(disc.rows.first(), listOf(":no-bluray-menu"), null)
        else
            Triple(disc.rows.firstOrNull { it.mode == PlayMode.NO_MENUS }, emptyList(), null)

    class Factory(private val app: LaserApp) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(app) as T
    }
}
