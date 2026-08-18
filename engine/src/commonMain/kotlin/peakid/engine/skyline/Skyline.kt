package peakid.engine.skyline

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Cresta como CAMINO GLOBAL, por programación dinámica.
 *
 * Puerto de `src/align/skyline.py` del motor. El skyline es un camino continuo
 * de la primera a la última columna, y aquí se busca el de coste mínimo global
 * con penalización por salto: un atajo cresta→nube→cresta paga los dos saltos
 * aunque su coste local sea menor.
 *
 * **NO USAR ESTA DP SOBRE EVIDENCIA DE COLOR.** Medido y descartado en el
 * motor: empeoraba las cinco fotos de referencia (141720 de 1.5 a 906 px,
 * sierra de 31 a 1970). El problema no era la discontinuidad sino la
 * evidencia: bajo un criterio de color una nube es tan "no-cielo" como una
 * montaña, y la DP solo vuelve COHERENTE una respuesta equivocada — una línea
 * suave y falsa es más creíble, y por tanto peor, que un desastre visiblemente
 * disperso. La DP vive aquí porque encima de una evidencia que SABE qué es una
 * nube (la segmentación con modelo) sí funciona. Por eso el puerto no trae
 * `sky_evidence`: no se porta lo que ya se midió que no debe usarse.
 *
 * Convenio de las matrices: **fila mayor**, `cost[fila * width + columna]`.
 * Es el mismo que el terreno del paquete y evita un array de arrays por
 * columna, que en el móvil se nota.
 */

/** Salto máximo entre columnas vecinas, en filas. */
const val JUMP_LIMIT_PX: Int = 25

/**
 * λ ADIMENSIONAL: fracción del rango de coste de una columna que cuesta un
 * salto del tamaño máximo.
 *
 * Fijarlo en unidades absolutas es un error de escala fácil de cometer y
 * difícil de ver — con λ=0.6 por fila y un rango de coste por columna de 0.73,
 * moverse UNA fila costaba casi todo el rango y el camino óptimo salía
 * perfectamente plano.
 */
const val SMOOTHNESS: Double = 0.35

/** "Lejos" al medir la fiabilidad de una columna. */
const val MARGIN_PX: Double = 40.0

/**
 * Coste de que la frontera pase por cada `(fila, columna)`.
 *
 *     coste(r,c) = media(1−s por encima de r) + media(s por debajo de r)
 *
 * Cielo mal explicado ARRIBA más terreno mal explicado ABAJO. Con sumas
 * acumuladas sale para todas las filas de golpe, O(H·W).
 *
 * **Los dos términos hacen falta.** Es lo que convierte "dónde está el borde"
 * en "qué partición cielo/terreno explica mejor la columna entera", que es una
 * pregunta mucho más robusta frente a un borde de nube. Con solo el de arriba,
 * cualquier fila por encima de la frontera explica el cielo igual de bien y el
 * mínimo se va a la fila 0.
 *
 * La fila `r` no entra en NINGUNO de los dos términos —arriba cuenta `0..r-1`
 * y abajo `r+1..alto-1`—, así que la frontera cae ENTRE dos filas y las dos
 * cuestan cero. No es un descuido: es dónde está el borde.
 */
fun boundaryCost(sky: DoubleArray, width: Int, height: Int): DoubleArray {
    val out = DoubleArray(height * width)
    // acumulado de cielo por columna, y total de la columna
    val aboveSky = DoubleArray(width)          // cielo en 0..r-1
    val totalSky = DoubleArray(width)
    for (r in 0 until height) {
        for (c in 0 until width) totalSky[c] += sky[r * width + c]
    }
    for (r in 0 until height) {
        val rowsAbove = max(r.toDouble(), 1.0)
        val belowCount = max((height - 1 - r).toDouble(), 1.0)
        for (c in 0 until width) {
            val above = (r - aboveSky[c]) / rowsAbove
            val below = (totalSky[c] - aboveSky[c] - sky[r * width + c]) / belowCount
            out[r * width + c] = above + below
        }
        for (c in 0 until width) aboveSky[c] += sky[r * width + c]
    }
    return out
}

