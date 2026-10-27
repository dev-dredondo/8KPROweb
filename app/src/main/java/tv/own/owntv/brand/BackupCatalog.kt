package tv.own.owntv.brand

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import tv.own.owntv.features.login.BackupAccount
import tv.own.owntv.features.login.BackupStore
import java.text.Normalizer
import java.util.concurrent.TimeUnit
import kotlin.math.ln

/**
 * 8K PRO: the backup provider's live channels, used **only** to offer an alternative when a channel
 * stops working. Nothing here is stored in the database or shown in any list: the catalogue lives in
 * memory, is fetched the first time it is needed and kept for [CACHE_MS].
 */
class BackupCatalog(private val store: BackupStore) {

    /** One backup channel offered in place of a dead one. [url] is never shown. */
    data class Alternative(val name: String, val url: String, val logoUrl: String?, val score: Double)

    private class Entry(val name: String, val streamId: Long, val logo: String?, val tokens: Set<String>, val key: String, val flat: String) {
        /** The name in lower case without accents, for the user's search. */
        val plain: String = Normalizer.normalize(name, Normalizer.Form.NFKD).lowercase().replace(Regex("\\p{Mn}+"), "")
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .build()
    }
    private val mutex = Mutex()
    private var cacheFor: BackupAccount? = null
    private var cacheAt = 0L
    private var entries: List<Entry> = emptyList()
    /** How many backup channels use each word: "m", "plus" or "liga" are everywhere, "dazn" is not. */
    private var wordCount: Map<String, Int> = emptyMap()
    private var extension = "ts"

    /** Whether the panel gave this device a backup provider. */
    val configured: Boolean get() = store.current() != null

    /**
     * Backup channels whose name looks like [channelName], best first. Null when there is no backup
     * provider or it could not be reached; empty when nothing looks alike.
     */
    suspend fun alternatives(channelName: String, limit: Int = 8): List<Alternative>? = withContext(Dispatchers.IO) {
        val account = store.current() ?: return@withContext null
        val list = load(account) ?: return@withContext null
        val wanted = normalize(channelName)
        if (wanted.tokens.isEmpty()) return@withContext emptyList()
        list.asSequence()
            .map { it to score(wanted, it) }
            .filter { it.second >= MIN_SCORE }
            .sortedWith(compareByDescending<Pair<Entry, Double>> { it.second }.thenBy { it.first.name.length })
            .distinctBy { it.first.key }
            .take(limit)
            .map { (e, sc) -> Alternative(e.name, streamUrl(account, e.streamId), e.logo, sc) }
            .toList()
    }

    /**
     * The user's own search in the backup's channels: every word typed must appear in the name (in any
     * order, accents and case ignored). Null when there is no backup or it could not be reached.
     */
    suspend fun search(text: String, limit: Int = 40): List<Alternative>? = withContext(Dispatchers.IO) {
        val account = store.current() ?: return@withContext null
        val list = load(account) ?: return@withContext null
        val words = Normalizer.normalize(text, Normalizer.Form.NFKD).lowercase().replace(Regex("\\p{Mn}+"), "")
            .split(Regex("[^a-z0-9+]+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return@withContext emptyList()
        list.asSequence()
            .filter { e -> words.all { w -> e.plain.contains(w) } }
            .sortedBy { it.name.length }
            .take(limit)
            .map { e -> Alternative(e.name, streamUrl(account, e.streamId), e.logo, 0.0) }
            .toList()
    }

    private suspend fun load(account: BackupAccount): List<Entry>? = mutex.withLock {
        val fresh = cacheFor == account && SystemClock.elapsedRealtime() - cacheAt < CACHE_MS && entries.isNotEmpty()
        if (fresh) return entries
        runCatching {
            val base = "${account.serverUrl}/player_api.php?username=${Uri.encode(account.username)}&password=${Uri.encode(account.password)}"
            // Which container the account may use: .ts when allowed, otherwise HLS.
            extension = runCatching {
                val info = JSONObject(get(base)).optJSONObject("user_info")
                val formats = info?.optJSONArray("allowed_output_formats")
                val allowed = (0 until (formats?.length() ?: 0)).map { formats!!.optString(it) }
                if (allowed.isEmpty() || "ts" in allowed) "ts" else "m3u8"
            }.getOrDefault("ts")
            val arr = JSONArray(get("$base&action=get_live_streams"))
            val out = ArrayList<Entry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name").trim()
                val id = o.optLong("stream_id", -1)
                if (name.isEmpty() || id < 0) continue
                // "##### DEPORTES #####": a heading in the provider's list, not a channel — it plays nothing.
                if ('#' in name) continue
                val n = normalize(name)
                if (n.tokens.isEmpty()) continue
                out += Entry(name, id, o.optString("stream_icon").takeIf { it.startsWith("http") }, n.tokens, n.key, n.flat)
            }
            entries = out
            wordCount = out.flatMap { it.tokens }.groupingBy { it }.eachCount()
            cacheFor = account
            cacheAt = SystemClock.elapsedRealtime()
            out as List<Entry>
        }.onFailure { Log.w(TAG, "backup catalogue failed: ${it.javaClass.simpleName}") }.getOrNull()
    }

    private fun get(url: String): String =
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            resp.body.string()
        }

    private fun streamUrl(account: BackupAccount, streamId: Long): String =
        "${account.serverUrl}/live/${Uri.encode(account.username)}/${Uri.encode(account.password)}/$streamId.$extension"

    /** [flat]: the words run together in order, so "laSexta" and "La Sexta" meet. */
    private class Normalized(val tokens: Set<String>, val numbers: Set<String>, val key: String, val flat: String)

