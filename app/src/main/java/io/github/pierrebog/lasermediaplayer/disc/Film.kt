package io.github.pierrebog.lasermediaplayer.disc

import android.content.Context
import android.util.Log
import io.github.pierrebog.lasermediaplayer.LASER_TAG
import io.github.pierrebog.lasermediaplayer.settings.AppSettings
import io.github.pierrebog.lasermediaplayer.settings.interfaceLocale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** The film on a video disc, as TMDB knows it, in the interface's [language];
 * [originalLanguage] is ISO 639-1, as TMDB gives it. A [series]' disc may
 * have its [episodes] named: by their title, as [EpisodeInfo.title] gives
 * it. */
data class FilmInfo(val title: String, val year: String?, val poster: File?, val language: String,
                    val originalLanguage: String? = null, val series: Boolean = false,
                    val episodes: Map<Int, EpisodeName> = emptyMap())

/**
 * Finds the film on a DVD or a Blu-ray, for the disc screen: a decoration,
 * so nothing is asked of the user, and a doubtful match is no match.
 *
 * The query is the disc's title - a Blu-ray's own, or a DVD's volume label
 * cleaned of its disc numbers, formats and regions. TMDB's candidates are
 * checked against the main title's length, which laser.c reads from the
 * disc: a film whose running time does not match is not the one.
 *
 * What is found is kept per disc - by the disc ID laser.c reads, else by the
 * title - and per language, TMDB's titles and posters being translated; so
 * is "nothing found", once TMDB has answered: a disc is looked up once in
 * each language. A failed request is tried again next time, and so is a
 * poster that could not be downloaded.
 *
 * A series' disc of several episodes has them named, when the season and
 * which of its episodes the disc holds can be told: see [Tmdb.findShow]. A
 * disc of episodes kept from an older lookup is looked up again once.
 *
 * Kept in the app's cache, the poster beside what was found: Android may
 * empty it, and the disc is then looked up again. What matters of a disc -
 * where its playback stopped, its tracks - is in ResumeStore.
 *
 * TMDB is asked only when the lookup is on and the user gave a key; what
 * is kept is used either way.
 */
class FilmLookup(context: Context, private val settings: AppSettings) {

    private val dir = movedToCache(context, "films")

    /** The film on [disc], or null; never throws. */
    suspend fun find(disc: DiscInfo): FilmInfo? = withContext(Dispatchers.IO) {
        val language = language()
        val id = storeId(disc) + "-" + language
        load(id, language, disc.episodes.size >= 2)?.let { (film, posterUrl) ->
            Log.d(LASER_TAG, "film $id: known, ${film?.title ?: "nothing found"}")
            if (film == null || film.poster != null || posterUrl == null ||
                !settings.lookups.value.filmsOnline)
                return@withContext film
            /* Its poster could not be downloaded last time. */
            return@withContext film.copy(poster = Http.download(posterUrl, File(dir, "$id.jpg")))
        }
        val lookups = settings.lookups.value
        if (!lookups.filmsOnline) return@withContext null
        val key = lookups.tmdbKey

        val match = try {
            lookUp(disc, language, key)
        } catch (e: Exception) {
            Log.w(LASER_TAG, "film lookup failed", e)
            return@withContext null
        }
        if (match == null) {
            save(id, null)
            return@withContext null
        }
        val poster = match.posterUrl?.let { Http.download(it, File(dir.apply { mkdirs() }, "$id.jpg")) }
        val film = FilmInfo(match.title, match.year, poster, language, match.originalLanguage,
                            match.series, match.episodes)
        save(id, film, match.posterUrl)
        film
    }

