package com.hearit.app.audio

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure-Kotlin Shazam-compatible constellation signature generator (16 kHz mono PCM).
 * Produces `data:audio/vnd.shazam.sig;base64,...` without external native dependencies.
 */
object ShazamSignature {

    private const val SAMPLE_RATE = 16000
    private const val FFT_SIZE = 2048
    private const val STEP_SIZE = 128
    private const val HEADER_MAGIC = 0xCAFE2580.toInt()

    private val hannWindow = DoubleArray(FFT_SIZE) { i ->
        0.5 * (1.0 - cos(2.0 * PI * i / (FFT_SIZE - 1)))
    }

    private data class Peak(val stripe: Int, val bin: Int, val magnitude: Double)

    fun generateUri(pcm16k: ShortArray): String {
        val maxSamples = minOf(pcm16k.size, SAMPLE_RATE * 10)
        val fftStripes = ArrayList<DoubleArray>()

        val real = DoubleArray(FFT_SIZE)
        val imag = DoubleArray(FFT_SIZE)

        var pos = 0
        while (pos + FFT_SIZE <= maxSamples) {
            for (i in 0 until FFT_SIZE) {
                real[i] = pcm16k[pos + i] * hannWindow[i]
                imag[i] = 0.0
            }
            fft(real, imag)

            val mag = DoubleArray(FFT_SIZE / 2 + 1)
            for (k in 0..FFT_SIZE / 2) {
                val re = real[k]
                val im = imag[k]
                mag[k] = (re * re + im * im) / (1L shl 17)
            }
            fftStripes.add(mag)
            pos += STEP_SIZE
        }

        // Band bin ranges at 16kHz / 2048 = 7.8125 Hz/bin
        val bands = arrayOf(
            32 to 66,    // 250 - 520 Hz
            66 to 186,   // 520 - 1450 Hz
            186 to 448,  // 1450 - 3500 Hz
            448 to 704   // 3500 - 5500 Hz
        )

        val bandStreams = Array(4) { ByteArrayOutputStream() }
        val lastStripe = IntArray(4) { 0 }

        for (s in 12 until fftStripes.size - 12) {
            for (bIdx in bands.indices) {
                val (bStart, bEnd) = bands[bIdx]
                var bestBin = -1
                var bestVal = 0.0
                for (bin in bStart until bEnd) {
                    val v = fftStripes[s][bin]
                    if (v > bestVal) {
                        bestVal = v
                        bestBin = bin
                    }
                }
                if (bestBin < 0 || bestVal <= 1e-6) continue

                // Check 2D local neighborhood dominance
                var isLocalMax = true
                for (ds in -6..6) {
                    if (ds == 0) continue
                    for (db in -3..3) {
                        val nb = bestBin + db
                        if (nb in 0 until fftStripes[s + ds].size && fftStripes[s + ds][nb] >= bestVal) {
                            isLocalMax = false
                            break
                        }
                    }
                    if (!isLocalMax) break
                }

                if (isLocalMax) {
                    val passDiff = s - lastStripe[bIdx]
                    if (passDiff >= 2) {
                        val logMag = max(1, (ln(max(1.0, bestVal)) * 1477.3).toInt().coerceIn(1, 65535))
                        val binScaled = (bestBin * 64).coerceIn(0, 65535)
                        val out = bandStreams[bIdx]
                        if (passDiff >= 255) {
                            out.write(0xFF)
                            writeIntLE(out, s)
                        } else {
                            out.write(passDiff)
                        }
                        writeShortLE(out, logMag)
                        writeShortLE(out, binScaled)
                        lastStripe[bIdx] = s
                    }
                }
            }
        }

        val body = ByteArrayOutputStream()
        // Sample rate id 3 = 16000 Hz
        writeVarintField(body, 1, 3 shl 27)
        val durationMs = (maxSamples * 1000L / SAMPLE_RATE).toInt()
        writeVarintField(body, 2, durationMs)

        for (bIdx in 0 until 4) {
            val bBytes = bandStreams[bIdx].toByteArray()
            if (bBytes.isNotEmpty()) {
                val tag = ((0x6003 + bIdx) shl 3) or 2
                writeVarint(body, tag)
                writeVarint(body, bBytes.size)
                body.write(bBytes)
            }
        }

        val payload = body.toByteArray()
        val headerAndBody = ByteArray(48 + payload.size)
        val buf = ByteBuffer.wrap(headerAndBody).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0, HEADER_MAGIC)
        buf.putInt(8, 48 + payload.size)
        buf.putInt(12, 0x94119C00.toInt())
        buf.putInt(16, 1)
        buf.putInt(24, (3 shl 27) + 0x2000000)
        buf.putInt(28, ((maxSamples.toDouble() / SAMPLE_RATE) * 1000.0).toInt())
        buf.putInt(32, 0x40000000)
        buf.putInt(40, 0x40000000)
        buf.putInt(44, payload.size)
        System.arraycopy(payload, 0, headerAndBody, 48, payload.size)

        val crc = CRC32()
        crc.update(headerAndBody, 8, headerAndBody.size - 8)
        buf.putInt(4, crc.value.toInt())

        val b64 = Base64.encodeToString(headerAndBody, Base64.NO_WRAP)
        return "data:audio/vnd.shazam.sig;base64,$b64"
    }

    private fun writeShortLE(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }

    private fun writeIntLE(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
        out.write((v shr 16) and 0xFF)
        out.write((v shr 24) and 0xFF)
    }

    private fun writeVarintField(out: ByteArrayOutputStream, fieldNum: Int, value: Int) {
        writeVarint(out, (fieldNum shl 3) or 0)
        writeVarint(out, value)
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Int) {
        var v = value
        while ((v and 0x7F.inv()) != 0) {
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.write(v and 0x7F)
    }

    private fun fft(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wlenRe = cos(ang)
            val wlenIm = sin(ang)
            var i = 0
            while (i < n) {
                var wRe = 1.0
                var wIm = 0.0
                for (k in 0 until len / 2) {
                    val uRe = real[i + k]
                    val uIm = imag[i + k]
                    val vRe = real[i + k + len / 2] * wRe - imag[i + k + len / 2] * wIm
                    val vIm = real[i + k + len / 2] * wIm + imag[i + k + len / 2] * wRe
                    real[i + k] = uRe + vRe
                    imag[i + k] = uIm + vIm
                    real[i + k + len / 2] = uRe - vRe
                    imag[i + k + len / 2] = uIm - vIm
                    val nextWRe = wRe * wlenRe - wIm * wlenIm
                    wIm = wRe * wlenIm + wIm * wlenRe
                    wRe = nextWRe
                }
                i += len
            }
            len = len shl 1
        }
    }
}
