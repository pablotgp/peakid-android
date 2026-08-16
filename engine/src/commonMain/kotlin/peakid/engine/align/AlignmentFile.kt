package peakid.engine.align

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * El fichero `.align.json`: mismo formato que `save_alignment` del motor.
 *
 * No es un formato de la app: es el del repo del motor, y la app ESCRIBE en él
 * para que sus alineamientos revisados entren directamente en el arnés de
 * evaluación. El doc técnico señala las **referencias limpias** como lo
 * inmediato que le falta al proyecto —las cinco actuales nacieron todas de la
 * heurística—, y una app en el campo es la forma natural de producirlas.
 *
 * ## Dos restricciones del CONSUMIDOR, no de este escritor
 *
 * Salen de leer `scripts/eval_skyline.py`, que es quien lo consume:
 *
 * 1. **`alignment` debe llevar EXACTAMENTE cuatro claves.** El arnés hace
 *    `AlignmentParams(**alineamiento["alignment"])`, así que colar ahí
 *    cualquier campo extra —la procedencia del FOV, una marca de versión— hace
 *    reventar al consumidor con un `TypeError`. Todo lo demás va fuera.
 * 2. **`observer` necesita `lat_deg`, `lon_deg` y `eye_m`.** El arnés los
 *    indexa sin `.get`, así que faltar uno es un `KeyError`.
 *
 * `eye_m` es la altitud del OJO sobre el nivel del mar, no la cota del suelo:
 * quien escriba suma la altura de la persona (~1.7 m).
 */

const val ALIGN_VERSION: Int = 1

@Serializable
data class AlignmentValues(
    @SerialName("azimuth_deg") val azimuthDeg: Double,
    @SerialName("hfov_deg") val hfovDeg: Double,
    @SerialName("pitch_deg") val pitchDeg: Double,
    @SerialName("roll_deg") val rollDeg: Double,
)

fun AlignmentParams.toValues(): AlignmentValues =
    AlignmentValues(azimuthDeg, hfovDeg, pitchDeg, rollDeg)

fun AlignmentValues.toParams(): AlignmentParams =
    AlignmentParams(azimuthDeg, hfovDeg, pitchDeg, rollDeg)

@Serializable
data class Observer(
    @SerialName("lat_deg") val latDeg: Double,
    @SerialName("lon_deg") val lonDeg: Double,
    /** Altitud del OJO sobre el nivel del mar, no la cota del suelo. */
    @SerialName("eye_m") val eyeM: Double,
    /** "exif", "manual", "gps". */
    val source: String,
)

/** Un valor inicial con su procedencia: "exif", "default", "manual". */
@Serializable
data class SeedEntry(val value: Double, val source: String)

@Serializable
data class ImageInfo(
    @SerialName("width_px") val widthPx: Int,
    @SerialName("height_px") val heightPx: Int,
    /**
     * Las dimensiones y los parámetros corresponden a la imagen CON la
     * orientación EXIF ya aplicada. Quien relea la foto debe aplicarla también:
     * un lector que entregue el buffer crudo daría ancho y alto intercambiados
     * en fotos verticales y todo el alineamiento saldría plausible e incorrecto.
     */
    @SerialName("exif_oriented") val exifOriented: Boolean = true,
)

/**
 * Cómo se obtuvo el alineamiento. **Decide si sirve como VERDAD.**
 *
 * Lo que contamina no es USAR la ayuda automática, sino ACEPTARLA SIN REVISAR.
 * `manual` y `reviewed` se DERIVAN con la misma regla que el motor y no se
 * pasan a mano: son conclusiones, no declaraciones.
 *
 * `reviewKind` sí se DECLARA (`correccion` / `verificacion`) y no se deduce de
 * que `reviewDelta` esté vacío: en el motor hubo una revisión que corrigió de
 * verdad —cambió de hipótesis por los topónimos— y aun así no dejó delta
 * numérico. Confundir ambas cosas hace afirmaciones falsas sobre el sesgo.
 */
@Serializable
data class Provenance(
    @SerialName("search_used") val searchUsed: Boolean,
    @SerialName("auto_pitch_roll") val autoPitchRoll: Boolean,
    val detector: String?,
    val manual: Boolean,
    @SerialName("manual_review") val manualReview: Boolean,
    @SerialName("review_delta") val reviewDelta: Map<String, Double>,
    @SerialName("review_note") val reviewNote: String?,
    @SerialName("review_kind") val reviewKind: String?,
    val reviewed: Boolean,
) {
    companion object {
        fun of(
            searchUsed: Boolean = false,
            autoPitchRoll: Boolean = false,
            detector: String? = null,
            manualReview: Boolean = false,
            reviewDelta: Map<String, Double> = emptyMap(),
            reviewNote: String? = null,
            reviewKind: String? = null,
        ): Provenance {
            val manual = !(searchUsed || autoPitchRoll)
            return Provenance(
                searchUsed = searchUsed,
                autoPitchRoll = autoPitchRoll,
                detector = detector,
                manual = manual,
                manualReview = manualReview,
                reviewDelta = reviewDelta,
                reviewNote = reviewNote,
                reviewKind = reviewKind,
                // sirve como verdad si nadie automático la tocó, o si un humano
                // la revisó después con información ajena al detector
                reviewed = manual || manualReview,
            )
        }
    }
}

@Serializable
data class AlignmentDocument(
    val version: Int = ALIGN_VERSION,
    /** RELATIVO al directorio del JSON, para que el par se mueva junto. */
    val photo: String,
    @SerialName("created_utc") val createdUtc: String,
    val notes: String = "",
    val observer: Observer,
    val seed: Map<String, SeedEntry>,
    val alignment: AlignmentValues,
    val image: ImageInfo,
    val provenance: Provenance,
    /**
     * Referencia dudosa: el alineamiento existe pero no es fiable como VERDAD.
     * Medir contra una referencia mala es peor que no medir, porque un detector
     * mejor mediría peor.
     */
    @SerialName("low_confidence") val lowConfidence: Boolean = false,
)

// `prettyPrint` con dos espacios y sin omitir nulos: así el fichero sale
// indistinguible del que escribe Python con `indent=2`, y `review_note: null`
// aparece igual que allí en vez de desaparecer.
private val alignJson = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = true
}

/** Serializa el documento. Termina en salto de línea, como el del motor. */
fun encodeAlignment(document: AlignmentDocument): String =
    alignJson.encodeToString(document) + "\n"

fun decodeAlignment(text: String): AlignmentDocument =
    alignJson.decodeFromString(text)

/** `foto.jpg` -> `foto.align.json`, en el mismo directorio. */
fun alignmentFileName(photoName: String): String {
    val stem = photoName.substringBeforeLast('.', photoName)
    return "$stem.align.json"
}
