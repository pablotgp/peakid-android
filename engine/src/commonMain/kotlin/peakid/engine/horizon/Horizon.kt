package peakid.engine.horizon

import kotlin.math.ceil
import peakid.engine.AZIMUTH_STEP_DEG
import peakid.engine.MAX_DISTANCE_M
import peakid.engine.STEP_M
import peakid.engine.SUMMIT_MARGIN_M
import peakid.engine.geo.azimuthDeg
import peakid.engine.geo.destinationPointsDeg
import peakid.engine.geo.elevationDeg
import peakid.engine.geo.elevationsDeg
import peakid.engine.geo.haversineM
import peakid.engine.geo.normalizeAzimuthDeg
import peakid.engine.terrain.TerrainSource

/**
 * Rayos, visibilidad y barrido de horizonte. Puerto de `src/horizon`.
 *
 * Regla central: **un punto es visible solo si hay que levantar la vista más
 * que para cualquier cosa encontrada antes en esa misma dirección.** Lo que
 * manda es la relación entre altura y distancia, no la altitud.
 *
 * Roles de cada fuente, heredados del motor: la altitud del OBJETIVO llega como
 * parámetro (viene de OSM, la fuente oficial de cimas) y el terreno se consulta
 * SOLO para el camino intermedio, que a 30 m sí está bien representado. El DEM
 * subestima las cimas por promediado.
 */

enum class Visibility { VISIBLE, BLOCKED, UNKNOWN }

/**
 * Resultado de visibilidad con sus reservas.
 *
 * TRES estados, no un booleano. `UNKNOWN` significa que el rayo se quedó sin
 * cobertura antes de encontrar obstáculo, e incluye la distancia de
 * truncamiento. Es el estado que impide que un hueco de datos se disfrace de
 * VISIBLE, y para la app es el que más importa: sin él, la falta de datos se
 * presentaría como un hecho.
 */
data class VisibilityResult(
    val status: Visibility,
    /** Solo si [status] es UNKNOWN. */
    val truncatedAtM: Double? = null,
    /** Solo si [status] es BLOCKED: distancia del primer obstáculo. */
    val blockedAtM: Double? = null,
)

/**
 * Perfil de horizonte sobre un sector.
 *
 * `elevationsDeg[i]` es `NaN` si todo el rayo era void; `truncatedAtM[i]` es
 * `NaN` si el rayo llegó al final sin salirse de la cobertura.
 */
class HorizonProfile(
    val azimuthsDeg: DoubleArray,
    val elevationsDeg: DoubleArray,
    val truncatedAtM: DoubleArray,
) {
    /**
     * Elevación del perfil en el azimut dado, tomando la muestra más cercana.
     *
     * Resuelve el cruce del norte, que es donde una búsqueda ingenua falla: con
     * un sector de 350° a 10°, el azimut 2° está DENTRO aunque numéricamente
     * quede por debajo del inicio.
     */
    fun elevationAtDeg(azDeg: Double): Double {
        if (azimuthsDeg.isEmpty()) return Double.NaN
        val target = normalizeAzimuthDeg(azDeg)
        var best = 0
        var bestDelta = Double.MAX_VALUE
        for (i in azimuthsDeg.indices) {
            val d = angularDistanceDeg(azimuthsDeg[i], target)
            if (d < bestDelta) {
                bestDelta = d
                best = i
            }
        }
        return elevationsDeg[best]
    }
}

/** Separación angular absoluta entre dos azimuts, en [0, 180]. */
internal fun angularDistanceDeg(aDeg: Double, bDeg: Double): Double {
    val d = normalizeAzimuthDeg(aDeg - bDeg)
    return if (d > 180.0) 360.0 - d else d
}

/**
 * ¿Se ve el objetivo desde el observador?
 *
 * `hObsM` es la altitud del OJO sobre el nivel del mar, no la cota del suelo:
 * quien llame suma la altura de la persona (~1.7 m). Pasar la cota hace que las
 * muestras cercanas bloqueen espuriamente.
 *
 * `hTgtM` es la altitud oficial del objetivo, no la del terreno.
 *
 * El rayo se detiene [SUMMIT_MARGIN_M] antes del objetivo: a esa distancia el
 * "terreno" ES la ladera del propio objetivo, y sin ese margen una cima se
 * bloquea a sí misma por centésimas de grado.
 */
