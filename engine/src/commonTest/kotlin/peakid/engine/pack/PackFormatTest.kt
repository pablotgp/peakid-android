package peakid.engine.pack

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import peakid.engine.VOID_ELEVATION
import peakid.engine.terrain.PackTerrain

/**
 * Formato del paquete y lectura del terreno, sobre paquetes sintéticos.
 *
 * Herméticos a propósito: no dependen de ningún dato descargado, así que estos
 * sí corren siempre. Los casos dorados que necesitan paquetes reales viven en
 * `jvmTest`.
 */
class PackFormatTest {

    // ---- el orden de bytes ------------------------------------------------

    @Test
    fun losEnterosSeLeenEnLittleEndianYNoEnBigEndian() {
        // 2065 = 0x0811. Little-endian son los bytes 11 08; big-endian, 08 11.
        // El ejemplo NO es arbitrario: es la altitud de La Maroma según el DEM,
        // y leída del revés da 4360, que sigue pareciendo una altitud. Ese es
        // exactamente el fallo que este test existe para cazar.
        val littleEndian = byteArrayOf(0x11, 0x08)
        assertEquals(2065.toShort(), littleEndian.leI16(0))

        val bigEndian = byteArrayOf(0x08, 0x11)
        assertEquals(4360.toShort(), bigEndian.leI16(0))

        // negativos: el void es -32768 = 0x8000
        assertEquals(VOID_ELEVATION, byteArrayOf(0x00, 0x80.toByte()).leI16(0))
    }

    @Test
    fun losEnterosAnchosTambienSonLittleEndian() {
        val bytes = byteArrayOf(0x78, 0x56, 0x34, 0x12, 0x00, 0x00, 0x00, 0x00)
        assertEquals(0x12345678, bytes.leI32(0))
        assertEquals(0x12345678L, bytes.leI64(0))
        assertEquals(0x12345678L, bytes.leU32(0))
    }

    @Test
    fun elArrayDeAlturasCoincideConLaLecturaEscalar() {
        val bytes = byteArrayOf(0x11, 0x08, 0x00, 0x80.toByte(), 0xFF.toByte(), 0xFF.toByte())
        val array = bytes.leI16Array(0, 3)
        for (i in 0 until 3) {
            assertEquals(bytes.leI16(i * 2), array[i], "posición $i")
        }
        assertEquals((-1).toShort(), array[2])
    }

    // ---- geometría de bloques ---------------------------------------------

    // Relleno en coordenadas GLOBALES de nodo (side = 5 -> 4 intervalos por
    // bloque). Dos consecuencias buscadas:
    //
    //  - el valor depende de fila Y columna, así que cualquier desplazamiento
    //    de índice, por pequeño que sea, cambia el número. Un patrón liso
    //    dejaría pasar justo los fallos que esto existe para cazar.
    //  - las aristas compartidas entre bloques vecinos salen DUPLICADAS con el
    //    mismo valor, que es lo que el formato garantiza. Escribir un relleno
    //    local por bloque violaba ese invariante y hacía que un punto sobre una
    //    frontera diera un número distinto según qué bloque lo resolviera.
    private fun nodoGlobal(bloqueFila: Int, bloqueCol: Int) = { r: Int, c: Int ->
        ((bloqueFila * 4 + r) * 100 + (bloqueCol * 4 + c)).toShort()
    }

    private fun packEscalonado() = SyntheticPackBuilder(side = 5)
        .block(0, 0, nodoGlobal(0, 0))
        .block(0, 1, nodoGlobal(0, 1))
        .block(1, 0, nodoGlobal(1, 0))
        .build()

    @Test
    fun lasAristasEntreBloquesEstanDuplicadasConElMismoValor() {
        // Es lo que hace que dé igual a qué lado caiga un punto de frontera. Si
        // dejara de cumplirse, el terreno tendría escalones invisibles justo en
        // los bordes de bloque y el horizonte se descuadraría por ahí.
        val terrain = PackTerrain(packEscalonado())
        // lat 0.75 es el borde sur de (0,0) y el norte de (1,0)
        assertEquals(400.0, terrain.elevationM(0.75, 0.0), 1e-9)
        // lon 0.25 es el borde este de (0,0) y el oeste de (0,1)
        assertEquals(4.0, terrain.elevationM(1.0, 0.25), 1e-9)
    }

