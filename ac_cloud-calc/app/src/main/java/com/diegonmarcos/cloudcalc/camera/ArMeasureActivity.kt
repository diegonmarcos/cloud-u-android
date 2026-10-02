package com.diegonmarcos.cloudcalc.camera

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.diegonmarcos.cloudcalc.R
import com.diegonmarcos.cloudcalc.measure.Space
import com.diegonmarcos.cloudcalc.measure.Vec
import com.diegonmarcos.cloudcalc.ui.CalcMetrics
import com.diegonmarcos.cloudcalc.ui.CalcTheme
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * #772 the AR measure route: ARCore tracks the room, a tap drops an anchor where it meets a
 * detected plane (or a feature point), and :measure's Space turns the anchors into a distance, a
 * path, an area or a height in metres. Offered only when ARCore is on the phone (CameraScreens
 * checks ArCoreApk first); the photo-and-reference route covers every other phone. The GL here is
 * the minimum ARCore needs — the camera image as an external texture on one full-screen quad —
 * and the marks are drawn by Compose from each anchor projected to the screen.
 */
class ArMeasureActivity : ComponentActivity(), GLSurfaceView.Renderer {
    private var session: Session? = null
    private var installRequested = false
    private lateinit var surface: GLSurfaceView
    private val taps = ConcurrentLinkedQueue<FloatArray>()
    private val anchors = ArrayList<Anchor>() // GL thread only
    @Volatile private var clearRequested = false
    @Volatile private var undoRequested = false
    private var texture = 0
    private var program = 0
    private var width = 1
    private var height = 1
    private val ndc: FloatBuffer = floats(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val uv: FloatBuffer = floats(FloatArray(8))

    private var mode by mutableStateOf(MODES.first())
    private var result by mutableStateOf("")
    private var marks by mutableStateOf(listOf<Offset>())
    private var note by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        surface = GLSurfaceView(this).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setRenderer(this@ArMeasureActivity)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            setOnTouchListener { v, e -> if (e.action == MotionEvent.ACTION_UP) { taps.add(floatArrayOf(e.x, e.y)); v.performClick() }; true }
        }
        setContent {
            CalcTheme {
                Box(Modifier.fillMaxSize()) {
                    AndroidView(factory = { surface }, modifier = Modifier.fillMaxSize())
                    val dot = MaterialTheme.colorScheme.primary
                    Canvas(Modifier.fillMaxSize()) { marks.forEach { drawCircle(dot, CalcMetrics.gap.toPx(), it) } }
                    Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), tonalElevation = CalcMetrics.small) {
                        Column(Modifier.padding(CalcMetrics.gutter)) {
                            Text(result.ifBlank { stringResource(R.string.ar_hint) }, style = MaterialTheme.typography.headlineSmall)
                            if (note.isNotBlank()) Text(note, color = MaterialTheme.colorScheme.error)
                            Row(horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
                                MODES.forEach { m -> FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(stringResource(label(m))) }) }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(CalcMetrics.gap)) {
                                OutlinedButton(onClick = { undoRequested = true }) { Text(stringResource(R.string.ar_undo)) }
                                OutlinedButton(onClick = { clearRequested = true }) { Text(stringResource(R.string.ar_clear)) }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (session == null) {
            try {
                if (ArCoreApk.getInstance().requestInstall(this, !installRequested) == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                    installRequested = true
                    return
                }
                session = Session(this).also { s ->
                    s.configure(Config(s).apply {
                        planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                        focusMode = Config.FocusMode.AUTO
                    })
                }
            } catch (e: Exception) {
                note = getString(R.string.ar_unavailable, e.message ?: e.javaClass.simpleName)
                return
            }
        }
        try { session?.resume() } catch (e: Exception) { note = getString(R.string.ar_unavailable, e.message ?: e.javaClass.simpleName); session = null; return }
        surface.onResume()
    }

    override fun onPause() {
        super.onPause()
        if (::surface.isInitialized) surface.onPause()
        session?.pause()
    }

    override fun onDestroy() {
        session?.close()
        session = null
        super.onDestroy()
    }

    // ── GL ───────────────────────────────────────────────────────────────────────────────────

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        texture = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        program = link(VERTEX, FRAGMENT)
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w; height = h
        GLES20.glViewport(0, 0, w, h)
        @Suppress("DEPRECATION")
        session?.setDisplayGeometry(windowManager.defaultDisplay.rotation, w, h)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return
        s.setCameraTextureName(texture)
        val frame = runCatching { s.update() }.getOrNull() ?: return
        if (frame.hasDisplayGeometryChanged()) {
            ndc.position(0); uv.position(0)
            frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, ndc, Coordinates2d.TEXTURE_NORMALIZED, uv)
        }
        drawBackground()
        if (clearRequested) { anchors.forEach { it.detach() }; anchors.clear(); clearRequested = false }
        if (undoRequested) { anchors.removeLastOrNull()?.detach(); undoRequested = false }
        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) { marks = emptyList(); return }
        while (true) {
            val tap = taps.poll() ?: break
            frame.hitTest(tap[0], tap[1]).firstOrNull { h ->
                val t = h.trackable
                (t is Plane && t.isPoseInPolygon(h.hitPose)) || t is Point
            }?.let { anchors += it.createAnchor() }
        }
        val pts = anchors.filter { it.trackingState == TrackingState.TRACKING }.map { a -> a.pose.let { Vec(it.tx().toDouble(), it.ty().toDouble(), it.tz().toDouble()) } }
        result = measure(mode, pts)
        val view = FloatArray(16); val proj = FloatArray(16); val vp = FloatArray(16)
        camera.getViewMatrix(view, 0)
        camera.getProjectionMatrix(proj, 0, 0.05f, 100f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        marks = pts.mapNotNull { p ->
            val c = FloatArray(4)
            Matrix.multiplyMV(c, 0, vp, 0, floatArrayOf(p.x.toFloat(), p.y.toFloat(), p.z.toFloat(), 1f), 0)
            if (c[3] <= 0f) null else Offset((c[0] / c[3] + 1f) / 2f * width, (1f - c[1] / c[3]) / 2f * height)
        }
    }

    private fun drawBackground() {
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        val pos = GLES20.glGetAttribLocation(program, "a_Position")
        val tex = GLES20.glGetAttribLocation(program, "a_TexCoord")
        ndc.position(0); uv.position(0)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, ndc)
        GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, 0, uv)
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glEnableVertexAttribArray(tex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(pos)
        GLES20.glDisableVertexAttribArray(tex)
    }

    private fun link(vs: String, fs: String): Int {
        fun shader(type: Int, src: String) = GLES20.glCreateShader(type).also { GLES20.glShaderSource(it, src); GLES20.glCompileShader(it) }
        return GLES20.glCreateProgram().also { p ->
            GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs))
            GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(p)
        }
    }

    companion object {
        const val DISTANCE = "distance"
        const val PATH = "path"
        const val AREA = "area"
        const val HEIGHT = "height"
        val MODES = listOf(DISTANCE, PATH, AREA, HEIGHT)

        fun label(mode: String): Int = when (mode) {
            PATH -> R.string.ar_path
            AREA -> R.string.ar_area
            HEIGHT -> R.string.ar_height
            else -> R.string.ar_distance
        }

        /** What [mode] reads off the anchors [pts] (metres, square metres); blank until enough points. */
        fun measure(mode: String, pts: List<Vec>): String = when {
            pts.size < 2 -> ""
            mode == PATH -> "%.3f m".format(Space.pathLength(pts))
            mode == AREA -> if (pts.size < 3) "" else "%.3f m²".format(Space.polygonArea(pts))
            mode == HEIGHT -> "%.3f m".format(Space.height(pts[pts.size - 2], pts.last()))
            else -> "%.3f m".format(Space.distance(pts[pts.size - 2], pts.last()))
        }

        private fun floats(a: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }

        private const val VERTEX = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() { gl_Position = a_Position; v_TexCoord = a_TexCoord; }
        """
        private const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES s_Texture;
            void main() { gl_FragColor = texture2D(s_Texture, v_TexCoord); }
        """
    }
}
