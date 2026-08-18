package peakid.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.InputStream
import kotlin.math.atan
import kotlin.math.roundToInt
import peakid.engine.geo.normalizeAzimuthDeg
import peakid.engine.geo.radToDeg

/**
 * ÚNICO punto de carga de píxeles de la foto.
 *
 * Aplica la orientación EXIF UNA vez y lee el tamaño DESPUÉS: al rotar 90° se
 * intercambian ancho y alto, y de eso depende toda la proyección. Una segunda
 * ruta de carga es una oportunidad de mezclar ejes orientados con píxeles sin
 * orientar, que es el tipo de fallo plausible-pero-incorrecto contra el que
 * está escrito CLAUDE.md.
 *
 * **El bitmap que se muestra está submuestreado; los parámetros NO.** El
 * alineamiento se expresa en píxeles de la imagen ORIENTADA A RESOLUCIÓN
 * COMPLETA, que es lo que guarda el `.align.json` y lo que el motor espera.
 * El lienzo solo aplica un factor de escala al pintar.
 */
class LoadedPhoto(
    /** Bitmap ya orientado, posiblemente submuestreado, para pintar. */
    val bitmap: Bitmap,
    /** Ancho de la imagen orientada A RESOLUCIÓN COMPLETA. */
    val widthPx: Int,
    /** Alto de la imagen orientada A RESOLUCIÓN COMPLETA. */
    val heightPx: Int,
    val seeds: PhotoSeeds,
    val displayName: String,
)

/**
 * Semillas del EXIF. Un valor inválido NUNCA sale como número: o sale bien, o
 * no sale.
 *
 * Medido en el motor sobre fotos reales: hay cámaras que escriben el bloque GPS
 * con racionales 0/0 cuando no llegaron a fijar posición, y eso producía
 * coordenadas NaN que entraban en el motor sin que nada fallara.
 */
class PhotoSeeds(
    val latDeg: Double? = null,
    val lonDeg: Double? = null,
    val azimuthDeg: Double? = null,
    val hfovDeg: Double? = null,
    /** El bloque GPS existe pero no es utilizable: se dice, no se calla. */
    val gpsInvalid: Boolean = false,
)

private const val FULL_FRAME_WIDTH_MM = 36.0
private const val MAX_DISPLAY_PX = 2200

object PhotoSource {

    fun load(context: Context, uri: Uri, displayName: String): LoadedPhoto {
        val seeds = context.contentResolver.openInputStream(uri).use { readSeeds(it) }

        // 1) tamaño real sin decodificar
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        val rawW = bounds.outWidth
        val rawH = bounds.outHeight
        require(rawW > 0 && rawH > 0) { "no se pudo leer el tamaño de la foto" }

        // 2) orientación EXIF
        val orientation = context.contentResolver.openInputStream(uri).use { input ->
            ExifInterface(input!!).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
            )
        }
        val swaps = orientation in intArrayOf(
            ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_TRANSVERSE, ExifInterface.ORIENTATION_ROTATE_270,
        )
        // el tamaño ORIENTADO: al rotar 90° se intercambian ancho y alto
        val orientedW = if (swaps) rawH else rawW
        val orientedH = if (swaps) rawW else rawH

        // 3) decodificar submuestreado y aplicar la rotación
        val sample = sampleSizeFor(orientedW, orientedH)
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = context.contentResolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: error("no se pudo decodificar la foto")
        val bitmap = applyOrientation(decoded, orientation)

        // coherencia: si esto falla, hay dos orígenes de píxeles
        check(bitmap.width * sample in (orientedW - sample)..(orientedW + sample)) {
            "el bitmap orientado (${bitmap.width}x${bitmap.height} x$sample) no " +
                "corresponde al tamaño orientado ${orientedW}x$orientedH"
        }

        return LoadedPhoto(bitmap, orientedW, orientedH, seeds, displayName)
    }

    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        while (maxOf(width, height) / sample > MAX_DISPLAY_PX) sample *= 2
        return sample
    }

    private fun applyOrientation(source: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.setRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(-90f)
            else -> return source
        }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, m, true)
        if (rotated != source) source.recycle()
        return rotated
    }

    private fun readSeeds(input: InputStream?): PhotoSeeds {
        if (input == null) return PhotoSeeds()
        val exif = ExifInterface(input)

        val focal35 = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0.0)
        val hfov = if (focal35 > 0.0 && focal35.isFinite()) {
            radToDeg(2.0 * atan(FULL_FRAME_WIDTH_MM / (2.0 * focal35)))
        } else {
            null
        }

        val direction = exif.getAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION)
            ?.let { parseRational(it) }
            ?.takeIf { it.isFinite() }
            ?.let { normalizeAzimuthDeg(it) }

        val latLon = FloatArray(2)
        val hasLatLon = exif.getLatLong(latLon)
        val tieneBloqueGps = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE) != null ||
            exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE) != null
        val lat = if (hasLatLon) latLon[0].toDouble() else null
        val lon = if (hasLatLon) latLon[1].toDouble() else null
        val valido = lat != null && lon != null &&
            lat.isFinite() && lon.isFinite() &&
            kotlin.math.abs(lat) <= 90.0 && kotlin.math.abs(lon) <= 180.0

        return PhotoSeeds(
            latDeg = if (valido) lat else null,
            lonDeg = if (valido) lon else null,
            azimuthDeg = direction,
            hfovDeg = hfov,
            // el bloque existe pero no es utilizable: se dice, no se calla
            gpsInvalid = tieneBloqueGps && !valido,
        )
    }

    private fun parseRational(text: String): Double = try {
        if ("/" in text) {
            val (a, b) = text.split("/", limit = 2)
            val den = b.toDouble()
            if (den == 0.0) Double.NaN else a.toDouble() / den
        } else {
            text.toDouble()
        }
    } catch (_: NumberFormatException) {
        Double.NaN
    }
}

/** Redondeo a un número de decimales, para los valores que se exportan. */
fun Double.round(decimals: Int): Double {
    var factor = 1.0
    repeat(decimals) { factor *= 10.0 }
    return (this * factor).roundToInt() / factor
}
