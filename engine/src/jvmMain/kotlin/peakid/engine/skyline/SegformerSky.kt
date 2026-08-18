package peakid.engine.skyline

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * Segmentación de cielo con SegFormer-B0 finetuneado en ADE20K, vía ONNX
 * Runtime. Puerto de `sky_probability` del motor.
 *
 * **Este fichero es compartido por escritorio y Android**: la API Java de ORT
 * (`ai.onnxruntime.*`) es la misma en los dos, solo cambia el artefacto Maven.
 * Es lo que permite comprobar el criterio de aceptación —las seis fotos contra
 * `cresta_referencia.json`— en la JVM, sin dispositivo.
 *
 * `torch` NUNCA es dependencia: el `.onnx` se obtiene una vez y aquí solo se
 * ejecuta.
 *
 * NO tiene tests unitarios propios, igual que en el motor: el preproceso
 * —÷255, normalización ImageNet, NCHW, softmax sobre 150 clases, clase 2,
 * reescalado ×4— lo vigila SOLO el fixture de la cresta, de extremo a extremo.
 * Es la parte del puerto donde un error no se ve, así que el fixture no es un
 * test más: es el único guardián que tiene.
 */
class SegformerSky(modelBytes: ByteArray) : SkyProbability, AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession =
        env.createSession(modelBytes, OrtSession.SessionOptions())
    private val inputName: String = session.inputNames.first()

    override fun compute(
        rgb: DoubleArray,
        width: Int,
        height: Int,
        longSide: Int,
    ): DoubleArray {
        val (targetW, targetH) = inputSize(width, height, longSide)

        // La foto de trabajo se lleva al tamaño de entrada canal a canal. Es
        // SIEMPRE una ampliación mientras WORK_MAX_WIDTH < INPUT_LONG_SIDE, y
        // de eso depende que la bilineal simple coincida con la de Pillow.
        val canal = DoubleArray(height * width)
        val entrada = FloatBuffer.allocate(3 * targetH * targetW)
        for (k in 0 until 3) {
            for (i in 0 until height * width) canal[i] = rgb[i * 3 + k]
            val escalado = resampleBilinear(canal, width, height, targetW, targetH)
            // Pillow devuelve uint8: se redondea ANTES de normalizar, igual
            // que en el motor, donde el resize sale de una imagen de 8 bits.
            val media = IMAGENET_MEAN[k]
            val desviacion = IMAGENET_STD[k]
            for (i in escalado.indices) {
                val v = kotlin.math.round(escalado[i]).coerceIn(0.0, 255.0) / 255.0
                entrada.put(k * targetH * targetW + i, ((v - media) / desviacion).toFloat())
            }
        }
        entrada.rewind()

        val forma = longArrayOf(1, 3, targetH.toLong(), targetW.toLong())
        val salida: DoubleArray
        OnnxTensor.createTensor(env, entrada, forma).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { resultado ->
                @Suppress("UNCHECKED_CAST")
                val logits = resultado.get(0).value as Array<Array<Array<FloatArray>>>
                salida = softmaxDeLaClaseCielo(logits[0])
            }
        }

        // de la rejilla del modelo (1/4 de la entrada) al tamaño de trabajo
        val gridH = targetH / 4
        val gridW = targetW / 4
        return resampleMascara(salida, gridW, gridH, width, height)
    }

    /**
     * `softmax` sobre las 150 clases y se queda con la de cielo.
     *
     * Se resta el máximo antes de exponenciar: sin eso, un logit grande
     * desborda a infinito y la probabilidad sale NaN, que luego viaja hasta la
     * cresta sin que nada avise.
     */
    private fun softmaxDeLaClaseCielo(logits: Array<Array<FloatArray>>): DoubleArray {
        val clases = logits.size
        val alto = logits[0].size
        val ancho = logits[0][0].size
        val out = DoubleArray(alto * ancho)
        for (r in 0 until alto) {
            for (c in 0 until ancho) {
                var maximo = Float.NEGATIVE_INFINITY
                for (k in 0 until clases) {
                    val v = logits[k][r][c]
                    if (v > maximo) maximo = v
                }
                var suma = 0.0
                for (k in 0 until clases) suma += kotlin.math.exp((logits[k][r][c] - maximo).toDouble())
                val cielo = kotlin.math.exp((logits[SKY_CLASS][r][c] - maximo).toDouble())
                out[r * ancho + c] = cielo / suma
            }
        }
        return out
    }

    /**
     * Lleva la máscara a la rejilla de trabajo pasando por uint8, como el motor.
     *
     * El motor escribe `Image.fromarray((sky * 255).astype(uint8)).resize(...)`,
     * así que la máscara se CUANTIZA a 256 niveles antes de reescalarse.
     * Reescalar en coma flotante daría una máscara ligeramente distinta y una
     * frontera `s >= 0.5` desplazada en algunas columnas.
     */
    private fun resampleMascara(
        mask: DoubleArray,
        srcW: Int,
        srcH: Int,
        dstW: Int,
        dstH: Int,
    ): DoubleArray {
        val cuantizada = DoubleArray(mask.size) {
            // astype(uint8) TRUNCA, no redondea
            kotlin.math.floor(mask[it] * 255.0).coerceIn(0.0, 255.0)
        }
        val escalada = resampleBilinear(cuantizada, srcW, srcH, dstW, dstH)
        for (i in escalada.indices) {
            escalada[i] = kotlin.math.round(escalada[i]).coerceIn(0.0, 255.0) / 255.0
        }
        return escalada
    }

    override fun close() {
        session.close()
    }
}
