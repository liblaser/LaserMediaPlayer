package io.github.pierrebog.lasermediaplayer.vlc

import android.content.Context
import android.util.Log
import io.github.pierrebog.lasermediaplayer.LASER_TAG
import io.github.pierrebog.lasermediaplayer.disc.DiscInfo
import io.github.pierrebog.lasermediaplayer.resume.CacheRecord
import io.github.pierrebog.lasermediaplayer.resume.ResumeStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/**
 * Whether a playback has one of the caches to fill that make a disc's later
 * playbacks start sooner - and so starts slower itself:
 *
 *  - [Cache.DEX]: libbluray converts the classes of a Blu-ray's menus written
 *    in Java (BD-J) into dex files, in "libbluray-dex" under java.io.tmpdir -
 *    the app's cache - one per set of classes, named by their hash and the
 *    dexer's version. Seconds the first time; kept 30 days after their last
 *    use.
 *  - [Cache.DVDCSS]: libdvdcss keeps a DVD's title keys in DVDCSS_CACHE
 *    (see NativeEnvironment). On a disc the drive's region forbids, the keys
 *    are cracked rather than asked of the drive: up to minutes.
 *
 * Neither library says beforehand what a disc will need. So a playback
 * records which files it added ([record], against a [snapshot] taken before
 * it), and a later one is cold if any of them is gone - the system cleared
 * the cache, or they expired - or if nothing was recorded.
 *
 * Plain listings of a folder, a few milliseconds: any thread.
 */
class PlaybackCaches(context: Context, private val store: ResumeStore) {

    private val appContext = context.applicationContext

    enum class Cache(internal val id: String) { DEX("dex"), DVDCSS("css") }

    /** A cache's files at one moment. */
    class Snapshot internal constructor(val cache: Cache, internal val files: Set<String>)

    fun snapshot(cache: Cache): Snapshot = Snapshot(cache, list(cache))

    /** Whether a playback of [disc] has [cache] to fill, as far as earlier
     * ones tell. */
    fun cold(disc: DiscInfo, cache: Cache): Boolean {
        val record = store.cacheRecord(disc, cache.id) ?: return true
        if (record.stamp != stampOf(cache)) return true
        val dir = dirOf(cache)
        return record.files.any { !File(dir, it).isFile }
    }

    /** Records the files [disc]'s playback added to the cache since
     * [before], with those earlier ones added: once libVLC has stopped, and
     * written all it writes. */
    fun record(disc: DiscInfo, before: Snapshot) {
        val cache = before.cache
        val added = list(cache) - before.files
        val stamp = stampOf(cache)
        val earlier = store.cacheRecord(disc, cache.id)?.takeIf { it.stamp == stamp }?.files.orEmpty()
        store.saveCacheRecord(disc, cache.id, CacheRecord(stamp, earlier + added))
        if (added.isNotEmpty())
            Log.d(LASER_TAG, "${disc.key}: ${added.size} file(s) added to the $cache cache")
        _changes.update { it + 1 }
    }

    companion object {
        private val _changes = MutableStateFlow(0)
        /** Bumped once a playback's files are recorded: what [cold] says
         * may have changed - the disc screen's warnings. */
        val changes: StateFlow<Int> = _changes.asStateFlow()
    }

    private fun dirOf(cache: Cache): File = when (cache) {
        Cache.DEX -> File(System.getProperty("java.io.tmpdir") ?: appContext.cacheDir.path,
                          "libbluray-dex")
        Cache.DVDCSS -> File(appContext.cacheDir, "dvdcss")
    }

    private fun list(cache: Cache): Set<String> {
        val dir = dirOf(cache)
        return when (cache) {
            /* The dex files only: an optimized one beside them is the
             * system's, made again as it sees fit. */
            Cache.DEX -> dir.list().orEmpty().filter { it.endsWith(".dex") }.toSet()
            /* A folder per disc, a file per title key. */
            Cache.DVDCSS -> dir.walkTopDown().filter { it.isFile }
                .map { it.relativeTo(dir).path }.toSet()
        }
    }

    /**
     * The dex files are named by the dexer's version, which an update of the
     * app may change, leaving the old ones in place for a month: what was
     * recorded under another installation is not trusted. libdvdcss's keys
     * do not depend on it.
     */
    private fun stampOf(cache: Cache): String = when (cache) {
        Cache.DEX -> appContext.packageManager
            .getPackageInfo(appContext.packageName, 0).lastUpdateTime.toString()
        Cache.DVDCSS -> ""
    }
}
