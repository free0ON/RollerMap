package ru.rollers.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import ru.rollers.app.RollersApp
import ru.rollers.app.nav.RideService
import ru.rollers.app.ui.events.CreateEventScreen
import ru.rollers.app.ui.events.EventDetailsScreen
import ru.rollers.app.ui.events.EventsScreen
import ru.rollers.app.ui.map.MapPin
import ru.rollers.app.ui.map.OsmMap
import ru.rollers.app.ui.rating.LeaderboardScreen
import ru.rollers.app.ui.rating.ProfileScreen
import ru.rollers.app.ui.ride.RideScreen
import ru.rollers.app.ui.route.RouteBuilderScreen
import ru.rollers.app.ui.route.RouteDetailsScreen
import ru.rollers.core.api.RouteSummary
import ru.rollers.core.geo.BBox
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatTime(ms: Long, withDate: Boolean = false): String =
    SimpleDateFormat(if (withDate) "d MMM, HH:mm" else "HH:mm", Locale("ru")).format(Date(ms))

class MainActivity : ComponentActivity() {
    private var pendingRide: (() -> Unit)? = null
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.ACCESS_FINE_LOCATION] == true) pendingRide?.invoke()
        pendingRide = null
    }

    /** Запускает заезд, предварительно запросив геолокацию и уведомления. */
    fun startRide(routeId: Long, eventId: Long?, then: () -> Unit) {
        val go = { RideService.start(this, routeId, eventId); then() }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) go()
        else {
            pendingRide = go
            permissions.launch(buildList {
                add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }.toTypedArray())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { App(this) } }
    }
}

private data class Tab(val route: String, val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val tabs = listOf(
    Tab("map", "Маршруты", Icons.Default.Map),
    Tab("events", "События", Icons.Default.Event),
    Tab("rating", "Рейтинг", Icons.Default.EmojiEvents),
    Tab("profile", "Профиль", Icons.Default.Person),
)

@Composable
private fun App(activity: MainActivity) {
    val nav = rememberNavController()
    val user by RollersApp.instance.session.user.collectAsState()
    val ride by RideService.state.collectAsState()
    val current = nav.currentBackStackEntryAsState().value?.destination?.route

    Scaffold(bottomBar = {
        if (current in tabs.map { it.route }) NavigationBar {
            tabs.forEach { t ->
                NavigationBarItem(selected = current == t.route, onClick = {
                    nav.navigate(t.route) { popUpTo("map") { saveState = true }; launchSingleTop = true; restoreState = true }
                }, icon = { Icon(t.icon, t.title) }, label = { Text(t.title) })
            }
        }
    }) { pad ->
        NavHost(nav, startDestination = "map", Modifier.padding(pad)) {
            composable("map") {
                HomeMap(
                    rideActive = ride.active,
                    onOpenRoute = { nav.navigate("route/$it") },
                    onNewRoute = { nav.navigate(if (user == null) "profile" else "builder") },
                    onOpenRide = { nav.navigate("ride") },
                )
            }
            composable("builder") { RouteBuilderScreen(onSaved = { id -> nav.navigate("route/$id") { popUpTo("map") } }) }
            composable("route/{id}", listOf(navArgument("id") { type = NavType.LongType })) { e ->
                RouteDetailsScreen(
                    e.arguments!!.getLong("id"),
                    onCreateEvent = { nav.navigate(if (user == null) "profile" else "newEvent/$it") },
                    onRide = { routeId -> if (user == null) nav.navigate("profile") else activity.startRide(routeId, null) { nav.navigate("ride") } },
                )
            }
            composable("newEvent/{routeId}", listOf(navArgument("routeId") { type = NavType.LongType })) { e ->
                CreateEventScreen(e.arguments!!.getLong("routeId"), onCreated = { id -> nav.navigate("event/$id") { popUpTo("events") } })
            }
            composable("events") { EventsScreen(onOpen = { nav.navigate("event/$it") }) }
            composable("event/{id}", listOf(navArgument("id") { type = NavType.LongType })) { e ->
                EventDetailsScreen(e.arguments!!.getLong("id"), onStartRide = { r, ev ->
                    if (user == null) nav.navigate("profile") else activity.startRide(r, ev) { nav.navigate("ride") }
                })
            }
            composable("ride") { RideScreen(onDone = { nav.popBackStack("map", false) }) }
            composable("rating") { LeaderboardScreen() }
            composable("profile") { ProfileScreen() }
        }
    }
}

@Composable
private fun HomeMap(rideActive: Boolean, onOpenRoute: (Long) -> Unit, onNewRoute: () -> Unit, onOpenRide: () -> Unit) {
    val api = RollersApp.instance.api
    var routes by remember { mutableStateOf<List<RouteSummary>>(emptyList()) }
    var bbox by remember { mutableStateOf<BBox?>(null) }
    LaunchedEffect(bbox) {
        kotlinx.coroutines.delay(400) // не дёргать сервер на каждый кадр прокрутки
        routes = runCatching { api.routes(bbox) }.getOrDefault(routes)
    }
    Box(Modifier.fillMaxSize()) {
        OsmMap(
            Modifier.fillMaxSize(),
            pins = routes.map { MapPin(it.start, it.title, "%.1f км".format(it.lengthM / 1000)) },
            onTap = { p -> routes.minByOrNull { ru.rollers.core.geo.Geo.distance(it.start, p) }
                ?.takeIf { ru.rollers.core.geo.Geo.distance(it.start, p) < 150 }?.let { onOpenRoute(it.id) } },
            onMoved = { s, w, n, e -> bbox = BBox(s, w, n, e) },
        )
        Column(Modifier.align(Alignment.BottomEnd).padding(16.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (rideActive) ExtendedFloatingActionButton(onClick = onOpenRide, icon = { Icon(Icons.Default.Navigation, null) }, text = { Text("Текущий заезд") })
            ExtendedFloatingActionButton(onClick = onNewRoute, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Новый маршрут") })
        }
    }
}
