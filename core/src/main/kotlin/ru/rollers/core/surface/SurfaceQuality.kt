package ru.rollers.core.surface

import kotlinx.serialization.Serializable
import ru.rollers.core.model.SkillLevel
import kotlin.math.exp
import kotlin.math.ln

/** Оценка участника с весом его опыта (ранг влияет на вес голоса). */
data class WeightedRating(val score: Int, val raterLevel: SkillLevel, val ageDays: Double)

/** Скорость конкретного участника на участке и его типичная (средняя) скорость. */
data class SpeedSample(val segmentSpeedKmh: Double, val riderAvgKmh: Double)

@Serializable
data class SurfaceScore(
    /** Итоговая оценка 1..5. */
    val score: Double,
    /** Уверенность 0..1 — растёт с количеством данных. */
    val confidence: Double,
    val expertScore: Double?,
    val speedScore: Double?,
    val ratingsCount: Int,
    val ridesCount: Int,
) {
    val label: String get() = when {
        score >= 4.5 -> "Отличное"
        score >= 3.5 -> "Хорошее"
        score >= 2.5 -> "Среднее"
        score >= 1.5 -> "Плохое"
        else -> "Непроезжаемо"
    }
}

/**
 * Качество покрытия: экспертные оценки + косвенная оценка по скорости.
 *
 * - Экспертная часть — байесовское среднее с весами по уровню оценщика и затуханием по давности
 *   (дороги со временем меняются, свежие оценки важнее).
 * - Скоростная часть — отношение скорости на участке к личной средней скорости участника:
 *   на плохом покрытии все едут медленнее своего обычного темпа. Нормировка на личный темп
 *   убирает разницу между новичками и профи.
 */
object SurfaceQuality {
    private const val PRIOR_MEAN = 3.0
    private const val PRIOR_WEIGHT = 2.0
    private const val HALF_LIFE_DAYS = 180.0

    fun levelWeight(level: SkillLevel) = when (level) {
        SkillLevel.BEGINNER -> 0.7
        SkillLevel.AMATEUR -> 1.0
        SkillLevel.ADVANCED -> 1.3
        SkillLevel.PRO -> 1.6
    }

    fun expertScore(ratings: List<WeightedRating>): Pair<Double, Double>? {
        if (ratings.isEmpty()) return null
        var sw = 0.0; var s = 0.0
        for (r in ratings) {
            val w = levelWeight(r.raterLevel) * exp(-ln(2.0) * r.ageDays / HALF_LIFE_DAYS)
            sw += w; s += w * r.score.coerceIn(1, 5)
        }
        val mean = (s + PRIOR_MEAN * PRIOR_WEIGHT) / (sw + PRIOR_WEIGHT)
        return mean to sw
    }

    /**
     * Перевод относительной скорости в шкалу 1..5:
     * ratio ≥ 1.05 → 5, ratio 0.95 → 4, 0.8 → 3, 0.65 → 2, ≤ 0.5 → 1.
     */
    fun speedScore(samples: List<SpeedSample>): Double? {
        val ratios = samples.filter { it.riderAvgKmh > 3 && it.segmentSpeedKmh > 0 }
            .map { it.segmentSpeedKmh / it.riderAvgKmh }
        if (ratios.isEmpty()) return null
        val median = ratios.sorted().let { if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }
        return (1 + (median - 0.5) / 0.1375).coerceIn(1.0, 5.0)
    }

    fun combine(ratings: List<WeightedRating>, speeds: List<SpeedSample>): SurfaceScore {
        val expert = expertScore(ratings)
        val speed = speedScore(speeds)
        // Вес экспертной оценки — сумма весов, скоростной — 0.3 за каждый заезд, но не более 5
        val we = expert?.second ?: 0.0
        val ws = if (speed != null) minOf(5.0, speeds.size * 0.3) else 0.0
        val score = when {
            expert == null && speed == null -> PRIOR_MEAN
            expert == null -> speed!!
            speed == null -> expert.first
            else -> (expert.first * we + speed * ws) / (we + ws)
        }
        val confidence = 1 - exp(-(we + ws) / 4)
        return SurfaceScore(score, confidence, expert?.first, speed, ratings.size, speeds.size)
    }

    /** Предварительная оценка по OSM-тегу surface, пока нет данных от участников. */
    fun fromOsmSurface(surface: String?): Double? = when (surface) {
        "asphalt", "concrete" -> 4.5
        "paved", "concrete:plates" -> 3.8
        "paving_stones" -> 3.0
        "sett", "cobblestone", "unhewn_cobblestone" -> 1.2
        "compacted", "fine_gravel" -> 1.8
        "gravel", "dirt", "ground", "grass", "sand", "unpaved" -> 1.0
        else -> null
    }
}
