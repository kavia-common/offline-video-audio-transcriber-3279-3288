package org.example.app.stt

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * PUBLIC_INTERFACE
 * ModelManager provides utilities to manage offline Vosk models inside the app's internal storage.
 *
 * It supports:
 * - Checking if a valid model exists at filesDir/models/vosk/<modelName>
 * - Importing a model from a user-selected URI (zip file or directory via SAF)
 * - Persisting read permissions for tree/document URIs as needed
 *
 * Storage layout:
 *   <filesDir>/models/vosk/<modelName>/
 * Expected contents:
 *   Either a conf/model.conf file or 'model' directory and essential files such as 'am', 'ivector', 'graph', etc.
 */
object ModelManager {

    // Base directory inside app files
    private fun modelsBaseDir(context: Context): File =
        File(context.filesDir, "models/vosk")

    /**
     * PUBLIC_INTERFACE
     * Returns true if there is at least one valid Vosk model present in the app's models directory.
     */
    suspend fun hasValidModel(context: Context, ioDispatcher: CoroutineDispatcher = Dispatchers.IO): Boolean =
        withContext(ioDispatcher) {
            val base = modelsBaseDir(context)
            if (!base.exists() || !base.isDirectory) return@withContext false
            val candidates = base.listFiles()?.filter { it.isDirectory } ?: emptyList()
            candidates.any { isValidVoskModelDir(it) }
        }

    /**
     * PUBLIC_INTERFACE
     * Returns a user-friendly current model name if one is present, or null.
     */
    suspend fun getCurrentModelName(context: Context, ioDispatcher: CoroutineDispatcher = Dispatchers.IO): String? =
        withContext(ioDispatcher) {
            val base = modelsBaseDir(context)
            if (!base.exists() || !base.isDirectory) return@withContext null
            val candidates = base.listFiles()?.filter { it.isDirectory } ?: emptyList()
            val valid = candidates.firstOrNull { isValidVoskModelDir(it) }
            valid?.name
        }

    /**
     * PUBLIC_INTERFACE
     * Import a model from the provided URI. The source can be:
     * - A .zip file (MIME application/zip or file name ends with .zip)
     * - A directory tree picked via ACTION_OPEN_DOCUMENT_TREE or a folder provided by ACTION_OPEN_DOCUMENT (on some devices)
     *
     * The model will be extracted or copied into <filesDir>/models/vosk/<modelName>.
     * Existing model with the same name will be replaced.
     *
     * Returns the imported model name on success.
     */
    suspend fun importModel(
        context: Context,
        sourceUri: Uri,
        takePersistablePermission: Boolean = true,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ): String = withContext(ioDispatcher) {
        // Persist read permission when possible and requested
        persistPermissionIfNeeded(context, sourceUri, takePersistablePermission)

        val cr = context.contentResolver
        val nameGuess = getDisplayName(cr, sourceUri) ?: sourceUri.lastPathSegment ?: "model"
        val isZip = isZipLike(cr, sourceUri, nameGuess)

        val base = modelsBaseDir(context)
        if (!base.exists()) base.mkdirs()

        val targetModelName = nameGuess.removeSuffix(".zip")
        val targetDir = File(base, sanitizeName(targetModelName))

        if (isZip) {
            // Clear existing
            if (targetDir.exists()) targetDir.deleteRecursively()
            targetDir.mkdirs()
            unzipFromUri(cr, sourceUri, targetDir)
            // Some zips contain a single top-level folder; if so, flatten it
            flattenIfSingleFolder(targetDir)
        } else {
            // Treat as directory/tree; copy recursively
            // Use DocumentsContract to navigate if it's a tree doc
            if (targetDir.exists()) targetDir.deleteRecursively()
            targetDir.mkdirs()
            copyTreeFromUri(cr, sourceUri, targetDir)
            flattenIfSingleFolder(targetDir)
        }

        // Final validation; throw if invalid
        if (!isValidVoskModelDir(targetDir)) {
            // Clean up invalid import
            targetDir.deleteRecursively()
            throw IOException("Imported content does not look like a valid Vosk model.")
        }

        return@withContext targetDir.name
    }

    // --- Helpers ---

