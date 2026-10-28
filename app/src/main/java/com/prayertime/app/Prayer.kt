package com.prayertime.app

import java.util.Locale

/** The five daily prayers, in chronological order. */
enum class Prayer(val label: String) {
    FAJR("İmsak"),
    DHUHR("Öğle"),
    ASR("İkindi"),
    MAGHRIB("Akşam"),
    ISHA("Yatsı");

    val prefKey: String get() = "adhan_${name.lowercase(Locale.US)}"
}
