package com.diegonmarcos.cloudnav.tracker

import android.content.Context
import android.content.ContextWrapper
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.diegonmarcos.cloudnav.maps.MapStyles
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

/** Explored > Maps renderer: OSM basemap, then areas (fill + outline), routes and pins from [ExploredMapData]'s GeoJSON. */
@Composable
fun ExploredMap(layers: ExploredLayers, modifier: Modifier = Modifier, styleKey: String = "light") {
    val ctx = LocalContext.current
    val mv = remember { MapLibre.getInstance(ctx); MapView(ctx).apply { onCreate(null) } }
    val lifecycle = remember(ctx) { ctx.lifecycleOwner()?.lifecycle }
    DisposableEffect(lifecycle, mv) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mv.onStart(); Lifecycle.Event.ON_RESUME -> mv.onResume()
                Lifecycle.Event.ON_PAUSE -> mv.onPause(); Lifecycle.Event.ON_STOP -> mv.onStop()
                else -> {}
            }
        }
        lifecycle?.addObserver(observer)
        mv.setOnTouchListener { v, ev -> v.parent?.requestDisallowInterceptTouchEvent(ev.actionMasked != MotionEvent.ACTION_UP && ev.actionMasked != MotionEvent.ACTION_CANCEL); false }
        onDispose { lifecycle?.removeObserver(observer); mv.onPause(); mv.onStop(); mv.onDestroy() }
    }
    LaunchedEffect(layers, styleKey) {
        mv.getMapAsync { map ->
            map.uiSettings.isRotateGesturesEnabled = false
            map.setStyle(Style.Builder().fromJson(MapStyles.styleJson(MapStyles.get(styleKey)))) { style ->
                style.addSource(GeoJsonSource("ex-areas", ExploredMapData.areasJson(layers)))
                style.addSource(GeoJsonSource("ex-routes", ExploredMapData.routesJson(layers)))
                style.addSource(GeoJsonSource("ex-pins", ExploredMapData.pinsJson(layers)))
                style.addLayer(FillLayer("ex-areas-fill", "ex-areas").withProperties(PropertyFactory.fillColor("#3B82F6"), PropertyFactory.fillOpacity(0.14f)))
                style.addLayer(LineLayer("ex-areas-line", "ex-areas").withProperties(PropertyFactory.lineColor("#3B82F6"), PropertyFactory.lineWidth(1.5f), PropertyFactory.lineOpacity(0.7f)))
                style.addLayer(LineLayer("ex-routes-casing", "ex-routes").withProperties(PropertyFactory.lineColor("#FFFFFF"), PropertyFactory.lineWidth(7f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
                style.addLayer(LineLayer("ex-routes-line", "ex-routes").withProperties(PropertyFactory.lineColor(Expression.get("color")), PropertyFactory.lineWidth(4f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
                style.addLayer(CircleLayer("ex-pins-circle", "ex-pins").withProperties(PropertyFactory.circleRadius(7f), PropertyFactory.circleColor(Expression.get("color")),
                    PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(2f)))
                val pts = layers.points
                if (pts.isEmpty()) return@setStyle
                if (pts.all { it == pts[0] }) { map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(pts[0].lat, pts[0].lon), 12.0)); return@setStyle }
                val b = LatLngBounds.Builder(); pts.forEach { b.include(LatLng(it.lat, it.lon)) }
                map.moveCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 72))
            }
        }
    }
    AndroidView(factory = { mv }, modifier = modifier)
}

private fun Context.lifecycleOwner(): LifecycleOwner? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is LifecycleOwner) return c; c = c.baseContext }
    return null
}
