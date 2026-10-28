package com.prayertime.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import java.time.LocalDate

/** Plays the adhan in a foreground service so playback can continue with the screen off. */
class AdhanService : Service() {

    private var player: MediaPlayer? = null
    private var scheduledPrayer: Prayer? = null
    private var scheduledDate: LocalDate? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopPlayback()
            ACTION_PLAY -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.app_name)
                val sound = AdhanSound.fromName(intent.getStringExtra(EXTRA_SOUND))
                scheduledPrayer = Prayer.entries.firstOrNull {
                    it.name == intent.getStringExtra(EXTRA_PRAYER)
                }
                scheduledDate = if (scheduledPrayer != null && intent.hasExtra(EXTRA_EPOCH_DAY)) {
                    LocalDate.ofEpochDay(intent.getLongExtra(EXTRA_EPOCH_DAY, 0L))
                } else {
                    null
                }
                try {
                    startPlaybackNotification(title)
                    if (!play(sound)) {
                        releaseScheduledClaim()
                        stopPlayback()
                    } else if (scheduledPrayer != null && scheduledDate != null &&
                        !Prefs(this).claimPlayed(scheduledPrayer!!, scheduledDate!!)
                    ) {
                        stopPlayback()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Could not start adhan playback", e)
                    releaseScheduledClaim()
                    stopPlayback()
                } finally {
                    clearPending(scheduledPrayer, scheduledDate)
                }
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun play(sound: AdhanSound): Boolean {
        player?.release()
        player = null

        val resId = findRaw(sound.rawName)
            ?: AdhanSound.entries.firstNotNullOfOrNull { findRaw(it.rawName) }
        if (resId == null) {
            notifyProblem(getString(R.string.audio_missing))
            return false
        }

        val audioPlayer = MediaPlayer()
        return try {
            audioPlayer.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            audioPlayer.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
            audioPlayer.setDataSource(this, Uri.parse("android.resource://$packageName/$resId"))
            audioPlayer.setOnCompletionListener { stopPlayback() }
            audioPlayer.setOnErrorListener { _, _, _ ->
                releaseScheduledClaim()
                notifyProblem(getString(R.string.audio_failed))
                stopPlayback()
                true
            }
            audioPlayer.prepare()
            audioPlayer.start()
            player = audioPlayer
            true
        } catch (e: Exception) {
            audioPlayer.release()
            Log.e(TAG, "Could not play adhan", e)
            notifyProblem(getString(R.string.audio_failed))
            false
        }
    }

    private fun startPlaybackNotification(title: String) {
        val notification = buildNotification(title)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(title: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, AdhanService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_PLAYBACK)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_playing_title, title))
            .setContentText(getString(R.string.notification_playing_text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stop),
                    getString(R.string.notification_stop),
                    stop
                ).build()
            )
            .build()
    }

    private fun stopPlayback() {
        player?.release()
        player = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseScheduledClaim() {
        val prayer = scheduledPrayer ?: return
        val date = scheduledDate ?: return
        Prefs(this).unclaimPlayed(prayer, date)
        scheduledPrayer = null
        scheduledDate = null
    }

    private fun notifyProblem(message: String) {
        val manager = getSystemService(NotificationManager::class.java)
        val openApp = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_problem_title))
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        manager.notify(ALERT_NOTIFICATION_ID, notification)
    }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        val playback = NotificationChannel(
            CHANNEL_PLAYBACK,
            getString(R.string.channel_playback),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_playback_description)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        val alerts = NotificationChannel(
            CHANNEL_ALERTS,
            getString(R.string.channel_alerts),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.channel_alerts_description)
            setSound(null, null)
            setShowBadge(false)
        }
        manager.createNotificationChannels(listOf(playback, alerts))
    }

    @SuppressLint("DiscouragedApi")
    private fun findRaw(name: String): Int? =
        resources.getIdentifier(name, "raw", packageName).takeIf { it != 0 }

    companion object {
        const val ACTION_PLAY = "com.prayertime.app.action.PLAY_ADHAN"
        const val ACTION_STOP = "com.prayertime.app.action.STOP_ADHAN"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SOUND = "sound"
        private const val EXTRA_PRAYER = "scheduled_prayer"
        private const val EXTRA_EPOCH_DAY = "scheduled_epoch_day"
        private const val CHANNEL_PLAYBACK = "adhan_playback_tr"
        private const val CHANNEL_ALERTS = "adhan_alerts_tr"
        private const val NOTIFICATION_ID = 10
        private const val ALERT_NOTIFICATION_ID = 11
        private const val TAG = "AdhanService"
        private val pendingPrayers = HashMap<String, Int>()
        private val pendingHandler = Handler(Looper.getMainLooper())
        private var nextPendingToken = 0

        fun play(context: Context, title: String, sound: AdhanSound) {
            val intent = Intent(context, AdhanService::class.java)
                .setAction(ACTION_PLAY)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_SOUND, sound.name)
            start(context, intent)
        }

        fun playForPrayer(context: Context, prayer: Prayer, date: LocalDate, sound: AdhanSound) {
            val prefs = Prefs(context)
            if (!prefs.adhanEnabled || !prefs.isPrayerEnabled(prayer) || prefs.wasPlayed(prayer, date)) {
                return
            }
            val pendingKey = pendingKey(prayer, date)
            val pendingToken = synchronized(pendingPrayers) {
                if (pendingPrayers.containsKey(pendingKey)) return
                (++nextPendingToken).also { pendingPrayers[pendingKey] = it }
            }
            pendingHandler.postDelayed({
                synchronized(pendingPrayers) {
                    if (pendingPrayers[pendingKey] == pendingToken) pendingPrayers.remove(pendingKey)
                }
            }, 15_000L)
            val intent = Intent(context, AdhanService::class.java)
                .setAction(ACTION_PLAY)
                .putExtra(EXTRA_TITLE, prayer.label)
                .putExtra(EXTRA_SOUND, sound.name)
                .putExtra(EXTRA_PRAYER, prayer.name)
                .putExtra(EXTRA_EPOCH_DAY, date.toEpochDay())
            try {
                start(context, intent)
            } catch (e: RuntimeException) {
                clearPending(prayer, date)
                Log.e(TAG, "Could not start adhan service", e)
            }
        }

        private fun pendingKey(prayer: Prayer, date: LocalDate): String = "${date}_${prayer.name}"

        private fun clearPending(prayer: Prayer?, date: LocalDate?) {
            if (prayer == null || date == null) return
            synchronized(pendingPrayers) { pendingPrayers.remove(pendingKey(prayer, date)) }
        }

        private fun start(context: Context, intent: Intent) {
            context.startForegroundService(intent)
        }
    }
}
