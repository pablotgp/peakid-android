package peakid.engine.horizon

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import peakid.engine.AZIMUTH_STEP_DEG
import peakid.engine.VOID_ELEVATION
import peakid.engine.geo.destinationPointDeg
import peakid.engine.geo.normalizeAzimuthDeg
import peakid.engine.pack.SyntheticPackBuilder
import peakid.engine.terrain.PackTerrain

/**
 * Rayos, visibilidad y barrido, sobre paquetes sintéticos.
 *
 * Herméticos: corren siempre, sin datos descargados. Los casos dorados que
 * necesitan paquetes reales viven en `jvmTest`.
 */
class HorizonTest {

    /** Paquete "mar": 2x2 bloques de 0.25° a altitud 0. Unos 55x55 km. */
    private fun packMar(): PackTerrain {
        val builder = SyntheticPackBuilder(
            blockDeg = 0.25, latMaxDeg = 1.0, lonMinDeg = 0.0,
            rows = 2, cols = 2, side = 5,
            centerLatDeg = 0.9, centerLonDeg = 0.25,
        )
        for (r in 0 until 2) for (c in 0 until 2) builder.block(r, c) { _, _ -> 0 }
        return PackTerrain(builder.build())
    }

    // ---- los tres estados --------------------------------------------------
    //
    // El enunciado del caso 6 solo habla de que una cima "es visible". El
    // contrato de check_visibility son TRES estados, y el que de verdad
    // importa para la app es UNKNOWN: sin él, un hueco de cobertura se
    // presentaría como un hecho.

    @Test
    fun tresEstados_bloqueadoPorElPropioHorizonteMarino() {
        val terrain = packMar()
        val destino = DoubleArray(2)
        destinationPointDeg(0.9, 0.25, 180.0, 20_000.0, destino)
        // objetivo a 20 km a nivel del mar: lo tapa el horizonte marino, que
        // está a -0.0947° mientras el objetivo queda a -0.107°
        val r = checkVisibility(0.9, 0.25, 10.0, destino[0], destino[1], 0.0, terrain)
        assertEquals(Visibility.BLOCKED, r.status)
        assertNotNull(r.blockedAtM)
        assertTrue(r.blockedAtM!! > 0.0)
    }

    @Test
    fun tresEstados_visibleSiSeElevaPorEncima() {
        val terrain = packMar()
        val destino = DoubleArray(2)
        destinationPointDeg(0.9, 0.25, 180.0, 20_000.0, destino)
        val r = checkVisibility(0.9, 0.25, 10.0, destino[0], destino[1], 500.0, terrain)
        assertEquals(Visibility.VISIBLE, r.status)
    }

    @Test
    fun tresEstados_desconocidoCuandoElRayoSaleDeLaCobertura() {
        val terrain = packMar()
        val destino = DoubleArray(2)
        destinationPointDeg(0.9, 0.25, 180.0, 100_000.0, destino)
        // nada lo bloquea antes, pero el rayo se sale del paquete. No puede
        // responderse, y eso se DICE en vez de devolver un VISIBLE que sería
        // mentira.
        val r = checkVisibility(0.9, 0.25, 10.0, destino[0], destino[1], 2_000.0, terrain)
        assertEquals(Visibility.UNKNOWN, r.status)
        assertNotNull(r.truncatedAtM)
        assertTrue(r.truncatedAtM!! > 0.0)
        assertNull(r.blockedAtM)
    }

    @Test
    fun bloqueadoGanaADesconocidoCuandoElObstaculoEstaAntes() {
        // El orden por distancia resuelve solo la precedencia: si el terreno
        // bloquea ANTES del hueco de cobertura, la respuesta es BLOCKED y el
        // hueco ni se mira. Al revés se perdería información real.
        val terrain = packMar()
        val destino = DoubleArray(2)
        destinationPointDeg(0.9, 0.25, 180.0, 100_000.0, destino)
        val r = checkVisibility(0.9, 0.25, 10.0, destino[0], destino[1], 0.0, terrain)
        assertEquals(Visibility.BLOCKED, r.status)
    }

    // ---- el horizonte marino, con solución cerrada -------------------------

    @Test
    fun horizonteMarinoCoincideConLaSolucionCerrada() {
        // Desde 10 m sobre el mar solo hay agua. El máximo tiene solución
        // exacta: maximizando f(d) = (-h - 0.87 d^2 / 2R) / d sale
        // d = sqrt(2Rh/0.87) = 12102.1 m y el ángulo -0.094688°.
        //
        // Valida de una vez el SIGNO de la curvatura (cambiado saldría
        // positivo), el convenio de elevaciones negativas, la regla del máximo
        // acumulado y la fórmula de destino. Sin curvatura daría -0.0473°.
        val terrain = packMar()
        val profile = horizonProfile(
            0.9, 0.25, 10.0, terrain,
            azStartDeg = 180.0, azSpanDeg = 0.2, maxDistanceM = 20_000.0,
        )
        assertEquals(-0.094688, profile.elevationsDeg[0], 0.005)
    }

