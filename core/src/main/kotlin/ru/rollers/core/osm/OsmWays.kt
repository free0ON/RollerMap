package ru.rollers.core.osm

import kotlinx.serialization.json.*
import ru.rollers.core.conditions.WeatherHour
import ru.rollers.core.geo.Geo
import ru.rollers.core.geo.LatLon
import ru.rollers.core.model.RouteSegment
import ru.rollers.core.model.StreetSide
import ru.rollers.core.model.WayKind
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Линия OSM с тегами (из ответа Overpass с `out geom`). */
data class OsmWay(val id: Long, val tags: Map<String, String>, val geometry: List<LatLon>)

/** Что известно о сторонах улицы из тегов OSM. */
data class SideInfo(
    val sidewalkLeft: Boolean, val sidewalkRight: Boolean,
    val cyclewayLeft: Boolean, val cyclewayRight: Boolean,
    val lit: Boolean?, val surface: String?, val name: String?,
    /** Улица сама по себе пешеходная/велодорожка — смещение не нужно. */
    val isPath: Boolean,
)

object OsmWays {
    private val pathHighways = setOf("footway", "cycleway", "path", "pedestrian", "track", "living_street")

    fun sides(tags: Map<String, String>): SideInfo {
        fun yes(v: String?) = v != null && v != "no" && v != "none"
        val sw = tags["sidewalk"] ?: tags["sidewalk:both"]?.let { if (yes(it)) "both" else "no" }
        val swL = sw == "both" || sw == "left" || yes(tags["sidewalk:left"])
        val swR = sw == "both" || sw == "right" || yes(tags["sidewalk:right"])
        val cw = tags["cycleway"] ?: tags["cycleway:both"]
        val cwL = cw in setOf("lane", "track") || tags["cycleway:left"] in setOf("lane", "track")
        val cwR = cw in setOf("lane", "track") || tags["cycleway:right"] in setOf("lane", "track")
        return SideInfo(swL, swR, cwL, cwR, tags["lit"]?.let { it == "yes" || it == "24/7" || it == "automatic" },
            tags["surface"], tags["name"], tags["highway"] in pathHighways)
    }

    /**
     * Стороны в OSM заданы относительно направления рисования линии.
     * Если маршрут идёт против направления линии — стороны меняются местами.
     */
    fun sidesAlongRoute(way: OsmWay, routeBearing: Double): SideInfo {
        val s = sides(way.tags)
        if (way.geometry.size < 2) return s
        val wayBearing = Geo.bearing(way.geometry.first(), way.geometry.last())
        val reversed = kotlin.math.abs(Geo.angleDiff(wayBearing, routeBearing)) > 90
        return if (!reversed) s else s.copy(
            sidewalkLeft = s.sidewalkRight, sidewalkRight = s.sidewalkLeft,
            cyclewayLeft = s.cyclewayRight, cyclewayRight = s.cyclewayLeft,
        )
    }

    /**
     * Автоматический выбор стороны и типа полотна для участка.
     * Приоритет: велодорожка > тротуар; при равенстве — правая сторона (по ходу движения в РФ).
     */
    fun suggest(axis: List<LatLon>, way: OsmWay?): RouteSegment {
        if (way == null || axis.size < 2) return RouteSegment(axis)
        val s = sidesAlongRoute(way, Geo.bearing(axis.first(), axis.last()))
        val (side, kind) = when {
            s.isPath -> StreetSide.CENTER to WayKind.PATH
            s.cyclewayRight -> StreetSide.RIGHT to WayKind.CYCLEWAY
            s.cyclewayLeft -> StreetSide.LEFT to WayKind.CYCLEWAY
            s.sidewalkRight -> StreetSide.RIGHT to WayKind.SIDEWALK
            s.sidewalkLeft -> StreetSide.LEFT to WayKind.SIDEWALK
            else -> StreetSide.RIGHT to WayKind.ROAD_SHOULDER
        }
        val lanes = way.tags["lanes"]?.toIntOrNull()
        val offset = if (kind == WayKind.PATH) 0.0
        else (lanes?.let { it * 3.5 / 2 + if (kind == WayKind.SIDEWALK) 2.5 else 1.0 } ?: kind.defaultOffsetM)
        return RouteSegment(axis, side, kind, offset, s.lit, s.surface, s.name)
    }

