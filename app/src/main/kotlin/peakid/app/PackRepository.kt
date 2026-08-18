package peakid.app

import android.content.Context
import java.io.File
import peakid.engine.geo.haversineM
import peakid.engine.pack.Pack
import peakid.engine.pack.openPack

/**
 * Paquetes de región instalados.
 *
 * Viven en `getExternalFilesDir("packs")/<nombre>/`. En esta fase se empujan
 * con `adb push`; en la fase 8 los escribirá el descargador **en ese mismo
 * directorio**, así que la ubicación ya es la definitiva y solo cambia el
 * llenado. Nada de este código es provisional.
 *
 *     adb push packs/axarquia \
 *       /sdcard/Android/data/peakid.app/files/packs/
 */
class PackRepository(private val context: Context) {

    /**
     * El directorio de paquetes, creándolo si hace falta.
     *
     * **Lo crea la app a propósito, no por comodidad.** Medido en el Galaxy A17:
     * un `adb shell mkdir -p .../files/packs/axarquia` deja el directorio a
     * nombre de `shell` con modo `drwxrws---`, y la app no lo atraviesa; con
     * `run-as` queda a nombre de la app y sí. Que exista de antemano hace que
     * `adb push` no tenga que crear nada, y la receta se reduce a una línea sin
     * `run-as` ni trampas de propietario.
     */
    fun packsDir(): File = File(context.getExternalFilesDir(null), "packs").apply {
        if (!isDirectory) mkdirs()
    }

    fun installed(): List<String> =
        packsDir().listFiles()
            ?.filter { it.isDirectory && File(it, "manifest.json").isFile }
            ?.map { it.name }
            ?.sorted()
            .orEmpty()

    /**
     * Qué hay REALMENTE bajo el directorio de paquetes, con manifest o sin él.
     *
     * Un mensaje que solo receta un comando no distingue "no has empujado nada"
     * de "empujaste el directorio equivocado" ni de "está ahí pero sin
     * manifest.json". Esto es lo que hace falta para diagnosticar en vez de
     * adivinar.
     */
    fun rawEntries(): List<String> =
        packsDir().listFiles()
            ?.map { f ->
                when {
                    !f.isDirectory -> "${f.name} (fichero suelto, no un paquete)"
                    File(f, "manifest.json").isFile -> "${f.name}/ ok"
                    else -> "${f.name}/ SIN manifest.json"
                }
            }
            ?.sorted()
            .orEmpty()

    fun open(name: String): Pack = openPack(File(packsDir(), name))

    /**
     * Elige el paquete que SIRVE a una posición, o explica por qué no hay.
     *
     * El criterio es `distancia(usuario, centro) <= observer_radius_m`, y **no**
     * «este paquete contiene esa montaña»: contener el terreno de una cima no
     * habilita a mirarla desde cualquier punto del paquete. Con el observador
     * lejos del centro, su terreno CERCANO está en resolución gruesa y el
     * horizonte se descuadra — medido en el motor: 0.69° a 45 km del centro y
     * 4.08° a 104 km.
     */
    fun forObserver(latDeg: Double, lonDeg: Double): PackChoice {
        val instalados = installed()
        if (instalados.isEmpty()) return PackChoice.NoPacksInstalled

        var mejor: Pack? = null
        var mejorD = Double.MAX_VALUE
        var masCercanoFuera: Pair<String, Double>? = null

        for (nombre in instalados) {
            val pack = try {
                open(nombre)
            } catch (err: Exception) {
                continue
            }
            val d = haversineM(latDeg, lonDeg, pack.centerLatDeg, pack.centerLonDeg)
            if (pack.servesObserver(latDeg, lonDeg)) {
                if (d < mejorD) {
                    mejor?.close()
                    mejor = pack
                    mejorD = d
                } else {
                    pack.close()
                }
            } else {
                if (masCercanoFuera == null || d < masCercanoFuera!!.second) {
                    masCercanoFuera = pack.name to d
                }
                pack.close()
            }
        }
        val elegido = mejor
        return if (elegido != null) {
            PackChoice.Serves(elegido, mejorD)
        } else {
            // DOS situaciones muy distintas para el usuario, y no se mezclan:
            // «estás fuera de la zona de este paquete» es una acción (hay uno y
            // se descarga); «no hay paquete para esta zona» es una limitación
            // de cobertura. En esta fase ambas acaban en adb, pero la
            // distinción ya vive en el modelo para que la fase 8 solo la rellene.
            PackChoice.OutsideObserverRadius(
                masCercanoFuera?.first ?: instalados.first(),
                masCercanoFuera?.second ?: Double.NaN,
            )
        }
    }
}

sealed interface PackChoice {
    /** Hay paquete y la posición está dentro de su radio de observador. */
    class Serves(val pack: Pack, val distanceToCenterM: Double) : PackChoice

    /** Hay paquetes, pero ninguno sirve a esta posición. */
    class OutsideObserverRadius(val nearest: String, val distanceM: Double) : PackChoice

    /** No hay ninguno instalado. */
    data object NoPacksInstalled : PackChoice
}
