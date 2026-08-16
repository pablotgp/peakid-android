package peakid.engine.geo

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geometría básica: distancia, azimut, curvatura+refracción y elevación.
 *
 * Puerto literal de `src/geo/__init__.py` del repo `peakid`. Las convenciones
 * de `docs/peakid-motor-CLAUDE.md` son VINCULANTES y se repiten aquí porque un
 * cambio de convenio entre motor y app sería el peor bug posible:
 *
 * - Coordenadas siempre `(lat, lon)` en ese orden, grados decimales, norte y
 *   este positivos.
 * - Azimut horario desde el norte GEOGRÁFICO, rango `[0, 360)`.
 *   Norte = 0, Este = 90, Sur = 180, Oeste = 270. NO es el convenio matemático.
 * - Elevación en grados sobre el plano horizontal del observador; puede ser
 *   negativa.
 * - Distancias y alturas en metros. Los km solo aparecen en mensajes al usuario.
 * - Grados fuera, radianes dentro.
 * - El sufijo del nombre es obligatorio (`azimuthDeg`, `latRad`, `dM`). La
 *   regla sobrevive al paso a camelCase: una variable de ángulo sin sufijo es
 *   un bug esperando.
 *
 * Curvatura y refracción van SIEMPRE incluidas y no hay parámetro para
 * desactivarlas. Podría haberlo, pero entonces alguien lo pondría en falso sin
 * darse cuenta y el error sería invisible. Al no existir la opción, el error es
 * imposible.
 */

/** Radio terrestre medio, metros. */
const val R_EARTH_M: Double = 6_371_000.0

/** Coeficiente de refracción atmosférica estándar. */
const val K_REFRACTION: Double = 0.13

private const val DEG_TO_RAD = PI / 180.0
private const val RAD_TO_DEG = 180.0 / PI

private fun radians(deg: Double): Double = deg * DEG_TO_RAD

private fun degrees(rad: Double): Double = rad * RAD_TO_DEG

/** Grados a radianes. Públicas porque `align/` también las necesita, y la
 * regla del proyecto es que los radianes no salgan de donde se calculan. */
fun degToRad(deg: Double): Double = deg * DEG_TO_RAD

fun radToDeg(rad: Double): Double = rad * RAD_TO_DEG

/**
 * Normaliza un azimut a `[0, 360)`.
 *
 * EXISTE POR UNA DIFERENCIA REAL ENTRE PYTHON Y KOTLIN, no por gusto. El motor
 * escribe `az % 360.0` y en Python eso devuelve siempre un valor no negativo
 * (`-10.0 % 360.0 == 350.0`), porque el resto de Python toma el signo del
 * divisor. En Kotlin `%` toma el signo del DIVIDENDO: `-10.0 % 360.0 == -10.0`.
 *
 * Traducir el `% 360.0` literalmente habría producido azimuts negativos en todo
 * el cuadrante noroeste — exactamente el fallo plausible-pero-incorrecto contra
 * el que está escrito CLAUDE.md, porque 336° saldría como -24° y las cuentas
 * intermedias seguirían pareciendo razonables.
 *
 * Ningún sitio de este módulo usa `% 360.0` a pelo. Ninguno debe.
 */
fun normalizeAzimuthDeg(azimuthDeg: Double): Double {
    val wrapped = azimuthDeg % 360.0
    return if (wrapped < 0.0) wrapped + 360.0 else wrapped
}

/**
 * Normaliza una diferencia angular a `[-180, 180)`.
 *
 * Mismo motivo que [normalizeAzimuthDeg]: el motor escribe
 * `(x + 180.0) % 360.0 - 180.0`, que en Kotlin se rompe con `x` negativo.
 */
fun wrapDeltaDeg(deltaDeg: Double): Double = normalizeAzimuthDeg(deltaDeg + 180.0) - 180.0

/** Distancia en metros entre A y B sobre la esfera de radio [R_EARTH_M]. */
fun haversineM(
    latADeg: Double,
    lonADeg: Double,
    latBDeg: Double,
    lonBDeg: Double,
): Double {
    val latARad = radians(latADeg)
    val latBRad = radians(latBDeg)
    val dLatRad = radians(latBDeg - latADeg)
    val dLonRad = radians(lonBDeg - lonADeg)
    val a = sin(dLatRad / 2.0) * sin(dLatRad / 2.0) +
        cos(latARad) * cos(latBRad) * sin(dLonRad / 2.0) * sin(dLonRad / 2.0)
    return 2.0 * R_EARTH_M * asin(sqrt(a))
}

/**
 * Azimut inicial de A hacia B: horario desde el norte, en `[0, 360)`.
 *
 * `atan2(este, norte)`: ángulo desde el norte abriéndose hacia el este, o sea
 * horario. `atan2(norte, este)` daría el convenio matemático, girado.
 */
fun azimuthDeg(
    latADeg: Double,
    lonADeg: Double,
    latBDeg: Double,
    lonBDeg: Double,
): Double {
    val latARad = radians(latADeg)
    val latBRad = radians(latBDeg)
    val dLonRad = radians(lonBDeg - lonADeg)
    val east = sin(dLonRad) * cos(latBRad)
    val north = cos(latARad) * sin(latBRad) - sin(latARad) * cos(latBRad) * cos(dLonRad)
    return normalizeAzimuthDeg(degrees(atan2(east, north)))
}

