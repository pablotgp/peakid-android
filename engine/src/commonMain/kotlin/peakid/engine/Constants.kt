package peakid.engine

/**
 * Constantes de muestreo del motor.
 *
 * En Python viven en `src/horizon` y `src/peaks`. Aquí van aparte porque las
 * consumen tanto `horizon/` como `pack/` —el paquete las transporta en su
 * manifest y el lector las verifica— y así no hay que decidir cuál de los dos
 * paquetes depende del otro.
 *
 * Los valores son los del motor y no se tocan por separado: si divergen, un
 * paquete construido con unos y leído con otros da resultados plausibles y
 * equivocados, que es justo lo que la comprobación del manifest impide.
 */

/** Paso de muestreo a lo largo del rayo, ≈ resolución del SRTM1. */
const val STEP_M: Double = 30.0

/** Más allá casi nunca hay visibilidad real, y multiplica el coste. */
const val MAX_DISTANCE_M: Double = 150_000.0

/** Paso del barrido de horizonte: 1800 rayos en una vuelta completa. */
const val AZIMUTH_STEP_DEG: Double = 0.2

/**
 * El rayo deja de comprobar obstáculos en los últimos 200 m antes del objetivo.
 *
 * Sin esto se produce AUTO-BLOQUEO: la ladera final del propio pico, que el DEM
 * rinde ligeramente por debajo de la cota oficial pero un poco más cerca del
 * observador, gana el ángulo por centésimas y tapa su propia cima. Medido en el
 * motor: Cima de Tejeda salía BLOCKED por terreno de 2068.3 m situado a 13 m de
 * la cima oficial de 2069.
 *
 * Mismo valor que el radio de recolocación de cimas, y por la misma razón: a esa
 * distancia el "terreno" y el objetivo son el mismo accidente geográfico.
 */
const val SUMMIT_MARGIN_M: Double = 200.0

/** Radio de recolocación de una cima al máximo del DEM. Ver SUMMIT_MARGIN_M. */
const val RELOCATE_RADIUS_M: Double = 200.0

/** Valor de dato ausente en el DEM. NUNCA es una altitud. */
const val VOID_ELEVATION: Short = -32768
