package peakid.engine.skyline

/**
 * Reloj de pared. Vale igual en escritorio y en Android: es el MISMO
 * `System.currentTimeMillis`, así que androidMain reutiliza este fichero en vez
 * de duplicar un `actual`.
 */
internal actual fun ahoraMs(): Long = System.currentTimeMillis()
