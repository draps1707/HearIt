package com.hearit.app.service

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.service.notification.NotificationListenerService
import com.hearit.app.data.AudioSourceType
import com.hearit.app.data.HearItDatabase
import com.hearit.app.data.TrackEntity
import com.hearit.app.network.ShazamRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Zero-mic, zero-battery MediaSession logger for Spotify and YouTube Music.
 */
class MediaSessionListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastLoggedKey: String = ""

    private val watchedPackages = mapOf(
        "com.spotify.music" to AudioSourceType.SPOTIFY_MEDIA,
        "com.google.android.apps.youtube.music" to AudioSourceType.YT_MUSIC_MEDIA
    )

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        controllers?.forEach { attachController(it) }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val comp = ComponentName(this, MediaSessionListener::class.java)
        runCatching {
            msm.addOnActiveSessionsChangedListener(sessionListener, comp)
            msm.getActiveSessions(comp).forEach { attachController(it) }
        }
    }

    private fun attachController(controller: MediaController) {
        val source = watchedPackages[controller.packageName] ?: return
        checkMetadata(controller.metadata, controller.playbackState, source)
        controller.registerCallback(object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) {
                checkMetadata(metadata, controller.playbackState, source)
            }
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                checkMetadata(controller.metadata, state, source)
            }
        })
    }

    private fun checkMetadata(meta: MediaMetadata?, state: PlaybackState?, source: AudioSourceType) {
        if (state?.state != PlaybackState.STATE_PLAYING || meta == null) return
        val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
        val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim().orEmpty()
        if (title.isBlank() || artist.isBlank()) return

        val key = "${title.lowercase()}|${artist.lowercase()}"
        if (key == lastLoggedKey) return
        lastLoggedKey = key

        val album = meta.getString(MediaMetadata.METADATA_KEY_ALBUM)?.trim().orEmpty()
        scope.launch {
            val dao = HearItDatabase.get(this@MediaSessionListener).trackDao()
            val now = System.currentTimeMillis()
            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(now))
            val existing = dao.findByTitleAndArtist(title, artist)
            if (existing != null) {
                dao.update(
                    existing.copy(
                        lastHeardAt = now,
                        dateGroup = dateStr,
                        playCount = if (now - existing.lastHeardAt > 240_000L) existing.playCount + 1 else existing.playCount
                    )
                )
            } else {
                val art = ShazamRecognizer.fetchItunesArt(title, artist)
                val q = Uri.encode("$title $artist")
                dao.insert(
                    TrackEntity(
                        title = title,
                        artist = artist,
                        album = album,
                        coverUrl = art,
                        spotifySearchUri = "https://open.spotify.com/search/$q",
                        ytMusicSearchUrl = "https://music.youtube.com/search?q=$q",
                        source = source,
                        firstHeardAt = now,
                        lastHeardAt = now,
                        dateGroup = dateStr
                    )
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
