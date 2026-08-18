package peakid.engine.pack

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.floor
import peakid.engine.AZIMUTH_STEP_DEG
import peakid.engine.MAX_DISTANCE_M
import peakid.engine.RELOCATE_RADIUS_M
import peakid.engine.STEP_M
import peakid.engine.SUMMIT_MARGIN_M
import peakid.engine.geo.K_REFRACTION
import peakid.engine.geo.R_EARTH_M
import peakid.engine.geo.haversineM

/**
 * Lectura de paquetes de región: terreno escalonado + cimas + cobertura.
 *
 * Puerto de `src/pack/__init__.py` del motor. Un paquete es lo que consume la
 * app: el terreno ya recortado al radio útil con resolución escalonada por
 * distancia, las cimas ya recolocadas y con la altitud decidida, y —lo que lo
 * distingue de un simple recorte— una declaración explícita de DÓNDE NO HAY
 * DATOS, para que el motor siga pudiendo devolver UNKNOWN en vez de inventarse
 * un VISIBLE.
 *
 * Convenios heredados sin cambiar: fila 0 = borde NORTE del bloque, columna 0 =
 * borde OESTE; `-32768` es void y nunca una altitud; coordenadas (lat, lon).
 * Lo que SÍ cambia respecto a los `.hgt` es el orden de bytes (ver PackFormat).
 *
 * ## TRES radios, y no son intercambiables
 *
 * - `terrainRadiusM`: hasta dónde llega el terreno.
 * - `peaksRadiusM`: hasta dónde llega el registro de cimas. Puede ser menor, y
 *   en esa corona el paquete NO SABE si hay cimas (ver [PeakQuery]).
 * - `observerRadiusM`: **dónde puede PONERSE el observador.** El más fácil de
 *   pasar por alto y el que más duele: como el tier se asigna por distancia al
 *   CENTRO, un observador lejos del centro tiene su terreno CERCANO en
 *   resolución gruesa, y con el ojo bajo el horizonte lo domina justamente el
 *   terreno cercano. Medido en el paquete de la Axarquía: a 0, 10, 20 y 30 km
 *   del centro el perfil coincide exacto con el del DEM; a 45 km se descuadra
 *   0.69°, y a 104 km, 4.08°. Un paquete sirve a su comarca, no a todo su
 *   radio de terreno.
 */

const val PACK_FORMAT_VERSION: Int = 1

private const val COVERAGE_MAGIC = "PKCV"
private const val PEAKS_MAGIC = "PKPK"

private const val COVERAGE_HEADER_SIZE = 16
private const val COVERAGE_RECORD_SIZE = 12
private const val PEAKS_HEADER_SIZE = 52
private const val PEAK_RECORD_SIZE = 40

/** El `dtype` que este lector sabe leer: entero de 16 bits little-endian. */
private const val EXPECTED_DTYPE = "<i2"

enum class Coverage { PRESENT, ABSENT }

data class Peak(
    val osmId: Long,
    val name: String?,
    val latDeg: Double,
    val lonDeg: Double,
    val eleM: Double,
    val altName: String? = null,
    val heightFromDem: Boolean = false,
)

/**
 * Resultado de preguntar por cimas, CON su propia cobertura declarada.
 *
 * `complete = false` significa "la zona preguntada se sale del registro de
 * cimas del paquete". Existe para que una lista vacía NUNCA pueda leerse como
 * "aquí no hay cimas" cuando lo cierto es "aquí no lo sé". Misma idea que el
 * truncamiento de un rayo, y por el mismo motivo: un hueco de información
 * disfrazado de hecho es el peor fallo posible en una herramienta de
 * identificación.
 */
data class PeakQuery(
    val peaks: List<Peak>,
    val registeredRadiusM: Double,
    val complete: Boolean,
)

internal class BlockRecord(
    val present: Boolean,
    val tier: Int,
    val side: Int,
    val offset: Long,
)

@Serializable
private data class ManifestCenter(
    @SerialName("lat_deg") val latDeg: Double,
    @SerialName("lon_deg") val lonDeg: Double,
)

@Serializable
private data class ManifestGrid(
    @SerialName("block_deg") val blockDeg: Double,
    @SerialName("lat_max_deg") val latMaxDeg: Double,
    @SerialName("lon_min_deg") val lonMinDeg: Double,
    val rows: Int,
    val cols: Int,
)

@Serializable
private data class ManifestTier(
    val tier: Int,
    @SerialName("max_distance_m") val maxDistanceM: Double,
    val arcsec: Int,
    val side: Int,
)

