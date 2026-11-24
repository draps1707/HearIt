package com.hearit.app.audio

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/**
 * Lightweight on-device gatekeeper using MediaPipe YAMNet (16 kHz mono, 0.975s = 15,600 samples).
 * Checks whether an audio snippet actually contains music before computing a fingerprint or
 * waking the network radio. Falls back to an RMS energy check if yamnet.tflite is absent.
 */
class MusicGatekeeper(private val context: Context) {

    private var interpreter: Interpreter? = null

    init {
        runCatching {
            val model = loadModelFile("yamnet.tflite")
            val opts = Interpreter.Options().apply { setNumThreads(2) }
            interpreter = Interpreter(model, opts)
        }
    }

    private fun loadModelFile(assetName: String): MappedByteBuffer {
        val afd = context.assets.openFd(assetName)
        FileInputStream(afd.fileDescriptor).use { fis ->
            return fis.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
        }
    }

    fun isMusicLikely(pcm16k: ShortArray): Boolean {
        if (pcm16k.size < 4000) return false

        // Quick RMS silence guard (< 0.004 is near-silence)
        var sumSq = 0.0
        val checkLen = minOf(pcm16k.size, 15600)
        for (i in 0 until checkLen) {
            val s = pcm16k[i] / 32768.0
            sumSq += s * s
        }
        val rms = sqrt(sumSq / checkLen)
        if (rms < 0.004) return false

        val tflite = interpreter ?: return true // Fallback: pass non-silent audio if model missing

        val inputSamples = 15600
        val inputBuffer = ByteBuffer.allocateDirect(inputSamples * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until inputSamples) {
            val v = if (i < pcm16k.size) pcm16k[i] / 32768.0f else 0f
            inputBuffer.putFloat(v)
        }
        inputBuffer.rewind()

        val output = Array(1) { FloatArray(521) }
        return try {
            tflite.run(inputBuffer, output)
            val scores = output[0]
            // AudioSet YAMNet indices: 132 = Music, 133 = Musical instrument, 137 = Singing, 27 = Singing/Choir
            val musicScore = maxOf(scores[132], scores[133], scores[137])
            val speechScore = scores[0] // 0 = Speech
            musicScore >= 0.12f || (musicScore > 0.06f && musicScore > speechScore * 0.5f)
        } catch (_: Throwable) {
            true
        }
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
