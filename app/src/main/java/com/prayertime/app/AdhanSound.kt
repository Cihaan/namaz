package com.prayertime.app

/** The three audio files the app can play. File names match res/raw. */
enum class AdhanSound(val label: String, val rawName: String) {
    FULL("Tam", "adhan_full"),
    MID("Orta", "adhan_mid"),
    SHORT("Kısa", "adhan_short");

    companion object {
        fun fromName(name: String?): AdhanSound = entries.firstOrNull { it.name == name } ?: FULL
    }
}
