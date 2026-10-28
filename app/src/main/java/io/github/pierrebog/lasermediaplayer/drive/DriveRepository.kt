package io.github.pierrebog.lasermediaplayer.drive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.SystemClock
import android.util.Log
import io.github.pierrebog.lasermediaplayer.LASER_TAG
import io.github.pierrebog.lasermediaplayer.disc.DiscInfo
import io.github.pierrebog.lasermediaplayer.disc.DiscKind
import io.github.pierrebog.lasermediaplayer.disc.FilmLookup
import io.github.pierrebog.lasermediaplayer.settings.AppSettings
import io.github.pierrebog.lasermediaplayer.vlc.AlbumLookup
import io.github.pierrebog.lasermediaplayer.vlc.DiscClassifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface DriveStatus {
    /** Classification in progress. */
    data object Identifying : DriveStatus

    /** The user refused the USB permission; asked again on an explicit refresh. */
    data object PermissionDenied : DriveStatus

    /** No readable disc: empty drive, data disc, or not an optical drive. */
    data object NoDisc : DriveStatus

    /** The drive did not answer liblaser's wait: what it holds is unknown. */
    data object NotResponding : DriveStatus

    data class Ready(val disc: DiscInfo) : DriveStatus
}

data class Drive(val deviceName: String, val label: String, val status: DriveStatus)

/**
 * The drives attached and what is in them. A drive is classified when it
 * appears, and again when its disc changes: while the app is in the
 * foreground, each drive is asked every [POLL_MS] what happened to its disc
 * ([LaserDrive.poll]) - a disc put in is classified, one taken out leaves
 * "no disc". An explicit [refresh] classifies every drive again.
 *
 * Drives are classified one at a time, under [passLock]. A drive playing is
 * not classified; its eject button, the tray being locked, is passed on to
 * the player ([ejectRequests]).
 */
