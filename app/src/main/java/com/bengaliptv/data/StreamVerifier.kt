package com.bengaliptv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Network-level preflight only: fetches the HLS manifest and, when possible,
 * its first media/key/map URI. Final acceptance should also require a successful
 * Media3 playback test because HTTP 200 alone does not prove playable video.
 */
object StreamVerifier {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun preflight(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).header("User-Agent", "BengalIPTV/1.0").build()
            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return@withContext false
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return@withContext false
                if (body.contains("#EXTM3U")) {
                    val child = body.lineSequence().map { it.trim() }
                        .firstOrNull { it.startsWith("http://") || it.startsWith("https://") }
                    if (child != null) {
                        val childReq = Request.Builder().url(child).header("User-Agent", "BengalIPTV/1.0").build()
                        client.newCall(childReq).execute().use { it.isSuccessful }
                    } else body.contains("#EXT-X-")
                } else true
            }
        } catch (_: Exception) { false }
    }
}
