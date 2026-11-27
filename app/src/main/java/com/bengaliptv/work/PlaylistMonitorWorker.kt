package com.bengaliptv.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bengaliptv.data.Sources
import com.bengaliptv.data.M3uParser
import com.bengaliptv.data.StreamVerifier
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class PlaylistMonitorWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS).build()
        return try {
            // Refresh source snapshots. Production version should persist parsed data in Room
            // and run per-channel recovery only for channels the user has enabled.
            Sources.all.forEach { source ->
                val request = Request.Builder().url(source.url).header("User-Agent", "BengalIPTV/1.0").build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@forEach
                    val text = response.body?.string().orEmpty()
                    M3uParser.parse(text, source)
                }
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
