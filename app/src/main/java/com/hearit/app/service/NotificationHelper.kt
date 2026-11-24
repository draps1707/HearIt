package com.hearit.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.hearit.app.MainActivity
import com.hearit.app.R
import com.hearit.app.data.TrackEntity

object NotificationHelper {
    const val CHANNEL_LISTENER = "hearit_listener"
    const val CHANNEL_NOW_PLAYING = "hearit_now_playing"
    const val ID_LISTENER = 1001
    const val ID_NOW_PLAYING = 2002

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val listenerChannel = NotificationChannel(
            CHANNEL_LISTENER,
            "HearIt Background Listener",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Keeps HearIt ready to identify music on screen wake"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }

        val nowPlayingChannel = NotificationChannel(
            CHANNEL_NOW_PLAYING,
            "Now Playing (Lock Screen)",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Pinned lock-screen card showing the last recognized song"
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        nm.createNotificationChannel(listenerChannel)
        nm.createNotificationChannel(nowPlayingChannel)
    }

    fun buildListenerNotification(context: Context, statusText: String = "Ready on screen wake"): Notification {
        ensureChannels(context)
        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val hearNowIntent = PendingIntent.getService(
            context,
            1,
            Intent(context, HearItService::class.java).apply { action = HearItService.ACTION_HEAR_NOW },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(context, CHANNEL_LISTENER)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle("HearIt")
            .setContentText(statusText)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_music_note, "Hear Now", hearNowIntent)
            .build()
    }

    suspend fun showPinnedSong(context: Context, track: TrackEntity) {
        ensureChannels(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val openAppIntent = PendingIntent.getActivity(
            context,
            10,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val spotifyPending = PendingIntent.getActivity(
            context,
            11,
            Intent(Intent.ACTION_VIEW, Uri.parse(track.spotifySearchUri)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val ytMusicPending = PendingIntent.getActivity(
            context,
            12,
            Intent(Intent.ACTION_VIEW, Uri.parse(track.ytMusicSearchUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val coverBitmap = loadBitmap(context, track.coverUrl)

        val liveExtras = Bundle().apply {
            putBoolean("android.requestPromotedOngoing", true)
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_NOW_PLAYING)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle("♪ ${track.title}")
            .setContentText(track.artist)
            .setSubText(if (track.album.isNotBlank()) track.album else "HearIt")
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(false) // Stays pinned until user swipes it away or next song replaces it
            .setAutoCancel(false)
            .setSilent(true)
            .setContentIntent(openAppIntent)
            .addAction(0, "Spotify", spotifyPending)
            .addAction(0, "YT Music", ytMusicPending)
            .addExtras(liveExtras)

        if (coverBitmap != null) {
            builder.setLargeIcon(coverBitmap)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }

        nm.notify(ID_NOW_PLAYING, builder.build())
    }

    private suspend fun loadBitmap(context: Context, url: String): Bitmap? {
        if (url.isBlank()) return null
        return runCatching {
            val loader = ImageLoader(context)
            val req = ImageRequest.Builder(context)
                .data(url)
                .size(256)
                .allowHardware(false)
                .build()
            val result = loader.execute(req)
            if (result is SuccessResult) {
                (result.drawable as? BitmapDrawable)?.bitmap
            } else null
        }.getOrNull()
    }
}
