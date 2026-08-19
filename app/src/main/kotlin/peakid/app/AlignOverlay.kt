package peakid.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import peakid.engine.align.AlignmentParams
import peakid.engine.align.projectProfile
import peakid.engine.align.projectedYPerColumn
import peakid.engine.geo.normalizeAzimuthDeg
import peakid.engine.geo.wrapDeltaDeg
import peakid.engine.horizon.Visibility

/**
 * Dibuja sobre la foto la silueta proyectada, las cimas y la cresta marcada.
 *
 * El lienzo trabaja en píxeles de PANTALLA; la proyección, en píxeles de la
 * imagen ORIENTADA A RESOLUCIÓN COMPLETA. La única traducción entre ambos es un
 * factor de escala, y vive aquí. Mezclar los dos espacios daría un alineamiento
 * que encaja en la pantalla y no en el fichero exportado.
 */
@Composable
fun PhotoOverlay(
    state: AlignState,
    modifier: Modifier = Modifier,
    onMarkCrest: (Double, Double) -> Unit,
    markMode: Boolean,
    /** Poder APAGAR la cresta es parte de juzgarla: tapa lo que hay debajo. */
    showCrest: Boolean = true,
) {
    val foto = state.photo ?: return
    val pano = state.panorama
    val version = state.crestVersion  // fuerza recomposición al marcar

    Canvas(
        modifier = modifier.pointerInput(markMode) {
            if (!markMode) return@pointerInput
            detectTapGestures { pos ->
                // pantalla -> píxeles de la imagen completa
                val escala = size.width.toFloat() / foto.widthPx
                onMarkCrest((pos.x / escala).toDouble(), (pos.y / escala).toDouble())
            }
        },
    ) {
        val escala = size.width / foto.widthPx.toFloat()
        drawImageScaled(foto, escala)
        if (pano == null) return@Canvas

        val params = AlignmentParams(
            state.azimuthDeg, state.hfovDeg, state.pitchDeg, state.rollDeg,
        )
        drawSkyline(pano, params, foto.widthPx, foto.heightPx, escala, version)
        drawPeaks(pano, params, foto.widthPx, foto.heightPx, escala)
        if (showCrest) drawCrest(state, escala)
    }
}

private fun DrawScope.drawImageScaled(foto: LoadedPhoto, escala: Float) {
    val canvas = drawContext.canvas.nativeCanvas
    val destino = android.graphics.RectF(
        0f, 0f, foto.widthPx * escala, foto.heightPx * escala,
    )
    canvas.drawBitmap(foto.bitmap, null, destino, null)
}

private fun DrawScope.drawSkyline(
    pano: Panorama,
    params: AlignmentParams,
    widthPx: Int,
    heightPx: Int,
    escala: Float,
    @Suppress("UNUSED_PARAMETER") version: Int,
) {
    val projected = projectProfile(
        pano.profile.azimuthsDeg, pano.profile.elevationsDeg, params, widthPx, heightPx,
    )
    val columnas = DoubleArray(widthPx / 4) { (it * 4).toDouble() }
    val y = projectedYPerColumn(projected.xPx, projected.yPx, projected.usable, columnas)

    var previo: Offset? = null
    for (i in columnas.indices) {
        if (y[i].isNaN()) { previo = null; continue }
        val punto = Offset((columnas[i] * escala).toFloat(), (y[i] * escala).toFloat())
        previo?.let {
            drawLine(Color(0xFFFFC400), it, punto, strokeWidth = 3f)
        }
        previo = punto
    }
}

private fun DrawScope.drawPeaks(
    pano: Panorama,
    params: AlignmentParams,
    widthPx: Int,
    heightPx: Int,
    escala: Float,
) {
    // Solo las que caen dentro del encuadre, y las más DESTACADAS primero: gana
    // la de mayor ángulo aparente, no la más alta en metros. La Maroma manda
    // por sus 6.4° de arco, no por sus 2069 m.
    val dentro = pano.peaks.filter { abs(wrapDeltaDeg(it.azimuthDeg - params.azimuthDeg)) < params.hfovDeg }
    val ocupadas = mutableListOf<Float>()
    var pintadas = 0

    for (pico in dentro) {
        if (pintadas >= 12) break
        val p = projectProfile(
            doubleArrayOf(pico.azimuthDeg), doubleArrayOf(pico.elevationDeg),
            params, widthPx, heightPx,
        )
        if (!p.usable[0]) continue
        val x = (p.xPx[0] * escala).toFloat()
        val y = (p.yPx[0] * escala).toFloat()
        if (x < 0f || x > size.width || y < 0f || y > size.height) continue
        // no amontonar etiquetas: si otra ya ocupa esa franja, se cede
        if (ocupadas.any { abs(it - x) < 90f }) continue
        ocupadas.add(x)
        pintadas++

        val incierta = pico.visibility == Visibility.UNKNOWN
        val color = if (incierta) Color(0xFFFFA000) else Color(0xFFFFFFFF)
        drawLine(color, Offset(x, y), Offset(x, max(0f, y - 70f)), strokeWidth = 2f)
        drawCircle(color, radius = 5f, center = Offset(x, y))

        val canvas = drawContext.canvas.nativeCanvas
        val paint = android.graphics.Paint().apply {
            this.color = if (incierta) 0xFFFFA000.toInt() else 0xFFFFFFFF.toInt()
            textSize = 30f
            isAntiAlias = true
            setShadowLayer(4f, 0f, 0f, 0xFF000000.toInt())
        }
        canvas.save()
        canvas.rotate(-90f, x, max(0f, y - 78f))
        val etiqueta = buildString {
            append(pico.name)
            append("  ")
            append(pico.eleM.toInt())
            append(" m")
            if (incierta) append("  (sin datos para confirmar)")
        }
        canvas.drawText(etiqueta, x + 6f, max(0f, y - 78f), paint)
        canvas.restore()
    }
}

