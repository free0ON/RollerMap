package ru.rollers.core.track

import kotlinx.serialization.Serializable
import ru.rollers.core.geo.Geo
import ru.rollers.core.model.TrackPoint
import ru.rollers.core.route.RouteGeometry

@Serializable
data class ActivityStats(
    val distanceM: Double,
    val durationMs: Long,
    val movingMs: Long,
    /** Максимальная скорость (сглаженная по окну ~5 с), км/ч. */
    val maxSpeedKmh: Double,
    /** Средняя скорость в движении, км/ч. */
    val avgMovingSpeedKmh: Double,
    /** Средняя скорость на участках маршрута (индекс участка → км/ч), если трек привязан к маршруту. */
    val segmentSpeedsKmh: Map<Int, Double> = emptyMap(),
)

/**
 * Обработка GPS-трека: фильтрация выбросов, сглаживание, подсчёт статистики.
 * Ограничения подобраны для роликов: скорость выше 70 км/ч считаем ошибкой GPS.
 */
object TrackStats {
    const val MAX_PLAUSIBLE_MPS = 70 / 3.6
    const val MOVING_THRESHOLD_MPS = 3 / 3.6
    const val MAX_ACCURACY_M = 25.0
    const val SMOOTH_WINDOW_MS = 5_000L

    fun clean(points: List<TrackPoint>): List<TrackPoint> {
        val sorted = points.filter { (it.accuracy ?: 0.0) <= MAX_ACCURACY_M }.sortedBy { it.time }
        val out = ArrayList<TrackPoint>(sorted.size)
        for (p in sorted) {
            val last = out.lastOrNull()
            if (last == null) { out += p; continue }
            val dt = (p.time - last.time) / 1000.0
            if (dt <= 0) continue
            if (Geo.distance(last.point, p.point) / dt > MAX_PLAUSIBLE_MPS) continue
            out += p
        }
        return out
    }

    fun compute(raw: List<TrackPoint>, route: RouteGeometry? = null): ActivityStats {
        val pts = clean(raw)
        if (pts.size < 2) return ActivityStats(0.0, 0, 0, 0.0, 0.0)
        var dist = 0.0; var moving = 0L
        for (i in 1 until pts.size) {
            val d = Geo.distance(pts[i - 1].point, pts[i].point)
            val dt = pts[i].time - pts[i - 1].time
            dist += d
            if (dt > 0 && d / (dt / 1000.0) >= MOVING_THRESHOLD_MPS && dt < 60_000) moving += dt
        }
        // Максимальная скорость — по скользящему окну, чтобы единичный скачок GPS не давал рекорд
        var maxMps = 0.0
        var j = 0; var windowDist = 0.0
        for (i in 1 until pts.size) {
            windowDist += Geo.distance(pts[i - 1].point, pts[i].point)
            while (pts[i].time - pts[j].time > SMOOTH_WINDOW_MS && j < i - 1) {
                windowDist -= Geo.distance(pts[j].point, pts[j + 1].point); j++
            }
            val dt = (pts[i].time - pts[j].time) / 1000.0
            if (dt >= SMOOTH_WINDOW_MS / 2000.0) maxMps = maxOf(maxMps, windowDist / dt)
        }
        val avg = if (moving > 0) dist / (moving / 1000.0) else 0.0
        return ActivityStats(
            distanceM = dist,
            durationMs = pts.last().time - pts.first().time,
            movingMs = moving,
            maxSpeedKmh = maxMps * 3.6,
            avgMovingSpeedKmh = avg * 3.6,
            segmentSpeedsKmh = route?.let { segmentSpeeds(pts, it) } ?: emptyMap(),
        )
    }

    /**
     * Скорость на каждом участке маршрута: точки трека проецируются на маршрут,
     * для каждого участка берётся время входа/выхода. Точки дальше 30 м от маршрута игнорируются.
     */
    fun segmentSpeeds(pts: List<TrackPoint>, route: RouteGeometry): Map<Int, Double> {
        if (route.line.size < 2) return emptyMap()
        data class Hit(val seg: Int, val along: Double, val time: Long)
        val hits = pts.mapNotNull { p ->
            val pr = Geo.project(p.point, route.line, route.cumulative)
            if (pr.distanceFromLine > 30) null else Hit(route.segmentIndexAt(pr.along), pr.along, p.time)
        }
        return hits.groupBy { it.seg }.mapNotNull { (seg, h) ->
            if (h.size < 2) return@mapNotNull null
            val d = h.maxOf { it.along } - h.minOf { it.along }
            val t = (h.maxOf { it.time } - h.minOf { it.time }) / 1000.0
            if (t < 3 || d < 20) null else seg to d / t * 3.6
        }.toMap()
    }
}
