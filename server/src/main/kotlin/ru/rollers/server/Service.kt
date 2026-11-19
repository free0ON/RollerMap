package ru.rollers.server

import kotlinx.serialization.builtins.ListSerializer
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.transactions.transaction
import ru.rollers.core.api.*
import ru.rollers.core.event.EventPlanner
import ru.rollers.core.geo.BBox
import ru.rollers.core.geo.Geo
import ru.rollers.core.model.*
import ru.rollers.core.rating.RankMetric
import ru.rollers.core.rating.Ranking
import ru.rollers.core.rating.UserStats
import ru.rollers.core.route.RouteGeometry
import ru.rollers.core.surface.SpeedSample
import ru.rollers.core.surface.SurfaceQuality
import ru.rollers.core.surface.SurfaceScore
import ru.rollers.core.surface.WeightedRating
import ru.rollers.core.track.ActivityStats
import ru.rollers.core.track.TrackStats

class ApiException(val status: Int, message: String) : RuntimeException(message)

private fun notFound(what: String): Nothing = throw ApiException(404, "$what не найден")
private fun bad(msg: String): Nothing = throw ApiException(400, msg)

class Service(private val db: Database, val jwt: JwtConfig, private val clock: () -> Long = System::currentTimeMillis) {
    private val json = ApiJson
    private fun <T> tx(block: Transaction.() -> T): T = transaction(db) { block() }

    // ---------- Пользователи
    fun register(r: RegisterRequest): AuthResponse = tx {
        if (r.name.isBlank() || '@' !in r.email || r.password.length < 6) bad("Некорректные имя, email или пароль (минимум 6 символов)")
        if (Users.selectAll().where { Users.email eq r.email.lowercase() }.any()) throw ApiException(409, "Email уже зарегистрирован")
        val id = Users.insert {
            it[name] = r.name.trim(); it[email] = r.email.lowercase(); it[passHash] = Passwords.hash(r.password); it[level] = r.level
        }[Users.id]
        UserStatsT.insert { it[userId] = id }
        AuthResponse(jwt.token(id), id, r.name.trim())
    }

    fun login(r: LoginRequest): AuthResponse = tx {
        val row = Users.selectAll().where { Users.email eq r.email.lowercase() }.singleOrNull()
        if (row == null || !Passwords.verify(r.password, row[Users.passHash])) throw ApiException(401, "Неверный email или пароль")
        AuthResponse(jwt.token(row[Users.id]), row[Users.id], row[Users.name])
    }

    private fun Transaction.user(id: Long) = Users.selectAll().where { Users.id eq id }.singleOrNull()
        ?.let { User(it[Users.id], it[Users.name], it[Users.level]) } ?: notFound("Пользователь")

    private fun Transaction.stats(id: Long): UserStats {
        val u = user(id)
        val r = UserStatsT.selectAll().where { UserStatsT.userId eq id }.single()
        return UserStats(id, u.name, r[UserStatsT.maxSpeed], r[UserStatsT.maxAvgSpeed], r[UserStatsT.totalDistance], r[UserStatsT.activities])
    }

    fun profile(id: Long): Profile = tx {
        val u = user(id); val s = stats(id)
        Profile(id, u.name, u.level, s, Ranking.rank(s))
    }

    // ---------- Маршруты
    fun createRoute(authorId: Long, route: Route): Route = tx {
        if (route.title.isBlank()) bad("Укажите название маршрута")
        if (route.segments.isEmpty() || route.segments.any { it.axis.size < 2 }) bad("Маршрут должен содержать участки минимум из 2 точек")
        val g = RouteGeometry(route.segments)
        if (g.length > 300_000) bad("Маршрут длиннее 300 км")
        val now = clock()
        val id = Routes.insert {
            it[Routes.authorId] = authorId; it[title] = route.title; it[description] = route.description
            it[segmentsJson] = json.encodeToString(ListSerializer(RouteSegment.serializer()), route.segments)
            it[lengthM] = g.length; it[startLat] = g.line.first().lat; it[startLon] = g.line.first().lon; it[createdAt] = now
        }[Routes.id]
        route.copy(id = id, authorId = authorId, createdAt = now)
    }

    private fun ResultRow.toRoute() = Route(
        this[Routes.id], this[Routes.authorId], this[Routes.title], this[Routes.description],
        json.decodeFromString(ListSerializer(RouteSegment.serializer()), this[Routes.segmentsJson]), this[Routes.createdAt],
    )

    fun route(id: Long): Route = tx { Routes.selectAll().where { Routes.id eq id }.singleOrNull()?.toRoute() ?: notFound("Маршрут") }

    fun routes(bbox: BBox?): List<RouteSummary> = tx {
        val q = Routes.selectAll()
        if (bbox != null) q.where {
            (Routes.startLat greaterEq bbox.south) and (Routes.startLat lessEq bbox.north) and
                (Routes.startLon greaterEq bbox.west) and (Routes.startLon lessEq bbox.east)
        }
        q.orderBy(Routes.createdAt, SortOrder.DESC).limit(200).map {
            RouteSummary(it[Routes.id], it[Routes.title], it[Routes.authorId], it[Routes.lengthM],
                ru.rollers.core.geo.LatLon(it[Routes.startLat], it[Routes.startLon]))
        }
    }

