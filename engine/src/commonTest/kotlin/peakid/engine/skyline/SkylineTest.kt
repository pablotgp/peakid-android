package peakid.engine.skyline

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Puerto de los nueve tests PUROS que cubren la segmentación y la DP en el
 * motor. Los otros cuatro necesitan el modelo y las fotos, y viven en
 * `jvmTest`.
 *
 * Se porta contra los TESTS del motor, no contra el enunciado, y cada uno se
 * verifica con la mutación que debería cazarlo. La batería de referencia son
 * las trece mutaciones que el motor caza hoy; en Kotlin deben caer las mismas.
 */
class SkylineTest {

    // ---------------------------------------------------------------- coste

    @Test
    fun costeDeFronteraMiraArribaYAbajo() {
        // `boundary_cost` no busca un BORDE, busca la PARTICIÓN cielo/terreno
        // que mejor explica la columna entera. Por eso suma los dos errores.
        val alto = 40
        val ancho = 6
        val frontera = 25
        val cielo = DoubleArray(alto * ancho)
        for (r in 0 until frontera) for (c in 0 until ancho) cielo[r * ancho + c] = 1.0

        val coste = boundaryCost(cielo, ancho, alto)

        // la fila r no entra en ninguno de los dos términos, así que la
        // frontera cae ENTRE dos filas y las dos cuestan cero
        for (c in 0 until ancho) {
            assertTrue(abs(coste[frontera * ancho + c]) < 1e-9)
            assertTrue(abs(coste[(frontera - 1) * ancho + c]) < 1e-9)
        }
        for (c in 0 until ancho) {
            var mejor = 0
            for (r in 1 until alto) {
                if (coste[r * ancho + c] < coste[mejor * ancho + c]) mejor = r
            }
            assertTrue(mejor == frontera - 1 || mejor == frontera, "mínimo en $mejor")
        }
        // sin el término de abajo, la fila 0 costaría cero y el mínimo sería
        // ambiguo; sin el de arriba, la última dejaría el cielo sin explicar
        for (c in 0 until ancho) {
            assertTrue(coste[0 * ancho + c] > 0.1)
            assertTrue(coste[(alto - 1) * ancho + c] > 0.1)
        }
        assertTrue(coste.all { it >= -1e-12 }, "el coste es una distancia")
    }

    @Test
    fun costeDeBordeEsRelativoACadaColumna() {
        // Normalizar por columna, no globalmente: si no, una columna en calima
        // o a contraluz tiene bordes débiles en absoluto y quedaría descartada
        // entera frente a otra bien iluminada.
        val alto = 60
        val ancho = 8
        val rgb = DoubleArray(alto * ancho * 3)
        for (r in 30 until alto) {
            for (c in 0 until ancho) {
                val v = if (c < 4) 200.0 else 8.0   // el mismo escalón, 25x más débil
                for (k in 0 until 3) rgb[(r * ancho + c) * 3 + k] = v
            }
        }
        val coste = edgeCost(rgb, ancho, alto)

        val fuerte = minOf(coste[29 * ancho + 0], coste[30 * ancho + 0])
        val debil = minOf(coste[29 * ancho + 4], coste[30 * ancho + 4])
        assertTrue(abs(fuerte - debil) < 1e-6, "fuerte=$fuerte debil=$debil")
        assertTrue(fuerte < 0.05)
        assertTrue(coste[10 * ancho + 0] > 0.9 && coste[10 * ancho + 4] > 0.9)
    }

    // ------------------------------------------------------------------- DP

    @Test
    fun laDpPrefiereElCaminoContinuo() {
        // El caso cresta->nube->cresta reducido a su esencia. Se comprueba el
        // MECANISMO y que λ lo gobierna, no que la λ de producción rechace
        // cualquier nube: se mantiene deliberadamente permisiva.
        val alto = 120
        val ancho = 200
        val coste = DoubleArray(alto * ancho) { 1.0 }
        for (c in 0 until ancho) coste[60 * ancho + c] = 0.30      // cresta continua
        for (c in 90 until 110) coste[10 * ancho + c] = 0.20       // "nube", corta

        val suelto = bestPath(coste.copyOf(), ancho, alto, jumpLimit = 60, smoothness = 0.0)
        assertEquals(10, suelto.rows[100], "sin penalización el camino salta a la nube")

        val rigido = bestPath(coste.copyOf(), ancho, alto, jumpLimit = 60, smoothness = 3.0)
        assertTrue(rigido.rows.all { it == 60 }, "con penalización gana la cresta")
        assertEquals(ancho, rigido.margin.size)
        assertTrue(rigido.margin.all { it >= 0.0 })
    }

