package peakid.engine.pack

/**
 * Primitivas de lectura del formato de paquete.
 *
 * TODO ENTERO DEL PAQUETE VA EN LITTLE-ENDIAN, y no es un detalle de gusto.
 * Los `.hgt` de la NASA son BIG-endian; el paquete lo consume un móvil, donde
 * un byteswap por muestra es desperdicio puro, así que el generador los escribe
 * al revés que la fuente. Las dos convenciones conviven en el mismo proyecto.
 *
 * Leer un paquete en big-endian no revienta: devuelve números plausibles y
 * equivocados. 2065 leído del revés son 4360, que sigue pareciendo una altitud.
 * Por eso el orden se comprueba con su propio test sobre un valor asimétrico,
 * y el `dtype` se lee del manifest en vez de suponerse.
 *
 * Kotlin común no tiene `ByteBuffer`, así que los enteros se componen a mano.
 */

/** Byte sin signo como Int, sin el sobresalto del Byte con signo de Kotlin. */
private fun ByteArray.u(index: Int): Int = this[index].toInt() and 0xFF

internal fun ByteArray.leU16(offset: Int): Int =
    u(offset) or (u(offset + 1) shl 8)

internal fun ByteArray.leI16(offset: Int): Short =
    leU16(offset).toShort()

internal fun ByteArray.leU32(offset: Int): Long =
    (u(offset).toLong()
        or (u(offset + 1).toLong() shl 8)
        or (u(offset + 2).toLong() shl 16)
        or (u(offset + 3).toLong() shl 24))

internal fun ByteArray.leI32(offset: Int): Int =
    (u(offset)
        or (u(offset + 1) shl 8)
        or (u(offset + 2) shl 16)
        or (u(offset + 3) shl 24))

internal fun ByteArray.leI64(offset: Int): Long {
    var value = 0L
    for (i in 7 downTo 0) value = (value shl 8) or u(offset + i).toLong()
    return value
}

internal fun ByteArray.leF32(offset: Int): Float =
    Float.fromBits(leI32(offset))

internal fun ByteArray.leF64(offset: Int): Double =
    Double.fromBits(leI64(offset))

internal fun ByteArray.ascii(offset: Int, length: Int): String =
    buildString(length) { for (i in 0 until length) append(u(offset + i).toChar()) }

/**
 * Decodifica `count` enteros de 16 bits little-endian a partir de `offset`.
 *
 * Es el bucle caliente de la lectura de terreno: un bloque t1 son 811 801
 * muestras. Se decodifica el bloque entero UNA vez y se cachea el `ShortArray`,
 * en vez de recomponer bytes en cada consulta del rayo.
 */
internal fun ByteArray.leI16Array(offset: Int, count: Int): ShortArray {
    val out = ShortArray(count)
    var p = offset
    for (i in 0 until count) {
        out[i] = ((this[p].toInt() and 0xFF) or (this[p + 1].toInt() shl 8)).toShort()
        p += 2
    }
    return out
}

/**
 * Acceso aleatorio a los bytes de un fichero del paquete.
 *
 * El parseo vive en `commonMain` y opera sobre `ByteArray`; el acceso al
 * fichero es lo único que cambia entre plataformas. Así el formato queda bajo
 * test multiplataforma y en iOS solo hace falta implementar esto.
 */
interface ByteSource {
    val size: Long

    /** Lee `count` bytes desde `offset`. Lanza si no hay tantos. */
    fun read(offset: Long, count: Int): ByteArray

    fun close()
}

/** Fuente respaldada por un `ByteArray` en memoria. Útil en tests. */
class MemoryByteSource(private val bytes: ByteArray) : ByteSource {
    override val size: Long get() = bytes.size.toLong()

    override fun read(offset: Long, count: Int): ByteArray {
        require(offset >= 0 && offset + count <= bytes.size) {
            "lectura fuera de rango: offset=$offset count=$count size=${bytes.size}"
        }
        return bytes.copyOfRange(offset.toInt(), offset.toInt() + count)
    }

    override fun close() = Unit
}

class PackFormatException(message: String) : Exception(message)

class EngineMismatchException(message: String) : Exception(message)

/**
 * El bloque que cubriría unas coordenadas no está en el paquete.
 *
 * Es "aquí no hay datos", que NO es lo mismo que un void puntual dentro de un
 * bloque que sí está. De esta distinción depende que el motor pueda devolver
 * UNKNOWN en vez de inventarse un VISIBLE: un bloque ausente jamás es 0 m,
 * porque eso sería un océano imaginario donde hay monte.
 */
class BlockNotFoundException(message: String) : Exception(message)
