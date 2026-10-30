package io.github.pierrebog.lasermediaplayer.vlc

import android.content.Context
import android.system.Os
import io.github.pierrebog.lasermediaplayer.BuildConfig
import io.github.pierrebog.lasermediaplayer.settings.interfaceLocale
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.interfaces.ILibVLC
import java.util.Locale

/**
 * The one LibVLC instance of the process, shared by classification and
 * playback.
 */
class VlcEngine(context: Context) {

    init {
        /* libdvdcss tells, in a debug build, how it authenticates and which
         * sectors it reads - logged by VLC-std. Read on each disc it opens,
         * so set before the first. */
        if (BuildConfig.DEBUG) Os.setenv("DVDCSS_VERBOSE", "2", true)
    }

    val libVlc: ILibVLC = LibVLC(context.applicationContext, options())

    private fun options(): ArrayList<String> {
        /* LMP's language - the one chosen in its options, else the system's -
         * for a Blu-ray's title at classification; playback sets it again on
         * each media, for a language changed since. libVLC takes the ISO 639-1
         * code as it is. Not --sub-language, which would turn on subtitles in
         * the language the film is already spoken in. */
        val lang = interfaceLocale().language.ifEmpty { Locale.getDefault().language }
        return arrayListOf(
            "--menu-language=$lang",
            "--audio-language=$lang",
            /* Discs are read in large blocks; a longer cache rides out the
             * drive's seeks. */
            "--disc-caching=1500",
        ).apply { if (BuildConfig.DEBUG) add("-vv") }
    }
}