    @Test
    fun laLambdaControlaElAplanado() {
        val alto = 100
        val ancho = 60
        val rng = Random(0)
        val coste = DoubleArray(alto * ancho) { rng.nextDouble() * 0.2 }
        for (c in 0 until ancho) {
            val fila = (50 + 30 * kotlin.math.sin(c / 3.0)).toInt()
            coste[fila * ancho + c] = 0.0
        }
        val suelto = bestPath(coste.copyOf(), ancho, alto, jumpLimit = 40, smoothness = 0.0)
        val rigido = bestPath(coste.copyOf(), ancho, alto, jumpLimit = 40, smoothness = 8.0)

        assertTrue(variacionTotal(suelto.rows) > variacionTotal(rigido.rows))
        assertTrue(saltoMaximo(rigido.rows) <= saltoMaximo(suelto.rows))
    }

    @Test
    fun elMargenMideElCaminoCompletoNoLaMitad() {
        // Un camino no tiene sentido de marcha, así que espejar la imagen
        // tiene que espejar el margen. El barrido de ida SOLO es asimétrico
        // por construcción: en la primera columna no tiene historia detrás.
        //
        // EL ALTO NO ES DECORATIVO: el margen mira las filas a más de
        // MARGIN_PX (40) del camino, así que con una imagen de 60 filas y el
        // camino por el centro NO HAY NINGUNA fila lejana y el margen sale
        // cero en todas las columnas. Así se escribió primero, y comparaba
        // ceros con ceros: el mismo patrón que el guardián sobre mar llano que
        // CLAUDE.md ya recoge. Con 200 filas el margen mide de verdad, y la
        // aserción de abajo lo comprueba antes de comparar nada.
        val alto = 200
        val ancho = 40
        val rng = Random(7)
        val coste = DoubleArray(alto * ancho) { rng.nextDouble() * 0.3 }
        for (c in 0 until ancho) coste[100 * ancho + c] = 0.1
        for (c in 15 until 25) {
            for (r in 0 until alto) coste[r * ancho + c] = 0.3
        }
        val espejo = DoubleArray(alto * ancho)
        for (r in 0 until alto) {
            for (c in 0 until ancho) espejo[r * ancho + c] = coste[r * ancho + (ancho - 1 - c)]
        }

        val directo = bestPath(coste, ancho, alto, jumpLimit = 10)
        val invertido = bestPath(espejo, ancho, alto, jumpLimit = 10)

        // PRIMERO: que haya margen que medir. Sin esto el test compara ceros.
        assertTrue(
            directo.margin.any { it > 1e-9 },
            "el margen es cero en todas las columnas: no hay filas a más de " +
                "MARGIN_PX del camino y este test no mide nada",
        )

        for (c in 0 until ancho) {
            val d = abs(directo.margin[c] - invertido.margin[ancho - 1 - c])
            assertTrue(d < 1e-9, "columna $c: el margen depende del sentido de marcha ($d)")
        }
        // NO se afirma que el margen sea menor donde falta evidencia: medido
        // en el motor, en una columna plana es ALTO, porque alejarse y volver
        // paga el salto dos veces y eso domina sobre el coste local.
        assertTrue(directo.margin.all { it >= 0.0 })
    }

    // ------------------------------------------- trampas propias del puerto

