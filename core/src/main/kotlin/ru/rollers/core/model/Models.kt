package ru.rollers.core.model

import kotlinx.serialization.Serializable
import ru.rollers.core.geo.LatLon

/** Сторона улицы, по которой проходит путь относительно направления движения. */
@Serializable
enum class StreetSide { LEFT, RIGHT, CENTER }

/** Тип полотна, по которому проложен участок. */
@Serializable
enum class WayKind(val title: String, val defaultOffsetM: Double) {
    SIDEWALK("Тротуар", 7.0),
    CYCLEWAY("Велодорожка", 5.0),
    ROAD_SHOULDER("Край проезжей части", 4.0),
    PATH("Парковая дорожка", 0.0),
}

/**
 * Участок маршрута. [axis] — геометрия оси улицы (как её вернул роутер/OSM),
 * [side] и [offsetM] задают, где именно относительно оси едет роллер.
 */
@Serializable
data class RouteSegment(
    val axis: List<LatLon>,
    val side: StreetSide = StreetSide.CENTER,
    val kind: WayKind = WayKind.PATH,
    val offsetM: Double = kind.defaultOffsetM,
    /** OSM-тег lit=yes/no, если известен. */
    val lit: Boolean? = null,
    /** OSM-тег surface (asphalt, paving_stones, …), если известен. */
    val osmSurface: String? = null,
    val streetName: String? = null,
)

@Serializable
data class Route(
    val id: Long = 0,
    val authorId: Long = 0,
    val title: String,
    val description: String = "",
    val segments: List<RouteSegment>,
    val createdAt: Long = 0,
)

@Serializable
enum class SkillLevel(val title: String, val minAvgKmh: Double) {
    BEGINNER("Новичок", 0.0),
    AMATEUR("Любитель", 12.0),
    ADVANCED("Продвинутый", 18.0),
    PRO("Профи", 24.0),
}

@Serializable
data class Stop(val point: LatLon, val name: String, val durationMin: Int = 5)

@Serializable
data class RideEvent(
    val id: Long = 0,
    val routeId: Long,
    val organizerId: Long = 0,
    val title: String,
    val description: String = "",
    /** Время старта, epoch millis UTC. */
    val startAt: Long,
    val avgSpeedKmh: Double,
    val stops: List<Stop> = emptyList(),
    val level: SkillLevel = SkillLevel.AMATEUR,
    val maxParticipants: Int? = null,
    val participantIds: List<Long> = emptyList(),
)

/** GPS-отсчёт трека. */
@Serializable
data class TrackPoint(
    val point: LatLon,
    val time: Long,
    /** Скорость от GPS-приёмника, м/с (если есть). */
    val speed: Double? = null,
    val accuracy: Double? = null,
)

@Serializable
data class Activity(
    val id: Long = 0,
    val userId: Long = 0,
    val routeId: Long? = null,
    val eventId: Long? = null,
    val points: List<TrackPoint>,
)

@Serializable
data class Roadwork(
    val id: Long = 0,
    val reporterId: Long = 0,
    val description: String,
    /** Полигон зоны работ (либо центр + радиус). */
    val polygon: List<LatLon> = emptyList(),
    val center: LatLon? = null,
    val radiusM: Double = 30.0,
    val from: Long? = null,
    val until: Long? = null,
    val confirmations: Int = 0,
)

/** Экспертная оценка покрытия участка: 1 — непроезжаемо, 5 — идеальный асфальт. */
@Serializable
data class SurfaceRating(
    val routeId: Long,
    val segmentIndex: Int,
    val userId: Long = 0,
    val score: Int,
    val comment: String? = null,
    val createdAt: Long = 0,
)

@Serializable
data class User(val id: Long, val name: String, val level: SkillLevel = SkillLevel.BEGINNER)
