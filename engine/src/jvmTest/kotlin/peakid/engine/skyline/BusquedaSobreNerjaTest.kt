package peakid.engine.skyline

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Assume
import peakid.engine.Packs
import peakid.engine.align.searchAlignment
import peakid.engine.horizon.horizonProfile
import peakid.engine.terrain.PackTerrain

/**
 * CASO DE VALIDACIÓN DE LA FASE 7.
 *
 * De extremo a extremo y sin ninguna ayuda humana: foto → detector → búsqueda.
 * El alineamiento manual de referencia de esa foto, verificado a mano contra
 * topónimos, es
 *
 *     azimut 40.6°   campo 40.2°   inclinación -3.05°   giro -2.05°
 *
 * desde (36.744222, -3.875444). La búsqueda tiene que llegar ahí sola, o
 * quedarse cerca y DECLARARLO — las dos salidas valen, la que no vale es
 * acertar de lejos y presentarlo como bueno.
 *
 * NO se le pasa pista de campo, y es deliberado: el EXIF de esa cámara dice
 * 67.38° donde la verdad es 40.2°, así que sembrar la búsqueda con él dejaría
 * el valor correcto fuera del espacio explorado. No fallaría: devolvería con
 * aplomo el mejor punto de un rango equivocado.
 */
class BusquedaSobreNerjaTest {

    private val nombreFoto =
        "Nerja,_view_from_the_Balcón_de_Europa_to_the_beach__Playa_de_Calahonda_.jpg"

    // del .align.json de referencia
    private val latRef = 36.744222
    private val lonRef = -3.875444
    private val azRef = 40.6
    private val fovRef = 40.2

    @Test
    fun laBusquedaLlegaAlAlineamientoDeReferencia() {
        val foto = Motor.foto(nombreFoto)
        Assume.assumeTrue(
            "falta ${foto.path} o el modelo: ${Motor.queFalta()}",
            foto.isFile && Motor.modelo.isFile,
        )
        val pack = Packs.abrir("axarquia")

        val terreno = PackTerrain(pack)
        val suelo = terreno.elevationM(latRef, lonRef)
        val perfil = horizonProfile(
            latRef, lonRef, (if (suelo.isNaN()) 0.0 else suelo) + 1.7, terreno,
        )

        val img = cargarOrientadaYDecimada(foto)
        val cresta = SegformerSky(Motor.modelo.readBytes()).use { modelo ->
            detectSkyline(img.rgb, img.width, img.height, img.step, modelo)
        }
        val columnas = ArrayList<Double>()
        val filas = ArrayList<Double>()
        for (i in cresta.valid.indices) {
            if (!cresta.valid[i]) continue
            columnas.add(cresta.columnsPx[i])
            filas.add(cresta.rowsPx[i])
        }
        assertTrue(columnas.size >= 20, "cresta insuficiente: ${columnas.size} columnas")

        // Acotado como lo acotaría la app con el sector marcado sobre la sierra:
        // ±30°, que es lo que concede la brújula de un móvil. Es la situación
        // realista, no un regalo — 40.6 está a 10.6° del centro.
        val res = searchAlignment(
            perfil.azimuthsDeg, perfil.elevationsDeg,
            columnas.toDoubleArray(), filas.toDoubleArray(),
            img.photoWidth, img.photoHeight,
            centerAzDeg = 30.0, azMarginDeg = 30.0, fovHintDeg = null,
        )

        assertTrue(res.candidates.isNotEmpty(), "la búsqueda no devolvió candidatos")
        val mejor = res.candidates.first()
        val dAz = abs(mejor.params.azimuthDeg - azRef)
        val dFov = abs(mejor.params.hfovDeg - fovRef)
        println(
            "  Nerja: azimut ${"%.2f".format(mejor.params.azimuthDeg)}° " +
                "(ref $azRef, Δ ${"%.2f".format(dAz)}) · " +
                "campo ${"%.2f".format(mejor.params.hfovDeg)}° " +
                "(ref $fovRef, Δ ${"%.2f".format(dFov)}) · " +
                "error ${"%.1f".format(mejor.errorPx)} px · " +
                "cobertura ${"%.0f".format(100 * mejor.coverage)}% · " +
                "fiable=${res.reliable} ambiguo=${res.ambiguous} " +
                "fovBorde=${res.fovAtEdge} azBorde=${res.azAtEdge}",
        )

        // EL CONTRATO NO ES "acierta": es "acierta O lo declara".
        //
        // Presentar un alineamiento equivocado como bueno es el único fallo
        // inaceptable aquí; quedarse corto y avisar es una respuesta legítima,
        // porque queda la vía manual. Por eso la aserción tiene dos ramas y no
        // una tolerancia a secas.
        val cerca = dAz <= 2.0 && dFov <= 5.0
        assertTrue(
            cerca || !res.reliable,
            "la búsqueda se aleja de la referencia (Δaz ${"%.2f".format(dAz)}°, " +
                "Δcampo ${"%.2f".format(dFov)}°) y AUN ASÍ se declara fiable: " +
                "eso es presentar como bueno un alineamiento que no lo es",
        )
        pack.close()
    }
}