    /**
     * Channel names from two providers rarely match letter for letter: "ES: LA 1 FHD", "|ES| La 1 HD",
     * "LA 1 (1080p)". Lower case, no accents, no country prefix, no quality or codec words — what is
     * left is what the channel is called.
     */
    private fun normalize(raw: String): Normalized {
        var s = Normalizer.normalize(raw, Normalizer.Form.NFKD).lowercase().replace(Regex("\\p{Mn}+"), "")
        s = s.replace(Regex("^\\s*[\\[(|]?\\s*[a-z]{2,3}\\s*[\\])|:\\-]+"), " ") // "ES:", "|ES|", "[ES]", "ES -"
        s = s.replace(Regex("^\\s*[^\\s:]{1,3}\\s*:"), " ") // a package tag before a colon: "M+: DAZN LaLiga"
        s = s.replace("+", " plus ").replace("&", " y ")
        val words = s.split(Regex("[^a-z0-9]+"))
            .filter { it.isNotEmpty() && it !in NOISE && !(it.endsWith("fps") && it.dropLast(3).all(Char::isDigit)) }
            .map { WORD_NUMBERS[it] ?: it }
        val tokens = words.toSet()
        val numbers = tokens.filter { t -> t.all(Char::isDigit) }.toSet()
        return Normalized(tokens, numbers, tokens.sorted().joinToString(" "), words.joinToString(""))
    }

    /**
     * How alike two names are, 0..1. Words count when they are equal or one is the start of the other
     * ("liga" / "laliga"); the names run together also count when one contains the other
     * ("DAZN LaLiga" / "DAZN LaLiga TV"). Different numbers rule a channel out ("La 1" is never "La 2");
     * a number on one side only lowers the score, so "DAZN LaLiga 2" still shows up, below "DAZN LaLiga".
     */
    private fun score(a: Normalized, e: Entry): Double {
        if (a.key == e.key) return 1.0
        if (a.flat.isNotEmpty() && a.flat == e.flat) return 0.95
        val bNumbers = e.tokens.filter { t -> t.all(Char::isDigit) }.toSet()
        if (a.numbers.isNotEmpty() && bNumbers.isNotEmpty() && a.numbers.intersect(bNumbers).isEmpty()) return 0.0
        val numberFactor = if (a.numbers == bNumbers) 1.0 else 0.8
        val aWords = a.tokens.filter { t -> !t.all(Char::isDigit) }
        val bWords = e.tokens.filter { t -> !t.all(Char::isDigit) }
        if (aWords.isEmpty() || bWords.isEmpty()) return 0.0
        // Each word counts by how rare it is in the backup's list, so a channel is found by what makes it
        // that channel ("DAZN") rather than by what half the list shares ("M+", "liga").
        val total = entries.size.coerceAtLeast(1)
        fun weight(w: String) = ln((total + 1.0) / ((wordCount[w] ?: 0) + 1.0)) + 1.0
        val matchedA = aWords.sumOf { w -> weight(w) * bWords.maxOf { o -> wordMatch(w, o) } }
        val matchedB = bWords.sumOf { w -> weight(w) * aWords.maxOf { o -> wordMatch(w, o) } }
        val dice = (matchedA + matchedB) / (aWords.sumOf { weight(it) } + bWords.sumOf { weight(it) })
        val shorter = minOf(a.flat.length, e.flat.length)
        val contained = if (shorter >= 4 && (e.flat.contains(a.flat) || a.flat.contains(e.flat))) {
            0.55 + 0.4 * shorter / maxOf(a.flat.length, e.flat.length)
        } else 0.0
        return maxOf(dice, contained) * numberFactor
    }

    /** 1 for the same word, 0.8 when one starts the other or contains it ("liga" in "laliga"), 0.7 for one typo. */
    private fun wordMatch(x: String, y: String): Double = when {
        x == y -> 1.0
        minOf(x.length, y.length) >= 3 && (x.startsWith(y) || y.startsWith(x)) -> 0.8
        minOf(x.length, y.length) >= 4 && (x.contains(y) || y.contains(x)) -> 0.8
        minOf(x.length, y.length) >= 5 && oneEditApart(x, y) -> 0.7
        else -> 0.0
    }

    private fun oneEditApart(x: String, y: String): Boolean {
        if (kotlin.math.abs(x.length - y.length) > 1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < x.length && j < y.length) {
            if (x[i] == y[j]) { i++; j++; continue }
            if (++edits > 1) return false
            when {
                x.length > y.length -> i++
                x.length < y.length -> j++
                else -> { i++; j++ }
            }
        }
        return edits + (x.length - i) + (y.length - j) <= 1
    }

    private companion object {
        const val TAG = "BackupCatalog"
        const val CACHE_MS = 6 * 60 * 60 * 1000L
        const val MIN_SCORE = 0.55
        /** Words that say how a channel is delivered, not which channel it is. */
        val NOISE = setOf(
            "hd", "fhd", "uhd", "sd", "hq", "lq", "4k", "8k", "2k", "1080", "1080p", "1080i", "720", "720p", "576p", "480p",
            "hevc", "h265", "h264", "x265", "x264", "hdr", "sdr", "50", "60", "backup", "bk", "vip", "raw", "multi", "multiaudio",
            "es", "esp", "spa", "spain", "espana",
        )
        val WORD_NUMBERS = mapOf("uno" to "1", "dos" to "2", "tres" to "3", "cuatro" to "4", "cinco" to "5", "one" to "1", "two" to "2")
    }
}
