package org.example.app.stt

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * VoskTranscriber provides offline STT transcription using the Vosk engine.
 *
 * It expects input WAV file to be:
 * - PCM 16-bit, little endian
 * - mono channel
 * - 16 kHz sample rate
 *
 * The class loads a Vosk model from a directory under app's internal storage, typically:
 *   <filesDir>/models/vosk/<modelName>/
 * and streams the WAV audio to Recognizer, collecting timestamped segments.
 */
object VoskTranscriber {

    private const val TAG = "VoskTranscriber"
    private const val EXPECTED_SAMPLE_RATE = 16_000
    private const val EXPECTED_CHANNELS = 1
    private const val EXPECTED_BITS_PER_SAMPLE = 16

    // PUBLIC_INTERFACE
    /**
     * Transcribe a mono 16kHz PCM WAV file using the Vosk model located in modelDir.
     *
     * Parameters:
     * - context: Android Context for resolving filesDir if needed for defaults.
     * - wavFile: Input WAV file path (must be PCM 16-bit LE, mono, 16 kHz).
     * - modelDir: Directory pointing to the root of the Vosk model. If null,
     *             the transcriber will try to find the first valid model under
     *             <filesDir>/models/vosk.
     *
     * Returns:
     * - List of Segment(startMs, endMs, text) containing the recognized utterances with timestamps.
     *
     * Throws:
     * - IOException if the model is not found or the WAV format is unsupported/invalid.
     * - IllegalStateException for decoding/recognizer errors.
     */
    @JvmStatic
    @Throws(IOException::class, IllegalStateException::class)
    fun transcribe(context: Context, wavFile: File, modelDir: File? = null): List<Segment> {
        // Resolve model directory
        val resolvedModelDir = modelDir ?: findFirstModelDir(context)
        if (resolvedModelDir == null || !resolvedModelDir.exists() || !resolvedModelDir.isDirectory) {
            throw IOException("Vosk model not found. Please import a valid model in filesDir/models/vosk.")
        }

        // Validate WAV header minimally and skip 44-byte header
        val header = readWavHeader(wavFile)
        if (header.sampleRate != EXPECTED_SAMPLE_RATE ||
            header.channels != EXPECTED_CHANNELS ||
            header.bitsPerSample != EXPECTED_BITS_PER_SAMPLE
        ) {
            throw IOException("WAV format mismatch. Expected 16kHz, mono, 16-bit PCM.")
        }

        val segments = mutableListOf<Segment>()

        // Initialize Vosk model and recognizer
        Model(resolvedModelDir.absolutePath).use { model ->
            Recognizer(model, EXPECTED_SAMPLE_RATE.toFloat()).use { recognizer ->
                // Stream audio in chunks after header
                BufferedInputStream(FileInputStream(wavFile)).use { input ->
                    // Skip header bytes (typically 44) based on our parsed header size
                    var toSkip = header.dataStartOffset
                    while (toSkip > 0) {
                        val skipped = input.skip(toSkip.toLong())
                        if (skipped <= 0) break
                        toSkip -= skipped.toInt()
                    }

                    val buffer = ByteArray(4096)
                    var bytesRead: Int
                    // Vosk expects little-endian shorts; we're already reading bytes as-is.
                    // We'll provide the byte buffer directly to recognizer.
                    while (true) {
                        bytesRead = input.read(buffer)
                        if (bytesRead <= 0) break

                        val ok = recognizer.acceptWaveForm(buffer, bytesRead)
                        if (ok) {
                            // Finalized result for the chunk, parse and append segments
                            val res = recognizer.result
                            parseResultJsonToSegments(res)?.let { seg ->
                                segments.addAll(seg)
                            }
                        } else {
                            // Partial result available if needed for progress. Not required; ignore here.
                            // val partial = recognizer.partialResult
                        }
                    }

                    // Flush last partials into final result
                    val finalRes = recognizer.finalResult
                    parseResultJsonToSegments(finalRes)?.let { seg ->
                        segments.addAll(seg)
                    }
                }
            }
        }

        // Merge adjacent segments with the same timing edge cases if needed
        return normalizeSegments(segments)
    }

    /**
     * Attempt to find the first valid model directory under filesDir/models/vosk using ModelManager heuristic.
     */
    private fun findFirstModelDir(context: Context): File? {
        val base = File(context.filesDir, "models/vosk")
        if (!base.exists() || !base.isDirectory) return null
        val candidates = base.listFiles()?.filter { it.isDirectory } ?: return null
        // Prefer directories that look valid using same heuristic as ModelManager
        val valid = candidates.firstOrNull { looksLikeVoskModelDir(it) }
        return valid ?: candidates.firstOrNull()
    }

    private fun looksLikeVoskModelDir(dir: File): Boolean {
        if (!dir.isDirectory) return false
        val confFile = File(dir, "conf/model.conf")
        if (confFile.exists()) return true
        val modelFolder = File(dir, "model")
        if (modelFolder.exists() && modelFolder.isDirectory) return true
        val likely = setOf("am", "ivector", "graph", "phones.txt", "final.mdl", "HCLG.fst")
        val names = dir.list()?.toSet() ?: emptySet()
        if (names.intersect(likely).isNotEmpty()) return true
        return dir.listFiles()?.any { it.isDirectory && looksLikeVoskModelDir(it) } ?: false
    }

