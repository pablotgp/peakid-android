package peakid.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import peakid.engine.skyline.Crest
import peakid.engine.skyline.SegformerSky
import peakid.engine.skyline.detectSkyline
import peakid.engine.skyline.exifRawX
import peakid.engine.skyline.exifRawY
import peakid.engine.skyline.exifSwapsAxes
import peakid.engine.skyline.workStep

/** Cresta detectada más lo que costó, que aquí es información de primera. */
class DeteccionMedida(
    val crest: Crest,
    val cargaModeloMs: Long,
    val pixelesMs: Long,
    /** Etapas de dentro del motor: inferencia, coste, camino. */
    val etapas: Map<String, Long>,
) {
    val totalMs: Long get() = cargaModeloMs + pixelesMs + etapas.values.sum()

    fun resumen(): String =
        "modelo $cargaModeloMs ms · píxeles $pixelesMs ms · " +
            etapas.entries.joinToString(" · ") { "${it.key} ${it.value} ms" } +
            " · total $totalMs ms"
}

/**
 * Detector de cresta en el dispositivo.
 *
 * **El `.onnx` va en `assets/`, no se empuja por adb.** El paquete de región es
 * DATO —varía por comarca, lo elige el usuario, y la fase 8 lo descargará—; el
 * modelo es CÓDIGO: la clase 2 = `sky`, la normalización y el fixture de la
 * cresta están atados a ese fichero exacto, así que cambiarlo es cambiar el
 * comportamiento y tiene que versionarse con el APK. Además el detector es no
 * negociable, y algo no negociable no puede depender de un ritual de máquina de
 * desarrollo.
 */
class DetectorDeCresta(private val context: Context) {

    private var modelo: SegformerSky? = null

    /**
     * Carga el modelo una vez. Son 15 MB: repetirlo por foto sería el grueso
     * del tiempo.
     */
    private fun modelo(): SegformerSky = modelo ?: run {
        val bytes = context.assets.open(NOMBRE_MODELO).use { it.readBytes() }
        // Dos hilos y sin arena: medido, con los defectos de ORT el sistema
        // mató la app con 2.1 GB en swap. En escritorio los defectos están
        // bien; aquí la memoria es el recurso escaso, no la CPU.
        SegformerSky(bytes, intraOpThreads = 2, arenaDeCpu = false)
            .also { modelo = it }
    }

    fun cerrar() {
        modelo?.close()
        modelo = null
    }

    fun detectar(uri: Uri, foto: LoadedPhoto): DeteccionMedida {
        val t0 = System.currentTimeMillis()
        val sesion = modelo()
        val t1 = System.currentTimeMillis()

        val trabajo = imagenDeTrabajo(uri, foto)
        val t2 = System.currentTimeMillis()

        val etapas = LinkedHashMap<String, Long>()
        val crest = detectSkyline(
            trabajo.rgb, trabajo.width, trabajo.height, trabajo.step, sesion,
            reloj = { etapa, ms -> etapas[etapa] = ms },
        )

        return DeteccionMedida(crest, t1 - t0, t2 - t1, etapas)
    }

