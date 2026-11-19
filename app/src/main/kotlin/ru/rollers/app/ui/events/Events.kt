package ru.rollers.app.ui.events

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.ktor.client.plugins.ResponseException
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.launch
import ru.rollers.app.RollersApp
import ru.rollers.app.ui.formatTime
import ru.rollers.app.ui.map.MapLine
import ru.rollers.app.ui.map.MapPin
import ru.rollers.app.ui.map.OsmMap
import ru.rollers.app.ui.route.StartTimePicker
import ru.rollers.core.api.ApiJson
import ru.rollers.core.api.ErrorResponse
import ru.rollers.core.event.EventPlan
import ru.rollers.core.event.EventPlanner
import ru.rollers.core.geo.Geo
import ru.rollers.core.geo.LatLon
import ru.rollers.core.model.*
import ru.rollers.core.route.RouteGeometry
import kotlin.math.roundToInt

suspend fun Throwable.readable(): String =
    if (this is ResponseException) runCatching { ApiJson.decodeFromString(ErrorResponse.serializer(), response.bodyAsText()).error }.getOrDefault(message ?: "")
    else message ?: toString()

class EventsViewModel : ViewModel() {
    private val api = RollersApp.instance.api
    var events by mutableStateOf<List<RideEvent>>(emptyList()); private set
    var error by mutableStateOf<String?>(null)
    fun load() = viewModelScope.launch { runCatching { events = api.events() }.onFailure { error = it.readable() } }
}

@Composable
fun EventsScreen(onOpen: (Long) -> Unit, vm: EventsViewModel = viewModel()) {
    LaunchedEffect(Unit) { vm.load() }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item { Text("Ближайшие покатушки", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 12.dp)) }
        vm.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        if (vm.events.isEmpty()) item { Text("Событий пока нет. Откройте маршрут и создайте событие.") }
        items(vm.events) { e ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onOpen(e.id) }) {
                Column(Modifier.padding(12.dp)) {
                    Text(e.title, style = MaterialTheme.typography.titleMedium)
                    Text("${formatTime(e.startAt, withDate = true)} · ${e.avgSpeedKmh.roundToInt()} км/ч · ${e.level.title}")
                    Text("Участников: ${e.participantIds.size}" + (e.maxParticipants?.let { " из $it" } ?: ""))
                }
            }
        }
    }
}

// ---------------- Создание события

class CreateEventViewModel : ViewModel() {
    private val api = RollersApp.instance.api
    var route by mutableStateOf<Route?>(null); private set
    var title by mutableStateOf("")
    var description by mutableStateOf("")
    var startAt by mutableLongStateOf(System.currentTimeMillis() + 86_400_000)
    var speed by mutableDoubleStateOf(15.0)
    var level by mutableStateOf(SkillLevel.AMATEUR)
    var maxParticipants by mutableStateOf("")
    var stops by mutableStateOf(listOf<Stop>())
    var error by mutableStateOf<String?>(null)
    val geometry get() = route?.let(RouteGeometry::of)

    fun load(id: Long) = viewModelScope.launch { route = api.route(id) }

    /** Остановка ставится касанием карты и притягивается к маршруту. */
    fun addStop(p: LatLon) {
        val g = geometry ?: return
        val pr = Geo.project(p, g.line, g.cumulative)
        if (pr.distanceFromLine > 100) { error = "Остановка должна быть рядом с маршрутом"; return }
        stops = stops + Stop(pr.point, "Остановка ${stops.size + 1}", 5)
    }
    fun updateStop(i: Int, s: Stop) { stops = stops.toMutableList().also { it[i] = s } }
    fun removeStop(i: Int) { stops = stops.toMutableList().also { it.removeAt(i) } }

    fun event() = RideEvent(routeId = route?.id ?: 0, title = title, description = description, startAt = startAt,
        avgSpeedKmh = speed, stops = stops, level = level, maxParticipants = maxParticipants.toIntOrNull())

    val plan: EventPlan? get() = geometry?.let { EventPlanner.plan(event(), it) }
    val warnings: List<String> get() = geometry?.let { EventPlanner.validate(event(), it, System.currentTimeMillis()) }.orEmpty()

    fun create(onCreated: (Long) -> Unit) = viewModelScope.launch {
        runCatching { api.createEvent(event()) }.onSuccess { onCreated(it.id) }.onFailure { error = it.readable() }
    }
}