/**
 * La cresta MARCADA y la DETECTADA no se pintan igual, porque no son lo mismo.
 *
 * A dedo son dos docenas de decisiones deliberadas y conviene ver cada una: van
 * como puntos. El detector devuelve una columna de cada `step` —en una foto de
 * 3060 de ancho son 1020 puntos—, y pintarlos como círculos de radio 6 da una
 * banda de 12 px que tapa justo la cresta que hay que juzgar. Van como línea
 * fina.
 */
private fun DrawScope.drawCrest(state: AlignState, escala: Float) {
    if (state.crestCols.isEmpty()) return

    if (state.crestDetected) {
        var previo: Offset? = null
        for (i in state.crestCols.indices) {
            val punto = Offset(
                (state.crestCols[i] * escala).toFloat(),
                (state.crestRows[i] * escala).toFloat(),
            )
            // el detector deja huecos donde el camino no es fiable, y esos
            // huecos son información: la línea se corta, no se interpola
            val salto = previo?.let { kotlin.math.abs(punto.x - it.x) > 4f * escala } ?: true
            if (!salto) drawLine(Color(0xFF00E5FF), previo!!, punto, strokeWidth = 2f)
            previo = punto
        }
    } else {
        for (i in state.crestCols.indices) {
            drawCircle(
                Color(0xFF00E5FF), radius = 6f,
                center = Offset(
                    (state.crestCols[i] * escala).toFloat(),
                    (state.crestRows[i] * escala).toFloat(),
                ),
            )
        }
    }
}

/**
 * Tira del panorama de 360° donde el usuario ACOTA el sector.
 *
 * Es lo primero que se hace, y no es cosmética: con los deslizadores sobre los
 * 360° completos la resolución es de 1.09°/px, imposible de ajustar con el
 * dedo. Acotado a 30°, baja a 0.06°/px. Fue lo que hizo usable la herramienta
 * de escritorio y aquí importa más, porque el dedo es más gordo que el ratón.
 */
@Composable
fun SectorStrip(
    state: AlignState,
    modifier: Modifier = Modifier,
) {
    val pano = state.panorama
    Canvas(
        modifier = modifier.pointerInput(pano) {
            if (pano == null) return@pointerInput
            var inicio = 0f
            detectDragGestures(
                onDragStart = { inicio = it.x },
                onDrag = { change, _ ->
                    val a = normalizeAzimuthDeg(inicio / size.width * 360.0)
                    val b = normalizeAzimuthDeg(change.position.x / size.width * 360.0)
                    val span = normalizeAzimuthDeg(b - a)
                    // acotar a mano manda sobre cualquier siembra posterior de
                    // la brújula: una lectura que llegue tarde no pisa esto
                    state.azimuthTouched = true
                    state.sectorStartDeg = a
                    state.sectorSpanDeg = span.coerceIn(5.0, 360.0)
                    state.azimuthDeg = normalizeAzimuthDeg(a + span / 2.0)
                },
            )
        },
    ) {
        drawRect(Color(0xFF101820))
        if (pano == null) return@Canvas

        val elevs = pano.profile.elevationsDeg
        val maxE = elevs.filter { !it.isNaN() }.maxOrNull() ?: 1.0
        val minE = elevs.filter { !it.isNaN() }.minOrNull() ?: 0.0
        val rango = max(maxE - minE, 0.5)

        for (i in elevs.indices) {
            if (elevs[i].isNaN()) continue
            val x = i.toFloat() / elevs.size * size.width
            val alturaRel = ((elevs[i] - minE) / rango).toFloat()
            val y = size.height * (1f - alturaRel * 0.85f)
            drawLine(Color(0xFF3D5A73), Offset(x, size.height), Offset(x, y), strokeWidth = 2f)
        }

        // el arco elegido, resaltado; cruza el norte sin partirse
        val x0 = (state.sectorStartDeg / 360.0 * size.width).toFloat()
        val ancho = (state.sectorSpanDeg / 360.0 * size.width).toFloat()
        drawRect(
            Color(0x33FFC400),
            topLeft = Offset(x0, 0f),
            size = androidx.compose.ui.geometry.Size(min(ancho, size.width - x0), size.height),
        )
        if (x0 + ancho > size.width) {
            drawRect(
                Color(0x33FFC400),
                topLeft = Offset(0f, 0f),
                size = androidx.compose.ui.geometry.Size(x0 + ancho - size.width, size.height),
            )
        }
        drawLine(Color(0xFFFFC400), Offset(x0, 0f), Offset(x0, size.height), strokeWidth = 3f)

        // el norte, como referencia fija
        drawLine(
            Color(0xFF88A0B8), Offset(0f, 0f), Offset(0f, size.height),
            strokeWidth = 2f,
        )
        state.compassAzimuthDeg?.let { az ->
            val xb = (az / 360.0 * size.width).toFloat()
            drawLine(Color(0xFF00E5FF), Offset(xb, 0f), Offset(xb, size.height), strokeWidth = 3f)
        }
        drawRect(Color(0xFF6A7A8A), style = Stroke(width = 2f))
    }
}