    /**
     * A TV series when the title names a season ("FRIENDS_S1_D2"), else a
     * film, and failing that a series: a box set of episodes has no film's
     * length.
     *
     * @throws Exception if TMDB cannot be reached
     */
    private fun lookUp(disc: DiscInfo, language: String, key: String): TmdbMatch? {
        showOf(disc.title)?.let { (name, season) ->
            return Tmdb.findShow(name, season, language, key, disc.episodes,
                                 discNumberOf(disc.title)).also {
                Log.d(LASER_TAG, "series \"$name\" season $season: ${it?.title ?: "no match"}")
            }
        }

        val queries = queriesOf(disc.title)
        val discNumber = discNumberOf(disc.title)
        if (queries.isEmpty()) {
            Log.d(LASER_TAG, "film: no usable title in \"${disc.title}\"")
            return null
        }
        /* One side, or part, of a film split over the disc: looked up as the
         * film, named with the side. */
        val part = partOf(disc.title)
        for (query in queries) {
            val match = Tmdb.find(query, disc.mainLengthMs, language, key, partial = part != null)
            Log.d(LASER_TAG, "film \"$query\" (${disc.mainLengthMs / 60_000} min): " +
                             (match?.let { "${it.title} (${it.year})" } ?: "no match"))
            if (match != null) {
                /* A film whose name does not hold the query, words in order,
                 * gives way to a series named so: "Band of Brothers", a disc
                 * of its episodes, found "The Naked Brothers Band: Battle of
                 * the Bands" of about an episode's length. On a disc of
                 * episodes, so does a film not named the query exactly, to
                 * a series that is: "Frères d'Armes : l'héritage", of an
                 * episode's length, for discs 2 and 5 of the same. */
                val episodes = disc.episodes.size >= 2
                if (!match.exactName || (episodes && !match.named)) {
                    val show = Tmdb.findShow(query, null, language, key, disc.episodes, discNumber)
                    if (show != null && (if (match.exactName) show.named else show.exactName)) {
                        Log.d(LASER_TAG, "series \"$query\": ${show.title}, named so rather than the film")
                        return show
                    }
                    Log.d(LASER_TAG, "series \"$query\": ${show?.title ?: "no match"}, the film kept")
                }
                return match.copy(title = match.title + part?.let { " – $it" }.orEmpty())
            }
        }
        return Tmdb.findShow(queries.first(), null, language, key, disc.episodes, discNumber).also {
            Log.d(LASER_TAG, "series \"${queries.first()}\": ${it?.title ?: "no match"}")
        }
    }

    private fun storeId(disc: DiscInfo): String = disc.discId ?: shortHash(disc.key)

    /** What is kept for a disc: its film - null if nothing was found - and
     * its poster's address. */
    private data class Stored(val film: FilmInfo?, val posterUrl: String?)

    /** null: never looked up - or, for a disc that may hold [episodes],
     * looked up by an older version than LOOKUP_VERSION. */
    private fun load(id: String, language: String, episodes: Boolean): Stored? {
        val file = File(dir, "$id.json")
        if (!file.isFile) return null
        return try {
            val o = JSONObject(file.readText())
            if (episodes && o.optInt("lookup") < LOOKUP_VERSION) null
            else if (o.optBoolean("none")) Stored(null, null)
            else Stored(FilmInfo(o.getString("title"),
                                 o.optString("year").ifEmpty { null },
                                 File(dir, "$id.jpg").takeIf { it.isFile }, language,
                                 o.optString("original").ifEmpty { null },
                                 o.optBoolean("series"),
                                 episodesOf(o.optJSONArray("episodes"))),
                        o.optString("poster").ifEmpty { null })
        } catch (_: Exception) {
            null
        }
    }

    private fun episodesOf(array: JSONArray?): Map<Int, EpisodeName> {
        array ?: return emptyMap()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            .associate { it.getInt("title") to EpisodeName(it.getInt("number"), it.optString("name")) }
    }

    private fun save(id: String, film: FilmInfo?, posterUrl: String? = null) {
        val o = if (film == null) JSONObject().put("none", true)
                else JSONObject().put("title", film.title).put("year", film.year ?: "")
                    .put("original", film.originalLanguage ?: "")
                    .put("poster", posterUrl ?: "")
                    .put("series", film.series)
        /* The episodes were looked for, as this version looks: see
         * LOOKUP_VERSION. */
        o.put("lookup", LOOKUP_VERSION)
        o.put("episodes", JSONArray().apply {
            film?.episodes?.forEach { (title, e) ->
                put(JSONObject().put("title", title).put("number", e.number).put("name", e.name))
            }
        })
        try {
            /* The cache may have been emptied since the folder was made. */
            dir.mkdirs()
            writeAtomically(File(dir, "$id.json"), o.toString())
        } catch (e: Exception) {
            Log.w(LASER_TAG, "cannot save film $id", e)
        }
    }

