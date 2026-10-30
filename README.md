# Namaz Vakitleri

A simple, landscape-only Android app for daily prayer times and adhan playback. Prayer times are calculated on the device; the app has no `INTERNET` permission and does not need a connection for its calculations or bundled audio.

## Features

- Large countdown to the next prayer, with the day's prayer times alongside it.
- Offline solar prayer-time calculations for Muslim World League, ISNA, Egypt, Karachi, Umm al-Qura, and Diyanet methods. Asr uses the Shafi'i convention; very high-latitude times use an approximation.
- Device timezone and daylight-saving rules are used automatically. The screen shows the UTC offset rather than a city name.
- A configurable **0–30 minute imsak adjustment**, defaulting to **+20 minutes**. The adjustment applies to the selected calculation method.
- Full, medium, and short adhan options. The medium recording is the beginning of the full recording, at its original speed, ending after the second shahada.
- Global and per-prayer adhan switches, plus preview and Stop controls.
- A compact battery indicator below the countdown: green above 30%, amber at 16–30%, and red at 15% or lower. It also shows when the phone is charging and never interrupts or changes adhan playback.
- Location can be obtained from available device location providers or entered manually. Coordinates are stored locally.

## Build

Requirements:

- JDK 17
- Android SDK Platform 34 and Android build tools

Build the installable debug APK from the project root:

```bash
./gradlew assembleDebug
```

Output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The debug APK is signed with Android's debug key and is suitable for local installation. For example, with a connected Android device and `adb` installed:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The app supports Android 8.0 (API 26) and later. The project uses native Android framework components and has no third-party library dependencies.

## First use

1. Open **Ayarlar** (Settings) and choose **Mevcut konumumu al** (Use my current location), or enter latitude and longitude manually. Location permission is only needed for automatic location.
2. Select a calculation method. For Diyanet times, the imsak adjustment starts at +20 minutes; change it if needed.
3. Choose the adhan recording and which prayers should play it, then save.
4. On Android 12 and later, grant **Kesin alarm izni** (exact-alarm access) in Settings for the most punctual playback. Allow notifications on Android 13 and later so the playback notification and Stop action are visible.
5. To reduce delays on devices with aggressive power management, allow the app to ignore battery optimization. Also make sure alarm volume is audible and that Do Not Disturb allows alarms.

The app schedules alarms locally and refreshes them after reboot, app updates, clock or timezone changes, and exact-alarm permission changes. It extends its rolling alarm window one day at a time instead of rebuilding every scheduled alarm after each prayer. The device's current timezone is used automatically when calculating and displaying times. The main clock keeps the display awake while it is open; screen brightness and the display itself remain the largest power consumers. Background countdown updates stop when the main screen is no longer active.

## Android playback limitations

The app uses exact alarms when Android allows them, an alarm-usage audio stream, a foreground media-playback service, and a wake lock. If exact-alarm access is denied, Android may defer the fallback alarm. Notification permission is for the visible playback/Stop notification; denying it does not intentionally disable adhan audio.

No Android app can guarantee audible playback in every device state. Android or the device manufacturer can prevent or delay it if the phone is powered off, the app is force-stopped, background activity is restricted, alarm volume is muted, or Do Not Disturb is configured to silence alarms. Grant the recommended permissions and check the phone's manufacturer-specific battery settings for best reliability.

## Audio credits

- “Beautiful adhan.ogg” by Adam-synagda, **CC0 1.0** — [Wikimedia Commons](https://commons.wikimedia.org/wiki/File:Beautiful_adhan.ogg). Used for the full recording and the medium excerpt.
- “Adhan.ogg”, **CC0 1.0** — [Wikimedia Commons](https://commons.wikimedia.org/wiki/File:Adhan.ogg). Used for the short recording.

The bundled AAC audio files are in `app/src/main/res/raw/`.
