package io.github.pierrebog.lasermediaplayer.settings

import android.content.Context
import android.content.res.Resources
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.ConfigurationCompat
import androidx.core.os.LocaleListCompat
import io.github.pierrebog.lasermediaplayer.disc.DiscKind
import io.github.pierrebog.lasermediaplayer.disc.Tmdb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

enum class ThemeMode { SYSTEM, DARK, LIGHT }

/** The interface languages: the system's, or one of the app's translations,
 * each named in itself and listed in that order. */
enum class AppLanguage(val tag: String, val nativeName: String) {
    SYSTEM("", ""),
    GERMAN("de", "Deutsch"),
    ENGLISH("en", "English"),
    SPANISH("es", "Español"),
    FRENCH("fr", "Français"),
    ITALIAN("it", "Italiano"),
    DUTCH("nl", "Nederlands"),
    PORTUGUESE("pt", "Português"),
}

/**
 * The tracks a disc starts with when nothing is remembered for it, and the
 * hardware decoding of each kind of video disc.
 *
 * @param audio [TrackPref.ORIGINAL], [TrackPref.INTERFACE] or an ISO 639-1 code
 * @param subtitles [TrackPref.NONE], [TrackPref.INTERFACE] or an ISO 639-1 code
 * @param noSubtitlesInInterfaceLanguage no subtitles when the audio track is
 *        in the interface's language, whatever [subtitles] says
 */
data class PlaybackDefaults(
    val audio: String = TrackPref.ORIGINAL,
    val subtitles: String = TrackPref.INTERFACE,
    val noSubtitlesInInterfaceLanguage: Boolean = true,
    val hwVcd: Boolean = true,
    val hwDvd: Boolean = true,
    val hwBluray: Boolean = true,
    /** The arrow pad over a DVD's or a Blu-ray's menu, on a touch screen. */
    val menuArrows: Boolean = true,
)

object TrackPref {
    const val ORIGINAL = "original"
    const val INTERFACE = "interface"
    const val NONE = "none"
}

/** What TMDB said of the key last time it was checked. */
enum class KeyStatus { NONE, CHECKING, VALID, REFUSED, UNVERIFIED }

/**
 * The online lookups. Off, nothing is asked on the network and no cover or
 * poster is shown; the names already found and kept are still shown.
 *
 * @param tmdbKey the user's TMDB API key: the app ships without one, and
 *        without one films are not looked up
 */
data class LookupSettings(
    val albums: Boolean = true,
    val films: Boolean = true,
    val tmdbKey: String = "",
    val keyStatus: KeyStatus = KeyStatus.NONE,
) {
    /** True if CDs and DVD-Audios may be looked up on MusicBrainz. */
    val albumsOnline: Boolean get() = albums
    /** True if films may be looked up on TMDB: on, with a key. */
    val filmsOnline: Boolean get() = films && tmdbKey.isNotEmpty()

    /** False when the disc's lookup is off: its screens show no cover or
     * poster, nor a case. */
    fun showsPicture(kind: DiscKind): Boolean = when (kind) {
        DiscKind.CD_AUDIO, DiscKind.DVD_AUDIO -> albums
        DiscKind.DVD_VIDEO, DiscKind.BLURAY -> films
        else -> true
    }
}

/** The interface's language: the one chosen in the options, else the system's. */
fun interfaceLocale(): Locale {
    val chosen = AppCompatDelegate.getApplicationLocales()
    val locale = if (!chosen.isEmpty) chosen[0]
                 else ConfigurationCompat.getLocales(Resources.getSystem().configuration)[0]
    return locale ?: Locale.getDefault()
}