    /**
     * The interface's language, as a BCP 47 tag: the one chosen in the
     * options, else the system's. TMDB answers in it.
     */
    fun language(): String = interfaceLocale().toLanguageTag()

    companion object {
        /* What a disc of episodes was looked up by, saved with it: a disc
         * looked up by an older version is looked up again. 2: a series
         * named exactly is preferred to a film that is not. */
        private const val LOOKUP_VERSION = 2

        /* Words a volume label adds to the film's name. A marker may be
         * followed by a number, dropped with it: D1, DISC_2, R2, DVD9. */
        private val MARKERS = setOf("D", "DISC", "DISK", "CD", "DVD", "BD", "R", "REGION")
        private val NOISE = setOf(
            "WS", "FS", "WIDESCREEN", "FULLSCREEN", "PAL", "NTSC", "SECAM",
            "SE", "CE", "LE", "SPECIAL", "EDITION", "COLLECTOR", "COLLECTORS", "UNRATED",
            "DIRECTORS", "DIRECTOR", "CUT", "EXTENDED", "BONUS", "FEATURE", "FEATURES",
            "BLURAY", "BLU", "RAY", "HD", "UHD", "4K", "3D",
            "FR", "FRA", "FRE", "FRENCH", "VF", "VOST", "VOSTFR", "EN", "ENG", "UK", "US",
            "DE", "GER", "ES", "SPA", "IT", "ITA", "NL",
        )
        /* What is left of a label that says nothing about the film. */
        private val GENERIC = setOf("", "VIDEO", "VIDEO TS", "MOVIE", "FILM", "NO NAME",
                                    "NONAME", "VOLUME", "UNTITLED", "DISC", "MAIN")

        private const val MAX_QUERIES = 3
        private val ARTICLES = setOf("THE", "AND", "LES", "DES", "UNE", "AUX", "DER", "DIE", "DAS")
        /* A season in a title: S1, S02D3, SEASON 3, SAISON 1, SERIES 2... */
        private val SEASON = Regex(
            """(?i)[\s_.\-]+(?:S|SEASON|SAISON|SERIES|STAFFEL|TEMPORADA|STAGIONE)[\s_.\-]*(\d{1,2})(?=$|[\s_.\-]|D\d|DVD|DISC|DISK)""")
        /* "SIDE A", "FACE B", "PART 1": a film split over two sides or discs. */
        private val PART = Regex("""\s+(SIDE|FACE|PART|PT)\s+([A-D]|\d{1,2})$""", RegexOption.IGNORE_CASE)

        /**
         * The queries to try for a disc's title, best first: the cleaned
         * title, then with "EP 4" spelled "EPISODE IV" as films are titled,
         * then shortened from the end - a label often adds to the name, and
         * the running time sorts out what a short query brings back.
         */
        fun queriesOf(title: String): List<String> {
            val base = queryOf(title)?.let { PART.replace(it, "") }?.takeIf { it.isNotBlank() }
                ?: return emptyList()
            val expanded = base.replace(Regex("""\bEP (\d{1,2})\b""")) {
                "EPISODE " + roman(it.groupValues[1].toInt())
            }
            /* Not ending on a number, an abbreviation or an article:
             * "STAR WARS EP", "FELLOWSHIP OF THE". */
            val words = base.split(' ')
            val shorter = (words.size - 1 downTo 2).map { words.take(it) }
                .filter { w -> w.last().length > 2 && !w.last().all { it.isDigit() } &&
                               w.last() !in ARTICLES }
                .map { it.joinToString(" ") }
            return (listOf(base, expanded) + shorter).distinct().take(MAX_QUERIES)
        }

        /**
         * A volume label as a title, when nothing better is known:
         * STAR_WARS_EP4_D1 reads "Star Wars Ep 4". A title already written
         * as such - a Blu-ray's own - is kept.
         */
        fun displayTitle(title: String): String {
            if (title.any { it.isLowerCase() }) return title
            return titleCase(queryOf(title) ?: return title)
        }

        /* "DISC 2", "DISK_3" in a title. */
        private val DISC_PART = Regex("""(?i)(?:^|[\s_.\-])(DISC|DISK)[\s_.\-]*(\d{1,2})(?=$|[\s_.\-])""")

        /** The title's "DISC 2", written "Disc 2", or null. */
        fun discPartOf(title: String): String? =
            DISC_PART.find(title)?.let { titleCase(it.groupValues[1]) + " " + it.groupValues[2] }

        /* A disc's number in a title: "D2", "DISC 3", "S01D2". */
        private val DISC_NUMBER = Regex("""(?i)(?:^|[\s_.\-]|S\d{1,2})(?:D|DISC|DISK)[\s_.\-]*(\d{1,2})(?=$|[\s_.\-])""")

        /** The disc's number in its set, as its title gives it, or null. */
        fun discNumberOf(title: String): Int? =
            DISC_NUMBER.find(title)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it >= 1 }