    /**
     * Parse Vosk result JSON and map to list of segments.
     * Vosk result schema typically contains:
     * {
     *   "result": [ {"conf": 0.8, "end": 1.23, "start": 0.67, "word": "hello"}, ... ],
     *   "text": "hello world"
     * }
     * We collapse words into a single segment spanning from first.start to last.end with concatenated text.
     */
    private fun parseResultJsonToSegments(json: String?): List<Segment>? {
        if (json.isNullOrBlank()) return null
        return try {
            val root = JSONObject(json)
            if (!root.has("result")) {
                // Some results may only contain "text" with no timing; ignore empty text
                val txt = root.optString("text").trim()
                if (txt.isBlank()) return null
                // When no timings, we cannot infer start/end; skip adding segment
                return null
            }
            val arr = root.getJSONArray("result")
            if (arr.length() == 0) return null

            val words = mutableListOf<WordItem>()
            for (i in 0 until arr.length()) {
                val w = arr.getJSONObject(i)
                val start = (w.optDouble("start", Double.NaN)).takeIf { !it.isNaN() } ?: continue
                val end = (w.optDouble("end", Double.NaN)).takeIf { !it.isNaN() } ?: continue
                val word = w.optString("word", "").trim()
                if (word.isNotBlank()) {
                    words.add(WordItem(start, end, word))
                }
            }
            if (words.isEmpty()) return null

            val startSec = words.first().start
            val endSec = words.last().end
            val text = words.joinToString(separator = " ") { it.word }.trim()
            if (text.isBlank()) return null

            listOf(
                Segment(
                    startMs = (startSec * 1000).toLong().coerceAtLeast(0),
                    endMs = (endSec * 1000).toLong().coerceAtLeast(0),
                    text = text
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse Vosk JSON: ${e.message}")
            null
        }
    }

    /**
     * Normalize segments by:
     * - sorting by start time
     * - merging overlapping or adjacent segments with small gaps
     */
    private fun normalizeSegments(input: List<Segment>): List<Segment> {
        if (input.isEmpty()) return input
        val sorted = input.sortedBy { it.startMs }
        val merged = mutableListOf<Segment>()
        var cur = sorted.first()

        fun maybeMerge(a: Segment, b: Segment): Segment? {
            // Merge if overlapping or gap <= 100 ms
            return if (b.startMs <= a.endMs + 100) {
                Segment(
                    startMs = minOf(a.startMs, b.startMs),
                    endMs = maxOf(a.endMs, b.endMs),
                    text = (a.text + " " + b.text).replace(Regex("\\s+"), " ").trim()
                )
            } else null
        }

        for (i in 1 until sorted.size) {
            val next = sorted[i]
            val m = maybeMerge(cur, next)
            if (m != null) {
                cur = m
            } else {
                merged.add(cur)
                cur = next
            }
        }
        merged.add(cur)
        return merged
    }

    // --- WAV header utilities ---

    private data class WavHeader(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val dataStartOffset: Int
    )

    private fun readWavHeader(file: File): WavHeader {
        FileInputStream(file).use { fis ->
            val header = ByteArray(44)
            val read = fis.read(header)
            if (read < 44) throw IOException("Invalid WAV: header too short")

            val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val riff = byteArrayOf(bb.get(0), bb.get(1), bb.get(2), bb.get(3)).toString(Charsets.US_ASCII)
            val wave = byteArrayOf(bb.get(8), bb.get(9), bb.get(10), bb.get(11)).toString(Charsets.US_ASCII)
            if (riff != "RIFF" || wave != "WAVE") throw IOException("Invalid WAV: missing RIFF/WAVE")

            // Parse fmt chunk (assuming PCM header at standard positions)
            val audioFormat = bb.getShort(20).toInt() and 0xFFFF
            val numChannels = bb.getShort(22).toInt() and 0xFFFF
            val sampleRate = bb.getInt(24)
            val bitsPerSample = bb.getShort(34).toInt() and 0xFFFF

            // Find data chunk start; often at offset 36 with "data" tag, but can vary.
            var offset = 12 // after RIFF/WAVE
            var dataOffset = -1
            while (offset + 8 <= header.size) {
                val chunkId = byteArrayOf(bb.get(offset), bb.get(offset + 1), bb.get(offset + 2), bb.get(offset + 3))
                    .toString(Charsets.US_ASCII)
                val chunkSize = bb.getInt(offset + 4)
                if (chunkId == "data") {
                    dataOffset = offset + 8
                    break
                }
                offset += 8 + chunkSize
                if (offset > header.size - 8) break
            }
            val start = if (dataOffset > 0) dataOffset else 44

            if (audioFormat != 1) {
                // 1 = PCM
                throw IOException("Unsupported WAV encoding (expected PCM).")
            }

            return WavHeader(
                sampleRate = sampleRate,
                channels = numChannels,
                bitsPerSample = bitsPerSample,
                dataStartOffset = start
            )
        }
    }

    private data class WordItem(val start: Double, val end: Double, val word: String)

    /**
     * PUBLIC_INTERFACE
     * Segment represents a recognized text chunk with start and end time in milliseconds.
     */
    data class Segment(
        val startMs: Long,
        val endMs: Long,
        val text: String
    )
}
