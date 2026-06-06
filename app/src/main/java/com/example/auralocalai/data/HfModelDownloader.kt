package com.example.auralocalai.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Exception thrown when a gated model is requested but no Hugging Face API token is provided.
 */
class MissingHfTokenException(message: String) : Exception(message)

/**
 * Exception thrown when the user is unauthorized (HTTP 401).
 */
class UnauthorizedException(message: String) : IOException(message)

/**
 * Exception thrown when the model file is not found (HTTP 404).
 */
class ModelNotFoundException(message: String) : IOException(message)

/**
 * General Exception thrown for other network download errors.
 */
class DownloadException(message: String, val statusCode: Int) : IOException(message)

/**
 * Asynchronous file download utility using OkHttp and Kotlin Coroutines/Flow
 * to handle gated Hugging Face repository model downloads.
 */
object HfModelDownloader {

    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

    /**
     * Downloads a gated model file asynchronously and emits progress.
     * 
     * @param preset The metadata of the model to download.
     * @param destinationFile Local destination File to save the model.
     * @param hfToken Optional Hugging Face API Access Token.
     * @return A Flow emitting pairs of (bytesRead, totalBytes) as the download progresses.
     */
    suspend fun downloadModel(
        preset: ModelPreset,
        destinationFile: File,
        hfToken: String?
    ): Flow<Pair<Long, Long>> = flow {
        // Validate HuggingFace authentication token for gated model downloads
        if (preset.requiresHfToken && hfToken.isNullOrEmpty()) {
            throw MissingHfTokenException(
                "Hugging Face API token is required to download the gated model: ${preset.name}"
            )
        }

        // Initialize OkHttp client with manual redirect handling.
        // Disabling automatic redirects prevents cross-domain token leaks (forwarding HF token to AWS S3).
        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        var currentUrl = preset.downloadUrl
        var redirectCount = 0
        val maxRedirects = 10
        var response: Response? = null

        while (redirectCount < maxRedirects) {
            val parsedUrl = currentUrl.toHttpUrlOrNull()
                ?: throw IllegalArgumentException("Malformed URL format: $currentUrl")

            val requestBuilder = Request.Builder()
                .url(parsedUrl)
                .header("User-Agent", USER_AGENT)

            // Inject the Bearer authorization header ONLY for Hugging Face domains to prevent leaks
            if (isHuggingFaceDomain(parsedUrl) && !hfToken.isNullOrEmpty()) {
                requestBuilder.header("Authorization", "Bearer $hfToken")
            }

            val request = requestBuilder.build()
            val resp = client.newCall(request).execute()

            if (resp.isRedirect) {
                val location = resp.header("Location") ?: throw IOException("Redirect header location is missing")
                val nextUrl = resp.request.url.resolve(location) ?: throw IOException("Invalid redirect target URL: $location")
                currentUrl = nextUrl.toString()
                resp.close()
                redirectCount++
            } else {
                response = resp
                break
            }
        }

        val finalResponse = response ?: throw IOException("Failed to establish a network connection to download server.")

        if (!finalResponse.isSuccessful) {
            val code = finalResponse.code
            finalResponse.close()
            when (code) {
                401 -> throw UnauthorizedException(
                    "Unauthorized (HTTP 401). Hugging Face token is invalid or lacks access permissions for this repository."
                )
                404 -> throw ModelNotFoundException(
                    "Model not found (HTTP 404). Check that the download URL is correct and the model is not deleted."
                )
                else -> throw DownloadException("Download failed with HTTP status code $code", code)
            }
        }

        val body = finalResponse.body ?: throw IOException("Empty response body from download server.")
        val totalBytes = body.contentLength()

        destinationFile.parentFile?.mkdirs()

        body.byteStream().use { input ->
            FileOutputStream(destinationFile).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Long = 0
                var read: Int

                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    bytesRead += read
                    emit(Pair(bytesRead, totalBytes))
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Determines whether the given host belongs to the Hugging Face domain space.
     */
    private fun isHuggingFaceDomain(url: HttpUrl): Boolean {
        val host = url.host
        return host == "huggingface.co" || host.endsWith(".huggingface.co") ||
               host == "hf.co" || host.endsWith(".hf.co")
    }
}
