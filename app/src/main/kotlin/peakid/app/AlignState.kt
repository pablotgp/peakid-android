package peakid.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import peakid.engine.align.AlignmentDocument
import peakid.engine.align.AlignmentParams
import peakid.engine.align.ImageInfo
import peakid.engine.align.Observer
import peakid.engine.align.Provenance
import peakid.engine.align.SeedEntry
import peakid.engine.align.alignmentFileName
import peakid.engine.align.encodeAlignment
import peakid.engine.align.solvePitchRoll
import peakid.engine.align.toValues

/** De dónde salió la posición del observador. */
enum class PositionSource(val json: String) {
    EXIF("exif"), MANUAL("manual"), GPS("gps")
}

/** Declaración del tipo de revisión. Se DECLARA, no se deduce. */
enum class ReviewKind(val json: String?) {
    NONE(null), CORRECCION("correccion"), VERIFICACION("verificacion")
}

/**
 * Estado del ajuste manual.
 *
 * La procedencia se construye a partir de lo que REALMENTE pasó, no de lo que
 * conviene: si el usuario usó el ajuste cerrado de inclinación y giro, queda
 * `auto_pitch_roll = true` y el alineamiento solo cuenta como verdad si además
 * lo revisó. Congelar la ayuda y ajustar a mano produce `manual = true`, que es
 * la referencia LIMPIA que el arnés está esperando.
 */
class AlignState {

    var photo by mutableStateOf<LoadedPhoto?>(null)
    var packName by mutableStateOf<String?>(null)
    var panorama by mutableStateOf<Panorama?>(null)
    var status by mutableStateOf("Elige una foto para empezar.")

    var latDeg by mutableStateOf(0.0)
    var lonDeg by mutableStateOf(0.0)
    var positionSource by mutableStateOf(PositionSource.MANUAL)

    var azimuthDeg by mutableStateOf(0.0)
    var hfovDeg by mutableStateOf(65.0)
    var pitchDeg by mutableStateOf(0.0)
    var rollDeg by mutableStateOf(0.0)

    /** Sector acotado por el usuario: los deslizadores se mueven dentro. */
    var sectorStartDeg by mutableStateOf(0.0)
    var sectorSpanDeg by mutableStateOf(360.0)

    /**
     * ¿Ha movido el usuario el azimut o el sector con esta foto?
     *
     * La brújula puede dar su primera lectura DESPUÉS de cargarse la foto, y
     * entonces la siembra llega tarde. Que llegue tarde está bien; que llegue
     * tarde y pise un ajuste hecho a mano, no. Esto es lo que distingue los dos
     * casos.
     */
    var azimuthTouched by mutableStateOf(false)

    /** Hay posición utilizable. (0,0) es el valor sin poner, no el golfo de Guinea. */
    fun hasPosition(): Boolean = !(latDeg == 0.0 && lonDeg == 0.0)

    /** Cresta sobre la foto, en píxeles de la imagen COMPLETA. */
    val crestCols = mutableListOf<Double>()
    val crestRows = mutableListOf<Double>()
    var crestVersion by mutableStateOf(0)

    /**
     * ¿La cresta viene del detector o la marcó una persona?
     *
     * Cambia cómo se dibuja —línea fina frente a puntos—, porque son cosas
     * distintas: dos docenas de decisiones deliberadas frente a mil columnas
     * automáticas. Y cambia la procedencia del `.align.json`.
     */
    var crestDetected by mutableStateOf(false)

    var compassAzimuthDeg by mutableStateOf<Double?>(null)

    /**
     * Rumbo de la brújula EN EL DISPARO, solo para fotos hechas con la app.
     *
     * Es la ÚNICA vía de tener una lectura de brújula atada a una foto: esta
     * cámara no escribe `GPSImgDirection` en el EXIF, así que sin esto la
     * lectura se pierde en cuanto el usuario baja el móvil.
     *
     * Y es lo que hace medible el error del sensor: exportada como semilla
     * junto al azimut que el usuario ajusta a mano, la DIFERENCIA entre las dos
     * es el error real de la brújula en ese disparo. Con una foto de galería no
     * existe tal diferencia que medir — la brújula de ahora no dice nada de una
     * foto de otro día— y por eso esa no se exporta como `compass`.
     */
    var capturaBrujulaDeg by mutableStateOf<Double?>(null)

