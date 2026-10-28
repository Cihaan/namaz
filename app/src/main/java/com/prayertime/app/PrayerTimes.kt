package com.prayertime.app

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Offline prayer time calculation.
 *
 * The sun's declination and the equation of time come from the NOAA solar equations.
 * Solar noon is converted to the device's current time zone, so daylight saving time
 * and travel are handled automatically. No internet connection is ever used.
 */
object PrayerTimes {

    private const val SUNRISE_ALTITUDE = -0.833 // refraction + solar radius
    private const val DHUHR_DELAY_SECONDS = 60L

    fun forDate(
        date: LocalDate,
        zone: ZoneId,
        settings: PrayerSettings
    ): Map<Prayer, ZonedDateTime> {
        val noon = solarNoon(date, zone, settings.longitude)
        val declination = declination(noon)
        val latitude = settings.latitude
        val fallbackLatitude = if (abs(latitude) > 48.0) Math.copySign(48.0, latitude) else latitude
        val method = settings.method

        // Hour angle of the sun for a given altitude. Null means the sun never reaches it.
        fun hoursAt(altitude: Double, atLatitude: Double = latitude): Double? =
            hourOffset(atLatitude, declination, altitude)

        fun hoursOrFallback(altitude: Double, fallback: Double): Double =
            hoursAt(altitude) ?: hoursAt(altitude, fallbackLatitude) ?: fallback

        val sunriseHours = hoursOrFallback(SUNRISE_ALTITUDE, 6.0)
        val fajrHours = hoursOrFallback(-method.fajrAngle, 6.0)
        val asr = asrHourOffset(latitude, declination)
            ?: asrHourOffset(fallbackLatitude, declination)
            ?: 4.0

        val fajr = time(noon, fajrHours, beforeNoon = true, zone)
        val sunrise = time(noon, sunriseHours, beforeNoon = true, zone)
        val dhuhr = noon.plusSeconds(DHUHR_DELAY_SECONDS)
            .atZone(zone)
            .plusSeconds(30)
            .truncatedTo(ChronoUnit.MINUTES)
        val asrTime = time(noon, asr, beforeNoon = false, zone)
        val maghrib = time(noon, sunriseHours, beforeNoon = false, zone)

        val isha = if (method.ishaIntervalMinutes != null) {
            maghrib.plusMinutes(method.ishaIntervalMinutes.toLong())
        } else {
            val ishaHours = hoursOrFallback(-method.ishaAngle, 6.0)
            time(noon, ishaHours, beforeNoon = false, zone)
        }

        // Keep the values ordered even at extreme latitudes where the fallbacks kick in.
        val safeFajr = if (fajr.isAfter(sunrise)) sunrise.minusMinutes(60) else fajr
        val adjustedFajr = safeFajr.plusMinutes(settings.fajrAdjustmentMinutes.toLong())
        val finalFajr = if (adjustedFajr.isAfter(sunrise)) sunrise else adjustedFajr
        val safeIsha = if (isha.isBefore(maghrib)) maghrib.plusMinutes(60) else isha

        return mapOf(
            Prayer.FAJR to finalFajr,
            Prayer.DHUHR to dhuhr,
            Prayer.ASR to asrTime,
            Prayer.MAGHRIB to maghrib,
            Prayer.ISHA to safeIsha
        )
    }

    private fun time(noon: Instant, hours: Double, beforeNoon: Boolean, zone: ZoneId): ZonedDateTime {
        val seconds = (hours * 3600).toLong()
        val instant = if (beforeNoon) noon.minusSeconds(seconds) else noon.plusSeconds(seconds)
        // Round to the nearest minute so the displayed time and the alarm agree.
        return instant.atZone(zone).plusSeconds(30).truncatedTo(ChronoUnit.MINUTES)
    }

