package com.example.auralocalai.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.TimeUnit

private const val TAG = "ModelDownloader"
private const val DEFAULT_BUFFER_SIZE_BYTES = 524288 // 512 KB

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

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false) // Handle redirects manually to manage tokens securely
        .followSslRedirects(false)
        .build()

    /**
     * Validates if the HuggingFace token has access to a specific URL by testing a HEAD request.
     * Uses domain-scoped auth: only sends token to huggingface.co / hf.co domains.
     */
    fun validateTokenAccess(fileUrl: String, hfToken: String): String {
        return try {
            Log.i(TAG, "Validating token access via HEAD: $fileUrl")
            val httpUrl = fileUrl.toHttpUrlOrNull() ?: return "Invalid URL format"
            val requestBuilder = Request.Builder()
                .url(httpUrl)
                .head()
                .header("User-Agent", "Mozilla/5.0")

            val host = httpUrl.host
            if (hfToken.isNotBlank() && isHuggingFaceDomain(host) && !isPresignedUrl(httpUrl)) {
                requestBuilder.header("Authorization", "Bearer $hfToken")
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                val responseCode = response.code
                Log.d(TAG, "HEAD response code: $responseCode")
                when (responseCode) {
                    in 200..299, 206, in 301..308 -> "OK"
                    401 -> "Token is INVALID or REVOKED (HTTP 401). Check your HF token."
                    403 -> "Access DENIED (HTTP 403). This model may be gated.\nVisit the model page on HuggingFace to accept the license."
                    404 -> "File not found (HTTP 404). Check the download URL."
                    else -> "Unexpected HTTP $responseCode from HuggingFace."
                }
            }
        } catch (e: Exception) {
            "Could not validate token: ${e.message}"
        }
    }

    /**
     * Returns true if the given URL is an AWS pre-signed URL.
     * We should never attach Authorization headers to pre-signed URLs.
     */
    private fun isPresignedUrl(url: okhttp3.HttpUrl): Boolean {
        val query = url.query ?: return false
        return query.contains("Signature=") || 
               query.contains("X-Amz-Signature=") || 
               query.contains("AWSAccessKeyId=")
    }

    /**
     * Returns true if the given hostname is a core HuggingFace domain.
     * Auth headers should ONLY be sent to core auth endpoints.
     */
    private fun isHuggingFaceDomain(host: String): Boolean {
        return host == "huggingface.co" || host == "hf.co" || 
               host == "api-face.huggingface.co" || host == "api.huggingface.co"
    }

    /**
     * Opens an OkHttp connection with manual redirect handling that preserves
     * Authorization headers only for HuggingFace domains.
     */
    private fun openConnectionWithAuthRedirects(
        initialUrl: String,
        hfToken: String,
        rangeStart: Long? = null
    ): Response {
        var currentUrl = initialUrl
        var redirectCount = 0
        val maxRedirects = 10

        while (redirectCount < maxRedirects) {
            val httpUrl = currentUrl.toHttpUrlOrNull() 
                ?: throw IllegalArgumentException("Invalid URL: $currentUrl")

            val requestBuilder = Request.Builder()
                .url(httpUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")

            // Only send HF token to core HuggingFace domains, NOT to pre-signed redirect targets
            val host = httpUrl.host
            if (isHuggingFaceDomain(host) && !isPresignedUrl(httpUrl) && hfToken.isNotBlank()) {
                requestBuilder.header("Authorization", "Bearer $hfToken")
                Log.d(TAG, "Sending auth header to HF domain: $host")
            }
            if (rangeStart != null && rangeStart > 0) {
                requestBuilder.header("Range", "bytes=$rangeStart-")
            }

            val response = client.newCall(requestBuilder.build()).execute()
            val responseCode = response.code
            Log.d(TAG, "Connection attempt $redirectCount: HTTP $responseCode from ${httpUrl.host}")

            if (response.isRedirect) {
                val location = response.header("Location")
                response.close()
                if (location == null) {
                    throw IOException("HTTP $responseCode but no Location header")
                }
                // Resolve relative redirect URLs against the request URL
                currentUrl = httpUrl.resolve(location)?.toString() ?: location
                redirectCount++
                Log.d(TAG, "Redirect $responseCode -> ${currentUrl.toHttpUrlOrNull()?.host}")
            } else {
                return response
            }
        }
        throw IOException("Too many redirects (>$maxRedirects)")
    }

    fun downloadModel(url: String, destinationFile: File, hfToken: String = ""): Flow<DownloadState> = flow {
        Log.i(TAG, "Preparing download from $url")
        var response: Response? = null
        var downloadedBytes = 0L
        var totalBytes = -1L

        try {
            // Ensure parent directory exists
            destinationFile.parentFile?.mkdirs()

            // Check existing bytes for resume
            downloadedBytes = if (destinationFile.exists()) destinationFile.length() else 0L
            val isResuming = downloadedBytes > 0L
            Log.d(TAG, "File check: exists=${destinationFile.exists()}, size=$downloadedBytes, resuming=$isResuming")

            // Open connection with domain-scoped auth and manual redirect handling
            val currentResponse = openConnectionWithAuthRedirects(
                initialUrl = url,
                hfToken = hfToken,
                rangeStart = if (isResuming) downloadedBytes else null
            )
            response = currentResponse

            val responseCode = currentResponse.code

            // HTTP 416: Range Not Satisfiable - file is already fully downloaded
            if (responseCode == 416) {
                currentResponse.close()
                Log.i(TAG, "HTTP 416: File already complete at $downloadedBytes bytes")
                emit(DownloadState.Success(destinationFile.absolutePath))
                return@flow
            }

            if (!currentResponse.isSuccessful && responseCode != 206) {
                currentResponse.close()
                if (isResuming && responseCode == 400) {
                    destinationFile.delete()
                }
                val errorMsg = if (responseCode == 403 && hfToken.isNotBlank()) {
                    "HTTP 403 Forbidden. The model may be gated or the token lacks access.\n" +
                    "Visit the model page on HuggingFace to accept the license."
                } else if (responseCode == 403) {
                    "HTTP 403 Forbidden. This model may require a HuggingFace token.\n" +
                    "Set your token in Settings > HuggingFace Token."
                } else {
                    "HTTP $responseCode"
                }
                emit(DownloadState.Error("Failed to download: $errorMsg"))
                return@flow
            }

            // Determine total file size from response headers
            try {
                val contentRange = currentResponse.header("Content-Range")
                val contentLength = currentResponse.header("Content-Length")?.toLongOrNull()

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

            val body = currentResponse.body ?: throw IOException("Empty response body")
            body.byteStream().use { input ->
                // Use RandomAccessFile for precise seek-based resume
                RandomAccessFile(destinationFile, "rw").use { raf ->
                    raf.seek(downloadedBytes)

                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE_BYTES)
                    var bytesRead: Int
                    var sessionBytesRead = 0L
                    val startTime = System.currentTimeMillis()
                    var lastUpdateTime = startTime

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        raf.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        sessionBytesRead += bytesRead

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
        } catch (e: Exception) {
            Log.e(TAG, "Download error", e)
            emit(DownloadState.Error(
                "Connection interrupted: ${e.localizedMessage ?: "Unknown error"}. Tap Download again to resume."
            ))
            return@flow
        } finally {
            response?.close()
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
            val content = try { destinationFile.readText().take(200) } catch (_: Exception) { "" }
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
