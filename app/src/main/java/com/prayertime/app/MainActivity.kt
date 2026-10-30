package com.prayertime.app

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Landscape prayer display: a large countdown on the left, today's prayers on the right.
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var nextName: TextView
    private lateinit var nextTime: TextView
    private lateinit var countdown: TextView
    private lateinit var locationLine: TextView
    private lateinit var todayDate: TextView
    private lateinit var adhanState: TextView
    private lateinit var warning: TextView
    private lateinit var batteryIndicator: View
    private lateinit var batteryIndicatorDot: View
    private lateinit var batteryPercent: TextView

    private val rows = LinkedHashMap<Prayer, Row>()
    private val handler = Handler(Looper.getMainLooper())
    private var batteryReceiverRegistered = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) updateChargingIndicator(intent)
        }
    }

    private var settings: PrayerSettings? = null
    private var zone: ZoneId = ZoneId.systemDefault()
    private var day: LocalDate = LocalDate.now(zone)
    private var rowsMinute: Long = Long.MIN_VALUE
    private var timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.forLanguageTag("tr-TR"))
    private var todayTimes: Map<Prayer, ZonedDateTime> = emptyMap()
    private var upcoming: List<Slot> = emptyList()

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000L - System.currentTimeMillis() % 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        nextName = findViewById(R.id.nextName)
        nextTime = findViewById(R.id.nextTime)
        countdown = findViewById(R.id.countdown)
        locationLine = findViewById(R.id.locationLine)
        todayDate = findViewById(R.id.todayDate)
        adhanState = findViewById(R.id.adhanState)
        warning = findViewById(R.id.warning)
        batteryIndicator = findViewById(R.id.batteryIndicator)
        batteryIndicatorDot = findViewById(R.id.batteryIndicatorDot)
        batteryPercent = findViewById(R.id.batteryPercent)

        buildRows(findViewById(R.id.prayerRows))

        val openSettings = View.OnClickListener { openSettings() }
        findViewById<View>(R.id.settingsButton).setOnClickListener(openSettings)
        adhanState.setOnClickListener(openSettings)
        warning.setOnClickListener(openSettings)

        requestNotificationPermissionOnce()
        hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        rebuild()
        registerBatteryReceiver()
        AdhanScheduler.scheduleAll(this)
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        if (batteryReceiverRegistered) {
            unregisterReceiver(batteryReceiver)
            batteryReceiverRegistered = false
        }
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun buildRows(container: LinearLayout) {
        val inflater = LayoutInflater.from(this)
        for (prayer in Prayer.entries) {
            val rowView = inflater.inflate(R.layout.item_prayer, container, false)
            container.addView(
                rowView,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            )
            rows[prayer] = Row(
                indicator = rowView.findViewById(R.id.rowIndicator),
                name = rowView.findViewById(R.id.rowName),
                status = rowView.findViewById(R.id.rowStatus),
                time = rowView.findViewById(R.id.rowTime)
            )
            rows[prayer]!!.name.text = prayer.label
        }
    }

    /** Recalculates today's and tomorrow's times. Called on resume, at midnight, or after a time zone change. */
    private fun rebuild() {
        zone = ZoneId.systemDefault()
        settings = prefs.settings()
        day = LocalDate.now(zone)
        rowsMinute = Long.MIN_VALUE
        val turkish = Locale.forLanguageTag("tr-TR")
        timeFormat = if (DateFormat.is24HourFormat(this)) {
            DateTimeFormatter.ofPattern("HH:mm", turkish)
        } else {
            DateTimeFormatter.ofPattern("h:mm a", turkish)
        }
        todayDate.text = DateTimeFormatter.ofPattern("EEE d MMM", turkish).format(day)

        val current = settings ?: return
        updateWarning(current)
        updateFooter(current)
        if (!current.locationSet) {
            todayTimes = emptyMap()
            upcoming = emptyList()
            return
        }

        todayTimes = PrayerTimes.forDate(day, zone, current)
        val tomorrow = PrayerTimes.forDate(day.plusDays(1), zone, current)
        val slots = ArrayList<Slot>(10)
        for (prayer in Prayer.entries) todayTimes[prayer]?.let { slots += Slot(prayer, it, day) }
        for (prayer in Prayer.entries) tomorrow[prayer]?.let { slots += Slot(prayer, it, day.plusDays(1)) }
        upcoming = slots.sortedBy { it.time.toInstant() }
    }

    private fun refresh() {
        val currentZone = ZoneId.systemDefault()
        if (currentZone != zone) {
            zone = currentZone
            rebuild()
            AdhanScheduler.scheduleAll(this)
        }
        if (LocalDate.now(zone) != day) rebuild()

        val current = settings ?: return

        if (!current.locationSet) {
            showMissingLocation()
            return
        }

        val now = Instant.now()
        val next = upcoming.firstOrNull { it.time.toInstant() > now }
        val nextLabel = next?.time?.toLocalDate()

        if (next == null) {
            countdown.text = "00:00:00"
        } else {
            val remaining = Duration.between(now, next.time.toInstant()).seconds.coerceAtLeast(0)
            countdown.text = formatDuration(remaining)
            nextName.text = next.prayer.label
            nextTime.text = if (nextLabel != null && nextLabel != day) {
                getString(R.string.next_time_tomorrow, timeFormat.format(next.time))
            } else {
                getString(R.string.next_time_today, timeFormat.format(next.time))
            }
        }

        val currentMinute = now.epochSecond / 60L
        if (currentMinute != rowsMinute) {
            updateRows(now, next)
            rowsMinute = currentMinute
        }
        checkPlayback(now, current)
    }

    private fun updateRows(now: Instant, next: Slot?) {
        val current = settings ?: return
        val accent = getColor(R.color.accent)
        val primary = getColor(R.color.text_primary)
        val secondary = getColor(R.color.text_secondary)
        val muted = getColor(R.color.text_muted)
        val nextTime = next?.time

        for (prayer in Prayer.entries) {
            val row = rows[prayer] ?: continue
            val time = todayTimes[prayer] ?: continue
            val isNext = time == nextTime
            val passed = !time.toInstant().isAfter(now)
            val enabled = current.adhanEnabled && current.enabled[prayer] == true

            row.time.text = timeFormat.format(time)
            row.status.text = if (enabled) {
                getString(R.string.prayer_adhan_enabled, current.sound.label)
            } else {
                getString(R.string.prayer_adhan_off)
            }

            row.name.setTextColor(if (passed && !isNext) muted else primary)
            row.time.setTextColor(
                when {
                    isNext -> accent
                    passed -> muted
                    else -> secondary
                }
            )
            row.status.setTextColor(if (!enabled) muted else if (isNext) accent else muted)
            row.indicator.visibility = if (isNext) View.VISIBLE else View.INVISIBLE
        }
    }

    private fun updateFooter(current: PrayerSettings) {
        adhanState.text = getString(if (current.adhanEnabled) R.string.adhan_on else R.string.adhan_off)
        adhanState.setTextColor(
            getColor(if (current.adhanEnabled) R.color.text_secondary else R.color.warning)
        )

        locationLine.text = if (current.locationSet) {
            String.format(
                Locale.US,
                "%.2f, %.2f · %s · %s",
                current.latitude,
                current.longitude,
                current.method.label,
                if (zone.rules.getOffset(Instant.now()).totalSeconds == 0) {
                    "UTC+00:00"
                } else {
                    "UTC${zone.rules.getOffset(Instant.now()).id}"
                }
            )
        } else {
            getString(R.string.location_not_set)
        }
    }

    private fun showMissingLocation() {
        nextName.text = getString(R.string.location_missing)
        nextTime.text = getString(R.string.location_missing_hint)
        countdown.text = "--:--:--"
        val muted = getColor(R.color.text_muted)
        for (row in rows.values) {
            row.time.text = "--:--"
            row.status.text = getString(R.string.location_needed)
            row.status.setTextColor(muted)
            row.name.setTextColor(muted)
            row.time.setTextColor(muted)
            row.indicator.visibility = View.INVISIBLE
        }
    }

    private fun updateWarning(current: PrayerSettings) {
        val problems = ArrayList<String>(3)
        if (!current.locationSet) problems += getString(R.string.warning_location)
        if (!AdhanScheduler.exactAlarmsAllowed(this)) problems += getString(R.string.warning_exact_alarm)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        ) problems += getString(R.string.warning_battery)

        if (problems.isEmpty()) {
            warning.visibility = View.GONE
        } else {
            warning.visibility = View.VISIBLE
            warning.text = problems.joinToString(" · ") + "  ›"
        }
    }

    private fun registerBatteryReceiver() {
        if (batteryReceiverRegistered) return
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val initialState = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(batteryReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(batteryReceiver, filter)
        }
        batteryReceiverRegistered = true
        initialState?.let(::updateChargingIndicator)
    }

    private fun updateChargingIndicator(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) {
            batteryIndicator.visibility = View.GONE
            return
        }

        val percentage = (level * 100f / scale).toInt().coerceIn(0, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING
        val (background, dot, state) = when {
            percentage <= BATTERY_CRITICAL_PERCENT -> Triple(
                R.drawable.battery_indicator_critical_bg,
                R.drawable.battery_dot_critical,
                R.string.battery_state_critical
            )
            percentage <= BATTERY_LOW_PERCENT -> Triple(
                R.drawable.battery_indicator_low_bg,
                R.drawable.battery_dot_low,
                R.string.battery_state_low
            )
            else -> Triple(
                R.drawable.battery_indicator_ok_bg,
                R.drawable.battery_dot_ok,
                R.string.battery_state_ok
            )
        }
        batteryIndicator.setBackgroundResource(background)
        batteryIndicatorDot.setBackgroundResource(dot)
        batteryPercent.text = getString(R.string.battery_level, percentage) +
            if (charging) " · ${getString(R.string.battery_charging)}" else ""
        batteryPercent.contentDescription = getString(
            R.string.battery_status_accessibility,
            percentage,
            getString(state)
        )
        batteryIndicator.visibility = View.VISIBLE
    }

    /**
     * Safety net for the case where an alarm was lost (for example, the app was force-stopped).
     * It plays at most once per prayer and only within 90 seconds of the prayer time.
     */
    private fun checkPlayback(now: Instant, current: PrayerSettings) {
        if (!current.adhanEnabled) return
        for (slot in upcoming) {
            val secondsLate = Duration.between(slot.time.toInstant(), now).seconds
            if (secondsLate !in 0..90) continue
            if (current.enabled[slot.prayer] != true) continue
            AdhanService.playForPrayer(this, slot.prayer, slot.date, current.sound)
        }
    }

    private fun requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (prefs.notificationAsked) return
        prefs.notificationAsked = true
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun formatDuration(totalSeconds: Long): String {
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

    private data class Slot(val prayer: Prayer, val time: ZonedDateTime, val date: LocalDate)

    private class Row(
        val indicator: View,
        val name: TextView,
        val status: TextView,
        val time: TextView
    )

    private companion object {
        const val REQUEST_NOTIFICATIONS = 100
        const val BATTERY_LOW_PERCENT = 30
        const val BATTERY_CRITICAL_PERCENT = 15
    }
}