/**
 * The user's options. The interface language is kept by AppCompat (the
 * system's per-app language from Android 13, its own storage before), the
 * rest in the preferences.
 */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(
        runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: "") }
            .getOrDefault(ThemeMode.SYSTEM))
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    fun setTheme(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.name).apply()
        _theme.value = mode
    }

    private val _playback = MutableStateFlow(loadPlayback())
    val playback: StateFlow<PlaybackDefaults> = _playback.asStateFlow()

    fun updatePlayback(change: (PlaybackDefaults) -> PlaybackDefaults) {
        _playback.update(change)
        val p = _playback.value
        prefs.edit()
            .putString(KEY_AUDIO, p.audio)
            .putString(KEY_SUBTITLES, p.subtitles)
            .putBoolean(KEY_NO_SUBS, p.noSubtitlesInInterfaceLanguage)
            .putBoolean(KEY_HW_VCD, p.hwVcd)
            .putBoolean(KEY_HW_DVD, p.hwDvd)
            .putBoolean(KEY_HW_BLURAY, p.hwBluray)
            .putBoolean(KEY_MENU_ARROWS, p.menuArrows)
            .apply()
    }

    private fun loadPlayback(): PlaybackDefaults {
        val d = PlaybackDefaults()
        return PlaybackDefaults(
            audio = prefs.getString(KEY_AUDIO, null) ?: d.audio,
            subtitles = prefs.getString(KEY_SUBTITLES, null) ?: d.subtitles,
            noSubtitlesInInterfaceLanguage = prefs.getBoolean(KEY_NO_SUBS, d.noSubtitlesInInterfaceLanguage),
            hwVcd = prefs.getBoolean(KEY_HW_VCD, d.hwVcd),
            hwDvd = prefs.getBoolean(KEY_HW_DVD, d.hwDvd),
            hwBluray = prefs.getBoolean(KEY_HW_BLURAY, d.hwBluray),
            menuArrows = prefs.getBoolean(KEY_MENU_ARROWS, d.menuArrows),
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _lookups = MutableStateFlow(LookupSettings(
        albums = prefs.getBoolean(KEY_ALBUMS, true),
        films = prefs.getBoolean(KEY_FILMS, true),
        tmdbKey = prefs.getString(KEY_TMDB, null).orEmpty(),
        keyStatus = runCatching { KeyStatus.valueOf(prefs.getString(KEY_TMDB_STATUS, null) ?: "") }
            .getOrDefault(KeyStatus.NONE).let { if (it == KeyStatus.CHECKING) KeyStatus.UNVERIFIED else it },
    ))
    val lookups: StateFlow<LookupSettings> = _lookups.asStateFlow()

    fun setAlbumLookup(on: Boolean) {
        prefs.edit().putBoolean(KEY_ALBUMS, on).apply()
        _lookups.update { it.copy(albums = on) }
    }

    fun setFilmLookup(on: Boolean) {
        prefs.edit().putBoolean(KEY_FILMS, on).apply()
        _lookups.update { it.copy(films = on) }
    }

    /** Keeps [key] - empty: removes it - and checks it with TMDB. */
    fun setTmdbKey(key: String) {
        val trimmed = key.trim()
        val status = if (trimmed.isEmpty()) KeyStatus.NONE else KeyStatus.CHECKING
        prefs.edit().putString(KEY_TMDB, trimmed).putString(KEY_TMDB_STATUS, status.name).apply()
        _lookups.update { it.copy(tmdbKey = trimmed, keyStatus = status) }
        if (trimmed.isEmpty()) return
        scope.launch {
            val checked = when (Tmdb.check(trimmed)) {
                true -> KeyStatus.VALID
                false -> KeyStatus.REFUSED
                null -> KeyStatus.UNVERIFIED
            }
            /* Unless the key changed meanwhile. */
            if (_lookups.value.tmdbKey != trimmed) return@launch
            prefs.edit().putString(KEY_TMDB_STATUS, checked.name).apply()
            _lookups.update { it.copy(keyStatus = checked) }
        }
    }

    /** The language chosen in the app, SYSTEM when there is none. */
    val language: AppLanguage
        get() {
            val locales = AppCompatDelegate.getApplicationLocales()
            if (locales.isEmpty) return AppLanguage.SYSTEM
            val code = locales[0]?.language
            return AppLanguage.entries.firstOrNull { it != AppLanguage.SYSTEM && it.tag == code }
                ?: AppLanguage.SYSTEM
        }

    /** Recreates the activity, in the new language. */
    fun setLanguage(language: AppLanguage) {
        AppCompatDelegate.setApplicationLocales(
            if (language == AppLanguage.SYSTEM) LocaleListCompat.getEmptyLocaleList()
            else LocaleListCompat.forLanguageTags(language.tag))
    }

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_AUDIO = "audio"
        const val KEY_SUBTITLES = "subtitles"
        const val KEY_NO_SUBS = "no_subs_in_interface_language"
        const val KEY_HW_VCD = "hw_vcd"
        const val KEY_HW_DVD = "hw_dvd"
        const val KEY_MENU_ARROWS = "menu_arrows"
        const val KEY_HW_BLURAY = "hw_bluray"
        const val KEY_ALBUMS = "lookup_albums"
        const val KEY_FILMS = "lookup_films"
        const val KEY_TMDB = "tmdb_key"
        const val KEY_TMDB_STATUS = "tmdb_key_status"
    }
}
