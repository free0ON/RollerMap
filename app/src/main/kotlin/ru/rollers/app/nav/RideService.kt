package ru.rollers.app.nav

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.*
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.receiveDeserialized
import io.ktor.client.plugins.websocket.sendSerialized
import io.ktor.websocket.close
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import ru.rollers.app.RollersApp
import ru.rollers.app.ui.MainActivity
import ru.rollers.core.api.ActivityResult
import ru.rollers.core.api.LivePosition
import ru.rollers.core.conditions.RouteConditions
import ru.rollers.core.geo.BBox
import ru.rollers.core.geo.LatLon
import ru.rollers.core.model.Activity
import ru.rollers.core.model.Route
import ru.rollers.core.model.TrackPoint
import ru.rollers.core.nav.NavState
import ru.rollers.core.nav.Navigator
import ru.rollers.core.route.RouteGeometry
import java.util.Locale

/** Состояние текущего заезда, видимое UI. */
data class RideUiState(
    val active: Boolean = false,
    val route: Route? = null,
    val eventId: Long? = null,
    val nav: NavState? = null,
    val position: LatLon? = null,
    val track: List<LatLon> = emptyList(),
    val others: Map<Long, LivePosition> = emptyMap(),
    val lastPhrase: String? = null,
    val result: ActivityResult? = null,
    val error: String? = null,
    val uploading: Boolean = false,
)

/**
 * Фоновый сервис заезда: GPS → навигатор → голосовые подсказки,
 * трансляция скорости участникам события, запись трека и отправка на сервер.
 */
class RideService : LifecycleService(), TextToSpeech.OnInitListener {

    private val api get() = (application as RollersApp).api
    private lateinit var fused: FusedLocationProviderClient
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var navigator: Navigator? = null
    private val points = mutableListOf<TrackPoint>()
    private var live: DefaultClientWebSocketSession? = null
    private var liveJob: Job? = null
    private var lastLiveSent = 0L

    private val callback = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) { r.locations.forEach(::onLocation) }
    }

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS && tts?.setLanguage(Locale("ru", "RU"))?.let { it >= 0 } == true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> start(intent.getLongExtra(EXTRA_ROUTE, 0), intent.getLongExtra(EXTRA_EVENT, 0).takeIf { it > 0 })
            ACTION_STOP -> stopRide()
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission") // разрешение запрашивается в UI до запуска сервиса
    private fun start(routeId: Long, eventId: Long?) {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("Подготовка маршрута…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        points.clear()
        _state.value = RideUiState(active = true, eventId = eventId)
        lifecycleScope.launch {
            try {
                val route = api.route(routeId)
                val geometry = RouteGeometry.of(route)
                val event = eventId?.let { api.event(it) }
                val roadworks = runCatching { api.roadworks(BBox.of(geometry.line)) }.getOrDefault(emptyList())
                val rwSegments = RouteConditions.roadworksOnRoute(geometry, roadworks, System.currentTimeMillis()).keys
                navigator = Navigator(
                    geometry,
                    stops = event?.stops.orEmpty().map { it.name to it.point },
                    roadworkSegments = rwSegments,
                    plannedSpeedKmh = event?.avgSpeedKmh,
                    startAt = event?.startAt,
                )
                _state.update { it.copy(route = route) }
                if (eventId != null) connectLive(eventId)
                val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000).setMinUpdateDistanceMeters(2f).build()
                fused.requestLocationUpdates(req, callback, Looper.getMainLooper())
                updateNotification("Навигация: ${route.title}")
            } catch (e: Exception) {
                Log.e(TAG, "start failed", e)
                _state.update { it.copy(error = "Не удалось загрузить маршрут: ${e.message}") }
            }
        }
    }

    private fun onLocation(l: android.location.Location) {
        val p = LatLon(l.latitude, l.longitude)
        points += TrackPoint(p, l.time, if (l.hasSpeed()) l.speed.toDouble() else null, if (l.hasAccuracy()) l.accuracy.toDouble() else null)
        val nav = navigator ?: return
        if (l.hasAccuracy() && l.accuracy > 30) return // слишком неточно для навигации, но в трек пишем — отфильтрует TrackStats
        val (st, phrases) = nav.update(p, l.time, if (l.hasSpeed()) l.speed.toDouble() else null)
        phrases.forEach(::speak)
        _state.update { it.copy(nav = st, position = p, track = it.track + p, lastPhrase = phrases.lastOrNull() ?: it.lastPhrase) }
        updateNotification("%.1f км/ч · осталось %.1f км".format(st.speedKmh, st.remainingM / 1000))

        // трансляция своей скорости участникам события — не чаще раза в 3 с
        val ws = live
        if (ws != null && l.time - lastLiveSent > 3000) {
            lastLiveSent = l.time
            lifecycleScope.launch {
                runCatching { ws.sendSerialized(LivePosition(0, point = p, speedKmh = st.speedKmh, avgSpeedKmh = st.avgSpeedKmh, alongM = st.along, time = l.time)) }
            }
        }
        if (st.finished) speak("Нажмите «Завершить», чтобы сохранить результат.")
    }

    private fun connectLive(eventId: Long) {
        liveJob = lifecycleScope.launch {
            while (isActive) {
                try {
                    val s = api.liveSession(eventId); live = s
                    while (isActive) {
                        val pos = s.receiveDeserialized<LivePosition>()
                        _state.update { it.copy(others = it.others + (pos.userId to pos)) }
                    }
                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    Log.w(TAG, "live reconnect", e); live = null; delay(5000)
                }
            }
        }
    }

    private fun stopRide() {
        fused.removeLocationUpdates(callback)
        liveJob?.cancel()
        val s = _state.value
        val track = points.toList()
        _state.update { it.copy(active = false, uploading = track.size > 1) }
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            runCatching { live?.close() }
            if (track.size > 1) {
                // повторяем отправку при сбое сети
                var result: ActivityResult? = null; var err: Exception? = null
                repeat(3) { attempt ->
                    if (result != null) return@repeat
                    try { result = api.upload(Activity(routeId = s.route?.id, eventId = s.eventId, points = track)) }
                    catch (e: Exception) { err = e; delay(2000L * (attempt + 1)) }
                }
                _state.update { it.copy(uploading = false, result = result, error = if (result == null) "Результат не отправлен: ${err?.message}" else null) }
                result?.newRecords?.forEach(::speak)
            }
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun speak(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_ADD, null, text.hashCode().toString())
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, RideService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, RollersApp.RIDE_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Заезд")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Завершить", stop)
            .build()
    }

    private fun updateNotification(text: String) =
        getSystemService(android.app.NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))

    override fun onDestroy() {
        fused.removeLocationUpdates(callback)
        tts?.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RideService"
        private const val NOTIFICATION_ID = 42
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val EXTRA_ROUTE = "route"
        const val EXTRA_EVENT = "event"

        private val _state = MutableStateFlow(RideUiState())
        val state: StateFlow<RideUiState> = _state.asStateFlow()

        fun start(ctx: Context, routeId: Long, eventId: Long?) = ctx.startForegroundService(
            Intent(ctx, RideService::class.java).setAction(ACTION_START).putExtra(EXTRA_ROUTE, routeId).putExtra(EXTRA_EVENT, eventId ?: 0)
        )
        fun stop(ctx: Context) = ctx.startService(Intent(ctx, RideService::class.java).setAction(ACTION_STOP))
    }
}
