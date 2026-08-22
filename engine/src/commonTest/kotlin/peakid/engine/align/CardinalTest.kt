package peakid.engine.align

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import peakid.engine.geo.wrapDeltaDeg

class CardinalTest {

    @Test
    fun losOchoRumbosEstanRepartidosCada45Grados() {
        val esperados = listOf(0.0, 45.0, 90.0, 135.0, 180.0, 225.0, 270.0, 315.0)
        assertEquals(esperados, Cardinal.entries.map { it.azimuthDeg })
        assertEquals(
            listOf("N", "NE", "E", "SE", "S", "SO", "O", "NO"),
            Cardinal.entries.map { it.etiqueta },
        )
    }

    @Test
    fun elSectorDelNorteEmpiezaEn315YNoEnMenos45() {
        // 0 - 45 = -45, que es EXACTAMENTE el azimut negativo contra el que
        // avisa CLAUDE.md: no revienta, se dibuja fuera de la tira por la
        // izquierda y el deslizador no alcanza medio sector. El norte es el
        // único rumbo donde la resta se sale por abajo, así que es el único
        // caso que caza esta mutación.
        val (inicio, amplitud) = sectorForCardinal(Cardinal.N)
        assertEquals(315.0, inicio, 1e-9)
        assertEquals(90.0, amplitud, 1e-9)
    }

    @Test
    fun cadaSectorContieneSuRumboYLoDejaEnElCentro() {
        for (c in Cardinal.entries) {
            val (inicio, amplitud) = sectorForCardinal(c)
            assertTrue(inicio in 0.0..360.0, "${c.etiqueta}: inicio $inicio fuera de [0,360]")

            // el rumbo cae a media amplitud del inicio, midiendo por el arco
            // corto para que el cruce del norte no falsee la cuenta
            val desdeInicio = wrapDeltaDeg(c.azimuthDeg - inicio)
            val positivo = if (desdeInicio < 0) desdeInicio + 360.0 else desdeInicio
            assertTrue(
                abs(positivo - amplitud / 2.0) < 1e-9,
                "${c.etiqueta}: el rumbo está a $positivo° del inicio, no a ${amplitud / 2}",
            )
        }
    }

    @Test
    fun elSectorDeNerjaContieneLaRespuesta() {
        // El caso que motivó esto: la respuesta correcta para la foto de Nerja
        // es 40.6°, y el usuario sabía decir "la sierra está al nordeste".
        val (inicio, amplitud) = sectorForCardinal(Cardinal.NE)
        assertEquals(0.0, inicio, 1e-9)
        val desde = wrapDeltaDeg(40.6 - inicio).let { if (it < 0) it + 360.0 else it }
        assertTrue(desde < amplitud, "40.6° queda fuera del sector del NE")
    }

    @Test
    fun elRumboMasCercanoEnvuelvePorElNorte() {
        // El caso que caza la resta a pelo: a 350° el rumbo más cercano es el
        // NORTE, a 10°, no el noroeste a 35°. Restando sin envolver salen 350 y
        // se elige mal en todo el último octante.
        assertEquals(Cardinal.N, nearestCardinal(350.0))
        assertEquals(Cardinal.N, nearestCardinal(10.0))
        assertEquals(Cardinal.NO, nearestCardinal(320.0))
        // y también con un azimut negativo, que no debería llegar pero llega
        assertEquals(Cardinal.N, nearestCardinal(-10.0))
    }

    @Test
    fun cadaRumboEsElMasCercanoASiMismo() {
        for (c in Cardinal.entries) {
            assertEquals(c, nearestCardinal(c.azimuthDeg))
            assertEquals(c, nearestCardinal(c.azimuthDeg + 20.0))
            assertEquals(c, nearestCardinal(c.azimuthDeg - 20.0))
        }
    }
}
