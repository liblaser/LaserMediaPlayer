package io.github.pierrebog.lasermediaplayer.ui.player

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.util.Log
import androidx.core.net.toUri
import io.github.pierrebog.lasermediaplayer.LASER_TAG
import io.github.pierrebog.lasermediaplayer.LaserApp
import io.github.pierrebog.lasermediaplayer.disc.DiscKind
import io.github.pierrebog.lasermediaplayer.disc.LaserMrl
import io.github.pierrebog.lasermediaplayer.disc.PlayMode
import io.github.pierrebog.lasermediaplayer.drive.DriveConnections
import io.github.pierrebog.lasermediaplayer.drive.UsbDrives
import io.github.pierrebog.lasermediaplayer.resume.AudioChannel
import io.github.pierrebog.lasermediaplayer.resume.ResumePoint
import io.github.pierrebog.lasermediaplayer.resume.TrackChoice
import io.github.pierrebog.lasermediaplayer.settings.interfaceLocale
import io.github.pierrebog.lasermediaplayer.settings.languageLocale
import io.github.pierrebog.lasermediaplayer.ui.home.PlaybackRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.Locale
import java.util.MissingResourceException
import kotlin.math.roundToInt

/** A chapter of the title playing. [name] is null on a DVD, which has none. */
data class ChapterMark(val startMs: Long, val durationMs: Long, val name: String?)

/** A track of the audio screen. [title] is null until the disc's names are
 * looked up; [durationMs] is 0 when unknown. */
data class PlaylistItem(val title: String?, val durationMs: Long)

data class PlayerUi(
    val playing: Boolean = false,
    val timeMs: Long = 0,
    val lengthMs: Long = 0,
    /** A DVD or Blu-ray menu is showing: the arrows go to the disc, and
     * there is no time to show nor seek in. */
    val inMenu: Boolean = false,
    val ended: Boolean = false,
    val error: Boolean = false,
    /** The error is a protected Blu-ray failing to open: its keys are most
     * likely missing from the key file. */
    val aacsError: Boolean = false,
    /** The current title's chapters, and the one playing (-1: none). */
    val chapters: List<ChapterMark> = emptyList(),
    val chapter: Int = -1,
    /** The audio screen's tracks, and the one playing (-1: not yet known). */
    val playlist: List<PlaylistItem> = emptyList(),
    val current: Int = -1,
    /** The playlist is the title's chapters (DVD-Audio), not media (CD). */
    val byChapter: Boolean = false,
    /** The tracks chosen, on the disc screen or during playback. */
    val tracks: TrackChoice = TrackChoice(null, null),
    /** The tracks playing, as the disc lists them: what the controls show.
     * The choice until libVLC says otherwise - its default, or a track the
     * choice could not find. */
    val selected: TrackChoice = TrackChoice(null, null),
    /** The picture's shape, width over height, its pixels' aspect included;
     * 0 until the video is known. */
    val videoAspect: Float = 0f,
    /** The picture fills the screen, cropped, rather than fitting in it:
     * chosen for the disc, and remembered. */
    val fill: Boolean = false,
) {
    /** Position in the playlist's current track: a CD track is a media of
     * its own, a DVD-Audio track a chapter of the title. */
    val itemTimeMs: Long
        get() = chapters.getOrNull(current)?.takeIf { byChapter }
            ?.let { (timeMs - it.startMs).coerceAtLeast(0) } ?: timeMs
    val itemLengthMs: Long
        get() = playlist.getOrNull(current)?.durationMs?.takeIf { it > 0 } ?: lengthMs
}

/**
 * One playback, from taking the drive to letting it go.
 *
 * Every libVLC call is in this class, checked against libvlcjni's VLC 4
 * MediaPlayer.java. libvlcjni delivers its events on the main thread, where
 * this object is used.
 *
 * The drive is let go only once libVLC reports Stopped: the native modules
 * release the liblaser token in their Close(), which has run by then. Its
 * tray is locked meanwhile; its eject button stops playback, then opens it
 * ([closeAndEject]).
 */
class PlayerController(private val app: LaserApp, private val request: PlaybackRequest) {

    /** The arrow pad over a disc menu, from the options. */
    val menuArrows: Boolean get() = app.settings.playback.value.menuArrows

    private val player = MediaPlayer(app.engine.libVlc)
    private val main = Handler(Looper.getMainLooper())
    private val deviceName = request.disc.deviceName
    /* What is being played: a universal disc's video part is a DVD-Video. */
    private val kind = request.row.kind

    /** DVD-Audio played through its audio zone: one track per chapter. */
    private val playlistByChapter = kind == DiscKind.DVD_AUDIO

