package peakid.engine.pack

import peakid.engine.AZIMUTH_STEP_DEG
import peakid.engine.MAX_DISTANCE_M
import peakid.engine.RELOCATE_RADIUS_M
import peakid.engine.STEP_M
import peakid.engine.SUMMIT_MARGIN_M
import peakid.engine.VOID_ELEVATION
import peakid.engine.geo.K_REFRACTION
import peakid.engine.geo.R_EARTH_M

/**
 * Constructor de paquetes sintéticos para los tests herméticos.
 *
 * Escribe los MISMOS bytes que `scripts/build_pack.py`, así que sirve de
 * segunda comprobación del formato: si el lector y este constructor se
 * pusieran de acuerdo en algo equivocado, los tests contra los paquetes reales
 * (que sí produce el script) lo delatarían.
 */
class SyntheticPackBuilder(
    val blockDeg: Double = 0.25,
    val latMaxDeg: Double = 1.0,
    val lonMinDeg: Double = 0.0,
    val rows: Int = 2,
    val cols: Int = 2,
    val side: Int = 5,
    val centerLatDeg: Double = 0.875,
    val centerLonDeg: Double = 0.125,
    val terrainRadiusM: Double = 50_000.0,
    val peaksRadiusM: Double = 50_000.0,
    val observerRadiusM: Double = 25_000.0,
    val engineOverrides: Map<String, Double> = emptyMap(),
    val dtype: String = "<i2",
) {
    /** (fila, columna) de bloque -> valores, o ausente si el bloque no está. */
    private val blocks = LinkedHashMap<Pair<Int, Int>, ShortArray>()
    private val peaks = ArrayList<Peak>()

    fun block(row: Int, col: Int, fill: (nodeRow: Int, nodeCol: Int) -> Short) = apply {
        blocks[Pair(row, col)] = ShortArray(side * side) { i ->
            fill(i / side, i % side)
        }
    }

    fun peak(peak: Peak) = apply { peaks.add(peak) }

    fun build(): Pack {
        val terrainBytes = ArrayList<Byte>()
        val offsets = HashMap<Pair<Int, Int>, Long>()
        for ((key, values) in blocks) {
            offsets[key] = terrainBytes.size.toLong()
            for (v in values) {
                terrainBytes.add((v.toInt() and 0xFF).toByte())
                terrainBytes.add(((v.toInt() shr 8) and 0xFF).toByte())
            }
        }

        val coverage = ArrayList<Byte>()
        coverage.addAll("PKCV".map { it.code.toByte() })
        coverage.addAll(u16(PACK_FORMAT_VERSION))
        coverage.addAll(u16(0))
        coverage.addAll(u32(rows))
        coverage.addAll(u32(cols))
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val present = blocks.containsKey(Pair(r, c))
                coverage.add(if (present) 1 else 0)
                coverage.add(if (present) 1 else 0)
                coverage.addAll(u16(if (present) side else 0))
                coverage.addAll(u64(offsets[Pair(r, c)] ?: 0L))
            }
        }

        val names = StringBuilder()
        val peakRecords = ArrayList<Byte>()
        for (p in peaks) {
            val nameBytes = (p.name ?: "").encodeToByteArray()
            val altBytes = (p.altName ?: "").encodeToByteArray()
            val nameOff = names.length
            names.append((p.name ?: ""))
            val altOff = names.length
            names.append((p.altName ?: ""))
            peakRecords.addAll(u64(p.osmId))
            peakRecords.addAll(u32((p.latDeg * 1e7).toInt()))
            peakRecords.addAll(u32((p.lonDeg * 1e7).toInt()))
            peakRecords.addAll(f32(p.eleM.toFloat()))
            peakRecords.addAll(u32(nameOff))
            peakRecords.addAll(u32(altOff))
            peakRecords.addAll(u16(nameBytes.size))
            peakRecords.addAll(u16(altBytes.size))
            peakRecords.add(if (p.heightFromDem) 1 else 0)
            peakRecords.add(0)
            repeat(6) { peakRecords.add(0) }
        }
        val nameBlob = names.toString().encodeToByteArray()
        val namesOff = 52 + peakRecords.size

        val peaksBin = ArrayList<Byte>()
        peaksBin.addAll("PKPK".map { it.code.toByte() })
        peaksBin.addAll(u16(PACK_FORMAT_VERSION))
        peaksBin.addAll(u16(0))
        peaksBin.addAll(u32(peaks.size))
        peaksBin.addAll(f64(centerLatDeg))
        peaksBin.addAll(f64(centerLonDeg))
        peaksBin.addAll(f64(peaksRadiusM))
        peaksBin.addAll(u64(namesOff.toLong()))
        peaksBin.addAll(u64(nameBlob.size.toLong()))
        peaksBin.addAll(peakRecords)
        peaksBin.addAll(nameBlob.toList())

        val engine = buildMap {
            put("R_EARTH_M", R_EARTH_M)
            put("K_REFRACTION", K_REFRACTION)
            put("STEP_M", STEP_M)
            put("MAX_DISTANCE_M", MAX_DISTANCE_M)
            put("AZIMUTH_STEP_DEG", AZIMUTH_STEP_DEG)
            put("SUMMIT_MARGIN_M", SUMMIT_MARGIN_M)
            put("RELOCATE_RADIUS_M", RELOCATE_RADIUS_M)
            putAll(engineOverrides)
        }
        val manifest = """
            {
              "format_version": $PACK_FORMAT_VERSION,
              "name": "sintetico",
              "center": {"lat_deg": $centerLatDeg, "lon_deg": $centerLonDeg},
              "terrain_radius_m": $terrainRadiusM,
              "observer_radius_m": $observerRadiusM,
              "grid": {"block_deg": $blockDeg, "lat_max_deg": $latMaxDeg,
                       "lon_min_deg": $lonMinDeg, "rows": $rows, "cols": $cols},
              "tiers": [{"tier": 1, "max_distance_m": $observerRadiusM,
                         "arcsec": 1, "side": $side}],
              "elevation": {"dtype": "$dtype", "void": ${VOID_ELEVATION.toInt()}},
              "engine": {${engine.entries.joinToString(", ") { "\"${it.key}\": ${it.value}" }}}
            }
        """.trimIndent()

        return openPack(
            manifestJson = manifest,
            coverageBytes = coverage.toByteArray(),
            peaksBytes = peaksBin.toByteArray(),
            terrain = MemoryByteSource(terrainBytes.toByteArray()),
        )
    }

    private fun u16(v: Int) = listOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    private fun u32(v: Int) = (0 until 4).map { ((v shr (8 * it)) and 0xFF).toByte() }

    private fun u64(v: Long) = (0 until 8).map { ((v shr (8 * it)) and 0xFF).toByte() }

    private fun f32(v: Float) = u32(v.toRawBits())

    private fun f64(v: Double) = u64(v.toRawBits())
}
