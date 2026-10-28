package io.github.pierrebog.lasermediaplayer

import android.app.Application
import io.github.pierrebog.lasermediaplayer.disc.AlbumStore
import io.github.pierrebog.lasermediaplayer.disc.FilmLookup
import io.github.pierrebog.lasermediaplayer.drive.DriveRepository
import io.github.pierrebog.lasermediaplayer.resume.ResumeStore
import io.github.pierrebog.lasermediaplayer.settings.AppSettings
import io.github.pierrebog.lasermediaplayer.vlc.AlbumLookup
import io.github.pierrebog.lasermediaplayer.vlc.BdjRuntime
import io.github.pierrebog.lasermediaplayer.vlc.DiscClassifier
import io.github.pierrebog.lasermediaplayer.vlc.NativeEnvironment
import io.github.pierrebog.lasermediaplayer.vlc.VlcEngine
import kotlin.concurrent.thread

const val LASER_TAG = "LaserPlayer"

/**
 * Holds the process-wide objects. No dependency-injection framework: there
 * are a handful of them.
 */
class LaserApp : Application() {

    lateinit var engine: VlcEngine
        private set
    lateinit var drives: DriveRepository
        private set
    lateinit var resume: ResumeStore
        private set
    lateinit var settings: AppSettings
        private set

    override fun onCreate() {
        super.onCreate()
        /* Before LibVLC exists: setenv() races libVLC's own getenv(). */
        NativeEnvironment.configure(this)
        NativeEnvironment.bdjAbi?.let { abi ->
            thread(name = "bdj-install", priority = Thread.MIN_PRIORITY) {
                BdjRuntime.install(this, abi)
            }
        }

        settings = AppSettings(this)
        engine = VlcEngine(this)
        drives = DriveRepository(this, DiscClassifier(engine),
                                 AlbumLookup(engine, AlbumStore(this), settings),
                                 FilmLookup(this, settings), settings)
        resume = ResumeStore(this)
    }
}
