package com.codexatlas.mobile

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Native AppWidget implementation for launchers that expose widgets as "cards" (including ColorOS). */
class AtlasWidgetReceiver : AppWidgetProvider() {
    override fun onEnabled(context: Context) {
        AtlasSyncService.start(context)
        updateAll(context)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        AtlasSyncService.start(context)
        updateWidgets(context, ids, goAsync())
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: android.os.Bundle) {
        updateWidgets(context, intArrayOf(appWidgetId), goAsync())
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_REFRESH -> updateAll(context, goAsync())
            ACTION_ACTIVATE, ACTION_CONTINUE -> performSessionAction(context, intent.action == ACTION_CONTINUE, goAsync())
            ACTION_REPLY -> openConversation(context, goAsync())
            else -> super.onReceive(context, intent)
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.codexatlas.mobile.action.REFRESH_WIDGET"
        const val ACTION_ACTIVATE = "com.codexatlas.mobile.action.ACTIVATE_SESSION"
        const val ACTION_CONTINUE = "com.codexatlas.mobile.action.INPUT_CONTINUE"
        const val ACTION_REPLY = "com.codexatlas.mobile.action.OPEN_REPLY"
        private const val REQUEST_REFRESH = 7001
        private const val REQUEST_ACTIVATE = 7002
        private const val REQUEST_CONTINUE = 7003
        private const val REQUEST_REPLY = 7004

        fun updateAll(context: Context, pending: BroadcastReceiver.PendingResult? = null) {
            if (BridgePreferences.token(context).isNotBlank()) AtlasSyncService.start(context)
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, AtlasWidgetReceiver::class.java))
            if (ids.isNotEmpty()) updateWidgets(context, ids, pending)
            else pending?.finish()
        }

        fun requestRefresh(context: Context) {
            if (BridgePreferences.token(context).isNotBlank()) AtlasSyncService.start(context)
            context.sendBroadcast(Intent(context, AtlasWidgetReceiver::class.java).setAction(ACTION_REFRESH))
        }

        private fun updateWidgets(context: Context, ids: IntArray, pending: BroadcastReceiver.PendingResult?) {
            widgetScope.launch(Dispatchers.IO) {
                try {
                    val snapshot = loadSnapshot(context)
                    withContext(Dispatchers.Main) {
                        val manager = AppWidgetManager.getInstance(context)
                        ids.forEach { id -> manager.updateAppWidget(id, render(context, snapshot, id)) }
                    }
                } finally {
                    pending?.finish()
                }
            }
        }

        private fun performSessionAction(context: Context, continueInput: Boolean, pending: BroadcastReceiver.PendingResult?) {
            widgetScope.launch(Dispatchers.IO) {
                try {
                    renderActionPending(context)
                    runCatching {
                        val snapshot = loadSnapshot(context)
                        if (snapshot.sessionId.isNotBlank()) {
                            val client = AtlasBridgeClient(preferredUrl(context), BridgePreferences.token(context))
                            if (continueInput) client.inputContinueAny(snapshot.sessionId, fallbackUrl(context))
                            else client.activateAny(snapshot.sessionId, fallbackUrl(context))
                        }
                    }
                    updateAll(context)
                } finally {
                    pending?.finish()
                }
            }
        }

        private fun openConversation(context: Context, pending: BroadcastReceiver.PendingResult?) {
            widgetScope.launch(Dispatchers.IO) {
                try {
                    val sessionId = loadSnapshot(context).sessionId
                    withContext(Dispatchers.Main) {
                        val intent = Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            if (sessionId.isNotBlank()) putExtra(MainActivity.EXTRA_SESSION_ID, sessionId)
                        }
                        context.startActivity(intent)
                    }
                } finally {
                    pending?.finish()
                }
            }
        }

        private fun renderActionPending(context: Context) {
            val snapshot = BridgePreferences.cachedSnapshot(context) ?: return
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, AtlasWidgetReceiver::class.java))
            if (ids.isEmpty()) return
            ids.forEach { id ->
                val views = render(context, snapshot.copy(lastOutput = "正在发送操作…"), id)
                manager.updateAppWidget(id, views)
            }
        }

        private fun loadSnapshot(context: Context): AtlasSnapshot = runCatching {
            AtlasBridgeClient(preferredUrl(context), BridgePreferences.token(context))
                .snapshotAny(fallbackUrl(context))
        }.onSuccess { BridgePreferences.saveCachedSnapshot(context, it) }
            .getOrElse { BridgePreferences.cachedSnapshot(context) ?: AtlasSnapshot() }

        private fun preferredUrl(context: Context): String =
            if (BridgePreferences.connectionRoute(context) == "server") BridgePreferences.tunnelUrl(context)
            else BridgePreferences.url(context)

        private fun fallbackUrl(context: Context): String =
            if (BridgePreferences.connectionRoute(context) == "lan") ""
            else if (BridgePreferences.connectionRoute(context) == "server") ""
            else BridgePreferences.tunnelUrl(context)

        private fun render(context: Context, snapshot: AtlasSnapshot, widgetId: Int): RemoteViews {
            val manager = AppWidgetManager.getInstance(context)
            val options = manager.getAppWidgetOptions(widgetId)
            val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            val maxWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
            val layout = when {
                maxWidth >= 360 || minWidth >= 300 -> R.layout.atlas_widget_large
                maxWidth >= 270 || minWidth >= 220 -> R.layout.atlas_widget_medium
                else -> R.layout.atlas_widget
            }
            val views = RemoteViews(context.packageName, layout)
            val state = snapshot.state.lowercase()
            val stateColor = when (state) {
                "working", "active", "completed", "done" -> Color.rgb(88, 190, 112)
                "failed", "blocked", "error" -> Color.rgb(216, 93, 89)
                "waiting", "approval", "attention" -> Color.rgb(211, 169, 65)
                else -> Color.rgb(143, 157, 145)
            }
            views.setTextViewText(R.id.atlas_widget_title, snapshot.title.ifBlank { "No active session" })
            views.setTextViewText(
                R.id.atlas_widget_meta,
                listOf(snapshot.deviceName, snapshot.folder, snapshot.state)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                    .ifBlank { "idle" },
            )
            views.setTextViewText(R.id.atlas_widget_output, snapshot.lastOutput.ifBlank { "Waiting for Codex output" })
            val balanceText = if (snapshot.balanceRemaining == null) {
                if (snapshot.balanceProvider.isBlank()) "Balance unavailable" else "${snapshot.balanceProvider} · unavailable"
            } else {
                "${snapshot.balanceProvider.ifBlank { "Codex" }} · ${String.format(Locale.US, "%.2f", snapshot.balanceRemaining)} ${snapshot.balanceUnit}"
            }
            views.setTextViewText(R.id.atlas_widget_balance, balanceText)
            views.setTextColor(R.id.atlas_widget_state_dot, stateColor)
            views.setTextColor(R.id.atlas_widget_balance, if (snapshot.balanceRemaining != null && snapshot.balanceRemaining <= 0.0) Color.rgb(216, 93, 89) else Color.rgb(146, 201, 149))
            views.setBoolean(R.id.atlas_widget_activate, "setEnabled", snapshot.canActivate && snapshot.sessionId.isNotBlank())
            views.setBoolean(R.id.atlas_widget_continue, "setEnabled", snapshot.canInputContinue && snapshot.sessionId.isNotBlank())
            views.setBoolean(R.id.atlas_widget_reply, "setEnabled", snapshot.sessionId.isNotBlank())
            views.setOnClickPendingIntent(R.id.atlas_widget_activate, pendingIntent(context, ACTION_ACTIVATE, REQUEST_ACTIVATE + widgetId))
            views.setOnClickPendingIntent(R.id.atlas_widget_continue, pendingIntent(context, ACTION_CONTINUE, REQUEST_CONTINUE + widgetId))
            views.setOnClickPendingIntent(R.id.atlas_widget_reply, pendingIntent(context, ACTION_REPLY, REQUEST_REPLY + widgetId))
            // Tapping the card opens the selected conversation; action buttons
            // keep their own explicit PendingIntents below.
            views.setOnClickPendingIntent(R.id.atlas_widget_root, pendingIntent(context, ACTION_REPLY, REQUEST_REPLY + widgetId))
            return views
        }

        private fun pendingIntent(context: Context, action: String, requestCode: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                requestCode,
                Intent(context, AtlasWidgetReceiver::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