        /** The series and season a title names, or null. */
        fun showOf(title: String): Pair<String, Int>? {
            val match = SEASON.find(title) ?: return null
            val head = title.substring(0, match.range.first)
            /* "24": a name of digits only, which queryOf() takes for none. */
            val name = queryOf(head) ?: head.replace(Regex("""[_.\-]+"""), " ").trim()
            return if (name.isEmpty()) null else name to match.groupValues[1].toInt()
        }

        /** The side or part a title ends with, as "Side A", or null. */
        fun partOf(title: String): String? =
            queryOf(title)?.let { PART.find(it) }?.value?.trim()?.let(::titleCase)

        private fun titleCase(text: String): String =
            text.lowercase(Locale.getDefault()).split(' ').joinToString(" ") { word ->
                word.replaceFirstChar { it.titlecase(Locale.getDefault()) }
            }

        private fun roman(n: Int): String {
            if (n !in 1..39) return n.toString()
            val tens = "X".repeat(n / 10)
            return tens + listOf("", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX")[n % 10]
        }

        /** A search query from a disc's title, or null if it names nothing. */
        fun queryOf(title: String): String? {
            /* A Blu-ray's own title is written as such: kept as it is. */
            if (title.any { it.isLowerCase() } && '_' !in title)
                return title.replace(Regex("""\s*[-(\[]?\s*blu-?ray.*$""", RegexOption.IGNORE_CASE), "")
                    .trim().takeIf { it.length >= 2 }
            val spaced = title.uppercase(Locale.ROOT)
                .replace(Regex("""[_.\-]+"""), " ")
                /* Trailing digits apart - SPIDERMAN2, D1, R2 - not SE7EN's. */
                .replace(Regex("""(?<=[A-Z])(?=\d+(\s|$))"""), " ")
            val words = ArrayList<String>()
            var dropNumber = false
            for (word in spaced.split(' ').filter { it.isNotBlank() }) {
                when {
                    dropNumber && word.all { it.isDigit() } -> dropNumber = false
                    word in MARKERS -> dropNumber = true
                    word in NOISE -> dropNumber = false
                    else -> { words += word; dropNumber = false }
                }
            }
            val query = words.joinToString(" ")
            if (query in GENERIC || query.all { it.isDigit() || it == ' ' }) return null
            return query
        }
    }
}

/** What TMDB answered for a disc. [exactName]: its name, or its original
 * name, holds the query's words in order - "Fellowship of the Ring" in "The
 * Lord of the Rings: The Fellowship of the Ring", not "Band of Brothers" in
 * "The Naked Brothers Band: Battle of the Bands". [named]: one of them is
 * the query's words and no others - "Band of Brothers", not "Band of
 * Brothers: The Legacy". */
data class TmdbMatch(val title: String, val year: String?, val posterUrl: String?,
                     val originalLanguage: String? = null, val exactName: Boolean = true,
                     val series: Boolean = false, val episodes: Map<Int, EpisodeName> = emptyMap(),
                     val named: Boolean = false)

/**
 * TMDB's search, then each candidate's running time against the disc's.
 * This product uses the TMDB API but is not endorsed or certified by TMDB.
 */
