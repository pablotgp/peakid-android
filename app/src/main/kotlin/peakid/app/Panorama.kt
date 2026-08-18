package peakid.app

import kotlin.math.roundToInt
import peakid.engine.MAX_DISTANCE_M
import peakid.engine.geo.azimuthDeg
import peakid.engine.geo.elevationDeg
import peakid.engine.geo.haversineM
import peakid.engine.horizon.HorizonProfile
import peakid.engine.horizon.Visibility
import peakid.engine.horizon.checkVisibility
import peakid.engine.horizon.horizonProfile
import peakid.engine.pack.Pack
import peakid.engine.terrain.PackTerrain

/**
 * Panorama calculado desde una posición, con sus cimas.
 *
 * **El panorama depende de la POSICIÓN, no de la orientación.** Esa separación
 * es lo que deja sitio al vídeo sin tocar el motor: se calcula una vez por
 * posición, se cachea, y por fotograma solo se reproyecta —que son
 * microsegundos— en vez de volver a trazar rayos.
 */
class Panorama(
    val profile: HorizonProfile,
    val peaks: List<VisiblePeak>,
    /** El registro de cimas cubre la zona preguntada por completo. */
    val peaksComplete: Boolean,
    val eyeM: Double,
    val computeMs: Long,
)

/**
 * Cuántas cimas se comprueban con rayo, ordenadas por ángulo aparente.
 *
 * Medido sobre la Axarquía desde Torre del Mar: de 2136 cimas registradas, 61
 * salen visibles —el mismo número que documenta el motor Python para ese
 * punto— y 2075 están tapadas. Comprobarlas todas cuesta 3.5 s en escritorio;
 * las 300 de mayor ángulo aparente cuestan una fracción y contienen todas las
 * que pueden llegar a etiquetarse.
 */
private const val MAX_PEAKS_EVALUATED = 300

class VisiblePeak(
    val name: String,
    val altName: String?,
    val eleM: Double,
    val azimuthDeg: Double,
    val distanceM: Double,
    val elevationDeg: Double,
    val visibility: Visibility,
)

/**
 * Caché con clave de posición CUANTIZADA.
 *
 * Recalcular solo cuando el usuario se mueve más de la cuantización es lo que
 * hará viable el vídeo en vivo: mientras no cambie de celda, cada fotograma es
 * una reproyección.
 */
class PanoramaCache(private val quantumM: Double = 50.0) {
    private var key: Triple<String, Int, Int>? = null
    private var value: Panorama? = null

    private fun keyFor(pack: Pack, latDeg: Double, lonDeg: Double): Triple<String, Int, Int> {
        // ~111 320 m por grado de latitud; basta para cuantizar
        val q = quantumM / 111_320.0
        return Triple(pack.name, (latDeg / q).roundToInt(), (lonDeg / q).roundToInt())
    }

    fun get(pack: Pack, latDeg: Double, lonDeg: Double, eyeAboveGroundM: Double): Panorama {
        val k = keyFor(pack, latDeg, lonDeg)
        val cached = value
        if (cached != null && key == k) return cached
        val fresh = compute(pack, latDeg, lonDeg, eyeAboveGroundM)
        key = k
        value = fresh
        return fresh
    }

    fun invalidate() {
        key = null
        value = null
    }
}

/**
 * Calcula el panorama completo de 360° y evalúa las cimas del paquete.
 *
 * `eyeAboveGroundM` es la altura de la PERSONA sobre el suelo: la altitud del
 * ojo la pone esta función sumándola a la cota del terreno. Pasar la cota sin
 * más hace que las muestras cercanas bloqueen espuriamente.
 */
fun compute(
    pack: Pack,
    latDeg: Double,
    lonDeg: Double,
    eyeAboveGroundM: Double = 1.7,
): Panorama {
    val terrain = PackTerrain(pack)
    val groundM = terrain.elevationM(latDeg, lonDeg)
    val eyeM = (if (groundM.isNaN()) 0.0 else groundM) + eyeAboveGroundM

    val inicio = System.currentTimeMillis()
    val profile = horizonProfile(
        latDeg, lonDeg, eyeM, terrain,
        azStartDeg = 0.0, azSpanDeg = 360.0, maxDistanceM = MAX_DISTANCE_M,
    )
    val ms = System.currentTimeMillis() - inicio

    // El registro de cimas del paquete tiene su propio radio, MENOR que el del
    // terreno. Se pregunta por el círculo MÁS GRANDE QUE CABE ENTERO dentro de
    // ese registro desde donde está el observador: pedir el radio completo
    // desde un punto descentrado sale siempre incompleto, y entonces el aviso
    // de "no lo sé" se dispara siempre y deja de significar nada.
    val dCentroM = haversineM(latDeg, lonDeg, pack.centerLatDeg, pack.centerLonDeg)
    val query = pack.peaksNear(latDeg, lonDeg, (pack.peaksRadiusM - dCentroM).coerceAtLeast(0.0))

    // Trazar un rayo de visibilidad por cima cuesta caro: medido sobre el
    // paquete de la Axarquía, 2136 cimas son 3.5 s en escritorio, que en un
    // móvil deja de ser usable. Se ORDENA primero por ángulo aparente —que es
    // aritmética pura, sin rayos— y solo se comprueban las que pueden llegar a
    // dominar la vista.
    //
    // No se pierde nada que se fuera a mostrar: el criterio de las etiquetas es
    // ya el ángulo aparente (gana lo que más ocupa en el cielo, no lo más alto
    // en metros — La Maroma manda por sus 6.4° de arco, no por sus 2069 m), y
    // se pintan una docena. Descartar la número 400 por aparente no cambia
    // ninguna respuesta que el usuario vaya a ver.
    val candidatas = query.peaks.mapNotNull { p ->
        val name = p.name ?: return@mapNotNull null
        val dM = haversineM(latDeg, lonDeg, p.latDeg, p.lonDeg)
        if (dM < 1.0) return@mapNotNull null
        Triple(p, dM, elevationDeg(dM, eyeM, p.eleM))
    }.sortedByDescending { it.third }.take(MAX_PEAKS_EVALUATED)

    val peaks = candidatas.mapNotNull { (p, dM, elev) ->
        val vis = checkVisibility(latDeg, lonDeg, eyeM, p.latDeg, p.lonDeg, p.eleM, terrain)
        // VISIBLE y UNKNOWN se conservan; BLOCKED se descarta. UNKNOWN NO es
        // "no se ve": es "no tengo datos para negarlo", y se marca como tal.
        if (vis.status == Visibility.BLOCKED) return@mapNotNull null
        VisiblePeak(
            p.name!!, p.altName, p.eleM,
            azimuthDeg(latDeg, lonDeg, p.latDeg, p.lonDeg), dM, elev, vis.status,
        )
    }.sortedByDescending { it.elevationDeg }

    return Panorama(profile, peaks, query.complete, eyeM, ms)
}