    // ---- voids: el máximo no se envenena -----------------------------------

    @Test
    fun unVoidEnElRayoNoEnvenenaElMaximo() {
        // Bloque de 0.05° con 11 nodos (~556 m entre nodos): terreno a 1000 m
        // en la mitad norte, void en la sur. Con un máximo que no ignore NaN,
        // el azimut entero saldría NaN pese a haber terreno real medido.
        val builder = SyntheticPackBuilder(
            blockDeg = 0.05, latMaxDeg = 1.0, lonMinDeg = 0.0,
            rows = 1, cols = 1, side = 11,
            centerLatDeg = 0.975, centerLonDeg = 0.025,
        )
        builder.block(0, 0) { r, _ -> if (r >= 6) VOID_ELEVATION else 1000 }
        val terrain = PackTerrain(builder.build())

        val sur = horizonProfile(
            0.975, 0.025, 100.0, terrain,
            azStartDeg = 180.0, azSpanDeg = 0.2, maxDistanceM = 2_000.0,
        )
        assertTrue(!sur.elevationsDeg[0].isNaN(),
            "el rayo cruza void pero también terreno medido: no puede salir NaN")
        assertTrue(sur.elevationsDeg[0] > 0.0,
            "el terreno de 1000 m está muy por encima del ojo a 100 m")
    }

    @Test
    fun unRayoEnteramenteVoidDaNaNyNoUnNumeroInventado() {
        val builder = SyntheticPackBuilder(
            blockDeg = 0.05, latMaxDeg = 1.0, lonMinDeg = 0.0,
            rows = 1, cols = 1, side = 11,
            centerLatDeg = 0.975, centerLonDeg = 0.025,
        )
        builder.block(0, 0) { _, _ -> VOID_ELEVATION }
        val terrain = PackTerrain(builder.build())

        val profile = horizonProfile(
            0.975, 0.025, 100.0, terrain,
            azStartDeg = 0.0, azSpanDeg = 0.2, maxDistanceM = 1_000.0,
        )
        assertTrue(profile.elevationsDeg[0].isNaN(),
            "sin dato, el perfil es NaN — no 0, no -32768 disfrazado")
        // y no se truncó: hay bloque, lo que falta es el dato dentro de él
        assertTrue(profile.truncatedAtM[0].isNaN(),
            "un void NO es una falta de cobertura y no debe truncar")
    }

    // ---- truncamiento: por distancia, no por bloque ------------------------

    @Test
    fun elTruncamientoEsPorDistanciaYNoPorGrupoDeBloque() {
        // Tres bloques en fila: el del medio AUSENTE. Un rayo al este sale del
        // paquete y vuelve a entrar. El corte tiene que ser la primera muestra
        // ausente EN ORDEN DE DISTANCIA, descartando todo lo posterior aunque
        // haya cobertura: agrupar por identidad de bloque cortaría más tarde y
        // el máximo del rayo mezclaría terreno con un hueco.
        val builder = SyntheticPackBuilder(
            blockDeg = 0.25, latMaxDeg = 1.0, lonMinDeg = 0.0,
            rows = 1, cols = 3, side = 5,
            centerLatDeg = 0.9, centerLonDeg = 0.1,
        )
        builder.block(0, 0) { _, _ -> 0 }
        builder.block(0, 2) { _, _ -> 0 }      // (0,1) queda ausente a propósito
        val pack = builder.build()
        val terrain = PackTerrain(pack)

        assertEquals(peakid.engine.pack.Coverage.ABSENT, pack.coverageAt(0.9, 0.3))
        assertEquals(peakid.engine.pack.Coverage.PRESENT, pack.coverageAt(0.9, 0.6))

        val profile = horizonProfile(
            0.9, 0.1, 10.0, terrain,
            azStartDeg = 90.0, azSpanDeg = 0.2, maxDistanceM = 60_000.0,
        )
        val cut = profile.truncatedAtM[0]
        assertTrue(!cut.isNaN(), "el rayo sale de la cobertura y debe registrarlo")
        // lon 0.1 -> 0.25 son 0.15° ~ 16.7 km en el ecuador
        assertTrue(abs(cut - 16_700.0) < 1_500.0,
            "corte esperado al entrar en el bloque ausente (~16.7 km), fue $cut")
    }

    // ---- el sector, y el cruce del norte -----------------------------------
    //
    // Guardián NUEVO: no existe en el motor, porque allí el barrido siempre es
    // de 360°. Es justo donde un ajuste de rango se rompe sin que ningún test
    // de vuelta completa se entere.

