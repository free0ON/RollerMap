package ru.rollers.core.geo

import kotlinx.serialization.Serializable
import kotlin.math.*

@Serializable
data class LatLon(val lat: Double, val lon: Double)

/** Геодезические утилиты (сферическая модель Земли — точности хватает для городских маршрутов). */
object Geo {
    const val EARTH_RADIUS_M = 6_371_008.8

    fun distance(a: LatLon, b: LatLon): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
    }

    /** Азимут из a в b, градусы 0..360 (0 — север, по часовой). */
    fun bearing(a: LatLon, b: LatLon): Double {
        val f1 = Math.toRadians(a.lat); val f2 = Math.toRadians(b.lat)
        val dl = Math.toRadians(b.lon - a.lon)
        val y = sin(dl) * cos(f2)
        val x = cos(f1) * sin(f2) - sin(f1) * cos(f2) * cos(dl)
        return normalize(Math.toDegrees(atan2(y, x)))
    }

    fun destination(from: LatLon, bearingDeg: Double, distanceM: Double): LatLon {
        val d = distanceM / EARTH_RADIUS_M
        val br = Math.toRadians(bearingDeg)
        val f1 = Math.toRadians(from.lat); val l1 = Math.toRadians(from.lon)
        val f2 = asin(sin(f1) * cos(d) + cos(f1) * sin(d) * cos(br))
        val l2 = l1 + atan2(sin(br) * sin(d) * cos(f1), cos(d) - sin(f1) * sin(f2))
        return LatLon(Math.toDegrees(f2), (Math.toDegrees(l2) + 540) % 360 - 180)
    }

    fun normalize(deg: Double): Double = ((deg % 360) + 360) % 360

    /** Знаковая разница углов в диапазоне -180..180 (положительная — поворот направо). */
    fun angleDiff(from: Double, to: Double): Double {
        var d = normalize(to) - normalize(from)
        if (d > 180) d -= 360
        if (d < -180) d += 360
        return d
    }

    fun length(points: List<LatLon>): Double =
        points.zipWithNext().sumOf { (a, b) -> distance(a, b) }

    /** Кумулятивные расстояния вдоль полилинии. */
    fun cumulative(points: List<LatLon>): DoubleArray {
        val out = DoubleArray(points.size)
        for (i in 1 until points.size) out[i] = out[i - 1] + distance(points[i - 1], points[i])
        return out
    }

    /** Локальная проекция в метры (equirectangular) относительно опорной точки. */
    private fun toXY(p: LatLon, ref: LatLon): Pair<Double, Double> {
        val x = Math.toRadians(p.lon - ref.lon) * cos(Math.toRadians(ref.lat)) * EARTH_RADIUS_M
        val y = Math.toRadians(p.lat - ref.lat) * EARTH_RADIUS_M
        return x to y
    }

    private fun fromXY(x: Double, y: Double, ref: LatLon): LatLon = LatLon(
        ref.lat + Math.toDegrees(y / EARTH_RADIUS_M),
        ref.lon + Math.toDegrees(x / (EARTH_RADIUS_M * cos(Math.toRadians(ref.lat)))),
    )

    data class Projection(
        val point: LatLon,
        val segmentIndex: Int,
        val distanceFromLine: Double,
        /** Пройденное вдоль полилинии расстояние до проекции. */
        val along: Double,
    )

    /** Ближайшая точка полилинии к [p]. */
    fun project(p: LatLon, line: List<LatLon>, cumulative: DoubleArray = cumulative(line)): Projection {
        require(line.size >= 2) { "Полилиния должна содержать минимум 2 точки" }
        var best: Projection? = null
        for (i in 0 until line.size - 1) {
            val a = line[i]; val b = line[i + 1]
            val (bx, by) = toXY(b, a); val (px, py) = toXY(p, a)
            val len2 = bx * bx + by * by
            val t = if (len2 == 0.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
            val proj = fromXY(bx * t, by * t, a)
            val d = distance(p, proj)
            if (best == null || d < best.distanceFromLine) {
                best = Projection(proj, i, d, cumulative[i] + distance(a, proj))
            }
        }
        return best!!
    }

    /** Точка на полилинии на расстоянии [along] от начала. */
    fun pointAt(line: List<LatLon>, along: Double, cumulative: DoubleArray = cumulative(line)): LatLon {
        if (along <= 0) return line.first()
        if (along >= cumulative.last()) return line.last()
        val i = cumulative.indexOfFirst { it > along } - 1
        val segLen = cumulative[i + 1] - cumulative[i]
        val t = if (segLen == 0.0) 0.0 else (along - cumulative[i]) / segLen
        return LatLon(
            line[i].lat + (line[i + 1].lat - line[i].lat) * t,
            line[i].lon + (line[i + 1].lon - line[i].lon) * t,
        )
    }

    /**
     * Смещение полилинии параллельно оси на [offsetM] метров.
     * Положительное смещение — вправо по направлению движения, отрицательное — влево.
     * Используется для привязки пути к нужной стороне улицы (тротуар, велодорожка, обочина).
     */
    fun offset(line: List<LatLon>, offsetM: Double): List<LatLon> {
        if (offsetM == 0.0 || line.size < 2) return line
        val ref = line.first()
        val xy = line.map { toXY(it, ref) }
        val out = ArrayList<LatLon>(line.size)
        for (i in xy.indices) {
            // нормали соседних сегментов
            val nPrev = if (i > 0) normal(xy[i - 1], xy[i]) else null
            val nNext = if (i < xy.size - 1) normal(xy[i], xy[i + 1]) else null
            val (nx, ny) = when {
                nPrev == null -> nNext!!
                nNext == null -> nPrev
                else -> {
                    // биссектриса с поправкой на угол, ограниченная для острых углов
                    val bx = nPrev.first + nNext.first; val by = nPrev.second + nNext.second
                    val bl = hypot(bx, by)
                    if (bl < 1e-9) nNext else {
                        val cosHalf = (bx / bl) * nNext.first + (by / bl) * nNext.second
                        val k = 1.0 / max(cosHalf, 0.5)
                        bx / bl * k to by / bl * k
                    }
                }
            }
            val (x, y) = xy[i]
            out += fromXY(x + nx * offsetM, y + ny * offsetM, ref)
        }
        return out
    }

    /** Правая единичная нормаль к сегменту a→b (в системе x — восток, y — север). */
    private fun normal(a: Pair<Double, Double>, b: Pair<Double, Double>): Pair<Double, Double> {
        val dx = b.first - a.first; val dy = b.second - a.second
        val l = hypot(dx, dy)
        return if (l < 1e-9) 0.0 to 0.0 else dy / l to -dx / l
    }

    fun pointInPolygon(p: LatLon, polygon: List<LatLon>): Boolean {
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val a = polygon[i]; val b = polygon[j]
            if ((a.lat > p.lat) != (b.lat > p.lat) &&
                p.lon < (b.lon - a.lon) * (p.lat - a.lat) / (b.lat - a.lat) + a.lon
            ) inside = !inside
            j = i
        }
        return inside
    }

    /** Упрощение Дугласа—Пекера, допуск в метрах. */
    fun simplify(points: List<LatLon>, toleranceM: Double): List<LatLon> {
        if (points.size < 3) return points
        val keep = BooleanArray(points.size).also { it[0] = true; it[it.size - 1] = true }
        fun rec(s: Int, e: Int) {
            if (e <= s + 1) return
            var maxD = 0.0; var idx = -1
            val seg = listOf(points[s], points[e])
            for (i in s + 1 until e) {
                val d = project(points[i], seg).distanceFromLine
                if (d > maxD) { maxD = d; idx = i }
            }
            if (maxD > toleranceM) { keep[idx] = true; rec(s, idx); rec(idx, e) }
        }
        rec(0, points.size - 1)
        return points.filterIndexed { i, _ -> keep[i] }
    }
}

@Serializable
data class BBox(val south: Double, val west: Double, val north: Double, val east: Double) {
    fun contains(p: LatLon) = p.lat in south..north && p.lon in west..east
    fun intersects(o: BBox) = !(o.west > east || o.east < west || o.south > north || o.north < south)

    companion object {
        fun of(points: Iterable<LatLon>): BBox {
            var s = 90.0; var w = 180.0; var n = -90.0; var e = -180.0
            for (p in points) { s = min(s, p.lat); n = max(n, p.lat); w = min(w, p.lon); e = max(e, p.lon) }
            return BBox(s, w, n, e)
        }
    }
}
