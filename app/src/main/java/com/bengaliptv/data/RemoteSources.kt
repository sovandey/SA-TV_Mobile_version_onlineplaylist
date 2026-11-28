package com.bengaliptv.data

import android.content.Context
import com.bengaliptv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.util.concurrent.TimeUnit

/** Settings you control from GitHub (playlist/config.json). */
data class RemoteConfig(
    val latestVersionCode: Int = 0,
    val minVersionCode: Int = 0,
    val latestVersionName: String = "",
    val apkUrl: String = "",
    val notes: String = "",
    val autoSources: Boolean = true,
    val blocked: Set<String> = emptySet()
)

/** Loads Sports / Kids / Hindi TV and FM radio lists from the internet (cached for offline use). */
object RemoteSources {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun fetch(url: String): String? = try {
        val req = Request.Builder().url(url).header("User-Agent", "SA-TV/1.0 (Android)").build()
        client.newCall(req).execute().use { r ->
            if (r.isSuccessful) r.body?.string()?.takeIf { it.isNotBlank() } else null
        }
    } catch (e: Exception) {
        null
    }

    private fun cached(context: Context, key: String, urls: List<String>, useCache: Boolean = true): String? {
        val file = File(context.cacheDir, "src_$key.txt")
        for (u in urls) {
            val text = fetch(u)
            if (text != null) {
                runCatching { file.writeText(text) }
                return text
            }
        }
        return if (useCache && file.exists()) runCatching { file.readText() }.getOrNull() else null
    }

    private fun raw(path: String) = "https://raw.githubusercontent.com/${BuildConfig.REPO}/HEAD/$path"

    /** playlist/config.json from your GitHub repo (cached for offline use). */
    fun loadConfig(context: Context, useCache: Boolean = true): RemoteConfig? {
        val text = cached(context, "config", listOf(raw("playlist/config.json")), useCache) ?: return null
        return try {
            val o = org.json.JSONObject(text)
            val blocked = HashSet<String>()
            o.optJSONArray("blocked")?.let { a ->
                for (i in 0 until a.length()) blocked += a.optString(i).trim().lowercase()
            }
            RemoteConfig(
                latestVersionCode = o.optInt("latestVersionCode", 0),
                minVersionCode = o.optInt("minVersionCode", 0),
                latestVersionName = o.optString("latestVersionName"),
                apkUrl = o.optString("apkUrl"),
                notes = o.optString("notes"),
                autoSources = o.optBoolean("autoSources", true),
                blocked = blocked
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * playlist/channels.m3u from your GitHub repo. Same channel name on several lines =
     * backup links; the player tries the next one when a link fails.
     */
    fun loadCurated(context: Context): List<Channel>? {
        val text = cached(context, "curated", listOf(raw("playlist/channels.m3u"))) ?: return null
        val all = M3uParser.parse(text, PlaylistSource("SA-TV playlist", "github", 0))
        if (all.isEmpty()) return null
        val byName = LinkedHashMap<String, MutableList<Channel>>()
        for (c in all) byName.getOrPut(c.name.lowercase().trim()) { mutableListOf() }.add(c)
        return byName.values.map { g ->
            g.first().copy(backupUrls = g.drop(1).map { it.url }.distinct())
        }
    }

    private fun playlist(context: Context, key: String, url: String, category: String, limit: Int): List<Channel> {
        val text = cached(context, key, listOf(url)) ?: return emptyList()
        fun rank(c: Channel): Int {
            val id = c.id.orEmpty().lowercase()
            return when {
                id.contains(".in") -> 0
                id.contains(".bd") -> 1
                else -> 2
            }
        }
        return M3uParser.parse(text, PlaylistSource(category, url, 0))
            .filter { !it.name.contains("Geo-blocked", true) && !it.name.contains("Not 24/7", true) }
            .distinctBy { it.url }
            .distinctBy { it.name.lowercase().trim() }
            .sortedBy { rank(it) }
            .take(limit)
            .map { it.copy(group = category) }
    }

    private fun radio(context: Context): List<Channel> {
        val hosts = listOf(
            "https://de1.api.radio-browser.info",
            "https://nl1.api.radio-browser.info",
            "https://at1.api.radio-browser.info"
        )
        val queries = listOf(
            "bn" to "language=bengali&limit=80",
            "bd" to "countrycode=BD&limit=40",
            "in" to "countrycode=IN&tag=bengali&limit=20"
        )
        val out = LinkedHashMap<String, Channel>()
        for ((key, q) in queries) {
            val urls = hosts.map { "$it/json/stations/search?$q&hidebroken=true&order=clickcount&reverse=true" }
            val text = cached(context, "radio_$key", urls) ?: continue
            try {
                val arr = JSONArray(text)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val url = o.optString("url_resolved").ifBlank { o.optString("url") }
                    val name = o.optString("name").trim()
                    if (url.isBlank() || name.isBlank()) continue
                    out.putIfAbsent(
                        name.lowercase(),
                        Channel(
                            id = o.optString("stationuuid"),
                            name = name,
                            url = url,
                            logo = o.optString("favicon").ifBlank { null },
                            group = "FM Radio",
                            country = o.optString("countrycode"),
                            language = o.optString("language")
                        )
                    )
                }
            } catch (e: Exception) {
                // ignore a bad response
            }
        }
        return out.values.take(120)
    }

    suspend fun loadAll(context: Context, onResult: (List<Channel>) -> Unit) = coroutineScope {
        val jobs = listOf(
            async(Dispatchers.IO) {
                playlist(context, "sports", "https://iptv-org.github.io/iptv/categories/sports.m3u", "Sports", 60)
            },
            async(Dispatchers.IO) {
                playlist(context, "kids", "https://iptv-org.github.io/iptv/categories/kids.m3u", "Kids", 50)
            },
            async(Dispatchers.IO) {
                playlist(context, "hindi", "https://iptv-org.github.io/iptv/languages/hin.m3u", "Hindi", 100)
            },
            async(Dispatchers.IO) { radio(context) }
        )
        for (job in jobs) {
            val r = job.await()
            withContext(Dispatchers.Main) { onResult(r) }
        }
    }
}
