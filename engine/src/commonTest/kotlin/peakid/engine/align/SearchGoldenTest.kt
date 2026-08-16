package peakid.engine.align

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import peakid.engine.geo.wrapDeltaDeg

/**
 * Ajuste cerrado de inclinación/giro y búsqueda ACOTADA.
 *
 * =========================================================================
 * HUECO DE COBERTURA CONOCIDO: EL ACOPLAMIENTO DETECTOR -> BÚSQUEDA
 * =========================================================================
 *
 * En el motor Python, los cuatro tests de `search_alignment` fabrican una foto
 * sintética y le pasan **`detect_photo_skyline`** —el detector heurístico de
 * color— para extraer la cresta. Aquí la cresta se deriva **de la propia línea
 * proyectada**: exacta para [solvePitchRoll] ([crestaProyectada]) y cuantizada
 * a filas enteras para la búsqueda ([crestaDetectada]).
 *
 * El motivo es deliberado: el detector heurístico NO se porta. Existía como
 * respaldo porque `onnxruntime` es una dependencia opcional en escritorio, y en
 * Android el runtime va dentro del APK, así que la razón del respaldo
 * desaparece (ver CLAUDE.md).
 *
 * **La consecuencia es que estos tests NO cubren el acoplamiento entre el
 * detector y la búsqueda.** Miden la búsqueda con una cresta perfecta, lo que
 * la aísla del ruido del detector y es más limpio para lo que aquí se
 * comprueba, pero deja fuera una pregunta que en Python SÍ está cubierta: qué
 * le pasa a la búsqueda cuando la cresta viene con huecos, saltos y columnas
 * inválidas de un detector real.
 *
 * Ese hueco se cierra en la **FASE 6**, cuando entre el detector ONNX
 * (SegFormer-B0) y se pueda alimentar la búsqueda con su salida. Hasta
 * entonces: si lees estos tests y los ves completos, no lo están.
 * =========================================================================
 */
class SearchGoldenTest {

    private val w = 1200
    private val h = 900

    // ---- perfiles sintéticos ----------------------------------------------

    /** Perfil rico: dos cimas destacadas más ondulación. Firma inequívoca. */
    private fun perfilSintetico(): Pair<DoubleArray, DoubleArray> {
        val n = 1800
        val az = DoubleArray(n) { it * 0.2 }
        val elev = DoubleArray(n) {
            val a = az[it]
            1.0 + 2.5 * exp(-((a - 20.0) / 5.0) * ((a - 20.0) / 5.0)) +
                1.6 * exp(-((a - 38.0) / 3.0) * ((a - 38.0) / 3.0)) +
                0.8 * sin(a * 3.0 * PI / 180.0)
        }
        return Pair(az, elev)
    }

    /**
     * Cresta EXACTA: la propia línea proyectada con `params`, sin cuantizar.
     *
     * Equivale a `_cresta_proyectada` del motor, y es la que usan los tests de
     * [solvePitchRoll] allí y aquí.
     */
    private fun crestaProyectada(
        az: DoubleArray,
        elev: DoubleArray,
        params: AlignmentParams,
        step: Double = 5.0,
    ): Pair<DoubleArray, DoubleArray> {
        val projected = projectProfile(az, elev, params, w, h)
        val n = ((w - 120.0) / step).toInt()
        val cols = DoubleArray(n) { 60.0 + it * step }
        val rows = projectedYPerColumn(projected.xPx, projected.yPx, projected.usable, cols)
        val keep = cols.indices.filter { !rows[it].isNaN() }
        return Pair(
            DoubleArray(keep.size) { cols[keep[it]] },
            DoubleArray(keep.size) { rows[keep[it]] },
        )
    }

    /**
     * Cresta como la devolvería un DETECTOR: la línea proyectada CUANTIZADA a
     * filas enteras.
     *
     * Sustituye al `detect_photo_skyline` del motor (ver el aviso de la
     * cabecera). La cuantización no es adorno: **sin ella el error del mejor
     * candidato sale exactamente 0 y el margen de ambigüedad —que es
     * RELATIVO, `(alt − mejor) / mejor`— degenera en infinito**, con lo que la
     * búsqueda nunca podría declararse ambigua y el test de ambigüedad pasaría
     * a medir nada.
     *
     * En Python el problema no existe porque la cresta viene de un detector
     * sobre una foto rasterizada, y ningún detector devuelve filas
     * fraccionarias. Redondear a entero es la cantidad mínima de realismo que
     * hace falta para que la métrica signifique algo.
     */
    private fun crestaDetectada(
        az: DoubleArray,
        elev: DoubleArray,
        params: AlignmentParams,
        step: Double = 5.0,
    ): Pair<DoubleArray, DoubleArray> {
        val (cols, rows) = crestaProyectada(az, elev, params, step)
        return Pair(cols, DoubleArray(rows.size) { kotlin.math.round(rows[it]) })
    }

