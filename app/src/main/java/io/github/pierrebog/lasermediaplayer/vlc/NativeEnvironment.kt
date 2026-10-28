package io.github.pierrebog.lasermediaplayer.vlc

import android.content.Context
import android.content.pm.PackageManager
import android.system.Os
import android.util.Log
import io.github.pierrebog.lasermediaplayer.BuildConfig
import io.github.pierrebog.lasermediaplayer.LASER_TAG
import io.github.pierrebog.lasermediaplayer.storage.AacsKeys
import java.io.File

/**
 * Sets the environment variables libdvdcss, libdvdread and libbluray read.
 *
 * Called once, from LaserApp.onCreate(), before the LibVLC instance exists:
 * setenv(3) is not safe against a concurrent getenv(3), and libVLC's threads
 * may call getenv() at any time. A libaacs provider installed while the app
 * runs is therefore only seen at the next start.
 */
object NativeEnvironment {

    /** The libaacs provider was found at start: AACS discs can play. */
    var libaacsInstalled = false
        private set

    /** The package whose APK carries libaacs. The manifest names it in a
     * <queries> element, or it is reported missing on API 30 and later. */
    private const val LIBAACS_PACKAGE = "org.libaacs.provider"

    /** The libaacs provider is installed now - perhaps since the start, and
     * then only used at the next one. */
    fun libaacsProviderInstalled(context: Context): Boolean = try {
        context.packageManager.getApplicationInfo(LIBAACS_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** The BD-J runtime the app carries for this process's ABI, to be
     * unpacked by [BdjRuntime.install]; null if none. */
    var bdjAbi: String? = null
        private set

    fun configure(context: Context) {
        configureLibdvdcss(context)
        configureLibaacs(context)
        configureBdj(context)
        configureFontconfig(context)
    }

    /**
     * This process's ABI, read from its native library directory:
     * Build.SUPPORTED_ABIS would be wrong for a 32-bit app on a 64-bit
     * device.
     */
    private fun processAbi(context: Context): String? =
        when (val dir = File(context.applicationInfo.nativeLibraryDir).name) {
            "arm64" -> "arm64-v8a"
            "arm" -> "armeabi-v7a"
            "x86_64" -> "x86_64"
            "x86" -> "x86"
            else -> {
                Log.w(LASER_TAG, "unknown native library directory \"$dir\"")
                null
            }
        }

    /**
     * DVDCSS_CACHE: libdvdcss's default under $HOME cannot be created here,
     * and without a cache every playback pays its key retrieval again.
     *
     * DVDREAD_NOKEYS: skips libdvdread's initAllCSSKeys(), which opens every
     * VOB at startup - seconds of seeking. Keys are then asked for on demand.
     *
     * DVDCSS_VERBOSE=1, errors only, in debug builds.
     */
    private fun configureLibdvdcss(context: Context) {
        val cache = File(context.cacheDir, "dvdcss")
        if (cache.isDirectory || cache.mkdirs())
            Os.setenv("DVDCSS_CACHE", cache.absolutePath, true)
        else
            Log.w(LASER_TAG, "could not create ${cache.absolutePath}, " +
                    "libdvdcss will retrieve CSS keys on every playback")

        Os.setenv("DVDREAD_NOKEYS", "1", true)

        if (BuildConfig.DEBUG)
            Os.setenv("DVDCSS_VERBOSE", "1", true)
    }

    /**
     * Points libbluray at the libaacs carried by the provider APK, mapped
     * straight out of it by the linker ("apk!/lib/<abi>/libaacs", API 23+),
     * for this process's ABI. libbluray appends ".so.0" itself.
     */
    private fun configureLibaacs(context: Context) {
        val apk = try {
            context.packageManager.getApplicationInfo(LIBAACS_PACKAGE, 0).sourceDir
        } catch (e: PackageManager.NameNotFoundException) {
            Log.d(LASER_TAG, "$LIBAACS_PACKAGE not installed, AACS discs will not play")
            return
        }

        val abi = processAbi(context) ?: run {
            Log.w(LASER_TAG, "cannot pick a libaacs for this ABI")
            return
        }

        Os.setenv("LIBAACS_PATH", "$apk!/lib/$abi/libaacs", true)
        libaacsInstalled = true
        Os.setenv("XDG_CACHE_HOME", context.cacheDir.absolutePath, true)

        /* KEYDB.cfg: the copy AacsKeys keeps in the private files. */
        Os.setenv("AACS_HOME", AacsKeys.home(context).absolutePath, true)
    }

    /**
     * Points libbluray at the BD-J runtime, if the app carries one for this
     * ABI: the JRE (JAVA_HOME, where bdj.c looks for lib/<arch>/server/
     * libjvm.so), its jars (LIBBLURAY_CP, a folder: the trailing separator
     * matters) and the Xlets' storage. Set even before BdjRuntime has
     * unpacked them: libbluray then finds no JVM and plays without menus.
     *
     * JAVA_TOOL_OPTIONS, which the JVM reads on top of libbluray's own
     * options: java.io.tmpdir, "/tmp" by default, which Android does not
     * have - libbluray's Java side unpacks the disc's JARs there.
     *
     * BD_DEBUG_MASK, in debug builds: libbluray's BD-J and critical messages
     * (DBG_BDJ | DBG_CRIT).
     */
    private fun configureBdj(context: Context) {
        val abi = processAbi(context) ?: return
        if (!BdjRuntime.bundled(context, abi)) return
        bdjAbi = abi

        val persistent = File(context.filesDir, "bluray/persistent")
        val cache = File(context.cacheDir, "bluray")
        val tmp = File(context.cacheDir, "bdj-tmp")
        persistent.mkdirs()
        cache.mkdirs()
        tmp.mkdirs()

        Os.setenv("JAVA_HOME", BdjRuntime.javaHome(context).absolutePath, true)
        Os.setenv("LIBBLURAY_CP", BdjRuntime.jarDir(context).absolutePath + "/", true)
        Os.setenv("LIBBLURAY_PERSISTENT_ROOT", persistent.absolutePath + "/", true)
        Os.setenv("LIBBLURAY_CACHE_ROOT", cache.absolutePath + "/", true)
        Os.setenv("JAVA_TOOL_OPTIONS", "-Djava.io.tmpdir=${tmp.absolutePath}", true)

        if (BuildConfig.DEBUG)
            Os.setenv("BD_DEBUG_MASK", "0x2800", true)
    }

    /**
     * FONTCONFIG_FILE: the fontconfig in libvlc.so - used by libbluray to
     * match BD-J fonts, and by VLC's subtitle renderer - finds no
     * configuration on Android, and then matches every font to whatever file
     * it finds first. This one points it at the system fonts, with its cache
     * in the app's own: the first scan takes a moment, the next ones none.
     * Rewritten at every start, so that it follows the app's folders.
     */
    private fun configureFontconfig(context: Context) {
        val cache = File(context.cacheDir, "fontconfig")
        val conf = File(context.filesDir, "fonts.conf")
        try {
            cache.mkdirs()
            conf.writeText("""
                |<?xml version="1.0"?>
                |<!DOCTYPE fontconfig SYSTEM "fonts.dtd">
                |<fontconfig>
                |  <dir>/system/fonts</dir>
                |  <dir>/product/fonts</dir>
                |  <cachedir>${cache.absolutePath}</cachedir>
                |  <!-- Rules apply in order: names first onto the generic
                |       families, then those onto Android's fonts. -->
                |  <!-- The Windows fonts BD-J's own fonts are named after
                |       (libbluray asks for these). -->
                |  <alias><family>Arial</family><default><family>sans-serif</family></default></alias>
                |  <alias><family>Times New Roman</family><default><family>serif</family></default></alias>
                |  <alias><family>Courier New</family><default><family>monospace</family></default></alias>
                |  <!-- Any other name: Android's sans-serif, not whatever file
                |       comes first. -->
                |  <match target="pattern">
                |    <edit name="family" mode="append_last"><string>sans-serif</string></edit>
                |  </match>
                |  <alias><family>sans-serif</family>
                |    <prefer><family>Roboto</family><family>Droid Sans</family></prefer></alias>
                |  <alias><family>serif</family>
                |    <prefer><family>Noto Serif</family><family>Droid Serif</family></prefer></alias>
                |  <alias><family>monospace</family>
                |    <prefer><family>Droid Sans Mono</family><family>Cutive Mono</family></prefer></alias>
                |</fontconfig>
                |""".trimMargin())
        } catch (e: java.io.IOException) {
            Log.w(LASER_TAG, "could not write $conf", e)
            return
        }
        Os.setenv("FONTCONFIG_FILE", conf.absolutePath, true)
    }
}
