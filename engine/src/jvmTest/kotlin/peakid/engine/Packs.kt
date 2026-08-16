package peakid.engine

import java.io.File
import org.junit.Assume
import peakid.engine.pack.Pack
import peakid.engine.pack.openPack

/**
 * Localización de los paquetes de región para los tests dorados.
 *
 * Los paquetes pesan decenas de MB y NO se versionan, así que un clon limpio no
 * los tiene. La ruta sale de la propiedad `peakid.packs.dir`, que Gradle fija
 * con defecto `../peakid/packs` — la disposición real en disco, con el repo del
 * motor como hermano de este.
 *
 * **Un caso dorado saltado no puede parecerse a uno verde.** Cada paquete
 * declara aquí qué casos del contrato deja sin vigilar si falta, y
 * [InformeDeCasosInactivos] lo imprime en un bloque que no se puede pasar por
 * alto. Es el mismo arreglo que se aplicó al motor Python, cuyos `skipif` eran
 * silenciosos.
 */
object Packs {

    private val dir: File = File(
        System.getProperty("peakid.packs.dir") ?: "../peakid/packs",
    )

    /** Qué casos del contrato depende de cada paquete. */
    val DEPENDENCIAS: Map<String, List<String>> = mapOf(
        "axarquia" to listOf(
            "72 cimas de PeakFinder: azimuts (validación externa independiente)",
            "72 cimas de PeakFinder: el perfil alcanza cada cima",
            "horizonte marino con solución cerrada (-0.094688°)",
        ),
        "guadarrama" to listOf(
            "caso 5 — altitud del Peñalara tras recolocar (2400-2430 m)",
            "caso 6 — visibilidad Peñalara -> Bola del Mundo",
        ),
    )

    fun path(name: String): File = File(dir, name)

    fun exists(name: String): Boolean = File(path(name), "manifest.json").isFile

    fun ausentes(): List<String> = DEPENDENCIAS.keys.filter { !exists(it) }.sorted()

    /**
     * Abre el paquete, o salta el test dejando constancia de qué se pierde.
     *
     * `Assume` marca el test como SALTADO, no como aprobado: un caso dorado sin
     * datos no puede contarse como verde.
     */
    fun abrir(name: String): Pack {
        val casos = DEPENDENCIAS[name].orEmpty().joinToString("; ")
        Assume.assumeTrue(
            "falta el paquete '$name' en $dir — quedan sin comprobar: $casos",
            exists(name),
        )
        return openPack(path(name))
    }

    /** Ruta absoluta, para mensajes. */
    fun raiz(): File = dir
}

/**
 * Imprime el bloque de casos dorados inactivos.
 *
 * No falla: informa. Falla sería hostil en un clon limpio, y callar sería
 * exactamente el patrón que este proyecto condena.
 */
object InformeDeCasosInactivos {
    fun emitir() {
        val ausentes = Packs.ausentes()
        if (ausentes.isEmpty()) return
        val sb = StringBuilder()
        sb.appendLine()
        sb.appendLine("=".repeat(75))
        sb.appendLine("CASOS DORADOS INACTIVOS")
        sb.appendLine("=".repeat(75))
        sb.appendLine()
        sb.appendLine("Los siguientes casos del CONTRATO no se han comprobado en esta tirada.")
        sb.appendLine("Que el resto salga verde NO dice nada sobre ellos:")
        sb.appendLine()
        for (name in ausentes) {
            for (caso in Packs.DEPENDENCIAS[name].orEmpty()) {
                sb.appendLine("  - $caso")
            }
            sb.appendLine("      falta el paquete '$name' en ${Packs.raiz()}")
            sb.appendLine()
        }
        sb.appendLine("Genéralos en el repo del motor con scripts/build_pack.py, o apunta")
        sb.appendLine("a otro sitio con -Ppeakid.packs.dir=<ruta>.")
        sb.appendLine("=".repeat(75))
        println(sb)
    }
}
