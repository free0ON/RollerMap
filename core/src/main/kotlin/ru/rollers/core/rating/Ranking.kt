package ru.rollers.core.rating

import kotlinx.serialization.Serializable
import ru.rollers.core.model.SkillLevel
import ru.rollers.core.track.ActivityStats
import kotlin.math.ln
import kotlin.math.roundToInt

@Serializable
data class UserStats(
    val userId: Long,
    val name: String = "",
    val maxSpeedKmh: Double = 0.0,
    /** Лучшая средняя скорость за заезд (учитываются заезды от 3 км). */
    val maxAvgSpeedKmh: Double = 0.0,
    val totalDistanceM: Double = 0.0,
    val activities: Int = 0,
)

@Serializable
enum class RankMetric { MAX_SPEED, MAX_AVG_SPEED, DISTANCE, OVERALL }

@Serializable
data class Rank(val title: String, val points: Int, val level: SkillLevel, val nextRankAt: Int?)

@Serializable
data class LeaderboardEntry(val position: Int, val stats: UserStats, val rank: Rank, val value: Double)

/**
 * Рейтинг участников. Каждая из трёх метрик даёт до ~1000 очков по своей шкале,
 * общий балл — взвешенная сумма. Звание определяется общим баллом.
 */
object Ranking {
    const val MIN_DISTANCE_FOR_AVG_M = 3000.0

    private val ranks = listOf(
        0 to "Новичок", 300 to "Катающийся", 700 to "Городской роллер", 1100 to "Марафонец",
        1500 to "Скоростник", 1900 to "Мастер", 2300 to "Легенда",
    )

    fun maxSpeedPoints(kmh: Double) = ((kmh - 10).coerceAtLeast(0.0) / 35 * 1000).coerceAtMost(1000.0)
    fun avgSpeedPoints(kmh: Double) = ((kmh - 8).coerceAtLeast(0.0) / 24 * 1000).coerceAtMost(1000.0)
    /** Логарифмическая шкала: 100 км ≈ 500, 10 000 км ≈ 1000. */
    fun distancePoints(m: Double): Double {
        val km = m / 1000
        return if (km <= 1) 0.0 else (ln(km) / ln(10_000.0) * 1000).coerceAtMost(1000.0)
    }

    fun points(s: UserStats): Int =
        (maxSpeedPoints(s.maxSpeedKmh) * 0.8 + avgSpeedPoints(s.maxAvgSpeedKmh) * 1.0 + distancePoints(s.totalDistanceM) * 0.7).roundToInt()

    fun rank(s: UserStats): Rank {
        val p = points(s)
        val idx = ranks.indexOfLast { p >= it.first }
        val level = SkillLevel.entries.last { s.maxAvgSpeedKmh >= it.minAvgKmh }
        return Rank(ranks[idx].second, p, level, ranks.getOrNull(idx + 1)?.first)
    }

    /** Обновить статистику после заезда. */
    fun apply(s: UserStats, a: ActivityStats): UserStats = s.copy(
        maxSpeedKmh = maxOf(s.maxSpeedKmh, a.maxSpeedKmh),
        maxAvgSpeedKmh = if (a.distanceM >= MIN_DISTANCE_FOR_AVG_M) maxOf(s.maxAvgSpeedKmh, a.avgMovingSpeedKmh) else s.maxAvgSpeedKmh,
        totalDistanceM = s.totalDistanceM + a.distanceM,
        activities = s.activities + 1,
    )

    fun leaderboard(all: List<UserStats>, metric: RankMetric, limit: Int = 100): List<LeaderboardEntry> {
        val value: (UserStats) -> Double = when (metric) {
            RankMetric.MAX_SPEED -> { s -> s.maxSpeedKmh }
            RankMetric.MAX_AVG_SPEED -> { s -> s.maxAvgSpeedKmh }
            RankMetric.DISTANCE -> { s -> s.totalDistanceM }
            RankMetric.OVERALL -> { s -> points(s).toDouble() }
        }
        return all.filter { it.activities > 0 }.sortedByDescending(value).take(limit)
            .mapIndexed { i, s -> LeaderboardEntry(i + 1, s, rank(s), value(s)) }
    }
}