fun checkVisibility(
    latObsDeg: Double,
    lonObsDeg: Double,
    hObsM: Double,
    latTgtDeg: Double,
    lonTgtDeg: Double,
    hTgtM: Double,
    source: TerrainSource,
): VisibilityResult {
    val dTotalM = haversineM(latObsDeg, lonObsDeg, latTgtDeg, lonTgtDeg)
    if (dTotalM <= STEP_M) return VisibilityResult(Visibility.VISIBLE)

    val bearingDeg = azimuthDeg(latObsDeg, lonObsDeg, latTgtDeg, lonTgtDeg)
    val targetElevationDeg = elevationDeg(dTotalM, hObsM, hTgtM)

    val limitM = dTotalM - SUMMIT_MARGIN_M
    val count = if (limitM <= STEP_M) 0 else ceil((limitM - STEP_M) / STEP_M).toInt()
    if (count <= 0) return VisibilityResult(Visibility.VISIBLE)

    val distances = DoubleArray(count) { STEP_M * (it + 1) }
    val lat = DoubleArray(count)
    val lon = DoubleArray(count)
    destinationPointsDeg(latObsDeg, lonObsDeg, bearingDeg, distances, lat, lon, count)

    // El orden por distancia resuelve solo la precedencia: si el terreno
    // bloquea ANTES del hueco de cobertura, gana BLOCKED y el hueco ni se mira.
    val cut = source.missingIndex(lat, lon, count)
    val usable = if (cut >= 0) cut else count

    val terrain = DoubleArray(usable)
    if (usable > 0) source.elevationsM(lat, lon, terrain, usable)

    for (i in 0 until usable) {
        // void puntual dentro de un bloque que sí existe: se salta, no cuenta
        // ni como obstáculo ni como terreno libre
        if (terrain[i].isNaN()) continue
        if (elevationDeg(distances[i], hObsM, terrain[i]) >= targetElevationDeg) {
            return VisibilityResult(Visibility.BLOCKED, blockedAtM = distances[i])
        }
    }
    if (cut >= 0) {
        return VisibilityResult(Visibility.UNKNOWN, truncatedAtM = distances[cut])
    }
    return VisibilityResult(Visibility.VISIBLE)
}

/**
 * Barrido de horizonte sobre un SECTOR, en pasos de [AZIMUTH_STEP_DEG].
 *
 * **Inicio + amplitud HORARIA, no mínimo y máximo.** Es inequívoco al cruzar el
 * norte —`azStartDeg = 350, azSpanDeg = 20` es el arco 350°→10°, sin
 * ambigüedad— y evita la regla de "un arco de más de 180° es el
 * complementario", que solo vale mientras el FOV tope en 80°. Con
 * `azSpanDeg = 360` sale el barrido completo.
 *
 * Sin corte anticipado dentro de cada rayo: un pico lejano puede superar a uno
 * cercano más bajo, así que hay que recorrerlo entero.
 */
fun horizonProfile(
    latDeg: Double,
    lonDeg: Double,
    hEyeM: Double,
    source: TerrainSource,
    azStartDeg: Double = 0.0,
    azSpanDeg: Double = 360.0,
    maxDistanceM: Double = MAX_DISTANCE_M,
): HorizonProfile {
    require(azSpanDeg > 0.0 && azSpanDeg <= 360.0) {
        "azSpanDeg debe estar en (0, 360], era $azSpanDeg"
    }
    val rayCount = if (azSpanDeg >= 360.0) {
        (360.0 / AZIMUTH_STEP_DEG).toInt()
    } else {
        (azSpanDeg / AZIMUTH_STEP_DEG).toInt() + 1
    }
    val sampleCount = ((maxDistanceM - STEP_M) / STEP_M).toInt() + 1
    val distances = DoubleArray(sampleCount) { STEP_M * (it + 1) }

    val azimuths = DoubleArray(rayCount)
    val elevations = DoubleArray(rayCount) { Double.NaN }
    val truncated = DoubleArray(rayCount) { Double.NaN }

    val lat = DoubleArray(sampleCount)
    val lon = DoubleArray(sampleCount)
    val terrain = DoubleArray(sampleCount)
    val rayElev = DoubleArray(sampleCount)

    for (i in 0 until rayCount) {
        // normalizado SIEMPRE: sin esto un sector que cruza el norte produce
        // azimuts fuera de [0, 360) y todo lo que los compare falla en silencio
        val bearingDeg = normalizeAzimuthDeg(azStartDeg + i * AZIMUTH_STEP_DEG)
        azimuths[i] = bearingDeg

        destinationPointsDeg(latDeg, lonDeg, bearingDeg, distances, lat, lon, sampleCount)

        val cut = source.missingIndex(lat, lon, sampleCount)
        val usable = if (cut >= 0) cut else sampleCount
        if (cut >= 0) truncated[i] = distances[cut]
        if (usable == 0) continue

        source.elevationsM(lat, lon, terrain, usable)
        elevationsDeg(distances, hEyeM, terrain, rayElev, usable)

        // máximo que IGNORA NaN: los voids llegan como NaN y envenenarían el
        // azimut entero. Si TODO el rayo es void, el perfil queda NaN, que es
        // la verdad, y no un número inventado.
        var best = Double.NaN
        for (k in 0 until usable) {
            val v = rayElev[k]
            if (v.isNaN()) continue
            if (best.isNaN() || v > best) best = v
        }
        elevations[i] = best
    }
    return HorizonProfile(azimuths, elevations, truncated)
}
