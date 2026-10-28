package com.prayertime.app

import android.annotation.SuppressLint
import android.content.Context
import java.time.LocalDate

/** Everything the user can change, stored locally in SharedPreferences. */
class Prefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("prayer_time", Context.MODE_PRIVATE)

    var adhanEnabled: Boolean
        get() = sp.getBoolean(KEY_ADHAN_ENABLED, true)
        set(value) = sp.edit().putBoolean(KEY_ADHAN_ENABLED, value).apply()

    var sound: AdhanSound
        get() = AdhanSound.fromName(sp.getString(KEY_SOUND, null))
        set(value) = sp.edit().putString(KEY_SOUND, value.name).apply()

    var method: CalcMethod
        get() = CalcMethod.fromName(sp.getString(KEY_METHOD, null))
        set(value) = sp.edit().putString(KEY_METHOD, value.name).apply()

    var fajrAdjustmentMinutes: Int
        get() = sp.getInt(KEY_FAJR_ADJUSTMENT, 20)
        set(value) = sp.edit().putInt(KEY_FAJR_ADJUSTMENT, value.coerceIn(0, 30)).apply()

    var latitude: Double
        get() = sp.getFloat(KEY_LATITUDE, 0f).toDouble()
        set(value) = sp.edit().putFloat(KEY_LATITUDE, value.toFloat()).apply()

    var longitude: Double
        get() = sp.getFloat(KEY_LONGITUDE, 0f).toDouble()
        set(value) = sp.edit().putFloat(KEY_LONGITUDE, value.toFloat()).apply()

    var locationSet: Boolean
        get() = sp.getBoolean(KEY_LOCATION_SET, false)
        set(value) = sp.edit().putBoolean(KEY_LOCATION_SET, value).apply()

    var notificationAsked: Boolean
        get() = sp.getBoolean(KEY_NOTIFICATION_ASKED, false)
        set(value) = sp.edit().putBoolean(KEY_NOTIFICATION_ASKED, value).apply()

    fun isPrayerEnabled(prayer: Prayer): Boolean = sp.getBoolean(prayer.prefKey, true)

    fun setPrayerEnabled(prayer: Prayer, enabled: Boolean) {
        sp.edit().putBoolean(prayer.prefKey, enabled).apply()
    }

    fun settings(): PrayerSettings = PrayerSettings(
        latitude = latitude,
        longitude = longitude,
        method = method,
        fajrAdjustmentMinutes = fajrAdjustmentMinutes,
        locationSet = locationSet,
        adhanEnabled = adhanEnabled,
        sound = sound,
        enabled = Prayer.entries.associateWith { isPrayerEnabled(it) }
    )

    /**
     * Claims the right to play a prayer exactly once per day.
     * commit() is intentional: the answer must be correct even if the process dies right away.
     */
    @SuppressLint("ApplySharedPref")
    @Synchronized
    fun claimPlayed(prayer: Prayer, date: LocalDate): Boolean {
        val key = "played_${date}_${prayer.name}"
        if (sp.getBoolean(key, false)) return false
        return sp.edit().putBoolean(key, true).commit()
    }

    fun wasPlayed(prayer: Prayer, date: LocalDate): Boolean =
        sp.getBoolean("played_${date}_${prayer.name}", false)

    @SuppressLint("ApplySharedPref")
    fun unclaimPlayed(prayer: Prayer, date: LocalDate) {
        sp.edit().remove("played_${date}_${prayer.name}").commit()
    }

    private companion object {
        const val KEY_ADHAN_ENABLED = "adhan_enabled"
        const val KEY_SOUND = "adhan_sound"
        const val KEY_METHOD = "calc_method"
        const val KEY_FAJR_ADJUSTMENT = "fajr_adjustment_minutes"
        const val KEY_LATITUDE = "latitude"
        const val KEY_LONGITUDE = "longitude"
        const val KEY_LOCATION_SET = "location_set"
        const val KEY_NOTIFICATION_ASKED = "notification_asked"
    }
}

data class PrayerSettings(
    val latitude: Double,
    val longitude: Double,
    val method: CalcMethod,
    val fajrAdjustmentMinutes: Int,
    val locationSet: Boolean,
    val adhanEnabled: Boolean,
    val sound: AdhanSound,
    val enabled: Map<Prayer, Boolean>
)