    /* A CD's or a DVD-Audio's track list is known from classification:
     * shown before the disc has listed its tracks. */
    private val _state = MutableStateFlow(PlayerUi(byChapter = playlistByChapter,
                                                   tracks = request.tracks,
                                                   selected = request.tracks,
                                                   playlist = namedPlaylist(
                                                       if (playlistByChapter) request.disc.dvdaTracks
                                                       else request.disc.cdTracks),
                                                   fill = app.resume.fillScreen(request.disc)))
    val state: StateFlow<PlayerUi> = _state.asStateFlow()

    /** A VCD: its chapters are its tracks. */
    val isVcd = kind == DiscKind.VIDEO_CD

    /** The disc's menu can be called up: a DVD or a Blu-ray played with its
     * menus. */
    val hasDiscMenu = (kind == DiscKind.DVD_VIDEO || kind == DiscKind.BLURAY) &&
        request.row.mode == PlayMode.MENUS && ":no-bluray-menu" !in request.options

    private var started = false
    private var mediaSet = false
    private var closing = false
    /* The eject button was pressed: the tray opens once libVLC has stopped. */
    private var ejectWhenStopped = false
    private var finished = false
    private var resumeApplied = false
    /* A DVD's resume time, waiting for its title to play - see applyResume(). */
    private var pendingResumeMs: Long? = null
    private var pendingResumeTitle = -1
    private var layout: VLCVideoLayout? = null
    /* The picture is cropped to the layout: the choice, where it applies. */
    private var cropped = false
    /* The title each chosen track was last selected in: a DVD title (or
     * the film after the menus) brings new ES, so the choice is applied
     * again in each title. */
    private var audioTitle: Int? = null
    private var subtitleTitle: Int? = null
    /* A DVD or a Blu-ray played with its menus: the menus decide the
     * tracks, and the choice is not applied - but in [choiceTitle]. */
    private val menusPlay = hasDiscMenu
    /* With the menus, the one title the choice applies to: the title a DVD
     * resumes, which skips the menus, or the one a track was picked in
     * during playback. Until a menu comes up after it has been reached: the
     * first-play menu shown before a resumed title changes nothing. */
    private var choiceTitle: Int? = null
    private var choiceReached = false

    /* An audio CD opens as a directory: the cdda access lists one subitem
     * per track and ends at once. The tracks are then played one by one. */
    private var subItems: List<IMedia>? = null
    /* A track is being changed: the old one's end is not the end of a track. */
    private var switching = false
    /* Playback started once: a later error is not the disc failing to open. */
    private var playedOnce = false

    /**
     * Opens the drive and starts playback, into [layout] for a video, or
     * without any for the audio screen.
     */
    fun start(layout: VLCVideoLayout?) {
        if (started) return
        started = true

        val device = UsbDrives.byName(app, deviceName)
        val connection = device?.let { DriveConnections.open(app, it) }
        val mrl = connection?.let { LaserMrl.withFd(request.row.mrl, it.fileDescriptor) }
        if (mrl == null) {
            Log.w(LASER_TAG, "cannot open $deviceName for playback")
            _state.update { it.copy(error = true) }
            return
        }
        app.drives.startPlayback(deviceName)
        if (hasDiscMenu && kind == DiscKind.BLURAY) main.postDelayed(menuWatch, MENU_WATCH_MS)

        val media = Media(app.engine.libVlc, mrl.toUri())
        request.options.forEach { media.addOption(it) }
        /* The disc menus, and the audio by default, in LMP's language - the
         * one chosen in its options, which may not be the system's that
         * VlcEngine gave libVLC at start: the player's language, as on a DVD
         * player, not the disc's choice of tracks, which the menus make. */
        interfaceLocale().language.takeIf { it.isNotEmpty() }?.let { lang ->
            media.addOption(":menu-language=$lang")
            media.addOption(":audio-language=$lang")
        }
        /* Hardware decoding, per kind of disc, from the options: a DVD's
         * MPEG-2 is often badly served by old devices' decoders.
         * setHWDecoderEnabled(false) leaves libVLC its own decoder order -
         * ":codec=all" - in which LibVLC 4 still tries MediaCodec first: off
         * is said explicitly, the software decoder first. */
        val hardware = app.settings.playback.value
        val hw = when (kind) {
            DiscKind.VIDEO_CD -> hardware.hwVcd
            DiscKind.DVD_VIDEO -> hardware.hwDvd
            DiscKind.BLURAY -> hardware.hwBluray
            else -> null
        }
        if (hw != null) {
            media.setHWDecoderEnabled(hw, false)
            if (!hw) media.addOption(":codec=avcodec,all")
        }
        player.setMedia(media)
        media.release()
        mediaSet = true

        if (layout != null) attach(layout)
        /* No media title drawn over the first seconds of the picture. */
        player.setVideoTitleDisplay(MediaPlayer.Position.Disable, 0)
        player.setEventListener(::onEvent)
        player.play()
        Log.d(LASER_TAG, "playing $mrl ${request.options}")
    }