    /** Overpass QL: линии с нужными тегами вдоль полосы маршрута. */
    fun overpassQuery(bboxSouth: Double, bboxWest: Double, bboxNorth: Double, bboxEast: Double): String =
        """[out:json][timeout:25];way["highway"]($bboxSouth,$bboxWest,$bboxNorth,$bboxEast);out tags geom;"""

    fun parseOverpass(json: String): List<OsmWay> {
        val root = Json.parseToJsonElement(json).jsonObject
        return root["elements"]?.jsonArray.orEmpty().mapNotNull { el ->
            val o = el.jsonObject
            if (o["type"]?.jsonPrimitive?.content != "way") return@mapNotNull null
            val tags = o["tags"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
            val geom = o["geometry"]?.jsonArray.orEmpty().map {
                LatLon(it.jsonObject["lat"]!!.jsonPrimitive.double, it.jsonObject["lon"]!!.jsonPrimitive.double)
            }
            OsmWay(o["id"]!!.jsonPrimitive.long, tags, geom)
        }
    }

    /** Ближайшая линия OSM к середине участка (с допуском по направлению). */
    fun matchWay(axis: List<LatLon>, ways: List<OsmWay>, maxDistM: Double = 20.0): OsmWay? {
        if (axis.size < 2) return null
        val mid = Geo.pointAt(axis, Geo.length(axis) / 2)
        val br = Geo.bearing(axis.first(), axis.last())
        return ways.filter { it.geometry.size >= 2 }
            .map { it to Geo.project(mid, it.geometry) }
            .filter { (w, pr) ->
                if (pr.distanceFromLine > maxDistM) return@filter false
                val wb = Geo.bearing(w.geometry[pr.segmentIndex], w.geometry[pr.segmentIndex + 1])
                val d = kotlin.math.abs(Geo.angleDiff(br, wb))
                d < 35 || d > 145
            }
            .minByOrNull { it.second.distanceFromLine }?.first
    }

    /**
     * Ответ OSRM (/route/v1/foot/...?steps=true&geometries=geojson) → оси участков по улицам.
     */
    fun parseOsrmSteps(json: String): List<Pair<String?, List<LatLon>>> {
        val root = Json.parseToJsonElement(json).jsonObject
        val route = root["routes"]?.jsonArray?.firstOrNull()?.jsonObject ?: return emptyList()
        return route["legs"]!!.jsonArray.flatMap { leg ->
            leg.jsonObject["steps"]!!.jsonArray.mapNotNull { st ->
                val o = st.jsonObject
                val coords = o["geometry"]!!.jsonObject["coordinates"]!!.jsonArray.map {
                    val a = it.jsonArray; LatLon(a[1].jsonPrimitive.double, a[0].jsonPrimitive.double)
                }
                if (coords.size < 2) null else o["name"]?.jsonPrimitive?.content?.ifBlank { null } to coords
            }
        }
    }
}

/** Разбор ответа Open-Meteo (hourly, windspeed_unit=ms, timezone=UTC). */
object OpenMeteo {
    fun url(lat: Double, lon: Double, days: Int = 3) =
        "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&hourly=temperature_2m,precipitation,precipitation_probability,wind_speed_10m,wind_gusts_10m,wind_direction_10m" +
            "&wind_speed_unit=ms&timezone=UTC&forecast_days=$days&past_days=1"

    fun parse(json: String): List<WeatherHour> {
        val h = Json.parseToJsonElement(json).jsonObject["hourly"]!!.jsonObject
        fun arr(k: String) = h[k]?.jsonArray
        val times = arr("time")!!.map { LocalDateTime.parse(it.jsonPrimitive.content).toInstant(ZoneOffset.UTC).toEpochMilli() }
        fun d(k: String, i: Int) = arr(k)?.getOrNull(i)?.jsonPrimitive?.doubleOrNull ?: 0.0
        return times.mapIndexed { i, t ->
            WeatherHour(
                time = t, temperatureC = d("temperature_2m", i), precipitationMm = d("precipitation", i),
                precipitationProbability = d("precipitation_probability", i).toInt(),
                windSpeed = d("wind_speed_10m", i), windGusts = d("wind_gusts_10m", i), windFromDeg = d("wind_direction_10m", i),
            )
        }
    }
}