    // ---- solvePitchRoll ---------------------------------------------------

    @Test
    fun solvePitchRoll_recuperaLosDos() {
        // ATA LOS SIGNOS del ajuste automático. Si el del giro se invirtiera,
        // el modo automático torcería la línea al revés sin que nada fallara de
        // forma visible.
        val (az, elev) = perfilSintetico()
        val casos = listOf(
            2.0 to 0.0, 0.0 to 1.5, 3.0 to -2.0, -4.0 to 3.0, 8.0 to 5.0,
        )
        for ((pitch, roll) in casos) {
            val verdad = AlignmentParams(23.7, 55.0, pitch, roll)
            val (cols, rows) = crestaProyectada(az, elev, verdad)
            // se parte de inclinación y giro a CERO: el solver debe llegar solo
            val partida = AlignmentParams(23.7, 55.0, 0.0, 0.0)
            val solved = solvePitchRoll(az, elev, partida, cols, rows, w, h)
            assertNotNull(solved, "no resolvió para pitch=$pitch roll=$roll")
            assertEquals(pitch, solved.first, 0.05, "inclinación con pitch=$pitch roll=$roll")
            assertEquals(roll, solved.second, 0.05, "giro con pitch=$pitch roll=$roll")
        }
    }

    @Test
    fun solvePitchRoll_esRobustoYSePlanta() {
        val (az, elev) = perfilSintetico()
        val verdad = AlignmentParams(23.7, 55.0, 3.0, -1.5)
        val (cols, rows) = crestaProyectada(az, elev, verdad)

        // 10% de columnas disparatadas (tejados, arbustos): el rechazo por MAD
        // debe impedir que tuerzan la recta
        val sucias = rows.copyOf()
        var i = 0
        while (i < sucias.size) { sucias[i] += 180.0; i += 10 }
        val partida = AlignmentParams(23.7, 55.0, 0.0, 0.0)
        val solved = solvePitchRoll(az, elev, partida, cols, sucias, w, h)
        assertNotNull(solved)
        assertEquals(3.0, solved.first, 0.15)
        assertEquals(-1.5, solved.second, 0.15)

        // SEGUNDA LECTURA, y es otra cosa: con cuatro columnas NO se resuelve.
        // Mejor null que un ajuste inventado con cuatro puntos.
        assertNull(
            solvePitchRoll(
                az, elev, partida, cols.copyOf(4), rows.copyOf(4), w, h,
            ),
            "con cuatro columnas debe plantarse, no inventar",
        )
    }

    // ---- CASO 7 DEL CONTRATO ----------------------------------------------

    @Test
    fun caso7_laBusquedaRecuperaElDesplazamientoSintetico() {
        // Renderizar el horizonte, desplazarlo artificialmente +13.7° y
        // comprobar que el alineamiento lo recupera a ±0.1°.
        val (az, elev) = perfilSintetico()
        val seedAz = 10.0
        val verdad = AlignmentParams(seedAz + 13.7, 55.0, 2.0, 1.0)
        val (cols, rows) = crestaDetectada(az, elev, verdad)

        val result = searchAlignment(az, elev, cols, rows, w, h, centerAzDeg = seedAz)
        assertTrue(result.candidates.isNotEmpty(), "la búsqueda no devolvió candidatos")
        val best = result.candidates.first().params

        val recovered = wrapDeltaDeg(best.azimuthDeg - seedAz)
        assertEquals(13.7, recovered, 0.1, "desplazamiento recuperado")
        assertEquals(55.0, best.hfovDeg, 1.0)
        assertEquals(2.0, best.pitchDeg, 0.2)
        assertEquals(1.0, best.rollDeg, 0.2)
        assertTrue(result.candidates.first().errorDeg < 0.05)

        // todos se puntúan con la MISMA métrica exacta, así que la lista está
        // ordenada de verdad por el error mostrado (en píxeles, que es el
        // espacio de comparación)
        val errores = result.candidates.map { it.errorPx }
        assertEquals(errores.sorted(), errores, "la lista no está ordenada por error_px")

        // perfil rico + campo ancho: el óptimo es claro y no toca ningún borde
        assertTrue(result.reliable, "debería ser fiable: margen ${result.ambiguityMargin}")
        assertFalse(result.ambiguous)
    }

