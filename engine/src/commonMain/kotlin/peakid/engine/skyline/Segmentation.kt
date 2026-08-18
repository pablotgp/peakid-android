package peakid.engine.skyline

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Geometría de la entrada del modelo. Puerto de `src/align/segmentation.py`.
 *
 * La inferencia en sí vive en el código de plataforma (`SegformerSky`), porque
 * ONNX Runtime no existe en `commonMain`. Aquí queda todo lo que es aritmética
 * pura, que es lo que puede estar bajo test multiplataforma.
 */

/** Índice de "sky" en ADE20K. */
const val SKY_CLASS: Int = 2

/**
 * Lado largo de la entrada del modelo.
 *
 * El preprocesador pide 512x512, pero el grafo ONNX tiene los ejes de alto y
 * ancho DINÁMICOS y SegFormer es tolerante a la escala. Importa mucho: el
 * modelo saca los logits a 1/4 de la entrada, y ese cuanto es el suelo del
 * error de localización. Medido en el motor, el residuo mediano contra los
 * alineamientos manuales sigue al cuanto:
 *
 *     entrada    cuanto (px de foto)   141720   141721   Nerja   sierra
 *     512                      ~32       8.4      4.8     3.2     29.9
 *     1024                     ~16       5.2      3.2     2.7     27.7
 *     1536                     ~11       2.0      3.0     2.6     27.1
 *     2048                      ~8       2.2      2.8     2.4     27.2
 *
 * 1536 es donde se estanca: 2048 no mejora y triplica el tiempo. Bajarlo
 * "porque es un móvil" cuesta resolución MEDIBLE, y además cruza el umbral que
 * cambia el reescalado de ampliación a reducción (ver [WORK_MAX_WIDTH]).
 */
const val INPUT_LONG_SIDE: Int = 1536

/** SegFormer reduce por 32; sin esto la salida cojea. */
const val SIZE_MULTIPLE: Int = 32

/**
 * Ancho máximo de la imagen de TRABAJO, sobre la que corren la DP y el coste.
 *
 * **Tiene que quedarse por debajo de [INPUT_LONG_SIDE], y no por gusto.**
 * Medido: Pillow escala el soporte de su filtro bilineal por el factor de
 * REDUCCIÓN, así que al reducir no coincide con una bilineal ingenua —29.7
 * niveles de gris de diferencia media, 141 de máximo—, mientras que al ampliar
 * coinciden hasta el redondeo (1 nivel).
 *
 * Con 1200 < 1536 el reescalado trabajo→modelo es siempre una AMPLIACIÓN, y
 * por eso este puerto puede usar bilineal simple y reproducir la cresta del
 * motor. Si alguien baja la entrada por debajo de este ancho, el reescalado se
 * invierte y la evidencia se degrada sin que nada reviente: las cuentas siguen
 * saliendo y el resultado sigue pareciendo una máscara.
 */
const val WORK_MAX_WIDTH: Int = 1200

/** Peso del término de borde frente al de región. */
const val EDGE_WEIGHT: Double = 0.3

/** Media y desviación de ImageNet, en orden RGB. */
val IMAGENET_MEAN = doubleArrayOf(0.485, 0.456, 0.406)
val IMAGENET_STD = doubleArrayOf(0.229, 0.224, 0.225)

/**
 * Tamaño de entrada que conserva el aspecto, en múltiplos de 32.
 *
 * Se conserva la RELACIÓN DE ASPECTO en vez de cuadrar la imagen: cuadrarla
 * desperdicia resolución en el eje largo, que es donde está la cresta.
 */
fun inputSize(width: Int, height: Int, longSide: Int = INPUT_LONG_SIDE): Pair<Int, Int> {
    val targetW: Int
    val targetH: Int
    if (width >= height) {
        targetW = longSide
        targetH = max(1, roundHalfEven(longSide.toDouble() * height / width))
    } else {
        targetH = longSide
        targetW = max(1, roundHalfEven(longSide.toDouble() * width / height))
    }
    return Pair(snap(targetW), snap(targetH))
}

