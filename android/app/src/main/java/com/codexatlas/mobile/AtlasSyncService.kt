package com.codexatlas.mobile

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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Keeps the authenticated bridge alive when the Android activity is backgrounded. */
class AtlasSyncService : Service() {
    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
            // A background exception must not take down the activity. The
            // next START_STICKY delivery will recreate the worker and retry
            // from the persisted cursor/outbox.
            updateNotification("Codex Atlas", "连接暂时中断，正在重试")
            android.util.Log.e("AtlasSyncService", "sync worker failed", error)
        },
    )
    private var syncWorker: Job? = null
    private var queueWorker: Job? = null
    private var cursorMs = 0L
    private var syncEpoch = ""
    private var afterSeq = 0L
    private var foregroundReady = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        runCatching {
            startForegroundCompat(notification("Codex Atlas", "正在保持连接"))
            foregroundReady = true
        }.onFailure { error ->
            // Some vendor ROMs reject a foreground-service type after an app
            // restore/update. Failing closed here prevents a delayed process
            // crash; the next explicit connection attempt can start it again.
            android.util.Log.e("AtlasSyncService", "foreground service start failed", error)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundReady) return START_NOT_STICKY
        cursorMs = maxOf(cursorMs, BridgePreferences.syncCursor(this))
        syncEpoch = BridgePreferences.syncEpoch(this)
        afterSeq = maxOf(afterSeq, BridgePreferences.syncSeq(this))
        if (activityVisible) {
            syncWorker?.cancel()
            syncWorker = null
        } else if (syncWorker?.isActive != true) {
            syncWorker = serviceScope.launch { runSyncLoop() }
        }
        if (queueWorker?.isActive != true) {
            AtlasMessageQueue.resetSending(this)
            queueWorker = serviceScope.launch { runQueueLoop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        syncWorker?.cancel()
        queueWorker?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // ColorOS and similar launchers may remove the task without stopping
        // the foreground service. Re-issue the start request so a killed
        // process is recreated with the persisted pairing data.
        activityVisible = false
        if (BridgePreferences.token(this).isNotBlank()) AtlasSyncService.start(this)
        super.onTaskRemoved(rootIntent)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15 terminates apps that do not promptly stop a timed-out
        // foreground service. Current releases use remoteMessaging, but keep
        // this guard for upgrades from an older dataSync service instance.
        syncWorker?.cancel()
        queueWorker?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun runQueueLoop() {
        while (currentCoroutineContext().isActive) {
            val pairing = MainActivity.storedPairing(this)
            val details = MainActivity.parsePairing(pairing)
            val token = BridgePreferences.token(this).trim()
            if (details == null || token.isBlank()) {
                delay(1_000)
                continue
            }

            val route = ConnectionRoute.fromKey(BridgePreferences.connectionRoute(this))
            val primary = primaryBridgeUrl(details, route)
            val fallback = fallbackBridgeUrl(details, route)
            if (primary.isBlank()) {
                delay(1_000)
                continue
            }

            val control = AtlasMessageQueue.control(this)
            if (control == AtlasQueueControl.Stopping) {
                // Stop the current queue run without dropping unsent items.
                // A blocking HTTP call is allowed to finish; no retry or next
                // item will be submitted after the stop request.
                AtlasMessageQueue.setControl(this, AtlasQueueControl.Paused)
            }
            val queued = if (control == AtlasQueueControl.Running) AtlasMessageQueue.claim(this) else null
            if (control == AtlasQueueControl.Paused || control == AtlasQueueControl.Stopping) {
                updateNotification("Codex Atlas", "发送队列已暂停")
                delay(750)
                continue
            }
            if (queued == null) {
                delay(350)
                continue
            }
            try {
                AtlasBridgeClient(primary, token).sendMessageAny(
                    queued.sessionId,
                    queued.text,
                    fallback,
                    queued.clientMessageId,
                )
                AtlasMessageQueue.remove(this, queued.id)
                sendBroadcast(Intent(ACTION_UPDATED).setPackage(packageName))
                updateNotification("Codex Atlas", "已发送队列消息")
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (AtlasMessageQueue.control(this) == AtlasQueueControl.Stopping) {
                    AtlasMessageQueue.setControl(this, AtlasQueueControl.Paused)
                    updateNotification("Codex Atlas", "发送队列已停止")
                    continue
                }
                AtlasMessageQueue.markFailure(this, queued.id, error.message)
                sendBroadcast(Intent(ACTION_UPDATED).setPackage(packageName))
                updateNotification("Codex Atlas", "队列发送失败，正在重试")
                delay((1_500L * (queued.attempts + 1).coerceAtMost(4)).coerceAtMost(30_000L))
            }
        }
    }

    private suspend fun runSyncLoop() {
        while (currentCoroutineContext().isActive) {
            val pairing = MainActivity.storedPairing(this)
            val details = MainActivity.parsePairing(pairing)
            val token = BridgePreferences.token(this).trim()
            if (details == null || token.isBlank()) {
                updateNotification("Codex Atlas", "等待配对")
                delay(2_500)
                continue
            }

            val route = ConnectionRoute.fromKey(BridgePreferences.connectionRoute(this))
            val primary = primaryBridgeUrl(details, route)
            val fallback = fallbackBridgeUrl(details, route)
            if (primary.isBlank()) {
                updateNotification("Codex Atlas", "没有可用通道")
                delay(2_500)
                continue
            }
            try {
                val sync = AtlasBridgeClient(primary, token).syncAny(
                    cursorMs,
                    fallback,
                    20_000,
                    syncEpoch,
                    afterSeq,
                )
                currentCoroutineContext().ensureActive()
                cursorMs = maxOf(cursorMs, sync.cursorMs)
                if (sync.syncEpoch.isNotBlank()) {
                    afterSeq = if (sync.reset || sync.gap || (syncEpoch.isNotBlank() && syncEpoch != sync.syncEpoch)) {
                        sync.nextSeq
                    } else {
                        maxOf(afterSeq, sync.nextSeq)
                    }
                    syncEpoch = sync.syncEpoch
                    BridgePreferences.saveSyncPosition(this, syncEpoch, afterSeq, cursorMs)
                } else {
                    BridgePreferences.saveSyncCursor(this, cursorMs)
                }
                sync.snapshot?.let { BridgePreferences.saveCachedSnapshot(this, it) }
                AtlasWidgetReceiver.requestRefresh(this)
                sendBroadcast(Intent(ACTION_UPDATED).setPackage(packageName))
                updateNotification(
                    "Codex Atlas",
                    when {
                        AtlasMessageQueue.count(this) > 0 -> "队列处理中"
                        else -> "已连接"
                    },
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                updateNotification("Codex Atlas", "连接重试中")
                delay(1_500)
            }
        }
    }

    private fun notification(title: String, text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_atlas_logo)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    8100,
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    private fun updateNotification(title: String, text: String) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(title, text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Codex Atlas connection", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun startForegroundCompat(value: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // A few vendor Android 14/15 builds reject remoteMessaging even
            // when the manifest permission is present. Fall back to the
            // declared dataSync type so the app remains usable on those ROMs.
            runCatching {
                startForeground(NOTIFICATION_ID, value, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
            }.getOrElse {
                startForeground(NOTIFICATION_ID, value, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, value, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, value)
        }
    }

    companion object {
        private const val CHANNEL_ID = "atlas_connection"
        private const val NOTIFICATION_ID = 8101
        const val ACTION_UPDATED = "com.codexatlas.mobile.action.SYNC_UPDATED"
        @Volatile private var activityVisible = false

        fun start(context: Context) {
            val intent = Intent(context, AtlasSyncService::class.java)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun setActivityVisible(context: Context, visible: Boolean) {
            activityVisible = visible
            if (BridgePreferences.token(context).isNotBlank()) start(context)
        }
    }
}
