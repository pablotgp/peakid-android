package peakid.engine.align

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import peakid.engine.geo.normalizeAzimuthDeg
import peakid.engine.geo.radToDeg
import peakid.engine.geo.wrapDeltaDeg

/**
 * Búsqueda ACOTADA de alineamiento. Puerto de `src/align/search.py`.
 *
 * **Sin el barrido de 360°.** La fase 2 del motor lo midió y concluyó que el
 * sistema no puede encontrar la orientación desde cero y no hace falta que lo
 * haga: su trabajo es CORREGIR una orientación aproximada, y la brújula —
 * aunque falle por quince grados— acota el espacio de sobra. Comprobado además
 * al portar: ningún test del motor ejercita el barrido completo, así que
 * quitarlo no pierde ningún guardián.
 *
 * Esto es un ASISTENTE, no un oráculo. El error en píxeles ordena candidatos
 * DENTRO de una hipótesis, no entre hipótesis: para elegir entre hipótesis hay
 * que mirar los topónimos, que es información que este módulo no usa. Por eso
 * se devuelven varios candidatos separados y por eso [SearchResult.reliable]
 * es una declaración de reservas y no una garantía.
 */

// --- rejilla ---------------------------------------------------------------

const val COARSE_AZ_HALF_DEG: Double = 20.0
const val COARSE_AZ_STEP_DEG: Double = 0.5

/** Incluye teleobjetivo: un 15-20° real caía fuera del rango antiguo (40-75). */
val FOV_RANGE_DEG: ClosedFloatingPointRange<Double> = 10.0..80.0

/**
 * Rejilla GEOMÉTRICA: resolución RELATIVA constante. Con paso lineal, 2.5° es
 * un 3% a 75° pero un 17% a 15°, justo donde más falta precisión.
 */
const val FOV_GRID_RATIO: Double = 1.05

/**
 * Límites FÍSICOS de la cámara, no de comodidad.
 *
 * La inclinación estuvo en 10° y era demasiado estrecha: fotografiar una cima
 * de 2500 m desde un valle a 9 km exige mirar 14.5° hacia arriba. El giro sin
 * acotar dejaba que la búsqueda devolviera −30°, un grado de libertad falso con
 * el que el ajuste se retuerce hasta encajar ruido.
 */
const val PITCH_LIMIT_DEG: Double = 30.0
const val ROLL_LIMIT_DEG: Double = 15.0
const val COARSE_ROLL_HALF_DEG: Double = 3.0

/** El perfil se diezma en la etapa gruesa: para ORDENAR no hace falta 0.2°. */
const val COARSE_PROFILE_STRIDE: Int = 5

/**
 * Un segundo óptimo a más de esta separación en azimut es una hipótesis
 * GENUINAMENTE distinta, no un vecino de rejilla.
 */
const val AMBIGUITY_AZ_DEG: Double = 10.0

/**
 * Si ese óptimo lejano no es peor que el mejor en al menos esta fracción, la
 * búsqueda no distingue entre ambos y no debe presentarse como solución.
 * Calibrado en el motor: con el espacio abierto de par en par el margen mide
 * 0.02 y el caso ES genuinamente ambiguo; acotando con pistas razonables sube a
 * 0.73. Dos regímenes separados por más de un orden de magnitud.
 */
const val AMBIGUITY_MARGIN: Double = 0.25

/** Tope del residuo por columna. */
const val CLIP_PX: Double = 40.0

/** Por encima de esta fracción, el error deja de medir la calidad. */
const val SATURATED_FRACTION: Double = 0.5

// --- resultados ------------------------------------------------------------

