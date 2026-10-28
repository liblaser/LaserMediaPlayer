package io.github.pierrebog.lasermediaplayer.drive

/**
 * The drive itself, through liblaser: what happened to its disc, its tray.
 *
 * The functions are in libvlc.so (libvlcjni's laser-jni.c), beside the
 * liblaser the laser access module uses: they share its registry of drives,
 * so the descriptor here is the same token classification and playback use.
 *
 * Every call blocks on USB commands - up to seconds on a drive that stops
 * answering, and [acquire] waits for the drive to be ready: never on the
 * main thread.
 */
object LaserDrive {

    init {
        /* Loaded by LibVLC already; again here, so that the order of first
         * use does not matter. */
        System.loadLibrary("vlc")
    }

    enum class Event { NONE, EJECT_REQUEST, NEW, REMOVED }

    /** Whether a disc is in. [UNKNOWN] from most drives: their events are
     * what to act on (see laser_medium_t). */
    enum class Medium { UNKNOWN, ABSENT, PRESENT }

    data class MediaStatus(val event: Event, val trayOpen: Boolean, val medium: Medium)

    /**
     * A claim on [fd], which keeps the drive set up between [poll]s - else
     * each would set it up again. Paired with [release].
     */
    fun acquire(fd: Int): Boolean = nativeAcquire(fd) == 0

    fun release(fd: Int) = nativeRelease(fd)

    /** What happened to the disc since the last poll, or null if the drive
     * did not answer. */
    fun poll(fd: Int): MediaStatus? {
        val packed = nativePoll(fd)
        if (packed < 0) return null
        return MediaStatus(
            event = Event.entries.getOrElse(packed and 0x0f) { Event.NONE },
            trayOpen = (packed and 0x10) != 0,
            medium = Medium.entries.getOrElse((packed shr 5) and 0x03) { Medium.UNKNOWN },
        )
    }

    /** Locks the tray, its button then only asking ([Event.EJECT_REQUEST]),
     * or unlocks it. */
    fun lock(fd: Int, locked: Boolean): Boolean = nativeLock(fd, locked) == 0

    /** Opens the tray; it must be unlocked. */
    fun eject(fd: Int): Boolean = nativeEject(fd) == 0

    @JvmStatic private external fun nativeAcquire(fd: Int): Int
    @JvmStatic private external fun nativeRelease(fd: Int)
    @JvmStatic private external fun nativePoll(fd: Int): Int
    @JvmStatic private external fun nativeLock(fd: Int, locked: Boolean): Int
    @JvmStatic private external fun nativeEject(fd: Int): Int
}