@Serializable
private data class ManifestElevation(
    val dtype: String,
    val void: Int,
)

@Serializable
private data class Manifest(
    @SerialName("format_version") val formatVersion: Int,
    val name: String? = null,
    val center: ManifestCenter,
    @SerialName("terrain_radius_m") val terrainRadiusM: Double,
    @SerialName("observer_radius_m") val observerRadiusM: Double? = null,
    val grid: ManifestGrid,
    val tiers: List<ManifestTier> = emptyList(),
    val elevation: ManifestElevation,
    val engine: Map<String, Double> = emptyMap(),
)

private val json = Json { ignoreUnknownKeys = true }

/**
 * Constantes que el paquete transporta Y este lector verifica.
 *
 * No basta con llevarlas: un paquete construido con k = 0.13 leído por un motor
 * con otro valor da resultados plausibles y equivocados. La comprobación es la
 * defensa explícita contra el peor bug posible del proyecto, y por eso falla en
 * la apertura en vez de degradar en silencio.
 */
val ENGINE_CONSTANTS: Map<String, Double> = mapOf(
    "R_EARTH_M" to R_EARTH_M,
    "K_REFRACTION" to K_REFRACTION,
    "STEP_M" to STEP_M,
    "MAX_DISTANCE_M" to MAX_DISTANCE_M,
    "AZIMUTH_STEP_DEG" to AZIMUTH_STEP_DEG,
    "SUMMIT_MARGIN_M" to SUMMIT_MARGIN_M,
    "RELOCATE_RADIUS_M" to RELOCATE_RADIUS_M,
)

