package ru.rollers.core.conditions

import kotlinx.serialization.Serializable
import ru.rollers.core.geo.Geo
import ru.rollers.core.geo.LatLon
import ru.rollers.core.model.Roadwork
import ru.rollers.core.route.RouteGeometry
import ru.rollers.core.surface.SurfaceScore
import kotlin.math.cos
import kotlin.math.roundToInt

/** Почасовой прогноз в точке (формат, близкий к Open-Meteo). */
@Serializable
data class WeatherHour(
    val time: Long,
    val temperatureC: Double,
    val precipitationMm: Double,
    val precipitationProbability: Int,
    /** Скорость ветра, м/с. */
    val windSpeed: Double,
    val windGusts: Double = windSpeed,
    /** Направление, ОТКУДА дует ветер, градусы. */
    val windFromDeg: Double,
)

@Serializable
enum class Severity { OK, CAUTION, WARNING, BLOCKER }

@Serializable
data class Issue(val severity: Severity, val message: String, val alongFrom: Double? = null, val alongTo: Double? = null)

@Serializable
data class SegmentConditions(
    val index: Int,
    val etaMs: Long,
    val lengthM: Double,
    val bearing: Double,
    /** Встречная составляющая ветра, м/с (>0 — в лицо, <0 — попутный). */
    val headwind: Double,
    val crosswind: Double,
    val phase: DaylightPhase,
    val lit: Boolean?,
    val surface: SurfaceScore?,
    val roadworkIds: List<Long>,
)

@Serializable
data class ConditionsReport(
    val startAt: Long,
    val finishAt: Long,
    val lengthM: Double,
    val segments: List<SegmentConditions>,
    val issues: List<Issue>,
    /** Доля пути, проходимая в темноте по неосвещённым участкам. */
    val darkUnlitShare: Double,
    val avgHeadwind: Double,
    val maxPrecipitationProbability: Int,
    /** Общая пригодность 0..100. */
    val suitability: Int,
)

/**
 * Сводная оценка условий на маршруте к заданному времени старта:
 * осадки, ветер (относительно направления каждого участка), освещение и светлое время,
 * покрытие и дорожные работы. ETA каждого участка считается по средней скорости.
 */
object RouteConditions {

    fun roadworksOnRoute(geometry: RouteGeometry, roadworks: List<Roadwork>, atMs: Long): Map<Int, List<Roadwork>> {
        val active = roadworks.filter { (it.from == null || it.from <= atMs) && (it.until == null || it.until >= atMs) }
        val result = mutableMapOf<Int, MutableList<Roadwork>>()
        geometry.segmentLines.forEachIndexed { i, line ->
            val samples = densify(line, 10.0)
            for (rw in active) {
                val hit = samples.any { p ->
                    (rw.polygon.size >= 3 && Geo.pointInPolygon(p, rw.polygon)) ||
                        (rw.center != null && Geo.distance(p, rw.center) <= rw.radiusM)
                }
                if (hit) result.getOrPut(i) { mutableListOf() } += rw
            }
        }
        return result
    }

    fun headAndCross(bearing: Double, windFromDeg: Double, windSpeed: Double): Pair<Double, Double> {
        // ветер дует ИЗ windFrom, т.е. движется в сторону windFrom+180.
        // Встречный — когда мы едем навстречу направлению, откуда он дует.
        val rel = Math.toRadians(Geo.angleDiff(bearing, windFromDeg))
        return windSpeed * cos(rel) to windSpeed * kotlin.math.abs(kotlin.math.sin(rel))
    }

    private fun weatherAt(weather: List<WeatherHour>, t: Long): WeatherHour? =
        weather.minByOrNull { kotlin.math.abs(it.time - t) }?.takeIf { kotlin.math.abs(it.time - t) <= 2 * 3_600_000 }

