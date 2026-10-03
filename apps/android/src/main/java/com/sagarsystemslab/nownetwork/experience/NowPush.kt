package com.sagarsystemslab.nownetwork.experience

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.sagarsystemslab.nownetwork.BuildConfig
import com.sagarsystemslab.nownetwork.MainActivity
import com.sagarsystemslab.nownetwork.R
import com.sagarsystemslab.nownetwork.security.AndroidKeystoreSecretStore

object NowPush {
    const val CHANNEL = "now_account_updates"
    const val OPEN_INBOX = "com.sagarsystemslab.nownetwork.OPEN_INBOX"
    val configured get() = listOf(BuildConfig.FIREBASE_APP_ID, BuildConfig.FIREBASE_PROJECT_ID, BuildConfig.FIREBASE_API_KEY, BuildConfig.FIREBASE_SENDER_ID).all { it.isNotBlank() }
    fun initialize(context: Context) {
        if (!configured) return
        if (FirebaseApp.getApps(context).isEmpty()) FirebaseApp.initializeApp(context, FirebaseOptions.Builder()
            .setApplicationId(BuildConfig.FIREBASE_APP_ID).setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
            .setApiKey(BuildConfig.FIREBASE_API_KEY).setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID).build())
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Proof & account updates", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Updates from your NOW account" })
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token -> AndroidKeystoreSecretStore(context).write("push_token", token) }
    }
}

/** Payloads contain only an inbox hint; destination records are read under the current actor. */
class NowMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) { AndroidKeystoreSecretStore(this).write("push_token", token) }
    override fun onMessageReceived(message: RemoteMessage) {
        val id = message.data["notification_id"] ?: return
        if (!Regex("[0-9a-fA-F-]{36}").matches(id) || !NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        val intent = Intent(this, MainActivity::class.java).setAction(NowPush.OPEN_INBOX)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, NowPush.CHANNEL)
            .setSmallIcon(R.drawable.ic_now_notification).setContentTitle("NOW update")
            .setContentText("Open your inbox to see the latest account update.")
            .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
        try { NotificationManagerCompat.from(this).notify(id, 0, notification) } catch (_: SecurityException) { /* Permission can change after the check. */ }
    }
}