class SearchCandidate(
    val params: AlignmentParams,
    /** Desajuste vertical cresta<->línea. MAGNITUD DE RANKING. */
    val errorPx: Double,
    /** El mismo error en grados, solo para leerlo. */
    val errorDeg: Double,
    /** Fracción de columnas de cresta bajo la línea proyectada. */
    val coverage: Double,
    /** Columnas cuyo residuo toca el recorte. */
    val saturatedFraction: Double = 0.0,
) {
    /**
     * El error ya no mide la calidad, solo dice "muy mal".
     *
     * Sin esto un desajuste de 350 px se lee como 40 y parece a un paso de
     * encajar. Pasó de verdad: se informó un ajuste como "38 px, casi bueno"
     * cuando la línea proyectada iba a 350 px de la cresta.
     */
    val saturated: Boolean get() = saturatedFraction >= SATURATED_FRACTION
}

/** Resultado CON sus reservas: un número sin ellas engaña más que ayuda. */
class SearchResult(
    val candidates: List<SearchCandidate>,
    /** Hay otro óptimo lejano casi igual de bueno. */
    val ambiguous: Boolean,
    /** Cuánto peor es ese óptimo lejano, en fracción. */
    val ambiguityMargin: Double,
    val alternative: SearchCandidate?,
    /** El óptimo se apoya en el borde del rango de FOV explorado. */
    val fovAtEdge: Boolean,
    /** Ídem en azimut: el verdadero puede estar fuera. */
    val azAtEdge: Boolean,
    val azRangeDeg: Pair<Double, Double>,
    val fovRangeDeg: Pair<Double, Double>,
) {
    /**
     * Sin reservas DETECTABLES por este módulo.
     *
     * NO es una garantía de que el resultado sea correcto. La detección de
     * ambigüedad contrasta el mejor contra UN alternativo lejano y no cubre un
     * continuo de óptimos parecidos, así que un mínimo global en el sitio
     * equivocado puede salir "fiable". La comprobación que zanja es geográfica
     * —qué cima cae sobre qué bulto— y esa la hace el usuario.
     */
    val reliable: Boolean
        get() = candidates.isNotEmpty() && !(ambiguous || fovAtEdge || azAtEdge)
}

// --- métrica ---------------------------------------------------------------

/**
 * Media del residuo absoluto, RECORTADO.
 *
 * Media y no mediana: sobre filas cuantizadas por el submuestreo del detector,
 * la mediana forma mesetas y los empates se resuelven por orden de iteración,
 * lo que sesga la búsqueda un paso de rejilla.
 *
 * CUIDADO al leer el número: el recorte satura. Un ajuste con residuos de
 * 350 px da ~40 igual que uno con residuos de 45, así que un error cercano a
 * [CLIP_PX] NO significa "casi bueno" sino "sin medir".
 */
fun clippedMeanAbs(residualPx: DoubleArray, count: Int = residualPx.size): Double {
    if (count == 0) return 0.0
    var acc = 0.0
    for (i in 0 until count) acc += min(abs(residualPx[i]), CLIP_PX)
    return acc / count
}

/** Fracción de columnas cuyo residuo toca el tope del recorte. */
fun saturatedFraction(residualPx: DoubleArray, count: Int = residualPx.size): Double {
    if (count == 0) return 1.0
    var n = 0
    for (i in 0 until count) if (abs(residualPx[i]) >= CLIP_PX) n++
    return n.toDouble() / count
}

private fun median(values: DoubleArray, count: Int): Double {
    val copy = values.copyOf(count)
    copy.sort()
    val mid = count / 2
    return if (count % 2 == 1) copy[mid] else (copy[mid - 1] + copy[mid]) / 2.0
}

/**
 * Recta robusta `residuo = a + b·(x − W/2)`.
 *
 * La ordenada `a` es el desplazamiento vertical (inclinación) y la pendiente
 * `b` el giro EN RADIANES. Las columnas que se apartan más de 3·MAD se
 * descartan antes de ajustar: un tejado o un arbusto detectado como cresta no
 * debe torcer la recta.
 *
 * Devuelve `null` si no hay bastantes columnas.
 */