    /**
     * Decima la foto a la rejilla de trabajo, DESDE LA RESOLUCIÓN COMPLETA.
     *
     * El bitmap que se pinta está submuestreado con `inSampleSize`, que es
     * potencia de dos y además promedia; el motor hace `photo[::step, ::step]`,
     * que toma un píxel de cada `step` SIN promediar y con `step` arbitrario
     * (3, 4, 5, 8...). Reutilizar el bitmap de pantalla daría una imagen más
     * suave y una cresta distinta de la de la referencia. Por eso se vuelve a
     * decodificar a resolución completa.
     *
     * La orientación se aplica AL LEER, con la tabla del motor, en vez de rotar
     * el bitmap: rotar 12 Mpx duplica el pico de memoria y aquí no hace falta,
     * porque solo se leen los píxeles de la rejilla de trabajo.
     */
    private fun imagenDeTrabajo(uri: Uri, foto: LoadedPhoto): Trabajo {
        val orientacion = context.contentResolver.openInputStream(uri).use { input ->
            ExifInterface(input!!).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
            )
        }
        // POR BANDAS, no la foto entera. Medido en el Galaxy A17: decodificar
        // 3060x4080 en ARGB_8888 son 50 MB de una tacada, y con 48 MB libres y
        // 1.7 GB de swap en uso el sistema mató el proceso —lmkd, señal 9,
        // `reason: device is not responding`, con oom_score_adj 0, o sea en
        // primer plano—. No fue un OutOfMemoryError de Java: no hubo excepción
        // que capturar, la app simplemente desapareció.
        //
        // Una banda de BANDA_FILAS filas crudas son ~4 MB, y como las
        // coordenadas crudas que hacen falta recorren la imagen en orden, cada
        // banda se decodifica UNA vez y no se vuelve a ella.
        val bandas = BandasDeLaFoto(context, uri)
        try {
            val gira = exifSwapsAxes(orientacion)
            val anchoOrientado = if (gira) bandas.alto else bandas.ancho
            val altoOrientado = if (gira) bandas.ancho else bandas.alto
            // coherencia con el único origen de píxeles de la app
            check(anchoOrientado == foto.widthPx && altoOrientado == foto.heightPx) {
                "el tamaño orientado ${anchoOrientado}x$altoOrientado no cuadra con " +
                    "el de PhotoSource ${foto.widthPx}x${foto.heightPx}"
            }

            val step = workStep(anchoOrientado)
            val w = (anchoOrientado + step - 1) / step
            val h = (altoOrientado + step - 1) / step
            val rgb = DoubleArray(h * w * 3)

            // El orden de recorrido no es indiferente: hay que avanzar por el
            // eje que hace crecer la FILA CRUDA de forma monótona, para que
            // cada banda se decodifique una vez y no haya que volver a ella.
            // Y ese eje depende de la orientación:
            //
            //  - sin giro de ejes, la fila cruda la fija la fila de trabajo
            //  - CON giro, la fija la COLUMNA de trabajo
            //
            // Recorrer siempre igual no da un resultado incorrecto: da una
            // redecodificación por píxel. Es el tipo de detalle que solo se ve
            // midiendo.
            fun leer(r: Int, c: Int) {
                val xs = exifRawX(c * step, r * step, orientacion, anchoOrientado, altoOrientado)
                val ys = exifRawY(c * step, r * step, orientacion, anchoOrientado, altoOrientado)
                val pixel = bandas.pixel(xs, ys)
                val i = (r * w + c) * 3
                rgb[i] = ((pixel shr 16) and 0xFF).toDouble()
                rgb[i + 1] = ((pixel shr 8) and 0xFF).toDouble()
                rgb[i + 2] = (pixel and 0xFF).toDouble()
            }
            if (gira) {
                for (c in 0 until w) for (r in 0 until h) leer(r, c)
            } else {
                for (r in 0 until h) for (c in 0 until w) leer(r, c)
            }
            return Trabajo(rgb, w, h, step)
        } finally {
            bandas.cerrar()
        }
    }

    private class Trabajo(
        val rgb: DoubleArray,
        val width: Int,
        val height: Int,
        val step: Int,
    )

    /**
     * Lectura de la foto a resolución COMPLETA por bandas horizontales.
     *
     * Existe porque decodificarla entera mató la app: 50 MB de una tacada en un
     * móvil con 48 MB libres y 1.7 GB de swap en uso. El sistema no lanza una
     * excepción — manda `SIGKILL` y la app desaparece —, así que no hay nada
     * que capturar y el arreglo tiene que ser no llegar a pedir esa memoria.
     *
     * NO se usa `inSampleSize`, que sería lo cómodo: solo admite potencias de
     * dos y además promedia, mientras que el motor hace `photo[::step, ::step]`
     * —un píxel de cada `step`, sin promediar—. Los píxeles tienen que ser
     * EXACTAMENTE los mismos que valida el fixture de la cresta, así que se
     * decodifica a resolución nativa y se recorta por regiones.
     *
     * La banda se sustituye cuando la fila pedida se sale de ella. Quien llame
     * debe pedir las filas en orden monótono, o esto se convierte en una
     * redecodificación por píxel.
     */
    private class BandasDeLaFoto(context: Context, uri: Uri) {

        @Suppress("DEPRECATION")   // newInstance(InputStream) pide API 31; minSdk es 26
        private val decoder: android.graphics.BitmapRegionDecoder =
            context.contentResolver.openInputStream(uri).use {
                android.graphics.BitmapRegionDecoder.newInstance(it!!, false)
            } ?: error("no se pudo abrir la foto por regiones")

        val ancho: Int = decoder.width
        val alto: Int = decoder.height

        private val opciones = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888   // RGB_565 perdería precisión
        }
        private var banda: Bitmap? = null
        private var desde = -1
        private var hasta = -1
        private var fila = IntArray(0)
        private var filaCargada = -1

        /** Cuántas filas crudas por banda: ~4 MB con una foto de 4000 de ancho. */
        private val altoDeBanda = 256

        fun pixel(x: Int, y: Int): Int {
            if (y < desde || y >= hasta) cargarBandaQueContiene(y)
            if (y != filaCargada) {
                banda!!.getPixels(fila, 0, ancho, 0, y - desde, ancho, 1)
                filaCargada = y
            }
            return fila[x]
        }

        private fun cargarBandaQueContiene(y: Int) {
            banda?.recycle()
            desde = (y / altoDeBanda) * altoDeBanda
            hasta = minOf(desde + altoDeBanda, alto)
            banda = decoder.decodeRegion(
                android.graphics.Rect(0, desde, ancho, hasta), opciones,
            ) ?: error("no se pudo decodificar la banda [$desde,$hasta)")
            if (fila.size != ancho) fila = IntArray(ancho)
            filaCargada = -1
        }

        fun cerrar() {
            banda?.recycle()
            banda = null
            decoder.recycle()
        }
    }

    companion object {
        const val NOMBRE_MODELO = "segformer_b0_ade.onnx"
    }
}
