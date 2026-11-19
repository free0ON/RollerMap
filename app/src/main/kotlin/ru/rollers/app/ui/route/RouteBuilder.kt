package ru.rollers.app.ui.route

import android.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
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
import ru.rollers.app.ui.map.MapLine
import ru.rollers.app.ui.map.MapPin
import ru.rollers.app.ui.map.OsmMap
import ru.rollers.core.geo.BBox
import ru.rollers.core.geo.Geo
import ru.rollers.core.geo.LatLon
import ru.rollers.core.model.*
import ru.rollers.core.osm.OsmWays
import ru.rollers.core.route.RouteGeometry

class RouteBuilderViewModel : ViewModel() {
    private val api = RollersApp.instance.api

    var waypoints by mutableStateOf(listOf<LatLon>()); private set
    var segments by mutableStateOf(listOf<RouteSegment>()); private set
    var selected by mutableStateOf<Int?>(null)
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var title by mutableStateOf("")
    var description by mutableStateOf("")

    val geometry: RouteGeometry get() = RouteGeometry(segments)

    fun addWaypoint(p: LatLon) { waypoints = waypoints + p }
    fun undo() { waypoints = waypoints.dropLast(1); if (waypoints.size < 2) segments = emptyList() }
    fun clear() { waypoints = emptyList(); segments = emptyList(); selected = null }

    /** Прокладывает путь через точки и автоматически подбирает сторону улицы по тегам OSM. */
    fun build() {
        if (waypoints.size < 2) { error = "Поставьте минимум 2 точки"; return }
        viewModelScope.launch {
            loading = true; error = null
            try {
                val steps = mergeSameStreet(api.routeThrough(waypoints))
                val ways = runCatching { api.osmWays(BBox.of(steps.flatMap { it.second })) }.getOrDefault(emptyList())
                segments = steps.map { (name, axis) ->
                    OsmWays.suggest(axis, OsmWays.matchWay(axis, ways)).let { if (it.streetName == null) it.copy(streetName = name) else it }
                }
            } catch (e: Exception) {
                error = "Не удалось построить маршрут: ${e.message}"
            } finally { loading = false }
        }
    }

    private fun mergeSameStreet(steps: List<Pair<String?, List<LatLon>>>): List<Pair<String?, List<LatLon>>> {
        val out = mutableListOf<Pair<String?, List<LatLon>>>()
        for (s in steps) {
            val last = out.lastOrNull()
            if (last != null && last.first == s.first && s.first != null) out[out.lastIndex] = last.first to (last.second + s.second.drop(1))
            else out += s
        }
        return out.filter { Geo.length(it.second) > 3 }
    }

    fun setSide(i: Int, side: StreetSide) { segments = segments.toMutableList().also { it[i] = it[i].copy(side = side) } }
    fun setKind(i: Int, kind: WayKind) {
        segments = segments.toMutableList().also { it[i] = it[i].copy(kind = kind, offsetM = kind.defaultOffsetM, side = if (kind == WayKind.PATH) StreetSide.CENTER else it[i].side) }
    }
    fun setOffset(i: Int, m: Double) { segments = segments.toMutableList().also { it[i] = it[i].copy(offsetM = m) } }

    fun save(onSaved: (Long) -> Unit) {
        if (title.isBlank()) { error = "Введите название"; return }
        viewModelScope.launch {
            loading = true
            try { onSaved(api.saveRoute(Route(title = title, description = description, segments = segments)).id) }
            catch (e: Exception) { error = "Ошибка сохранения: ${e.message}" }
            finally { loading = false }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteBuilderScreen(onSaved: (Long) -> Unit, vm: RouteBuilderViewModel = viewModel()) {
    var showSave by remember { mutableStateOf(false) }
    val g = vm.geometry
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            OsmMap(
                modifier = Modifier.fillMaxSize(),
                lines = buildList {
                    // ось улицы — пунктиром, путь роллера (со смещением на сторону) — сплошной
                    vm.segments.forEach { s -> add(MapLine(s.axis, Color.GRAY, 4f, dashed = true)) }
                    g.segmentLines.forEachIndexed { i, l ->
                        add(MapLine(l, if (vm.selected == i) Color.MAGENTA else Color.rgb(33, 150, 243), if (vm.selected == i) 14f else 9f) { vm.selected = i })
                    }
                },
                pins = vm.waypoints.mapIndexed { i, p -> MapPin(p, "Точка ${i + 1}") } +
                    g.crossings.map { MapPin(it.at, "Переход через улицу") },
                onTap = vm::addWaypoint,
            )
            Row(Modifier.align(Alignment.TopEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SmallFloatingActionButton(onClick = vm::undo) { Icon(Icons.AutoMirrored.Filled.Undo, "Отменить") }
                SmallFloatingActionButton(onClick = vm::clear) { Icon(Icons.Default.Delete, "Очистить") }
                FloatingActionButton(onClick = vm::build) { Icon(Icons.Default.Route, "Проложить") }
            }
            if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp)) }
        if (vm.segments.isEmpty()) {
            Text("Касайтесь карты, чтобы поставить точки маршрута, затем нажмите «Проложить».", Modifier.padding(12.dp))
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("%.2f км · %d участков · %d переходов".format(g.length / 1000, vm.segments.size, g.crossings.size), Modifier.weight(1f))
                Button(onClick = { showSave = true }) { Text("Сохранить") }
            }
            LazyColumn(Modifier.heightIn(max = 260.dp)) {
                itemsIndexed(vm.segments) { i, s -> SegmentRow(i, s, vm.selected == i, vm) }
            }
        }
    }
    if (showSave) AlertDialog(
        onDismissRequest = { showSave = false },
        title = { Text("Сохранить маршрут") },
        text = {
            Column {
                OutlinedTextField(vm.title, { vm.title = it }, label = { Text("Название") })
                OutlinedTextField(vm.description, { vm.description = it }, label = { Text("Описание") }, minLines = 2)
            }
        },
        confirmButton = { TextButton({ showSave = false; vm.save(onSaved) }) { Text("Сохранить") } },
        dismissButton = { TextButton({ showSave = false }) { Text("Отмена") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SegmentRow(i: Int, s: RouteSegment, selected: Boolean, vm: RouteBuilderViewModel) {
    ListItem(
        modifier = Modifier.clickable { vm.selected = i },
        colors = if (selected) ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else ListItemDefaults.colors(),
        headlineContent = { Text(s.streetName ?: "Участок ${i + 1}") },
        supportingContent = {
            Column {
                Text("%.0f м · %s%s%s".format(Geo.length(s.axis), s.kind.title,
                    s.lit?.let { if (it) " · освещено" else " · без освещения" } ?: "",
                    s.osmSurface?.let { " · $it" } ?: ""))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    StreetSide.entries.forEach { side ->
                        FilterChip(selected = s.side == side, onClick = { vm.setSide(i, side) }, enabled = s.kind != WayKind.PATH || side == StreetSide.CENTER,
                            label = { Text(when (side) { StreetSide.LEFT -> "Слева"; StreetSide.CENTER -> "По оси"; StreetSide.RIGHT -> "Справа" }) })
                    }
                }
                if (selected) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        WayKind.entries.forEach { k -> AssistChip(onClick = { vm.setKind(i, k) }, label = { Text(k.title) }) }
                    }
                    if (s.kind != WayKind.PATH) {
                        Text("Смещение от оси: %.0f м".format(s.offsetM))
                        Slider(value = s.offsetM.toFloat(), onValueChange = { vm.setOffset(i, it.toDouble()) }, valueRange = 1f..25f)
                    }
                }
            }
        },
    )
}