internal fun fitPitchRoll(
    residualPx: DoubleArray,
    offsetPx: DoubleArray,
    count: Int,
    minColumns: Int = 8,
): Triple<Double, Double, Double>? {
    if (count < minColumns) return null
    val med = median(residualPx, count)
    val deviation = DoubleArray(count) { abs(residualPx[it] - med) }
    val scale = 1.4826 * median(deviation, count)
    var keep = BooleanArray(count) { scale <= 0.0 || deviation[it] <= 3.0 * scale }
    var kept = keep.count { it }
    if (kept < minColumns) {
        keep = BooleanArray(count) { true }
        kept = count
    }

    // mínimos cuadrados de dos parámetros en forma cerrada: no hace falta un
    // solver general y así queda bajo el control de su propio test
    var sx = 0.0
    var sy = 0.0
    for (i in 0 until count) if (keep[i]) { sx += offsetPx[i]; sy += residualPx[i] }
    val mx = sx / kept
    val my = sy / kept
    var sxx = 0.0
    var sxy = 0.0
    for (i in 0 until count) if (keep[i]) {
        val dx = offsetPx[i] - mx
        sxx += dx * dx
        sxy += dx * (residualPx[i] - my)
    }
    val slope = if (sxx == 0.0) 0.0 else sxy / sxx
    val intercept = my - slope * mx

    val resid = DoubleArray(count) { residualPx[it] - (intercept + slope * offsetPx[it]) }
    return Triple(intercept, slope, clippedMeanAbs(resid, count))
}

// --- inclinación y giro en forma cerrada -----------------------------------

/**
 * Inclinación y giro en forma cerrada, fijados azimut y FOV.
 *
 * Modelo de primer orden, verificado numéricamente contra la proyección:
 *
 *     y(pitch, roll) − y(0, 0)  ≈  f·tan(pitch) + (x − W/2)·roll_rad
 *
 * o sea que el residuo vertical entre la cresta detectada y la línea proyectada
 * es una RECTA en x: su ordenada da la inclinación y su pendiente el giro. Dos
 * parámetros que se resuelven de una vez en lugar de buscarse, lo que elimina
 * dos dimensiones enteras de la rejilla.
 *
 * Se itera (por defecto 2) porque el modelo es de primer orden y a 8°/5° deja
 * ~0.1° de error; con una segunda pasada el residuo del modelo desaparece.
 *
 * Devuelve `null` si no quedan bastantes columnas utilizables: **mejor no
 * resolver que resolver con cuatro puntos.**
 */
fun solvePitchRoll(
    azimuthsDeg: DoubleArray,
    elevationsDeg: DoubleArray,
    params: AlignmentParams,
    skylineColsPx: DoubleArray,
    skylineRowsPx: DoubleArray,
    widthPx: Int,
    heightPx: Int,
    iterations: Int = 2,
    minColumns: Int = 20,
): Pair<Double, Double>? {
    val n = azimuthsDeg.size
    val cols = skylineColsPx
    val rows = skylineRowsPx
    val focal = focalPx(widthPx, params.hfovDeg)
    var pitchDeg = params.pitchDeg
    var rollDeg = params.rollDeg
    var used = 0

    val x = DoubleArray(n)
    val y = DoubleArray(n)
    val usable = BooleanArray(n)
    val yLine = DoubleArray(cols.size)
    val residual = DoubleArray(cols.size)
    val offset = DoubleArray(cols.size)

    repeat(max(1, iterations)) {
        val current = AlignmentParams(params.azimuthDeg, params.hfovDeg, pitchDeg, rollDeg)
        projectProfile(azimuthsDeg, elevationsDeg, current, widthPx, heightPx, x, y, usable, n)
        projectedYPerColumn(x, y, usable, cols, yLine, n, cols.size)

        var m = 0
        for (i in cols.indices) {
            if (yLine[i].isNaN()) continue
            residual[m] = rows[i] - yLine[i]
            offset[m] = cols[i] - widthPx / 2.0
            m++
        }
        if (m < minColumns) return null

        val fit = fitPitchRoll(residual, offset, m, minColumns) ?: return null
        used = m
        pitchDeg += radToDeg(atan(fit.first / focal))
        rollDeg += radToDeg(fit.second)
        // acotado a lo que una cámara puede hacer
        pitchDeg = pitchDeg.coerceIn(-PITCH_LIMIT_DEG, PITCH_LIMIT_DEG)
        rollDeg = rollDeg.coerceIn(-ROLL_LIMIT_DEG, ROLL_LIMIT_DEG)
    }
    if (used < minColumns) return null
    return Pair(pitchDeg, rollDeg)
}

