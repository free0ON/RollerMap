package ru.rollers.app.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.*
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.serialization.kotlinx.json.json
import ru.rollers.app.BuildConfig
import ru.rollers.core.api.*
import ru.rollers.core.conditions.WeatherHour
import ru.rollers.core.event.EventPlan
import ru.rollers.core.geo.BBox
import ru.rollers.core.geo.LatLon
import ru.rollers.core.model.*
import ru.rollers.core.osm.OpenMeteo
import ru.rollers.core.osm.OsmWay
import ru.rollers.core.osm.OsmWays
import ru.rollers.core.rating.LeaderboardEntry
import ru.rollers.core.rating.RankMetric

/** Клиент собственного сервера + внешних открытых сервисов (OSRM, Overpass, Open-Meteo). */
class Api(private val session: Session) {
    private val base = BuildConfig.API_URL

    val http = HttpClient(OkHttp) {
        expectSuccess = true
        install(ContentNegotiation) { json(ApiJson) }
        install(WebSockets) { contentConverter = KotlinxWebsocketSerializationConverter(ApiJson) }
        install(HttpTimeout) { requestTimeoutMillis = 30_000 }
    }

    private fun HttpRequestBuilder.authed() {
        session.token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        contentType(ContentType.Application.Json)
    }

    // --- Аккаунт
    suspend fun register(r: RegisterRequest): AuthResponse = http.post("$base/auth/register") { authed(); setBody(r) }.body()
    suspend fun login(r: LoginRequest): AuthResponse = http.post("$base/auth/login") { authed(); setBody(r) }.body()
    suspend fun me(): Profile = http.get("$base/me") { authed() }.body()
    suspend fun myActivities(): List<ActivitySummary> = http.get("$base/me/activities") { authed() }.body()

    // --- Маршруты
    suspend fun routes(bbox: BBox?): List<RouteSummary> =
        http.get("$base/routes") { bbox?.let { parameter("bbox", "${it.south},${it.west},${it.north},${it.east}") } }.body()
    suspend fun route(id: Long): Route = http.get("$base/routes/$id").body()
    suspend fun saveRoute(r: Route): Route = http.post("$base/routes") { authed(); setBody(r) }.body()
    suspend fun surface(routeId: Long): SurfaceResponse = http.get("$base/routes/$routeId/surface").body()
    suspend fun rateSurface(r: SurfaceRating) { http.post("$base/routes/${r.routeId}/surface") { authed(); setBody(r) } }

    // --- Ремонты
    suspend fun roadworks(b: BBox): List<Roadwork> =
        http.get("$base/roadworks") { parameter("bbox", "${b.south},${b.west},${b.north},${b.east}") }.body()
    suspend fun reportRoadwork(r: Roadwork): Roadwork = http.post("$base/roadworks") { authed(); setBody(r) }.body()
    suspend fun confirmRoadwork(id: Long) { http.post("$base/roadworks/$id/confirm") { authed() } }

    // --- События
    suspend fun events(): List<RideEvent> = http.get("$base/events").body()
    suspend fun event(id: Long): RideEvent = http.get("$base/events/$id").body()
    suspend fun eventPlan(id: Long): EventPlan = http.get("$base/events/$id/plan").body()
    suspend fun createEvent(e: RideEvent): RideEvent = http.post("$base/events") { authed(); setBody(e) }.body()
    suspend fun join(id: Long): RideEvent = http.post("$base/events/$id/join") { authed() }.body()
    suspend fun leave(id: Long) { http.delete("$base/events/$id/join") { authed() } }
    suspend fun liveSession(eventId: Long) = http.webSocketSession {
        url("${base.replaceFirst("http", "ws")}/events/$eventId/live")
        parameter("token", session.token)
    }

    // --- Заезды и рейтинг
    suspend fun upload(a: Activity): ActivityResult = http.post("$base/activities") { authed(); setBody(a) }.body()
    suspend fun leaderboard(metric: RankMetric): List<LeaderboardEntry> =
        http.get("$base/leaderboard") { parameter("metric", metric.name) }.body()

    // --- Внешние сервисы
    /** Пешеходный маршрут через опорные точки, разбитый по улицам. */
    suspend fun routeThrough(points: List<LatLon>): List<Pair<String?, List<LatLon>>> {
        val coords = points.joinToString(";") { "${it.lon},${it.lat}" }
        val json = http.get("${BuildConfig.OSRM_URL}/route/v1/foot/$coords") {
            parameter("overview", "false"); parameter("steps", "true"); parameter("geometries", "geojson")
        }.bodyAsText()
        return OsmWays.parseOsrmSteps(json)
    }

    /** Линии OSM с тегами sidewalk/cycleway/lit/surface в рамке. */
    suspend fun osmWays(b: BBox): List<OsmWay> {
        val pad = 0.0005
        val q = OsmWays.overpassQuery(b.south - pad, b.west - pad, b.north + pad, b.east + pad)
        val json = http.post(BuildConfig.OVERPASS_URL) { setBody(FormDataContent(Parameters.build { append("data", q) })) }.bodyAsText()
        return OsmWays.parseOverpass(json)
    }

    suspend fun weather(p: LatLon): List<WeatherHour> = OpenMeteo.parse(http.get(OpenMeteo.url(p.lat, p.lon)).bodyAsText())
}