@Composable
fun CreateEventScreen(routeId: Long, onCreated: (Long) -> Unit, vm: CreateEventViewModel = viewModel()) {
    LaunchedEffect(routeId) { vm.load(routeId) }
    val g = vm.geometry ?: return
    val recommended = EventPlanner.recommendLevel(vm.speed, g.length / 1000)
    Column(Modifier.fillMaxSize()) {
        OsmMap(
            Modifier.fillMaxWidth().height(220.dp),
            lines = listOf(MapLine(g.line, android.graphics.Color.rgb(33, 150, 243))),
            pins = vm.stops.map { MapPin(it.point, it.name) },
            fitTo = g.line,
            onTap = vm::addStop,
        )
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            item {
                Text("Коснитесь карты, чтобы добавить остановку", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(vm.title, { vm.title = it }, label = { Text("Название") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(vm.description, { vm.description = it }, label = { Text("Описание") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                StartTimePicker(vm.startAt) { vm.startAt = it }
                Text("Средняя скорость: ${vm.speed.roundToInt()} км/ч")
                Slider(vm.speed.toFloat(), { vm.speed = it.toDouble() }, valueRange = 6f..35f)
                Text("Уровень участников (рекомендуется: ${recommended.title})")
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SkillLevel.entries.forEach { FilterChip(vm.level == it, { vm.level = it }, label = { Text(it.title) }) }
                }
                OutlinedTextField(vm.maxParticipants, { vm.maxParticipants = it.filter(Char::isDigit) }, label = { Text("Макс. участников (необязательно)") })
            }
            items(vm.stops.size) { i ->
                val s = vm.stops[i]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(s.name, { vm.updateStop(i, s.copy(name = it)) }, Modifier.weight(1f), label = { Text("Остановка") })
                    OutlinedTextField("${s.durationMin}", { v -> vm.updateStop(i, s.copy(durationMin = v.filter(Char::isDigit).toIntOrNull() ?: 0)) },
                        Modifier.width(80.dp), label = { Text("мин") })
                    TextButton({ vm.removeStop(i) }) { Text("✕") }
                }
            }
            item {
                vm.plan?.let { p ->
                    Text("План: в пути ${p.rideDurationMs / 60000} мин, всего ${p.totalDurationMs / 60000} мин, финиш ${formatTime(p.finishAt)}")
                    p.stops.forEach { Text("• ${it.name}: ${formatTime(it.arriveAt)}–${formatTime(it.departAt)} (%.1f км)".format(it.alongM / 1000)) }
                }
                vm.warnings.forEach { Text("⚠ $it", color = MaterialTheme.colorScheme.error) }
                vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button({ vm.create(onCreated) }, enabled = vm.warnings.isEmpty(), modifier = Modifier.padding(vertical = 12.dp)) { Text("Создать событие") }
            }
        }
    }
}

// ---------------- Карточка события

class EventDetailsViewModel : ViewModel() {
    private val api = RollersApp.instance.api
    var event by mutableStateOf<RideEvent?>(null); private set
    var route by mutableStateOf<Route?>(null); private set
    var plan by mutableStateOf<EventPlan?>(null); private set
    var error by mutableStateOf<String?>(null)
    val me get() = RollersApp.instance.session.user.value?.userId

    fun load(id: Long) = viewModelScope.launch {
        runCatching {
            val e = api.event(id); event = e
            route = api.route(e.routeId); plan = api.eventPlan(id)
        }.onFailure { error = it.readable() }
    }
    fun join() = viewModelScope.launch { runCatching { event = api.join(event!!.id) }.onFailure { error = it.readable() } }
    fun leave() = viewModelScope.launch { runCatching { api.leave(event!!.id); load(event!!.id) }.onFailure { error = it.readable() } }
}

@Composable
fun EventDetailsScreen(eventId: Long, onStartRide: (routeId: Long, eventId: Long) -> Unit, vm: EventDetailsViewModel = viewModel()) {
    LaunchedEffect(eventId) { vm.load(eventId) }
    val e = vm.event; val r = vm.route
    if (e == null || r == null) { vm.error?.let { Text(it, Modifier.padding(16.dp)) } ?: LinearProgressIndicator(Modifier.fillMaxWidth()); return }
    val g = RouteGeometry.of(r)
    val joined = vm.me in e.participantIds
    Column(Modifier.fillMaxSize()) {
        OsmMap(Modifier.fillMaxWidth().weight(1f), lines = listOf(MapLine(g.line, android.graphics.Color.rgb(33, 150, 243))),
            pins = e.stops.map { MapPin(it.point, it.name, "${it.durationMin} мин") } + MapPin(g.line.first(), "Старт"), fitTo = g.line)
        Column(Modifier.weight(1f).padding(12.dp)) {
            Text(e.title, style = MaterialTheme.typography.titleLarge)
            if (e.description.isNotBlank()) Text(e.description)
            Text("Старт: ${formatTime(e.startAt, withDate = true)}")
            Text("Маршрут: ${r.title}, %.1f км".format(g.length / 1000))
            Text("Средняя скорость: ${e.avgSpeedKmh.roundToInt()} км/ч · уровень: ${e.level.title}")
            Text("Участников: ${e.participantIds.size}" + (e.maxParticipants?.let { " из $it" } ?: ""))
            vm.plan?.let { p ->
                Text("Финиш ≈ ${formatTime(p.finishAt)}")
                p.stops.forEach { Text("• ${it.name}: ${formatTime(it.arriveAt)}") }
            }
            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!joined) Button(vm::join) { Text("Присоединиться") }
                else {
                    Button({ onStartRide(r.id, e.id) }) { Text("Начать заезд") }
                    if (vm.me != e.organizerId) OutlinedButton(vm::leave) { Text("Выйти") }
                }
            }
        }
    }
}