class Pack internal constructor(
    val name: String,
    val centerLatDeg: Double,
    val centerLonDeg: Double,
    val terrainRadiusM: Double,
    val peaksRadiusM: Double,
    val observerRadiusM: Double,
    val blockDeg: Double,
    val latMaxDeg: Double,
    val lonMinDeg: Double,
    val rows: Int,
    val cols: Int,
    val peaks: List<Peak>,
    internal val coverage: Array<BlockRecord>,
    internal val terrain: ByteSource,
    private val blockCacheLimit: Int = 12,
) {
    // Caché LRU de bloques ya decodificados. Un barrido de 1800 rayos vuelve a
    // pisar los mismos bloques miles de veces, y recomponer 811 801 enteros
    // little-endian en cada consulta sería el coste dominante.
    private val blockCache = LinkedHashMap<Int, ShortArray>()

    internal fun record(row: Int, col: Int): BlockRecord = coverage[row * cols + col]

    /** El registro por índice ya empaquetado. Ver [blockIndexOf]. */
    internal fun recordAt(packed: Int): BlockRecord = coverage[packed]

    /**
     * ¿Está el observador dentro del radio que este paquete promete servir?
     *
     * Es el criterio de selección y de descarga, y NO es "el paquete contiene
     * esa montaña": contener el terreno de una cima no habilita a mirarla desde
     * cualquier punto del paquete.
     */
    fun servesObserver(latDeg: Double, lonDeg: Double): Boolean =
        haversineM(latDeg, lonDeg, centerLatDeg, centerLonDeg) <= observerRadiusM

    /**
     * Vista del bloque decodificado, `side * side` valores en orden fila-mayor
     * con la fila 0 al NORTE. Lanza [BlockNotFoundException] si no está.
     */
    fun blockArray(row: Int, col: Int): ShortArray {
        val key = row * cols + col
        // Sin reordenar en cada acierto: el desalojo es por antigüedad de
        // inserción. Reordenar costaría un remove+put por consulta y quien
        // recorre un rayo ya lleva su propio memo del bloque en curso, así que
        // esta caché solo absorbe los saltos entre bloques.
        blockCache[key]?.let { return it }
        val rec = record(row, col)
        if (!rec.present) {
            val (north, west) = blockBounds(row, col)
            throw BlockNotFoundException(
                "el paquete '$name' no cubre el bloque ($row, $col) con esquina " +
                    "NO en ($north, $west)",
            )
        }
        val bytes = terrain.read(rec.offset, rec.side * rec.side * 2)
        val decoded = bytes.leI16Array(0, rec.side * rec.side)
        blockCache[key] = decoded
        if (blockCache.size > blockCacheLimit) {
            val oldest = blockCache.keys.first()
            blockCache.remove(oldest)
        }
        return decoded
    }

    /** (latitud del borde norte, longitud del borde oeste) del bloque. */
    fun blockBounds(row: Int, col: Int): Pair<Double, Double> =
        Pair(latMaxDeg - row * blockDeg, lonMinDeg + col * blockDeg)

    /**
     * (fila, columna) del bloque que cubre el punto, o `null` si cae fuera.
     *
     * Fila 0 = la más al NORTE, igual que la fila 0 de un `.hgt` es su borde
     * norte. Los bordes sur y este exactos pertenecen a la última fila/columna,
     * donde son su nodo final: las aristas están duplicadas entre bloques
     * vecinos y el valor es el mismo, así que la elección no cambia la
     * respuesta, pero sin ella un punto justo en el borde se sale de la rejilla.
     */
    fun blockOf(latDeg: Double, lonDeg: Double): Pair<Int, Int>? {
        val packed = blockIndexOf(latDeg, lonDeg)
        return if (packed < 0) null else Pair(packed / cols, packed % cols)
    }

    /**
     * (fila, columna) empaquetados en un `Int`, o −1 si el punto cae fuera.
     *
     * SIN ASIGNAR. Existe porque el barrido llama a esto **nueve millones de
     * veces** por vuelta completa, y devolver un `Pair` significaba nueve
     * millones de objetos para el recolector. Medido en un Galaxy A17: el
     * barrido pasó de 28.8 s a lo que mide el test de rendimiento.
     *
     * `blockOf` sigue existiendo para quien quiera legibilidad fuera del bucle
     * caliente, y se apoya en esta.
     */
    fun blockIndexOf(latDeg: Double, lonDeg: Double): Int {
        var row = floor((latMaxDeg - latDeg) / blockDeg).toInt()
        var col = floor((lonDeg - lonMinDeg) / blockDeg).toInt()
        val latMin = latMaxDeg - rows * blockDeg
        val lonMax = lonMinDeg + cols * blockDeg
        if (abs(latDeg - latMin) < 1e-9) row = rows - 1
        if (abs(lonDeg - lonMax) < 1e-9) col = cols - 1
        if (row !in 0 until rows || col !in 0 until cols) return -1
        return row * cols + col
    }

    /**
     * ¿Hay datos aquí? Distingue "no hay bloque" de "el bloque dice void".
     *
     * Es la distinción que permite emitir UNKNOWN: un hueco de cobertura no
     * puede disfrazarse de terreno a 0 m.
     */
    fun coverageAt(latDeg: Double, lonDeg: Double): Coverage {
        val index = blockOf(latDeg, lonDeg) ?: return Coverage.ABSENT
        return if (record(index.first, index.second).present) Coverage.PRESENT
        else Coverage.ABSENT
    }

    /**
     * Cimas a menos de `maxDistanceM`, CON su cobertura. Ver [PeakQuery]: el
     * resultado nunca es una lista pelada.
     */
    fun peaksNear(latDeg: Double, lonDeg: Double, maxDistanceM: Double): PeakQuery {
        val selected = peaks.filter {
            haversineM(latDeg, lonDeg, it.latDeg, it.lonDeg) <= maxDistanceM
        }
        val dCenter = haversineM(latDeg, lonDeg, centerLatDeg, centerLonDeg)
        return PeakQuery(
            peaks = selected,
            registeredRadiusM = peaksRadiusM,
            complete = (dCenter + maxDistanceM) <= peaksRadiusM,
        )
    }

    fun close() = terrain.close()
}

/**
 * Abre un paquete a partir de sus cuatro ficheros y VERIFICA sus constantes.
 *
 * `checkEngine = false` existe solo para inspeccionar un paquete viejo o
 * corrupto desde una herramienta. El motor nunca debe usarlo.
 */