    /** Returns the hour angle in hours for the given solar altitude, or null if it never happens. */
    private fun hourOffset(latitude: Double, declination: Double, altitude: Double): Double? {
        val latRad = Math.toRadians(latitude)
        val denominator = cos(latRad) * cos(declination)
        if (denominator == 0.0) return null
        val cosH = (sin(Math.toRadians(altitude)) - sin(latRad) * sin(declination)) / denominator
        if (cosH < -1.0 || cosH > 1.0) return null
        return Math.toDegrees(acos(cosH)) / 15.0
    }

    /** Asr when an object's shadow is equal to its height (Shafi method). */
    private fun asrHourOffset(latitude: Double, declination: Double): Double? {
        val latRad = Math.toRadians(latitude)
        val altitude = Math.toDegrees(atan(1.0 / (1.0 + tan(abs(latRad - declination)))))
        return hourOffset(latitude, declination, altitude)
    }

    /**
     * Solar noon (the moment the sun crosses the meridian) for the local date.
     * Three UTC days are tried so this works in every time zone, including UTC+14 and UTC-12.
     */
    private fun solarNoon(date: LocalDate, zone: ZoneId, longitude: Double): Instant {
        val localStart = date.atStartOfDay(zone).toInstant()
        val utcDate = localStart.atZone(ZoneOffset.UTC).toLocalDate()
        var firstCandidate: Instant? = null

        for (shift in -1L..1L) {
            val day = utcDate.plusDays(shift)
            val midnight = day.atStartOfDay(ZoneOffset.UTC).toInstant()
            var minutes = 720.0 - 4.0 * longitude
            repeat(2) {
                val guess = midnight.plusSeconds((minutes * 60).toLong())
                minutes = 720.0 - 4.0 * longitude - solarPosition(guess).equationOfTime
            }
            val noon = midnight.plusSeconds((minutes * 60).toLong())
            if (noon.atZone(zone).toLocalDate() == date) return noon
            if (firstCandidate == null) firstCandidate = noon
        }
        return firstCandidate!!
    }

    /** Sun declination in radians for the given instant. */
    private fun declination(at: Instant): Double = solarPosition(at).declination

    /**
     * NOAA solar position equations. Accurate to well under a minute of prayer time,
     * unlike the short Fourier approximations found in many tutorials.
     */
    private fun solarPosition(at: Instant): SolarPosition {
        val days = at.epochSecond / 86400.0 + 2440587.5
        val t = (days - 2451545.0) / 36525.0

        val meanLongitude = normalizeDegrees(280.46646 + t * (36000.76983 + t * 0.0003032))
        val meanAnomaly = 357.52911 + t * (35999.05029 - 0.0001537 * t)
        val eccentricity = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val obliquity = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0 +
            0.00256 * cos(Math.toRadians(125.04 - 1934.136 * t))

        val anomalyRad = Math.toRadians(meanAnomaly)
        val longitudeRad = Math.toRadians(meanLongitude)
        var y = tan(Math.toRadians(obliquity) / 2.0)
        y *= y

        val equationOfTime = 4.0 * Math.toDegrees(
            y * sin(2 * longitudeRad) -
                2.0 * eccentricity * sin(anomalyRad) +
                4.0 * eccentricity * y * sin(anomalyRad) * cos(2 * longitudeRad) -
                0.5 * y * y * sin(4 * longitudeRad) -
                1.25 * eccentricity * eccentricity * sin(2 * anomalyRad)
        )

        val apparentLongitude = meanLongitude +
            1.914602 * sin(anomalyRad) +
            0.019993 * sin(2 * anomalyRad) +
            0.000289 * sin(3 * anomalyRad) -
            0.00569 -
            0.00478 * sin(Math.toRadians(125.04 - 1934.136 * t))

        val declination = Math.toDegrees(
            Math.asin(sin(Math.toRadians(obliquity)) * sin(Math.toRadians(apparentLongitude)))
        )

        return SolarPosition(Math.toRadians(declination), equationOfTime)
    }

    private fun normalizeDegrees(degrees: Double): Double {
        var value = degrees % 360.0
        if (value < 0) value += 360.0
        return value
    }

    private data class SolarPosition(val declination: Double, val equationOfTime: Double)
}
