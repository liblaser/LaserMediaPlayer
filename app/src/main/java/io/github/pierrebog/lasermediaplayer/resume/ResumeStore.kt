package io.github.pierrebog.lasermediaplayer.resume

import android.content.Context
import androidx.core.content.edit
import io.github.pierrebog.lasermediaplayer.disc.DiscInfo
import io.github.pierrebog.lasermediaplayer.disc.PlayMode

/**
 * Where playback of a disc stopped, so that the disc screen can offer to
 * resume it.
 *
 * @param title the libVLC title playing: a DVD or Blu-ray is a set of titles,
 *              and a time means nothing without its title. On an audio CD,
 *              the track index. -1 when the disc has none (VCD).
 * @param timeMs position in that title
 * @param lengthMs that title's length, for the progress bar
 * @param mode and [options]: how the disc was being played. Title numbers
 *             depend on it - dvdnav and dvdread, or the bluray demuxer with
 *             and without menus, do not number titles alike - so a point is
 *             resumed the same way it was saved.
 */
data class ResumePoint(
    val title: Int,
    val timeMs: Long,
    val lengthMs: Long,
    val mode: PlayMode = PlayMode.DIRECT,
    val options: List<String> = emptyList(),
)

/**
 * The tracks to play, by track id (see AudioTrackInfo). A null id leaves the
 * track to libVLC (or to the disc's menu), unless [subtitlesOff].
 */
data class TrackChoice(
    val audioId: Int?,
    val subtitleId: Int?,
    /** VCD: one channel on both speakers, for discs with one language per
     * channel. */
    val channel: AudioChannel = AudioChannel.STEREO,
    /** No subtitles: chosen ("None"), or none played last time. */
    val subtitlesOff: Boolean = false,
)

enum class AudioChannel { STEREO, LEFT, RIGHT }

/**
 * Per-disc memory, keyed by [DiscInfo.key], in SharedPreferences: a few
 * values per disc, no query needed.
 */
class ResumeStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("discs", Context.MODE_PRIVATE)

    /* A DVD's versions of the film each have their own point. */
    private fun pointKey(disc: DiscInfo, version: Int?): String =
        if (version != null) "${disc.key}|v$version" else disc.key

    /** Where playback of [disc] stopped - in [version], by its title
     * number, when the disc has versions. */
    fun resumePoint(disc: DiscInfo, version: Int? = null): ResumePoint? {
        val k = pointKey(disc, version)
        val time = prefs.getLong("$k|time", -1)
        if (time < 0) return null
        val mode = prefs.getString("$k|mode", null)
            ?.let { runCatching { PlayMode.valueOf(it) }.getOrNull() } ?: PlayMode.DIRECT
        val options = prefs.getString("$k|options", null)
            ?.split('\n')?.filter { it.isNotEmpty() }.orEmpty()
        return ResumePoint(prefs.getInt("$k|title", -1), time, prefs.getLong("$k|length", 0),
                           mode, options)
    }

    /**
     * Saves where playback stopped. Near the start or the end there is
     * nothing worth resuming: the point is forgotten instead.
     */
    fun savePoint(disc: DiscInfo, point: ResumePoint, version: Int? = null) {
        val k = pointKey(disc, version)
        val worthKeeping = point.timeMs > MIN_RESUME_MS &&
                (point.lengthMs <= 0 || point.lengthMs - point.timeMs > END_MARGIN_MS)
        prefs.edit {
            if (worthKeeping) {
                putInt("$k|title", point.title)
                putLong("$k|time", point.timeMs)
                putLong("$k|length", point.lengthMs)
                putString("$k|mode", point.mode.name)
                putString("$k|options", point.options.joinToString("\n"))
            } else {
                for (field in listOf("title", "time", "length", "mode", "options"))
                    remove("$k|$field")
            }
        }
    }

    /** True once tracks were chosen, or played, for [disc]. */
    fun hasTrackChoice(disc: DiscInfo): Boolean = prefs.contains("${disc.key}|audio")

    fun trackChoice(disc: DiscInfo): TrackChoice {
        val k = disc.key
        return TrackChoice(
            prefs.getInt("$k|audio", -1).takeIf { it >= 0 },
            prefs.getInt("$k|sub", -1).takeIf { it >= 0 },
            prefs.getString("$k|channel", null)
                ?.let { runCatching { AudioChannel.valueOf(it) }.getOrNull() }
                ?: AudioChannel.STEREO,
            prefs.getBoolean("$k|suboff", false),
        )
    }

    fun saveTrackChoice(disc: DiscInfo, choice: TrackChoice) {
        val k = disc.key
        prefs.edit {
            putInt("$k|audio", choice.audioId ?: -1)
            putInt("$k|sub", choice.subtitleId ?: -1)
            putString("$k|channel", choice.channel.name)
            putBoolean("$k|suboff", choice.subtitleId == null && choice.subtitlesOff)
        }
    }

    /** The version chosen for [disc], by its title number; null if none
     * was. */
    fun version(disc: DiscInfo): Int? =
        prefs.getInt("${disc.key}|version", -1).takeIf { it >= 1 }

    fun saveVersion(disc: DiscInfo, title: Int) {
        prefs.edit { putInt("${disc.key}|version", title) }
    }

    /** True if the picture was last made to fill the screen, cropped, for
     * [disc]. */
    fun fillScreen(disc: DiscInfo): Boolean = prefs.getBoolean("${disc.key}|fill", false)

    fun saveFillScreen(disc: DiscInfo, fill: Boolean) {
        prefs.edit { putBoolean("${disc.key}|fill", fill) }
    }

    private companion object {
        const val MIN_RESUME_MS = 30_000L
        const val END_MARGIN_MS = 120_000L
    }
}
