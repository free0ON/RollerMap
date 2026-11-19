package ru.rollers.app.ui.ride

import android.graphics.Color
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.rollers.app.nav.RideService
import ru.rollers.app.ui.map.MapLine
import ru.rollers.app.ui.map.MapPin
import ru.rollers.app.ui.map.OsmMap
import ru.rollers.core.nav.ManeuverType
import ru.rollers.core.route.RouteGeometry
import kotlin.math.abs
import kotlin.math.roundToInt

private fun ManeuverType.arrow() = when (this) {
    ManeuverType.TURN_LEFT, ManeuverType.SHARP_LEFT -> "⬅"
    ManeuverType.TURN_RIGHT, ManeuverType.SHARP_RIGHT -> "➡"
    ManeuverType.SLIGHT_LEFT -> "↖"
    ManeuverType.SLIGHT_RIGHT -> "↗"
    ManeuverType.CROSS_STREET -> "🚸"
    ManeuverType.STOP -> "⏸"
    ManeuverType.ROADWORK -> "🚧"
    ManeuverType.FINISH -> "🏁"
}

/** Экран заезда: карта, следующий манёвр, скорость, участники события. */
@Composable
fun RideScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val s by RideService.state.collectAsState()
    val geometry = remember(s.route) { s.route?.let(RouteGeometry::of) }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            OsmMap(
                Modifier.fillMaxSize(),
                lines = listOfNotNull(
                    geometry?.let { MapLine(it.line, Color.rgb(33, 150, 243), 12f) },
                    s.track.takeIf { it.size > 1 }?.let { MapLine(it, Color.rgb(233, 30, 99), 6f) },
                ),
                pins = s.others.values.map { MapPin(it.point, it.name, "${it.speedKmh.roundToInt()} км/ч") },
                myPosition = s.position,
                center = s.position,
                zoom = 17.0,
            )
            val nav = s.nav
            if (nav?.next != null) Card(Modifier.align(Alignment.TopCenter).padding(8.dp).fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(nav.next!!.type.arrow(), fontSize = 40.sp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("${nav.distanceToNext?.roundToInt() ?: 0} м", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        s.lastPhrase?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            if (nav?.offRoute == true) Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.align(Alignment.Center).padding(16.dp)) {
                Text("Вы сошли с маршрута (${nav.distanceFromRoute.roundToInt()} м)", Modifier.padding(12.dp))
            }
        }
        Surface(tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                val nav = s.nav
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Metric("Скорость", "${nav?.speedKmh?.roundToInt() ?: 0}", "км/ч", big = true)
                    Metric("Средняя", "%.1f".format(nav?.avgSpeedKmh ?: 0.0), "км/ч")
                    Metric("Пройдено", "%.2f".format((nav?.along ?: 0.0) / 1000), "км")
                    Metric("Осталось", "%.2f".format((nav?.remainingM ?: 0.0) / 1000), "км")
                }
                nav?.scheduleDeltaSec?.let { d ->
                    if (abs(d) > 30) Text(if (d > 0) "Отставание от группы ${d / 60} мин ${d % 60} с" else "Опережение ${-d / 60} мин",
                        color = if (d > 120) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                }
                if (s.others.isNotEmpty()) {
                    Text("Участники:", fontWeight = FontWeight.Bold)
                    s.others.values.sortedByDescending { it.alongM }.forEach {
                        val gap = ((it.alongM - (nav?.along ?: 0.0))).roundToInt()
                        Text("${it.name}: ${it.speedKmh.roundToInt()} км/ч, ${if (gap >= 0) "впереди на $gap м" else "позади на ${-gap} м"}")
                    }
                }
                s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                when {
                    s.active -> Button({ RideService.stop(ctx) }, Modifier.fillMaxWidth()) { Text("Завершить и сохранить") }
                    s.uploading -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Отправка результата…") }
                    s.result != null -> {
                        val r = s.result!!
                        Text("Результат сохранён", fontWeight = FontWeight.Bold)
                        Text("Дистанция %.2f км · средняя %.1f км/ч · максимум %.1f км/ч".format(r.stats.distanceM / 1000, r.stats.avgMovingSpeedKmh, r.stats.maxSpeedKmh))
                        Text("Звание: ${r.rank.title} (${r.rank.points} очков)")
                        r.newRecords.forEach { Text("🏆 $it") }
                        Button(onDone, Modifier.fillMaxWidth()) { Text("Готово") }
                    }
                    else -> Button(onDone, Modifier.fillMaxWidth()) { Text("Закрыть") }
                }
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, unit: String, big: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(value, fontSize = if (big) 32.sp else 20.sp, fontWeight = FontWeight.Bold)
        Text(unit, style = MaterialTheme.typography.labelSmall)
    }
}