object Tmdb {
    private const val API = "https://api.themoviedb.org/3"
    private const val IMAGES = "https://image.tmdb.org/t/p/w500"
    private const val CANDIDATES = 5
    /* A running time this close to the disc's is the film: no need to look
     * further. */
    private const val CLOSE_MS = 60_000L

    /**
     * The film [query] names, in [language], or null if no candidate fits.
     *
     * With the main title's length, the candidate whose running time is
     * closest to it, within max(6 min, 8 %): a disc's main title may hold
     * more or less of the credits. A PAL disc plays a film 4 % fast, so the
     * running time is also compared sped up: "Vaiana" (107 min) is 103 min
     * on a PAL DVD, closer to "Vaiana 2" (100 min) than to itself otherwise.
     * Of the candidates that fit, one named exactly the query comes first:
     * "Rio" (96 min, 92 min on a PAL DVD) over "Rio Corgo", which TMDB may list
     * before it in French. Then the closest; of candidates as close, the
     * first in TMDB's order. One named so and within a minute ends the
     * search.
     * Without it, the first candidate if its title holds all the query's
     * words: "Fellowship of the Ring" is "The Lord of the Rings: The
     * Fellowship of the Ring".
     * A [partial] disc holds a side or a part of the film: its main title
     * is shorter than the film, by up to three quarters.
     *
     * @throws Exception if TMDB cannot be reached
     */
    fun find(query: String, mainLengthMs: Long, language: String, key: String,
             partial: Boolean = false): TmdbMatch? {
        val search = JSONObject(get("$API/search/movie?query=${enc(query)}" +
                                    "&language=$language&include_adult=false", key))
        val results = search.optJSONArray("results") ?: return null
        val candidates = (0 until minOf(results.length(), CANDIDATES)).map { results.getJSONObject(it) }

        val chosen = if (mainLengthMs > 0) {
            val tolerance = max(6 * 60_000L, mainLengthMs * 8 / 100)
            fun runtimeOf(c: JSONObject): Long =
                JSONObject(get("$API/movie/${c.getInt("id")}?language=$language", key))
                    .optLong("runtime") * 60_000
            if (partial) {
                candidates.firstOrNull { c ->
                    val runtimeMs = runtimeOf(c)
                    runtimeMs > 0 && mainLengthMs >= runtimeMs / 4 &&
                        mainLengthMs <= runtimeMs + tolerance
                }
            } else {
                var best: JSONObject? = null
                var bestGap = Long.MAX_VALUE
                var bestNamed = false
                for (c in candidates) {
                    val runtimeMs = runtimeOf(c)
                    if (runtimeMs <= 0) continue
                    /* At 25 frames a second instead of 24: PAL. */
                    val gap = minOf(abs(runtimeMs - mainLengthMs),
                                    abs(runtimeMs * 24 / 25 - mainLengthMs))
                    val named = named(c.optString("title"), query) ||
                        named(c.optString("original_title"), query)
                    Log.d(LASER_TAG, "film \"$query\": candidate \"${c.optString("title")}\" (" +
                          "${c.optString("release_date").take(4)}), ${runtimeMs / 60_000} min" +
                          if (named) ", named so" else "")
                    if (gap > tolerance) continue
                    if (best == null || (named && !bestNamed) || (named == bestNamed && gap < bestGap)) {
                        best = c
                        bestGap = gap
                        bestNamed = named
                    }
                    if (bestNamed && bestGap <= CLOSE_MS) break
                }
                best
            }
        } else {
            candidates.firstOrNull()?.takeIf {
                holds(it.optString("title"), query) || holds(it.optString("original_title"), query)
            }
        } ?: return null

        return TmdbMatch(
            title = chosen.optString("title").ifEmpty { chosen.optString("original_title") },
            year = chosen.optString("release_date").take(4).ifEmpty { null },
            posterUrl = chosen.optString("poster_path").takeIf { it.startsWith("/") }?.let { IMAGES + it },
            originalLanguage = chosen.optString("original_language").ifEmpty { null },
            exactName = phrase(chosen.optString("title"), query) ||
                phrase(chosen.optString("original_title"), query),
            named = named(chosen.optString("title"), query) ||
                named(chosen.optString("original_title"), query),
        )
    }