    @Test
    fun unSectorQueCruzaElNorteProduceAzimutsValidos() {
        val terrain = packMar()
        val profile = horizonProfile(
            0.9, 0.25, 10.0, terrain,
            azStartDeg = 350.0, azSpanDeg = 20.0, maxDistanceM = 5_000.0,
        )
        assertEquals(101, profile.azimuthsDeg.size, "20° a pasos de 0.2° + el final")
        for (az in profile.azimuthsDeg) {
            assertTrue(az >= 0.0 && az < 360.0, "azimut fuera de [0,360): $az")
        }
        assertEquals(350.0, profile.azimuthsDeg.first(), 1e-9)
        assertEquals(10.0, profile.azimuthsDeg.last(), 1e-9)
        // y pasa por el norte por dentro, no por fuera
        assertTrue(profile.azimuthsDeg.any { abs(it - 0.0) < 1e-9 },
            "el sector 350->10 tiene que incluir el 0")
        assertTrue(profile.azimuthsDeg.any { abs(it - 359.8) < 1e-9 })
    }

    @Test
    fun elSectorEsUnTrozoExactoDeLaVueltaCompleta() {
        // Invariante fuerte: un sector no es otra cosa que una rebanada del
        // barrido de 360°. Si difiriera, alguno de los dos estaría mal.
        val terrain = packMar()
        val completo = horizonProfile(
            0.9, 0.25, 10.0, terrain, azSpanDeg = 360.0, maxDistanceM = 5_000.0,
        )
        val sector = horizonProfile(
            0.9, 0.25, 10.0, terrain,
            azStartDeg = 350.0, azSpanDeg = 20.0, maxDistanceM = 5_000.0,
        )
        for (i in sector.azimuthsDeg.indices) {
            val az = sector.azimuthsDeg[i]
            val j = completo.azimuthsDeg.indexOfFirst { abs(it - az) < 1e-6 }
            assertTrue(j >= 0, "el azimut $az del sector no está en la vuelta completa")
            val a = sector.elevationsDeg[i]
            val b = completo.elevationsDeg[j]
            if (a.isNaN() || b.isNaN()) {
                assertEquals(a.isNaN(), b.isNaN(), "NaN discrepante en $az")
            } else {
                assertEquals(b, a, 1e-9, "elevación discrepante en $az")
            }
        }
    }

    @Test
    fun laVueltaCompletaTiene1800Rayos() {
        val terrain = packMar()
        val completo = horizonProfile(
            0.9, 0.25, 10.0, terrain, azSpanDeg = 360.0, maxDistanceM = 1_000.0,
        )
        assertEquals(1800, completo.azimuthsDeg.size)
        assertEquals(0.0, completo.azimuthsDeg.first(), 1e-9)
        assertEquals(360.0 - AZIMUTH_STEP_DEG, completo.azimuthsDeg.last(), 1e-9)
    }

    @Test
    fun buscarLaElevacionPorAzimutResuelveElCruceDelNorte() {
        // Perfil FABRICADO con una elevación distinta por muestra.
        //
        // La primera versión de este test usaba el paquete "mar", donde todas
        // las elevaciones valen lo mismo: comparaba el valor devuelto contra el
        // de la muestra esperada y coincidía SIEMPRE, eligiera la que eligiera.
        // Parecía vigilar el cruce del norte y no vigilaba nada. Lo delató una
        // mutación (`%` a pelo en la distancia angular) que este test dejó pasar
        // y solo cazaron las 72 cimas, sobre terreno de verdad.
        val azimuths = DoubleArray(101) { normalizeAzimuthDeg(350.0 + it * 0.2) }
        val elevations = DoubleArray(101) { it.toDouble() }
        val profile = HorizonProfile(azimuths, elevations, DoubleArray(101) { Double.NaN })

        // 350 + 60*0.2 = 362 -> 2°. Está DENTRO del sector aunque numéricamente
        // quede por debajo del inicio.
        assertEquals(60.0, profile.elevationAtDeg(2.0), 1e-12)
        assertEquals(0.0, profile.elevationAtDeg(350.0), 1e-12)
        assertEquals(50.0, profile.elevationAtDeg(0.0), 1e-12)
        assertEquals(100.0, profile.elevationAtDeg(10.0), 1e-12)
        // y justo al otro lado del corte de 0/360
        assertEquals(49.0, profile.elevationAtDeg(359.8), 1e-12)
    }

    @Test
    fun laDistanciaAngularNuncaEsNegativaNiCruzandoElNorte() {
        // Es la primitiva de la que dependía el test anterior. Con `%` a pelo,
        // angularDistanceDeg(0, 2) sale -2.0 y cualquier búsqueda de mínimo
        // elige esa muestra por encima de la correcta.
        assertEquals(2.0, angularDistanceDeg(0.0, 2.0), 1e-12)
        assertEquals(2.0, angularDistanceDeg(2.0, 0.0), 1e-12)
        assertEquals(0.4, angularDistanceDeg(359.8, 0.2), 1e-12)
        assertEquals(180.0, angularDistanceDeg(0.0, 180.0), 1e-12)
        assertEquals(0.0, angularDistanceDeg(0.0, 360.0), 1e-12)
        for (a in 0 until 360 step 7) {
            for (b in 0 until 360 step 11) {
                val d = angularDistanceDeg(a.toDouble(), b.toDouble())
                assertTrue(d >= 0.0 && d <= 180.0, "angularDistanceDeg($a,$b) = $d")
            }
        }
    }
}
