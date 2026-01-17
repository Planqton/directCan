package at.planqton.directcan.service

import android.app.Notification
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
import at.planqton.directcan.DirectCanApplication
import at.planqton.directcan.MainActivity
import at.planqton.directcan.R
import at.planqton.directcan.data.can.CanFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "CanLoggingService"

class CanLoggingService : Service() {

    companion object {
        private const val CHANNEL_ID = "can_logging_channel"
        private const val NOTIFICATION_ID = 1

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, CanLoggingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, CanLoggingService::class.java)
            context.stopService(intent)
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var loggingJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Service starting...")
        val notification = createNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        _isRunning.value = true
        startLogging()
        Log.i(TAG, "Service started successfully - Logging active")

        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroying...")
        super.onDestroy()
        _isRunning.value = false
        loggingJob?.cancel()
        Log.i(TAG, "Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "CAN Logging",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Zeigt an wenn CAN-Daten im Hintergrund aufgezeichnet werden"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CAN Logging aktiv")
            .setContentText("CAN-Daten werden aufgezeichnet")
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startLogging() {
        val app = DirectCanApplication.instance
        val usbManager = app.usbSerialManager
        val canDataRepository = app.canDataRepository
        val settingsRepository = app.settingsRepository

        // Start hardware logging
        Log.d(TAG, "Starting hardware logging")
        usbManager.startLogging()
        canDataRepository.setLoggingActive(true)

        val startTime = System.currentTimeMillis()

        // Periodic status logging based on settings
        loggingJob = scope.launch {
            val logEnabled = settingsRepository.getDevLogEnabledSync()
            val intervalMinutes = settingsRepository.getDevLogIntervalMinutesSync()
            Log.i(TAG, "Status logging: enabled=$logEnabled, interval=${intervalMinutes}min")

            while (true) {
                // Re-check settings each iteration (allows live changes)
                val currentLogEnabled = settingsRepository.getDevLogEnabledSync()
                val currentIntervalMinutes = settingsRepository.getDevLogIntervalMinutesSync()

                kotlinx.coroutines.delay(currentIntervalMinutes * 60 * 1000L)

                if (currentLogEnabled) {
                    val elapsedMinutes = (System.currentTimeMillis() - startTime) / 60000
                    val frameCount = canDataRepository.currentFrames.value.size
                    val totalCaptured = canDataRepository.totalFramesCaptured.value

                    Log.i(TAG, "Still Capturing - $frameCount unique IDs, $totalCaptured total frames - Capturing since $elapsedMinutes minutes")
                }
            }
        }
    }
}
