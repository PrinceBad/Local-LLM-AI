package com.example.auralocalai.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import android.util.Log

private const val TAG = "ModelDownloader"
private const val DEFAULT_BUFFER_SIZE_BYTES = 65536 // 64 KB

sealed interface DownloadState {
    data object Idle : DownloadState
    data class Progress(
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val percentage: Int,
        val speedBytesPerSec: Double,
        val etaSeconds: Long
    ) : DownloadState
    data class Success(val filePath: String) : DownloadState
    data class Error(val message: String) : DownloadState
}

class ModelDownloader {

    /**
     * Validates if the HuggingFace token has access to a specific URL by testing a HEAD request.
     * Uses domain-scoped auth: only sends token to huggingface.co / hf.co domains.
     */
    fun validateTokenAccess(fileUrl: String, hfToken: String): String {
        return try {
            Log.i(TAG, "Validating token access via HEAD: $fileUrl")
            val url = URL(fileUrl)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = 10_000
                readTimeout = 10_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Mozilla/5.0")
                if (hfToken.isNotBlank() && isHuggingFaceDomain(url.host ?: "")) {
                    setRequestProperty("Authorization", "Bearer $hfToken")
                }
            }

            val responseCode = connection.responseCode
            Log.d(TAG, "HEAD response code: $responseCode")
            connection.disconnect()

            when (responseCode) {
                in 200..299, 206, in 301..308 -> "OK"
                401 -> "Token is INVALID or REVOKED (HTTP 401). Check your HF token."
                403 -> "Access DENIED (HTTP 403). This model may be gated.\nVisit the model page on HuggingFace to accept the license."
                404 -> "File not found (HTTP 404). Check the download URL."
                else -> "Unexpected HTTP $responseCode from HuggingFace."
            }
        } catch (e: Exception) {
            "Could not validate token: ${e.message}"
        }
    }

    /**
     * Returns true if the given hostname is a HuggingFace domain.
     * Auth headers should ONLY be sent to these domains.
     * Sending auth to redirect targets (like AWS S3 pre-signed URLs) will cause 403 errors.
     */
    private fun isHuggingFaceDomain(host: String): Boolean {
        return host == "huggingface.co" || host.endsWith(".huggingface.co") ||
               host == "hf.co" || host.endsWith(".hf.co")
    }

    /**
     * Opens an HttpURLConnection with manual redirect handling that preserves
     * Authorization headers only for HuggingFace domains.
     * 
     * Java's HttpURLConnection drops auth headers on cross-domain redirects.
     * HuggingFace redirects downloads to AWS S3 pre-signed URLs.
     * Sending the HF token to S3 causes signature verification failures (403).
     */
    private fun openConnectionWithAuthRedirects(
        initialUrl: String,
        hfToken: String,
        rangeStart: Long? = null
    ): HttpURLConnection {
        var currentUrl = initialUrl
        var redirectCount = 0
        val maxRedirects = 10

        while (redirectCount < maxRedirects) {
            val url = URL(currentUrl)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = false // Manually handle redirects to preserve auth
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")

                // Only send HF token to HuggingFace domains, NOT to S3 redirect targets
                val host = url.host ?: ""
                if (isHuggingFaceDomain(host) && hfToken.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer $hfToken")
                    Log.d(TAG, "Sending auth header to HF domain: $host")
                }
                if (rangeStart != null && rangeStart > 0) {
                    setRequestProperty("Range", "bytes=$rangeStart-")
                }
            }

            val responseCode = connection.responseCode
            Log.d(TAG, "Connection attempt $redirectCount: HTTP $responseCode from ${url.host}")

            when {
                responseCode in 200..299 || responseCode == 206 -> {
                    return connection
                }
                responseCode in 301..308 -> {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (location == null) {
                        throw RuntimeException("HTTP $responseCode but no Location header")
                    }
                    val prevUrl = URL(currentUrl)
                    currentUrl = URL(prevUrl, location).toString()
                    redirectCount++
                    Log.d(TAG, "Redirect $responseCode -> ${URL(currentUrl).host}")
                }
                responseCode == 416 -> {
                    return connection // Range not satisfiable - file already complete
                }
                else -> {
                    connection.disconnect()
                    val errorMsg = if (responseCode == 403 && hfToken.isNotBlank()) {
                        "HTTP 403 Forbidden. The model may be gated or the token lacks access.\n" +
                        "Visit the model page on HuggingFace to accept the license."
                    } else if (responseCode == 403) {
                        "HTTP 403 Forbidden. This model may require a HuggingFace token.\n" +
                        "Set your token in Settings > HuggingFace Token."
                    } else {
                        "HTTP $responseCode"
                    }
                    throw RuntimeException("Download failed: $errorMsg")
                }
            }
        }
        throw RuntimeException("Too many redirects (>$maxRedirects)")
    }

    fun downloadModel(url: String, destinationFile: File, hfToken: String = ""): Flow<DownloadState> = flow {
        Log.i(TAG, "Preparing download from $url")

        // Ensure parent directory exists
        destinationFile.parentFile?.mkdirs()

        // Check existing bytes for resume
        var downloadedBytes = if (destinationFile.exists()) destinationFile.length() else 0L
        val isResuming = downloadedBytes > 0L
        Log.d(TAG, "File check: exists=${destinationFile.exists()}, size=$downloadedBytes, resuming=$isResuming")

        // Open connection with domain-scoped auth and manual redirect handling
        val connection = openConnectionWithAuthRedirects(
            initialUrl = url,
            hfToken = hfToken,
            rangeStart = if (isResuming) downloadedBytes else null
        )

        val responseCode = connection.responseCode

        // HTTP 416: Range Not Satisfiable - file is already fully downloaded
        if (responseCode == 416) {
            connection.disconnect()
            Log.i(TAG, "HTTP 416: File already complete at $downloadedBytes bytes")
            emit(DownloadState.Success(destinationFile.absolutePath))
            return@flow
        }

        if (responseCode !in 200..299 && responseCode != 206) {
            connection.disconnect()
            if (isResuming && responseCode == 400) {
                destinationFile.delete()
            }
            emit(DownloadState.Error("Failed to download: HTTP $responseCode"))
            return@flow
        }

        // Determine total file size from response headers
        var totalBytes: Long = -1L
        try {
            val contentRange = connection.getHeaderField("Content-Range")
            val contentLength = connection.getHeaderField("Content-Length")?.toLongOrNull()

            if (contentRange != null) {
                // Format: "bytes start-end/total"
                val total = contentRange.substringAfter('/').toLongOrNull()
                if (total != null && total > 0) totalBytes = total

                // Verify server resumed from correct position
                val rangeStart = contentRange.substringAfter(' ').substringBefore('-').toLongOrNull()
                if (rangeStart != null && rangeStart != downloadedBytes) {
                    Log.w(TAG, "Server resumed at $rangeStart but local file is $downloadedBytes. Truncating.")
                    if (destinationFile.exists()) {
                        RandomAccessFile(destinationFile, "rw").use { it.setLength(rangeStart) }
                    }
                    downloadedBytes = rangeStart
                }
            } else if (contentLength != null && contentLength > 0) {
                totalBytes = if (isResuming && responseCode == 206) {
                    downloadedBytes + contentLength
                } else {
                    contentLength
                }
            }
        } catch (_: Exception) { /* ignore header parse errors */ }

        // If server ignored Range header (returned 200 instead of 206), restart from 0
        if (isResuming && responseCode == 200) {
            Log.w(TAG, "Server ignored Range header. Restarting full download.")
            if (destinationFile.exists()) destinationFile.delete()
            destinationFile.parentFile?.mkdirs()
            destinationFile.createNewFile()
            downloadedBytes = 0L
        }

        Log.i(TAG, "Connected. HTTP $responseCode, total=$totalBytes, startFrom=$downloadedBytes")

        // Emit initial progress
        emit(DownloadState.Progress(
            bytesDownloaded = downloadedBytes,
            totalBytes = if (totalBytes > 0) totalBytes else 0L,
            percentage = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt() else 0,
            speedBytesPerSec = 0.0,
            etaSeconds = 0L
        ))

        try {
            connection.inputStream.use { input ->
                // Use RandomAccessFile for precise seek-based resume
                RandomAccessFile(destinationFile, "rw").use { raf ->
                    raf.seek(downloadedBytes)

                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE_BYTES)
                    var bytesRead: Int
                    var sessionBytesRead = 0L
                    val startTime = System.currentTimeMillis()
                    var lastUpdateTime = startTime
                    var bytesSinceLastEmit = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        raf.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        sessionBytesRead += bytesRead
                        bytesSinceLastEmit += bytesRead

                        val currentTime = System.currentTimeMillis()
                        val elapsed = currentTime - lastUpdateTime

                        // Adaptive emit: faster for small files, slower for large
                        val shouldEmit = if (downloadedBytes < 10_000_000) elapsed > 250 else elapsed > 1000

                        if (shouldEmit || (totalBytes > 0 && downloadedBytes >= totalBytes)) {
                            val sessionElapsed = currentTime - startTime
                            val speed = if (sessionElapsed > 0) {
                                (sessionBytesRead.toDouble() / sessionElapsed) * 1000.0
                            } else 0.0

                            val effectiveTotal = if (totalBytes > 0) totalBytes else downloadedBytes
                            val eta = if (speed > 0 && totalBytes > 0) {
                                ((totalBytes - downloadedBytes) / speed).toLong()
                            } else 0L

                            val percentage = if (effectiveTotal > 0) {
                                ((downloadedBytes * 100) / effectiveTotal).toInt()
                            } else 0

                            emit(DownloadState.Progress(
                                bytesDownloaded = downloadedBytes,
                                totalBytes = effectiveTotal,
                                percentage = percentage,
                                speedBytesPerSec = speed,
                                etaSeconds = eta
                            ))
                            lastUpdateTime = currentTime
                            bytesSinceLastEmit = 0L
                        }

                        if (totalBytes > 0 && downloadedBytes >= totalBytes) break
                    }

                    // Final emit
                    val finalTotal = if (totalBytes > 0) maxOf(totalBytes, downloadedBytes) else downloadedBytes
                    emit(DownloadState.Progress(
                        bytesDownloaded = downloadedBytes,
                        totalBytes = finalTotal,
                        percentage = 100,
                        speedBytesPerSec = 0.0,
                        etaSeconds = 0L
                    ))
                }
            }
        } catch (e: IOException) {
            // Preserve partial file for future resume
            emit(DownloadState.Error(
                "Connection interrupted at ${downloadedBytes / (1024 * 1024)} MB" +
                (if (totalBytes > 0) " / ${totalBytes / (1024 * 1024)} MB" else "") +
                ". Tap Download again to resume."
            ))
            return@flow
        } finally {
            connection.disconnect()
        }

        // Final sanity check
        if (totalBytes > 0 && downloadedBytes < totalBytes) {
            emit(DownloadState.Error(
                "Download incomplete: ${downloadedBytes / (1024 * 1024)} MB of " +
                "${totalBytes / (1024 * 1024)} MB. Tap Download again to resume."
            ))
            return@flow
        }

        // Validate downloaded file isn't a tiny error page
        if (destinationFile.length() < 1024) {
            Log.w(TAG, "Downloaded file is suspiciously small (${destinationFile.length()} bytes). May be an error page.")
            val content = destinationFile.readText().take(200)
            if (content.contains("<!DOCTYPE", ignoreCase = true) || content.contains("<html", ignoreCase = true)) {
                destinationFile.delete()
                emit(DownloadState.Error("Download returned an HTML error page instead of model data. Check the URL or token."))
                return@flow
            }
        }

        // Run integrity validation check on downloaded model package
        if (!isValidModelFile(destinationFile)) {
            Log.e(TAG, "Integrity check failed for: ${destinationFile.absolutePath}")
            destinationFile.delete()
            emit(DownloadState.Error("Downloaded file is corrupted or is not a valid model package (failed Flatbuffer/ZIP structure validation). Please delete and re-download."))
            return@flow
        }

        Log.i(TAG, "Download complete. Total bytes: $downloadedBytes")
        emit(DownloadState.Success(destinationFile.absolutePath))
    }.flowOn(Dispatchers.IO)
}