// --- puntuación ------------------------------------------------------------

private class Score(val errorPx: Double, val coverage: Double, val saturated: Double)

private fun scoreExact(
    azimuthsDeg: DoubleArray,
    elevationsDeg: DoubleArray,
    params: AlignmentParams,
    cols: DoubleArray,
    rows: DoubleArray,
    widthPx: Int,
    heightPx: Int,
    minColumns: Int,
): Score? {
    val projected = projectProfile(azimuthsDeg, elevationsDeg, params, widthPx, heightPx)
    val yLine = projectedYPerColumn(projected.xPx, projected.yPx, projected.usable, cols)
    val residual = DoubleArray(cols.size)
    var m = 0
    for (i in cols.indices) {
        if (yLine[i].isNaN()) continue
        residual[m++] = rows[i] - yLine[i]
    }
    if (m < minColumns) return null
    return Score(
        clippedMeanAbs(residual, m),
        m.toDouble() / cols.size,
        saturatedFraction(residual, m),
    )
}

private fun pxToDeg(errPx: Double, params: AlignmentParams, widthPx: Int): Double =
    radToDeg(atan(errPx / focalPx(widthPx, params.hfovDeg)))

// --- rejilla de FOV --------------------------------------------------------

/**
 * Valores de FOV a explorar.
 *
 * Sin pista: rejilla GEOMÉTRICA de 10 a 80° con ratio constante, que da
 * resolución RELATIVA constante. Con pista: rejilla lineal fina dentro del
 * margen, porque quien sabe el campo aproximado quiere resolución absoluta.
 */
fun buildFovGrid(
    fovHintDeg: Double? = null,
    fovMarginDeg: Double = 10.0,
    ratio: Double = FOV_GRID_RATIO,
): DoubleArray {
    if (fovHintDeg != null) {
        val lo = max(fovHintDeg - fovMarginDeg, FOV_RANGE_DEG.start)
        val hi = min(fovHintDeg + fovMarginDeg, FOV_RANGE_DEG.endInclusive)
        val step = max(0.5, (hi - lo) / 24.0)
        val n = ((hi - lo) / step).toInt() + 1
        return DoubleArray(n) { lo + it * step }
    }
    val lo = FOV_RANGE_DEG.start
    val hi = FOV_RANGE_DEG.endInclusive
    val count = ceil(ln(hi / lo) / ln(ratio)).toInt()
    return DoubleArray(count + 1) { min(lo * ratio.pow(it), hi) }
}

/**
 * Arco marcado por el usuario -> rangos de los deslizadores.
 *
 * Devuelve (centro, ancho, lo, hi, fovLo, fovHi).
 *
 * Un arco de MÁS DE 180° se interpreta como el COMPLEMENTARIO, es decir el que
 * cruza el norte: arrastrar de 10° a 350° selecciona los 20° de enfrente, no
 * los 340° del medio. Es inequívoco SOLO porque el FOV tope en 80°: ninguna
 * foto abarca 340°. Si ese tope subiera por encima de 180°, la regla deja de
 * valer.
 *
 * Su consumidor es la UI de ajuste manual (fase 5); aquí vive porque pertenece
 * a este módulo.
 */
