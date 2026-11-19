package ru.rollers.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.transactions.transaction
import ru.rollers.core.model.SkillLevel

object Users : Table("users") {
    val id = long("id").autoIncrement()
    val name = varchar("name", 100)
    val email = varchar("email", 200).uniqueIndex()
    val passHash = varchar("pass_hash", 200)
    val level = enumerationByName("level", 20, SkillLevel::class)
    override val primaryKey = PrimaryKey(id)
}

object UserStatsT : Table("user_stats") {
    val userId = long("user_id").references(Users.id)
    val maxSpeed = double("max_speed").default(0.0)
    val maxAvgSpeed = double("max_avg_speed").default(0.0)
    val totalDistance = double("total_distance").default(0.0)
    val activities = integer("activities").default(0)
    override val primaryKey = PrimaryKey(userId)
}

object Routes : Table("routes") {
    val id = long("id").autoIncrement()
    val authorId = long("author_id").references(Users.id)
    val title = varchar("title", 200)
    val description = text("description")
    val segmentsJson = text("segments_json")
    val lengthM = double("length_m")
    val startLat = double("start_lat")
    val startLon = double("start_lon")
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

object SurfaceRatings : Table("surface_ratings") {
    val routeId = long("route_id").references(Routes.id)
    val segmentIndex = integer("segment_index")
    val userId = long("user_id").references(Users.id)
    val score = integer("score")
    val comment = varchar("comment", 500).nullable()
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(routeId, segmentIndex, userId)
}

object SegmentSpeeds : Table("segment_speeds") {
    val id = long("id").autoIncrement()
    val routeId = long("route_id").references(Routes.id)
    val segmentIndex = integer("segment_index")
    val userId = long("user_id").references(Users.id)
    val speedKmh = double("speed_kmh")
    val riderAvgKmh = double("rider_avg_kmh")
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
    init { index(false, routeId, segmentIndex) }
}

object Roadworks : Table("roadworks") {
    val id = long("id").autoIncrement()
    val reporterId = long("reporter_id").references(Users.id)
    val json = text("json")
    val south = double("south"); val west = double("west"); val north = double("north"); val east = double("east")
    val until = long("until").nullable()
    val confirmations = integer("confirmations").default(0)
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

object Events : Table("events") {
    val id = long("id").autoIncrement()
    val routeId = long("route_id").references(Routes.id)
    val organizerId = long("organizer_id").references(Users.id)
    val json = text("json")
    val startAt = long("start_at").index()
    override val primaryKey = PrimaryKey(id)
}

object EventParticipants : Table("event_participants") {
    val eventId = long("event_id").references(Events.id)
    val userId = long("user_id").references(Users.id)
    override val primaryKey = PrimaryKey(eventId, userId)
}

object Activities : Table("activities") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").references(Users.id)
    val routeId = long("route_id").nullable()
    val eventId = long("event_id").nullable()
    val pointsJson = text("points_json")
    val statsJson = text("stats_json")
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

object DatabaseFactory {
    fun init(url: String, user: String = "", password: String = ""): Database {
        val ds = HikariDataSource(HikariConfig().apply {
            jdbcUrl = url; username = user; this.password = password; maximumPoolSize = 8
        })
        val db = Database.connect(ds)
        transaction(db) {
            SchemaUtils.create(Users, UserStatsT, Routes, SurfaceRatings, SegmentSpeeds, Roadworks, Events, EventParticipants, Activities)
        }
        return db
    }
}
