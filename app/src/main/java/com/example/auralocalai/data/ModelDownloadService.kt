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
import androidx.core.app.NotificationCompat
import com.example.auralocalai.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

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

        // Start Foreground Service
        startForegroundServiceCompat(modelId, fileName)

        // Cancel any active download before starting a new one
        activeDownloadJob?.cancel()

        activeDownloadJob = serviceScope.launch {
            val storageDir = File(getExternalFilesDir(null) ?: filesDir, "models")
            val destFile = File(storageDir, fileName)

            downloader.downloadModel(url, destFile, hfToken).collect { state ->
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
                        downloadState.value = ServiceDownloadState.Success(
                            modelId = modelId,
                            fileName = fileName,
                            filePath = state.filePath
                        )
                        showCompletionNotification(modelId, fileName, true)
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

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        activeDownloadJob?.cancel()
        serviceJob.cancel()
        downloadState.value = ServiceDownloadState.Idle
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
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundServiceCompat(modelId: String, fileName: String) {
        val notification = BuildNotification(
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
    }

    private fun updateProgressNotification(modelId: String, fileName: String, percentage: Int, speed: Double) {
        val speedText = formatSpeed(speed)
        val notification = BuildNotification(
            title = "Downloading $fileName",
            content = "$percentage% completed • $speedText",
            progress = percentage,
            indeterminate = false
        )
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun showCompletionNotification(modelId: String, fileName: String, success: Boolean) {
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
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun BuildNotification(title: String, content: String, progress: Int, indeterminate: Boolean): android.app.Notification {
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
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(content)
            .setProgress(100, progress, indeterminate)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun formatSpeed(bytesPerSec: Double): String {
        val mbps = bytesPerSec / (1024 * 1024)
        if (mbps >= 1.0) {
            return String.format("%.1f MB/s", mbps)
        }
        val kbps = bytesPerSec / 1024
        return String.format("%.1f KB/s", kbps)
    }
}
