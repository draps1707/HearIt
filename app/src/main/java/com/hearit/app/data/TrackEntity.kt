package com.hearit.app.data

import android.content.Context
import androidx.room.Entity
import androidx.room.PrimaryKey

enum class AudioSourceType {
    AMBIENT_MIC,
    HEADPHONE_CAPTURE,
    SPOTIFY_MEDIA,
    YT_MUSIC_MEDIA
}

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val artist: String,
    val album: String = "",
    val coverUrl: String = "",
    val shazamUrl: String = "",
    val spotifySearchUri: String,
    val ytMusicSearchUrl: String,
    val source: AudioSourceType = AudioSourceType.AMBIENT_MIC,
    val isFavorite: Boolean = false,
    val playCount: Int = 1,
    val firstHeardAt: Long = System.currentTimeMillis(),
    val lastHeardAt: Long = System.currentTimeMillis(),
    val dateGroup: String
)

@Entity(tableName = "pending_signatures")
data class PendingSignatureEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val signatureUri: String,
    val sampleMs: Int,
    val source: AudioSourceType,
    val capturedAt: Long = System.currentTimeMillis()
)

object HearItPrefs {
    private const val PREFS = "hearit_prefs"
    private const val KEY_SCREEN_ON = "screen_on_enabled"
    private const val KEY_POCKET_SKIP = "pocket_skip_enabled"
    private const val KEY_ARMED = "service_armed"

    fun isScreenOnEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SCREEN_ON, true)

    fun setScreenOnEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SCREEN_ON, enabled).apply()
    }

    fun isPocketSkipEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_POCKET_SKIP, true)

    fun setPocketSkipEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_POCKET_SKIP, enabled).apply()
    }

    fun isServiceArmed(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ARMED, false)

    fun setServiceArmed(context: Context, armed: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ARMED, armed).apply()
    }
}