    // ---------- Покрытие
    fun rateSurface(userId: Long, r: SurfaceRating): Unit = tx {
        val route = Routes.selectAll().where { Routes.id eq r.routeId }.singleOrNull()?.toRoute() ?: notFound("Маршрут")
        if (r.segmentIndex !in route.segments.indices) bad("Нет участка ${r.segmentIndex}")
        if (r.score !in 1..5) bad("Оценка от 1 до 5")
        SurfaceRatings.upsert {
            it[routeId] = r.routeId; it[segmentIndex] = r.segmentIndex; it[SurfaceRatings.userId] = userId
            it[score] = r.score; it[comment] = r.comment?.take(500); it[createdAt] = clock()
        }
    }

    fun surface(routeId: Long): SurfaceResponse = tx {
        val route = Routes.selectAll().where { Routes.id eq routeId }.singleOrNull()?.toRoute() ?: notFound("Маршрут")
        val now = clock()
        val ratings = (SurfaceRatings innerJoin Users).selectAll().where { SurfaceRatings.routeId eq routeId }
            .groupBy({ it[SurfaceRatings.segmentIndex] }) {
                WeightedRating(it[SurfaceRatings.score], it[Users.level], (now - it[SurfaceRatings.createdAt]) / 86_400_000.0)
            }
        val speeds = SegmentSpeeds.selectAll().where { SegmentSpeeds.routeId eq routeId }
            .groupBy({ it[SegmentSpeeds.segmentIndex] }) { SpeedSample(it[SegmentSpeeds.speedKmh], it[SegmentSpeeds.riderAvgKmh]) }
        val result = route.segments.indices.associateWith { i ->
            val r = ratings[i].orEmpty(); val s = speeds[i].orEmpty()
            if (r.isEmpty() && s.isEmpty()) {
                // нет данных от участников — берём оценку из OSM-тега surface
                SurfaceQuality.fromOsmSurface(route.segments[i].osmSurface)?.let { SurfaceScore(it, 0.1, null, null, 0, 0) }
                    ?: SurfaceQuality.combine(r, s)
            } else SurfaceQuality.combine(r, s)
        }
        SurfaceResponse(routeId, result)
    }

    // ---------- Ремонты
    fun reportRoadwork(userId: Long, rw: Roadwork): Roadwork = tx {
        if (rw.description.isBlank()) bad("Опишите ремонт")
        val pts = rw.polygon.ifEmpty { listOfNotNull(rw.center) }
        if (pts.isEmpty()) bad("Укажите место ремонта")
        val box = BBox.of(pts)
        val pad = rw.radiusM / 111_000.0
        val id = Roadworks.insert {
            it[reporterId] = userId; it[json] = ApiJson.encodeToString(Roadwork.serializer(), rw)
            it[south] = box.south - pad; it[north] = box.north + pad; it[west] = box.west - pad * 2; it[east] = box.east + pad * 2
            it[until] = rw.until; it[createdAt] = clock()
        }[Roadworks.id]
        rw.copy(id = id, reporterId = userId)
    }

    fun confirmRoadwork(id: Long): Unit = tx {
        val n = Roadworks.update({ Roadworks.id eq id }) { with(SqlExpressionBuilder) { it.update(confirmations, confirmations + 1) } }
        if (n == 0) notFound("Ремонт")
    }

    fun roadworks(bbox: BBox): List<Roadwork> = tx {
        val now = clock()
        Roadworks.selectAll().where {
            (Roadworks.north greaterEq bbox.south) and (Roadworks.south lessEq bbox.north) and
                (Roadworks.east greaterEq bbox.west) and (Roadworks.west lessEq bbox.east) and
                (Roadworks.until.isNull() or (Roadworks.until greaterEq now))
        }.map {
            ApiJson.decodeFromString(Roadwork.serializer(), it[Roadworks.json])
                .copy(id = it[Roadworks.id], reporterId = it[Roadworks.reporterId], confirmations = it[Roadworks.confirmations])
        }
    }

    // ---------- События
    private fun Transaction.loadEvent(id: Long): RideEvent {
        val row = Events.selectAll().where { Events.id eq id }.singleOrNull() ?: notFound("Событие")
        val participants = EventParticipants.selectAll().where { EventParticipants.eventId eq id }.map { it[EventParticipants.userId] }
        return ApiJson.decodeFromString(RideEvent.serializer(), row[Events.json])
            .copy(id = id, organizerId = row[Events.organizerId], participantIds = participants)
    }