    @Test
    fun laFilaCeroEsElBordeNorte() {
        // Bloque (0,0): lat 1.00 (norte) .. 0.75 (sur), lon 0.00 .. 0.25.
        // Con fill = fila*100 + columna, el nodo del borde NORTE es fila 0 -> 0,
        // y el del borde SUR es fila 4 -> 400. Invertir las filas daría 400
        // arriba: un mapa reflejado que sigue pareciendo un mapa.
        val terrain = PackTerrain(packEscalonado())
        assertEquals(0.0, terrain.elevationM(1.0, 0.0), 1e-9, "esquina NO")
        assertEquals(400.0, terrain.elevationM(0.75, 0.0), 1e-9, "esquina SO")
        assertEquals(4.0, terrain.elevationM(1.0, 0.25), 1e-9, "esquina NE")
    }

    @Test
    fun laBilinealInterpolaEntreNodos() {
        val terrain = PackTerrain(packEscalonado())
        // punto medio entre las filas 0 y 1 del bloque (0,0), columna 0:
        // (0 + 100) / 2 = 50
        val latMedia = 1.0 - (0.25 / 4.0) / 2.0
        assertEquals(50.0, terrain.elevationM(latMedia, 0.0), 1e-9)
    }

    @Test
    fun elBordeExactoEntreBloquesNoSeSaleDeLaRejilla() {
        // el borde sur y el este exactos pertenecen a la última fila/columna,
        // donde son su nodo final. Sin ese ajuste, un punto justo en el borde
        // se sale de la rejilla y el rayo se corta donde no debe.
        val pack = packEscalonado()
        assertNotNull(pack.blockOf(0.5, 0.0), "borde sur exacto")
        assertNotNull(pack.blockOf(1.0, 0.5), "borde este exacto")
        assertNull(pack.blockOf(1.5, 0.0), "fuera por el norte")
        assertNull(pack.blockOf(0.9, -0.5), "fuera por el oeste")
    }

    // ---- cobertura: lo que permite decir UNKNOWN --------------------------

    @Test
    fun unBloqueAusenteNoEsAltitudCero() {
        val pack = packEscalonado()      // el bloque (1,1) no se rellenó
        assertEquals(Coverage.PRESENT, pack.coverageAt(1.0, 0.0))
        assertEquals(Coverage.ABSENT, pack.coverageAt(0.6, 0.4),
            "el bloque (1,1) no está en el paquete")
        // y leerlo NO devuelve 0.0: sería inventar un océano donde hay monte
        assertFailsWith<BlockNotFoundException> { pack.blockArray(1, 1) }
        assertFailsWith<BlockNotFoundException> {
            PackTerrain(pack).elevationM(0.6, 0.4)
        }
    }

    @Test
    fun fueraDeLaRejillaTambienEsAusente() {
        assertEquals(Coverage.ABSENT, packEscalonado().coverageAt(45.0, 45.0))
    }

    // ---- voids -------------------------------------------------------------

    @Test
    fun laBilinealDescartaLosVoidsYRenormaliza() {
        // bloque con la mitad de las esquinas void: el resultado debe salir de
        // las válidas, no de mezclar -32768 en la media
        val pack = SyntheticPackBuilder(side = 2)
            .block(0, 0) { r, _ -> if (r == 0) 1000 else VOID_ELEVATION }
            .build()
        val terrain = PackTerrain(pack)
        // en cualquier punto, las dos esquinas válidas valen 1000
        assertEquals(1000.0, terrain.elevationM(1.0, 0.0), 1e-9)
        assertEquals(1000.0, terrain.elevationM(0.875, 0.125), 1e-9)
    }