fun sectorToRanges(startDeg: Double, endDeg: Double): DoubleArray {
    val start = normalizeAzimuthDeg(startDeg)
    val end = normalizeAzimuthDeg(endDeg)
    var lo = min(start, end)
    var hi = max(start, end)
    var width = hi - lo
    if (width > 180.0) {
        val newLo = hi
        hi = lo + 360.0
        lo = newLo
        width = hi - lo
    }
    width = max(width, 1.0)
    val center = normalizeAzimuthDeg(lo + width / 2.0)
    val fovLo = max(width * 0.5, FOV_RANGE_DEG.start)
    val fovHi = min(max(width * 2.0, fovLo + 1.0), FOV_RANGE_DEG.endInclusive)
    return doubleArrayOf(center, width, lo, hi, fovLo, fovHi)
}

private fun azSeparation(a: AlignmentParams, b: AlignmentParams): Double =
    abs(wrapDeltaDeg(a.azimuthDeg - b.azimuthDeg))

private fun round2(v: Double): Double = round(v * 100.0) / 100.0

// --- la búsqueda -----------------------------------------------------------

private fun refine(
    azimuthsDeg: DoubleArray,
    elevationsDeg: DoubleArray,
    params: AlignmentParams,
    cols: DoubleArray,
    rows: DoubleArray,
    widthPx: Int,
    heightPx: Int,
    minColumns: Int,
    azStep: Double,
    fovSpan: Double,
    fovLo: Double,
    fovHi: Double,
): AlignmentParams {
    var best = params
    var bestErr = Double.MAX_VALUE
    val azFrom = params.azimuthDeg - azStep
    val azTo = params.azimuthDeg + azStep
    val azInc = azStep / 8.0
    val fovInc = max(fovSpan / 5.0, 0.1)

    var az = azFrom
    while (az <= azTo + 1e-9) {
        var fov = params.hfovDeg - fovSpan
        while (fov <= params.hfovDeg + fovSpan + 1e-9) {
            val clamped = fov.coerceIn(fovLo, fovHi)
            var trial = AlignmentParams(
                normalizeAzimuthDeg(az), clamped, params.pitchDeg, params.rollDeg,
            )
            val solved = solvePitchRoll(
                azimuthsDeg, elevationsDeg, trial, cols, rows, widthPx, heightPx,
                minColumns = minColumns,
            )
            if (solved != null) {
                trial = AlignmentParams(trial.azimuthDeg, trial.hfovDeg, solved.first, solved.second)
            }
            val scored = scoreExact(
                azimuthsDeg, elevationsDeg, trial, cols, rows, widthPx, heightPx, minColumns,
            )
            if (scored != null && scored.errorPx < bestErr) {
                bestErr = scored.errorPx
                best = trial
            }
            fov += fovInc
        }
        az += azInc
    }
    return AlignmentParams(
        round2(best.azimuthDeg), round2(best.hfovDeg),
        round2(best.pitchDeg), round2(best.rollDeg),
    )
}

/**
 * Fuerza bruta en dos etapas sobre azimut y FOV.
 *
 * NI la inclinación NI el giro se rejillan: ambos se resuelven en forma cerrada
 * a partir del residuo (ver [solvePitchRoll]), lo que elimina dos dimensiones
 * enteras. La etapa fina rejilla otra vez alrededor de CADA candidato.
 *
 * Devuelve hasta `maxCandidates` candidatos SEPARADOS ENTRE SÍ al menos
 * `minSeparationDeg` en azimut — no los mejores absolutos, que suelen ser
 * variaciones del mismo óptimo y no le dan a elegir nada al usuario.
 */
