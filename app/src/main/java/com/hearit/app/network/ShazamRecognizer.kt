package com.hearit.app.network

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

data class RecognizedTrack(
    val title: String,
    val artist: String,
    val album: String = "",
    val coverUrl: String = "",
    val shazamUrl: String = ""
) {
    fun spotifySearchUri(): String =
        "https://open.spotify.com/search/${Uri.encode("$title $artist")}"

    fun ytMusicSearchUrl(): String =
        "https://music.youtube.com/search?q=${Uri.encode("$title $artist")}"
}

object ShazamRecognizer {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    suspend fun recognize(signatureUri: String, sampleMs: Int): RecognizedTrack? =
        withContext(Dispatchers.IO) {
            val uuid1 = UUID.randomUUID().toString().uppercase()
            val uuid2 = UUID.randomUUID().toString().uppercase()
            val url = "https://amp.shazam.com/discovery/v5/en/IN/android/-/tag/$uuid1/$uuid2"

            val payload = JSONObject().apply {
                put("signature", JSONObject().apply {
                    put("uri", signatureUri)
                    put("samplems", sampleMs)
                })
                put("timestamp", System.currentTimeMillis())
                put("timezone", "Asia/Kolkata")
            }.toString()

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 14; SM-S918B Build/UP1A.231005.007)")
                .header("Content-Language", "en_IN")
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            runCatching {
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val bodyStr = resp.body?.string() ?: return@withContext null
                    val json = JSONObject(bodyStr)
                    val track = json.optJSONObject("track") ?: return@withContext null
                    val title = track.optString("title").takeIf { it.isNotBlank() } ?: return@withContext null
                    val artist = track.optString("subtitle", "Unknown Artist")
                    val images = track.optJSONObject("images")
                    var cover = images?.optString("coverarthq").orEmpty()
                    if (cover.isBlank()) cover = images?.optString("coverart").orEmpty()
                    val shazamUrl = track.optString("url", "")
                    val album = extractAlbum(track)
                    if (cover.isBlank()) {
                        cover = fetchItunesArt(title, artist)
                    }
                    RecognizedTrack(
                        title = title,
                        artist = artist,
                        album = album,
                        coverUrl = cover,
                        shazamUrl = shazamUrl
                    )
                }
            }.getOrNull()
        }

    private fun extractAlbum(track: JSONObject): String {
        val sections = track.optJSONArray("sections") ?: return ""
        for (i in 0 until sections.length()) {
            val sec = sections.optJSONObject(i) ?: continue
            val meta = sec.optJSONArray("metadata") ?: continue
            for (j in 0 until meta.length()) {
                val item = meta.optJSONObject(j) ?: continue
                if (item.optString("title").equals("Album", ignoreCase = true)) {
                    return item.optString("text", "")
                }
            }
        }
        return ""
    }

    suspend fun fetchItunesArt(title: String, artist: String): String =
        withContext(Dispatchers.IO) {
            runCatching {
                val q = Uri.encode("$title $artist")
                val req = Request.Builder()
                    .url("https://itunes.apple.com/search?term=$q&entity=song&limit=1")
                    .build()
                client.newCall(req).execute().use { resp ->
                    val json = JSONObject(resp.body?.string() ?: "{}")
                    val results = json.optJSONArray("results") ?: return@withContext ""
                    if (results.length() == 0) return@withContext ""
                    results.getJSONObject(0)
                        .optString("artworkUrl100", "")
                        .replace("100x100bb", "600x600bb")
                }
            }.getOrDefault("")
        }
}
