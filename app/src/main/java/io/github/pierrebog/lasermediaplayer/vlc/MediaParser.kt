package io.github.pierrebog.lasermediaplayer.vlc

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import androidx.core.net.toUri
import kotlin.time.Duration
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.videolan.libvlc.Media
import org.videolan.libvlc.interfaces.IMedia
import kotlin.coroutines.resume

/** How a parse ended, and what was read of it. */
sealed interface Parsed<out T> {
    data class Done<T>(val value: T) : Parsed<T>
    /** libVLC could not open the media. */
    data object Failed : Parsed<Nothing>
    /** libVLC's timeout ended the parse. */
    data object TimedOut : Parsed<Nothing>
    /** libVLC never ended it, past the safety timeout. */
    data object NeverEnded : Parsed<Nothing>
}

/**
 * Parses a media - a laser.c classification, the cdda module's track list -
 * and reads the items it lists.
 *
 * Parsed, not browsed: the parse ends - with a status - whatever happens, a
 * drive laser.c cannot open included, and libVLC applies the timeout itself,
 * interrupting the parse, which laser.c and liblaser then give up.
 * MediaBrowser ends only with a list of items, and cannot stop its parse.
 *
 * One parse at a time: its caller runs them one after the other. The media
 * is created, read and released on a thread of its own, [name].
 */
class MediaParser(private val engine: VlcEngine, name: String) {

    private val thread = HandlerThread(
        name, Process.THREAD_PRIORITY_DEFAULT + Process.THREAD_PRIORITY_LESS_FAVORABLE
    ).apply { start() }
    private val handler = Handler(thread.looper)

    /**
     * Parses [mrl], with [options], under libVLC's [timeoutMs] and, should
     * libVLC never end it, [safetyTimeout]. Once it is done, [read] is given
     * the items it lists, on the parser's thread; they are released after.
     * The media is released whatever happens, the caller cancelled included:
     * it holds the parse's input, and so the drive.
     */
    suspend fun <T> parse(mrl: String, options: List<String>, timeoutMs: Int,
                          safetyTimeout: Duration, read: (List<IMedia>) -> T): Parsed<T> {
        /* Touched on the handler thread only. */
        var media: Media? = null
        try {
            /* The parse's status, or null if libVLC never ended it. */
            val status = withTimeoutOrNull(safetyTimeout) {
                suspendCancellableCoroutine { cont ->
                    handler.post {
                        val m = Media(engine.libVlc, mrl.toUri())
                        options.forEach { m.addOption(it) }
                        media = m
                        /* Any thread: only resumes the caller. */
                        m.setEventListener { event ->
                            if (event.type == IMedia.Event.ParsedChanged && cont.isActive)
                                cont.resume(event.parsedStatus)
                        }
                        if (!m.parseAsync(IMedia.Parse.ParseNetwork, timeoutMs) && cont.isActive)
                            cont.resume(IMedia.ParsedStatus.Failed)
                    }
                }
            }
            return when (status) {
                null -> Parsed.NeverEnded
                IMedia.ParsedStatus.Timeout -> Parsed.TimedOut
                IMedia.ParsedStatus.Failed -> Parsed.Failed
                else -> Parsed.Done(onHandler {
                    val list = media?.subItems()
                    val items = if (list == null) emptyList() else try {
                        (0 until list.count).mapNotNull { list.getMediaAt(it) }
                    } finally {
                        list.release()
                    }
                    try {
                        read(items)
                    } finally {
                        items.forEach { it.release() }
                    }
                })
            }
        } finally {
            withContext(NonCancellable) {
                onHandler {
                    media?.setEventListener(null)
                    media?.release()
                    media = null
                }
            }
        }
    }

    /** Runs [block] on [handler]'s thread and returns its result - on the
     * calling thread if that one has quit. */
    private suspend fun <T> onHandler(block: () -> T): T =
        suspendCancellableCoroutine { cont ->
            val posted = handler.post {
                val result = runCatching(block)
                if (cont.isActive) cont.resumeWith(result)
            }
            if (!posted) {
                val result = runCatching(block)
                if (cont.isActive) cont.resumeWith(result)
            }
        }
}