    fun assess(
        geometry: RouteGeometry,
        startAt: Long,
        avgSpeedKmh: Double,
        weather: List<WeatherHour>,
        surfaces: Map<Int, SurfaceScore> = emptyMap(),
        roadworks: List<Roadwork> = emptyList(),
        stopsDurationMs: Long = 0,
    ): ConditionsReport {
        require(avgSpeedKmh > 0)
        val mps = avgSpeedKmh / 3.6
        val totalRideMs = (geometry.length / mps * 1000).toLong()
        val rw = roadworksOnRoute(geometry, roadworks, startAt)
        val issues = mutableListOf<Issue>()
        val segs = geometry.segmentLines.mapIndexed { i, line ->
            val range = geometry.segmentRanges[i]
            val mid = (range.start + range.endInclusive) / 2
            // упрощённо: остановки распределяем равномерно по маршруту
            val eta = startAt + (mid / mps * 1000).toLong() +
                if (geometry.length > 0) (stopsDurationMs * mid / geometry.length).toLong() else 0
            val bearing = if (line.size >= 2) Geo.bearing(line.first(), line.last()) else 0.0
            val midPoint = Geo.pointAt(geometry.line, mid, geometry.cumulative)
            val w = weatherAt(weather, eta)
            val (head, cross) = if (w != null) headAndCross(bearing, w.windFromDeg, w.windSpeed) else 0.0 to 0.0
            SegmentConditions(
                index = i, etaMs = eta, lengthM = range.endInclusive - range.start, bearing = bearing,
                headwind = head, crosswind = cross,
                phase = SunCalc.phase(eta, midPoint.lat, midPoint.lon),
                lit = geometry.segments[i].lit, surface = surfaces[i],
                roadworkIds = rw[i].orEmpty().map { it.id },
            )
        }

        // --- Осадки
        val relevantWeather = weather.filter { it.time in (startAt - 3_600_000)..(startAt + totalRideMs + stopsDurationMs + 3_600_000) }
        val maxProb = relevantWeather.maxOfOrNull { it.precipitationProbability } ?: 0
        val totalPrecip = relevantWeather.sumOf { it.precipitationMm }
        when {
            totalPrecip >= 1.0 -> issues += Issue(Severity.BLOCKER, "Ожидаются осадки ${"%.1f".format(totalPrecip)} мм — мокрый асфальт опасен для роликов")
            maxProb >= 60 -> issues += Issue(Severity.WARNING, "Вероятность осадков до $maxProb%")
            maxProb >= 30 -> issues += Issue(Severity.CAUTION, "Возможны осадки ($maxProb%)")
        }
        // Дождь незадолго до старта — асфальт может быть мокрым
        val before = weather.filter { it.time in (startAt - 3 * 3_600_000)..startAt }.sumOf { it.precipitationMm }
        if (before >= 0.5 && totalPrecip < 1.0) issues += Issue(Severity.CAUTION, "Недавно был дождь — возможны лужи и мокрое покрытие")
        relevantWeather.minOfOrNull { it.temperatureC }?.let { if (it < 3) issues += Issue(Severity.WARNING, "Температура до ${it.roundToInt()}°C — возможна наледь") }

        // --- Ветер
        val len = geometry.length.coerceAtLeast(1.0)
        val avgHead = segs.sumOf { it.headwind * it.lengthM } / len
        val maxGust = relevantWeather.maxOfOrNull { it.windGusts } ?: 0.0
        if (maxGust >= 15) issues += Issue(Severity.WARNING, "Порывы ветра до ${maxGust.roundToInt()} м/с")
        segs.filter { it.headwind >= 6 }.forEach {
            val r = geometry.segmentRanges[it.index]
            issues += Issue(Severity.CAUTION, "Сильный встречный ветер ${it.headwind.roundToInt()} м/с", r.start, r.endInclusive)
        }

        // --- Освещение
        var darkUnlit = 0.0
        segs.forEach {
            if (it.phase == DaylightPhase.DARK && it.lit != true) darkUnlit += it.lengthM
        }
        val darkUnlitShare = darkUnlit / len
        if (darkUnlitShare > 0.0) {
            val sev = if (darkUnlitShare > 0.3) Severity.WARNING else Severity.CAUTION
            issues += Issue(sev, "${(darkUnlitShare * 100).roundToInt()}% пути — в темноте без освещения. Нужны фонарь и светоотражатели")
        }
        if (segs.any { it.phase == DaylightPhase.CIVIL_TWILIGHT }) issues += Issue(Severity.CAUTION, "Часть пути придётся на сумерки")

        // --- Покрытие
        segs.forEach { s ->
            val sc = s.surface ?: return@forEach
            val r = geometry.segmentRanges[s.index]
            if (sc.score < 1.5) issues += Issue(Severity.BLOCKER, "Непроезжаемое покрытие", r.start, r.endInclusive)
            else if (sc.score < 2.5) issues += Issue(Severity.WARNING, "Плохое покрытие (${"%.1f".format(sc.score)})", r.start, r.endInclusive)
        }

        // --- Ремонт
        rw.forEach { (i, list) ->
            val r = geometry.segmentRanges[i]
            list.forEach { issues += Issue(Severity.WARNING, "Ремонт: ${it.description}", r.start, r.endInclusive) }
        }

        val penalty = issues.sumOf {
            when (it.severity) { Severity.OK -> 0; Severity.CAUTION -> 7; Severity.WARNING -> 18; Severity.BLOCKER -> 45 }.toInt()
        }
        return ConditionsReport(
            startAt = startAt,
            finishAt = startAt + totalRideMs + stopsDurationMs,
            lengthM = geometry.length,
            segments = segs,
            issues = issues.sortedByDescending { it.severity },
            darkUnlitShare = darkUnlitShare,
            avgHeadwind = avgHead,
            maxPrecipitationProbability = maxProb,
            suitability = (100 - penalty).coerceIn(0, 100),
        )
    }

    private fun densify(line: List<LatLon>, stepM: Double): List<LatLon> {
        if (line.size < 2) return line
        val cum = Geo.cumulative(line)
        val n = (cum.last() / stepM).toInt()
        return (0..n).map { Geo.pointAt(line, it * stepM, cum) } + line.last()
    }
}
