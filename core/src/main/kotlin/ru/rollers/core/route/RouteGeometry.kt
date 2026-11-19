package ru.rollers.core.route

import ru.rollers.core.geo.Geo
import ru.rollers.core.geo.LatLon
import ru.rollers.core.model.Route
import ru.rollers.core.model.RouteSegment
import ru.rollers.core.model.StreetSide

/** Переход с одной стороны улицы на другую — нужен для навигации и подсветки на карте. */
data class Crossing(val at: LatLon, val along: Double, val from: StreetSide, val to: StreetSide, val segmentIndex: Int)

/**
 * Итоговая геометрия пути роллера: ось каждого участка смещена на свою сторону,
 * участки сшиты, смены стороны отмечены как переходы.
 */
class RouteGeometry(val segments: List<RouteSegment>) {

    /** Смещённая линия каждого участка. */
    val segmentLines: List<List<LatLon>> = segments.map { s ->
        val sign = when (s.side) { StreetSide.RIGHT -> 1.0; StreetSide.LEFT -> -1.0; StreetSide.CENTER -> 0.0 }
        Geo.offset(s.axis, sign * s.offsetM)
    }

    /** Полная линия маршрута. */
    val line: List<LatLon> = buildList {
        segmentLines.forEach { seg ->
            seg.forEach { p -> if (isEmpty() || Geo.distance(last(), p) > 0.5) add(p) }
        }
    }

    val cumulative: DoubleArray = Geo.cumulative(line)
    val length: Double get() = cumulative.lastOrNull() ?: 0.0

    /** Границы участков по расстоянию вдоль маршрута: [start, end). */
    val segmentRanges: List<ClosedFloatingPointRange<Double>> = run {
        var acc = 0.0
        segmentLines.map { l -> val len = Geo.length(l); (acc..acc + len).also { acc += len } }
    }

    val crossings: List<Crossing> = buildList {
        for (i in 1 until segments.size) {
            val a = segments[i - 1].side; val b = segments[i].side
            if (a != b && a != StreetSide.CENTER && b != StreetSide.CENTER) {
                add(Crossing(segments[i].axis.first(), segmentRanges[i].start, a, b, i))
            }
        }
    }

    fun segmentIndexAt(along: Double): Int =
        segmentRanges.indexOfFirst { along <= it.endInclusive }.let { if (it < 0) segments.lastIndex else it }

    companion object {
        fun of(route: Route) = RouteGeometry(route.segments)

        /**
         * Делит ось, полученную от роутера, на участки по смене улицы/стороны.
         * [sideByStreet] — выбранная пользователем сторона для каждой улицы.
         */
        fun splitByStreets(
            ways: List<Pair<String?, List<LatLon>>>,
            sideByStreet: Map<String?, StreetSide>,
            template: RouteSegment = RouteSegment(emptyList()),
        ): List<RouteSegment> = ways.map { (name, pts) ->
            template.copy(axis = pts, streetName = name, side = sideByStreet[name] ?: template.side)
        }
    }
}
