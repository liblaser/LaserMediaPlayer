package io.github.pierrebog.lasermediaplayer.disc

import android.content.Context
import android.util.Log
import io.github.pierrebog.lasermediaplayer.LASER_TAG
import io.github.pierrebog.lasermediaplayer.settings.AppSettings
import io.github.pierrebog.lasermediaplayer.settings.interfaceLocale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** The film on a video disc, as TMDB knows it, in the interface's [language];
 * [originalLanguage] is ISO 639-1, as TMDB gives it. */
data class FilmInfo(val title: String, val year: String?, val poster: File?, val language: String,
                    val originalLanguage: String? = null)

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
 * TMDB is asked only when the lookup is on and the user gave a key; what
 * is kept is used either way.
 */
class FilmLookup(context: Context, private val settings: AppSettings) {

    private val dir = File(context.filesDir, "films")

    /** The film on [disc], or null; never throws. */
    suspend fun find(disc: DiscInfo): FilmInfo? = withContext(Dispatchers.IO) {
        val language = language()
        val id = storeId(disc) + "-" + language
        load(id, language)?.let {
            Log.d(LASER_TAG, "film $id: known, ${it.getOrNull()?.title ?: "nothing found"}")
            val film = it.getOrNull() ?: return@withContext null
            if (film.poster != null || !settings.lookups.value.filmsOnline)
                return@withContext film
            /* Its poster could not be downloaded last time. */
            val poster = storedPosterUrl(id)?.let { url ->
                Tmdb.download(url, File(dir, "$id.jpg"))
            }
            return@withContext film.copy(poster = poster)
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
        val poster = match.posterUrl?.let { Tmdb.download(it, File(dir.apply { mkdirs() }, "$id.jpg")) }
        val film = FilmInfo(match.title, match.year, poster, language, match.originalLanguage)
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
            return Tmdb.findShow(name, season, language, key).also {
                Log.d(LASER_TAG, "series \"$name\" season $season: ${it?.title ?: "no match"}")
            }
        }

        val queries = queriesOf(disc.title)
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
                 * the Bands" of about an episode's length. */
                if (!match.exactName) {
                    val show = Tmdb.findShow(query, null, language, key)
                    if (show?.exactName == true) {
                        Log.d(LASER_TAG, "series \"$query\": ${show.title}, named so rather than the film")
                        return show
                    }
                }
                return match.copy(title = match.title + part?.let { " – $it" }.orEmpty())
            }
        }
        return Tmdb.findShow(queries.first(), null, language, key).also {
            Log.d(LASER_TAG, "series \"${queries.first()}\": ${it?.title ?: "no match"}")
        }
    }

    private fun storeId(disc: DiscInfo): String =
        disc.discId ?: MessageDigest.getInstance("SHA-1").digest(disc.key.toByteArray())
            .take(10).joinToString("") { "%02x".format(it) }

    /** null: never looked up; a failed Result: looked up, nothing found. */
    private fun load(id: String, language: String): Result<FilmInfo>? {
        val file = File(dir, "$id.json")
        if (!file.isFile) return null
        return try {
            val o = JSONObject(file.readText())
            /* "Nothing found" by older rules: looked up again. */
            if (o.optBoolean("none") && o.optInt("rules", 1) < RULES) null
            else if (o.optBoolean("none")) Result.failure(NoSuchElementException())
            /* Found before the original language was kept: looked up again. */
            else if (!o.has("original")) null
            /* Found by older matching rules, which could take a sequel of
             * about the same length: looked up again, when it can be. */
            else if (o.optInt("match", 1) < MATCH && settings.lookups.value.filmsOnline) null
            /* Found without its poster before the poster's address was kept:
             * looked up again, for the address. */
            else if (!o.has("poster") && !File(dir, "$id.jpg").isFile &&
                     settings.lookups.value.filmsOnline) null
            else Result.success(FilmInfo(o.getString("title"),
                                         o.optString("year").ifEmpty { null },
                                         File(dir, "$id.jpg").takeIf { it.isFile }, language,
                                         o.optString("original").ifEmpty { null }))
        } catch (e: Exception) {
            null
        }
    }

    /** The address of the poster of the film kept as [id], if it has one. */
    private fun storedPosterUrl(id: String): String? = try {
        JSONObject(File(dir, "$id.json").readText()).optString("poster").ifEmpty { null }
    } catch (e: Exception) {
        null
    }

    private fun save(id: String, film: FilmInfo?, posterUrl: String? = null) {
        val o = if (film == null) JSONObject().put("none", true).put("rules", RULES)
                else JSONObject().put("title", film.title).put("year", film.year ?: "")
                    .put("original", film.originalLanguage ?: "")
                    .put("poster", posterUrl ?: "").put("match", MATCH)
        try {
            dir.mkdirs()
            val tmp = File(dir, "$id.json.tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(File(dir, "$id.json"))) tmp.delete()
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
        /* Bumped when the lookup finds more: discs found by none of the
         * earlier rules are looked up again. */
        private const val RULES = 4
        /* Bumped when the lookup matches better: films found by earlier
         * rules are looked up again. */
        private const val MATCH = 3
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
 * "The Naked Brothers Band: Battle of the Bands". */
data class TmdbMatch(val title: String, val year: String?, val posterUrl: String?,
                     val originalLanguage: String? = null, val exactName: Boolean = true)

/**
 * TMDB's search, then each candidate's running time against the disc's.
 * This product uses the TMDB API but is not endorsed or certified by TMDB.
 */
object Tmdb {
    private const val API = "https://api.themoviedb.org/3"
    private const val IMAGES = "https://image.tmdb.org/t/p/w500"
    private const val TIMEOUT_MS = 10_000
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
     * Of candidates as close, the first in TMDB's order; one within a minute
     * ends the search.
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
                for (c in candidates) {
                    val runtimeMs = runtimeOf(c)
                    if (runtimeMs <= 0) continue
                    /* At 25 frames a second instead of 24: PAL. */
                    val gap = minOf(abs(runtimeMs - mainLengthMs),
                                    abs(runtimeMs * 24 / 25 - mainLengthMs))
                    if (gap <= tolerance && gap < bestGap) {
                        best = c
                        bestGap = gap
                    }
                    if (bestGap <= CLOSE_MS) break
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
        )
    }

    /**
     * The series [query] names: TMDB's first answer, if its name holds all
     * the query's words - a box set has no length to check it against. With
     * a [season], named and illustrated by that season when TMDB has it:
     * "Friends – Saison 1".
     *
     * @throws Exception if TMDB cannot be reached
     */
    fun findShow(query: String, season: Int?, language: String, key: String): TmdbMatch? {
        val search = JSONObject(get("$API/search/tv?query=${enc(query)}" +
                                    "&language=$language&include_adult=false", key))
        val show = search.optJSONArray("results")?.optJSONObject(0)
            ?.takeIf { holds(it.optString("name"), query) || holds(it.optString("original_name"), query) }
            ?: return null
        val name = show.optString("name").ifEmpty { show.optString("original_name") }
        var title = name
        var year = show.optString("first_air_date").take(4).ifEmpty { null }
        var poster = show.optString("poster_path").takeIf { it.startsWith("/") }

        if (season != null) {
            /* A season TMDB does not know: the series alone. */
            val details = runCatching {
                JSONObject(get("$API/tv/${show.getInt("id")}/season/$season?language=$language", key))
            }.getOrNull()
            if (details != null) {
                title = "$name – " + details.optString("name").ifEmpty { "Season $season" }
                details.optString("air_date").take(4).takeIf { it.isNotEmpty() }?.let { year = it }
                details.optString("poster_path").takeIf { it.startsWith("/") }?.let { poster = it }
            }
        }
        return TmdbMatch(title, year, poster?.let { IMAGES + it },
                         show.optString("original_language").ifEmpty { null },
                         phrase(show.optString("name"), query) ||
                             phrase(show.optString("original_name"), query))
    }

    fun download(url: String, dest: File): File? {
        val tmp = File(dest.path + ".tmp")
        return try {
            val connection = open(url)
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
                connection.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            } finally {
                connection.disconnect()
            }
            if (tmp.renameTo(dest)) dest else null
        } catch (e: Exception) {
            Log.w(LASER_TAG, "cannot fetch poster $url", e)
            null
        } finally {
            tmp.delete()
        }
    }

    /** [title] has every word of [query], case and accents aside. */
    private fun holds(title: String, query: String): Boolean {
        val wanted = words(query)
        return wanted.isNotEmpty() && words(title).containsAll(wanted)
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
        val connection = open("$API/configuration?api_key=${enc(key)}")
        try {
            when (connection.responseCode) {
                HttpURLConnection.HTTP_OK -> true
                HttpURLConnection.HTTP_UNAUTHORIZED -> false
                else -> null
            }
        } finally {
            connection.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    /* The key goes in the URL, as TMDB's v3 API wants it: no URL is ever
     * logged, nor shown in an exception's message. */
    private fun get(url: String, key: String): String {
        val separator = if ('?' in url) '&' else '?'
        val connection = open("$url${separator}api_key=${enc(key)}")
        connection.setRequestProperty("Accept", "application/json")
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IllegalStateException("TMDB: HTTP $code")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            ExtraRoots.apply(this)
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }

    private fun enc(text: String): String = URLEncoder.encode(text, "UTF-8")
}