private fun snap(v: Int): Int = max(SIZE_MULTIPLE, (v / SIZE_MULTIPLE) * SIZE_MULTIPLE)

/**
 * Redondeo AL PAR, que es el de `round()` de Python y el de numpy.
 *
 * Kotlin redondea 0.5 hacia arriba y Python hacia el par más cercano: 2.5 da 3
 * en Kotlin y 2 en Python.
 *
 * **MEDIDO: aquí no cambia nada, y aun así se conserva.** Barridas ~4 millones
 * de combinaciones de ancho, alto y lado largo, en NINGUNA cambia el tamaño de
 * entrada del modelo: [snap] a múltiplos de 32 absorbe la diferencia de una
 * unidad, y los empates que caen justo por debajo de un múltiplo de 32
 * redondean igual con las dos reglas, porque todo múltiplo de 32 es par.
 *
 * Se deja porque es el convenio correcto y porque la inercia depende de
 * [SIZE_MULTIPLE]: si ese 32 cambiara, la diferencia dejaría de estar
 * absorbida. No se le escribe un test que afirme que importa hoy — no
 * importa—, del mismo modo que el término `2*band` de [pathJumpLimit] está
 * latente y se vigila en el régimen donde sí muerde.
 */
internal fun roundHalfEven(v: Double): Int {
    val abajo = kotlin.math.floor(v)
    val frac = v - abajo
    return when {
        frac > 0.5 -> (abajo + 1).toInt()
        frac < 0.5 -> abajo.toInt()
        // empate exacto: al par
        abajo.toLong() % 2L == 0L -> abajo.toInt()
        else -> (abajo + 1).toInt()
    }
}

/**
 * Paso de submuestreo de la foto a la imagen de trabajo.
 *
 * Es DECIMACIÓN pura (`photo[::step, ::step]` en el motor), no un reescalado
 * filtrado: se toma un píxel de cada `step` y no se promedia nada. Filtrar
 * aquí daría una imagen más bonita y una cresta distinta de la del motor.
 */
fun workStep(photoWidth: Int, maxWidth: Int = WORK_MAX_WIDTH): Int =
    max(1, (photoWidth + maxWidth - 1) / maxWidth)   // ceil(ancho / maxWidth)

/**
 * Bilineal para llevar un mapa de una rejilla a otra, con el convenio de
 * centros de Pillow: `centro = (i + 0.5) · escala − 0.5`, y borde repetido.
 *
 * Vale porque en este camino SIEMPRE se amplía (ver [WORK_MAX_WIDTH]). Al
 * reducir haría falta el filtro de soporte escalado de Pillow, y no está aquí
 * a propósito: tenerlo invitaría a bajar la entrada sin medir.
 */
fun resampleBilinear(
    src: DoubleArray,
    srcW: Int,
    srcH: Int,
    dstW: Int,
    dstH: Int,
): DoubleArray {
    val out = DoubleArray(dstH * dstW)
    val escalaX = srcW.toDouble() / dstW
    val escalaY = srcH.toDouble() / dstH
    for (r in 0 until dstH) {
        val y = (r + 0.5) * escalaY - 0.5
        val y0 = kotlin.math.floor(y).toInt()
        val fy = y - y0
        val y0c = y0.coerceIn(0, srcH - 1)
        val y1c = (y0 + 1).coerceIn(0, srcH - 1)
        for (c in 0 until dstW) {
            val x = (c + 0.5) * escalaX - 0.5
            val x0 = kotlin.math.floor(x).toInt()
            val fx = x - x0
            val x0c = x0.coerceIn(0, srcW - 1)
            val x1c = (x0 + 1).coerceIn(0, srcW - 1)
            val v00 = src[y0c * srcW + x0c]
            val v01 = src[y0c * srcW + x1c]
            val v10 = src[y1c * srcW + x0c]
            val v11 = src[y1c * srcW + x1c]
            out[r * dstW + c] =
                v00 * (1 - fy) * (1 - fx) + v01 * (1 - fy) * fx +
                    v10 * fy * (1 - fx) + v11 * fy * fx
        }
    }
    return out
}
