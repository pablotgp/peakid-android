package peakid.engine.skyline

/**
 * De coordenadas ORIENTADAS a coordenadas de la imagen CRUDA.
 *
 * Vive aquí, en común y con test propio, porque es donde se cometió el error
 * del puerto: con las dimensiones ya orientadas `(w, h)`, en las orientaciones
 * 5–8 la imagen cruda mide `(h, w)`, así que **la X del crudo la acota `h` y la
 * Y la acota `w`** — al revés de lo que parece al leer el código.
 *
 * Escribirlo al revés no da una imagen girada. Da un desbordamiento si hay
 * suerte, y si no una lectura DENTRO DE RANGO sobre otra parte de la foto: una
 * cresta continua, con su cobertura al 100%, calculada sobre píxeles que no son
 * los que se ven. Nada avisa.
 */

/**
 * ¿Esta orientación intercambia los ejes?
 *
 * El **0 no es un valor válido de EXIF** (el rango es 1..8) y aparece en fotos
 * reales: quiere decir "sin especificar". Medido con la foto de Nerja en el
 * móvil, donde `ExifInterface` devolvió 0 porque el tag EXISTE con ese valor y
 * por tanto no se aplicó el defecto. Se trata como el 1, que es lo que hace
 * `exif_transpose` de PIL: solo transforma con 2..8 y deja igual todo lo demás.
 *
 * Para valores FUERA de 0..8 se sigue lanzando, y ahí este puerto se aparta de
 * PIL a propósito: PIL los ignora en silencio, y un valor corrupto significa
 * que no sabemos la geometría de la foto. Fallar en voz alta y ofrecer el
 * marcado a dedo es preferible a procesar una foto girada sin girarla, que
 * devuelve una cresta plausible leída de otra parte de la imagen.
 */
fun exifSwapsAxes(orientation: Int): Boolean = orientation in 5..8

/**
 * Coordenada X de la imagen cruda. Ver [exifRawY] para la otra mitad.
 *
 * @param w ancho ORIENTADO
 * @param h alto ORIENTADO
 */
fun exifRawX(x: Int, y: Int, orientation: Int, w: Int, h: Int): Int = when (orientation) {
    0, 1, 4 -> x
    2, 3 -> w - 1 - x
    5, 6 -> y
    7, 8 -> h - 1 - y
    else -> throw IllegalArgumentException("orientación EXIF no contemplada: $orientation")
}

/** Coordenada Y de la imagen cruda. */
fun exifRawY(x: Int, y: Int, orientation: Int, w: Int, h: Int): Int = when (orientation) {
    0, 1, 2 -> y
    3, 4 -> h - 1 - y
    5 -> x
    6, 7 -> w - 1 - x
    8 -> x
    else -> throw IllegalArgumentException("orientación EXIF no contemplada: $orientation")
}
