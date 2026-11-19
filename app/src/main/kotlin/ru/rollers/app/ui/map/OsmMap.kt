package ru.rollers.app.ui.map

import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import ru.rollers.core.geo.LatLon

fun LatLon.gp() = GeoPoint(lat, lon)
fun GeoPoint.ll() = LatLon(latitude, longitude)

data class MapLine(val points: List<LatLon>, val color: Int, val width: Float = 10f, val dashed: Boolean = false, val onClick: (() -> Unit)? = null)
data class MapPin(val point: LatLon, val title: String, val snippet: String? = null, val icon: Int? = null)
data class MapCircle(val center: LatLon, val radiusM: Double, val color: Int, val title: String)

/** Цвет участка по оценке покрытия 1..5. */
fun surfaceColor(score: Double?): Int = when {
    score == null -> Color.rgb(33, 150, 243)
    score >= 4.5 -> Color.rgb(46, 125, 50)
    score >= 3.5 -> Color.rgb(124, 179, 66)
    score >= 2.5 -> Color.rgb(251, 192, 45)
    score >= 1.5 -> Color.rgb(245, 124, 0)
    else -> Color.rgb(198, 40, 40)
}

/**
 * Обёртка osmdroid для Compose. Оверлеи пересоздаются при каждом изменении входных данных —
 * для маршрутов из сотен точек это дёшево.
 */
@Composable
fun OsmMap(
    modifier: Modifier = Modifier,
    lines: List<MapLine> = emptyList(),
    pins: List<MapPin> = emptyList(),
    circles: List<MapCircle> = emptyList(),
    myPosition: LatLon? = null,
    center: LatLon? = null,
    fitTo: List<LatLon>? = null,
    zoom: Double = 15.0,
    onTap: ((LatLon) -> Unit)? = null,
    onLongPress: ((LatLon) -> Unit)? = null,
    onMoved: ((south: Double, west: Double, north: Double, east: Double) -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val map = remember {
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(zoom)
            controller.setCenter((center ?: LatLon(55.7558, 37.6173)).gp())
        }
    }
    DisposableEffect(Unit) { map.onResume(); onDispose { map.onPause(); map.onDetach() } }

    AndroidView(factory = { map }, modifier = modifier) { mv ->
        mv.overlays.clear()
        mv.overlays += MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean { onTap?.invoke(p.ll()); return onTap != null }
            override fun longPressHelper(p: GeoPoint): Boolean { onLongPress?.invoke(p.ll()); return onLongPress != null }
        })
        circles.forEach { c ->
            mv.overlays += Polygon(mv).apply {
                points = Polygon.pointsAsCircle(c.center.gp(), c.radiusM)
                fillPaint.color = Color.argb(70, Color.red(c.color), Color.green(c.color), Color.blue(c.color))
                outlinePaint.color = c.color; outlinePaint.strokeWidth = 3f
                title = c.title
            }
        }
        lines.forEach { l ->
            mv.overlays += Polyline(mv).apply {
                setPoints(l.points.map { it.gp() })
                outlinePaint.color = l.color; outlinePaint.strokeWidth = l.width
                if (l.dashed) outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(20f, 15f), 0f)
                l.onClick?.let { cb -> setOnClickListener { _, _, _ -> cb(); true } }
            }
        }
        pins.forEach { p ->
            mv.overlays += Marker(mv).apply {
                position = p.point.gp(); title = p.title; snippet = p.snippet
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                p.icon?.let { icon = androidx.core.content.ContextCompat.getDrawable(ctx, it) }
            }
        }
        myPosition?.let {
            mv.overlays += Marker(mv).apply {
                position = it.gp(); title = "Я"
                icon = androidx.core.content.ContextCompat.getDrawable(ctx, android.R.drawable.presence_online)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            }
        }
        onMoved?.let { cb ->
            mv.setMapListener(object : org.osmdroid.events.MapListener {
                override fun onScroll(e: org.osmdroid.events.ScrollEvent?): Boolean { mv.boundingBox.let { cb(it.latSouth, it.lonWest, it.latNorth, it.lonEast) }; return false }
                override fun onZoom(e: org.osmdroid.events.ZoomEvent?): Boolean { mv.boundingBox.let { cb(it.latSouth, it.lonWest, it.latNorth, it.lonEast) }; return false }
            })
        }
        mv.invalidate()
    }

    // подгонка масштаба / следование за позицией
    androidx.compose.runtime.LaunchedEffect(fitTo?.size, fitTo?.firstOrNull()) {
        val pts = fitTo ?: return@LaunchedEffect
        if (pts.size >= 2) map.post {
            map.zoomToBoundingBox(BoundingBox.fromGeoPoints(pts.map { it.gp() }).increaseByScale(1.2f), true)
        }
    }
    androidx.compose.runtime.LaunchedEffect(center) { center?.let { map.controller.animateTo(it.gp()) } }
}