    private fun persistPermissionIfNeeded(context: Context, uri: Uri, take: Boolean) {
        if (!take) return
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            val takeFlags = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
        } catch (_: SecurityException) {
            // Not a persistable URI or not granted; ignore
        } catch (_: IllegalArgumentException) {
            // Not a document URI; ignore
        }
    }

    private fun isZipLike(cr: ContentResolver, uri: Uri, nameGuess: String): Boolean {
        val ext = nameGuess.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        if (ext == "zip") return true
        val mime = cr.getType(uri)
        if (mime == "application/zip" || mime == "application/x-zip-compressed") return true
        // Sometimes unknown; try extension from MimeTypeMap
        if (mime == null && ext.isNotEmpty()) {
            val guessed = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            if (guessed == "application/zip") return true
        }
        return false
    }

    private fun getDisplayName(cr: ContentResolver, uri: Uri): String? {
        // Attempt to query display name; fallback to last segment
        return try {
            cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
                }
        } catch (_: Exception) {
            null
        }
    }

    private fun sanitizeName(name: String): String {
        return name.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    private fun unzipFromUri(cr: ContentResolver, uri: Uri, targetDir: File) {
        cr.openInputStream(uri)?.use { input ->
            ZipInputStream(BufferedInputStream(input)).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (entry != null) {
                    val outFile = File(targetDir, entry.name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            var count: Int
                            while (zis.read(buffer).also { count = it } != -1) {
                                fos.write(buffer, 0, count)
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } ?: throw IOException("Unable to open zip input stream.")
    }

    private fun flattenIfSingleFolder(root: File) {
        val children = root.listFiles() ?: return
        if (children.size == 1 && children[0].isDirectory) {
            val inner = children[0]
            // Move contents of inner to root, then delete inner
            inner.listFiles()?.forEach { f ->
                val dest = File(root, f.name)
                if (dest.exists()) dest.deleteRecursively()
                f.renameTo(dest)
            }
            inner.delete()
        }
    }

    private fun copyTreeFromUri(cr: ContentResolver, uri: Uri, targetDir: File) {
        // If it's a tree URI, enumerate children
        val isDocUri = DocumentsContract.isDocumentUri(targetDir.contextOrNull(), uri)
        val isTreeUri = DocumentsContract.isTreeUri(uri)

        if (isTreeUri) {
            copyTreeChildrenFromTree(cr, uri, DocumentsContract.getTreeDocumentId(uri), targetDir)
        } else if (isDocUri) {
            // Single document, may be a directory or file
            val docId = DocumentsContract.getDocumentId(uri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, docId)
            try {
                cr.query(childrenUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val childId = cursor.getString(0)
                        val childName = cursor.getString(1) ?: childId.substringAfterLast(':', childId)
                        val childMime = cursor.getString(2) ?: ""
                        val childDoc = DocumentsContract.buildDocumentUriUsingTree(uri, childId)
                        val out = File(targetDir, childName)
                        if (DocumentsContract.Document.MIME_TYPE_DIR == childMime) {
                            out.mkdirs()
                            copyTreeFromUri(cr, childDoc, out)
                        } else {
                            copySingleFile(cr, childDoc, out)
                        }
                    }
                }
            } catch (_: Exception) {
                // Fallback to copying as a single file
                val name = getDisplayName(cr, uri) ?: "file.bin"
                copySingleFile(cr, uri, File(targetDir, name))
            }
        } else {
            // Not a document URI; attempt openInputStream copy
            val name = getDisplayName(cr, uri) ?: uri.lastPathSegment ?: "file.bin"
            copySingleFile(cr, uri, File(targetDir, name))
        }
    }

    private fun copyTreeChildrenFromTree(cr: ContentResolver, treeUri: Uri, treeDocId: String, outDir: File) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
        cr.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ),
            null, null, null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val childId = cursor.getString(0)
                val childName = cursor.getString(1) ?: childId.substringAfterLast(':', childId)
                val childMime = cursor.getString(2) ?: ""
                val childDoc = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                val out = File(outDir, childName)
                if (DocumentsContract.Document.MIME_TYPE_DIR == childMime) {
                    out.mkdirs()
                    copyTreeChildrenFromTree(cr, treeUri, childId, out)
                } else {
                    copySingleFile(cr, childDoc, out)
                }
            }
        }
    }

    private fun copySingleFile(cr: ContentResolver, uri: Uri, outFile: File) {
        outFile.parentFile?.mkdirs()
        cr.openInputStream(uri)?.use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw IOException("Unable to open input stream for $uri")
    }

    /**
     * Simple heuristic to check Vosk model validity:
     * - has conf/model.conf OR
     * - contains a 'model' directory OR
     * - contains typical Vosk files/folders like 'am', 'ivector', 'graph', etc.
     */
    private fun isValidVoskModelDir(dir: File): Boolean {
        if (!dir.isDirectory) return false
        val confFile = File(dir, "conf/model.conf")
        if (confFile.exists()) return true
        val modelFolder = File(dir, "model")
        if (modelFolder.exists() && modelFolder.isDirectory) return true
        val likely = listOf("am", "ivector", "graph", "phones.txt", "final.mdl", "HCLG.fst")
        val names = dir.list()?.toSet() ?: emptySet()
        if (names.intersect(likely.toSet()).isNotEmpty()) return true
        // Sometimes the actual model is nested under 'vosk-model-*', try to detect
        val subValid = dir.listFiles()?.any { it.isDirectory && isValidVoskModelDir(it) } ?: false
        return subValid
    }

    // Extension to get a Context from File if available (not standard; used to satisfy calling sign)
    // We can't actually derive a Context from File; this is a no-op helper for conditional logic.
    private fun File.contextOrNull(): Context? = null
}
