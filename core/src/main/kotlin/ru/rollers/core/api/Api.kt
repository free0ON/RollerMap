package ru.rollers.core.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.rollers.core.geo.LatLon
import ru.rollers.core.model.SkillLevel
import ru.rollers.core.rating.Rank
import ru.rollers.core.rating.UserStats
import ru.rollers.core.surface.SurfaceScore
import ru.rollers.core.track.ActivityStats

/** DTO обмена между клиентом и сервером. */

val ApiJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Serializable data class RegisterRequest(val name: String, val email: String, val password: String, val level: SkillLevel = SkillLevel.BEGINNER)
@Serializable data class LoginRequest(val email: String, val password: String)
@Serializable data class AuthResponse(val token: String, val userId: Long, val name: String)

@Serializable data class RouteSummary(val id: Long, val title: String, val authorId: Long, val lengthM: Double, val start: LatLon)

@Serializable data class SurfaceResponse(val routeId: Long, val segments: Map<Int, SurfaceScore>)

@Serializable data class ActivityResult(val activityId: Long, val stats: ActivityStats, val userStats: UserStats, val rank: Rank, val newRecords: List<String>)

@Serializable data class ActivitySummary(val id: Long, val routeId: Long?, val eventId: Long?, val createdAt: Long, val stats: ActivityStats)

@Serializable data class Profile(val userId: Long, val name: String, val level: SkillLevel, val stats: UserStats, val rank: Rank)

/** Сообщения live-канала события (WebSocket /events/{id}/live). */
@Serializable
data class LivePosition(
    val userId: Long,
    val name: String = "",
    val point: LatLon,
    val speedKmh: Double,
    val avgSpeedKmh: Double,
    val alongM: Double,
    val time: Long,
)

@Serializable data class ErrorResponse(val error: String)
