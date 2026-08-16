package peakid.engine.pack

import java.io.File
import java.io.RandomAccessFile

/**
 * Acceso a los bytes de `terrain.bin` desde el sistema de ficheros.
 *
 * Es lo ÚNICO específico de plataforma en la lectura de un paquete: el parseo
 * del formato vive en `commonMain` y opera sobre `ByteArray`, así que portar a
 * iOS es implementar esta clase y nada más.
 *
 * Lectura por bloques bajo demanda en vez de cargar el fichero entero: el
 * terreno de la Axarquía son 32 MB y en un móvil no tiene sentido residentes.
 * La caché de bloques decodificados vive en [Pack].
 */
class FileByteSource(private val file: File) : ByteSource {
    private val handle = RandomAccessFile(file, "r")

    override val size: Long get() = handle.length()

    override fun read(offset: Long, count: Int): ByteArray {
        val buffer = ByteArray(count)
        handle.seek(offset)
        handle.readFully(buffer)
        return buffer
    }

    override fun close() = handle.close()
}

/**
 * Abre el paquete que vive en `dir` (manifest.json, coverage.bin, peaks.bin,
 * terrain.bin).
 */
fun openPack(dir: File, checkEngine: Boolean = true): Pack {
    require(dir.isDirectory) { "no es un directorio de paquete: $dir" }
    return openPack(
        manifestJson = File(dir, "manifest.json").readText(),
        coverageBytes = File(dir, "coverage.bin").readBytes(),
        peaksBytes = File(dir, "peaks.bin").readBytes(),
        terrain = FileByteSource(File(dir, "terrain.bin")),
        checkEngine = checkEngine,
    )
}
