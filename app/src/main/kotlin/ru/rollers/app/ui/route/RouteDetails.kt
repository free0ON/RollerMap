package ru.rollers.app.ui.route

import android.graphics.Color
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import kotlinx.coroutines.launch
import ru.rollers.app.RollersApp
import ru.rollers.app.ui.formatTime
import ru.rollers.app.ui.map.*
import ru.rollers.core.conditions.*
import ru.rollers.core.geo.BBox
import ru.rollers.core.geo.LatLon
import ru.rollers.core.model.Roadwork
import ru.rollers.core.model.Route
import ru.rollers.core.model.SurfaceRating
import ru.rollers.core.route.RouteGeometry
import ru.rollers.core.surface.SurfaceScore
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.roundToInt

class RouteDetailsViewModel : ViewModel() {
    private val api = RollersApp.instance.api
    var route by mutableStateOf<Route?>(null); private set
    var surface by mutableStateOf<Map<Int, SurfaceScore>>(emptyMap()); private set
    var roadworks by mutableStateOf<List<Roadwork>>(emptyList()); private set
    var weather by mutableStateOf<List<WeatherHour>>(emptyList()); private set
    var startAt by mutableLongStateOf(System.currentTimeMillis())
    var speedKmh by mutableDoubleStateOf(15.0)
    var message by mutableStateOf<String?>(null)
    val geometry get() = route?.let(RouteGeometry::of)

    fun load(id: Long) = viewModelScope.launch {
        try {
            val r = api.route(id); route = r
            val g = RouteGeometry.of(r)
            launch { surface = runCatching { api.surface(id).segments }.getOrDefault(emptyMap()) }
            launch { roadworks = runCatching { api.roadworks(BBox.of(g.line)) }.getOrDefault(emptyList()) }
            launch { weather = runCatching { api.weather(g.line[g.line.size / 2]) }.getOrDefault(emptyList()) }
        } catch (e: Exception) { message = "Ошибка загрузки: ${e.message}" }
    }

    val report: ConditionsReport? get() = geometry?.let { RouteConditions.assess(it, startAt, speedKmh, weather, surface, roadworks) }

    fun rate(segment: Int, score: Int, comment: String?) = viewModelScope.launch {
        val r = route ?: return@launch
        runCatching { api.rateSurface(SurfaceRating(r.id, segment, score = score, comment = comment)) }
            .onSuccess { surface = api.surface(r.id).segments; message = "Спасибо за оценку!" }
            .onFailure { message = "Не удалось отправить оценку: ${it.message}" }
    }

    fun reportRoadwork(p: LatLon, text: String, days: Int?) = viewModelScope.launch {
        runCatching {
            api.reportRoadwork(Roadwork(description = text, center = p, radiusM = 30.0,
                until = days?.let { System.currentTimeMillis() + it * 86_400_000L }))
        }.onSuccess { roadworks = roadworks + it; message = "Ремонт отмечен" }
            .onFailure { message = "Ошибка: ${it.message}" }
    }

    fun confirm(rw: Roadwork) = viewModelScope.launch { runCatching { api.confirmRoadwork(rw.id) }; message = "Подтверждено" }
}

