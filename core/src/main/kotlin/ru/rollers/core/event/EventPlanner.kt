package ru.rollers.core.event

import kotlinx.serialization.Serializable
import ru.rollers.core.geo.Geo
import ru.rollers.core.model.RideEvent
import ru.rollers.core.model.SkillLevel
import ru.rollers.core.route.RouteGeometry

@Serializable
data class StopEta(val name: String, val alongM: Double, val arriveAt: Long, val departAt: Long)

@Serializable
data class EventPlan(val stops: List<StopEta>, val finishAt: Long, val rideDurationMs: Long, val totalDurationMs: Long)

object EventPlanner {

    /** Рекомендуемый уровень по средней скорости и длине. */
    fun recommendLevel(avgSpeedKmh: Double, lengthKm: Double): SkillLevel {
        val bySpeed = SkillLevel.entries.last { avgSpeedKmh >= it.minAvgKmh }
        val byLength = when {
            lengthKm > 60 -> SkillLevel.PRO
            lengthKm > 35 -> SkillLevel.ADVANCED
            lengthKm > 15 -> SkillLevel.AMATEUR
            else -> SkillLevel.BEGINNER
        }
        return maxOf(bySpeed, byLength)
    }

    fun validate(event: RideEvent, geometry: RouteGeometry, now: Long): List<String> = buildList {
        if (event.title.isBlank()) add("Укажите название")
        if (event.avgSpeedKmh !in 5.0..45.0) add("Средняя скорость должна быть от 5 до 45 км/ч")
        if (event.startAt < now) add("Время старта в прошлом")
        if (event.maxParticipants != null && event.maxParticipants < 2) add("Минимум 2 участника")
        event.stops.forEach { s ->
            val d = Geo.project(s.point, geometry.line, geometry.cumulative).distanceFromLine
            if (d > 100) add("Остановка «${s.name}» в ${d.toInt()} м от маршрута")
        }
        val rec = recommendLevel(event.avgSpeedKmh, geometry.length / 1000)
        if (event.level < rec) add("Для такой скорости и длины рекомендуется уровень не ниже «${rec.title}»")
    }

    fun plan(event: RideEvent, geometry: RouteGeometry): EventPlan {
        val mps = event.avgSpeedKmh / 3.6
        var shift = 0L
        val stops = event.stops
            .map { it to Geo.project(it.point, geometry.line, geometry.cumulative).along }
            .sortedBy { it.second }
            .map { (s, along) ->
                val arrive = event.startAt + (along / mps * 1000).toLong() + shift
                shift += s.durationMin * 60_000L
                StopEta(s.name, along, arrive, arrive + s.durationMin * 60_000L)
            }
        val ride = (geometry.length / mps * 1000).toLong()
        return EventPlan(stops, event.startAt + ride + shift, ride, ride + shift)
    }

    fun canJoin(event: RideEvent, userLevel: SkillLevel, userId: Long): Result<Unit> = when {
        userId in event.participantIds -> Result.failure(IllegalStateException("Вы уже участвуете"))
        event.maxParticipants != null && event.participantIds.size >= event.maxParticipants ->
            Result.failure(IllegalStateException("Нет свободных мест"))
        // на один уровень ниже — можно (с предупреждением в UI), на два — нельзя
        userLevel.ordinal < event.level.ordinal - 1 ->
            Result.failure(IllegalStateException("Ваш уровень «${userLevel.title}» слишком низок для события уровня «${event.level.title}»"))
        else -> Result.success(Unit)
    }
}
