package peakid.engine.terrain

import kotlin.math.floor
import peakid.engine.VOID_ELEVATION
import peakid.engine.pack.BlockNotFoundException
import peakid.engine.pack.Pack

/**
 * Fuente de terreno para el barrido de horizonte.
 *
 * Son exactamente los dos métodos que el barrido necesita: dónde se acaba la
 * cobertura, y qué altitud hay en un montón de puntos. Existe para que
 * `horizon/` no dependa de UNA implementación — espejo de `DemSource` /
 * `PackSource` en Python. Hoy en el móvil solo hay paquetes, pero un test se
 * apoya en poder sustituirla.
 */
interface TerrainSource {
    /**
     * Índice de la primera muestra EN ORDEN DE DISTANCIA sin cobertura, o -1.
     *
     * Por orden de distancia, NO por grupo de bloque: un rayo puede salir del
     * paquete y volver a entrar por una esquina, y todo lo posterior al primer
     * hueco se descarta igualmente, porque el máximo del rayo ya no es de fiar.
     */
    fun missingIndex(latDeg: DoubleArray, lonDeg: DoubleArray, count: Int): Int

    /**
     * Altitudes interpoladas. `NaN` donde el dato es void.
     *
     * Quien llame debe usar un máximo que ignore NaN: un solo void envenenaría
     * el azimut entero.
     */
    fun elevationsM(
        latDeg: DoubleArray,
        lonDeg: DoubleArray,
        out: DoubleArray,
        count: Int,
    )
}

/** Fuente respaldada por un paquete de región. */
class PackTerrain(private val pack: Pack) : TerrainSource {

    override fun missingIndex(latDeg: DoubleArray, lonDeg: DoubleArray, count: Int): Int {
        for (i in 0 until count) {
            val packed = pack.blockIndexOf(latDeg[i], lonDeg[i])
            if (packed < 0) return i
            if (!pack.recordAt(packed).present) return i
        }
        return -1
    }

    // Memo del último bloque usado. NO es afinado prematuro: las muestras de un
    // rayo van en orden a lo largo del terreno, así que cientos seguidas caen en
    // el mismo bloque. Sin esto, un barrido completo son ~9 millones de búsquedas
    // en tabla hash para resolver siempre lo mismo.
    private var memoPacked = -1
    private var memoArray: ShortArray? = null
    private var memoSide = 0
    private var memoNorth = 0.0
    private var memoWest = 0.0

    override fun elevationsM(
        latDeg: DoubleArray,
        lonDeg: DoubleArray,
        out: DoubleArray,
        count: Int,
    ) {
        for (i in 0 until count) {
            out[i] = elevationM(latDeg[i], lonDeg[i])
        }
    }

    /**
     * Altitud interpolada bilinealmente en un punto. `NaN` si las cuatro
     * esquinas usadas son void.
     *
     * Las esquinas void se EXCLUYEN y se renormalizan los pesos, en vez de
     * mezclar el −32768 en la media: tratarlo como altitud abriría un agujero
     * de 32 kilómetros de profundidad.
     */
    fun elevationM(latDeg: Double, lonDeg: Double): Double {
        val packed = pack.blockIndexOf(latDeg, lonDeg)
        if (packed < 0) {
            throw BlockNotFoundException(
                "el paquete '${pack.name}' no llega a ($latDeg, $lonDeg)",
            )
        }
        if (packed != memoPacked || memoArray == null) {
            val row = packed / pack.cols
            val col = packed % pack.cols
            memoArray = pack.blockArray(row, col)
            val bounds = pack.blockBounds(row, col)
            memoNorth = bounds.first
            memoWest = bounds.second
            memoSide = pack.recordAt(packed).side
            memoPacked = packed
        }
        return bilinear(
            memoArray!!, memoSide, memoNorth, memoWest, pack.blockDeg, latDeg, lonDeg,
        )
    }
}

/**
 * Bilineal dentro de un bloque.
 *
 * Copia deliberada de la del motor con la geometría del bloque: son cinco
 * líneas y así cada una queda bajo el control de su propio test, que es la
 * regla del proyecto para las fórmulas.
 *
 * `n` son los INTERVALOS, no los nodos: un bloque de 901 nodos tiene 900
 * intervalos, y confundirlos desplaza toda la rejilla un nodo.
 *
 * La fila crece hacia el SUR (fila 0 = borde norte), al revés que la latitud.
 * Ignorarlo produce un mapa reflejado que sigue pareciendo un mapa.
 */
internal fun bilinear(
    array: ShortArray,
    side: Int,
    northDeg: Double,
    westDeg: Double,
    blockDeg: Double,
    latDeg: Double,
    lonDeg: Double,
): Double {
    val n = side - 1
    val rowF = n * (northDeg - latDeg) / blockDeg
    val colF = n * (lonDeg - westDeg) / blockDeg
    val row0 = floor(rowF).toInt().coerceIn(0, n)
    val col0 = floor(colF).toInt().coerceIn(0, n)
    val fRow = rowF - row0
    val fCol = colF - col0
    val row1 = minOf(row0 + 1, n)
    val col1 = minOf(col0 + 1, n)

    val v00 = array[row0 * side + col0]
    val v01 = array[row0 * side + col1]
    val v10 = array[row1 * side + col0]
    val v11 = array[row1 * side + col1]

    var weightSum = 0.0
    var acc = 0.0
    var validCount = 0
    var validSum = 0.0

    // se recorren las cuatro esquinas descartando las void y renormalizando
    fun corner(value: Short, weight: Double) {
        if (value == VOID_ELEVATION) return
        validCount++
        validSum += value.toDouble()
        weightSum += weight
        acc += value.toDouble() * weight
    }
    corner(v00, (1 - fRow) * (1 - fCol))
    corner(v01, (1 - fRow) * fCol)
    corner(v10, fRow * (1 - fCol))
    corner(v11, fRow * fCol)

    if (validCount == 0) return Double.NaN
    if (weightSum > 0.0) return acc / weightSum
    // degenerado: el punto cae casi exacto sobre un nodo void y las esquinas
    // válidas tienen peso ~0 -> media simple, mismo criterio que el motor
    return validSum / validCount
}
