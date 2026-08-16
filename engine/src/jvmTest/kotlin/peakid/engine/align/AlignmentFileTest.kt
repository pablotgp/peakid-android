package peakid.engine.align

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * El `.align.json` que exporta la app.
 *
 * Estos tests comprueban la FORMA. La comprobación que de verdad decide —que
 * Python lo lea y el arnés lo consuma sin conversión— la hace
 * `scripts/verify_align_roundtrip.py` del repo del motor sobre un fichero
 * escrito por este código, no sobre uno redactado a mano.
 */
class AlignmentFileTest {

    private fun documento() = AlignmentDocument(
        photo = "141721.jpg",
        createdUtc = "2026-08-16T03:14:15Z",
        notes = "exportado por la app, ajuste manual sin ayuda automática",
        observer = Observer(
            latDeg = 36.748457, lonDeg = -4.086678, eyeM = 6.291128319993825,
            source = "exif",
        ),
        seed = mapOf(
            "azimuth_deg" to SeedEntry(0.0, "default"),
            "hfov_deg" to SeedEntry(67.38013505195957, "exif"),
            "pitch_deg" to SeedEntry(0.0, "default"),
            "roll_deg" to SeedEntry(0.0, "default"),
        ),
        alignment = AlignmentParams(15.4, 50.5, 4.0, 0.2).toValues(),
        image = ImageInfo(3060, 4080),
        provenance = Provenance.of(
            searchUsed = false,
            autoPitchRoll = false,
            manualReview = false,
            reviewKind = "correccion",
            reviewNote = "ajustado a mano contra los topónimos",
        ),
    )

    @Test
    fun elBloqueAlignmentLlevaExactamenteCuatroClaves() {
        // RESTRICCIÓN DEL CONSUMIDOR: el arnés hace
        // AlignmentParams(**alineamiento["alignment"]), así que una clave de
        // más ahí revienta a Python con TypeError. Es el error más fácil de
        // cometer al añadir metadatos.
        val texto = encodeAlignment(documento())
        val bloque = texto.substringAfter("\"alignment\": {").substringBefore("}")
        val claves = Regex("\"(\\w+)\":").findAll(bloque).map { it.groupValues[1] }.toList()
        assertEquals(
            listOf("azimuth_deg", "hfov_deg", "pitch_deg", "roll_deg"),
            claves,
            "el bloque alignment no puede llevar nada más",
        )
    }

    @Test
    fun elObservadorLlevaLasTresClavesQueElArnesIndexa() {
        val texto = encodeAlignment(documento())
        for (clave in listOf("lat_deg", "lon_deg", "eye_m")) {
            assertTrue("\"$clave\":" in texto, "falta observer.$clave")
        }
    }

    @Test
    fun manualYReviewedSeDerivanNoSeDeclaran() {
        // Sin ayuda automática: manual y, por tanto, sirve como VERDAD.
        val limpio = Provenance.of()
        assertTrue(limpio.manual)
        assertTrue(limpio.reviewed)

        // Con ayuda automática y SIN revisar: no sirve como verdad. Es la
        // distinción que costó una vuelta atrás en el motor.
        val sinRevisar = Provenance.of(searchUsed = true, autoPitchRoll = true)
        assertTrue(!sinRevisar.manual)
        assertTrue(!sinRevisar.reviewed)

        // Con ayuda automática pero REVISADA: vuelve a servir.
        val revisado = Provenance.of(autoPitchRoll = true, manualReview = true)
        assertTrue(!revisado.manual)
        assertTrue(revisado.reviewed)
    }

    @Test
    fun reviewKindSeDeclaraYNoSeDeduceDelDelta() {
        // En el motor hubo una revisión que corrigió de verdad —cambió de
        // hipótesis por los topónimos— y no dejó delta numérico. Deducir el
        // tipo de revisión de que el delta esté vacío afirma algo falso.
        val corrigioSinDelta = Provenance.of(
            searchUsed = true, manualReview = true,
            reviewDelta = emptyMap(), reviewKind = "correccion",
        )
        assertEquals("correccion", corrigioSinDelta.reviewKind)
        assertTrue(corrigioSinDelta.reviewDelta.isEmpty())
        assertTrue(corrigioSinDelta.reviewed)
    }

    @Test
    fun idaYVueltaConservaLosValores() {
        val original = documento()
        val vuelta = decodeAlignment(encodeAlignment(original))
        assertEquals(original, vuelta)
    }

    @Test
    fun elNombreDelJsonSaleDelNombreDeLaFoto() {
        assertEquals("141721.align.json", alignmentFileName("141721.jpg"))
        assertEquals("IMG_20240210_122140.align.json", alignmentFileName("IMG_20240210_122140.jpg"))
        assertEquals("sin_extension.align.json", alignmentFileName("sin_extension"))
    }

    @Test
    fun escribeUnFicheroRealParaLaComprobacionCruzada() {
        // Deja en build/ un fichero de verdad, escrito por este código, para
        // que el script de Python lo consuma. NO se redacta a mano: eso
        // comprobaría que sé escribir JSON, no que el escritor sea correcto.
        val destino = File("build/roundtrip/141721.align.json")
        destino.parentFile.mkdirs()
        destino.writeText(encodeAlignment(documento()), Charsets.UTF_8)
        assertTrue(destino.isFile && destino.length() > 0)
        println("align.json escrito en ${destino.absolutePath}")
    }
}
