package peakid.engine.align

import kotlin.math.abs
import peakid.engine.geo.normalizeAzimuthDeg
import peakid.engine.geo.wrapDeltaDeg

/**
 * Rumbos cardinales, para acotar el sector sin hablar de grados.
 *
 * El sector se pedía en grados y **el usuario no los sabe**: nadie mira una
 * sierra y piensa "eso está a 40°". Sí sabe decir "miraba al nordeste". Esto
 * traduce lo segundo en lo primero.
 *
 * Nombres en castellano, que es el idioma de la interfaz: SO, O, NO — no SW,
 * W, NW.
 */
enum class Cardinal(val etiqueta: String, val azimuthDeg: Double) {
    N("N", 0.0),
    NE("NE", 45.0),
    E("E", 90.0),
    SE("SE", 135.0),
    S("S", 180.0),
    SO("SO", 225.0),
    O("O", 270.0),
    NO("NO", 315.0),
}

/**
 * Anchura del sector que fija un rumbo cardinal.
 *
 * 90° NO es un número redondo elegido por bonito: es lo que acotó bien el caso
 * de Nerja, donde la respuesta estaba en 40.6° y el sector del nordeste
 * (0°–90°) la contiene con holgura. Señalar la zona basta; la precisión la
 * pone después la búsqueda.
 *
 * Más estrecho arriesga dejar fuera la respuesta —que es el fallo caro, porque
 * la búsqueda no puede encontrar lo que no explora—; más ancho empieza a
 * admitir bultos lejanos parecidos entre sí.
 */
const val CARDINAL_SECTOR_DEG: Double = 90.0

/**
 * Sector centrado en un rumbo cardinal: `(inicio, amplitud)`.
 *
 * **El inicio se normaliza, y ahí está la trampa.** El norte da
 * `0 − 45 = −45`, que es justo el azimut negativo contra el que avisa
 * CLAUDE.md: no revienta, se dibujaría fuera de la tira por la izquierda y el
 * deslizador no alcanzaría medio sector. Con la normalización, el sector del
 * norte empieza en 315° y cruza el norte, que es lo correcto.
 */
fun sectorForCardinal(
    cardinal: Cardinal,
    spanDeg: Double = CARDINAL_SECTOR_DEG,
): Pair<Double, Double> =
    Pair(normalizeAzimuthDeg(cardinal.azimuthDeg - spanDeg / 2.0), spanDeg)

/**
 * El rumbo cardinal más cercano a un azimut.
 *
 * Sirve para PONERLE NOMBRE a una lectura de brújula: "159°" no le dice nada a
 * nadie, "S" sí, y con el nombre el usuario puede desmentirla —"yo no miraba
 * al sur"— que es justo lo que un número no permite.
 *
 * **La distancia se mide con [wrapDeltaDeg], no restando.** A 350° el rumbo
 * más cercano es el NORTE (10° de distancia), no el noroeste (35°): restar sin
 * envolver da 350 − 0 = 350 y elige mal en todo el último octante.
 */
fun nearestCardinal(azimuthDeg: Double): Cardinal {
    val az = normalizeAzimuthDeg(azimuthDeg)
    return Cardinal.entries.minBy { abs(wrapDeltaDeg(az - it.azimuthDeg)) }
}