    /**
     * The series [query] names: TMDB's first answer, if its name holds all
     * the query's words - a box set has no length to check it against. With
     * a [season], named and illustrated by that season when TMDB has it:
     * "Friends – Saison 1"; and the disc's [episodes] named after that
     * season's, when it can be told which they are: see [alignEpisodes].
     * [discNumber]: the disc's in the season's set, from its title.
     *
     * @throws Exception if TMDB cannot be reached
     */
    fun findShow(query: String, season: Int?, language: String, key: String,
                 episodes: List<EpisodeInfo> = emptyList(), discNumber: Int? = null): TmdbMatch? {
        val search = JSONObject(get("$API/search/tv?query=${enc(query)}" +
                                    "&language=$language&include_adult=false", key))
        val show = search.optJSONArray("results")?.optJSONObject(0)
            ?.takeIf { holds(it.optString("name"), query) || holds(it.optString("original_name"), query) }
            ?: return null
        val name = show.optString("name").ifEmpty { show.optString("original_name") }
        var title = name
        var year = show.optString("first_air_date").take(4).ifEmpty { null }
        var poster = show.optString("poster_path").takeIf { it.startsWith("/") }
        var episodeNames = emptyMap<Int, EpisodeName>()

        if (season != null) {
            /* A season TMDB does not know: the series alone. */
            val details = runCatching {
                JSONObject(get("$API/tv/${show.getInt("id")}/season/$season?language=$language", key))
            }.getOrNull()
            if (details != null) {
                title = "$name – " + details.optString("name").ifEmpty { "Season $season" }
                details.optString("air_date").take(4).takeIf { it.isNotEmpty() }?.let { year = it }
                details.optString("poster_path").takeIf { it.startsWith("/") }?.let { poster = it }
                if (episodes.size >= 2) episodeNames = nameEpisodes(episodes, details, discNumber)
            }
        } else if (episodes.size >= 2) {
            /* No season in the disc's title: a series of one season - a
             * miniseries, "BAND_OF_BROTHERS_DISC1" - has its episodes named
             * after it all the same. */
            runCatching { onlySeason(show.getInt("id"), language, key) }.getOrNull()
                ?.let { episodeNames = nameEpisodes(episodes, it, discNumber) }
        }
        return TmdbMatch(title, year, poster?.let { IMAGES + it },
                         show.optString("original_language").ifEmpty { null },
                         phrase(show.optString("name"), query) ||
                             phrase(show.optString("original_name"), query),
                         series = true, episodes = episodeNames,
                         named = named(show.optString("name"), query) ||
                             named(show.optString("original_name"), query))
    }

    /** The season of series [id], specials aside, if it has only one;
     * null otherwise. */
    private fun onlySeason(id: Int, language: String, key: String): JSONObject? {
        val seasons = JSONObject(get("$API/tv/$id?language=$language", key)).optJSONArray("seasons")
            ?: return null
        val numbers = (0 until seasons.length()).mapNotNull { seasons.optJSONObject(it) }
            .map { it.optInt("season_number") }.filter { it >= 1 }
        val only = numbers.singleOrNull() ?: return null
        return JSONObject(get("$API/tv/$id/season/$only?language=$language", key))
    }

    /* An episode's length against TMDB's runtime: this close, or this share
     * of it - its credits, a recap before it. */
    private const val EPISODE_GAP_MS = 4 * 60_000L
    private const val EPISODE_GAP_PERCENT = 15
    /* Of placements that fit, the closest is taken if it is closer than the
     * next by this much, on average. */
    private const val EPISODE_BETTER_MS = 2 * 60_000L

    /** The disc's [episodes], by title, named after those of the season
     * [details] gives; none if it cannot be told which they are. */
    private fun nameEpisodes(episodes: List<EpisodeInfo>, details: JSONObject,
                             discNumber: Int?): Map<Int, EpisodeName> {
        val list = details.optJSONArray("episodes") ?: return emptyMap()
        val season = (0 until list.length()).mapNotNull { list.optJSONObject(it) }
            .filter { it.optInt("episode_number") > 0 }
        val start = alignEpisodes(episodes.map { it.lengthMs },
                                  season.map { it.optLong("runtime") * 60_000 }, discNumber)
        Log.d(LASER_TAG, "episodes: ${episodes.size} on the disc, ${season.size} in the season, " +
                         "from ${start?.let { season[it].optInt("episode_number") } ?: "unknown"}")
        start ?: return emptyMap()
        return episodes.withIndex().associate { (i, e) ->
            val s = season[start + i]
            e.title to EpisodeName(s.optInt("episode_number"), s.optString("name"))
        }
    }

