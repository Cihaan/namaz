package com.prayertime.app

/** Common calculation conventions. Asr is always the Shafi (shadow factor 1) method. */
enum class CalcMethod(
    val label: String,
    val fajrAngle: Double,
    val ishaAngle: Double,
    val ishaIntervalMinutes: Int? = null
) {
    MWL("Dünya Müslüman Birliği", 18.0, 17.0),
    ISNA("Kuzey Amerika (ISNA)", 15.0, 15.0),
    EGYPT("Mısır", 19.5, 17.5),
    KARACHI("Karaçi", 18.0, 18.0),
    UMM_AL_QURA("Ümmü'l-Kurâ", 18.5, 0.0, 90),
    DIYANET("Diyanet", 18.0, 17.0);

    companion object {
        fun fromName(name: String?): CalcMethod = entries.firstOrNull { it.name == name } ?: MWL
    }
}