class DriveRepository(
    context: Context,
    private val classifier: DiscClassifier,
    private val albums: AlbumLookup,
    private val films: FilmLookup,
    private val settings: AppSettings,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val passLock = Mutex()
    private var probeWait: Job? = null
    /** The language the films on the discs were named in. */
    private var filmLanguage: String? = null

    private val _drives = MutableStateFlow<List<Drive>>(emptyList())
    val drives: StateFlow<List<Drive>> = _drives.asStateFlow()

    /** True while the kernel is still identifying a just-plugged device. */
    private val _probing = MutableStateFlow(false)
    val probing: StateFlow<Boolean> = _probing.asStateFlow()

    /** The eject button of a drive playing, by device name: the player
     * stops, then the tray opens ([endPlayback]). */
    private val _ejectRequests = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val ejectRequests: SharedFlow<String> = _ejectRequests.asSharedFlow()

    /** The app is shown: the drives are followed only then. */
    private var foreground = false
    /** One watch per drive with a connection, while in the foreground. */
    private val watchers = HashMap<String, Job>()
    /** The descriptors the watches hold a claim on, until the drive is
     * unplugged: kept in the background too, so that the drive stays set up
     * and remembers what happened to its disc meanwhile. */
    private val claims = HashMap<String, Int>()
    /** Drives just classified: the next event reporting a new disc is the
     * one already classified. */
    private val drain = HashSet<String>()
    /** The medium last reported by a drive without events. */
    private val lastMedium = HashMap<String, LaserDrive.Medium>()
    /** Devices liblaser turned down - a card reader the kernel's types did
     * not reveal: not followed, so that its kernel driver, given back, is not
     * taken again at every return to the foreground. Until unplugged. */
    private val declined = HashSet<String>()
    /** Drives whose classification found nothing after a long wait: most
     * likely still waking - a drive behind a slow bridge may answer nothing
     * for longer than liblaser waits. Classified once more as soon as a poll
     * gets an answer. */
    private val waking = HashSet<String>()
    /** Drives classified again for that once already: not again, until a
     * disc is found or the drive reports a change. */
    private val wakeRetried = HashSet<String>()

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> refresh()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    @Suppress("DEPRECATION")
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (device != null) {
                        val name = device.deviceName
                        _drives.update { list -> list.filter { it.deviceName != name } }
                        drain -= name
                        lastMedium -= name
                        declined -= name
                        /* The watch lets go of the token before the
                         * descriptor goes. */
                        val watcher = watchers.remove(name)
                        scope.launch {
                            watcher?.cancelAndJoin()
                            claims.remove(name)?.let { fd ->
                                withContext(Dispatchers.IO) { LaserDrive.release(fd) }
                            }
                            DriveConnections.close(name)
                        }
                    }
                }
            }
        }
    }

    init {
        registerReceiverCompat(appContext, usbReceiver, IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        })
        /* A lookup turned on, or a TMDB key given: the discs already
         * classified are looked up. */
        scope.launch {
            var previous = settings.lookups.value
            settings.lookups.collect { now ->
                if (now.albumsOnline && !previous.albumsOnline) nameAlbums()
                if (now.filmsOnline && (!previous.filmsOnline || now.tmdbKey != previous.tmdbKey))
                    nameFilms()
                previous = now
            }
        }
    }

    /**
     * Looks at the attached drives: drives that left go, new ones are
     * classified.
     *
     * @param explicit the user asked: every drive is classified again, and
     *                 drives whose permission was refused are asked again
     */
    fun refresh(explicit: Boolean = false) {
        if (explicit) UsbDrives.forgetPermissionDenials()
        renameFilms()
        scope.launch { passLock.withLock { pass(explicit) } }
    }

    /** The interface changed language (the activity resumes, recreated):
     * the films on the discs already classified are named again in it. */
    private fun renameFilms() {
        val language = films.language()
        if (filmLanguage == null || filmLanguage == language) return
        filmLanguage = language
        nameFilms()
    }

    /** Looks up the films on the discs already classified. */
    private fun nameFilms() {
        _drives.value.forEach { drive ->
            (drive.status as? DriveStatus.Ready)?.disc
                ?.takeIf { it.kind == DiscKind.DVD_VIDEO || it.kind == DiscKind.BLURAY }
                ?.let(::nameFilm)
        }
    }

    /** Looks up the CDs and DVD-Audios already classified, and not named by
     * MusicBrainz yet (no cover): their tracks' lengths are enough, no drive
     * needed. */
    private fun nameAlbums() {
        _drives.value.forEach { drive ->
            val disc = (drive.status as? DriveStatus.Ready)?.disc
                ?.takeIf { (it.kind == DiscKind.CD_AUDIO || it.kind == DiscKind.DVD_AUDIO) &&
                           it.album?.cover == null } ?: return@forEach
            scope.launch {
                val named = albums.online(disc) ?: return@launch
                updateDisc(disc) { it.copy(album = named.album) }
            }
        }
    }

    /**
     * The app is shown, or no longer: the drives are polled only while it
     * is. Called by the activity's onStart() and onStop(). The claims stay:
     * back in the foreground, the first poll tells what happened meanwhile.
     */
    fun setForeground(on: Boolean) {
        if (foreground == on) return
        foreground = on
        if (on) {
            _drives.value.forEach { watch(it.deviceName) }
        } else {
            watchers.values.forEach { it.cancel() }
            watchers.clear()
        }
    }

    /**
     * Follows [deviceName]'s disc, if it has a connection and the app is in
     * the foreground. A claim on the token, taken the first time and kept
     * until the drive is unplugged, keeps the drive set up between polls.
     */
    private fun watch(deviceName: String) {
        if (!foreground || deviceName in declined || watchers[deviceName]?.isActive == true) return
        val fd = DriveConnections.fd(deviceName) ?: return
        watchers[deviceName] = scope.launch {
            if (claims[deviceName] != fd) {
                /* Recorded even if cancelled meanwhile, to be released. */
                val claimed = withContext(NonCancellable + Dispatchers.IO) { LaserDrive.acquire(fd) }
                if (!claimed) {
                    /* Not an optical drive, or not answering: its connection
                     * goes, and it is left alone. */
                    Log.w(LASER_TAG, "$deviceName turned down, not followed")
                    declined += deviceName
                    withContext(Dispatchers.IO) { DriveConnections.close(deviceName) }
                    return@launch
                }
                claims[deviceName] = fd
            }
            while (isActive) {
                delay(POLL_MS)
                val status = withContext(Dispatchers.IO) { LaserDrive.poll(fd) } ?: continue
                /* It answers now: what it holds can be read. Only once it
                 * reports a disc, as liblaser's readiness check expects -
                 * any answer to the poll is too early. */
                val answers = status.medium == LaserDrive.Medium.PRESENT ||
                    status.event == LaserDrive.Event.NEW
                if (answers && deviceName in waking && !DriveConnections.isPlaying(deviceName)) {
                    waking -= deviceName
                    Log.i(LASER_TAG, "$deviceName answers now, classifying it again")
                    wakeRetried += deviceName
                    reclassify(deviceName)
                    continue
                }
                onMedia(deviceName, status)
            }
        }
    }

    /** What the drive reported. A new disc is classified; a disc taken out,
     * or the tray open, is "no disc". */
    private fun onMedia(deviceName: String, status: LaserDrive.MediaStatus) {
        val drained = drain.remove(deviceName)
        val previousMedium = lastMedium.put(deviceName, status.medium)
        val current = _drives.value.firstOrNull { it.deviceName == deviceName }?.status ?: return
        if (DriveConnections.isPlaying(deviceName)) {
            if (status.event == LaserDrive.Event.EJECT_REQUEST) _ejectRequests.tryEmit(deviceName)
            return
        }
        when {
            /* Being classified: the classification sees the new disc. */
            current == DriveStatus.Identifying -> {}
            status.event == LaserDrive.Event.NEW && !drained -> {
                wakeRetried -= deviceName
                reclassify(deviceName)
            }
            status.event == LaserDrive.Event.REMOVED || status.trayOpen ||
                status.medium == LaserDrive.Medium.ABSENT ->
                if (current is DriveStatus.Ready || current == DriveStatus.NotResponding)
                    setStatus(deviceName, DriveStatus.NoDisc)
            /* A drive without events tells the medium itself: one put in. */
            status.medium == LaserDrive.Medium.PRESENT &&
                previousMedium == LaserDrive.Medium.ABSENT -> reclassify(deviceName)
        }
    }

    /** Classifies [deviceName] again: its disc changed, or it was waking. */
    private fun reclassify(deviceName: String) {
        scope.launch {
            passLock.withLock {
                val device = withContext(Dispatchers.IO) { UsbDrives.byName(appContext, deviceName) }
                    ?: return@withLock
                classifyDrive(device)
            }
        }
    }

    /**
     * Playback on [deviceName] starts: the tray is locked, its button then
     * only asking ([ejectRequests]).
     */
    fun startPlayback(deviceName: String) {
        DriveConnections.setPlaying(deviceName, true)
        val fd = DriveConnections.fd(deviceName) ?: return
        scope.launch(Dispatchers.IO) { LaserDrive.lock(fd, true) }
    }

    /**
     * Playback on [deviceName] has stopped - libVLC has let go of the drive:
     * the tray is unlocked, and opened if [eject].
     */
    fun endPlayback(deviceName: String, eject: Boolean) {
        DriveConnections.setPlaying(deviceName, false)
        val fd = DriveConnections.fd(deviceName) ?: return
        scope.launch {
            withContext(Dispatchers.IO) {
                LaserDrive.lock(fd, false)
                if (eject) LaserDrive.eject(fd)
            }
            if (eject) setStatus(deviceName, DriveStatus.NoDisc)
        }
    }

    /** Changes [disc] if it is still in its drive. */
    private fun updateDisc(disc: DiscInfo, change: (DiscInfo) -> DiscInfo) {
        _drives.update { list ->
            list.map { drive ->
                val status = drive.status
                if (drive.deviceName == disc.deviceName && status is DriveStatus.Ready &&
                    status.disc.key == disc.key)
                    drive.copy(status = DriveStatus.Ready(change(status.disc)))
                else drive
            }
        }
    }

    private suspend fun pass(all: Boolean) {
        val devices = withContext(Dispatchers.IO) { UsbDrives.candidates(appContext) }
        val previous = _drives.value.associateBy { it.deviceName }

        /* Drives that left the bus go; new ones appear as Identifying. */
        _drives.value = devices.map { device ->
            previous[device.deviceName]
                ?: Drive(device.deviceName, device.label(), DriveStatus.Identifying)
        }

        watchProbe()

        for (device in devices) {
            /* Already classified: kept until an explicit refresh. */
            val known = previous[device.deviceName]?.status
            if (!all && known != null && known != DriveStatus.Identifying) continue
            if (UsbDrives.isPermissionDenied(device.deviceName)) {
                setStatus(device.deviceName, DriveStatus.PermissionDenied)
                continue
            }
            classifyDrive(device)
        }
    }

    /** Classifies [device], names what is in it, and follows it. Not while
     * it plays: playback has the drive. Caller holds [passLock]. */
    private suspend fun classifyDrive(device: UsbDevice) {
        if (DriveConnections.isPlaying(device.deviceName)) return
        setStatus(device.deviceName, DriveStatus.Identifying)
        val started = SystemClock.elapsedRealtime()
        val classified = classify(device)
        /* Nothing found, but after liblaser's whole wait for the drive: it
         * did not answer, rather than holding no disc - which takes seconds
         * at most to tell. Tried again once it answers (see watch); a retry
         * that finds nothing either leaves it not responding. */
        val name = device.deviceName
        val silent = classified == DriveStatus.NoDisc &&
            (SystemClock.elapsedRealtime() - started >= WAKE_SUSPECT_MS || name in wakeRetried)
        val status = if (silent) DriveStatus.NotResponding else classified
        setStatus(device.deviceName, status)
        if (silent && name !in wakeRetried) {
            Log.i(LASER_TAG, "$name did not answer, to be classified again once it does")
            waking += name
        } else {
            waking -= name
            if (status is DriveStatus.Ready) wakeRetried -= name
        }
        /* The insertion classified here may still be reported. */
        drain += device.deviceName
        watch(device.deviceName)
        val disc = (status as? DriveStatus.Ready)?.disc ?: return
        when (disc.kind) {
            DiscKind.DVD_VIDEO, DiscKind.BLURAY -> nameFilm(disc)
            DiscKind.DVD_AUDIO -> nameDvdAudio(disc)
            else -> {}
        }
    }

    private suspend fun classify(device: UsbDevice): DriveStatus {
        if (!UsbDrives.requestPermission(appContext, device)) return DriveStatus.PermissionDenied

        /* Kept open: the drive's one connection (see DriveConnections). */
        val connection: UsbDeviceConnection = withContext(Dispatchers.IO) {
            DriveConnections.open(appContext, device)
        } ?: run {
            Log.w(LASER_TAG, "could not open ${device.deviceName}")
            return DriveStatus.NoDisc
        }

        val disc = classifier.classify(connection.fileDescriptor, device.deviceName)
        return when {
            disc == null -> DriveStatus.NoDisc
            /* The cdda module reads the table of contents through the
             * connection. */
            /* A CD is looked up here, before it is shown; a Video CD is
             * not looked up; the others after they are shown. */
            disc.kind == DiscKind.CD_AUDIO -> DriveStatus.Ready(albums.complete(disc).copy(lookupDone = true))
            disc.kind == DiscKind.VIDEO_CD -> DriveStatus.Ready(disc.copy(lookupDone = true))
            else -> DriveStatus.Ready(disc)
        }
    }

    /**
     * Looks the film on [disc] up, after it is shown: no drive needed, only
     * the network. The disc is updated if it is still in the drive.
     * [disc] may already carry a film, named in another language.
     */
    private fun nameFilm(disc: DiscInfo) {
        filmLanguage = films.language()
        scope.launch {
            /* Nothing found: the title of an earlier language goes too. The
             * lookup is over either way. */
            val film = films.find(disc)
            _drives.update { list ->
                list.map { drive ->
                    val status = drive.status
                    if (drive.deviceName == disc.deviceName && status is DriveStatus.Ready &&
                        status.disc.key == disc.key)
                        drive.copy(status = DriveStatus.Ready(
                            status.disc.copy(film = film, lookupDone = true)))
                    else drive
                }
            }
        }
    }

    /**
     * Names the DVD-Audio [disc], after it is shown: what is kept of it, else
     * MusicBrainz. No drive needed - laser.c listed its tracks. The disc is
     * updated if it is still in the drive.
     */
    private fun nameDvdAudio(disc: DiscInfo) {
        scope.launch {
            val named = albums.dvdAudio(disc)
            /* The lookup is over, whatever it found. */
            updateDisc(disc) { it.copy(album = named.album ?: it.album, lookupDone = true) }
        }
    }

    /** A device the kernel is still probing is not a candidate yet: look
     * again once it is identified. */
    private fun watchProbe() {
        val probing = UsbDrives.isProbing()
        _probing.value = probing
        if (!probing || probeWait?.isActive == true) return
        probeWait = scope.launch {
            UsbDrives.awaitProbe(appContext)
            _probing.value = false
            refresh()
        }
    }

    private fun setStatus(deviceName: String, status: DriveStatus) {
        _drives.update { list ->
            list.map { if (it.deviceName == deviceName) it.copy(status = status) else it }
        }
    }

    private companion object {
        /* Linux polls a drive every two seconds; a little faster, a disc put
         * in shows soon enough. */
        const val POLL_MS = 1_500L
        /* A classification finding nothing after this long met liblaser's
         * wait for a drive that does not answer (15 s, 30 s for one that is
         * busy); an empty tray or a data disc takes a few seconds. */
        const val WAKE_SUSPECT_MS = 12_000L
    }

    private fun UsbDevice.label(): String =
        listOfNotNull(manufacturerName?.trim(), productName?.trim())
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(" ")
            .ifEmpty { "USB" }
}