    // ---- ambigüedad: el fallo real del teleobjetivo -----------------------

    @Test
    fun laBusquedaDetectaAmbiguedadConCampoEstrecho() {
        // EL FALLO REAL DEL USUARIO (teleobjetivo de Sierra Nevada). Con campo
        // estrecho la firma del horizonte es pobre y la correlación tiene
        // muchos máximos casi equivalentes. Aquí se fuerza el caso extremo: un
        // perfil PERIÓDICO fotografiado con 15° de campo.
        //
        // EL PERIODO TIENE QUE SER MENOR QUE EL SEMIRRANGO EXPLORADO, y no es
        // un detalle del fixture: con periodo 15° y semirrango ±20°, la
        // repetición equivalente cae DENTRO del espacio de búsqueda y la
        // ambigüedad es REAL, que es lo que este test debe detectar. Con un
        // periodo MAYOR que el rango no habría dos hipótesis que confundir —
        // solo un espacio demasiado estrecho— y el test pasaría por el motivo
        // equivocado, midiendo otra cosa mientras parece medir esta. Es la
        // misma trampa que el mar llano de la fase 3.
        val n = 1800
        val az = DoubleArray(n) { it * 0.2 }
        val elev = DoubleArray(n) { 2.0 + 1.5 * sin(az[it] * 24.0 * PI / 180.0) }
        val verdad = AlignmentParams(40.0, 15.0, 1.0, 0.0)
        val (cols, rows) = crestaDetectada(az, elev, verdad)

        val result = searchAlignment(
            az, elev, cols, rows, w, h,
            centerAzDeg = 40.0, azMarginDeg = 20.0,
            fovHintDeg = 15.0, fovMarginDeg = 5.0,
        )
        assertTrue(result.candidates.isNotEmpty())
        assertTrue(
            result.ambiguous,
            "margen ${result.ambiguityMargin}: la búsqueda debería admitir que " +
                "no distingue entre repeticiones del mismo perfil",
        )
        assertNotNull(result.alternative)
        val separacion = abs(
            wrapDeltaDeg(
                result.alternative!!.params.azimuthDeg - result.candidates.first().params.azimuthDeg,
            ),
        )
        assertTrue(separacion > 10.0, "la alternativa debe ser otra hipótesis, no un vecino")
        assertFalse(result.reliable)
    }

    // ---- saturación de FOV, con su límite ---------------------------------

    @Test
    fun laBusquedaDetectaSaturacionDeFov() {
        val (az, elev) = perfilSintetico()
        val verdad = AlignmentParams(23.7, 20.0, 1.0, 0.0)
        val (cols, rows) = crestaDetectada(az, elev, verdad)

        // rango 25-45: el verdadero (20) queda justo FUERA, el óptimo se apoya
        // en el extremo inferior y hay que avisar de que el bueno puede estar
        // fuera. Es lo que pasó con el teleobjetivo real contra el rango
        // antiguo de 40-75°.
        val fuera = searchAlignment(
            az, elev, cols, rows, w, h,
            centerAzDeg = 23.7, fovHintDeg = 35.0, fovMarginDeg = 10.0,
        )
        assertTrue(fuera.fovAtEdge, "el óptimo se apoya en el borde y debe declararse")
        assertFalse(fuera.reliable)

        // LA MITAD NEGATIVA, y es la que importa no perder: la detección de
        // saturación SOLO muerde cuando el verdadero está CERCA del borde. Con
        // el rango 45-65 (verdad 20, muy lejos) la búsqueda encuentra un óptimo
        // interior ESPURIO y `fovAtEdge` NO se dispara.
        //
        // Contra ese caso protege la AMBIGÜEDAD, no la saturación. Queda
        // escrito para que nadie confíe de más en `fovAtEdge`: que salga false
        // no significa que el campo explorado contenga el verdadero.
        val lejos = searchAlignment(
            az, elev, cols, rows, w, h,
            centerAzDeg = 23.7, fovHintDeg = 55.0, fovMarginDeg = 10.0,
        )
        assertFalse(lejos.fovAtEdge, "con el rango lejos, la saturación NO avisa")
        assertTrue(
            lejos.candidates.first().params.hfovDeg > 40.0,
            "y el óptimo que devuelve es espurio, lejos de los 20° verdaderos",
        )

        // el rango completo por defecto sí lo contiene
        val dentro = searchAlignment(az, elev, cols, rows, w, h, centerAzDeg = 23.7)
        assertFalse(dentro.fovAtEdge)
    }