fun openPack(
    manifestJson: String,
    coverageBytes: ByteArray,
    peaksBytes: ByteArray,
    terrain: ByteSource,
    checkEngine: Boolean = true,
): Pack {
    val manifest = json.decodeFromString<Manifest>(manifestJson)
    if (manifest.formatVersion != PACK_FORMAT_VERSION) {
        throw PackFormatException(
            "format_version ${manifest.formatVersion}, se esperaba $PACK_FORMAT_VERSION",
        )
    }
    if (checkEngine) checkEngineConstants(manifest)
    if (manifest.elevation.dtype != EXPECTED_DTYPE) {
        throw PackFormatException(
            "elevaciones en '${manifest.elevation.dtype}', este lector espera " +
                "'$EXPECTED_DTYPE' (little-endian; ver PackFormat.kt)",
        )
    }

    val grid = manifest.grid
    val coverage = readCoverage(coverageBytes, grid.rows, grid.cols)
    val (peaks, peaksRadiusM) = readPeaks(peaksBytes)

    return Pack(
        name = manifest.name ?: "(sin nombre)",
        centerLatDeg = manifest.center.latDeg,
        centerLonDeg = manifest.center.lonDeg,
        terrainRadiusM = manifest.terrainRadiusM,
        peaksRadiusM = peaksRadiusM,
        observerRadiusM = manifest.observerRadiusM
            ?: manifest.tiers.firstOrNull()?.maxDistanceM
            ?: manifest.terrainRadiusM,
        blockDeg = grid.blockDeg,
        latMaxDeg = grid.latMaxDeg,
        lonMinDeg = grid.lonMinDeg,
        rows = grid.rows,
        cols = grid.cols,
        peaks = peaks,
        coverage = coverage,
        terrain = terrain,
    )
}

private fun checkEngineConstants(manifest: Manifest) {
    val diff = ENGINE_CONSTANTS.filter { (name, mine) -> manifest.engine[name] != mine }
    if (diff.isEmpty()) return
    val detail = diff.keys.sorted().joinToString("; ") { name ->
        "$name: paquete=${manifest.engine[name]} motor=${ENGINE_CONSTANTS[name]}"
    }
    throw EngineMismatchException(
        "el paquete se construyó con otras constantes ($detail). Regenéralo: " +
            "mezclarlas da resultados plausibles y equivocados.",
    )
}

private fun readCoverage(raw: ByteArray, rows: Int, cols: Int): Array<BlockRecord> {
    val magic = raw.ascii(0, 4)
    if (magic != COVERAGE_MAGIC) {
        throw PackFormatException("coverage.bin: magic '$magic', se esperaba '$COVERAGE_MAGIC'")
    }
    val version = raw.leU16(4)
    if (version != PACK_FORMAT_VERSION) {
        throw PackFormatException("coverage.bin: versión $version")
    }
    val r = raw.leU32(8).toInt()
    val c = raw.leU32(12).toInt()
    if (r != rows || c != cols) {
        throw PackFormatException(
            "coverage.bin: rejilla ${r}x$c no coincide con el manifest ${rows}x$cols",
        )
    }
    return Array(rows * cols) { i ->
        val p = COVERAGE_HEADER_SIZE + i * COVERAGE_RECORD_SIZE
        BlockRecord(
            present = (raw[p].toInt() and 0xFF) == 1,
            tier = raw[p + 1].toInt() and 0xFF,
            side = raw.leU16(p + 2),
            offset = raw.leI64(p + 4),
        )
    }
}

private fun readPeaks(raw: ByteArray): Pair<List<Peak>, Double> {
    val magic = raw.ascii(0, 4)
    if (magic != PEAKS_MAGIC) {
        throw PackFormatException("peaks.bin: magic '$magic', se esperaba '$PEAKS_MAGIC'")
    }
    val version = raw.leU16(4)
    if (version != PACK_FORMAT_VERSION) {
        throw PackFormatException("peaks.bin: versión $version")
    }
    val count = raw.leU32(8).toInt()
    val radiusM = raw.leF64(28)
    val namesOff = raw.leI64(36).toInt()
    val namesLen = raw.leI64(44).toInt()

    fun text(offset: Int, length: Int): String? =
        if (length == 0) null
        else raw.decodeToString(namesOff + offset, namesOff + offset + length)

    require(namesOff + namesLen <= raw.size) {
        "peaks.bin: el blob de nombres se sale del fichero"
    }

    val peaks = ArrayList<Peak>(count)
    for (i in 0 until count) {
        val p = PEAKS_HEADER_SIZE + i * PEAK_RECORD_SIZE
        peaks.add(
            Peak(
                osmId = raw.leI64(p),
                name = text(raw.leU32(p + 20).toInt(), raw.leU16(p + 28)),
                latDeg = raw.leI32(p + 8) / 1e7,
                lonDeg = raw.leI32(p + 12) / 1e7,
                eleM = raw.leF32(p + 16).toDouble(),
                altName = text(raw.leU32(p + 24).toInt(), raw.leU16(p + 30)),
                heightFromDem = (raw[p + 32].toInt() and 0xFF) == 1,
            ),
        )
    }
    return Pair(peaks, radiusM)
}