/**
 * Coordenada alcanzada partiendo de (`latDeg`, `lonDeg`) con rumbo
 * `bearingDeg` (horario desde el norte) tras recorrer `distanceM` sobre el
 * círculo máximo.
 *
 * Problema geodésico directo, complementario de [haversineM] y [azimuthDeg].
 * Es la que hace avanzar el rayo por el terreno.
 *
 * Devuelve la latitud en [outLatLon]`[0]` y la longitud en [outLatLon]`[1]`,
 * en ese orden — nunca `(lon, lat)`.
 */
fun destinationPointDeg(
    latDeg: Double,
    lonDeg: Double,
    bearingDeg: Double,
    distanceM: Double,
    outLatLon: DoubleArray,
) {
    require(outLatLon.size >= 2) { "outLatLon necesita al menos 2 posiciones" }
    val latRad = radians(latDeg)
    val lonRad = radians(lonDeg)
    val bearingRad = radians(bearingDeg)
    val angRad = distanceM / R_EARTH_M
    val lat2Rad = asin(
        sin(latRad) * cos(angRad) + cos(latRad) * sin(angRad) * cos(bearingRad),
    )
    val lon2Rad = lonRad + atan2(
        sin(bearingRad) * sin(angRad) * cos(latRad),
        cos(angRad) - sin(latRad) * sin(lat2Rad),
    )
    outLatLon[0] = degrees(lat2Rad)
    outLatLon[1] = wrapDeltaDeg(degrees(lon2Rad))
}

/**
 * [destinationPointDeg] para un array de distancias con el mismo rumbo.
 *
 * El barrido de horizonte llama a esto una vez por RAYO en vez de una vez por
 * muestra. Escribe en arrays de salida en vez de asignar: en el bucle caliente
 * se recorren millones de muestras y una asignación por muestra se nota.
 *
 * Un test comprueba que coincide con la versión escalar.
 */
fun destinationPointsDeg(
    latDeg: Double,
    lonDeg: Double,
    bearingDeg: Double,
    distancesM: DoubleArray,
    outLatDeg: DoubleArray,
    outLonDeg: DoubleArray,
    count: Int = distancesM.size,
) {
    require(outLatDeg.size >= count && outLonDeg.size >= count) {
        "los arrays de salida no llegan a $count"
    }
    val latRad = radians(latDeg)
    val lonRad = radians(lonDeg)
    val bearingRad = radians(bearingDeg)
    val sinLat = sin(latRad)
    val cosLat = cos(latRad)
    val sinBearing = sin(bearingRad)
    val cosBearing = cos(bearingRad)
    for (i in 0 until count) {
        val angRad = distancesM[i] / R_EARTH_M
        val sinAng = sin(angRad)
        val cosAng = cos(angRad)
        val lat2Rad = asin(sinLat * cosAng + cosLat * sinAng * cosBearing)
        val lon2Rad = lonRad + atan2(
            sinBearing * sinAng * cosLat,
            cosAng - sinLat * sin(lat2Rad),
        )
        outLatDeg[i] = degrees(lat2Rad)
        outLonDeg[i] = wrapDeltaDeg(degrees(lon2Rad))
    }
}

/**
 * Caída aparente en metros por curvatura terrestre, corregida por refracción
 * atmosférica.
 *
 * Siempre incluida, no es opcional. Referencia: 10 km → 6.8 m, 30 km → 61.5 m,
 * 60 km → 245.9 m. A 60 km son 246 metros: ignorarlo hace que aparezcan cimas
 * que en realidad están ocultas.
 */
fun curvatureDropM(dM: Double): Double = (1.0 - K_REFRACTION) * dM * dM / (2.0 * R_EARTH_M)

/**
 * Ángulo de elevación del objetivo B (altura `hBM`) a distancia `dM`, visto
 * desde el observador A (altura `hAM`).
 *
 * Incluye siempre curvatura+refracción. Negativo si B queda bajo el plano
 * horizontal del observador.
 *
 * Recibe la DISTANCIA, no las coordenadas: en un barrido de horizonte esta
 * función se llama millones de veces y la distancia siempre se conoce de
 * antemano.
 *
 * `hAM` es la altitud del OJO sobre el nivel del mar, no la cota del suelo:
 * quien llame suma la altura de la persona (~1.7 m).
 */
fun elevationDeg(dM: Double, hAM: Double, hBM: Double): Double =
    degrees(atan2(hBM - hAM - curvatureDropM(dM), dM))

/**
 * [elevationDeg] sobre arrays (una llamada por rayo en vez de por muestra).
 *
 * Propaga NaN: una muestra de terreno NaN (void del DEM) da elevación NaN, que
 * quien llame debe descartar con un máximo que ignore NaN, nunca con un máximo
 * corriente — un solo NaN envenenaría el azimut entero.
 */
fun elevationsDeg(
    distancesM: DoubleArray,
    hAM: Double,
    terrainM: DoubleArray,
    outElevationDeg: DoubleArray,
    count: Int = distancesM.size,
) {
    require(outElevationDeg.size >= count) { "el array de salida no llega a $count" }
    for (i in 0 until count) {
        val dM = distancesM[i]
        outElevationDeg[i] = degrees(atan2(terrainM[i] - hAM - curvatureDropM(dM), dM))
    }
}