/**
 * Coste BAJO donde hay un borde horizontal fuerte, en `[0, 1]`.
 *
 * Existe porque el modelo y la imagen saben cosas distintas. El modelo dice
 * QUÉ es cielo pero saca los logits a 1/4 de su entrada; el gradiente de la
 * imagen no sabe qué es una nube, pero está a resolución de trabajo. Uno para
 * acertar de zona, el otro de fila.
 *
 * Se normaliza por el percentil 95 **DE CADA COLUMNA**. Globalmente no: una
 * columna en calima o a contraluz tiene bordes débiles en términos absolutos y
 * quedaría descartada entera frente a otra bien iluminada, aunque su cresta
 * sea igual de nítida.
 *
 * `rgb` viene en `[fila * width * 3 + columna * 3 + canal]`.
 */
fun edgeCost(rgb: DoubleArray, width: Int, height: Int): DoubleArray {
    val luminance = DoubleArray(height * width)
    for (i in 0 until height * width) {
        luminance[i] = 0.299 * rgb[i * 3] + 0.587 * rgb[i * 3 + 1] + 0.114 * rgb[i * 3 + 2]
    }
    val gradient = DoubleArray(height * width)
    for (r in 1 until height - 1) {
        for (c in 0 until width) {
            gradient[r * width + c] =
                abs(luminance[(r + 1) * width + c] - luminance[(r - 1) * width + c])
        }
    }
    val out = DoubleArray(height * width)
    val columna = DoubleArray(height)
    for (c in 0 until width) {
        for (r in 0 until height) columna[r] = gradient[r * width + c]
        val referencia = percentile95(columna)
        for (r in 0 until height) {
            val v = gradient[r * width + c] / max(referencia, 1e-6)
            out[r * width + c] = 1.0 - v.coerceIn(0.0, 1.0)
        }
    }
    return out
}

/**
 * Percentil 95 con el convenio LINEAL de numpy, que es el que usa el motor.
 *
 * No es "el elemento en la posición 0.95·n": numpy interpola entre los dos
 * vecinos. Redondear en vez de interpolar desplaza la referencia y, con ella,
 * el umbral de todas las columnas.
 */
internal fun percentile95(values: DoubleArray): Double {
    val orden = values.copyOf()
    orden.sort()
    if (orden.size == 1) return orden[0]
    val pos = 0.95 * (orden.size - 1)
    val bajo = floor(pos).toInt()
    val alto = min(bajo + 1, orden.size - 1)
    val frac = pos - bajo
    return orden[bajo] * (1.0 - frac) + orden[alto] * frac
}

/** Camino y fiabilidad por columna. */
class Path(val rows: IntArray, val margin: DoubleArray)

/**
 * Camino de coste mínimo columna a columna, con penalización de salto.
 *
 *     mejor(c,r) = coste(r,c) + min_{|r'−r| ≤ K} [ mejor(c−1,r') + λ·|r−r'| ]
 *
 * El margen es cuánto peor es el mejor camino que pasa LEJOS de la solución en
 * esa columna: mide si la evidencia manda ahí o si el camino pasa por inercia
 * de sus vecinos.
 *
 * **El margen usa LOS DOS barridos, ida y vuelta.** Con solo el de ida mediría
 * la mitad del problema y daría por fiables columnas que van por inercia de
 * sus vecinas de la izquierda. La propiedad que lo delata: un camino no tiene
 * sentido de marcha, así que el margen tiene que ser simétrico al invertir el
 * orden de las columnas.
 *
 * Con `smoothness = 0` degenera en elegir el mínimo de cada columna por
 * separado, que es justo el comportamiento que se quiere superar.
 */