    /**
     * Where the disc's episodes, of [lengthsMs], start among the season's,
     * of [runtimesMs] - 0 when TMDB does not say: a placement where each
     * episode TMDB times is within EPISODE_GAP_MS or EPISODE_GAP_PERCENT of
     * its runtime, sped up or not (PAL). Of several, the one the disc's
     * number gives - as many episodes on each disc before it - else the
     * clearly closest, else none: a doubtful naming is no naming.
     */
    fun alignEpisodes(lengthsMs: List<Long>, runtimesMs: List<Long>, discNumber: Int?): Int? {
        val count = lengthsMs.size
        if (count == 0 || runtimesMs.size < count) return null
        /* The placements that fit: where, how many episodes timed, their
         * average gap. */
        val fits = (0..runtimesMs.size - count).mapNotNull { start ->
            var timed = 0
            var gaps = 0L
            for (i in 0 until count) {
                val runtime = runtimesMs[start + i]
                if (runtime <= 0) continue
                val gap = minOf(abs(runtime - lengthsMs[i]), abs(runtime * 24 / 25 - lengthsMs[i]))
                if (gap > max(EPISODE_GAP_MS, runtime * EPISODE_GAP_PERCENT / 100)) return@mapNotNull null
                timed++
                gaps += gap
            }
            Triple(start, timed, if (timed > 0) gaps / timed else 0L)
        }
        if (fits.size == 1) return fits[0].first
        if (discNumber != null) {
            val start = (discNumber - 1) * count
            if (fits.any { it.first == start }) return start
        }
        /* Every placement timed, the closest clearly so. */
        if (fits.size >= 2 && fits.all { it.second > 0 }) {
            val sorted = fits.sortedBy { it.third }
            if (sorted[1].third - sorted[0].third >= EPISODE_BETTER_MS) return sorted[0].first
        }
        return null
    }

    /** [title] has every word of [query], case and accents aside. */
    private fun holds(title: String, query: String): Boolean {
        val wanted = words(query)
        return wanted.isNotEmpty() && words(title).containsAll(wanted)
    }

    /** [title] is [query], case, accents and punctuation aside. */
    private fun named(title: String, query: String): Boolean {
        val wanted = words(query)
        return wanted.isNotEmpty() && words(title) == wanted
    }

    /** [title] has the words of [query] in a row, in order, case and accents
     * aside. */
    private fun phrase(title: String, query: String): Boolean {
        val wanted = words(query)
        return wanted.isNotEmpty() &&
            java.util.Collections.indexOfSubList(words(title), wanted) >= 0
    }

    private fun words(text: String): List<String> =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")
            .lowercase(Locale.ROOT)
            .split(Regex("""[^\p{L}\p{N}]+"""))
            .filter { it.isNotEmpty() }

    /**
     * True if TMDB accepts [key], false if it refuses it, null if it cannot
     * be reached.
     */
    fun check(key: String): Boolean? = try {
        val connection = Http.open("$API/configuration?api_key=${enc(key)}")
        try {
            when (connection.responseCode) {
                HttpURLConnection.HTTP_OK -> true
                HttpURLConnection.HTTP_UNAUTHORIZED -> false
                else -> null
            }
        } finally {
            connection.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    /* The key goes in the URL, as TMDB's v3 API wants it: no URL is ever
     * logged, nor shown in an exception's message. */
    private fun get(url: String, key: String): String {
        val separator = if ('?' in url) '&' else '?'
        val connection = Http.open("$url${separator}api_key=${enc(key)}", json = true)
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IllegalStateException("TMDB: HTTP $code")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun enc(text: String): String = URLEncoder.encode(text, "UTF-8")
}
