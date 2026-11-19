package ru.rollers.core.conditions

import kotlinx.serialization.Serializable
import kotlin.math.*

/** Фаза освещённости по высоте Солнца. */
@Serializable
enum class DaylightPhase(val title: String) {
    DAY("День"),
    CIVIL_TWILIGHT("Гражданские сумерки"),
    DARK("Темно"),
}

/**
 * Положение Солнца (алгоритм NOAA, точность ~1 мин для восхода/захода).
 * Время — epoch millis UTC.
 */
object SunCalc {
    private const val DAY_MS = 86_400_000.0
    private const val J1970 = 2440587.5
    private const val J2000 = 2451545.0

    private fun toJulian(ms: Long) = ms / DAY_MS + J1970

    /** Высота Солнца над горизонтом, градусы. */
    fun elevation(timeMs: Long, lat: Double, lon: Double): Double {
        val d = toJulian(timeMs) - J2000
        val t = d / 36525.0
        val l0 = (280.46646 + t * (36000.76983 + t * 0.0003032)) % 360
        val m = 357.52911 + t * (35999.05029 - 0.0001537 * t)
        val mr = Math.toRadians(m)
        val c = sin(mr) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sin(2 * mr) * (0.019993 - 0.000101 * t) + sin(3 * mr) * 0.000289
        val trueLong = l0 + c
        val omega = 125.04 - 1934.136 * t
        val lambda = trueLong - 0.00569 - 0.00478 * sin(Math.toRadians(omega))
        val eps0 = 23 + (26 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60) / 60
        val eps = eps0 + 0.00256 * cos(Math.toRadians(omega))
        val decl = asin(sin(Math.toRadians(eps)) * sin(Math.toRadians(lambda)))

        // уравнение времени (мин)
        val y = tan(Math.toRadians(eps / 2)).pow(2)
        val e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val l0r = Math.toRadians(l0)
        val eqTime = 4 * Math.toDegrees(
            y * sin(2 * l0r) - 2 * e * sin(mr) + 4 * e * y * sin(mr) * cos(2 * l0r) -
                0.5 * y * y * sin(4 * l0r) - 1.25 * e * e * sin(2 * mr)
        )
        val minutesUtc = ((timeMs % DAY_MS.toLong()) + DAY_MS.toLong()) % DAY_MS.toLong() / 60000.0
        val trueSolarTime = ((minutesUtc + eqTime + 4 * lon) % 1440 + 1440) % 1440
        var hourAngle = trueSolarTime / 4 - 180
        if (hourAngle < -180) hourAngle += 360
        val latR = Math.toRadians(lat)
        val cosZenith = sin(latR) * sin(decl) + cos(latR) * cos(decl) * cos(Math.toRadians(hourAngle))
        val zenith = Math.toDegrees(acos(cosZenith.coerceIn(-1.0, 1.0)))
        return 90 - zenith
    }

    fun phase(timeMs: Long, lat: Double, lon: Double): DaylightPhase {
        val el = elevation(timeMs, lat, lon)
        return when {
            el > -0.833 -> DaylightPhase.DAY
            el > -6 -> DaylightPhase.CIVIL_TWILIGHT
            else -> DaylightPhase.DARK
        }
    }

    @Serializable
    data class DayTimes(val sunrise: Long?, val sunset: Long?, val civilDawn: Long?, val civilDusk: Long?)

    /**
     * Восход/заход и гражданские сумерки для суток, начинающихся в [dayStartUtcMs].
     * Поиск перебором с шагом 1 мин — просто и надёжно, в т.ч. в полярных широтах (null — события нет).
     */
    fun dayTimes(dayStartUtcMs: Long, lat: Double, lon: Double): DayTimes {
        val step = 60_000L
        var sunrise: Long? = null; var sunset: Long? = null
        var dawn: Long? = null; var dusk: Long? = null
        var prev = elevation(dayStartUtcMs, lat, lon)
        var t = dayStartUtcMs + step
        while (t <= dayStartUtcMs + DAY_MS.toLong()) {
            val el = elevation(t, lat, lon)
            if (prev <= -0.833 && el > -0.833 && sunrise == null) sunrise = t
            if (prev > -0.833 && el <= -0.833 && sunset == null) sunset = t
            if (prev <= -6 && el > -6 && dawn == null) dawn = t
            if (prev > -6 && el <= -6 && dusk == null) dusk = t
            prev = el; t += step
        }
        return DayTimes(sunrise, sunset, dawn, dusk)
    }
}