fun bestPath(
    cost: DoubleArray,
    width: Int,
    height: Int,
    jumpLimit: Int = JUMP_LIMIT_PX,
    smoothness: Double = SMOOTHNESS,
): Path {
    val offsets = IntArray(2 * jumpLimit + 1) { it - jumpLimit }

    // λ en unidades del propio coste: un salto del tamaño máximo cuesta
    // `smoothness` veces el rango típico de una columna
    val rangos = DoubleArray(width)
    for (c in 0 until width) {
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (r in 0 until height) {
            val v = cost[r * width + c]
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        rangos[c] = hi - lo
    }
    val scale = median(rangos)
    val lam = smoothness * max(scale, 1e-9) / max(jumpLimit, 1)
    val penalty = DoubleArray(offsets.size) { lam * abs(offsets[it]).toDouble() }

    val forward = DoubleArray(width * height)
    val choice = IntArray(width * height)
    sweep(cost, width, height, offsets, penalty, forward, choice, adelante = true)

    val backward = DoubleArray(width * height)
    val ignorado = IntArray(width * height)
    sweep(cost, width, height, offsets, penalty, backward, ignorado, adelante = false)

    val rows = IntArray(width)
    var mejor = 0
    for (r in 1 until height) {
        if (forward[(width - 1) * height + r] < forward[(width - 1) * height + mejor]) mejor = r
    }
    rows[width - 1] = mejor
    for (c in width - 1 downTo 1) rows[c - 1] = choice[c * height + rows[c]]

    // MARGEN EXACTO: coste del mejor camino COMPLETO que pasa por (col, r),
    // que es ida + vuelta menos el coste de la casilla, contado dos veces.
    val margin = DoubleArray(width)
    for (c in 0 until width) {
        var optimo = Double.MAX_VALUE
        var lejano = Double.MAX_VALUE
        var hayLejano = false
        for (r in 0 until height) {
            val through = forward[c * height + r] + backward[c * height + r] - cost[r * width + c]
            if (through < optimo) optimo = through
            if (abs(r - rows[c]) > MARGIN_PX) {
                hayLejano = true
                if (through < lejano) lejano = through
            }
        }
        margin[c] = if (hayLejano) (lejano - optimo) else 0.0
    }
    val norm = max(scale, 1e-9)
    for (c in 0 until width) margin[c] /= norm
    return Path(rows, margin)
}

/**
 * Coste acumulado y elección, recorriendo las columnas en un sentido.
 *
 * `acc` va indexado `[columna * height + fila]` —al revés que `cost`— porque
 * el barrido lee una columna entera de golpe y así queda contigua.
 */
private fun sweep(
    cost: DoubleArray,
    width: Int,
    height: Int,
    offsets: IntArray,
    penalty: DoubleArray,
    acc: DoubleArray,
    pickFrom: IntArray,
    adelante: Boolean,
) {
    val primera = if (adelante) 0 else width - 1
    for (r in 0 until height) {
        acc[primera * height + r] = cost[r * width + primera]
        pickFrom[primera * height + r] = r
    }
    val rango = if (adelante) (1 until width) else (width - 2 downTo 0)
    for (col in rango) {
        val previa = if (adelante) col - 1 else col + 1
        for (r in 0 until height) {
            var mejorValor = Double.MAX_VALUE
            var mejorFila = r
            for (i in offsets.indices) {
                val origen = r + offsets[i]
                if (origen < 0 || origen >= height) continue
                val v = acc[previa * height + origen] + penalty[i]
                if (v < mejorValor) {
                    mejorValor = v
                    mejorFila = origen
                }
            }
            acc[col * height + r] = cost[r * width + col] + mejorValor
            pickFrom[col * height + r] = mejorFila
        }
    }
}

/** Mediana con el convenio de numpy: media de los dos centrales si es par. */
internal fun median(values: DoubleArray): Double {
    if (values.isEmpty()) return 0.0
    val orden = values.copyOf()
    orden.sort()
    val n = orden.size
    return if (n % 2 == 1) orden[n / 2] else (orden[n / 2 - 1] + orden[n / 2]) / 2.0
}

// ---------------------------------------------------------------------------
// La banda, y el tope de salto. Puerto de `src/align/segmentation.py`.
// ---------------------------------------------------------------------------

/**
 * Cuanto de la máscara, EN FILAS DE TRABAJO.
 *
 * El modelo saca los logits a 1/4 de su entrada, así que por debajo de eso no
 * puede situar nada: este número es el suelo del error de localización, y por
 * eso gobierna la anchura de la banda y la tolerancia con que se compara una
 * cresta contra otra.
 */
fun maskQuantumRows(width: Int, height: Int, longSide: Int = INPUT_LONG_SIDE): Double {
    val (_, targetH) = inputSize(width, height, longSide)
    return height / max(targetH / 4.0, 1.0)
}

/**
 * Semianchura de la banda, en filas de trabajo.
 *
 * Dos cuantos: la incertidumbre real del modelo, ni más ni menos. El suelo de
 * 4 filas es para que en una foto diminuta la banda no se cierre sobre la
 * frontera cruda y la deje sin margen para corregir.
 */
fun bandWidth(quantum: Double): Int = max(4.0, round(2.0 * quantum)).toInt()

/**
 * Tope de salto de la DP: el suelo fijo, o la banda si es más ancha.
 *
 * El tope NO se ata a la banda, pero tampoco puede quedarse por debajo. Son
 * dos mecanismos con trabajos distintos: la BANDA impide vagar hacia las
 * nubes, el TOPE solo suaviza el camino DENTRO de ella. Atados —el fallo
 * histórico era `min(25, banda)`— una pared vertical sale en diagonal, porque
 * el camino no puede seguir a la banda cuando es la banda la que salta.
 * Medido en el Naranjo de Bulnes: el salto máximo quedaba estrangulado en 5
 * filas, exactamente el tope, con un 2.25% de columnas contra él; desatados
 * sube a 13 y la pared sale vertical.
 *
 * **De las dos mitades, a 1536 solo trabaja el SUELO**: el cuanto da banda 5-7
 * y `2*band` queda entre 10 y 14, sin llegar nunca a 25. El término `2*band`
 * está latente y solo manda si el cuanto pasa de 6.25 filas — lo que ocurre al
 * bajar la entrada a 512, que es justo la tentación de rendimiento aquí.
 */
fun pathJumpLimit(band: Int, floor: Int = JUMP_LIMIT_PX): Int = max(floor, 2 * band)

/**
 * Encarece lo que quede a más de `band` filas de la frontera del modelo.
 *
 * ES LO QUE HACE SEGURA LA EVIDENCIA DE BORDE. El borde de una nube es tan
 * fuerte como el de una cresta, así que sin acotar por dónde puede pasar el
 * camino, el término de borde reintroduce justo el fallo que el modelo vino a
 * resolver: la DP se va por arriba siguiendo el contorno de la nube.
 *
 * Con menos de dos columnas utilizables no hay centro que interpolar y se
 * devuelve el coste intacto: sin banda se corre el riesgo de vagar, pero
 * inventar un centro con una sola muestra es peor.
 *
 * Modifica `cost` en sitio y lo devuelve.
 */
fun applyBand(
    cost: DoubleArray,
    width: Int,
    height: Int,
    raw: DoubleArray,
    usable: IntArray,
    band: Int,
    penalty: Double = 10.0,
): DoubleArray {
    if (usable.size < 2) return cost
    val center = interp(width, usable, raw)
    for (c in 0 until width) {
        for (r in 0 until height) {
            if (abs(r - center[c]) > band) cost[r * width + c] += penalty
        }
    }
    return cost
}

/**
 * `np.interp` sobre `0 until width`: lineal entre muestras, CONSTANTE fuera.
 *
 * El extremo importa: numpy no extrapola, repite el valor del borde. Extrapolar
 * mandaría el centro de la banda fuera de la imagen en las columnas de los
 * bordes, que es donde el modelo ya es menos fiable.
 */
internal fun interp(width: Int, xs: IntArray, ys: DoubleArray): DoubleArray {
    val out = DoubleArray(width)
    var i = 0
    for (c in 0 until width) {
        if (c <= xs[0]) { out[c] = ys[xs[0]]; continue }
        if (c >= xs[xs.size - 1]) { out[c] = ys[xs[xs.size - 1]]; continue }
        while (i + 1 < xs.size && xs[i + 1] < c) i++
        val x0 = xs[i]
        val x1 = xs[i + 1]
        val t = if (x1 == x0) 0.0 else (c - x0).toDouble() / (x1 - x0)
        out[c] = ys[x0] * (1.0 - t) + ys[x1] * t
    }
    return out
}
