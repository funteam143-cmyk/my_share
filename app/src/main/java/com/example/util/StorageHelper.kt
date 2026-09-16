package com.example.util

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.DecimalFormat
import java.util.Locale

object StorageHelper {

    /**
     * Sanitizes file names to prevent path traversal attacks (e.g., "../../system/file").
     * Enforces safe file naming according to Scoped Storage requirements.
     */
    fun sanitizeFileName(rawName: String): String {
        var cleanName = File(rawName).name // Extract only the last segment
        cleanName = cleanName.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1F]"), "_")
        cleanName = cleanName.trim()
        if (cleanName.isEmpty() || cleanName == "." || cleanName == "..") {
            cleanName = "file_${System.currentTimeMillis()}"
        }
        // Limit max length to avoid filesystem truncation errors
        if (cleanName.length > 200) {
            val extIndex = cleanName.lastIndexOf('.')
            cleanName = if (extIndex in 1..195) {
                val ext = cleanName.substring(extIndex)
                cleanName.substring(0, 195 - ext.length) + ext
            } else {
                cleanName.substring(0, 200)
            }
        }
        return cleanName
    }

    /**
     * Resolves metadata (display name and size) for any Uri picked via SAF.
     */
    fun resolveUriMetadata(contentResolver: ContentResolver, uri: Uri): Pair<String, Long> {
        var name = "unknown_file"
        var size = 0L

        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1 && !cursor.isNull(nameIndex)) {
                        name = cursor.getString(nameIndex)
                    }
                    if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) {
                        size = cursor.getLong(sizeIndex)
                    }
                }
            }
        } catch (_: Exception) {
            // Fallback to uri path segment
            uri.lastPathSegment?.let { name = it }
        }

        return Pair(sanitizeFileName(name), size)
    }

    /**
     * Creates an output destination for a received file inside Downloads/FKShare.
     * Guarantees that files are not overwritten accidentally by generating unique suffixes if needed.
     */
    fun createReceivedFile(context: Context, fileName: String): File {
        val safeName = sanitizeFileName(fileName)
        val downloadDir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: context.filesDir,
            "FKShare"
        )
        if (!downloadDir.exists()) {
            downloadDir.mkdirs()
        }

        var targetFile = File(downloadDir, safeName)
        if (targetFile.exists()) {
            val baseName = safeName.substringBeforeLast('.', safeName)
            val extension = if (safeName.contains('.')) "." + safeName.substringAfterLast('.') else ""
            var counter = 1
            while (targetFile.exists()) {
                targetFile = File(downloadDir, "${baseName}_$counter$extension")
                counter++
            }
        }
        return targetFile
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
        val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
        return DecimalFormat("#,##0.#").format(value) + " " + units[digitGroups]
    }

    fun formatSpeed(mbps: Double): String {
        return if (mbps >= 100) {
            String.format(Locale.US, "%.1f MB/s", mbps)
        } else if (mbps >= 1.0) {
            String.format(Locale.US, "%.2f MB/s", mbps)
        } else {
            val kbps = mbps * 1024.0
            String.format(Locale.US, "%.1f KB/s", kbps)
        }
    }

    fun formatDuration(seconds: Long): String {
        if (seconds < 0) return "--:--"
        val mins = seconds / 60
        val secs = seconds % 60
        return if (mins >= 60) {
            val hours = mins / 60
            val remMins = mins % 60
            String.format(Locale.US, "%02d:%02d:%02d", hours, remMins, secs)
        } else {
            String.format(Locale.US, "%02d:%02d", mins, secs)
        }
    }
}
