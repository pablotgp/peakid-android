package peakid.engine.geo

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TESTS DORADOS — el contrato del proyecto, portado de `tests/test_golden.py`.
 *
 * NO modificar los valores esperados sin aprobación explícita del usuario. Si
 * un test dorado falla, el bug está en el código, no en el test. Ajustar el
 * valor esperado para que pase es la peor cosa que se puede hacer en este repo.
 *
 * Aquí están los casos 1 a 4, que son los que solo dependen de `geo/`. Los
 * casos 5 (DEM), 6 (visibilidad) y 7 (recuperar un desplazamiento sintético)
 * llegan con `dem/`, `horizon/` y `align/`.
 */
class GeoGoldenTest {

    // ---- Caso 1: curvatura ------------------------------------------------
    // 10 km -> 6.8 m | 30 km -> 61.5 m | 60 km -> 245.9 m. Tolerancia +-2%.

    @Test
    fun caso1_curvaturaEnLosTresPuntosDeReferencia() {
        assertRelative(6.8, curvatureDropM(10_000.0), 0.02, "caída a 10 km")
        assertRelative(61.5, curvatureDropM(30_000.0), 0.02, "caída a 30 km")
        assertRelative(245.9, curvatureDropM(60_000.0), 0.02, "caída a 60 km")
    }

    @Test
    fun caso1_atajoEnKilometrosCoincideConLaFormula() {
        // drop_m ~= 0.0683 * d_km^2, el atajo documentado en CLAUDE.md
        for (dKm in listOf(5.0, 10.0, 30.0, 60.0, 150.0)) {
            assertRelative(0.0683 * dKm * dKm, curvatureDropM(dKm * 1000.0), 0.01, "atajo a $dKm km")
        }
    }

    // ---- Caso 2: Puerta del Sol -> Peñalara -------------------------------

    private val solLatDeg = 40.4168
    private val solLonDeg = -3.7038
    private val solEyeM = 650.0
    private val penalaraLatDeg = 40.8508
    private val penalaraLonDeg = -3.9578
    private val penalaraM = 2428.0

    @Test
    fun caso2_distancia() {
        val dM = haversineM(solLatDeg, solLonDeg, penalaraLatDeg, penalaraLonDeg)
        assertTrue(
            abs(dM - 52_900.0) <= 1_000.0,
            "distancia Sol->Peñalara: esperada ~52.9 km +-1 km, obtenida ${dM / 1000.0} km",
        )
    }

    @Test
    fun caso2_azimut() {
        // ~336deg, noroeste. Si sale otra cosa hay un signo o un orden lat/lon
        // invertido: es el test que caza el convenio de azimut.
        val az = azimuthDeg(solLatDeg, solLonDeg, penalaraLatDeg, penalaraLonDeg)
        assertTrue(
            abs(wrapDeltaDeg(az - 336.0)) <= 1.0,
            "azimut Sol->Peñalara: esperado ~336deg +-1deg, obtenido ${az}deg",
        )
    }

    @Test
    fun caso2_elevacionConCurvatura() {
        val dM = haversineM(solLatDeg, solLonDeg, penalaraLatDeg, penalaraLonDeg)
        val elevDeg = elevationDeg(dM, solEyeM, penalaraM)
        assertTrue(
            abs(elevDeg - 1.72) <= 0.05,
            "elevación con curvatura: esperada ~1.72deg +-0.05deg, obtenida ${elevDeg}deg",
        )
    }

    @Test
    fun caso2_elevacionSinCurvaturaEsElControl() {
        // Test de CONTROL: si el valor principal diera 1.93, faltaría la
        // corrección de curvatura. El valor sin curvatura se calcula aquí a
        // mano a propósito: el motor NO ofrece forma de desactivarla, y no
        // debe ofrecerla nunca.
        val dM = haversineM(solLatDeg, solLonDeg, penalaraLatDeg, penalaraLonDeg)
        val sinCurvaturaDeg = atan2(penalaraM - solEyeM, dM) * 180.0 / kotlin.math.PI
        assertTrue(
            abs(sinCurvaturaDeg - 1.93) <= 0.05,
            "elevación sin curvatura: esperada ~1.93deg, obtenida ${sinCurvaturaDeg}deg",
        )
        // y la corrección tiene que estar MOVIENDO el resultado
        val conCurvaturaDeg = elevationDeg(dM, solEyeM, penalaraM)
        assertTrue(
            conCurvaturaDeg < sinCurvaturaDeg - 0.1,
            "la curvatura no está bajando la elevación: $conCurvaturaDeg vs $sinCurvaturaDeg",
        )
    }

