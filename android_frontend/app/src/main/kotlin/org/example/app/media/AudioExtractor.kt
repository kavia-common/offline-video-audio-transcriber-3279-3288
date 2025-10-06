package org.example.app.media

import android.content.Context
import android.media.*
import android.net.Uri
import android.os.Build
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * AudioExtractor is a utility for extracting and decoding audio from a video Uri
 * into a mono 16 kHz PCM WAV file (16-bit little-endian) suitable for offline STT.
 *
 * Designed to be called from a background/Worker thread. It throws IOException or
 * IllegalStateException for notable error states and provides descriptive messages.
 *
 * It:
 * - Uses MediaExtractor to find the first non-DRM audio track (AAC/MP3/Opus if decoders available)
 * - Decodes with MediaCodec to PCM float (preferred) or PCM 16-bit
 * - Downmixes to mono if needed
 * - Resamples to 16 kHz via linear interpolation if source sample-rate differs
 * - Writes a valid RIFF/WAVE header with fixed-up sizes on close
 */
object AudioExtractor {

    private const val TAG = "AudioExtractor"
    private const val TARGET_SAMPLE_RATE = 16_000
    private const val TARGET_CHANNELS = 1 // mono
    private const val WAV_HEADER_SIZE = 44

    /**
     * PUBLIC_INTERFACE
     * Extracts audio from inputUri and writes a mono 16 kHz 16-bit WAV file.
     *
     * Parameters:
     * - context: Android context used to access the Uri via ContentResolver.
     * - inputUri: The video or audio Uri selected by user (SAF).
     * - outputWavFile: Destination file to write WAV data into (will be overwritten).
     *
     * Returns:
     * - AudioMeta describing original audio parameters and decoded duration.
     *
     * Throws:
     * - IOException for I/O errors or unsupported formats
     * - IllegalStateException with descriptive message for missing/DRM/unsupported track
     */
    @JvmStatic
    @Throws(IOException::class, IllegalStateException::class)
    fun extractToWav(context: Context, inputUri: Uri, outputWavFile: File): AudioMeta {
        val extractor = MediaExtractor()
        try {
            context.contentResolver.openFileDescriptor(inputUri, "r")?.use { pfd ->
                extractor.setDataSource(pfd.fileDescriptor)
            } ?: throw IOException("Unable to open Uri: $inputUri")

            val trackIndex = selectFirstDecodableAudioTrack(extractor)
            if (trackIndex < 0) {
                throw IllegalStateException("No decodable audio track found or audio is DRM-protected.")
            }

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IllegalStateException("Audio format missing MIME.")
            val origSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val origChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION))
                format.getLong(MediaFormat.KEY_DURATION) else -1L

            // Note: Some API levels may expose DRM indicators, but KEY_IS_DRM is not universally available.
            // We rely on decoder availability; DRM-protected streams typically have no decodable codec available.

            val decoder = MediaCodec.createDecoderByType(mime)
            val outFormat = MediaFormat().apply {
                // Prefer float PCM if available (gives better intermediate for resampling and mixing)
                if (Build.VERSION.SDK_INT >= 24) {
                    setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
                } else {
                    setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                }
            }

            decoder.configure(format, null, null, 0)
            decoder.start()

