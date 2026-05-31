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
        emit(DownloadState.Progress(0, 0, 0, 0.0, 0))

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()
        
        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                emit(DownloadState.Error("Failed to download model: HTTP ${response.code}"))
                return@flow
            }

            val body = response.body
            if (body == null) {
                emit(DownloadState.Error("Response body is empty"))
                return@flow
            }

            val totalBytes = body.contentLength()
            val inputStream = body.byteStream()
            
            // Ensure parent directory exists
            destinationFile.parentFile?.mkdirs()
            val outputStream = FileOutputStream(destinationFile)

            val buffer = ByteArray(65536) // 64 KB buffer for high-performance chunked file streaming
            var bytesRead: Int
            var totalBytesRead: Long = 0
            val startTime = System.currentTimeMillis()
            var lastUpdateTime = startTime

            inputStream.use { input ->
                outputStream.use { output ->
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead

                        val currentTime = System.currentTimeMillis()
                        // Limit emissions to avoid overwhelming the Compose UI thread (every 200ms)
                        if (currentTime - lastUpdateTime > 200 || totalBytesRead == totalBytes) {
                            val elapsedMillis = currentTime - startTime
                            val speed = if (elapsedMillis > 0) {
                                (totalBytesRead.toDouble() / elapsedMillis) * 1000.0 // bytes per second
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

            if (totalBytes > 0 && totalBytesRead < totalBytes) {
                emit(DownloadState.Error("Connection interrupted: Only downloaded ${totalBytesRead / (1024 * 1024)} MB of ${totalBytes / (1024 * 1024)} MB. Please check your network and try again."))
                return@flow
            }

            emit(DownloadState.Success(destinationFile.absolutePath))
        } catch (e: IOException) {
            emit(DownloadState.Error(e.message ?: "Network error occurred"))
        }
    }.flowOn(Dispatchers.IO)
}
