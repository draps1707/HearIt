package com.hearit.app.service

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import com.hearit.app.audio.MusicGatekeeper
import com.hearit.app.audio.ShazamSignature
import com.hearit.app.data.AudioSourceType
import com.hearit.app.data.HearItDatabase
import com.hearit.app.data.HearItPrefs
import com.hearit.app.data.PendingSignatureEntity
import com.hearit.app.data.TrackEntity
import com.hearit.app.network.RecognizedTrack
import com.hearit.app.network.ShazamRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class HearItService : Service() {

    companion object {
        const val ACTION_START = "com.hearit.app.START"
        const val ACTION_STOP = "com.hearit.app.STOP"
        const val ACTION_HEAR_NOW = "com.hearit.app.HEAR_NOW"
        const val ACTION_HEADPHONE_CAPTURE = "com.hearit.app.HEADPHONE_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val SAMPLE_RATE = 16000
        private const val CAPTURE_SECONDS = 5
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isBusy = AtomicBoolean(false)
    private lateinit var gatekeeper: MusicGatekeeper
    private var isNearPocket = false
    private var lastScreenOnCheckMs = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_ON && HearItPrefs.isScreenOnEnabled(this@HearItService)) {
                val now = System.currentTimeMillis()
                if (now - lastScreenOnCheckMs < 8000L) return
                lastScreenOnCheckMs = now
                triggerAmbientCheck(bypassGate = false)
            }
        }
    }

    private val proximityListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            isNearPocket = event.values.firstOrNull()?.let { it < event.sensor.maximumRange } ?: false
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    override fun onCreate() {
        super.onCreate()
        gatekeeper = MusicGatekeeper(this)
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let {
            sm.registerListener(proximityListener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                HearItPrefs.setServiceArmed(this, false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_HEADPHONE_CAPTURE -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                @Suppress("DEPRECATION")
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                if (code == Activity.RESULT_OK && data != null) {
                    startTypedForeground(includeMediaProjection = true)
                    triggerHeadphoneCapture(code, data)
                }
            }
            ACTION_HEAR_NOW -> {
                startTypedForeground(includeMediaProjection = false)
                HearItPrefs.setServiceArmed(this, true)
                triggerAmbientCheck(bypassGate = true)
            }
            else -> {
                startTypedForeground(includeMediaProjection = false)
                HearItPrefs.setServiceArmed(this, true)
                scope.launch { flushPendingQueue() }
            }
        }
        return START_STICKY
    }

    private fun startTypedForeground(includeMediaProjection: Boolean) {
        val notification = NotificationHelper.buildListenerNotification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val fgsType = if (includeMediaProjection) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NotificationHelper.ID_LISTENER, notification, fgsType)
        } else {
            startForeground(NotificationHelper.ID_LISTENER, notification)
        }
    }

    private fun triggerAmbientCheck(bypassGate: Boolean) {
        if (!isBusy.compareAndSet(false, true)) return
        scope.launch {
            try {
                if (!bypassGate && HearItPrefs.isPocketSkipEnabled(this@HearItService) && isNearPocket) {
                    return@launch
                }
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                if (am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION) {
                    return@launch
                }

                updateListenerStatus("Listening…")
                val pcm = recordMicPcm(CAPTURE_SECONDS) ?: return@launch

                if (!bypassGate && !gatekeeper.isMusicLikely(pcm)) {
                    return@launch
                }

                processPcm(pcm, AudioSourceType.AMBIENT_MIC)
            } finally {
                updateListenerStatus("Ready on screen wake")
                isBusy.set(false)
            }
        }
    }

    private fun triggerHeadphoneCapture(resultCode: Int, data: Intent) {
        if (!isBusy.compareAndSet(false, true)) return
        scope.launch {
            var projection: MediaProjection? = null
            try {
                updateListenerStatus("Listening to earbuds/media…")
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projection = mpm.getMediaProjection(resultCode, data)
                val pcm = recordPlaybackPcm(projection, CAPTURE_SECONDS) ?: return@launch
                processPcm(pcm, AudioSourceType.HEADPHONE_CAPTURE)
            } finally {
                projection?.stop()
                startTypedForeground(includeMediaProjection = false)
                updateListenerStatus("Ready on screen wake")
                isBusy.set(false)
            }
        }
    }

    private suspend fun processPcm(pcm: ShortArray, source: AudioSourceType) {
        val sigUri = ShazamSignature.generateUri(pcm)
        val sampleMs = (pcm.size * 1000L / SAMPLE_RATE).toInt()
        val db = HearItDatabase.get(this)

        if (!isOnline()) {
            db.trackDao().enqueuePending(
                PendingSignatureEntity(signatureUri = sigUri, sampleMs = sampleMs, source = source)
            )
            return
        }

        flushPendingQueue()
        val match = ShazamRecognizer.recognize(sigUri, sampleMs) ?: return
        saveAndNotify(match, source)
    }

    private suspend fun flushPendingQueue() {
        if (!isOnline()) return
        val dao = HearItDatabase.get(this).trackDao()
        val pending = dao.getPending()
        for (item in pending) {
            val match = ShazamRecognizer.recognize(item.signatureUri, item.sampleMs)
            if (match != null) {
                saveAndNotify(match, item.source)
            }
            dao.deletePending(item.id)
        }
    }

    private suspend fun saveAndNotify(match: RecognizedTrack, source: AudioSourceType) {
        val dao = HearItDatabase.get(this).trackDao()
        val now = System.currentTimeMillis()
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(now))
        val existing = dao.findByTitleAndArtist(match.title, match.artist)

        val savedTrack = if (existing != null) {
            val updated = existing.copy(
                lastHeardAt = now,
                dateGroup = dateStr,
                playCount = if (now - existing.lastHeardAt > 240_000L) existing.playCount + 1 else existing.playCount,
                coverUrl = existing.coverUrl.ifBlank { match.coverUrl }
            )
            dao.update(updated)
            updated
        } else {
            val entity = TrackEntity(
                title = match.title,
                artist = match.artist,
                album = match.album,
                coverUrl = match.coverUrl,
                shazamUrl = match.shazamUrl,
                spotifySearchUri = match.spotifySearchUri(),
                ytMusicSearchUrl = match.ytMusicSearchUrl(),
                source = source,
                firstHeardAt = now,
                lastHeardAt = now,
                dateGroup = dateStr
            )
            val id = dao.insert(entity)
            entity.copy(id = id)
        }

        NotificationHelper.showPinnedSong(this, savedTrack)
    }

    @SuppressLint("MissingPermission")
    private fun recordMicPcm(seconds: Int): ShortArray? {
        val totalSamples = SAMPLE_RATE * seconds
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 2
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return null
        }

        val out = ShortArray(totalSamples)
        return try {
            recorder.startRecording()
            var offset = 0
            while (offset < totalSamples) {
                val read = recorder.read(out, offset, minOf(2048, totalSamples - offset))
                if (read <= 0) break
                offset += read
            }
            if (offset >= SAMPLE_RATE * 2) out.copyOf(offset) else null
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }

    @SuppressLint("MissingPermission")
    private fun recordPlaybackPcm(projection: MediaProjection, seconds: Int): ShortArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val recorder = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBuf * 2)
            .setAudioPlaybackCaptureConfig(config)
            .build()

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return null
        }

        val totalSamples = SAMPLE_RATE * seconds
        val out = ShortArray(totalSamples)
        return try {
            recorder.startRecording()
            var offset = 0
            while (offset < totalSamples) {
                val read = recorder.read(out, offset, minOf(2048, totalSamples - offset))
                if (read <= 0) break
                offset += read
            }
            if (offset >= SAMPLE_RATE * 2) out.copyOf(offset) else null
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }

    private fun updateListenerStatus(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NotificationHelper.ID_LISTENER, NotificationHelper.buildListenerNotification(this, text))
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(screenReceiver) }
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sm.unregisterListener(proximityListener)
        gatekeeper.close()
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
