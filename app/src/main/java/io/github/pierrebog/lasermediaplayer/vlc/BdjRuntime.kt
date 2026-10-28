package io.github.pierrebog.lasermediaplayer.vlc

import android.content.Context
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import io.github.pierrebog.lasermediaplayer.LASER_TAG
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * The Java runtime libbluray needs for BD-J menus, and libbluray's own jars.
 *
 * Shipped as assets, under "bdj/": the JRE as one zip per ABI,
 * "jre8-<abi>.zip", and the two jars, "libbluray-j2se-<version>.jar" and
 * "libbluray-awt-j2se-<version>.jar". libbluray dlopens the JVM and puts the
 * jars on its boot class path, so they must be files: they are unpacked
 * into the app's private files, once per installation of the app.
 *
 * NativeEnvironment points libbluray at them at start (JAVA_HOME,
 * LIBBLURAY_CP), whether unpacked yet or not: before, libbluray finds no
 * JVM and plays without menus, as without this runtime.
 *
 * libbluray looks for libjvm under JAVA_HOME in lib/<arch>/server, with its
 * own idea of <arch> - "arm" in a 32-bit ARM build, where the Android port
 * of OpenJDK has "aarch32"; "arm" in an arm64 build too before libbluray's
 * fix, where OpenJDK has "aarch64" - and then in lib/server: lib/server and
 * lib/client are made links to the JRE's own folders.
 */
object BdjRuntime {

    private const val ASSET_DIR = "bdj"

    /** Bumped when what install() lays out changes, to lay it out again. */
    private const val LAYOUT = 4

    private val _ready = MutableStateFlow(false)
    /** True once unpacked: Blu-ray menus can be offered. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    fun javaHome(context: Context) = File(context.filesDir, "jre")
    fun jarDir(context: Context) = File(context.filesDir, "bdj")
    private fun stampFile(context: Context) = File(context.filesDir, "bdj.stamp")

    private fun zipName(abi: String) = "jre8-$abi.zip"

    /** The app carries a runtime for [abi]: checked at start, cheaply. */
    fun bundled(context: Context, abi: String): Boolean =
        context.assets.list(ASSET_DIR)?.contains(zipName(abi)) == true

    /** Unpacks the runtime if this installation of the app has not yet.
     * Slow the first time - some 100 MB written: on a background thread. */
    fun install(context: Context, abi: String) {
        val stamp = stampOf(context)
        val stampFile = stampFile(context)
        if (stampFile.isFile && stampFile.readText() == stamp &&
            File(javaHome(context), "lib").isDirectory) {
            _ready.value = true
            return
        }

        val started = SystemClock.elapsedRealtime()
        try {
            stampFile.delete()
            unzip(context, "$ASSET_DIR/${zipName(abi)}", javaHome(context))
            copyJars(context, jarDir(context))
            stampFile.writeText(stamp)
            _ready.value = true
            Log.i(LASER_TAG, "BD-J runtime unpacked in " +
                    "${SystemClock.elapsedRealtime() - started} ms")
        } catch (e: IOException) {
            Log.w(LASER_TAG, "BD-J runtime not unpacked, Blu-ray menus unavailable", e)
        }
    }

    /** Changes with every installation or update of the app, and so with
     * its assets. */
    private fun stampOf(context: Context): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return "$LAYOUT:${info.lastUpdateTime}"
    }

    /** Into a sibling folder first, then renamed: never half a runtime. */
    private fun unzip(context: Context, asset: String, target: File) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.deleteRecursively()
        if (!tmp.mkdirs()) throw IOException("cannot create $tmp")
        val root = tmp.canonicalPath + File.separator

        ZipInputStream(context.assets.open(asset).buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val out = File(tmp, entry.name)
                if (!out.canonicalPath.startsWith(root))
                    throw IOException("entry outside the archive: ${entry.name}")
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                }
            }
        }
        linkVmFolders(File(tmp, "lib"))
        replace(tmp, target)
    }

    /** lib/server -> <arch>/server, and the same for client: the folders
     * libbluray tries whatever its <arch>. Relative, so that they survive
     * the rename. HotSpot resolves its own path, and finds java.home from
     * the real folder. */
    private fun linkVmFolders(lib: File) {
        val arches = lib.listFiles { f -> f.isDirectory }.orEmpty()
        for (variant in listOf("server", "client")) {
            val link = File(lib, variant)
            if (link.exists()) continue
            val arch = arches.firstOrNull { File(it, "$variant/libjvm.so").isFile } ?: continue
            try {
                Os.symlink("${arch.name}/$variant", link.path)
            } catch (e: ErrnoException) {
                throw IOException("cannot link $link", e)
            }
        }
    }

    private fun copyJars(context: Context, target: File) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.deleteRecursively()
        if (!tmp.mkdirs()) throw IOException("cannot create $tmp")
        val jars = context.assets.list(ASSET_DIR).orEmpty().filter { it.endsWith(".jar") }
        if (jars.isEmpty()) throw IOException("no libbluray jar in the assets")
        for (jar in jars) {
            context.assets.open("$ASSET_DIR/$jar").use { input ->
                File(tmp, jar).outputStream().use { input.copyTo(it) }
            }
        }
        replace(tmp, target)
    }

    private fun replace(tmp: File, target: File) {
        target.deleteRecursively()
        if (!tmp.renameTo(target)) throw IOException("cannot rename $tmp to $target")
    }
}
