package peakid.engine.skyline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Guardián de la tabla de orientaciones EXIF.
 *
 * Escrito DESPUÉS de que el error apareciera: la tabla estaba mal en las tres
 * orientaciones que giran los ejes, y solo se vio porque dos de las seis fotos
 * del fixture del motor traen orientación 6. Con fotos sin girar habría pasado
 * en verde, y la variante silenciosa del mismo error devuelve una cresta
 * plausible leída de otra parte de la imagen.
 *
 * La propiedad que lo caza sin depender de ninguna foto: **el mapa tiene que
 * ser una BIYECCIÓN sobre el rectángulo crudo.** Si se equivoca de eje, o se
 * sale (y entonces desborda) o pisa dos veces la misma casilla dejando otra sin
 * visitar — y eso se detecta contando.
 */
class ExifOrientationTest {

    // rectángulo deliberadamente NO cuadrado: con 8x8 un cambio de eje pasa
    // desapercibido, que es la trampa del guardián sobre datos uniformes
    private val w = 7
    private val h = 11

    @Test
    fun cadaOrientacionEsUnaBiyeccionSobreElRectanguloCrudo() {
        for (orientacion in 1..8) {
            val gira = exifSwapsAxes(orientacion)
            val crudoW = if (gira) h else w
            val crudoH = if (gira) w else h
            val visitadas = HashSet<Int>()

            for (y in 0 until h) {
                for (x in 0 until w) {
                    val rx = exifRawX(x, y, orientacion, w, h)
                    val ry = exifRawY(x, y, orientacion, w, h)
                    assertTrue(
                        rx in 0 until crudoW,
                        "orientación $orientacion: x cruda $rx fuera de [0,$crudoW) " +
                            "desde ($x,$y) — se ha usado la dimensión equivocada",
                    )
                    assertTrue(
                        ry in 0 until crudoH,
                        "orientación $orientacion: y cruda $ry fuera de [0,$crudoH) " +
                            "desde ($x,$y)",
                    )
                    assertTrue(
                        visitadas.add(ry * crudoW + rx),
                        "orientación $orientacion: ($rx,$ry) visitada dos veces; " +
                            "el mapa no es una biyección y hay píxeles sin leer",
                    )
                }
            }
            assertEquals(
                w * h, visitadas.size,
                "orientación $orientacion: no se cubre el rectángulo crudo entero",
            )
        }
    }

    @Test
    fun lasEsquinasVanDondeDiceElConvenio() {
        // 1 es la identidad
        assertEquals(0, exifRawX(0, 0, 1, w, h))
        assertEquals(0, exifRawY(0, 0, 1, w, h))
        // 6 = 90° horario: la esquina superior izquierda de lo que se VE sale
        // de la inferior izquierda del crudo
        assertEquals(0, exifRawX(0, 0, 6, w, h))
        assertEquals(w - 1, exifRawY(0, 0, 6, w, h))
        // 8 = 90° antihorario: sale de la superior derecha
        assertEquals(h - 1, exifRawX(0, 0, 8, w, h))
        assertEquals(0, exifRawY(0, 0, 8, w, h))
        // 3 = 180°
        assertEquals(w - 1, exifRawX(0, 0, 3, w, h))
        assertEquals(h - 1, exifRawY(0, 0, 3, w, h))
    }

    @Test
    fun unaOrientacionDesconocidaLanzaEnVezDePasarDeLargo() {
        // Pasar de largo daría una foto girada procesada sin girar: plausible
        // y equivocada, que es el fallo callado que este proyecto persigue.
        assertFailsWith<IllegalArgumentException> { exifRawX(0, 0, 9, w, h) }
        assertFailsWith<IllegalArgumentException> { exifRawY(0, 0, 0, w, h) }
    }
}