    // ---- Caso 3: simetría --------------------------------------------------

    @Test
    fun caso3_azimutIdaYVueltaDifierenEn180() {
        val pares = listOf(
            listOf(40.4168, -3.7038, 40.8508, -3.9578),   // Sol -> Peñalara
            listOf(36.7450, -4.0900, 36.8800, -4.0500),   // Málaga -> norte
            listOf(43.2775, -4.8189, 43.1925, -4.8558),   // Picos de Europa
            listOf(36.7450, -4.0900, 37.0540, -3.3110),   // costa -> Sierra Nevada
        )
        for (p in pares) {
            val ida = azimuthDeg(p[0], p[1], p[2], p[3])
            val vuelta = azimuthDeg(p[2], p[3], p[0], p[1])
            val diff = abs(wrapDeltaDeg(ida - vuelta))
            assertTrue(
                abs(diff - 180.0) <= 0.5,
                "azimut ida $ida y vuelta $vuelta deberían diferir 180deg +-0.5, difieren $diff",
            )
        }
    }

    // ---- Caso 4: sentido ---------------------------------------------------
    //
    // El caso 4 tiene DOS lecturas y en Python están las dos: `test_sentido`
    // comprueba el AZIMUT hacia rumbos cardinales, y `test_destino_sentido`
    // comprueba que avanzar con un rumbo mueve la coordenada en el sentido
    // correcto. Portar solo la segunda pierde un guardián del convenio de
    // `atan2` — medido: con los argumentos intercambiados, `test_sentido` cae
    // (90.0 donde se esperaba 0.0) y la lectura de `destination_point` pasa
    // verde, porque no llama a `azimuth_deg` en ningún momento.

    @Test
    fun caso4_sentidoDelAzimutEnRumbosCardinales() {
        // Norte: exacto en cualquier latitud (mismo meridiano).
        assertEquals(0.0, azimuthDeg(40.0, -3.0, 41.0, -3.0), 1e-9)
        // Este/oeste: solo exacto en el ecuador, porque los meridianos
        // convergen y el azimut inicial de un círculo máximo se desvía.
        assertEquals(90.0, azimuthDeg(0.0, 0.0, 0.0, 1.0), 1e-9)
        assertEquals(270.0, azimuthDeg(0.0, 0.0, 0.0, -1.0), 1e-9)
        // Sur: el que cruza el corte de 0/360, donde el `%` mal portado
        // también se manifiesta.
        assertEquals(180.0, azimuthDeg(40.0, -3.0, 39.0, -3.0), 1e-9)
    }

    @Test
    fun caso4_azimutCeroSubeLaLatitud() {
        val out = DoubleArray(2)
        destinationPointDeg(40.0, -3.0, 0.0, 10_000.0, out)
        assertTrue(out[0] > 40.0, "con azimut 0deg la latitud debe AUMENTAR, quedó ${out[0]}")
        assertTrue(
            abs(out[1] - (-3.0)) < 1e-6,
            "con azimut 0deg la longitud debe quedarse casi igual, quedó ${out[1]}",
        )
    }

    @Test
    fun caso4_azimutNoventaSubeLaLongitud() {
        val out = DoubleArray(2)
        destinationPointDeg(40.0, -3.0, 90.0, 10_000.0, out)
        assertTrue(out[1] > -3.0, "con azimut 90deg la longitud debe AUMENTAR, quedó ${out[1]}")
        assertTrue(
            abs(out[0] - 40.0) < 0.01,
            "con azimut 90deg la latitud apenas debe moverse, quedó ${out[0]}",
        )
    }

    @Test
    fun caso4_idaYVueltaRecuperaElPuntoDePartida() {
        // destination_point y azimuth/haversine son inversos: comprobarlo caza
        // un error de signo que los cuatro casos anteriores podrían no ver.
        val latDeg = 36.7450
        val lonDeg = -4.0900
        for (bearingDeg in listOf(0.0, 45.0, 90.0, 170.0, 200.0, 285.0, 359.0)) {
            val out = DoubleArray(2)
            destinationPointDeg(latDeg, lonDeg, bearingDeg, 25_000.0, out)
            val azVuelta = azimuthDeg(latDeg, lonDeg, out[0], out[1])
            assertTrue(
                abs(wrapDeltaDeg(azVuelta - bearingDeg)) < 0.001,
                "rumbo $bearingDeg recuperado como $azVuelta",
            )
            val dM = haversineM(latDeg, lonDeg, out[0], out[1])
            assertTrue(abs(dM - 25_000.0) < 1.0, "distancia recuperada $dM en vez de 25000")
        }
    }

