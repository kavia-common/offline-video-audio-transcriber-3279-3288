package org.example.app.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.app.R
import org.example.app.media.AudioExtractor
import org.example.app.srt.SrtGenerator
import org.example.app.stt.VoskTranscriber
import org.example.app.storage.FileUtils
import java.io.File
import java.io.IOException

/**
 * PUBLIC_INTERFACE
 * TranscriptionWorker orchestrates the offline transcription pipeline:
 * 1) Create a session directory under filesDir/transcripts/yyyyMMdd_HHmmss
 * 2) Extract audio from a provided input video Uri into a mono 16kHz WAV
 * 3) Run offline STT via VoskTranscriber on the WAV using the imported model
 * 4) Generate an SRT subtitle file via SrtGenerator
 * 5) Return success with output paths and session info, or failure with a descriptive message
 *
 * This worker runs in the foreground with a persistent notification ("Transcription" channel).
 *
 * Inputs (Data):
 * - input_video_uri: String Uri of the input video (SAF Uri). Required.
 *
 * Outputs (Data) on success:
 * - output_wav_path: Absolute filesystem path to the generated WAV file
 * - output_srt_path: Absolute filesystem path to the generated SRT file
 * - session_dir: Absolute path to the created session directory
 * - duration_us: Long decoded media duration in microseconds (best-effort)
 *
 * On failure:
 * - message: Descriptive error message
 */
class TranscriptionWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        // Input keys
        const val KEY_INPUT_VIDEO_URI = "input_video_uri"

        // Output keys
        const val KEY_OUTPUT_WAV_PATH = "output_wav_path"
        const val KEY_OUTPUT_SRT_PATH = "output_srt_path"
        const val KEY_SESSION_DIR = "session_dir"
        const val KEY_DURATION_US = "duration_us"

        // Progress keys
        const val PROGRESS_STAGE = "stage"
        const val PROGRESS_MESSAGE = "message"
        const val PROGRESS_PERCENT = "percent"

        // Notification channel
        private const val CHANNEL_ID = "Transcription"
        private const val CHANNEL_NAME = "Transcription"
        private const val NOTIFICATION_ID = 1001
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // Ensure we start foreground early
        setForeground(createForegroundInfo("Preparing…"))

        // Read input
        val inputUriStr = inputData.getString(KEY_INPUT_VIDEO_URI)
        if (inputUriStr.isNullOrBlank()) {
            return@withContext failureResult("Missing input: '$KEY_INPUT_VIDEO_URI'")
        }
        val videoUri = Uri.parse(inputUriStr)

        try {
            // Stage 1: Create session directory
            setProgressPercent(stage = "init", percent = 5, message = "Creating session directory…")
            val sessionDir = FileUtils.createOutputSessionDir(applicationContext)

            // Destinations
            val wavFile = File(sessionDir, "audio.wav")
            val srtFile = File(sessionDir, "subtitles.srt")

            // Stage 2: Audio extraction to WAV
            setForeground(createForegroundInfo("Extracting audio…"))
            setProgressPercent(stage = "extract_audio", percent = 20, message = "Extracting audio to WAV…")
            val meta = AudioExtractor.extractToWav(applicationContext, videoUri, wavFile)

            // Stage 3: Transcription
            setForeground(createForegroundInfo("Transcribing…"))
            setProgressPercent(stage = "transcribe", percent = 50, message = "Transcribing with Vosk…")
            val modelBase = File(applicationContext.filesDir, "models/vosk")
            val modelDir: File? = modelBase.listFiles()?.firstOrNull { it.isDirectory }
            val segments = VoskTranscriber.transcribe(applicationContext, wavFile, modelDir)

            // Stage 4: SRT generation
            setForeground(createForegroundInfo("Generating subtitles…"))
            setProgressPercent(stage = "generate_srt", percent = 80, message = "Generating SRT…")
            SrtGenerator.generate(segments, srtFile)

            // Stage 5: Done
            setProgressPercent(stage = "done", percent = 100, message = "Completed")
            return@withContext Result.success(
                workDataOf(
                    KEY_OUTPUT_WAV_PATH to wavFile.absolutePath,
                    KEY_OUTPUT_SRT_PATH to srtFile.absolutePath,
                    KEY_SESSION_DIR to sessionDir.absolutePath,
                    KEY_DURATION_US to meta.durationUs
                )
            )
        } catch (e: IOException) {
            return@withContext failureResult("I/O error: ${e.message ?: "Unknown"}")
        } catch (e: SecurityException) {
            return@withContext failureResult("Permission error: ${e.message ?: "Unknown"}")
        } catch (e: IllegalStateException) {
            return@withContext failureResult("Processing error: ${e.message ?: "Unknown"}")
        } catch (e: Exception) {
            return@withContext failureResult("Unexpected error: ${e.message ?: "Unknown"}")
        }
    }

    private suspend fun setProgressPercent(stage: String, percent: Int, message: String) {
        setProgress(
            Data.Builder()
                .putString(PROGRESS_STAGE, stage)
                .putInt(PROGRESS_PERCENT, percent.coerceIn(0, 100))
                .putString(PROGRESS_MESSAGE, message)
                .build()
        )
    }

    private fun failureResult(msg: String): Result {
        return Result.failure(workDataOf("message" to msg))
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        // Fallback foreground info if WorkManager requests it implicitly
        return createForegroundInfo("Working…")
    }

    private fun createForegroundInfo(contentText: String): ForegroundInfo {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(nm)

        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download) // system icon to avoid vector dep
            .setContentTitle(applicationContext.getString(R.string.app_name))
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    private fun ensureChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existing = nm.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = applicationContext.getString(R.string.work_fg_channel_desc)
                    enableLights(false)
                    enableVibration(false)
                    lightColor = Color.BLUE
                    setShowBadge(false)
                }
                nm.createNotificationChannel(ch)
            }
        }
    }
}