            // Prepare output
            val parentDir = outputWavFile.parentFile
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs()
            }
            FileOutputStream(outputWavFile).use { fos ->
                BufferedOutputStream(fos).use { bos ->
                    // Write placeholder WAV header (to be fixed later)
                    writeWavHeaderPlaceholder(bos, TARGET_SAMPLE_RATE, TARGET_CHANNELS, 16)

                    var totalPcmBytesWritten: Long = 0
                    var decodedDurationUs: Long = 0

                    val bufferInfo = MediaCodec.BufferInfo()
                    var inputDone = false
                    var decoderDone = false

                    // Resampler state for linear interpolation per channel-agnostic mono stream
                    var resampleFrac = 0.0
                    val srcRate = origSampleRate
                    val dstRate = TARGET_SAMPLE_RATE
                    val resampleRatio = srcRate.toDouble() / dstRate.toDouble()

                    // For downmix: accumulate across channels and average
                    while (!decoderDone) {
                        // Feed input to decoder
                        if (!inputDone) {
                            val inputBufIndex = decoder.dequeueInputBuffer(10_000)
                            if (inputBufIndex >= 0) {
                                val inputBuffer: ByteBuffer? = if (Build.VERSION.SDK_INT >= 21) {
                                    decoder.getInputBuffer(inputBufIndex)
                                } else {
                                    decoder.inputBuffers[inputBufIndex]
                                }
                                val sampleSize = extractor.readSampleData(inputBuffer!!, 0)
                                if (sampleSize < 0) {
                                    decoder.queueInputBuffer(
                                        inputBufIndex,
                                        0,
                                        0,
                                        0,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    inputDone = true
                                } else {
                                    val presentationTimeUs = extractor.sampleTime
                                    decoder.queueInputBuffer(inputBufIndex, 0, sampleSize, presentationTimeUs, 0)
                                    extractor.advance()
                                }
                            }
                        }

                        // Drain decoder output
                        val outIndex = decoder.dequeueOutputBuffer(bufferInfo, 10_000)
                        when {
                            outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                // Decoder output format changed (rare with raw decoder)
                                // No special handling required for our pipeline.
                            }

                            outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                                // No output available yet
                            }

                            outIndex >= 0 -> {
                                val outputBuffer: ByteBuffer? = if (Build.VERSION.SDK_INT >= 21) {
                                    decoder.getOutputBuffer(outIndex)
                                } else {
                                    decoder.outputBuffers[outIndex]
                                }
                                outputBuffer?.order(ByteOrder.LITTLE_ENDIAN)

                                if (bufferInfo.size > 0 && outputBuffer != null) {
                                    decodedDurationUs = max(decodedDurationUs, bufferInfo.presentationTimeUs)

                                    // Determine PCM encoding of decoder output
                                    val decoderOutFormat = decoder.outputFormat
                                    val encoding = if (Build.VERSION.SDK_INT >= 24 &&
                                        decoderOutFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)
                                    ) {
                                        decoderOutFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                                    } else {
                                        AudioFormat.ENCODING_PCM_16BIT
                                    }

                                    val outChannelCount = if (decoderOutFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                                        decoderOutFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                                    else origChannels

                                    val outSampleRate = if (decoderOutFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                                        decoderOutFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                                    else origSampleRate

                                    // Convert decoder buffer to normalized float mono array
                                    val monoFloat = when (encoding) {
                                        AudioFormat.ENCODING_PCM_FLOAT -> {
                                            // Interpret as float samples in [-1,1], downmix to mono
                                            val floatArray = FloatArray(bufferInfo.size / 4)
                                            outputBuffer.asFloatBuffer().get(floatArray)
                                            downmixToMonoFloat(floatArray, outChannelCount)
                                        }
                                        AudioFormat.ENCODING_PCM_16BIT -> {
                                            val shortArray = ShortArray(bufferInfo.size / 2)
                                            outputBuffer.asShortBuffer().get(shortArray)
                                            // Convert to float and downmix
                                            val floatInterleaved = FloatArray(shortArray.size)
                                            for (i in shortArray.indices) {
                                                floatInterleaved[i] = shortArray[i] / 32768f
                                            }
                                            downmixToMonoFloat(floatInterleaved, outChannelCount)
                                        }
                                        else -> {
                                            throw IOException("Unsupported PCM encoding from decoder: $encoding")
                                        }
                                    }

                                    // Resample to 16 kHz using linear interpolation
                                    val resampled = if (outSampleRate != TARGET_SAMPLE_RATE) {
                                        linearResampleMono(
                                            monoFloat,
                                            outSampleRate,
                                            TARGET_SAMPLE_RATE,
                                            resampleRatio,
                                            startFrac = resampleFrac
                                        ).also { resampleFrac = it.second }.first
                                    } else {
                                        monoFloat
                                    }

                                    // Convert float [-1,1] mono to 16-bit little-endian and write
                                    val pcmBytes = floatMonoToPcm16Le(resampled)
                                    bos.write(pcmBytes)
                                    totalPcmBytesWritten += pcmBytes.size
                                }

                                decoder.releaseOutputBuffer(outIndex, false)

                                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                    decoderDone = true
                                }
                            }
                        }
                    }

                    // Fix up WAV header with actual sizes
                    bos.flush()
                    fixupWavHeader(outputWavFile, totalPcmBytesWritten.toInt(), TARGET_SAMPLE_RATE, TARGET_CHANNELS, 16)

                    return AudioMeta(
                        sampleRate = origSampleRate,
                        channels = origChannels,
                        durationUs = if (durationUs > 0) durationUs else decodedDurationUs,
                        frameTimestampsUs = emptyList() // optional for now
                    )
                }
            }
        } catch (e: MediaCodec.CodecException) {
            Log.e(TAG, "Decoder error: ${e.diagnosticInfo}", e)
            throw IOException("Failed to decode audio: ${e.message}", e)
        } finally {
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Selects the first audio track that is not DRM protected and appears decodable.
     */
    private fun selectFirstDecodableAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                // Try to verify a decoder exists. If none, skip this track (could be DRM or unsupported).
                val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
                val name = try {
                    codecList.findDecoderForFormat(format)
                } catch (_: Throwable) {
                    null
                }
                if (name != null) return i
            }
        }
        return -1
    }

    private fun writeWavHeaderPlaceholder(out: BufferedOutputStream, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8

        // 44-byte header with placeholder sizes
        val header = ByteArray(WAV_HEADER_SIZE)
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF chunk descriptor
        bb.put("RIFF".toByteArray()) // ChunkID
        bb.putInt(0) // ChunkSize (placeholder)
        bb.put("WAVE".toByteArray()) // Format

        // fmt subchunk
        bb.put("fmt ".toByteArray()) // Subchunk1ID
        bb.putInt(16) // Subchunk1Size (16 for PCM)
        bb.putShort(1.toShort()) // AudioFormat (1 = PCM)
        bb.putShort(channels.toShort()) // NumChannels
        bb.putInt(sampleRate) // SampleRate
        bb.putInt(byteRate) // ByteRate
        bb.putShort(blockAlign.toShort()) // BlockAlign
        bb.putShort(bitsPerSample.toShort()) // BitsPerSample

        // data subchunk
        bb.put("data".toByteArray()) // Subchunk2ID
        bb.putInt(0) // Subchunk2Size (placeholder)

        out.write(header)
    }

    private fun fixupWavHeader(file: File, dataSizeBytes: Int, sampleRate: Int, channels: Int, bitsPerSample: Int) {
        // Update RIFF and data sizes
        RandomAccessFileCompat(file, "rw").use { raf ->
            val riffChunkSize = 36 + dataSizeBytes
            raf.seek(4)
            raf.writeIntLE(riffChunkSize)
            raf.seek(40)
            raf.writeIntLE(dataSizeBytes)
        }
    }

    private fun downmixToMonoFloat(interleaved: FloatArray, channels: Int): FloatArray {
        if (channels <= 1) return interleaved // already mono
        val frames = interleaved.size / channels
        val out = FloatArray(frames)
        var srcIdx = 0
        for (i in 0 until frames) {
            var acc = 0f
            for (ch in 0 until channels) {
                acc += interleaved[srcIdx + ch]
            }
            out[i] = acc / channels
            srcIdx += channels
        }
        return out
    }

    /**
     * Linear resample from srcRate to dstRate for a mono float array.
     * Maintains a fractional cursor between calls for continuous processing.
     *
     * Returns Pair(resampled, newFractionalPosition)
     */
    private fun linearResampleMono(
        mono: FloatArray,
        srcRate: Int,
        dstRate: Int,
        resampleRatio: Double,
        startFrac: Double
    ): Pair<FloatArray, Double> {
        if (srcRate == dstRate || mono.isEmpty()) return Pair(mono, startFrac)
        // Calculate number of output samples
        val outLength = ((mono.size - startFrac) / resampleRatio).toInt().coerceAtLeast(0)
        val out = FloatArray(outLength)

        var frac = startFrac
        var srcPos = 0
        for (i in 0 until outLength) {
            val baseIndex = srcPos
            val nextIndex = min(baseIndex + 1, mono.size - 1)
            val t = (frac).toFloat()
            val s = mono[baseIndex] * (1f - t) + mono[nextIndex] * t
            out[i] = s

            frac += resampleRatio - 1.0
            while (frac >= 1.0) {
                frac -= 1.0
                srcPos++
                if (srcPos >= mono.size - 1) {
                    // Clamp at end
                    for (j in i + 1 until outLength) {
                        out[j] = mono[mono.size - 1]
                    }
                    return Pair(out, frac)
                }
            }
        }
        return Pair(out, frac)
    }

    private fun floatMonoToPcm16Le(mono: FloatArray): ByteArray {
        val out = ByteArray(mono.size * 2)
        var o = 0
        for (f in mono) {
            // Clip to [-1,1]
            val clamped = if (f > 1f) 1f else if (f < -1f) -1f else f
            val s = (clamped * 32767f).toInt()
            out[o++] = (s and 0xFF).toByte()
            out[o++] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    // Minimal RandomAccessFile replacement to avoid adding java.io.RandomAccessFile directly (works equally)
    private class RandomAccessFileCompat(file: File, mode: String) : AutoCloseable {
        private val raf = java.io.RandomAccessFile(file, mode)

        fun seek(pos: Long) = raf.seek(pos)
        fun writeIntLE(value: Int) {
            val b0 = (value and 0xFF).toByte()
            val b1 = ((value shr 8) and 0xFF).toByte()
            val b2 = ((value shr 16) and 0xFF).toByte()
            val b3 = ((value shr 24) and 0xFF).toByte()
            raf.write(byteArrayOf(b0, b1, b2, b3))
        }

        override fun close() {
            raf.close()
        }
    }

    /**
     * PUBLIC_INTERFACE
     * Simple metadata container returned by extractToWav.
     */
    data class AudioMeta(
        val sampleRate: Int,
        val channels: Int,
        val durationUs: Long,
        val frameTimestampsUs: List<Long> = emptyList()
    )
}
