package io.github.pierrebog.lasermediaplayer.settings

import io.github.pierrebog.lasermediaplayer.disc.DiscInfo
import io.github.pierrebog.lasermediaplayer.resume.TrackChoice
import java.text.Collator
import java.util.IllformedLocaleException
import java.util.Locale
import java.util.MissingResourceException

/**
 * The languages offered for the default tracks, as ISO 639-1 codes: the
 * most common on discs sold in Europe, the Americas and Asia.
 */
private val TRACK_LANGUAGES = listOf(
    "fr", "en", "de", "es", "it", "nl", "pt", "ja",
    "uk", "ru", "pl", "cs", "hu", "sv", "da", "no", "fi", "tr", "el",
    "zh", "ko", "th", "he", "ar", "hi",
)

/** [TRACK_LANGUAGES] in the alphabetical order of their names in the
 * interface's language. */
fun trackLanguages(): List<String> {
    val ui = interfaceLocale()
    val collator = Collator.getInstance(ui)
    return TRACK_LANGUAGES.sortedWith(compareBy(collator) { languageNameInUi(it) })
}

/** [code]'s name in the interface's language: "Allemand", "Japonais". */
fun languageNameInUi(code: String): String {
    val ui = interfaceLocale()
    val name = languageLocale(code)?.getDisplayLanguage(ui) ?: code
    return name.replaceFirstChar { it.titlecase(ui) }
}

/**
 * The locale of a language code, ISO 639 ("fr", "fre", "fra"), or null if
 * [code] cannot be one. Locale(String) is deprecated, and Locale.of() is
 * API 36.
 */
fun languageLocale(code: String): Locale? = try {
    Locale.Builder().setLanguage(code).build()
} catch (e: IllformedLocaleException) {
    null
}

/**
 * The tracks [disc] starts with when nothing is remembered for it.
 *
 * Audio: the first track (as the disc orders them) in the wanted language,
 * a plain one before a commentary or an audio description; the disc's first
 * track when it has none, or when the original language is wanted and the
 * film was not identified.
 * Subtitles: the first plain track in the wanted language - never a forced
 * or a commentary one - else none; none too when the audio is in the
 * interface's language and [PlaybackDefaults.noSubtitlesInInterfaceLanguage].
 */
fun defaultTracks(disc: DiscInfo, defaults: PlaybackDefaults): TrackChoice {
    val ui = interfaceLocale().language

    val audioLanguage = when (defaults.audio) {
        TrackPref.ORIGINAL -> disc.film?.originalLanguage
        TrackPref.INTERFACE -> ui
        else -> defaults.audio
    }
    val audio = audioLanguage?.let { lang ->
        disc.audioTracks.filter { sameLanguage(it.language, lang) }
            .minByOrNull { if (it.extension <= 1) 0 else 1 }
    } ?: disc.audioTracks.firstOrNull()

    val subtitleLanguage = when (defaults.subtitles) {
        TrackPref.NONE -> null
        TrackPref.INTERFACE -> ui
        else -> defaults.subtitles
    }?.takeUnless {
        defaults.noSubtitlesInInterfaceLanguage && audio != null && sameLanguage(audio.language, ui)
    }
    val subtitle = subtitleLanguage?.let { lang ->
        disc.subtitleTracks
            .filter { sameLanguage(it.language, lang) && it.extension != FORCED &&
                      it.extension !in COMMENTARY }
            .minByOrNull { if (it.extension <= 1) 0 else 1 }
    }

    return TrackChoice(audio?.id, subtitle?.id, subtitlesOff = subtitle == null)
}

private const val FORCED = 9
private val COMMENTARY = 13..15

/** Two language codes - ISO 639-1, or 639-2 as a Blu-ray writes them - for
 * the same language. */
fun sameLanguage(a: String, b: String): Boolean {
    if (a.isEmpty() || b.isEmpty()) return false
    if (a.equals(b, ignoreCase = true)) return true
    val x = iso3(a) ?: return false
    return x == iso3(b)
}

private fun iso3(code: String): String? {
    val lower = code.lowercase(Locale.ROOT)
    BIBLIOGRAPHIC[lower]?.let { return it }
    /* Norwegian: discs write no, nb (Bokmål), sometimes nn (Nynorsk). */
    if (lower in NORWEGIAN) return "nor"
    return try {
        languageLocale(lower)?.isO3Language?.takeIf { it.isNotEmpty() }
    } catch (e: MissingResourceException) {
        null
    }
}

private val NORWEGIAN = setOf("no", "nb", "nn", "nor", "nob", "nno")

/* ISO 639-2 bibliographic codes, which discs use, and their terminology
 * twins, which Locale knows. */
private val BIBLIOGRAPHIC = mapOf(
    "fre" to "fra", "ger" to "deu", "dut" to "nld", "chi" to "zho", "cze" to "ces",
    "gre" to "ell", "per" to "fas", "rum" to "ron", "slo" to "slk", "ice" to "isl",
    "mac" to "mkd", "may" to "msa", "alb" to "sqi", "arm" to "hye", "baq" to "eus",
    "bur" to "mya", "geo" to "kat", "wel" to "cym", "tib" to "bod",
)
