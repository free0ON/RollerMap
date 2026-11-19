package ru.rollers.core.nav

import kotlinx.serialization.Serializable
import ru.rollers.core.geo.Geo
import ru.rollers.core.geo.LatLon
import ru.rollers.core.route.RouteGeometry
import kotlin.math.abs
import kotlin.math.roundToInt

@Serializable
enum class ManeuverType { TURN_LEFT, TURN_RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, SHARP_LEFT, SHARP_RIGHT, CROSS_STREET, STOP, ROADWORK, FINISH }

@Serializable
data class Maneuver(val type: ManeuverType, val along: Double, val at: LatLon, val note: String? = null)

@Serializable
data class NavState(
    val along: Double,
    val remainingM: Double,
    val distanceFromRoute: Double,
    val offRoute: Boolean,
    val speedKmh: Double,
    val avgSpeedKmh: Double,
    val next: Maneuver?,
    val distanceToNext: Double?,
    /** Отклонение от графика события, с (>0 — отставание). */
    val scheduleDeltaSec: Long?,
    val finished: Boolean,
)

/**
 * Пошаговая навигация по маршруту с голосовыми подсказками.
 * Не зависит от Android: на вход — координаты и время, на выход — состояние и фразы для TTS.
 */
class Navigator(
    private val geometry: RouteGeometry,
    stops: List<Pair<String, LatLon>> = emptyList(),
    roadworkSegments: Set<Int> = emptySet(),
    /** Запланированная средняя скорость события (для контроля графика), км/ч. */
    private val plannedSpeedKmh: Double? = null,
    private val startAt: Long? = null,
    private val speedAnnounceEveryM: Double = 1000.0,
    private val offRouteThresholdM: Double = 35.0,
) {
    val maneuvers: List<Maneuver> = buildManeuvers(stops, roadworkSegments)

    private var along = 0.0
    private var lastFix: Pair<LatLon, Long>? = null
    private var firstTime: Long? = null
    private var movingMs = 0L
    private var travelled = 0.0
    private var smoothedSpeed = 0.0
    private val announced = mutableSetOf<String>()
    private var offRouteSince: Long? = null
    private var lastSpeedAnnounceAt = 0.0
    private var finished = false

    private fun buildManeuvers(stops: List<Pair<String, LatLon>>, roadworkSegments: Set<Int>): List<Maneuver> {
        val line = geometry.line; val cum = geometry.cumulative
        val result = mutableListOf<Maneuver>()
        if (line.size >= 3) {
            // сравниваем азимуты на 15 м до и после вершины — устойчиво к «дребезгу» геометрии
            var lastTurnAlong = -100.0
            for (i in 1 until line.size - 1) {
                val before = Geo.pointAt(line, cum[i] - 15, cum)
                val after = Geo.pointAt(line, cum[i] + 15, cum)
                if (Geo.distance(before, line[i]) < 3 || Geo.distance(line[i], after) < 3) continue
                val diff = Geo.angleDiff(Geo.bearing(before, line[i]), Geo.bearing(line[i], after))
                val type = when {
                    abs(diff) < 30 -> null
                    abs(diff) < 60 -> if (diff > 0) ManeuverType.SLIGHT_RIGHT else ManeuverType.SLIGHT_LEFT
                    abs(diff) < 135 -> if (diff > 0) ManeuverType.TURN_RIGHT else ManeuverType.TURN_LEFT
                    else -> if (diff > 0) ManeuverType.SHARP_RIGHT else ManeuverType.SHARP_LEFT
                } ?: continue
                if (cum[i] - lastTurnAlong < 25) continue
                lastTurnAlong = cum[i]
                result += Maneuver(type, cum[i], line[i], geometry.segments.getOrNull(geometry.segmentIndexAt(cum[i] + 1))?.streetName)
            }
        }
        geometry.crossings.forEach { result += Maneuver(ManeuverType.CROSS_STREET, it.along, it.at) }
        stops.forEach { (name, p) ->
            val pr = Geo.project(p, line, cum)
            result += Maneuver(ManeuverType.STOP, pr.along, pr.point, name)
        }
        roadworkSegments.forEach { i ->
            val r = geometry.segmentRanges[i]
            result += Maneuver(ManeuverType.ROADWORK, r.start, Geo.pointAt(line, r.start, cum))
        }
        if (line.isNotEmpty()) result += Maneuver(ManeuverType.FINISH, geometry.length, line.last())
        return result.sortedBy { it.along }
    }

    /** Обработать новую точку. Возвращает состояние и список фраз для озвучивания. */
    fun update(p: LatLon, time: Long, gpsSpeedMps: Double? = null): Pair<NavState, List<String>> {
        val say = mutableListOf<String>()
        if (firstTime == null) { firstTime = time; say += "Навигация начата. Длина маршрута ${km(geometry.length)}." }

        // проекция: ищем вблизи предыдущей позиции, чтобы не «перескакивать» на параллельный участок
        val pr = Geo.project(p, geometry.line, geometry.cumulative)
        if (pr.along >= along - 50) along = maxOf(along, pr.along) else if (pr.distanceFromLine < 15) along = pr.along

        val prev = lastFix
        if (prev != null) {
            val dt = time - prev.second
            val d = Geo.distance(prev.first, p)
            if (dt in 1..30_000) {
                val instant = gpsSpeedMps ?: (d / (dt / 1000.0))
                smoothedSpeed = smoothedSpeed * 0.6 + instant * 0.4
                if (instant > 0.8) { movingMs += dt; travelled += d }
            }
        }
        lastFix = p to time

        // уход с маршрута — с задержкой 5 с, чтобы не реагировать на одиночный выброс GPS
        val offRoute = if (pr.distanceFromLine > offRouteThresholdM) {
            if (offRouteSince == null) offRouteSince = time
            time - offRouteSince!! > 5_000
        } else { offRouteSince = null; announced.remove("off"); false }
        if (offRoute && announced.add("off")) say += "Вы сошли с маршрута. Вернитесь на ${(pr.distanceFromLine).roundToInt()} метров."

        val next = maneuvers.firstOrNull { it.along > along - 5 }
        val dNext = next?.let { it.along - along }
        if (next != null && dNext != null && !offRoute) {
            val key = "${next.type}@${next.along.roundToInt()}"
            when {
                dNext <= 30 && announced.add("$key/now") -> say += phrase(next, null)
                dNext in 30.0..200.0 && dNext > 120 && announced.add("$key/far") -> say += phrase(next, dNext)
            }
        }

        if (along - lastSpeedAnnounceAt >= speedAnnounceEveryM) {
            lastSpeedAnnounceAt = along
            say += "Пройдено ${km(along)}. Скорость ${(smoothedSpeed * 3.6).roundToInt()}, средняя ${avgKmh().roundToInt()} километров в час."
            scheduleDelta(time)?.let { d ->
                if (abs(d) > 60) say += if (d > 0) "Отставание от графика ${d / 60} мин." else "Опережение графика ${-d / 60} мин."
            }
        }

        if (!finished && geometry.length - along < 15) {
            finished = true
            say += "Финиш! Дистанция ${km(travelled)}, средняя скорость ${avgKmh().roundToInt()} километров в час."
        }

        return NavState(
            along = along, remainingM = (geometry.length - along).coerceAtLeast(0.0),
            distanceFromRoute = pr.distanceFromLine, offRoute = offRoute,
            speedKmh = smoothedSpeed * 3.6, avgSpeedKmh = avgKmh(),
            next = next, distanceToNext = dNext, scheduleDeltaSec = scheduleDelta(time), finished = finished,
        ) to say
    }

    private fun avgKmh() = if (movingMs > 0) travelled / (movingMs / 1000.0) * 3.6 else 0.0

    private fun scheduleDelta(time: Long): Long? {
        val v = plannedSpeedKmh ?: return null
        val s = startAt ?: return null
        val expectedAlong = (time - s) / 1000.0 * v / 3.6
        return ((expectedAlong - along) / (v / 3.6)).toLong()
    }

    private fun km(m: Double) = if (m < 1000) "${m.roundToInt()} метров" else "%.1f километра".format(m / 1000).replace('.', ',')

    fun phrase(m: Maneuver, distance: Double?): String {
        val pre = distance?.let { "Через ${(it / 10).roundToInt() * 10} метров " } ?: ""
        val street = m.note?.let { " на $it" } ?: ""
        val body = when (m.type) {
            ManeuverType.TURN_LEFT -> "поверните налево$street"
            ManeuverType.TURN_RIGHT -> "поверните направо$street"
            ManeuverType.SLIGHT_LEFT -> "держитесь левее$street"
            ManeuverType.SLIGHT_RIGHT -> "держитесь правее$street"
            ManeuverType.SHARP_LEFT -> "резкий поворот налево$street"
            ManeuverType.SHARP_RIGHT -> "резкий поворот направо$street"
            ManeuverType.CROSS_STREET -> "переход на другую сторону улицы, используйте пешеходный переход"
            ManeuverType.STOP -> "остановка${m.note?.let { " «$it»" } ?: ""}"
            ManeuverType.ROADWORK -> "ремонт дороги, будьте внимательны"
            ManeuverType.FINISH -> "финиш"
        }
        return (pre + body).replaceFirstChar { it.uppercase() }
    }
}