    @Test
    fun elPercentil95InterpolaComoNumpy() {
        // `np.percentile` NO devuelve "el elemento en la posición 0.95·n":
        // interpola linealmente entre los dos vecinos. En `edgeCost` fija la
        // referencia de cada columna, así que redondear en vez de interpolar
        // desplaza el umbral de TODAS las columnas a la vez.
        //
        // No hay test equivalente en el motor porque allí es numpy quien lo
        // hace. Es de las piezas donde Kotlin y Python difieren en silencio,
        // igual que el operador `%`.
        val v = DoubleArray(21) { it.toDouble() }        // 0..20
        // pos = 0.95 * 20 = 19.0 exacto -> no hay interpolación que hacer
        assertTrue(abs(percentile95(v) - 19.0) < 1e-12)

        val w = DoubleArray(11) { it * 10.0 }            // 0,10,...,100
        // pos = 0.95 * 10 = 9.5 -> entre 90 y 100, mitad: 95, no 90 ni 100
        assertTrue(abs(percentile95(w) - 95.0) < 1e-12, "obtenido ${percentile95(w)}")

        assertTrue(abs(percentile95(doubleArrayOf(7.0)) - 7.0) < 1e-12)
        // y ordena: el percentil no depende del orden de entrada
        assertTrue(
            abs(percentile95(doubleArrayOf(100.0, 0.0, 50.0)) -
                percentile95(doubleArrayOf(0.0, 50.0, 100.0))) < 1e-12,
        )
    }

    @Test
    fun laInterpolacionRepiteElBordeNoExtrapola() {
        // `np.interp` mantiene CONSTANTE el valor fuera del rango de muestras.
        // Extrapolar mandaría el centro de la banda fuera de la imagen en las
        // columnas de los bordes, que es donde el modelo ya es menos fiable:
        // la banda se saldría del lienzo y dejaría esas columnas sin acotar.
        val ys = DoubleArray(20)
        ys[5] = 100.0
        ys[15] = 200.0
        val out = interp(20, intArrayOf(5, 15), ys)

        assertTrue(abs(out[0] - 100.0) < 1e-12, "antes de la primera muestra: borde repetido")
        assertTrue(abs(out[5] - 100.0) < 1e-12)
        assertTrue(abs(out[10] - 150.0) < 1e-12, "en medio: lineal")
        assertTrue(abs(out[15] - 200.0) < 1e-12)
        assertTrue(abs(out[19] - 200.0) < 1e-12, "tras la última muestra: borde repetido")
    }

    @Test
    fun elTopeDeSaltoNoSeAtaALaBanda() {
        // El fallo histórico era `min(25, banda)`, que con la banda de 5 del
        // Naranjo de Bulnes daba un tope de 5: el salto máximo quedaba
        // estrangulado ahí, con un 2.25% de columnas contra él, y la pared
        // salía en diagonal.
        //
        // De las dos mitades del `max`, a 1536 solo trabaja el SUELO. Aquí se
        // comprueban las dos por separado, que es lo que el motor necesita dos
        // tests con modelo para comprobar.
        assertEquals(25, pathJumpLimit(5), "con banda estrecha manda el suelo")
        assertEquals(25, pathJumpLimit(12), "2*12 = 24, todavía por debajo")
        assertEquals(32, pathJumpLimit(16), "al bajar la entrada, manda la banda")
        // y nunca por debajo del suelo, que es lo que `min` rompía
        for (banda in 1..40) assertTrue(pathJumpLimit(banda) >= JUMP_LIMIT_PX)
    }

    @Test
    fun elTopeDeSaltoGobiernaLasParedesVerticales() {
        // Una aguja exige seguir a la banda cuando ESTA salta, no frenarla.
        val alto = 200
        val ancho = 60
        val coste = DoubleArray(alto * ancho) { 1.0 }
        for (c in 0 until 30) coste[30 * ancho + c] = 0.0       // meseta alta
        for (c in 30 until ancho) coste[170 * ancho + c] = 0.0  // valle tras la pared

        val corto = bestPath(coste.copyOf(), ancho, alto, jumpLimit = 5, smoothness = 0.0)
        val largo = bestPath(coste.copyOf(), ancho, alto, jumpLimit = 140, smoothness = 0.0)

        assertTrue(saltoMaximo(corto.rows) <= 5)
        assertTrue(contarSaltos(corto.rows) > 20, "con el tope corto debería bajar en rampa")
        assertEquals(140, saltoMaximo(largo.rows))
        assertTrue(contarSaltos(largo.rows) <= 2, "con el tope suelto debería ser un escalón")
    }

