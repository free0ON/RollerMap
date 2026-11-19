package ru.rollers.server

import io.ktor.client.HttpClient
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.coroutines.withTimeout
import io.ktor.websocket.close
import ru.rollers.core.api.*
import ru.rollers.core.geo.Geo
import ru.rollers.core.geo.LatLon
import ru.rollers.core.model.*
import ru.rollers.core.rating.LeaderboardEntry
import ru.rollers.core.route.RouteGeometry
import java.util.UUID
import kotlin.test.*

class ServerTest {
    private val A = LatLon(55.7600, 37.6100)
    private val B = LatLon(55.7500, 37.6100)
    private val C = LatLon(55.7500, 37.6260)
    private val now = 1_800_000_000_000L

    private fun ApplicationTestBuilder.setup(): HttpClient {
        val db = DatabaseFactory.init("jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1")
        application { module(Service(db, JwtConfig("test"), clock = { now })) }
        return createClient {
            install(ContentNegotiation) { json(ApiJson) }
            install(WebSockets) { contentConverter = KotlinxWebsocketSerializationConverter(ApiJson) }
        }
    }

    private suspend fun HttpClient.register(name: String, level: SkillLevel = SkillLevel.AMATEUR): AuthResponse =
        post("/auth/register") { contentType(ContentType.Application.Json); setBody(RegisterRequest(name, "$name@x.ru", "secret123", level)) }.body()

    private fun HttpRequestBuilder.auth(a: AuthResponse) { header(HttpHeaders.Authorization, "Bearer ${a.token}"); contentType(ContentType.Application.Json) }

