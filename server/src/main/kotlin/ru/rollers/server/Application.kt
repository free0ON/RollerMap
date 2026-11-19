package ru.rollers.server

import io.ktor.http.*
import io.ktor.serialization.kotlinx.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import ru.rollers.core.api.*
import ru.rollers.core.event.EventPlanner
import ru.rollers.core.geo.BBox
import ru.rollers.core.model.*
import ru.rollers.core.rating.RankMetric
import ru.rollers.core.route.RouteGeometry
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

fun main() {
    val port = System.getenv("PORT")?.toInt() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        val db = DatabaseFactory.init(
            System.getenv("DB_URL") ?: "jdbc:h2:file:./data/rollers;MODE=PostgreSQL",
            System.getenv("DB_USER") ?: "", System.getenv("DB_PASSWORD") ?: "",
        )
        module(Service(db, JwtConfig(System.getenv("JWT_SECRET") ?: "dev-secret-change-me")))
    }.start(wait = true)
}

/** Ретранслятор live-позиций участников события. */
class LiveHub {
    private val flows = ConcurrentHashMap<Long, MutableSharedFlow<LivePosition>>()
    private val last = ConcurrentHashMap<Long, ConcurrentHashMap<Long, LivePosition>>()
    fun flow(eventId: Long) = flows.getOrPut(eventId) { MutableSharedFlow(extraBufferCapacity = 256) }
    fun snapshot(eventId: Long) = last[eventId]?.values?.toList().orEmpty()
    suspend fun publish(eventId: Long, p: LivePosition) {
        last.getOrPut(eventId) { ConcurrentHashMap() }[p.userId] = p
        flow(eventId).emit(p)
    }
}

fun Application.module(service: Service) {
    val hub = LiveHub()
    install(ContentNegotiation) { json(ApiJson) }
    install(WebSockets) {
        pingPeriod = 15.seconds
        contentConverter = KotlinxWebsocketSerializationConverter(ApiJson)
    }
    service.jwt.install(this)
    install(StatusPages) {
        exception<ApiException> { call, e -> call.respond(HttpStatusCode.fromValue(e.status), ErrorResponse(e.message ?: "")) }
        exception<SerializationException> { call, e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse("Неверный формат: ${e.message}")) }
        exception<BadRequestException> { call, e -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Неверный запрос")) }
        exception<NumberFormatException> { call, _ -> call.respond(HttpStatusCode.BadRequest, ErrorResponse("Неверный параметр")) }
    }

    fun ApplicationCall.idParam(name: String = "id") = parameters[name]?.toLongOrNull() ?: throw ApiException(400, "Неверный $name")
    fun ApplicationCall.bbox(): BBox? = request.queryParameters["bbox"]?.split(',')?.map { it.toDouble() }
        ?.takeIf { it.size == 4 }?.let { BBox(it[0], it[1], it[2], it[3]) }

    routing {
        get("/health") { call.respondText("ok") }
        post("/auth/register") { call.respond(HttpStatusCode.Created, service.register(call.receive())) }
        post("/auth/login") { call.respond(service.login(call.receive())) }

        get("/routes") { call.respond(service.routes(call.bbox())) }
        get("/routes/{id}") { call.respond(service.route(call.idParam())) }
        get("/routes/{id}/surface") { call.respond(service.surface(call.idParam())) }
        get("/roadworks") { call.respond(service.roadworks(call.bbox() ?: throw ApiException(400, "Нужен bbox=south,west,north,east"))) }
        get("/events") { call.respond(service.upcomingEvents()) }
        get("/events/{id}") { call.respond(service.event(call.idParam())) }
        get("/events/{id}/plan") {
            val e = service.event(call.idParam())
            call.respond(EventPlanner.plan(e, RouteGeometry.of(service.route(e.routeId))))
        }
        get("/leaderboard") {
            val metric = call.request.queryParameters["metric"]?.let { RankMetric.valueOf(it.uppercase()) } ?: RankMetric.OVERALL
            call.respond(service.leaderboard(metric, call.request.queryParameters["limit"]?.toInt()?.coerceIn(1, 500) ?: 100))
        }
        get("/users/{id}") { call.respond(service.profile(call.idParam())) }

        authenticate("auth") {
            get("/me") { call.respond(service.profile(call.userId)) }
            post("/routes") { call.respond(HttpStatusCode.Created, service.createRoute(call.userId, call.receive())) }
            post("/routes/{id}/surface") {
                val r = call.receive<SurfaceRating>().copy(routeId = call.idParam())
                service.rateSurface(call.userId, r); call.respond(HttpStatusCode.NoContent)
            }
            post("/roadworks") { call.respond(HttpStatusCode.Created, service.reportRoadwork(call.userId, call.receive())) }
            post("/roadworks/{id}/confirm") { service.confirmRoadwork(call.idParam()); call.respond(HttpStatusCode.NoContent) }
            post("/events") { call.respond(HttpStatusCode.Created, service.createEvent(call.userId, call.receive())) }
            post("/events/{id}/join") { call.respond(service.join(call.userId, call.idParam())) }
            delete("/events/{id}/join") { service.leave(call.userId, call.idParam()); call.respond(HttpStatusCode.NoContent) }
            post("/activities") { call.respond(HttpStatusCode.Created, service.uploadActivity(call.userId, call.receive())) }
            get("/me/activities") { call.respond(service.activities(call.userId)) }

            // Live-трансляция скорости и положения участников события
            webSocket("/events/{id}/live") {
                val eventId = call.idParam(); val uid = call.userId
                if (!service.isParticipant(uid, eventId)) { close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Не участник")); return@webSocket }
                val name = service.userName(uid)
                hub.snapshot(eventId).forEach { sendSerialized(it) }
                val job = launch { hub.flow(eventId).filter { it.userId != uid }.collect { sendSerialized(it) } }
                try {
                    incoming.consumeEach { frame ->
                        if (frame is Frame.Text) {
                            val p = ApiJson.decodeFromString(LivePosition.serializer(), frame.readText())
                            hub.publish(eventId, p.copy(userId = uid, name = name))
                        }
                    }
                } finally { job.cancel() }
            }
        }
    }
}