    // ---------------------------------------------------------------- banda

    @Test
    fun laBandaImpideQueElCaminoSeVayaALaNube() {
        val alto = 120
        val ancho = 200
        val coste = DoubleArray(alto * ancho) { 1.0 }
        for (c in 0 until ancho) coste[70 * ancho + c] = 0.5   // cresta, donde dice el modelo
        for (c in 0 until ancho) coste[15 * ancho + c] = 0.0   // "nube": borde más fuerte

        val suelto = bestPath(coste.copyOf(), ancho, alto, jumpLimit = 60, smoothness = 0.1)
        assertEquals(15, mediana(suelto.rows), "sin banda gana la nube")

        val raw = DoubleArray(ancho) { 70.0 }
        val usable = IntArray(ancho) { it }
        val acotado = applyBand(coste.copyOf(), ancho, alto, raw, usable, band = 6)
        val cenido = bestPath(acotado, ancho, alto, jumpLimit = 60, smoothness = 0.1)
        assertEquals(70, mediana(cenido.rows), "con banda la nube queda fuera")

        // la banda no aplasta lo que cae DENTRO
        assertEquals(coste[70 * ancho + 0], acotado[70 * ancho + 0], 1e-12)
        assertTrue(acotado[15 * ancho + 0] > coste[15 * ancho + 0])

        // con menos de dos columnas utilizables no hay centro que interpolar
        val intacto = applyBand(coste.copyOf(), ancho, alto, raw, IntArray(1), band = 6)
        assertTrue(intacto.contentEquals(coste))

        // la anchura sale del cuanto, no de un número redondo
        val cuanto = maskQuantumRows(1020, 1360)
        assertEquals(bandWidth(cuanto), maxOf(4.0, kotlin.math.round(2.0 * cuanto)).toInt())
        assertEquals(4, bandWidth(0.1), "suelo para fotos diminutas")
    }

    // ------------------------------------------------------------- entrada

    @Test
    fun laEntradaDelModeloConservaElAspecto() {
        for ((ancho, alto) in listOf(9248 to 6944, 3060 to 4080, 2551 to 1701)) {
            val (w, h) = inputSize(ancho, alto, 1536)
            assertEquals(0, w % SIZE_MULTIPLE)
            assertEquals(0, h % SIZE_MULTIPLE)
            assertEquals(1536, maxOf(w, h), "el lado largo manda")
            assertTrue(abs((w.toDouble() / h) - (ancho.toDouble() / alto)) < 0.06)
            assertEquals(ancho > alto, w > h, "el eje largo de la entrada es el de la foto")
        }
    }

    @Test
    fun laResolucionDeEntradaEsLaMedidaNoUnNumeroComodo() {
        // 1536 no es una preferencia: es donde el residuo se estanca (8.4 px a
        // 512, 2.0 px a 1536). Bajarlo cuesta resolución medible.
        assertEquals(1536, INPUT_LONG_SIDE)
        assertTrue(abs(maskQuantumRows(1020, 1360) - 3.54) < 0.05)
        // bajar la entrada engorda el cuanto en proporción
        assertTrue(
            abs(maskQuantumRows(1020, 1360, 512) - 3.0 * maskQuantumRows(1020, 1360, 1536)) < 0.1,
        )
        // y el ancho de trabajo tiene que quedarse POR DEBAJO de la entrada, o
        // el reescalado pasa a ser una reducción y la bilineal simple de este
        // puerto deja de coincidir con Pillow (29.7 niveles de gris medidos)
        assertTrue(WORK_MAX_WIDTH < INPUT_LONG_SIDE)
    }

    // --------------------------------------------------------------- ayudas

    private fun variacionTotal(rows: IntArray): Int =
        (1 until rows.size).sumOf { abs(rows[it] - rows[it - 1]) }

    private fun saltoMaximo(rows: IntArray): Int =
        (1 until rows.size).maxOf { abs(rows[it] - rows[it - 1]) }

    private fun contarSaltos(rows: IntArray): Int =
        (1 until rows.size).count { rows[it] != rows[it - 1] }

    private fun mediana(rows: IntArray): Int = rows.sorted()[rows.size / 2]
}
