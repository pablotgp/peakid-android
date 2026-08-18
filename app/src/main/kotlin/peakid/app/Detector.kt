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
    val inferenciaYCaminoMs: Long,
) {
    val totalMs: Long get() = cargaModeloMs + pixelesMs + inferenciaYCaminoMs
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
        SegformerSky(bytes).also { modelo = it }
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

        val crest = detectSkyline(
            trabajo.rgb, trabajo.width, trabajo.height, trabajo.step, sesion,
        )
        val t3 = System.currentTimeMillis()

        return DeteccionMedida(crest, t1 - t0, t2 - t1, t3 - t2)
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
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888   // RGB_565 perdería precisión
        }
        val bruta = context.contentResolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: error("no se pudo decodificar la foto a resolución completa")

        try {
            val gira = exifSwapsAxes(orientacion)
            val anchoOrientado = if (gira) bruta.height else bruta.width
            val altoOrientado = if (gira) bruta.width else bruta.height
            // coherencia con el único origen de píxeles de la app
            check(anchoOrientado == foto.widthPx && altoOrientado == foto.heightPx) {
                "el tamaño orientado ${anchoOrientado}x$altoOrientado no cuadra con " +
                    "el de PhotoSource ${foto.widthPx}x${foto.heightPx}"
            }

            val step = workStep(anchoOrientado)
            val w = (anchoOrientado + step - 1) / step
            val h = (altoOrientado + step - 1) / step
            val rgb = DoubleArray(h * w * 3)

            // Se leen filas ENTERAS del bitmap crudo, no píxel a píxel:
            // `getPixel` serían ~1.4 millones de llamadas JNI. Pero para que
            // una fila cargada sirva para varios píxeles hay que recorrer en el
            // orden que deja FIJA la fila CRUDA, y ese orden depende de la
            // orientación:
            //
            //  - sin giro de ejes, la fila cruda la fija la fila de trabajo
            //  - CON giro, la fija la COLUMNA de trabajo
            //
            // Recorrer siempre igual no da un resultado incorrecto: da una
            // recarga por píxel, que es peor que no cachear. Es el tipo de
            // detalle que solo se ve midiendo.
            val fila = IntArray(bruta.width)
            var filaCargada = -1
            fun leer(r: Int, c: Int) {
                val xs = exifRawX(c * step, r * step, orientacion, anchoOrientado, altoOrientado)
                val ys = exifRawY(c * step, r * step, orientacion, anchoOrientado, altoOrientado)
                if (ys != filaCargada) {
                    bruta.getPixels(fila, 0, bruta.width, 0, ys, bruta.width, 1)
                    filaCargada = ys
                }
                val pixel = fila[xs]
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
            bruta.recycle()
        }
    }

    private class Trabajo(
        val rgb: DoubleArray,
        val width: Int,
        val height: Int,
        val step: Int,
    )

    companion object {
        const val NOMBRE_MODELO = "segformer_b0_ade.onnx"
    }
}