    @Test
    fun fullFlow() = testApplication {
        val client = setup()
        val anna = client.register("anna", SkillLevel.ADVANCED)
        val boris = client.register("boris")

        // повторная регистрация и неверный пароль
        assertEquals(HttpStatusCode.Conflict, client.post("/auth/register") { contentType(ContentType.Application.Json); setBody(RegisterRequest("anna", "anna@x.ru", "secret123")) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/auth/login") { contentType(ContentType.Application.Json); setBody(LoginRequest("anna@x.ru", "wrong")) }.status)
        assertEquals(anna.userId, client.post("/auth/login") { contentType(ContentType.Application.Json); setBody(LoginRequest("anna@x.ru", "secret123")) }.body<AuthResponse>().userId)

        // маршрут по правой стороне, затем по левой
        val route: Route = client.post("/routes") {
            auth(anna)
            setBody(Route(title = "Центр", segments = listOf(
                RouteSegment(listOf(A, B), StreetSide.RIGHT, WayKind.SIDEWALK, streetName = "Тверская"),
                RouteSegment(listOf(B, C), StreetSide.LEFT, WayKind.CYCLEWAY, osmSurface = "paving_stones"),
            )))
        }.body()
        assertTrue(route.id > 0)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/routes") { contentType(ContentType.Application.Json); setBody(route) }.status)
        assertEquals(1, client.get("/routes?bbox=55.7,37.5,55.8,37.7").body<List<RouteSummary>>().size)
        assertEquals(0, client.get("/routes?bbox=59.0,30.0,60.0,31.0").body<List<RouteSummary>>().size)

        // оценки покрытия
        client.post("/routes/${route.id}/surface") { auth(anna); setBody(SurfaceRating(route.id, 0, score = 1, comment = "Ямы")) }
        client.post("/routes/${route.id}/surface") { auth(boris); setBody(SurfaceRating(route.id, 0, score = 2)) }
        assertEquals(HttpStatusCode.BadRequest, client.post("/routes/${route.id}/surface") { auth(boris); setBody(SurfaceRating(route.id, 9, score = 2)) }.status)
        val surface = client.get("/routes/${route.id}/surface").body<SurfaceResponse>()
        assertTrue(surface.segments[0]!!.score < 2.5)
        assertEquals(3.0, surface.segments[1]!!.score) // из OSM: paving_stones

        // ремонт
        val rw: Roadwork = client.post("/roadworks") { auth(boris); setBody(Roadwork(description = "Перекладка плитки", center = LatLon(55.75, 37.618))) }.body()
        client.post("/roadworks/${rw.id}/confirm") { auth(anna) }
        val rws = client.get("/roadworks?bbox=55.74,37.6,55.76,37.63").body<List<Roadwork>>()
        assertEquals(1, rws.single().confirmations)

        // событие уровня PRO — boris (AMATEUR) не может присоединиться
        val bad = client.post("/events") { auth(anna); setBody(RideEvent(routeId = route.id, title = "x", startAt = now - 1, avgSpeedKmh = 16.0)) }
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        val pro: RideEvent = client.post("/events") {
            auth(anna); setBody(RideEvent(routeId = route.id, title = "Быстрый круг", startAt = now + 3600_000, avgSpeedKmh = 26.0, level = SkillLevel.PRO))
        }.body()
        assertEquals(HttpStatusCode.Conflict, client.post("/events/${pro.id}/join") { auth(boris) }.status)

        val ev: RideEvent = client.post("/events") {
            auth(anna); setBody(RideEvent(routeId = route.id, title = "Вечерняя покатушка", startAt = now + 3600_000, avgSpeedKmh = 16.0,
                stops = listOf(Stop(B, "Вода", 5)), level = SkillLevel.AMATEUR))
        }.body()
        val joined: RideEvent = client.post("/events/${ev.id}/join") { auth(boris) }.body()
        assertEquals(setOf(anna.userId, boris.userId), joined.participantIds.toSet())
        assertEquals(2, client.get("/events").body<List<RideEvent>>().size)
        val plan = client.get("/events/${ev.id}/plan").body<ru.rollers.core.event.EventPlan>()
        assertEquals("Вода", plan.stops.single().name)

        // live: boris получает позицию anna
        client.webSocket("/events/${ev.id}/live?token=${boris.token}") {
            val annaWs = client.webSocketSession("/events/${ev.id}/live?token=${anna.token}")
            annaWs.sendSerialized(LivePosition(0, point = A, speedKmh = 19.5, avgSpeedKmh = 17.0, alongM = 120.0, time = now))
            val got = withTimeout(5000) { receiveDeserialized<LivePosition>() }
            assertEquals(anna.userId, got.userId); assertEquals("anna", got.name); assertEquals(19.5, got.speedKmh)
            annaWs.close()
        }

        // загрузка заезда → статистика, скорости по участкам, рейтинг
        val g = RouteGeometry.of(route)
        // туда и обратно (~4.2 км, т.к. рекорд средней скорости учитывается от 3 км), 6 м/с = 21.6 км/ч
        val n = g.length.toInt() / 6
        val there = (0..n).map { s -> Geo.pointAt(g.line, s * 6.0, g.cumulative) }
        val track = (there + there.reversed().drop(1)).mapIndexed { i, p -> TrackPoint(p, now + i * 1000L, accuracy = 5.0) }
        val res: ActivityResult = client.post("/activities") { auth(boris); setBody(Activity(eventId = ev.id, points = track)) }.body()
        assertEquals(21.6, res.stats.avgMovingSpeedKmh, 0.6)
        assertEquals(setOf(0, 1), res.stats.segmentSpeedsKmh.keys)
        assertEquals(1, res.userStats.activities)
        val slow = track.mapIndexed { i, p -> p.copy(time = now + i * 2000L) } // 9 км/ч
        client.post("/activities") { auth(anna); setBody(Activity(routeId = route.id, points = slow)) }

        val board = client.get("/leaderboard?metric=max_avg_speed").body<List<LeaderboardEntry>>()
        assertEquals(listOf(boris.userId, anna.userId), board.map { it.stats.userId })
        val me = client.get("/me") { auth(boris) }.body<Profile>()
        assertEquals(SkillLevel.ADVANCED, me.level) // уровень повышен по средней скорости
        assertEquals(1, client.get("/me/activities") { auth(boris) }.body<List<ActivitySummary>>().size)
    }

    @Test
    fun liveRequiresParticipation() = testApplication {
        val client = setup()
        val a = client.register("a"); val b = client.register("b")
        val route: Route = client.post("/routes") { auth(a); setBody(Route(title = "r", segments = listOf(RouteSegment(listOf(A, B))))) }.body()
        val ev: RideEvent = client.post("/events") { auth(a); setBody(RideEvent(routeId = route.id, title = "e", startAt = now + 1000, avgSpeedKmh = 14.0)) }.body()
        val s = client.webSocketSession("/events/${ev.id}/live?token=${b.token}")
        val reason = withTimeout(5000) { s.closeReason.await() }
        assertEquals(io.ktor.websocket.CloseReason.Codes.VIOLATED_POLICY.code, reason?.code)
    }
}
