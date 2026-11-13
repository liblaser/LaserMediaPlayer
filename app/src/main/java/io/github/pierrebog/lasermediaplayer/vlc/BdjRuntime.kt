package io.github.pierrebog.lasermediaplayer.vlc

import android.content.Context
import android.os.SystemClock
import android.util.Log
import io.github.pierrebog.lasermediaplayer.LASER_TAG
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException

/**
 * libbluray's jars, for the Blu-ray menus written in Java (BD-J).
 *
 * BD-J runs in the app's own ART, which libVLC gives libbluray
 * (bd_set_java_vm()). libbluray's android target installs three jars,
 * already converted to dex: "libbluray-android-<version>.jar",
 * "libbluray-awt-android-<version>.jar" and the dexer that converts a disc's
 * classes on the device, "libbluray-android-dexer-<version>.jar" (dx, from
 * libbluray's contrib/dx). libbluray loads them through a DexClassLoader.
 *
 * Shipped as assets, under "bdj/", and copied into the app's private files
 * once per installation of the app: libbluray loads files. Made read-only:
 * Android 14 refuses to load code from a writable file.
 *
 * NativeEnvironment points libbluray at their folder at start
 * (LIBBLURAY_CP), whether copied yet or not: before, libbluray finds no jar
 * and plays without menus.
 */
object BdjRuntime {

    private const val ASSET_DIR = "bdj"

    /** Bumped when what install() lays out changes, to lay it out again. */
    private const val LAYOUT = 5

    /** The three jars, by the start of their names. "libbluray-android-"
     * also starts the dexer's: a jar is the first of these it starts with. */
    private val JARS = listOf("libbluray-android-dexer-", "libbluray-awt-android-",
                              "libbluray-android-")

    private val _ready = MutableStateFlow(false)
    /** True once copied: Blu-ray menus can be offered. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    fun jarDir(context: Context) = File(context.filesDir, "bdj")
    private fun stampFile(context: Context) = File(context.filesDir, "bdj.stamp")

    /** Which of [JARS] [name] is; null if none. */
    private fun jarOf(name: String): String? =
        if (name.endsWith(".jar")) JARS.firstOrNull { name.startsWith(it) } else null

    /** The app carries the three jars: checked at start, cheaply. Without
     * the dexer, BD-J would start but could load none of a disc's classes. */
    fun bundled(context: Context): Boolean {
        val found = context.assets.list(ASSET_DIR).orEmpty().mapNotNull(::jarOf)
        return JARS.all { it in found }
    }

    /** Copies the jars if this installation of the app has not yet: on a
     * background thread. */
    fun install(context: Context) {
        val stamp = stampOf(context)
        val stampFile = stampFile(context)
        if (stampFile.isFile && stampFile.readText() == stamp) {
            _ready.value = true
            return
        }

        val started = SystemClock.elapsedRealtime()
        try {
            stampFile.delete()
            copyJars(context, jarDir(context))
            stampFile.writeText(stamp)
            _ready.value = true
            Log.i(LASER_TAG, "BD-J jars copied in " +
                    "${SystemClock.elapsedRealtime() - started} ms")
        } catch (e: IOException) {
            Log.w(LASER_TAG, "BD-J jars not copied, Blu-ray menus unavailable", e)
        }
    }

    /** Changes with every installation or update of the app, and so with
     * its assets. */
    private fun stampOf(context: Context): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return "$LAYOUT:${info.lastUpdateTime}"
    }

    /** Into a sibling folder first, then renamed: never half of them. */
    private fun copyJars(context: Context, target: File) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.deleteRecursively()
        if (!tmp.mkdirs()) throw IOException("cannot create $tmp")
        val jars = context.assets.list(ASSET_DIR).orEmpty().filter { jarOf(it) != null }
        if (jars.isEmpty()) throw IOException("no libbluray jar in the assets")
        for (jar in jars) {
            val out = File(tmp, jar)
            context.assets.open("$ASSET_DIR/$jar").use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
            /* Loaded as code: read-only, or Android 14 refuses it. */
            if (!out.setReadOnly())
                throw IOException("cannot make $out read-only")
        }
        replace(tmp, target)
    }

    private fun replace(tmp: File, target: File) {
        target.deleteRecursively()
        if (!tmp.renameTo(target)) throw IOException("cannot rename $tmp to $target")
    }
}