@Composable
fun RouteDetailsScreen(routeId: Long, onCreateEvent: (Long) -> Unit, onRide: (Long) -> Unit, vm: RouteDetailsViewModel = viewModel()) {
    LaunchedEffect(routeId) { vm.load(routeId) }
    val route = vm.route
    if (route == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val g = vm.geometry!!
    val report = vm.report
    var rateSegment by remember { mutableStateOf<Int?>(null) }
    var roadworkAt by remember { mutableStateOf<LatLon?>(null) }

    Column(Modifier.fillMaxSize()) {
        OsmMap(
            modifier = Modifier.fillMaxWidth().weight(1f),
            lines = g.segmentLines.mapIndexed { i, l ->
                val dark = report?.segments?.getOrNull(i)?.let { it.phase == DaylightPhase.DARK && it.lit != true } == true
                MapLine(l, surfaceColor(vm.surface[i]?.score), 11f, dashed = dark) { rateSegment = i }
            },
            circles = vm.roadworks.map { MapCircle(it.center ?: it.polygon.first(), it.radiusM, Color.rgb(255, 87, 34), "Ремонт: ${it.description}") },
            pins = listOf(MapPin(g.line.first(), "Старт"), MapPin(g.line.last(), "Финиш")) + g.crossings.map { MapPin(it.at, "Переход") },
            fitTo = g.line,
            onLongPress = { roadworkAt = it },
        )
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            item {
                Text(route.title, style = MaterialTheme.typography.titleLarge)
                if (route.description.isNotBlank()) Text(route.description)
                Text("%.1f км · нажмите участок для оценки покрытия, долгое нажатие — отметить ремонт".format(g.length / 1000),
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                StartTimePicker(vm.startAt) { vm.startAt = it }
                Text("Средняя скорость: ${vm.speedKmh.roundToInt()} км/ч")
                Slider(vm.speedKmh.toFloat(), { vm.speedKmh = it.toDouble() }, valueRange = 6f..35f)
            }
            if (report != null) {
                item {
                    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Пригодность: ${report.suitability}/100", style = MaterialTheme.typography.titleMedium)
                            Text("Финиш ≈ ${formatTime(report.finishAt)}")
                            Text("Встречный ветер в среднем: %.1f м/с".format(report.avgHeadwind))
                            Text("Осадки: до ${report.maxPrecipitationProbability}%")
                            Text("В темноте без освещения: ${(report.darkUnlitShare * 100).roundToInt()}% пути")
                            val first = g.line.first()
                            val day = SunCalc.dayTimes(LocalDate.now().atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(), first.lat, first.lon)
                            Text("Светлое время: ${day.sunrise?.let(::formatTime) ?: "—"} – ${day.sunset?.let(::formatTime) ?: "—"}")
                            if (vm.weather.isEmpty()) Text("Прогноз погоды недоступен", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                items(report.issues) { issue ->
                    val color = when (issue.severity) {
                        Severity.BLOCKER -> MaterialTheme.colorScheme.error
                        Severity.WARNING -> androidx.compose.ui.graphics.Color(0xFFE65100)
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    Text("• ${issue.message}" + (issue.alongFrom?.let { " (%.1f км)".format(it / 1000) } ?: ""), color = color)
                }
            }
            items(vm.roadworks) { rw ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🚧 ${rw.description} (подтверждений: ${rw.confirmations})", Modifier.weight(1f))
                    TextButton({ vm.confirm(rw) }) { Text("Ещё тут") }
                }
            }
            item {
                Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ onCreateEvent(route.id) }) { Text("Создать событие") }
                    OutlinedButton({ onRide(route.id) }) { Text("Поехать сейчас") }
                }
                vm.message?.let { Text(it) }
            }
        }
    }

    rateSegment?.let { seg -> RateDialog(seg, route.segments[seg].streetName, vm.surface[seg], { rateSegment = null }) { s, c -> vm.rate(seg, s, c); rateSegment = null } }
    roadworkAt?.let { p -> RoadworkDialog({ roadworkAt = null }) { text, days -> vm.reportRoadwork(p, text, days); roadworkAt = null } }
}

@Composable
fun StartTimePicker(value: Long, onChange: (Long) -> Unit) {
    val zone = ZoneId.systemDefault()
    fun at(day: LocalDate, h: Int) = day.atTime(LocalTime.of(h, 0)).atZone(zone).toInstant().toEpochMilli()
    val today = LocalDate.now(); val tomorrow = today.plusDays(1)
    val options = listOf(
        "Сейчас" to System.currentTimeMillis(),
        "Через 1 ч" to System.currentTimeMillis() + 3_600_000,
        "Сегодня 19:00" to at(today, 19),
        "Завтра 8:00" to at(tomorrow, 8),
        "Завтра 19:00" to at(tomorrow, 19),
    )
    Text("Старт: ${formatTime(value, withDate = true)}")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        options.forEach { (label, t) -> AssistChip(onClick = { onChange(t) }, label = { Text(label) }) }
    }
}

@Composable
private fun RateDialog(segment: Int, street: String?, current: SurfaceScore?, onDismiss: () -> Unit, onRate: (Int, String?) -> Unit) {
    var score by remember { mutableIntStateOf(4) }
    var comment by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Покрытие: ${street ?: "участок ${segment + 1}"}") },
        text = {
            Column {
                current?.let {
                    Text("Сейчас: %.1f — %s (оценок %d, заездов %d, достоверность %d%%)".format(
                        it.score, it.label, it.ratingsCount, it.ridesCount, (it.confidence * 100).roundToInt()))
                }
                Row { (1..5).forEach { s -> FilterChip(score == s, { score = s }, label = { Text("$s") }) } }
                Text(listOf("", "Непроезжаемо", "Плохое", "Среднее", "Хорошее", "Идеальный асфальт")[score], style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(comment, { comment = it }, label = { Text("Комментарий") })
            }
        },
        confirmButton = { TextButton({ onRate(score, comment.ifBlank { null }) }) { Text("Оценить") } },
        dismissButton = { TextButton(onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun RoadworkDialog(onDismiss: () -> Unit, onReport: (String, Int?) -> Unit) {
    var text by remember { mutableStateOf("") }
    var days by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Отметить ремонт") },
        text = {
            Column {
                OutlinedTextField(text, { text = it }, label = { Text("Что происходит") })
                OutlinedTextField(days, { days = it.filter(Char::isDigit) }, label = { Text("Продлится дней (если известно)") })
            }
        },
        confirmButton = { TextButton({ if (text.isNotBlank()) onReport(text, days.toIntOrNull()) }) { Text("Отправить") } },
        dismissButton = { TextButton(onDismiss) { Text("Отмена") } },
    )
}
