package peakid.engine.skyline

/**
 * Detector de cresta: segmentación de cielo con modelo, y la DP con banda
 * encima. Puerto del cierre de `build_model_detector` del motor.
 *
 * **Todo lo que no es la llamada a ONNX vive aquí**, en código común: el
 * montaje del coste, la banda, el tope de salto y el camino. Así queda bajo
 * test multiplataforma y la parte específica de plataforma se reduce a "dame
 * la probabilidad de cielo", que es lo único que ORT aporta.
 */

/** Probabilidad de cielo `s(r,c) ∈ [0,1]` sobre la imagen de trabajo. */
fun interface SkyProbability {
    /**
     * @param rgb imagen de trabajo, `[fila * width * 3 + columna * 3 + canal]`,
     *   canales en 0..255
     * @return `s(r,c)` al MISMO tamaño que la entrada
     */
    fun compute(rgb: DoubleArray, width: Int, height: Int, longSide: Int): DoubleArray
}

/**
 * Cresta detectada, en píxeles de la FOTO completa.
 *
 * `valid` NO quiere decir "encontré cresta": la DP siempre devuelve un camino
 * entero, así que quiere decir **"aquí el camino es fiable"**. Sin ese matiz la
 * cobertura saldría del 100% siempre, basura incluida, y se perdería la señal
 * que avisa de que una foto no sirve.
 */
class Crest(
    val columnsPx: DoubleArray,
    val rowsPx: DoubleArray,
    val valid: BooleanArray,
)

/** Margen mínimo para dar por fiable una columna. */
const val MIN_MARGIN: Double = 0.02

/** Reloj de pared en milisegundos, sin depender de la plataforma. */
internal expect fun ahoraMs(): Long

/**
 * Detecta la cresta sobre una imagen de trabajo YA DECIMADA.
 *
 * La decimación la hace quien tiene los píxeles, y a propósito: la foto
 * completa como `DoubleArray` son cientos de MB en un móvil, y el motor no la
 * necesita entera. El submuestreo es `photo[::step, ::step]` — un píxel de
 * cada `step`, SIN promediar. Filtrar ahí daría una imagen más bonita y una
 * cresta distinta de la del motor.
 *
 * @param jumpLimitOverride fuerza el tope en vez de derivarlo de la banda.
 *   Solo para comparar contra el comportamiento estrangulado en un test; en
 *   producción se deja nulo y manda [pathJumpLimit].
 */
fun detectSkyline(
    work: DoubleArray,
    workWidth: Int,
    workHeight: Int,
    step: Int,
    sky: SkyProbability,
    longSide: Int = INPUT_LONG_SIDE,
    jumpLimitOverride: Int? = null,
    /**
     * Reloj por etapa, opcional. Existe porque medir "inferencia + DP" en un
     * solo número no permite decidir nada: las dos tienen palancas distintas
     * —la resolución del modelo por un lado, el bucle del camino por otro— y
     * agrupadas cualquier optimización sería a ciegas.
     */
    reloj: ((etapa: String, ms: Long) -> Unit)? = null,
): Crest {
    val t0 = ahoraMs()
    val s = sky.compute(work, workWidth, workHeight, longSide)
    reloj?.invoke("inferencia", ahoraMs() - t0)

    // frontera CRUDA del modelo: primera fila que deja de ser cielo
    val raw = DoubleArray(workWidth)
    val found = BooleanArray(workWidth)
    val hasSky = BooleanArray(workWidth)
    for (c in 0 until workWidth) {
        var primeraNoCielo = -1
        var vioCielo = false
        for (r in 0 until workHeight) {
            val esCielo = s[r * workWidth + c] >= 0.5
            if (esCielo) vioCielo = true
            if (!esCielo && primeraNoCielo < 0) primeraNoCielo = r
        }
        found[c] = primeraNoCielo >= 0
        hasSky[c] = vioCielo
        // np.argmax sobre todo falso devuelve 0, no -1: mismo convenio
        raw[c] = (if (primeraNoCielo < 0) 0 else primeraNoCielo).toDouble()
    }

    val tCoste = ahoraMs()
    // cuanto de la máscara EN FILAS DE TRABAJO, y de ahí la banda
    val quantum = maskQuantumRows(workWidth, workHeight, longSide)
    val band = bandWidth(quantum)

    // coste de región, normalizado a [0,1], más el de borde con su peso
    val cost = boundaryCost(s, workWidth, workHeight)
    var lo = Double.MAX_VALUE
    var hi = -Double.MAX_VALUE
    for (v in cost) { if (v < lo) lo = v; if (v > hi) hi = v }
    val span = hi - lo
    val denom = if (span > 1e-9) span else 1e-9
    for (i in cost.indices) cost[i] = (cost[i] - lo) / denom

    val edge = edgeCost(work, workWidth, workHeight)
    for (i in cost.indices) cost[i] += EDGE_WEIGHT * edge[i]

    val usable = ArrayList<Int>()
    for (c in 0 until workWidth) if (found[c] && hasSky[c]) usable.add(c)
    applyBand(cost, workWidth, workHeight, raw, usable.toIntArray(), band)

    reloj?.invoke("coste", ahoraMs() - tCoste)

    val tCamino = ahoraMs()
    val limite = jumpLimitOverride ?: pathJumpLimit(band)
    val path = bestPath(cost, workWidth, workHeight, jumpLimit = limite)
    reloj?.invoke("camino", ahoraMs() - tCamino)

    val rows = DoubleArray(workWidth) { path.rows[it].toDouble() }
    var valid = BooleanArray(workWidth) { path.margin[it] >= MIN_MARGIN && hasSky[it] }
    if (valid.count { it } >= 5) {
        valid = dropShortSegments(rows, valid, workHeight, workWidth)
    }

    return Crest(
        columnsPx = DoubleArray(workWidth) { it.toDouble() * step + step / 2.0 },
        rowsPx = DoubleArray(workWidth) { rows[it] * step + step / 2.0 },
        valid = valid,
    )
}

/**
 * Parte la cresta por escalones grandes y tira los tramos demasiado cortos
 * para ser relieve.
 *
 * Postes, farolas y ramas en primer plano rompen la cresta con un escalón de
 * cientos de píxeles y envenenan el ajuste de inclinación y giro: unas pocas
 * columnas que no son horizonte tuercen la recta.
 *
 * **Con salvaguarda**: si el criterio se llevara casi todo, es que la foto es
 * así de accidentada, y quedarse sin columnas es peor que conservarlas.
 */
internal fun dropShortSegments(
    rows: DoubleArray,
    valid: BooleanArray,
    height: Int,
    width: Int,
): BooleanArray {
    val salto = maxOf(4.0, height / 40.0)
    val minimo = maxOf(3, width / 50)
    val out = valid.copyOf()

    var inicio = -1
    val tramos = ArrayList<Pair<Int, Int>>()
    for (c in 0 until width) {
        val rompe = !valid[c] ||
            (inicio >= 0 && c > inicio && kotlin.math.abs(rows[c] - rows[c - 1]) > salto)
        if (rompe) {
            if (inicio >= 0) tramos.add(Pair(inicio, c))
            inicio = if (valid[c]) c else -1
        } else if (inicio < 0 && valid[c]) {
            inicio = c
        }
    }
    if (inicio >= 0) tramos.add(Pair(inicio, width))

    for ((a, b) in tramos) {
        if (b - a < minimo) for (c in a until b) out[c] = false
    }
    // salvaguarda: si se ha llevado más del 80%, deshacer
    if (out.count { it } < 0.2 * valid.count { it }) return valid
    return out
}
