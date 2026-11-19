package ru.rollers.core

import ru.rollers.core.conditions.*
import ru.rollers.core.event.EventPlanner
import ru.rollers.core.geo.Geo
import ru.rollers.core.geo.LatLon
import ru.rollers.core.model.*
import ru.rollers.core.nav.ManeuverType
import ru.rollers.core.nav.Navigator
import ru.rollers.core.osm.OpenMeteo
import ru.rollers.core.osm.OsmWay
import ru.rollers.core.osm.OsmWays
import ru.rollers.core.rating.RankMetric
import ru.rollers.core.rating.Ranking
import ru.rollers.core.rating.UserStats
import ru.rollers.core.route.RouteGeometry
import ru.rollers.core.surface.SpeedSample
import ru.rollers.core.surface.SurfaceQuality
import ru.rollers.core.surface.WeightedRating
import ru.rollers.core.track.TrackStats
import java.time.ZonedDateTime
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.test.*

// Москва, Тверская: условная улица с севера на юг и поворот на восток
private val A = LatLon(55.7600, 37.6100)
private val B = LatLon(55.7500, 37.6100)
private val C = LatLon(55.7500, 37.6260)

class GeoTest {
    @Test fun distanceAndBearing() {
        assertEquals(1112.0, Geo.distance(A, B), 2.0)
        assertEquals(180.0, Geo.bearing(A, B), 0.1)
        assertEquals(90.0, Geo.bearing(B, C), 0.2)
    }

    @Test fun offsetRightOfSouthboundIsWest() {
        val off = Geo.offset(listOf(A, B), 8.0)
        // при движении на юг правая сторона — запад
        assertTrue(off[0].lon < A.lon)
        assertEquals(8.0, Geo.distance(A, off[0]), 0.2)
        val left = Geo.offset(listOf(A, B), -8.0)
        assertTrue(left[0].lon > A.lon)
    }

    @Test fun offsetCornerKeepsDistance() {
        val off = Geo.offset(listOf(A, B, C), 6.0)
        // внешний угол поворота: точка излома на расстоянии 6·√2 от вершины, участки — в 6 м от осей
        assertEquals(6.0 * Math.sqrt(2.0), Geo.distance(B, off[1]), 0.3)
        assertEquals(6.0, Geo.project(Geo.pointAt(off, 300.0), listOf(A, B)).distanceFromLine, 0.3)
        assertEquals(6.0, Geo.project(Geo.pointAt(off, Geo.length(off) - 300.0), listOf(B, C)).distanceFromLine, 0.3)
    }

    @Test fun projection() {
        val mid = LatLon(55.7550, 37.6101)
        val pr = Geo.project(mid, listOf(A, B, C))
        assertEquals(0, pr.segmentIndex)
        assertEquals(556.0, pr.along, 3.0)
        assertTrue(pr.distanceFromLine < 10)
    }
}

class RouteGeometryTest {
    @Test fun crossingDetected() {
        val g = RouteGeometry(listOf(
            RouteSegment(listOf(A, B), StreetSide.RIGHT, WayKind.SIDEWALK),
            RouteSegment(listOf(B, C), StreetSide.LEFT, WayKind.SIDEWALK),
        ))
        assertEquals(1, g.crossings.size)
        assertEquals(StreetSide.RIGHT, g.crossings[0].from)
        assertEquals(0, g.segmentIndexAt(100.0))
        assertEquals(1, g.segmentIndexAt(g.length - 10))
    }
}

class SurfaceTest {
    @Test fun badRatingsAndSlowSpeedsGiveLowScore() {
        val r = List(5) { WeightedRating(1, SkillLevel.ADVANCED, 10.0) }
        val s = List(10) { SpeedSample(8.0, 20.0) }
        val score = SurfaceQuality.combine(r, s)
        assertTrue(score.score < 2.0, "score=${score.score}")
        assertTrue(score.confidence > 0.8)
    }

    @Test fun noDataIsNeutral() {
        val s = SurfaceQuality.combine(emptyList(), emptyList())
        assertEquals(3.0, s.score); assertEquals(0.0, s.confidence)
    }

    @Test fun proRatingWeighsMore() {
        val pro = SurfaceQuality.expertScore(listOf(WeightedRating(5, SkillLevel.PRO, 0.0), WeightedRating(1, SkillLevel.BEGINNER, 0.0)))!!
        assertTrue(pro.first > 3.0)
    }
}

