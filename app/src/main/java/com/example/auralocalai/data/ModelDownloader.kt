package com.example.auralocalai.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

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

class ModelDownloader(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) {

    fun downloadModel(url: String, destinationFile: File): Flow<DownloadState> = flow {
        // Check how many bytes we already have for potential resume
        val existingBytes = if (destinationFile.exists()) destinationFile.length() else 0L
        val isResuming = existingBytes > 0L

        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")

        // Add Range header if we have a partial file to resume from
        if (isResuming) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
        }

        val request = requestBuilder.build()

        try {
            val response = client.newCall(request).execute()

            // HTTP 416: range not satisfiable — file is already fully downloaded
            if (response.code == 416) {
                response.close()
                emit(DownloadState.Success(destinationFile.absolutePath))
                return@flow
            }

            if (!response.isSuccessful) {
                response.close()
                // If server rejected Range header with 400, delete corrupted partial
                if (isResuming && response.code == 400) {
                    destinationFile.delete()
                }
                emit(DownloadState.Error("Failed to download model: HTTP ${response.code}"))
                return@flow
            }

            // HTTP 206 = server accepted the Range request (resume mode)
            val isResume = response.code == 206

            val body = response.body
            if (body == null) {
                response.close()
                emit(DownloadState.Error("Response body is empty"))
                return@flow
            }

            // Determine total file size:
            // - On 206, parse Content-Range: bytes start-end/total header
            // - On 200 (server ignored Range), content-length is the full file size
            val totalBytes: Long = if (isResume) {
                val contentRange = response.header("Content-Range")
                contentRange?.substringAfterLast('/')?.toLongOrNull()
                    ?: (existingBytes + body.contentLength())
            } else {
                body.contentLength()
            }

            // On a 200 response (server ignored Range), restart from byte 0
            val startOffset = if (isResume) existingBytes else 0L

            // Emit initial progress so UI shows already-downloaded progress immediately
            emit(DownloadState.Progress(
                bytesDownloaded = startOffset,
                totalBytes = totalBytes,
                percentage = if (totalBytes > 0) ((startOffset * 100) / totalBytes).toInt() else 0,
                speedBytesPerSec = 0.0,
                etaSeconds = 0L
            ))

            // Ensure parent directory exists
            destinationFile.parentFile?.mkdirs()

            // Append mode on resume, overwrite on fresh download
            val outputStream = FileOutputStream(destinationFile, isResume)

            val buffer = ByteArray(65536) // 64 KB buffer for high-performance chunked streaming
            var bytesRead: Int
            var totalBytesRead: Long = startOffset    // total file bytes written so far
            var sessionBytesRead: Long = 0L           // bytes written in this session (for speed)
            val startTime = System.currentTimeMillis()
            var lastUpdateTime = startTime

            try {
                body.byteStream().use { input ->
                    outputStream.use { output ->
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead
                            sessionBytesRead += bytesRead

                            val currentTime = System.currentTimeMillis()
                            // Throttle UI updates to every 200ms to avoid overwhelming Compose
                            if (currentTime - lastUpdateTime > 200 || totalBytesRead == totalBytes) {
                                val elapsedMillis = currentTime - startTime
                                // Speed based on bytes downloaded in THIS session only
                                val speed = if (elapsedMillis > 0) {
                                    (sessionBytesRead.toDouble() / elapsedMillis) * 1000.0
                                } else {
                                    0.0
                                }

                                val eta = if (speed > 0 && totalBytes > 0) {
                                    ((totalBytes - totalBytesRead) / speed).toLong()
                                } else {
                                    0L
                                }

                                val percentage = if (totalBytes > 0) {
                                    ((totalBytesRead * 100) / totalBytes).toInt()
                                } else {
                                    0
                                }

                                emit(DownloadState.Progress(
                                    bytesDownloaded = totalBytesRead,
                                    totalBytes = totalBytes,
                                    percentage = percentage,
                                    speedBytesPerSec = speed,
                                    etaSeconds = eta
                                ))
                                lastUpdateTime = currentTime
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                // Preserve partial file for future resume — do NOT delete it
                emit(DownloadState.Error(
                    "Connection interrupted at ${totalBytesRead / (1024 * 1024)} MB / " +
                    "${totalBytes / (1024 * 1024)} MB. Tap Download again to resume."
                ))
                return@flow
            }

            // Final sanity check — server closed connection before sending all data
            if (totalBytes > 0 && totalBytesRead < totalBytes) {
                // Keep the partial file — allow resume on next attempt
                emit(DownloadState.Error(
                    "Download incomplete: ${totalBytesRead / (1024 * 1024)} MB of " +
                    "${totalBytes / (1024 * 1024)} MB. Tap Download again to resume."
                ))
                return@flow
            }

            emit(DownloadState.Success(destinationFile.absolutePath))
        } catch (e: IOException) {
            // Top-level network failure (DNS, connection refused, etc.)
            // Preserve partial file for future resume
            emit(DownloadState.Error(e.message ?: "Network error. Tap Download again to retry."))
        }
    }.flowOn(Dispatchers.IO)
}
