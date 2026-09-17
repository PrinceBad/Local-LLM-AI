package com.example.auralocalai.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.auralocalai.MainActivity
import com.example.auralocalai.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "ModelDownloadService"

sealed interface ServiceDownloadState {
    data object Idle : ServiceDownloadState
    data class Progress(
        val modelId: String,
        val fileName: String,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val percentage: Int,
        val speedBytesPerSec: Double,
        val etaSeconds: Long
    ) : ServiceDownloadState
    data class Success(val modelId: String, val fileName: String, val filePath: String) : ServiceDownloadState
    data class Error(val modelId: String, val fileName: String, val message: String) : ServiceDownloadState
}

class ModelDownloadService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var activeDownloadJob: Job? = null
    private lateinit var downloader: ModelDownloader
    private lateinit var notificationManager: NotificationManager

    companion object {
        private const val CHANNEL_ID = "model_download_channel"
        private const val NOTIFICATION_ID = 1001
        val downloadState = MutableStateFlow<ServiceDownloadState>(ServiceDownloadState.Idle)
    }

    override fun onCreate() {
        super.onCreate()
        downloader = ModelDownloader()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra("url") ?: ""
        val fileName = intent?.getStringExtra("fileName") ?: ""
        val modelId = intent?.getStringExtra("modelId") ?: ""
        val hfToken = intent?.getStringExtra("hfToken") ?: ""

        if (url.isBlank() || fileName.isBlank() || modelId.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Start Foreground Service safely
        startForegroundServiceCompat(modelId, fileName)

        // Cancel any active download before starting a new one
        activeDownloadJob?.cancel()

        activeDownloadJob = serviceScope.launch {
            val storageDir = File(getExternalFilesDir(null) ?: filesDir, "models")
            val tempFile = File(storageDir, "$fileName.tmp")
            val destFile = File(storageDir, fileName)

            downloader.downloadModel(url, tempFile, hfToken).collect { state ->
                when (state) {
                    is DownloadState.Idle -> {
                        downloadState.value = ServiceDownloadState.Idle
                    }
                    is DownloadState.Progress -> {
                        downloadState.value = ServiceDownloadState.Progress(
                            modelId = modelId,
                            fileName = fileName,
                            bytesDownloaded = state.bytesDownloaded,
                            totalBytes = state.totalBytes,
                            percentage = state.percentage,
                            speedBytesPerSec = state.speedBytesPerSec,
                            etaSeconds = state.etaSeconds
                        )
                        updateProgressNotification(modelId, fileName, state.percentage, state.speedBytesPerSec)
                    }
                    is DownloadState.Success -> {
                        val renameSuccess = ModelSafetyValidator.moveFileSafely(tempFile, destFile)

                        if (renameSuccess) {
                            downloadState.value = ServiceDownloadState.Success(
                                modelId = modelId,
                                fileName = fileName,
                                filePath = destFile.absolutePath
                            )
                            showCompletionNotification(modelId, fileName, true)
                        } else {
                            if (tempFile.exists()) {
                                tempFile.delete()
                            }
                            downloadState.value = ServiceDownloadState.Error(
                                modelId = modelId,
                                fileName = fileName,
                                message = "Failed to finalize downloaded model file."
                            )
                            showCompletionNotification(modelId, fileName, false)
                        }
                        stopSelf()
                    }
                    is DownloadState.Error -> {
                        downloadState.value = ServiceDownloadState.Error(
                            modelId = modelId,
                            fileName = fileName,
                            message = state.message
                        )
                        showCompletionNotification(modelId, fileName, false)
                        stopSelf()
                    }
                }
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        activeDownloadJob?.cancel()
        serviceJob.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Model Downloader",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress of model downloads running in the background"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundServiceCompat(modelId: String, fileName: String) {
        try {
            val notification = buildNotification(
                title = "Downloading Model",
                content = "Starting download for $fileName...",
                progress = 0,
                indeterminate = true
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed (ignoring to allow download to proceed): ${e.message}")
        }
    }

    private fun updateProgressNotification(modelId: String, fileName: String, percentage: Int, speed: Double) {
        try {
            val speedText = formatSpeed(speed)
            val notification = buildNotification(
                title = "Downloading $fileName",
                content = "$percentage% completed • $speedText",
                progress = percentage,
                indeterminate = false
            )
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.d(TAG, "Notification update skipped: ${e.message}")
        }
    }

    private fun showCompletionNotification(modelId: String, fileName: String, success: Boolean) {
        try {
            val title = if (success) "Download Successful" else "Download Failed"
            val content = if (success) "Successfully downloaded $fileName." else "Failed to download $fileName."
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(if (success) R.drawable.ic_download_done else R.drawable.ic_download)
                .setContentTitle(title)
                .setContentText(content)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(NOTIFICATION_ID + 1, notification)
        } catch (e: Exception) {
            Log.d(TAG, "Completion notification skipped: ${e.message}")
        }
    }

    private fun buildNotification(title: String, content: String, progress: Int, indeterminate: Boolean): android.app.Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(content)
            .setProgress(100, progress, indeterminate)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun formatSpeed(bytesPerSec: Double): String {
        val mbps = bytesPerSec / (1024 * 1024)
        if (mbps >= 1.0) {
            return String.format(java.util.Locale.US, "%.1f MB/s", mbps)
        }
        val kbps = bytesPerSec / 1024
        return String.format(java.util.Locale.US, "%.1f KB/s", kbps)
    }
}