class ConditionsTest {
    @Test fun sunriseMoscowJune() {
        val day = ZonedDateTime.of(2026, 6, 21, 0, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
        val t = SunCalc.dayTimes(day, 55.75, 37.62)
        val sunriseMsk = (t.sunrise!! - day) / 60000 + 180 // UTC+3
        // фактический восход в Москве 21 июня ≈ 3:44 МСК
        assertTrue(abs(sunriseMsk - (3 * 60 + 44)) <= 4, "sunrise=$sunriseMsk")
    }

    @Test fun headwind() {
        // едем на север (0°), ветер с севера — в лицо
        val (h, c) = RouteConditions.headAndCross(0.0, 0.0, 5.0)
        assertEquals(5.0, h, 1e-6); assertEquals(0.0, c, 1e-6)
        assertEquals(-5.0, RouteConditions.headAndCross(180.0, 0.0, 5.0).first, 1e-6)
    }

    @Test fun nightRainAndRoadworks() {
        val g = RouteGeometry(listOf(RouteSegment(listOf(A, B), lit = false), RouteSegment(listOf(B, C), lit = true)))
        val night = ZonedDateTime.of(2026, 12, 1, 20, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli() // 23:00 МСК
        val weather = (0..3).map { WeatherHour(night + it * 3_600_000L, 5.0, 0.6, 80, 7.0, 9.0, 180.0) }
        val rw = Roadwork(id = 7, description = "Замена асфальта", center = LatLon(55.7500, 37.6180), radiusM = 40.0)
        val r = RouteConditions.assess(g, night, 15.0, weather, roadworks = listOf(rw))
        assertTrue(r.issues.any { it.severity == Severity.BLOCKER && "осадки" in it.message })
        assertEquals(0.53, r.darkUnlitShare, 0.03)
        assertEquals(listOf(7L), r.segments[1].roadworkIds)
        assertTrue(r.segments[0].headwind > 6) // едем на юг, ветер с юга
        assertTrue(r.suitability < 30)
    }
}

class TrackTest {
    private fun ride(speedMps: Double, seconds: Int, start: Long = 0L): List<TrackPoint> {
        val line = listOf(A, B, C); val cum = Geo.cumulative(line)
        return (0..seconds).map { s -> TrackPoint(Geo.pointAt(line, s * speedMps, cum), start + s * 1000L, accuracy = 5.0) }
    }

    @Test fun statsAndOutlierRemoval() {
        val pts = ride(5.0, 300).toMutableList()
        pts[100] = pts[100].copy(point = LatLon(55.80, 37.70)) // выброс GPS
        val st = TrackStats.compute(pts)
        assertEquals(18.0, st.avgMovingSpeedKmh, 0.6)
        assertTrue(st.maxSpeedKmh < 20)
        assertEquals(1500.0, st.distanceM, 20.0)
    }

    @Test fun segmentSpeeds() {
        val g = RouteGeometry(listOf(RouteSegment(listOf(A, B)), RouteSegment(listOf(B, C))))
        val st = TrackStats.compute(ride(5.0, 400), g)
        assertEquals(18.0, st.segmentSpeedsKmh[0]!!, 1.0)
        assertTrue(1 in st.segmentSpeedsKmh)
    }
}

class EventTest {
    @Test fun planWithStops() {
        val g = RouteGeometry(listOf(RouteSegment(listOf(A, B, C))))
        val ev = RideEvent(routeId = 1, title = "Вечерний круг", startAt = 1_000_000L, avgSpeedKmh = 16.0,
            stops = listOf(Stop(B, "Вода", 10)), level = SkillLevel.AMATEUR)
        val plan = EventPlanner.plan(ev, g)
        assertEquals(1, plan.stops.size)
        val toB = 1112.0 / (16.0 / 3.6) * 1000
        assertEquals(ev.startAt + toB, plan.stops[0].arriveAt.toDouble(), 2000.0)
        assertEquals(plan.rideDurationMs + 600_000, plan.totalDurationMs)
        assertEquals(emptyList(), EventPlanner.validate(ev, g, 0))
        assertTrue(EventPlanner.validate(ev.copy(avgSpeedKmh = 26.0), g, 0).any { "Профи" in it })
    }

    @Test fun joinRules() {
        val ev = RideEvent(routeId = 1, title = "x", startAt = 0, avgSpeedKmh = 25.0, level = SkillLevel.PRO, maxParticipants = 2, participantIds = listOf(1))
        assertTrue(EventPlanner.canJoin(ev, SkillLevel.BEGINNER, 2).isFailure)
        assertTrue(EventPlanner.canJoin(ev, SkillLevel.ADVANCED, 2).isSuccess)
        assertTrue(EventPlanner.canJoin(ev, SkillLevel.PRO, 1).isFailure)
    }
}

class NavigatorTest {
    @Test fun turnAndFinishAnnounced() {
        val g = RouteGeometry(listOf(RouteSegment(listOf(A, B), streetName = "Тверская"), RouteSegment(listOf(B, C), streetName = "Охотный ряд")))
        val nav = Navigator(g, stops = listOf("Площадь" to LatLon(55.7500, 37.6200)))
        assertEquals(ManeuverType.TURN_LEFT, nav.maneuvers.first().type) // юг → восток = налево
        val line = g.line; val cum = g.cumulative
        val said = mutableListOf<String>()
        var t = 0L; var d = 0.0
        while (d <= g.length + 5) {
            said += nav.update(Geo.pointAt(line, d, cum), t).second
            d += 5.0; t += 1000
        }
        assertTrue(said.any { it.startsWith("Через") && "налево" in it }, said.toString())
        assertTrue(said.any { it == "Поверните налево на Охотный ряд" }, said.toString())
        assertTrue(said.any { "«Площадь»" in it })
        assertTrue(said.any { it.startsWith("Финиш!") })
        assertTrue(said.any { it.startsWith("Пройдено") })
    }

    @Test fun offRoute() {
        val g = RouteGeometry(listOf(RouteSegment(listOf(A, B))))
        val nav = Navigator(g)
        nav.update(A, 0)
        val far = LatLon(55.7550, 37.6200)
        nav.update(far, 1000)
        val (st, say) = nav.update(far, 8000)
        assertTrue(st.offRoute); assertTrue(say.any { "сошли" in it })
    }
}

class RankingTest {
    @Test fun rankAndLeaderboard() {
        var s = UserStats(1, "Аня")
        s = Ranking.apply(s, TrackStats.compute(emptyList()).copy(distanceM = 25_000.0, maxSpeedKmh = 32.0, avgMovingSpeedKmh = 21.0))
        val r = Ranking.rank(s)
        assertEquals(SkillLevel.ADVANCED, r.level)
        assertTrue(r.points > 700)
        val board = Ranking.leaderboard(listOf(s, UserStats(2, "Боря", 40.0, 15.0, 1000.0, 1)), RankMetric.MAX_SPEED)
        assertEquals(2L, board[0].stats.userId)
    }

    @Test fun shortRideDoesNotCountForAvg() {
        val s = Ranking.apply(UserStats(1), TrackStats.compute(emptyList()).copy(distanceM = 1000.0, avgMovingSpeedKmh = 30.0))
        assertEquals(0.0, s.maxAvgSpeedKmh)
    }
}

class OsmTest {
    @Test fun sideFlipsWhenReversed() {
        // линия нарисована с юга на север, тротуар только справа (восток)
        val way = OsmWay(1, mapOf("highway" to "primary", "sidewalk" to "right", "lit" to "yes", "name" to "Тверская"), listOf(B, A))
        val seg = OsmWays.suggest(listOf(A, B), way) // едем на юг → тротуар слева
        assertEquals(StreetSide.LEFT, seg.side)
        assertEquals(WayKind.SIDEWALK, seg.kind)
        assertEquals(true, seg.lit)
    }

    @Test fun parseOpenMeteo() {
        val json = """{"hourly":{"time":["2026-11-19T10:00","2026-11-19T11:00"],"temperature_2m":[1.5,2.0],
            "precipitation":[0.0,0.4],"precipitation_probability":[10,55],"wind_speed_10m":[3.2,4.1],
            "wind_gusts_10m":[6.0,8.0],"wind_direction_10m":[270,280]}}"""
        val w = OpenMeteo.parse(json)
        assertEquals(2, w.size); assertEquals(55, w[1].precipitationProbability); assertEquals(280.0, w[1].windFromDeg)
    }
}