    /** Hay una captura en curso esperando la primera lectura del sensor. */
    var esperandoBrujulaDeCaptura by mutableStateOf(false)
    var declinationDeg by mutableStateOf(0.0)

    // --- procedencia ---
    /**
     * Qué detector produjo la cresta, o `null` si la marcó una persona.
     *
     * Va al `.align.json` tal cual: una referencia nacida del detector no puede
     * usarse luego para medir ese mismo detector, y sin este campo esa
     * distinción se pierde.
     */
    var detectorUsado by mutableStateOf<String?>(null)
    var usedAutoPitchRoll by mutableStateOf(false)

    /** ¿Intervino la búsqueda automática en estos parámetros? */
    var searchUsed by mutableStateOf(false)

    /**
     * El último resultado de la búsqueda, CON sus reservas.
     *
     * Se guarda entero, y no solo el mejor candidato, porque las reservas y los
     * candidatos alternativos son parte del resultado: presentar el óptimo a
     * secas convertiría "el mejor de estos" en "este es", que es justo lo que el
     * proyecto no hace.
     */
    var searchResult by mutableStateOf<peakid.engine.align.SearchResult?>(null)
    private var paramsAfterAssist: AlignmentParams? = null

    /** Congela los parámetros que dejó una ayuda automática, para el delta. */
    fun marcarPuntoDePartidaAutomatico() {
        paramsAfterAssist = params()
    }
    var reviewKind by mutableStateOf(ReviewKind.NONE)
    var reviewNote by mutableStateOf("")
    var notes by mutableStateOf("")
    var lowConfidence by mutableStateOf(false)

    // semillas, con su origen
    var seedAzimuth by mutableStateOf(SeedEntry(0.0, "default"))
    var seedHfov by mutableStateOf(SeedEntry(65.0, "default"))

    /**
     * Olvida TODO lo que pertenece a la foto anterior.
     *
     * Sin esto, cargar otra foto conservaba la cresta de la anterior dibujada
     * encima —que es lo que se ve— y, peor, la PROCEDENCIA: el `.align.json`
     * de la foto B declaraba `detector = "modelo+dp"` y `auto_pitch_roll` por
     * un trabajo hecho sobre la foto A. Eso no es un fallo visual: es una
     * referencia que afirma algo falso sobre su propio origen, y el arnés la
     * creeria.
     *
     * Lo que NO se toca aqui: la posicion y el panorama. Dependen del sitio,
     * no de la foto, y `cargar` los vuelve a decidir con las semillas de la
     * foto nueva.
     */
    fun reiniciarParaFotoNueva() {
        crestCols.clear()
        crestRows.clear()
        crestDetected = false
        crestVersion++

        pitchDeg = 0.0
        rollDeg = 0.0

        detectorUsado = null
        usedAutoPitchRoll = false
        searchUsed = false
        searchResult = null
        paramsAfterAssist = null
        reviewKind = ReviewKind.NONE
        reviewNote = ""
        notes = ""
        lowConfidence = false
    }

    fun params() = AlignmentParams(azimuthDeg, hfovDeg, pitchDeg, rollDeg)

    /** ¿Movió algo el usuario DESPUÉS de la ayuda automática? */
    fun reviewDelta(): Map<String, Double> {
        val base = paramsAfterAssist ?: return emptyMap()
        val ahora = params()
        val delta = mutableMapOf<String, Double>()
        if (base.azimuthDeg != ahora.azimuthDeg) {
            delta["azimuth_deg"] = (ahora.azimuthDeg - base.azimuthDeg).round(3)
        }
        if (base.hfovDeg != ahora.hfovDeg) {
            delta["hfov_deg"] = (ahora.hfovDeg - base.hfovDeg).round(3)
        }
        if (base.pitchDeg != ahora.pitchDeg) {
            delta["pitch_deg"] = (ahora.pitchDeg - base.pitchDeg).round(3)
        }
        if (base.rollDeg != ahora.rollDeg) {
            delta["roll_deg"] = (ahora.rollDeg - base.rollDeg).round(3)
        }
        return delta
    }