    // ---- La trampa del operador % -----------------------------------------

    @Test
    fun normalizacionDeAzimutNuncaDevuelveNegativos() {
        // En Python `-10.0 % 360.0` es 350.0; en Kotlin `%` conserva el signo
        // del dividendo y da -10.0. Traducir el motor literalmente habría
        // producido azimuts negativos en todo el cuadrante noroeste.
        assertEquals(350.0, normalizeAzimuthDeg(-10.0), 1e-9)
        assertEquals(350.0, normalizeAzimuthDeg(-370.0), 1e-9)
        assertEquals(0.0, normalizeAzimuthDeg(360.0), 1e-9)
        assertEquals(0.0, normalizeAzimuthDeg(-360.0), 1e-9)
        assertEquals(336.0, normalizeAzimuthDeg(-24.0), 1e-9)
        for (raw in listOf(-1000.0, -360.5, -0.001, 0.0, 45.0, 359.999, 720.7)) {
            val n = normalizeAzimuthDeg(raw)
            assertTrue(n >= 0.0 && n < 360.0, "normalizeAzimuthDeg($raw) salió $n, fuera de [0,360)")
        }
    }

    @Test
    fun elAzimutSiempreCaeEnElRangoDeclarado() {
        // barrido alrededor de un punto: el cuadrante noroeste es donde el
        // fallo del `%` se manifestaría
        var lat = 30.0
        while (lat <= 60.0) {
            var lon = -10.0
            while (lon <= 5.0) {
                val az = azimuthDeg(45.0, -3.0, lat, lon)
                assertTrue(az >= 0.0 && az < 360.0, "azimut a ($lat,$lon) salió $az")
                lon += 2.5
            }
            lat += 5.0
        }
    }

    @Test
    fun wrapDeltaDejaLasDiferenciasEnMenos180A180() {
        assertEquals(-24.0, wrapDeltaDeg(336.0), 1e-9)
        assertEquals(10.0, wrapDeltaDeg(370.0), 1e-9)
        assertEquals(-180.0, wrapDeltaDeg(180.0), 1e-9)
        assertEquals(0.0, wrapDeltaDeg(0.0), 1e-9)
        for (raw in listOf(-540.0, -181.0, -3.0, 0.0, 179.0, 181.0, 900.0)) {
            val w = wrapDeltaDeg(raw)
            assertTrue(w >= -180.0 && w < 180.0, "wrapDeltaDeg($raw) salió $w")
        }
    }

    // ---- Coherencia escalar <-> array -------------------------------------

    @Test
    fun destinationPointsCoincideConLaVersionEscalar() {
        val distancesM = DoubleArray(200) { 30.0 * (it + 1) }
        val lat = DoubleArray(distancesM.size)
        val lon = DoubleArray(distancesM.size)
        val scalar = DoubleArray(2)
        for (bearingDeg in listOf(0.0, 33.3, 90.0, 187.5, 300.0)) {
            destinationPointsDeg(36.745, -4.09, bearingDeg, distancesM, lat, lon)
            for (i in distancesM.indices) {
                destinationPointDeg(36.745, -4.09, bearingDeg, distancesM[i], scalar)
                assertEquals(scalar[0], lat[i], 1e-12, "lat en i=$i rumbo $bearingDeg")
                assertEquals(scalar[1], lon[i], 1e-12, "lon en i=$i rumbo $bearingDeg")
            }
        }
    }

    @Test
    fun elevationsCoincideConLaVersionEscalarYPropagaNaN() {
        val distancesM = doubleArrayOf(1000.0, 5000.0, 30_000.0, 90_000.0)
        val terrainM = doubleArrayOf(500.0, 1200.0, Double.NaN, 2400.0)
        val out = DoubleArray(distancesM.size)
        elevationsDeg(distancesM, 700.0, terrainM, out)
        for (i in distancesM.indices) {
            if (terrainM[i].isNaN()) {
                assertTrue(out[i].isNaN(), "el void en i=$i debe propagarse como NaN, salió ${out[i]}")
            } else {
                assertEquals(elevationDeg(distancesM[i], 700.0, terrainM[i]), out[i], 1e-12)
            }
        }
    }

    // ---- Utilidad ----------------------------------------------------------

    private fun assertRelative(expected: Double, actual: Double, tolerance: Double, what: String) {
        val error = abs(actual - expected) / abs(expected)
        assertTrue(
            error <= tolerance,
            "$what: esperado $expected, obtenido $actual (error relativo ${error * 100}%)",
        )
    }
}