    fun createEvent(userId: Long, e: RideEvent): RideEvent = tx {
        val route = Routes.selectAll().where { Routes.id eq e.routeId }.singleOrNull()?.toRoute() ?: notFound("Маршрут")
        val errors = EventPlanner.validate(e, RouteGeometry.of(route), clock())
        if (errors.isNotEmpty()) bad(errors.joinToString("; "))
        val stored = e.copy(participantIds = emptyList())
        val id = Events.insert {
            it[routeId] = e.routeId; it[organizerId] = userId; it[startAt] = e.startAt
            it[json] = ApiJson.encodeToString(RideEvent.serializer(), stored)
        }[Events.id]
        EventParticipants.insert { it[eventId] = id; it[EventParticipants.userId] = userId }
        loadEvent(id)
    }

    fun event(id: Long) = tx { loadEvent(id) }

    fun upcomingEvents(from: Long = clock() - 3 * 3600_000): List<RideEvent> = tx {
        Events.selectAll().where { Events.startAt greaterEq from }.orderBy(Events.startAt).limit(100)
            .map { loadEvent(it[Events.id]) }
    }

    fun join(userId: Long, eventId: Long): RideEvent = tx {
        val e = loadEvent(eventId)
        EventPlanner.canJoin(e, user(userId).level, userId).onFailure { throw ApiException(409, it.message ?: "Нельзя присоединиться") }
        EventParticipants.insert { it[EventParticipants.eventId] = eventId; it[EventParticipants.userId] = userId }
        loadEvent(eventId)
    }

    fun leave(userId: Long, eventId: Long): Unit = tx {
        EventParticipants.deleteWhere { (EventParticipants.eventId eq eventId) and (EventParticipants.userId eq userId) }
    }

    fun isParticipant(userId: Long, eventId: Long) = tx {
        EventParticipants.selectAll().where { (EventParticipants.eventId eq eventId) and (EventParticipants.userId eq userId) }.any()
    }

    fun userName(id: Long) = tx { user(id).name }

    // ---------- Заезды и рейтинг
    fun uploadActivity(userId: Long, a: Activity): ActivityResult = tx {
        if (a.points.size < 2) bad("Трек слишком короткий")
        val routeId = a.routeId ?: a.eventId?.let { loadEvent(it).routeId }
        val geometry = routeId?.let { id -> Routes.selectAll().where { Routes.id eq id }.singleOrNull()?.toRoute()?.let(RouteGeometry::of) }
        val st: ActivityStats = TrackStats.compute(a.points, geometry)
        val now = clock()
        val id = Activities.insert {
            it[Activities.userId] = userId; it[Activities.routeId] = routeId; it[eventId] = a.eventId; it[createdAt] = now
            it[pointsJson] = ApiJson.encodeToString(ListSerializer(TrackPoint.serializer()), a.points)
            it[statsJson] = ApiJson.encodeToString(ActivityStats.serializer(), st)
        }[Activities.id]

        // скорости на участках — вклад в оценку покрытия
        if (routeId != null && st.avgMovingSpeedKmh > 3) st.segmentSpeedsKmh.forEach { (seg, v) ->
            SegmentSpeeds.insert {
                it[SegmentSpeeds.routeId] = routeId; it[segmentIndex] = seg; it[SegmentSpeeds.userId] = userId
                it[speedKmh] = v; it[riderAvgKmh] = st.avgMovingSpeedKmh; it[createdAt] = now
            }
        }

        val before = stats(userId)
        val after = Ranking.apply(before, st)
        UserStatsT.update({ UserStatsT.userId eq userId }) {
            it[maxSpeed] = after.maxSpeedKmh; it[maxAvgSpeed] = after.maxAvgSpeedKmh
            it[totalDistance] = after.totalDistanceM; it[activities] = after.activities
        }
        // уровень пользователя растёт автоматически по лучшей средней скорости
        val newLevel = Ranking.rank(after).level
        if (newLevel > user(userId).level) Users.update({ Users.id eq userId }) { it[level] = newLevel }

        val records = buildList {
            if (after.maxSpeedKmh > before.maxSpeedKmh && before.activities > 0) add("Новый рекорд максимальной скорости")
            if (after.maxAvgSpeedKmh > before.maxAvgSpeedKmh && before.activities > 0) add("Новый рекорд средней скорости")
        }
        ActivityResult(id, st, after, Ranking.rank(after), records)
    }

    fun activities(userId: Long): List<ActivitySummary> = tx {
        Activities.selectAll().where { Activities.userId eq userId }.orderBy(Activities.createdAt, SortOrder.DESC).limit(100)
            .map { ActivitySummary(it[Activities.id], it[Activities.routeId], it[Activities.eventId], it[Activities.createdAt], ApiJson.decodeFromString(ActivityStats.serializer(), it[Activities.statsJson])) }
    }

    fun leaderboard(metric: RankMetric, limit: Int): List<ru.rollers.core.rating.LeaderboardEntry> = tx {
        val all = (UserStatsT innerJoin Users).selectAll().map {
            UserStats(it[Users.id], it[Users.name], it[UserStatsT.maxSpeed], it[UserStatsT.maxAvgSpeed], it[UserStatsT.totalDistance], it[UserStatsT.activities])
        }
        Ranking.leaderboard(all, metric, limit)
    }
}
