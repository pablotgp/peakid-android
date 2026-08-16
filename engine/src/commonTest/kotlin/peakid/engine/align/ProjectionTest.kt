package peakid.engine.align

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Proyección pinhole. Puerto de los tres tests de `project_profile` y el de
 * `projected_y_per_column` del motor.
 *
 * Los signos de inclinación y giro son CONTRATO: invertidos, el ajuste manual
 * movería la línea al revés del deslizador y nada fallaría de forma visible.
 */
class ProjectionTest {

    private val w = 4000
    private val h = 3000

    @Test
    fun proyeccion_centroYBordes() {
        val params = AlignmentParams(azimuthDeg = 100.0, hfovDeg = 65.0, pitchDeg = 0.0, rollDeg = 0.0)

        // el centro exacto (azimut central, elevación = pitch) va al centro
        val centro = projectProfile(doubleArrayOf(100.0), doubleArrayOf(0.0), params, w, h)
        assertTrue(centro.usable[0])
        assertEquals(w / 2.0, centro.xPx[0], 1e-6)
        assertEquals(h / 2.0, centro.yPx[0], 1e-6)

        // con pitch=0 y roll=0, azimut central ± hfov/2 a elevación 0 cae
        // EXACTAMENTE en los bordes. Es lo que fija la pinhole frente a la
        // aproximación lineal y, de paso, la fórmula de la focal.
        val bordes = projectProfile(
            doubleArrayOf(100.0 + 32.5, 100.0 - 32.5), doubleArrayOf(0.0, 0.0), params, w, h,
        )
        assertEquals(w.toDouble(), bordes.xPx[0], 1e-6)
        assertEquals(0.0, bordes.xPx[1], 1e-6)
        assertEquals(h / 2.0, bordes.yPx[0], 1e-6)

        // una muestra DETRÁS de la cámara no es proyectable
        val detras = projectProfile(doubleArrayOf(280.0), doubleArrayOf(0.0), params, w, h)
        assertFalse(detras.usable[0])

        // elevación NaN (void del perfil) tampoco, y sin reventar
        val void = projectProfile(doubleArrayOf(100.0), doubleArrayOf(Double.NaN), params, w, h)
        assertFalse(void.usable[0])
    }

    @Test
    fun proyeccion_inclinacionYGiro() {
        // inclinación: mirar hacia arriba exactamente a la elevación de la
        // muestra la centra verticalmente
        val conPitch = projectProfile(
            doubleArrayOf(100.0), doubleArrayOf(3.0),
            AlignmentParams(100.0, 65.0, pitchDeg = 3.0, rollDeg = 0.0), w, h,
        )
        assertTrue(conPitch.usable[0])
        assertEquals(h / 2.0, conPitch.yPx[0], 1e-6)

        // giro positivo = el lado DERECHO del horizonte dibujado BAJA
        val conRoll = projectProfile(
            doubleArrayOf(110.0), doubleArrayOf(0.0),
            AlignmentParams(100.0, 65.0, 0.0, rollDeg = 5.0), w, h,
        )
        assertTrue(conRoll.yPx[0] > h / 2.0)

        // con giro de 90° el desplazamiento horizontal se vuelve VERTICAL por
        // completo: la muestra vuelve al centro en x
        val recto = projectProfile(
            doubleArrayOf(110.0), doubleArrayOf(0.0),
            AlignmentParams(100.0, 65.0, 0.0, rollDeg = 90.0), w, h,
        )
        assertEquals(w / 2.0, recto.xPx[0], 1e-6)
        assertTrue(recto.yPx[0] > h / 2.0)
    }

    @Test
    fun proyeccion_inclinacionPositivaBajaLaLinea() {
        // El signo, aislado. Con inclinación positiva la cámara mira hacia
        // arriba y el horizonte se dibuja MÁS ABAJO. Invertido, el ajuste
        // manual movería la línea al revés del deslizador: plausible pero
        // incorrecto, que es el modo de fallo de este proyecto.
        val neutro = projectProfile(
            doubleArrayOf(100.0), doubleArrayOf(0.0),
            AlignmentParams(100.0, 65.0, 0.0, 0.0), w, h,
        )
        val arriba = projectProfile(
            doubleArrayOf(100.0), doubleArrayOf(0.0),
            AlignmentParams(100.0, 65.0, 2.0, 0.0), w, h,
        )
        assertEquals(h / 2.0, neutro.yPx[0], 1e-6)
        assertTrue(arriba.yPx[0] > neutro.yPx[0])
    }

    @Test
    fun alturaPorColumna() {
        val xPx = doubleArrayOf(100.0, 200.0, 300.0)
        val yPx = doubleArrayOf(50.0, 70.0, 60.0)
        val usable = booleanArrayOf(true, true, true)

        val y = projectedYPerColumn(
            xPx, yPx, usable, doubleArrayOf(50.0, 150.0, 250.0, 400.0),
        )
        assertTrue(y[0].isNaN(), "antes del tramo cubierto")
        assertEquals(60.0, y[1], 1e-9)   // interpolado entre 50 y 70
        assertEquals(65.0, y[2], 1e-9)
        assertTrue(y[3].isNaN(), "después del tramo cubierto")

        // con menos de dos puntos utilizables no hay nada que interpolar, y
        // devolver un número sería inventarlo
        val pobre = projectedYPerColumn(
            xPx, yPx, booleanArrayOf(true, false, false), doubleArrayOf(150.0),
        )
        assertTrue(pobre[0].isNaN())
    }

    @Test
    fun laProyeccionNoEsLineal() {
        // Guardián que el motor no tiene explícito pero que su comentario
        // reclama: con 65° de campo, una regla de tres erraría ~4% del ancho
        // en los bordes. A media distancia del borde la pinhole y la lineal
        // divergen de forma medible, y es lo que separa un alineamiento fino
        // de uno que parece bueno.
        val params = AlignmentParams(0.0, 65.0, 0.0, 0.0)
        val mitad = projectProfile(doubleArrayOf(16.25), doubleArrayOf(0.0), params, w, h)
        val lineal = w / 2.0 + (16.25 / 32.5) * (w / 2.0)
        val pinhole = mitad.xPx[0]
        assertTrue(
            kotlin.math.abs(pinhole - lineal) > 30.0,
            "pinhole $pinhole y lineal $lineal deberían separarse claramente",
        )
    }
}