    // ---- las pistas acotan el espacio, y el espacio se declara ------------

    @Test
    fun lasPistasAcotanElEspacioExplorado() {
        // Sin pista, un desplazamiento de 35° cae fuera de los ±20° explorados
        // y la búsqueda ni siquiera mira ahí: es el fallo del caso de Granada.
        // Con pista, el rango se centra donde toca y lo encuentra.
        val (az, elev) = perfilSintetico()
        val trueAz = 45.0
        val verdad = AlignmentParams(trueAz, 55.0, 2.0, 1.0)
        val (cols, rows) = crestaDetectada(az, elev, verdad)

        val sinPista = searchAlignment(az, elev, cols, rows, w, h, centerAzDeg = 10.0)
        // el espacio explorado se DECLARA: un fallo así tiene que ser visible
        // sin adivinarlo
        assertEquals(-10.0, sinPista.azRangeDeg.first, 1e-9)
        assertEquals(30.0, sinPista.azRangeDeg.second, 1e-9)
        assertFalse(sinPista.reliable, "el verdadero ni se exploró")

        val conPista = searchAlignment(
            az, elev, cols, rows, w, h, centerAzDeg = trueAz, azMarginDeg = 10.0,
        )
        assertEquals(35.0, conPista.azRangeDeg.first, 1e-9)
        assertEquals(55.0, conPista.azRangeDeg.second, 1e-9)
        assertEquals(trueAz, conPista.candidates.first().params.azimuthDeg, 0.3)
    }

    // ---- saturación del error ---------------------------------------------

    @Test
    fun elErrorSaturadoNoPareceCasiBueno() {
        // El recorte a 40 px satura: un desajuste de 350 px daba el mismo
        // número que uno de 45, y se informó como "38 px, casi bueno" cuando la
        // línea iba a 350 px de la cresta.
        val catastrofico = DoubleArray(200) { 350.0 }
        val mediocre = DoubleArray(200) { if (it < 180) 12.0 else 300.0 }

        assertEquals(40.0, clippedMeanAbs(catastrofico), 1e-9)
        assertEquals(1.0, saturatedFraction(catastrofico), 1e-9)
        assertEquals(0.1, saturatedFraction(mediocre), 1e-9)

        val p = AlignmentParams(0.0, 50.0, 0.0, 0.0)
        assertTrue(SearchCandidate(p, 40.0, 0.5, 1.0, 1.0).saturated)
        assertFalse(SearchCandidate(p, 12.5, 0.2, 1.0, 0.1).saturated)
    }

    // ---- sector -> rangos --------------------------------------------------

    @Test
    fun sectorARangos() {
        // sector normal de 30°
        val normal = sectorToRanges(20.0, 50.0)
        assertEquals(35.0, normal[0], 1e-9)   // centro
        assertEquals(30.0, normal[1], 1e-9)   // ancho
        assertEquals(20.0, normal[2], 1e-9)
        assertEquals(50.0, normal[3], 1e-9)
        assertEquals(15.0, normal[4], 1e-9)   // fov lo
        assertEquals(60.0, normal[5], 1e-9)   // fov hi

        // arco > 180° -> el COMPLEMENTARIO, que cruza el norte. Inequívoco solo
        // porque el FOV máximo son 80°: ninguna foto abarca 340°.
        val cruzando = sectorToRanges(10.0, 350.0)
        assertEquals(20.0, cruzando[1], 1e-9)
        assertEquals(350.0, cruzando[2], 1e-9)
        assertEquals(370.0, cruzando[3], 1e-9)
        assertEquals(0.0, cruzando[0], 1e-9)

        // el FOV se acota al rango soportado por el buscador
        val ancho = sectorToRanges(0.0, 120.0)
        assertTrue(ancho[4] >= FOV_RANGE_DEG.start)
        assertTrue(ancho[5] <= FOV_RANGE_DEG.endInclusive)
    }
}
