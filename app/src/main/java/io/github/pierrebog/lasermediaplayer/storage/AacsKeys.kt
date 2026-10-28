package io.github.pierrebog.lasermediaplayer.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.annotation.WorkerThread
import io.github.pierrebog.lasermediaplayer.LASER_TAG
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * libaacs's key database, aacs/KEYDB.cfg, kept in this app's private files,
 * where libaacs reads it (AACS_HOME, set by NativeEnvironment).
 *
 * The user chooses the file - or a zip archive holding it, as it is often
 * published - with the system's file picker: no storage permission. It is
 * copied, and the original forgotten; a newer version is loaded the same
 * way, from the options.
 */
object AacsKeys {

    private const val NAME = "KEYDB.cfg"

    /** Larger than any key database, smaller than a mistake. */
    private const val MAX_SIZE = 512L * 1024 * 1024

    /** How far into the file its format is looked for. */
    private const val PROBE_LINES = 5000

    /** A loading's outcome; NO_PICKER: the device has no file picker to
     * choose with (some TVs). */
    enum class Result { LOADED, NOT_KEYDB, UNREADABLE, NO_PICKER }

    /** AACS_HOME: libaacs adds "aacs/" itself. */
    fun home(context: Context): File = context.filesDir

    private fun copyOf(context: Context) = File(File(home(context), "aacs"), NAME)

    /** A copy is there: AACS discs can be offered. */
    fun present(context: Context): Boolean = copyOf(context).isFile

    /** The date of the key database loaded - the original file's when it
     * was known, else the loading's - or null if there is none. */
    fun date(context: Context): Long? = copyOf(context).takeIf { it.isFile }?.lastModified()

    /**
     * Copies the key database from [uri], a KEYDB.cfg or a zip archive with
     * one in any folder. The copy only replaces the previous one once it is
     * whole and looks like a key database: libaacs never reads half a file,
     * nor loses the keys it had to a wrong choice.
     */
    @WorkerThread
    fun load(context: Context, uri: Uri): Result {
        val copy = copyOf(context)
        val dir = copy.parentFile!!
        if (!dir.isDirectory && !dir.mkdirs()) {
            Log.w(LASER_TAG, "could not create $dir")
            return Result.UNREADABLE
        }
        val part = File(dir, "$NAME.part")
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: return Result.UNREADABLE
            val date = BufferedInputStream(input).use { stream ->
                stream.mark(4)
                val head = ByteArray(4)
                var got = 0
                while (got < head.size) {
                    val n = stream.read(head, got, head.size - got)
                    if (n < 0) break
                    got += n
                }
                val zip = got == head.size && head.isZipMagic()
                stream.reset()
                if (zip) copyFromZip(stream, part) else {
                    copyLimited(stream, part)
                    documentDate(context, uri)
                }
            }
            if (date == null && !part.isFile) return Result.NOT_KEYDB
            if (!looksLikeKeydb(part)) {
                Log.d(LASER_TAG, "not a key database: $uri")
                return Result.NOT_KEYDB
            }
            if (date != null && date > 0) part.setLastModified(date)
            if (!part.renameTo(copy)) throw IOException("rename to $copy failed")
            Log.d(LASER_TAG, "KEYDB.cfg loaded, ${copy.length()} bytes")
            return Result.LOADED
        } catch (e: TooLarge) {
            Log.w(LASER_TAG, "not a key database, too large: $uri")
            return Result.NOT_KEYDB
        } catch (e: Exception) {
            Log.w(LASER_TAG, "could not load the key database from $uri", e)
            return Result.UNREADABLE
        } finally {
            part.delete()
        }
    }

    private fun ByteArray.isZipMagic() =
        this[0] == 'P'.code.toByte() && this[1] == 'K'.code.toByte() &&
        this[2] == 3.toByte() && this[3] == 4.toByte()

    /** The archive's KEYDB.cfg, whatever its folder and case, into [target];
     * its date, or null if the archive has none. */
    private fun copyFromZip(input: InputStream, target: File): Long? {
        val zip = ZipInputStream(input)
        while (true) {
            val entry = zip.nextEntry ?: return null
            if (entry.isDirectory) continue
            val name = entry.name.substringAfterLast('/').substringAfterLast('\\')
            if (!name.equals(NAME, ignoreCase = true)) continue
            copyLimited(zip, target)
            return entry.time
        }
    }

    private class TooLarge : IOException()

    private fun copyLimited(input: InputStream, target: File) {
        target.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > MAX_SIZE) throw TooLarge()
                output.write(buffer, 0, n)
            }
        }
    }

    /** The picked document's date, if its provider gives one. */
    private fun documentDate(context: Context, uri: Uri): Long? = try {
        context.contentResolver.query(uri,
            arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)
            ?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
    } catch (e: Exception) {
        null
    }

    /*
     * A KEYDB.cfg is text: comments (";"), the keys' lines ("| DK |",
     * "| PK |", "| HC |") and one line per disc, its 20 bytes ID in
     * hexadecimal first ("0x0123...89AB = Title | D | ..."). One such line
     * near the top is enough; a NUL byte is not text.
     */
    private val KEY_LINE = Regex("""^\s*\|\s*(DK|PK|HC)\s*\|""")
    private val DISC_LINE = Regex("""^\s*0x[0-9A-Fa-f]{40}\s*=""")

    private fun looksLikeKeydb(file: File): Boolean {
        if (!file.isFile || file.length() == 0L) return false
        file.inputStream().buffered().use { stream ->
            val head = ByteArray(64 * 1024)
            val n = stream.read(head).coerceAtLeast(0)
            if ((0 until n).any { head[it] == 0.toByte() }) return false
        }
        file.bufferedReader(Charsets.ISO_8859_1).useLines { lines ->
            return lines.take(PROBE_LINES).any { KEY_LINE.containsMatchIn(it) || DISC_LINE.containsMatchIn(it) }
        }
    }
}
