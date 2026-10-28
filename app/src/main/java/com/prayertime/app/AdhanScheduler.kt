package com.prayertime.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.LocalDate
import java.time.ZoneId

/**
 * Schedules one exact alarm per enabled prayer, eight days ahead.
 * Alarms are re-created whenever the app opens, after boot, and after time changes,
 * so scheduled playback does not depend on the app being open or on a network connection.
 */
object AdhanScheduler {

    const val ACTION_ADHAN = "com.prayertime.app.action.ADHAN"
    const val EXTRA_PRAYER = "prayer"
    const val EXTRA_EPOCH_DAY = "epoch_day"

    private const val DAYS_AHEAD = 7L

    fun scheduleAll(context: Context) {
        val app = context.applicationContext
        val alarmManager = app.getSystemService(AlarmManager::class.java)
        val prefs = Prefs(app)
        val settings = prefs.settings()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val now = System.currentTimeMillis()

        // Remove the previous window first, so disabled prayers and old time zones cannot fire.
        for (dayOffset in -2L..(DAYS_AHEAD + 1)) {
            val date = today.plusDays(dayOffset)
            for (prayer in Prayer.entries) cancel(app, alarmManager, date, prayer)
        }

        if (!settings.locationSet || !settings.adhanEnabled) return

        for (dayOffset in 0L..DAYS_AHEAD) {
            val date = today.plusDays(dayOffset)
            val times = PrayerTimes.forDate(date, zone, settings)
            for ((prayer, time) in times) {
                if (settings.enabled[prayer] != true) continue
                val triggerAt = time.toInstant().toEpochMilli()
                if (triggerAt <= now) continue
                set(app, alarmManager, date, prayer, triggerAt)
            }
        }
    }

    fun exactAlarmsAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    private fun set(
        context: Context,
        alarmManager: AlarmManager,
        date: LocalDate,
        prayer: Prayer,
        triggerAt: Long
    ) {
        val operation = pendingIntent(context, date, prayer)
        try {
            val canBeExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                alarmManager.canScheduleExactAlarms()
            if (canBeExact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
            } else {
                // Still fires without the special permission, possibly a few minutes late.
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
            }
        } catch (_: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
        }
    }

    private fun cancel(
        context: Context,
        alarmManager: AlarmManager,
        date: LocalDate,
        prayer: Prayer
    ) {
        val operation = pendingIntent(context, date, prayer)
        alarmManager.cancel(operation)
        operation.cancel()
    }

    private fun pendingIntent(context: Context, date: LocalDate, prayer: Prayer): PendingIntent {
        val requestCode = (date.toEpochDay() % 100_000).toInt() * 10 + prayer.ordinal
        val intent = Intent(context, AdhanReceiver::class.java)
            .setAction(ACTION_ADHAN)
            .putExtra(EXTRA_PRAYER, prayer.name)
            .putExtra(EXTRA_EPOCH_DAY, date.toEpochDay())
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
