package com.prayertime.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.LocalDate

/**
 * Receives the exact alarms, and also re-schedules everything after a reboot,
 * a clock change, or a time zone change.
 */
class AdhanReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AdhanScheduler.ACTION_ADHAN) {
            AdhanScheduler.scheduleAll(context)
            return
        }

        val prayer = Prayer.entries.firstOrNull {
            it.name == intent.getStringExtra(AdhanScheduler.EXTRA_PRAYER)
        } ?: return

        val epochDay = intent.getLongExtra(AdhanScheduler.EXTRA_EPOCH_DAY, LocalDate.now().toEpochDay())
        val prefs = Prefs(context)
        val date = LocalDate.ofEpochDay(epochDay)

        // The only reason to skip is that the user disabled this prayer (or all adhan).
        if (!prefs.adhanEnabled || !prefs.isPrayerEnabled(prayer)) {
            AdhanScheduler.extendAfterAlarm(context, date)
            return
        }

        AdhanService.playForPrayer(context, prayer, date, prefs.sound)
        AdhanScheduler.extendAfterAlarm(context, date)
    }
}