    /**
     * ¿Ha revisado un humano lo que produjo la ayuda automática?
     *
     * Vale para las DOS ayudas —la búsqueda y el ajuste cerrado de inclinación
     * y giro—, no solo para la segunda. Mirarla solo a ella dejaba fuera el
     * caso más frecuente de la fase 7: buscar, corregir el azimut a ojo y
     * exportar. Eso es una revisión humana y tiene que constar como tal.
     */
    fun manualReview(): Boolean =
        (usedAutoPitchRoll || searchUsed) &&
            (reviewDelta().isNotEmpty() || reviewKind != ReviewKind.NONE)

    /**
     * Resuelve inclinación y giro en forma cerrada a partir de la cresta
     * marcada. Es lo que convierte cuatro controles en dos.
     *
     * Sin detector todavía (eso es la fase 6), la cresta la marca el usuario
     * tocando la línea del horizonte en la foto: `solvePitchRoll` no exige que
     * venga de un detector, solo columnas y filas.
     */
    fun solveAssist(): String {
        val pano = panorama ?: return "No hay panorama todavía."
        val foto = photo ?: return "No hay foto."
        if (crestCols.size < 20) {
            return "Marca al menos 20 puntos de la cresta (llevas ${crestCols.size}). " +
                "Con menos, el ajuste no se resuelve: mejor nada que un valor inventado."
        }
        val solved = solvePitchRoll(
            pano.profile.azimuthsDeg, pano.profile.elevationsDeg,
            params(), crestCols.toDoubleArray(), crestRows.toDoubleArray(),
            foto.widthPx, foto.heightPx,
        ) ?: return "No se pudo resolver: la línea proyectada no cubre bastantes " +
            "de las columnas marcadas. Acerca primero el azimut."

        pitchDeg = solved.first.round(2)
        rollDeg = solved.second.round(2)
        usedAutoPitchRoll = true
        paramsAfterAssist = params()
        return "Inclinación ${pitchDeg}° y giro ${rollDeg}° resueltos en forma cerrada."
    }

    fun exportDocument(photoName: String): AlignmentDocument {
        val foto = photo!!
        val pano = panorama!!
        return AlignmentDocument(
            photo = photoName,
            createdUtc = utcNow(),
            notes = notes,
            observer = Observer(
                latDeg = latDeg, lonDeg = lonDeg,
                eyeM = pano.eyeM, source = positionSource.json,
            ),
            seed = mapOf(
                "azimuth_deg" to seedAzimuth,
                "hfov_deg" to seedHfov,
                "pitch_deg" to SeedEntry(0.0, "default"),
                "roll_deg" to SeedEntry(0.0, "default"),
            ),
            alignment = params().toValues(),
            image = ImageInfo(foto.widthPx, foto.heightPx),
            provenance = Provenance.of(
                searchUsed = searchUsed,
                autoPitchRoll = usedAutoPitchRoll,
                detector = detectorUsado,
                manualReview = manualReview(),
                reviewDelta = reviewDelta(),
                reviewNote = reviewNote.ifBlank { null },
                reviewKind = reviewKind.json,
            ),
            lowConfidence = lowConfidence,
        )
    }

    /** Escribe el `.align.json` junto a las fotos exportadas de la app. */
    fun export(context: Context, photoName: String): File {
        val dir = File(context.getExternalFilesDir(null), "alineamientos")
        dir.mkdirs()
        val destino = File(dir, alignmentFileName(photoName))
        destino.writeText(encodeAlignment(exportDocument(photoName)), Charsets.UTF_8)
        return destino
    }

    private fun utcNow(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }
}
