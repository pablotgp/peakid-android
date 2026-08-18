package peakid.engine.skyline

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume

/**
 * EL CRITERIO DE ACEPTACIÓN DE LA FASE 6.
 *
 * La cresta detectada aquí debe coincidir con la del motor sobre las mismas
 * seis fotos, dentro del cuanto del modelo. Se lee el MISMO fichero que vigila
 * al motor (`tests/data/cresta_referencia.json`), no una copia: una copia se
 * desincroniza y entonces los dos lados pasan comparándose con cosas distintas.
 *
 * **La tolerancia es el cuanto guardado en el fichero, y no se recalcula.** Si
 * saliera del código que este test vigila, bajar la resolución movería a la vez
 * la cresta y su listón y la regresión se taparía a sí misma. Cero tampoco
 * serviría: onnxruntime de escritorio y el de Android son compilaciones
 * distintas y no dan bit a bit lo mismo — de ahí que el motor congelara el
 * cuanto precisamente para esto.
 *
 * Es además el ÚNICO guardián del preproceso (÷255, ImageNet, NCHW, softmax
 * sobre 150 clases, clase 2, reescalado ×4), que no tiene tests unitarios ni
 * aquí ni en el motor. Y es lo que cierra las dos mutaciones que ningún test
 * puro caza en ninguno de los dos lenguajes: `edge_cost` normalizado
 * globalmente y `SMOOTHNESS` a cero.
 */
class CrestaReferenciaTest {

    @Test
    fun laCrestaCoincideConLaDelMotor() {
        Assume.assumeTrue(
            "no se puede comprobar el criterio de aceptación de la fase 6: " +
                Motor.queFalta() + " (raíz del motor: ${Motor.raiz()})",
            Motor.disponible(),
        )

        val referencia = Json.parseToJsonElement(
            Motor.fixture.readText(Charsets.UTF_8),
        ).jsonObject
        val paso = referencia["column_stride"]!!.jsonPrimitive.int
        val fotos = referencia["photos"]!!.jsonArray
        assertTrue(fotos.isNotEmpty(), "fixture vacío")

        // el modelo se carga UNA vez: son 15 MB y seis fotos
        SegformerSky(Motor.modelo.readBytes()).use { modelo ->
            for (entrada in fotos) {
                val e = entrada.jsonObject
                val nombre = e["photo"]!!.jsonPrimitive.content
                val fichero = Motor.foto(nombre)
                assertTrue(fichero.isFile, "falta la foto $nombre en ${Motor.raiz()}")

                val corta = nombre.take(28)
                val img = cargarOrientadaYDecimada(fichero)

                // la orientación EXIF se comprueba ANTES que nada: si una foto
                // girada entrara sin girar, la cresta saldría plausible y
                // equivocada, y el resto del test mediría sobre otra imagen
                assertEquals(
                    e["width"]!!.jsonPrimitive.int, img.photoWidth,
                    "$corta: ancho orientado",
                )
                assertEquals(
                    e["height"]!!.jsonPrimitive.int, img.photoHeight,
                    "$corta: alto orientado",
                )
                assertEquals(e["step"]!!.jsonPrimitive.int, img.step, "$corta: paso de trabajo")

                val cresta = detectSkyline(
                    img.rgb, img.width, img.height, img.step, modelo,
                )

                // el submuestreo de trabajo es parte del contrato: si cambia,
                // las columnas dejan de ser comparables
                assertEquals(
                    e["n_columns"]!!.jsonPrimitive.int, cresta.columnsPx.size,
                    "$corta: número de columnas de trabajo",
                )
                val columnasEsperadas = e["columns_px"]!!.jsonArray
                for (i in columnasEsperadas.indices) {
                    val obtenida = cresta.columnsPx[i * paso]
                    val esperada = columnasEsperadas[i].jsonPrimitive.double
                    assertTrue(
                        abs(obtenida - esperada) < 1e-6,
                        "$corta: columna $i vale $obtenida y se esperaba $esperada",
                    )
                }

                val cuanto = e["quantum_px"]!!.jsonPrimitive.double
                val filasEsperadas = e["rows_px"]!!.jsonArray
                var peor = 0.0
                var peorEn = -1
                val desvios = DoubleArray(filasEsperadas.size)
                for (i in filasEsperadas.indices) {
                    val d = abs(cresta.rowsPx[i * paso] - filasEsperadas[i].jsonPrimitive.double)
                    desvios[i] = d
                    if (d > peor) { peor = d; peorEn = i * paso }
                }
                desvios.sort()
                val mediana = desvios[desvios.size / 2]
                println(
                    "  $corta: desvío máx ${"%.1f".format(peor)} px " +
                        "(cuanto ${"%.1f".format(cuanto)}), mediana ${"%.1f".format(mediana)} px",
                )
                assertTrue(
                    peor <= cuanto,
                    "$corta: la cresta se desvía ${"%.1f".format(peor)} px de la del motor " +
                        "en la columna $peorEn, por encima del cuanto del modelo " +
                        "(${"%.1f".format(cuanto)} px). Mediana ${"%.1f".format(mediana)} px",
                )

                // La cobertura mide OTRA cosa que las filas: `valid` no dice
                // "encontré cresta" —la DP siempre devuelve un camino entero—
                // sino "aquí el camino es fiable". Una mutación puede dejar las
                // filas en su sitio y hundir la fiabilidad, o al revés.
                val cobertura = cresta.valid.count { it }.toDouble() / cresta.valid.size
                val esperada = e["coverage"]!!.jsonPrimitive.double
                assertTrue(
                    abs(cobertura - esperada) <= 0.02,
                    "$corta: cobertura ${"%.1f".format(100 * cobertura)}%, " +
                        "se esperaba ${"%.1f".format(100 * esperada)}%",
                )
            }
        }
    }
}