    @Test
    fun cuatroEsquinasVoidDanNaNyNoUnNumero() {
        val pack = SyntheticPackBuilder(side = 2)
            .block(0, 0) { _, _ -> VOID_ELEVATION }
            .build()
        val value = PackTerrain(pack).elevationM(0.9, 0.1)
        assertTrue(value.isNaN(), "esperaba NaN, salió $value")
    }

    // ---- constantes del motor ---------------------------------------------

    @Test
    fun unPaqueteConOtrasConstantesNoSeCarga() {
        // k = 0.13 frente a 0.20: el paquete sería utilizable byte a byte y
        // daría resultados plausibles y equivocados. Debe negarse a abrir.
        val error = assertFailsWith<EngineMismatchException> {
            SyntheticPackBuilder(engineOverrides = mapOf("K_REFRACTION" to 0.20))
                .block(0, 0) { _, _ -> 100 }
                .build()
        }
        assertTrue("K_REFRACTION" in error.message!!, error.message!!)
    }

    @Test
    fun unPaqueteEnBigEndianNoSeCarga() {
        val error = assertFailsWith<PackFormatException> {
            SyntheticPackBuilder(dtype = ">i2").block(0, 0) { _, _ -> 100 }.build()
        }
        assertTrue("little-endian" in error.message!!, error.message!!)
    }

    // ---- cimas: la corona donde el paquete NO SABE -------------------------

    @Test
    fun unaListaVaciaDeCimasNoAfirmaQueNoHayCimas() {
        val pack = SyntheticPackBuilder(peaksRadiusM = 10_000.0)
            .block(0, 0) { _, _ -> 100 }
            .peak(Peak(1, "Cerca", 0.875, 0.125, 1200.0))
            .build()

        // dentro del registro: la respuesta es completa
        val cerca = pack.peaksNear(0.875, 0.125, 1_000.0)
        assertTrue(cerca.complete, "el círculo cabe en el registro")

        // pidiendo más allá del radio registrado: sigue sin haber cimas, pero
        // ahora "no hay" pasa a ser "no lo sé", y eso tiene que constar
        val lejos = pack.peaksNear(0.875, 0.125, 50_000.0)
        assertTrue(!lejos.complete,
            "el círculo se sale del registro y debe declararse incompleto")
        assertEquals(10_000.0, lejos.registeredRadiusM)
    }

    @Test
    fun losNombresSeLeenConSuAlternativo() {
        val pack = SyntheticPackBuilder()
            .block(0, 0) { _, _ -> 100 }
            .peak(Peak(42, "Picu Urriellu", 0.9, 0.1, 2519.0, altName = "Naranjo de Bulnes"))
            .build()
        val peak = pack.peaks.single()
        assertEquals("Picu Urriellu", peak.name)
        assertEquals("Naranjo de Bulnes", peak.altName)
        assertEquals(42L, peak.osmId)
        assertEquals(2519.0, peak.eleM, 1e-6)
        assertEquals(0.9, peak.latDeg, 1e-7)
        assertEquals(0.1, peak.lonDeg, 1e-7)
    }

    // ---- el radio de observador es una promesa -----------------------------

    @Test
    fun elRadioDeObservadorNoEsElRadioDeTerreno() {
        // Un paquete sirve a su comarca, no a todo su radio de terreno: el tier
        // se asigna por distancia al CENTRO, así que un observador lejano tiene
        // su terreno CERCANO en resolución gruesa. Confundirlos da errores de
        // varios grados (medido: 4.08° a 104 km del centro).
        val pack = SyntheticPackBuilder(
            terrainRadiusM = 130_000.0, observerRadiusM = 25_000.0,
        ).block(0, 0) { _, _ -> 100 }.build()

        assertEquals(25_000.0, pack.observerRadiusM)
        assertEquals(130_000.0, pack.terrainRadiusM)
        assertTrue(pack.servesObserver(pack.centerLatDeg, pack.centerLonDeg))
        // a ~0.5° al sur, unos 55 km: dentro del terreno pero FUERA de lo que
        // el paquete promete servir
        assertTrue(!pack.servesObserver(pack.centerLatDeg - 0.5, pack.centerLonDeg))
    }
}
