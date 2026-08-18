package peakid.engine.skyline

import java.io.File
import javax.imageio.ImageIO

/**
 * Acceso al repo del MOTOR desde los tests del puerto.
 *
 * El criterio de aceptación de la fase 6 es `tests/data/cresta_referencia.json`
 * del motor: seis fotos, tolerancia el cuanto de cada una. Ni las fotos (16 MB
 * una de ellas) ni el `.onnx` se duplican aquí — se leen de su sitio, igual que
 * los paquetes de región.
 *
 * La ruta sale de la propiedad `peakid.motor.dir`, con defecto `../peakid`, que
 * es la disposición real en disco.
 */
object Motor {

    private val dir: File = File(System.getProperty("peakid.motor.dir") ?: "../peakid")

    val fixture: File get() = File(dir, "tests/data/cresta_referencia.json")
    val modelo: File get() = File(dir, "models/segformer_b0_ade.onnx")

    fun foto(nombre: String): File = File(dir, nombre)

    fun disponible(): Boolean = fixture.isFile && modelo.isFile

    fun queFalta(): String = buildString {
        if (!fixture.isFile) append("falta ${fixture.path} (generar con scripts/freeze_skyline.py); ")
        if (!modelo.isFile) append("falta ${modelo.path}; ")
        if (isEmpty()) append("nada")
    }

    fun raiz(): File = dir
}

/** Imagen de trabajo ya decimada, en el convenio del motor. */
class ImagenDeTrabajo(
    val rgb: DoubleArray,
    val width: Int,
    val height: Int,
    val step: Int,
    val photoWidth: Int,
    val photoHeight: Int,
)

/**
 * Carga una foto aplicando la orientación EXIF UNA vez, y la decima.
 *
 * Espejo de `load_oriented_photo` del motor. El tamaño se lee DESPUÉS de
 * orientar: al rotar 90° se intercambian ancho y alto, y de eso depende toda la
 * geometría. Dos de las seis fotos de referencia traen orientación 6.
 *
 * La decimación es `photo[::step, ::step]` — un píxel de cada `step`, SIN
 * promediar—, y se hace al vuelo: materializar la foto entera como `DoubleArray`
 * son 300 MB en las de 12 Mpx.
 */
fun cargarOrientadaYDecimada(fichero: File): ImagenDeTrabajo {
    val bruta = ImageIO.read(fichero) ?: error("no se pudo leer ${fichero.path}")
    val orientacion = orientacionExif(fichero)

    val giraEjes = exifSwapsAxes(orientacion)
    val anchoOrientado = if (giraEjes) bruta.height else bruta.width
    val altoOrientado = if (giraEjes) bruta.width else bruta.height

    val step = workStep(anchoOrientado)
    val w = (anchoOrientado + step - 1) / step
    val h = (altoOrientado + step - 1) / step
    val rgb = DoubleArray(h * w * 3)

    for (r in 0 until h) {
        for (c in 0 until w) {
            val xo = c * step
            val yo = r * step
            // de coordenadas ORIENTADAS a coordenadas de la imagen cruda
            val xs = exifRawX(xo, yo, orientacion, anchoOrientado, altoOrientado)
            val ys = exifRawY(xo, yo, orientacion, anchoOrientado, altoOrientado)
            val pixel = bruta.getRGB(xs, ys)
            val i = (r * w + c) * 3
            rgb[i] = ((pixel shr 16) and 0xFF).toDouble()
            rgb[i + 1] = ((pixel shr 8) and 0xFF).toDouble()
            rgb[i + 2] = (pixel and 0xFF).toDouble()
        }
    }
    return ImagenDeTrabajo(rgb, w, h, step, anchoOrientado, altoOrientado)
}

/**
 * Lee el tag 0x0112 (orientación) del bloque APP1 de un JPEG. 1 si no está.
 *
 * Cuarenta líneas en vez de una dependencia: el motor solo necesita un entero,
 * y `ImageIO` no lo expone sin bajar a los metadatos en XML.
 */
private fun orientacionExif(fichero: File): Int {
    val bytes = fichero.readBytes()
    if (bytes.size < 4 || (bytes[0].toInt() and 0xFF) != 0xFF ||
        (bytes[1].toInt() and 0xFF) != 0xD8
    ) return 1

    var i = 2
    while (i + 4 <= bytes.size) {
        if ((bytes[i].toInt() and 0xFF) != 0xFF) return 1
        val marca = bytes[i + 1].toInt() and 0xFF
        if (marca == 0xDA || marca == 0xD9) return 1        // datos de imagen
        val longitud = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
        if (marca == 0xE1 && i + 10 < bytes.size &&
            String(bytes, i + 4, 4, Charsets.US_ASCII) == "Exif"
        ) {
            val tiff = i + 10
            val little = String(bytes, tiff, 2, Charsets.US_ASCII) == "II"
            fun u16(p: Int): Int =
                if (little) ((bytes[p + 1].toInt() and 0xFF) shl 8) or (bytes[p].toInt() and 0xFF)
                else ((bytes[p].toInt() and 0xFF) shl 8) or (bytes[p + 1].toInt() and 0xFF)
            fun u32(p: Int): Int =
                if (little) (u16(p + 2) shl 16) or u16(p)
                else (u16(p) shl 16) or u16(p + 2)

            val ifd0 = tiff + u32(tiff + 4)
            if (ifd0 + 2 > bytes.size) return 1
            val entradas = u16(ifd0)
            for (e in 0 until entradas) {
                val p = ifd0 + 2 + e * 12
                if (p + 12 > bytes.size) break
                if (u16(p) == 0x0112) return u16(p + 8)
            }
            return 1
        }
        i += 2 + longitud
    }
    return 1
}
