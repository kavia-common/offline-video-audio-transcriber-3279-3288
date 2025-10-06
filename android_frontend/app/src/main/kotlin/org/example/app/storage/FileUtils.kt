package org.example.app.storage

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Utility methods for common file operations used by the app.
 * All public methods throw IOException on failure to keep error handling explicit.
 */
object FileUtils {

    // PUBLIC_INTERFACE
    /**
     * Create and return a new output session directory as:
     * <filesDir>/transcripts/yyyyMMdd_HHmmss
     *
     * The directory is created atomically; if creation fails, an IOException is thrown.
     *
     * @param context Android Context to resolve filesDir.
     * @return A File pointing to the created session directory.
     * @throws IOException if the directory cannot be created.
     */
    @JvmStatic
    @Throws(IOException::class)
    fun createOutputSessionDir(context: Context): File {
        val base = File(context.filesDir, "transcripts")
        // Ensure base exists
        if (!base.exists() && !base.mkdirs()) {
            if (!base.exists() || !base.isDirectory) {
                throw IOException("Failed to create base output directory: ${base.absolutePath}")
            }
        }

        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val session = File(base, ts)

        if (!session.mkdirs()) {
            // mkdirs returns false if dir exists; but ensure it's a directory
            if (!session.exists() || !session.isDirectory) {
                throw IOException("Failed to create session directory: ${session.absolutePath}")
            }
        }
        return session
    }

    // PUBLIC_INTERFACE
    /**
     * Write the provided text content to the destination file using UTF-8.
     * Parent directories are created if missing.
     *
     * @param file Destination file.
     * @param content Text content to write.
     * @throws IOException if writing fails.
     */
    @JvmStatic
    @Throws(IOException::class)
    fun writeTextToFile(file: File, content: String) {
        file.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                if (!parent.exists() || !parent.isDirectory) {
                    throw IOException("Failed to create parent directories for ${file.absolutePath}")
                }
            }
        }
        try {
            file.writeText(content, charset("UTF-8"))
        } catch (e: Exception) {
            throw IOException("Failed to write text to ${file.absolutePath}: ${e.message}", e)
        }
    }

    // PUBLIC_INTERFACE
    /**
     * Copy the content addressed by the given Uri into the destination File.
     * Parent directories are created if they do not exist.
     *
     * @param context Android Context for ContentResolver access.
     * @param src The source content Uri (e.g., user-selected video or zip).
     * @param dest Destination file to be overwritten/created.
     * @throws IOException if copy fails or the source cannot be opened.
     */
    @JvmStatic
    @Throws(IOException::class)
    fun copyUriToFile(context: Context, src: Uri, dest: File) {
        dest.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                if (!parent.exists() || !parent.isDirectory) {
                    throw IOException("Failed to create parent directories for ${dest.absolutePath}")
                }
            }
        }

        val resolver = context.contentResolver
        val inputStream = resolver.openInputStream(src)
            ?: throw IOException("Unable to open input stream for $src")

        var bis: BufferedInputStream? = null
        var bos: BufferedOutputStream? = null
        try {
            bis = BufferedInputStream(inputStream)
            bos = BufferedOutputStream(FileOutputStream(dest, false))
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var read: Int
            while (true) {
                read = bis.read(buffer)
                if (read <= 0) break
                bos.write(buffer, 0, read)
            }
            bos.flush()
        } catch (e: Exception) {
            throw IOException("Failed to copy $src to ${dest.absolutePath}: ${e.message}", e)
        } finally {
            safeClose(bis)
            safeClose(bos)
            safeClose(inputStream)
        }
    }

    // PUBLIC_INTERFACE
    /**
     * Format milliseconds as an SRT-compliant timecode string "HH:MM:SS,mmm".
     * Negative values are clamped to 0.
     *
     * @param ms Milliseconds to format.
     * @return Timecode string as "HH:MM:SS,mmm".
     */
    @JvmStatic
    fun formatMsToSrtTimecode(ms: Long): String {
        val clamped = if (ms < 0) 0L else ms
        val hours = clamped / 3_600_000
        val minutes = (clamped % 3_600_000) / 60_000
        val seconds = (clamped % 60_000) / 1_000
        val millis = clamped % 1_000
        return String.format("%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
    }

    /**
     * Safely close a Closeable without throwing. Use when finalizing resources
     * in finally blocks. Any exception is swallowed.
     */
    fun safeClose(closeable: Closeable?) {
        try {
            closeable?.close()
        } catch (_: Exception) {
        }
    }

    /**
     * Safely close an AutoCloseable without throwing. Overload for types that
     * are not java.io.Closeable but implement AutoCloseable (e.g., RandomAccessFile).
     */
    fun safeClose(closeable: AutoCloseable?) {
        try {
            closeable?.close()
        } catch (_: Exception) {
        }
    }
}