fun searchAlignment(
    azimuthsDeg: DoubleArray,
    elevationsDeg: DoubleArray,
    skylineColsPx: DoubleArray,
    skylineRowsPx: DoubleArray,
    widthPx: Int,
    heightPx: Int,
    centerAzDeg: Double,
    azMarginDeg: Double = COARSE_AZ_HALF_DEG,
    fovHintDeg: Double? = null,
    fovMarginDeg: Double = 10.0,
    maxColumns: Int = 400,
    coarseColumns: Int = 200,
    maxCandidates: Int = 5,
    minSeparationDeg: Double = AMBIGUITY_AZ_DEG,
): SearchResult {
    fun thin(count: Int): Pair<DoubleArray, DoubleArray> {
        if (skylineColsPx.size <= count) return Pair(skylineColsPx, skylineRowsPx)
        val keep = IntArray(count) {
            (it.toDouble() * (skylineColsPx.size - 1) / (count - 1)).toInt()
        }
        return Pair(
            DoubleArray(count) { skylineColsPx[keep[it]] },
            DoubleArray(count) { skylineRowsPx[keep[it]] },
        )
    }

    val (coarseCols, coarseRows) = thin(coarseColumns)
    val (cols, rows) = thin(maxColumns)
    val minColumns = max(20, (0.3 * cols.size).toInt())
    val coarseMinColumns = max(15, (0.3 * coarseCols.size).toInt())

    val strideN = (azimuthsDeg.size + COARSE_PROFILE_STRIDE - 1) / COARSE_PROFILE_STRIDE
    val coarseAz = DoubleArray(strideN) { azimuthsDeg[it * COARSE_PROFILE_STRIDE] }
    val coarseElev = DoubleArray(strideN) { elevationsDeg[it * COARSE_PROFILE_STRIDE] }

    // --- etapa gruesa ---
    val azCount = ((2.0 * azMarginDeg) / COARSE_AZ_STEP_DEG).toInt() + 1
    val azGrid = DoubleArray(azCount) { centerAzDeg - azMarginDeg + it * COARSE_AZ_STEP_DEG }
    val fovGrid = buildFovGrid(fovHintDeg, fovMarginDeg)

    val coarse = ArrayList<Triple<Double, Double, AlignmentParams>>()
    val px = DoubleArray(strideN)
    val py = DoubleArray(strideN)
    val pu = BooleanArray(strideN)
    val yLine = DoubleArray(coarseCols.size)
    val residual = DoubleArray(coarseCols.size)
    val offset = DoubleArray(coarseCols.size)

    for (fov in fovGrid) {
        val focal = focalPx(widthPx, fov)
        for (azRaw in azGrid) {
            val base = AlignmentParams(normalizeAzimuthDeg(azRaw), fov, 0.0, 0.0)
            projectProfile(coarseAz, coarseElev, base, widthPx, heightPx, px, py, pu, strideN)
            projectedYPerColumn(px, py, pu, coarseCols, yLine, strideN, coarseCols.size)
            var m = 0
            for (i in coarseCols.indices) {
                if (yLine[i].isNaN()) continue
                residual[m] = coarseRows[i] - yLine[i]
                offset[m] = coarseCols[i] - widthPx / 2.0
                m++
            }
            if (m < coarseMinColumns) continue
            val fit = fitPitchRoll(residual, offset, m) ?: continue
            val pitch = radToDeg(atan(fit.first / focal))
                .coerceIn(-PITCH_LIMIT_DEG, PITCH_LIMIT_DEG)
            val roll = radToDeg(fit.second)
                .coerceIn(-COARSE_ROLL_HALF_DEG, COARSE_ROLL_HALF_DEG)
            coarse.add(
                Triple(
                    fit.third, m.toDouble() / coarseCols.size,
                    AlignmentParams(
                        normalizeAzimuthDeg(azRaw), fov,
                        round(pitch * 1000.0) / 1000.0, round(roll * 1000.0) / 1000.0,
                    ),
                ),
            )
        }
    }

    val azRange = Pair(azGrid.first(), azGrid.last())
    val fovRange = Pair(fovGrid.first(), fovGrid.last())
    if (coarse.isEmpty()) {
        return SearchResult(
            emptyList(), false, Double.POSITIVE_INFINITY, null, false, false, azRange, fovRange,
        )
    }
    coarse.sortBy { it.first }

    // candidatos SEPARADOS entre sí, no los mejores absolutos
    val top = ArrayList<AlignmentParams>()
    for ((_, _, p) in coarse) {
        if (top.all { azSeparation(p, it) >= minSeparationDeg }) top.add(p)
        if (top.size == maxCandidates) break
    }

    // SATURACIÓN: si el óptimo se apoya en un extremo de lo explorado, el
    // verdadero puede estar FUERA y la búsqueda no tiene forma de saberlo.
    val coarseBest = coarse.first().third
    val fovAtEdge = coarseBest.hfovDeg <= fovGrid.first() + 1e-6 ||
        coarseBest.hfovDeg >= fovGrid.last() - 1e-6
    val azOffset = abs(wrapDeltaDeg(coarseBest.azimuthDeg - centerAzDeg))
    val azAtEdge = azOffset >= azMarginDeg - COARSE_AZ_STEP_DEG

    var alternativeParams = coarse.firstOrNull {
        azSeparation(it.third, coarseBest) > AMBIGUITY_AZ_DEG
    }?.third

    // --- etapa fina: TODOS los candidatos, no solo el mejor ---
    // el FOV se acota al rango REALMENTE explorado: sin esto la etapa fina se
    // escapaba por encima del máximo, reportando un valor fuera del espacio que
    // la propia salida dice haber explorado
    val fovLo = fovGrid.first()
    val fovHi = fovGrid.last()
    val fovSpan = max(fovHi - fovLo, 1.0) * 0.08
    val refined = top.map {
        refine(
            azimuthsDeg, elevationsDeg, it, cols, rows, widthPx, heightPx,
            minColumns, COARSE_AZ_STEP_DEG, fovSpan, fovLo, fovHi,
        )
    }

    // TODOS con la MISMA métrica exacta antes de ordenar: mezclar un refinado
    // con estimaciones de la rejilla gruesa daba una lista cuyo orden no se
    // correspondía con los errores mostrados
    val scored = ArrayList<SearchCandidate>()
    for (p in refined) {
        val s = scoreExact(
            azimuthsDeg, elevationsDeg, p, cols, rows, widthPx, heightPx, minColumns,
        ) ?: continue
        scored.add(
            SearchCandidate(p, s.errorPx, pxToDeg(s.errorPx, p, widthPx), s.coverage, s.saturated),
        )
    }
    scored.sortBy { it.errorPx }

    var alternative: SearchCandidate? = null
    var margin = Double.POSITIVE_INFINITY
    if (alternativeParams != null && scored.isNotEmpty()) {
        // la alternativa se REFINA igual que el mejor antes de puntuarla:
        // comparar un refinado contra una estimación de rejilla infla el margen
        // y hace pasar por inequívoco lo que no lo es
        alternativeParams = refine(
            azimuthsDeg, elevationsDeg, alternativeParams, cols, rows, widthPx, heightPx,
            minColumns, COARSE_AZ_STEP_DEG, fovSpan, fovLo, fovHi,
        )
        val s = scoreExact(
            azimuthsDeg, elevationsDeg, alternativeParams, cols, rows,
            widthPx, heightPx, minColumns,
        )
        if (s != null) {
            alternative = SearchCandidate(
                alternativeParams, s.errorPx,
                pxToDeg(s.errorPx, alternativeParams, widthPx), s.coverage, s.saturated,
            )
            val bestPx = scored.first().errorPx
            margin = if (bestPx > 0.0) (alternative.errorPx - bestPx) / bestPx
            else Double.POSITIVE_INFINITY
        }
    }

    return SearchResult(
        candidates = scored,
        ambiguous = margin < AMBIGUITY_MARGIN,
        ambiguityMargin = margin,
        alternative = alternative,
        fovAtEdge = fovAtEdge,
        azAtEdge = azAtEdge,
        azRangeDeg = azRange,
        fovRangeDeg = fovRange,
    )
}
