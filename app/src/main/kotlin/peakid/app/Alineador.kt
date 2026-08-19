package peakid.app

import peakid.engine.align.AlignmentParams
import peakid.engine.align.SearchResult
import peakid.engine.align.searchAlignment
import peakid.engine.align.sectorToRanges
import peakid.engine.geo.normalizeAzimuthDeg

/**
 * De dónde sale el acotado del espacio de búsqueda, y si acota de verdad.
 *
 * `acotada = false` significa barrido amplio: la búsqueda funciona igual, pero
 * el resultado tiene muchas más ocasiones de ser ambiguo y eso hay que decirlo
 * ANTES, no después.
 */
class PistaDeBusqueda(
    val centroDeg: Double,
    val margenDeg: Double,
    val fuente: String,
    val acotada: Boolean,
)

/** Semiancho que se le concede a la brújula de un móvil. */
private const val MARGEN_BRUJULA_DEG = 30.0

/** Un sector por encima de esto es "sin acotar". */
private const val SECTOR_COMPLETO_DEG = 359.0

/**
 * Qué acota la búsqueda, y por qué ese y no otro.
 *
 * El orden NO es de precisión sino de PERTINENCIA: la pregunta no es qué pista
 * es más estrecha, sino cuál habla de ESTA foto.
 *
 * 1. **El sector marcado a mano manda** en cuanto el usuario lo toca. Es una
 *    afirmación deliberada sobre dónde mira la foto, y nada automático debería
 *    pisarla.
 * 2. **Si la posición vino del EXIF, la brújula no vale.** Que la foto traiga
 *    su propia posición significa que se tomó en otro sitio y en otro momento;
 *    hacia dónde apunta el móvil AHORA no dice nada de ella. Usarla acotaría
 *    ±30° alrededor de un rumbo inventado, y eso es PEOR que no acotar: esconde
 *    la respuesta correcta fuera del rango explorado.
 * 3. **Con la posición del móvil, la brújula sí es una pista**: el usuario está
 *    donde se tomó la foto, así que su rumbo actual es una hipótesis razonable.
 * 4. **Sin nada de eso, barrido amplio** y se avisa.
 */
fun pistaParaBuscar(state: AlignState, compass: Compass): PistaDeBusqueda {
    val sectorAcotado = state.sectorSpanDeg < SECTOR_COMPLETO_DEG
    val rangos = sectorToRanges(
        state.sectorStartDeg,
        normalizeAzimuthDeg(state.sectorStartDeg + state.sectorSpanDeg),
    )
    val sector = PistaDeBusqueda(rangos[0], rangos[1] / 2.0, "el sector que has marcado", true)

    if (state.azimuthTouched && sectorAcotado) return sector

    val brujulaSirve = state.positionSource != PositionSource.EXIF &&
        compass.positioned && state.compassAzimuthDeg != null
    if (brujulaSirve) {
        return PistaDeBusqueda(
            state.compassAzimuthDeg!!, MARGEN_BRUJULA_DEG, "la brújula", true,
        )
    }
    if (sectorAcotado) return sector

    return PistaDeBusqueda(state.azimuthDeg, 180.0, "nada: barrido de 360°", false)
}

/**
 * Lanza la búsqueda acotada sobre la cresta que haya.
 *
 * **No se le pasa pista de CAMPO a propósito.** Lo natural sería sembrarla con
 * el valor del EXIF, y sería un error: medido en la cámara de las fotos de
 * referencia, el EXIF dice 67.38° donde el alineamiento manual da 40.2°. Con
 * `fovHint = 67.38` y el margen de 10° la rejilla iría de 57° a 77° y el valor
 * correcto quedaría FUERA del espacio explorado — la búsqueda no fallaría, que
 * sería lo bueno: devolvería con aplomo el mejor punto de un rango equivocado.
 * Sin pista, la rejilla recorre los 10°–80° enteros.
 */
fun buscarAlineamiento(state: AlignState, pista: PistaDeBusqueda): SearchResult {
    val pano = state.panorama ?: error("no hay panorama")
    val foto = state.photo ?: error("no hay foto")
    return searchAlignment(
        pano.profile.azimuthsDeg, pano.profile.elevationsDeg,
        state.crestCols.toDoubleArray(), state.crestRows.toDoubleArray(),
        foto.widthPx, foto.heightPx,
        centerAzDeg = pista.centroDeg,
        azMarginDeg = pista.margenDeg,
        fovHintDeg = null,
    )
}

/** Aplica un candidato a los cuatro parámetros. */
fun AlignState.aplicar(params: AlignmentParams) {
    azimuthDeg = params.azimuthDeg
    hfovDeg = params.hfovDeg
    pitchDeg = params.pitchDeg
    rollDeg = params.rollDeg
}

/**
 * Las reservas del resultado, en palabras, para PANTALLA.
 *
 * Vacío = sin reservas detectables. Que esté vacío NO garantiza que el
 * alineamiento sea correcto: la ambigüedad se contrasta contra UN alternativo
 * lejano y no cubre un continuo de óptimos parecidos. Lo que zanja es
 * geográfico —qué cima cae sobre qué bulto— y eso lo mira una persona.
 */
fun reservasDe(r: SearchResult, pista: PistaDeBusqueda): List<String> {
    val out = mutableListOf<String>()
    if (r.candidates.isEmpty()) {
        out.add("La búsqueda no ha encontrado ningún candidato.")
        return out
    }
    if (r.ambiguous) {
        out.add(
            "AMBIGUO: hay otra hipótesis lejana casi igual de buena " +
                "(solo un ${"%.0f".format(100 * r.ambiguityMargin)}% peor). " +
                "Compara los topónimos antes de fiarte.",
        )
    }
    if (r.fovAtEdge) {
        out.add(
            "El campo óptimo se apoya en el BORDE del rango explorado " +
                "(${"%.1f".format(r.fovRangeDeg.first)}°–" +
                "${"%.1f".format(r.fovRangeDeg.second)}°): " +
                "el verdadero puede estar fuera.",
        )
    }
    if (r.azAtEdge) {
        out.add(
            "El azimut óptimo se apoya en el BORDE del rango explorado " +
                "(${"%.1f".format(r.azRangeDeg.first)}°–" +
                "${"%.1f".format(r.azRangeDeg.second)}°): " +
                "amplía el sector y vuelve a buscar.",
        )
    }
    r.candidates.firstOrNull()?.let {
        if (it.saturated) {
            out.add(
                "El error está SATURADO: la línea proyectada se va muy lejos de " +
                    "la cresta y el número deja de medir la calidad.",
            )
        }
    }
    if (!pista.acotada) {
        out.add(
            "Se ha buscado en 360° sin acotar. Marca el sector sobre la sierra " +
                "y repite: un barrido amplio encuentra óptimos lejanos que se " +
                "parecen entre sí.",
        )
    }
    return out
}