    /**
     * Moves the picture to [layout]: the video screen's layout changes with
     * the orientation, and the new view comes before the old one goes.
     */
    fun attach(layout: VLCVideoLayout) {
        if (this.layout === layout || finished) return
        if (this.layout != null) player.detachViews()
        player.attachViews(layout, null, true, false)
        this.layout = layout
        /* The scale belongs to the views: set again on each. */
        applyScale()
    }

    /** Fills the screen with the picture, cropped, or fits it whole; saved
     * for the disc. */
    fun chooseFill(fill: Boolean) {
        app.resume.saveFillScreen(request.disc, fill)
        _state.update { it.copy(fill = fill) }
    }

    /**
     * Crops the picture to the layout, or fits it whole. The screen decides,
     * from the choice: there only when the picture's shape is not the
     * screen's, and not in portrait, whose band fits the picture.
     */
    fun setCropped(cropped: Boolean) {
        if (this.cropped == cropped) return
        this.cropped = cropped
        applyScale()
    }

    private fun applyScale() {
        if (layout == null || finished) return
        player.setVideoScale(if (cropped) MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
                             else MediaPlayer.ScaleType.SURFACE_BEST_FIT)
    }

    fun detach(layout: VLCVideoLayout) {
        if (this.layout !== layout || finished) return
        player.detachViews()
        this.layout = null
    }

    fun togglePause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun seekBy(deltaMs: Long) = seekTo(player.time + deltaMs)

    fun seekTo(timeMs: Long) {
        if (_state.value.inMenu) return
        val length = player.length
        val target = if (length > 0) timeMs.coerceIn(0, length - 1) else timeMs.coerceAtLeast(0)
        player.setTime(target)
        _state.update { it.copy(timeMs = target) }
    }

    /** Sends a remote key to the disc's menu. */
    fun navigate(action: NavAction) {
        player.navigate(
            when (action) {
                NavAction.UP -> MediaPlayer.Navigate.Up
                NavAction.DOWN -> MediaPlayer.Navigate.Down
                NavAction.LEFT -> MediaPlayer.Navigate.Left
                NavAction.RIGHT -> MediaPlayer.Navigate.Right
                NavAction.ACTIVATE -> MediaPlayer.Navigate.Activate
            }
        )
    }

    /**
     * A tap on a disc menu, at ([x], [y]) in the window: a mouse click for
     * libVLC, on the video view. VLC turns the view's coordinates into the
     * picture's, and the dvdnav and bluray demuxers its move and press into
     * a button's selection and activation. Sent once the tap is complete:
     * VLC activates on the press. Taps beside the picture are dropped.
     */
    fun tapMenu(x: Float, y: Float) {
        if (!_state.value.inMenu) return
        val view = videoView() ?: return
        val at = IntArray(2)
        view.getLocationInWindow(at)
        val vx = (x - at[0]).roundToInt()
        val vy = (y - at[1]).roundToInt()
        if (vx !in 0 until view.width || vy !in 0 until view.height) return
        val vout = player.vlcVout
        vout.sendMouseEvent(MotionEvent.ACTION_DOWN, 0, vx, vy)
        vout.sendMouseEvent(MotionEvent.ACTION_UP, 0, vx, vy)
    }

