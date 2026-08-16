package peakid.engine.align

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan
import peakid.engine.geo.degToRad
import peakid.engine.geo.wrapDeltaDeg

/**
 * Proyección del perfil de horizonte sobre una fotografía. Puerto de
 * `src/align/__init__.py`.
 *
 * Convenios, y son CONTRATO (ver `docs/peakid-motor-CLAUDE.md`):
 *
 * - `azimuthDeg`: azimut del CENTRO de la imagen, no del borde.
 * - `pitchDeg`: elevación del centro; positivo = cámara mirando hacia ARRIBA,
 *   y entonces el horizonte se dibuja MÁS ABAJO. Invertirlo haría que el
 *   ajuste manual moviera la línea al revés del deslizador: plausible e
 *   incorrecto.
 * - `rollDeg`: positivo = el lado DERECHO del horizonte dibujado BAJA.
 * - Proyección pinhole rectilínea, sin distorsión de lente.
 *
 * La conversión NO es una regla de tres. Una cámara es una proyección en
 * perspectiva, así que hacia los bordes los grados se comprimen: con 65° de
 * campo, la aproximación lineal erraría ~4% del ancho en los extremos, unos
 * 150 px en una foto de 4000, y eso arruina el alineamiento fino.
 */
data class AlignmentParams(
    val azimuthDeg: Double,
    val hfovDeg: Double,
    val pitchDeg: Double,
    val rollDeg: Double,
)

/** Distancia focal en píxeles que corresponde a un campo horizontal. */
fun focalPx(widthPx: Int, hfovDeg: Double): Double =
    (widthPx / 2.0) / tan(degToRad(hfovDeg) / 2.0)

/**
 * Proyecta muestras (azimut, elevación) a píxeles de la foto.
 *
 * `outUsable[i]` marca las muestras por delante de la cámara. Las que no, y
 * las de elevación NaN (voids del perfil), salen `false` y sus píxeles no son
 * utilizables — comparar NaN da `false` en Kotlin igual que en numpy, así que
 * los voids caen ahí solos.
 */
fun projectProfile(
    azimuthsDeg: DoubleArray,
    elevationsDeg: DoubleArray,
    params: AlignmentParams,
    widthPx: Int,
    heightPx: Int,
    outX: DoubleArray,
    outY: DoubleArray,
    outUsable: BooleanArray,
    count: Int = azimuthsDeg.size,
) {
    val pitchRad = degToRad(params.pitchDeg)
    val rollRad = degToRad(params.rollDeg)
    val cosPitch = cos(pitchRad)
    val sinPitch = sin(pitchRad)
    val cosRoll = cos(rollRad)
    val sinRoll = sin(rollRad)
    val focal = focalPx(widthPx, params.hfovDeg)
    val halfW = widthPx / 2.0
    val halfH = heightPx / 2.0

    for (i in 0 until count) {
        val elRad = degToRad(elevationsDeg[i])
        // wrapDeltaDeg y NO `(x + 180) % 360 - 180`: en Kotlin el resto
        // conserva el signo del dividendo y la traducción literal rompe todo
        // el cuadrante noroeste (ver CLAUDE.md).
        val dazRad = degToRad(wrapDeltaDeg(azimuthsDeg[i] - params.azimuthDeg))

        val cosEl = cos(elRad)
        val forward = cosEl * cos(dazRad)
        val right = cosEl * sin(dazRad)
        val up = sin(elRad)

        // inclinación: rotación en el plano adelante/arriba
        val forwardP = forward * cosPitch + up * sinPitch
        val upP = -forward * sinPitch + up * cosPitch

        // giro: rotación en el plano derecha/arriba. Con roll positivo un
        // punto a la derecha del centro obtiene up negativo -> se dibuja más
        // bajo, que es el convenio declarado.
        val rightR = right * cosRoll + upP * sinRoll
        val upR = -right * sinRoll + upP * cosRoll

        val usable = forwardP > 1e-9      // NaN comparado da false
        outUsable[i] = usable
        if (usable) {
            outX[i] = halfW + focal * rightR / forwardP
            outY[i] = halfH - focal * upR / forwardP
        } else {
            outX[i] = Double.NaN
            outY[i] = Double.NaN
        }
    }
}

/** Resultado de proyectar, para quien no quiera gestionar los arrays. */
class Projected(val xPx: DoubleArray, val yPx: DoubleArray, val usable: BooleanArray)

fun projectProfile(
    azimuthsDeg: DoubleArray,
    elevationsDeg: DoubleArray,
    params: AlignmentParams,
    widthPx: Int,
    heightPx: Int,
): Projected {
    val n = azimuthsDeg.size
    val x = DoubleArray(n)
    val y = DoubleArray(n)
    val usable = BooleanArray(n)
    projectProfile(azimuthsDeg, elevationsDeg, params, widthPx, heightPx, x, y, usable, n)
    return Projected(x, y, usable)
}

/**
 * Altura de la silueta en cada columna pedida, interpolando la polilínea
 * proyectada. NaN fuera del tramo que cubre.
 *
 * La proyección da un punto por muestra de azimut, no por columna, y la
 * comparación con la cresta detectada necesita lo segundo.
 *
 * Con menos de dos puntos utilizables devuelve NaN en todas: no hay nada que
 * interpolar y devolver un número sería inventarlo.
 */
fun projectedYPerColumn(
    xPx: DoubleArray,
    yPx: DoubleArray,
    usable: BooleanArray,
    columnsPx: DoubleArray,
    out: DoubleArray,
    count: Int = xPx.size,
    columnCount: Int = columnsPx.size,
) {
    var used = 0
    for (i in 0 until count) if (usable[i]) used++
    if (used < 2) {
        for (j in 0 until columnCount) out[j] = Double.NaN
        return
    }

    val order = ArrayList<Int>(used)
    for (i in 0 until count) if (usable[i]) order.add(i)
    order.sortBy { xPx[it] }
    val xs = DoubleArray(used) { xPx[order[it]] }
    val ys = DoubleArray(used) { yPx[order[it]] }

    for (j in 0 until columnCount) {
        val c = columnsPx[j]
        if (c < xs[0] || c > xs[used - 1]) {
            out[j] = Double.NaN
            continue
        }
        // búsqueda binaria del tramo que contiene c
        var lo = 0
        var hi = used - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (xs[mid] <= c) lo = mid else hi = mid
        }
        val span = xs[hi] - xs[lo]
        out[j] = if (span <= 0.0) ys[lo] else ys[lo] + (ys[hi] - ys[lo]) * (c - xs[lo]) / span
    }
}

fun projectedYPerColumn(
    xPx: DoubleArray,
    yPx: DoubleArray,
    usable: BooleanArray,
    columnsPx: DoubleArray,
): DoubleArray {
    val out = DoubleArray(columnsPx.size)
    projectedYPerColumn(xPx, yPx, usable, columnsPx, out)
    return out
}