    /** The view libVLC draws the video in, VLC's window: inside the layout,
     * placed and sized by libvlcjni. */
    private fun videoView(): View? {
        val root = layout ?: return null
        fun find(v: View): View? {
            if ((v is SurfaceView || v is TextureView) && v.id != View.NO_ID) {
                val name = runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull()
                if (name == "surface_video" || name == "texture_video") return v
            }
            if (v is ViewGroup)
                for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        return find(root) ?: root
    }

    /** Back to the disc's menu: the title marked as one. */
    fun openDiscMenu() {
        if (!hasDiscMenu) return
        val menu = player.titles?.indexOfFirst { it.isMenu } ?: -1
        if (menu >= 0) player.setTitle(menu)
    }

    /** The picture's shape, from the video track playing: a DVD's menus
     * and its film may differ. */
    private fun refreshAspect() {
        val track = player.getSelectedTrack(IMedia.Track.Type.Video) as? IMedia.VideoTrack ?: return
        if (track.width <= 0 || track.height <= 0) return
        Log.d(LASER_TAG, "video ${track.width}x${track.height} sar ${track.sarNum}:${track.sarDen}")
        val sar = if (track.sarNum > 0 && track.sarDen > 0) track.sarNum.toFloat() / track.sarDen else 1f
        val aspect = track.width * sar / track.height
        if (aspect != _state.value.videoAspect) _state.update { it.copy(videoAspect = aspect) }
    }

    /* ---- chapters (video screen) ---------------------------------------- */

    /**
     * Back to the start of the chapter, or to the previous one when at its
     * very start. Never asks libVLC for a chapter before the first or after
     * the last: the vcd access crashes on it.
     */
    fun previousChapter() {
        val ui = _state.value
        val chapter = ui.chapters.getOrNull(ui.chapter) ?: return
        if (ui.chapter == 0 || ui.timeMs - chapter.startMs > RESTART_THRESHOLD_MS)
            seekTo(chapter.startMs)
        else
            player.chapter = ui.chapter - 1
    }

    fun nextChapter() {
        val ui = _state.value
        if (ui.chapter >= 0 && ui.chapter + 1 < ui.chapters.size) player.chapter = ui.chapter + 1
    }

    /* ---- playlist (audio screen) ---------------------------------------- */

    fun playItem(index: Int) {
        val ui = _state.value
        if (index !in ui.playlist.indices) return
        if (playlistByChapter) {
            player.chapter = index
            _state.update { it.copy(current = index) }
        } else {
            playSubItem(index)
        }
    }

    /** Back to the start of the track, or to the previous one when at its
     * very start. */
    fun previousItem() {
        val ui = _state.value
        when {
            ui.itemTimeMs > RESTART_THRESHOLD_MS || ui.current <= 0 ->
                seekTo(ui.chapters.getOrNull(ui.current)?.takeIf { ui.byChapter }?.startMs ?: 0L)
            else -> playItem(ui.current - 1)
        }
    }

    fun nextItem() {
        val ui = _state.value
        if (ui.current + 1 in ui.playlist.indices) playItem(ui.current + 1)
    }

    /* ---- tracks chosen during playback ------------------------------------ */

    /** Applies and saves a new choice of tracks. */
    fun chooseTracks(choice: TrackChoice) {
        val previous = _state.value.tracks
        val shown = _state.value.selected
        _state.update { it.copy(tracks = choice) }
        app.resume.saveTrackChoice(request.disc, choice)
        if (choice.audioId != previous.audioId) audioTitle = null
        if (choice.subtitleId != previous.subtitleId || choice.subtitlesOff != previous.subtitlesOff)
            subtitleTitle = null
        /* With the menus, a pick applies to the title playing. */
        if (menusPlay) {
            choiceTitle = player.title
            choiceReached = true
        }
        applyTracks()
        applyChannel()
        /* Shown at once: libVLC applies the change later, and reports it. */
        _state.update {
            it.copy(selected = TrackChoice(choice.audioId ?: shown.audioId, choice.subtitleId,
                                           choice.channel, choice.subtitlesOff))
        }
    }

    /* ---- lifetime ---------------------------------------------------------- */

    /**
     * Saves where playback is and stops it; the drive is let go once
     * libVLC has stopped. Idempotent.
     *
     * The stop runs on a thread of its own: libvlcjni's stop() waits for
     * libVLC to have stopped, and libVLC waits for the read in progress - as
     * long as a drive spinning up takes, tens of seconds. The player is
     * released once it returns, never while it waits.
     *
     * [onStopped] is called once libVLC has stopped, or after
     * STOP_WAIT_MS at the latest: the screen is left then, and not before -
     * leaving it destroys the video's surface, which the video output may
     * still be drawing into (a debug build of libVLC aborts on the GL error).
     * The first call's is the one kept.
     */
    fun close(onStopped: () -> Unit = {}) {
        if (closing) return
        closing = true
        stoppedCallback = onStopped
        if (!mediaSet) {
            finish()
            notifyStopped()
            return
        }
        saveResumePoint()
        /* The tracks that played are the disc's tracks next time, and what
         * the disc screen shows. */
        if (started && !_state.value.inMenu && (kind == DiscKind.DVD_VIDEO || kind == DiscKind.BLURAY))
            app.resume.saveTrackChoice(request.disc, _state.value.selected)
        /* Should libVLC take long - a drive stuck mid-read - the screen is
         * left anyway. */
        main.postDelayed(::notifyStopped, STOP_WAIT_MS)
        Thread({
            player.stop()
            main.post {
                finish()
                notifyStopped()
            }
        }, "PlayerStop").start()
    }

    private var stoppedCallback: (() -> Unit)? = null

    private fun notifyStopped() {
        val callback = stoppedCallback ?: return
        stoppedCallback = null
        callback()
    }

    /** The drive's eject button: stops as [close] does, then opens the tray. */
    fun closeAndEject(onStopped: () -> Unit = {}) {
        ejectWhenStopped = true
        close(onStopped)
    }

    private fun onEvent(event: MediaPlayer.Event) {
        when (event.type) {
            MediaPlayer.Event.Playing -> {
                switching = false
                playedOnce = true
                _state.update { it.copy(playing = true) }
                applyResume()
                noteVersion()
                refreshMenuState()
                refreshChapters()
                applyChannel()
                refreshSelected()
            }
            MediaPlayer.Event.Paused -> _state.update { it.copy(playing = false) }
            MediaPlayer.Event.TimeChanged -> {
                onTime(event.timeChanged)
                applyPendingResume()
                /* The video track's size may be known only once its first
                 * pictures are decoded - after the events above. */
                if (_state.value.videoAspect == 0f) refreshAspect()
            }
            MediaPlayer.Event.LengthChanged -> {
                Log.d(LASER_TAG, "length ${event.lengthChanged} ms")
                _state.update { it.copy(lengthMs = event.lengthChanged) }
                /* A new title (DVD) has new chapters. */
                refreshChapters()
                noteVersion()
            }
            MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESSelected -> {
                /* A DVD title change shows as new ES: the menu may have gone. */
                refreshMenuState()
                applyTracks()
                applyChannel()
                refreshSelected()
                refreshAspect()
            }
            /* The video output is up: the picture's size is known. */
            MediaPlayer.Event.Vout -> refreshAspect()
            MediaPlayer.Event.EndReached -> {
                /* Changing tracks stops the previous one, and libvlcjni reports
                 * stopping (close()) as EndReached: not an end, and the resume
                 * point close() saved must stay. */
                if (switching || closing) return
                if (playNextSubItem()) return
                /* Played to the end: nothing to resume - in the version
                 * played either. */
                request.disc.let { app.resume.savePoint(it, ResumePoint(-1, 0, 0)) }
                playedVersion?.let { app.resume.savePoint(request.disc, ResumePoint(-1, 0, 0), it) }
                _state.update { it.copy(playing = false, ended = true) }
            }
            MediaPlayer.Event.EncounteredError -> {
                /* A protected Blu-ray failing before it plays: libaacs
                 * found no keys for it - libaacs itself was checked before
                 * the playback, the key file too. */
                val aacs = !playedOnce && kind == DiscKind.BLURAY && request.disc.aacs != false
                _state.update { it.copy(playing = false, error = true, aacsError = aacs) }
            }
            MediaPlayer.Event.Stopped -> _state.update { it.copy(playing = false) }
        }
    }

    private fun finish() {
        if (finished) return
        finished = true
        main.removeCallbacksAndMessages(null)
        player.setEventListener(null)
        player.detachViews()
        layout = null
        player.release()
        subItems?.forEach { it.release() }
        subItems = null
        app.drives.endPlayback(deviceName, eject = ejectWhenStopped)
    }

    /* ---- chapters ---------------------------------------------------------- */

    private fun refreshChapters() {
        val chapters = player.getChapters(-1)
            ?.map { ChapterMark(it.timeOffset, it.duration, it.name?.takeIf(String::isNotBlank)) }
            .orEmpty()
        _state.update { ui ->
            /* The names are those of the first title's tracks, which the
             * lookup knows - one per chapter unless the title has track
             * points, which are chapters too. */
            val playlist = when {
                !playlistByChapter || chapters.isEmpty() -> ui.playlist
                player.title <= 0 && chapters.size == request.disc.dvdaTracks.size ->
                    namedPlaylist(chapters.map { it.durationMs })
                else -> chapters.map { PlaylistItem(null, it.durationMs) }
            }
            ui.copy(chapters = chapters, chapter = chapterAt(chapters, ui.timeMs),
                    playlist = playlist,
                    current = if (playlistByChapter) chapterAt(chapters, ui.timeMs) else ui.current)
        }
    }

    private fun onTime(timeMs: Long) {
        _state.update { ui ->
            val chapter = chapterAt(ui.chapters, timeMs)
            ui.copy(timeMs = timeMs, chapter = chapter,
                    current = if (playlistByChapter) chapter else ui.current)
        }
    }

    private fun chapterAt(chapters: List<ChapterMark>, timeMs: Long): Int =
        chapters.indexOfLast { it.startMs <= timeMs }

    /* ---- subitems (audio CD tracks) -------------------------------------- */

    /**
     * On EndReached: the CD's directory has listed its tracks, which start
     * playing; or a track ended, and the next one starts. False when there is
     * nothing more to play.
     */
    private fun playNextSubItem(): Boolean {
        val items = subItems
        if (items == null) {
            val media = player.media ?: return false
            val list = media.subItems()
            val found = (0 until list.count).mapNotNull { list.getMediaAt(it) }
            list.release()
            media.release()
            if (found.isEmpty()) return false
            subItems = found
            _state.update { ui ->
                ui.copy(playlist = namedPlaylist(found.map { it.duration.coerceAtLeast(0) }))
            }
            /* On a CD, a resume point's title is the track index. */
            playSubItem(request.resume?.title?.takeIf { it in found.indices } ?: 0)
            return true
        }
        val next = _state.value.current + 1
        if (next !in items.indices) return false
        playSubItem(next)
        return true
    }

    /** The CD's or the DVD-Audio's tracks, named from the disc lookup. A
     * track whose artist is not the album's (a compilation) shows it after
     * the title. */
    private fun namedPlaylist(lengthsMs: List<Long>): List<PlaylistItem> {
        val names = request.disc.album?.tracks
        return lengthsMs.mapIndexed { i, length ->
            val track = names?.getOrNull(i)
            val title = track?.title?.let { t -> track.artist?.let { "$t · $it" } ?: t }
            PlaylistItem(title, length)
        }
    }

    /** Plays the CD's track [index]. The media is changed from a posted
     * message, never from within an event. */
    private fun playSubItem(index: Int) {
        val media = subItems?.getOrNull(index) ?: return
        switching = true
        _state.update { it.copy(current = index, timeMs = 0, lengthMs = 0) }
        main.post {
            if (closing || finished) return@post
            player.setMedia(media)
            player.play()
        }
    }

    /* ---- resume ---------------------------------------------------------- */

    private fun applyResume() {
        if (resumeApplied) return
        val point = request.resume
        if (point == null) {
            resumeApplied = true
            /* The version chosen, when not the demuxer's first title. */
            request.startTitle?.takeIf { it != player.title }?.let { player.setTitle(it) }
            return
        }
        if (kind == DiscKind.CD_AUDIO) {
            /* Not before the track itself is playing; playNextSubItem()
             * started from the saved one. */
            if (subItems == null) return
            resumeApplied = true
            player.setTime(point.timeMs)
            return
        }
        resumeApplied = true
        if (point.title >= 0) {
            player.setTitle(point.title)
        } else if (kind == DiscKind.BLURAY && point.lengthMs > 0) {
            /* A point saved during a Blu-ray's menus: the film's playlist,
             * found by its length - see saveResumePoint(). */
            titleOfLength(point.lengthMs)?.takeIf { it != player.title }?.let { player.setTitle(it) }
        }
        /* A DVD resumed with its menus: the menus are skipped, and the
         * choice applies to that title. */
        if (menusPlay && point.title >= 0) {
            choiceTitle = point.title
            choiceReached = false
        }
        Log.d(LASER_TAG, "resume: title ${point.title} at ${point.timeMs} ms")
        /* With the menus, a DVD plays through libdvdnav, where a title change
         * only moves its virtual machine: a time seek before the title's
         * first block is read fails, and playback with it. The time is set
         * once the title plays. */
        if (menusPlay && kind == DiscKind.DVD_VIDEO && point.title >= 0 &&
            point.title != player.title) {
            pendingResumeMs = point.timeMs
            pendingResumeTitle = point.title
        } else {
            player.setTime(point.timeMs)
        }
    }

    private fun applyPendingResume() {
        val timeMs = pendingResumeMs ?: return
        if (player.title != pendingResumeTitle) return
        pendingResumeMs = null
        player.setTime(timeMs)
    }

    private fun saveResumePoint() {
        if (_state.value.ended || _state.value.inMenu) return
        /* Audio - a CD, a DVD's audio part - is not resumed; nor does the
         * audio part replace the point of the DVD's video part. */
        if (kind == DiscKind.DVD_AUDIO || kind == DiscKind.CD_AUDIO) return
        /* The time last shown, which libVLC's TimeChanged gives from what has
         * been played. getTime() is the demuxer's position instead, ahead of
         * it by all that is buffered: on a Blu-ray, some ten seconds. */
        val time = _state.value.timeMs.takeIf { it > 0 } ?: player.time
        if (time <= 0) return
        /* A Blu-ray's menus number the disc's titles, and a BD-J disc plays
         * its film inside its menu's title: going back to that title shows
         * the menu again, which then decides what plays. So the point is the
         * playlist playing, resumed without the menus, where each playlist is
         * a title of its own: found again by its length, as no number is
         * shared between the two ways of playing. */
        val bdMenus = menusPlay && kind == DiscKind.BLURAY
        val title = when (kind) {
            DiscKind.BLURAY -> if (bdMenus) -1 else player.title
            DiscKind.DVD_VIDEO -> player.title
            else -> -1
        }
        val options = if (bdMenus) request.options + ":no-bluray-menu" else request.options
        /* A version's point is its own, and the version the one shown next. */
        val version = versionOf(title)
        app.resume.savePoint(request.disc, ResumePoint(title, time, player.length,
                                                       request.row.mode, options),
                             version)
        if (version != null) app.resume.saveVersion(request.disc, version)
    }

    /**
     * The libVLC title lasting [lengthMs], give or take a second - the
     * closest if several do; null if none.
     */
    private fun titleOfLength(lengthMs: Long): Int? =
        player.titles.orEmpty().withIndex()
            .map { (i, t) -> i to kotlin.math.abs(t.duration - lengthMs) }
            .filter { it.second <= LENGTH_TOLERANCE_MS }
            .minByOrNull { it.second }?.first

    /**
     * The version of the film a libVLC [title] plays, by its number in the
     * disc's title table; null if it plays none. dvdnav puts its menu first
     * and numbers the titles from 1, dvdread from 0.
     */
    private fun versionOf(title: Int): Int? {
        if (kind != DiscKind.DVD_VIDEO || title < 0 || request.disc.versions.isEmpty())
            return null
        val number = if (request.row.mode == PlayMode.MENUS) title else title + 1
        /* A localised title is its version's */
        return request.disc.versions.firstOrNull { v ->
            v.title == number || v.variants.any { it.title == number }
        }?.title
    }

    /* The last version played: dvdread goes on to the next titles after it,
     * and the end it reaches is that version's end too. */
    private var playedVersion: Int? = null

    private fun noteVersion() {
        versionOf(player.title)?.let { playedVersion = it }
    }

    /* ---- menus ----------------------------------------------------------- */

    private fun refreshMenuState() {
        val titles = player.titles
        val current = player.title
        val inMenu = titles?.getOrNull(current)?.isMenu == true
        val wasInMenu = _state.value.inMenu
        _state.update { it.copy(inMenu = inMenu) }
        if (menusPlay && choiceTitle != null) {
            if (current == choiceTitle && !inMenu) choiceReached = true
            /* A menu after it: from then on, the menus decide. */
            else if (inMenu && choiceReached) choiceTitle = null
        }
        /* A Blu-ray menu closing over its film: the tracks shown are the
         * ones the film plays now. */
        if (wasInMenu && !inMenu && kind == DiscKind.BLURAY) refreshSelected()
    }

    /*
     * A Blu-ray with its menus: VLC's bluray module (patched) marks the
     * title playing as a menu while a menu is on screen - a BD-J disc shows
     * its menus in ordinary titles, and pop-up menus over the film. No
     * libVLC event says so (libvlcjni has none for a new title list): the
     * title is looked at twice a second.
     */
    private val menuWatch = object : Runnable {
        override fun run() {
            refreshMenuState()
            main.postDelayed(this, MENU_WATCH_MS)
        }
    }

    /* ---- tracks ---------------------------------------------------------- */

    /**
     * VCD: one channel on both speakers. Set again whenever the audio
     * starts, since the audio output may reset its stereo mode for a new
     * stream.
     */
    private fun applyChannel() {
        if (kind != DiscKind.VIDEO_CD) return
        val mode = when (_state.value.tracks.channel) {
            AudioChannel.STEREO -> MediaPlayer.StereoMode.Stereo
            AudioChannel.LEFT -> MediaPlayer.StereoMode.Left
            AudioChannel.RIGHT -> MediaPlayer.StereoMode.Right
        }
        val current = player.stereoMode
        /* Stereo: only undo a channel set before, leave the default alone. */
        if (mode == MediaPlayer.StereoMode.Stereo &&
            current != MediaPlayer.StereoMode.Left && current != MediaPlayer.StereoMode.Right)
            return
        if (current != mode) player.setStereoMode(mode)
    }

    /**
     * Selects the tracks chosen, in every title played without the menus;
     * with them, only in [choiceTitle].
     * Retried on each new ES until the track is there: a DVD demuxer creates
     * a subtitle ES only when its first packet arrives.
     *
     * On a Blu-ray the choice is a PID, which the bluray demuxer uses as the
     * ES id: the libVLC track id ends with it. On a DVD it is the logical
     * stream number, which the demuxers do not expose: the track is found by
     * language, the n-th track of that language on the disc being the n-th
     * of that language in libVLC's list.
     */
    private fun applyTracks() {
        if (_state.value.inMenu) return
        val title = player.title
        if (menusPlay && title != choiceTitle) return
        val choice = _state.value.tracks
        val disc = request.disc

        val audioId = choice.audioId
        if (audioId != null && audioTitle != title) {
            val wanted = disc.audioTracks.firstOrNull { it.id == audioId }
            if (wanted == null) {
                audioTitle = title
            } else {
                val ordinal = disc.audioTracks.takeWhile { it.id != audioId }
                    .count { sameLanguage(it.language, wanted.language) }
                val track = player.getTracks(IMedia.Track.Type.Audio)
                    ?.let { matchTrack(it, audioId, wanted.language, ordinal) }
                if (track != null) {
                    Log.d(LASER_TAG, "title $title: audio ${wanted.language} -> ${track.id}")
                    player.selectTrack(track.id)
                    audioTitle = title
                }
            }
        }

        val subtitleId = choice.subtitleId
        /* "None": off, whatever libVLC or the disc turned on. */
        if (subtitleId == null && choice.subtitlesOff && subtitleTitle != title) {
            player.unselectTrackType(IMedia.Track.Type.Text)
            subtitleTitle = title
        }
        if (subtitleId != null && subtitleTitle != title) {
            val wanted = disc.subtitleTracks.firstOrNull { it.id == subtitleId }
            if (wanted == null) {
                subtitleTitle = title
            } else {
                val ordinal = disc.subtitleTracks.takeWhile { it.id != subtitleId }
                    .count { sameLanguage(it.language, wanted.language) }
                val tracks = player.getTracks(IMedia.Track.Type.Text)
                val track = tracks?.let { matchTrack(it, subtitleId, wanted.language, ordinal) }
                if (track != null) {
                    Log.d(LASER_TAG, "title $title: subtitles ${wanted.language} -> ${track.id}")
                    player.selectTrack(track.id)
                    subtitleTitle = title
                } else {
                    Log.d(LASER_TAG, "title $title: subtitles ${wanted.language} not among " +
                          tracks?.joinToString { "${it.id}(${it.language})" })
                }
            }
        }
    }

    /** What libVLC plays, as the disc's track ids, for the controls. */
    private fun refreshSelected() {
        /* The disc's lists are the film's: a DVD-Video or a Blu-ray. */
        if (kind != DiscKind.DVD_VIDEO && kind != DiscKind.BLURAY) return
        if (_state.value.inMenu || finished) return
        val disc = request.disc
        if (disc.audioTracks.isEmpty() && disc.subtitleTracks.isEmpty()) return
        val audio = player.getSelectedTrack(IMedia.Track.Type.Audio)?.let { track ->
            discTrackOf(track, IMedia.Track.Type.Audio, disc.audioTracks.map { it.id to it.language })
        }
        val subtitle = player.getSelectedTrack(IMedia.Track.Type.Text)?.let { track ->
            discTrackOf(track, IMedia.Track.Type.Text, disc.subtitleTracks.map { it.id to it.language })
        }
        _state.update {
            it.copy(selected = TrackChoice(audio, subtitle, it.tracks.channel, subtitlesOff = subtitle == null))
        }
    }

    /**
     * The disc's track id for a libVLC track - the reverse of [matchTrack]:
     * on a Blu-ray the PID ends the ES id; on a DVD, the n-th track of a
     * language is the disc's n-th of that language. Null if none matches.
     */
    private fun discTrackOf(track: IMedia.Track, type: Int, discTracks: List<Pair<Int, String>>): Int? {
        if (kind == DiscKind.BLURAY)
            return discTracks.firstOrNull { track.id.endsWith("/${it.first}") }?.first
        val language = track.language.orEmpty()
        if (language.isEmpty()) return null
        val ordinal = player.getTracks(type)
            ?.filter { sameLanguage(it.language.orEmpty(), language) }
            ?.indexOfFirst { it.id == track.id }
            ?.takeIf { it >= 0 } ?: return null
        return discTracks.filter { sameLanguage(it.second, language) }.getOrNull(ordinal)?.first
    }

    private fun matchTrack(tracks: Array<IMedia.Track>, id: Int, language: String,
                           ordinal: Int): IMedia.Track? =
        if (kind == DiscKind.BLURAY)
            tracks.firstOrNull { it.id.endsWith("/$id") }
        else
            tracks.filter { sameLanguage(it.language.orEmpty(), language) }
                .let { it.getOrNull(ordinal) ?: it.firstOrNull() }

    /** "ar", "ara", "Arabic" and "arabe" are one language. */
    private fun sameLanguage(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (a.equals(b, ignoreCase = true)) return true
        return languageKey(a).equals(languageKey(b), ignoreCase = true)
    }

    /* ISO 639-2 for a code; for a name, the code whose English or local
     * name it is. */
    private fun languageKey(s: String): String {
        if (s.length <= 3) {
            val locale = languageLocale(s) ?: return s
            return try { locale.isO3Language } catch (_: MissingResourceException) { s }
        }
        val locale = Locale.getAvailableLocales().firstOrNull {
            it.getDisplayLanguage(Locale.ENGLISH).equals(s, ignoreCase = true) ||
            it.getDisplayLanguage(Locale.getDefault()).equals(s, ignoreCase = true)
        } ?: return s
        return try { locale.isO3Language } catch (_: MissingResourceException) { s }
    }

    private companion object {
        const val MENU_WATCH_MS = 500L
        const val STOP_WAIT_MS = 3_000L
        const val LENGTH_TOLERANCE_MS = 1_000L
    }
}

/** Previous: past this, back to the start of the track or chapter. */
const val RESTART_THRESHOLD_MS = 3_000L

enum class NavAction { UP, DOWN, LEFT, RIGHT, ACTIVATE }
