package peakid.app

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import peakid.engine.geo.normalizeAzimuthDeg
import peakid.engine.geo.radToDeg

/**
 * Brújula: azimut respecto al norte GEOGRÁFICO, no al magnético.
 *
 * `TYPE_ROTATION_VECTOR` da el rumbo respecto al norte MAGNÉTICO.
 * [GeomagneticField] aporta la declinación del lugar y la fecha, y sumarla es
 * lo que lo convierte al convenio del motor —norte geográfico— que es el único
 * que existe en este proyecto.
 *
 * En Málaga la declinación ronda 1.5° W. Sin corregirla, todo el alineamiento
 * arrancaría con un sesgo constante: pequeño, sistemático y perfectamente
 * plausible, que es la peor combinación.
 */
class Compass(context: Context) : SensorEventListener {

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val rotation = FloatArray(9)
    private val orientation = FloatArray(3)

    /** Azimut magnético crudo, o null si aún no hay lectura. */
    var magneticAzimuthDeg: Double? = null
        private set

    /** Declinación aplicada, para poder mostrarla y no esconder la corrección. */
    var declinationDeg: Double = 0.0
        private set

    /**
     * ¿Se arrancó con una posición REAL?
     *
     * La declinación depende del lugar, así que arrancar sin posición da un
     * rumbo geográfico con un sesgo desconocido. Se arranca igual —tener el
     * sensor girando desde el principio es lo que evita la carrera con la carga
     * de la foto— pero el dato queda MARCADO, y nada que dependa del convenio
     * geográfico lo usa hasta que esto es cierto.
     */
    var positioned: Boolean = false
        private set

    val available: Boolean get() = sensor != null

    private var onChange: ((Double) -> Unit)? = null

    /**
     * Arranca —o vuelve a arrancar— el sensor.
     *
     * Llamarla otra vez con una posición mejor recalcula la declinación sin
     * perder la suscripción: es lo que hace el paso de "sin posición" a
     * "posicionada" cuando la foto trae GPS.
     */
    fun start(
        latDeg: Double?,
        lonDeg: Double?,
        altitudeM: Double,
        onChange: (Double) -> Unit,
    ) {
        positioned = latDeg != null && lonDeg != null
        declinationDeg = if (positioned) {
            GeomagneticField(
                latDeg!!.toFloat(), lonDeg!!.toFloat(), altitudeM.toFloat(),
                System.currentTimeMillis(),
            ).declination.toDouble()
        } else {
            0.0
        }
        this.onChange = onChange
        sensor?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    /**
     * Suelta el sensor sin olvidar a quién avisaba, para que [resume] pueda
     * volver a engancharlo.
     *
     * Perder aquí el `onChange` era lo que dejaba la brújula muerta justo
     * cuando hace falta: elegir foto —galería o cámara— PAUSA la actividad, así
     * que el sensor se soltaba, y al volver con la foto cargada ya no llegaba
     * ninguna lectura. La semilla no tenía nada que leer ni en el momento de
     * cargar ni después.
     */
    fun stop() {
        manager.unregisterListener(this)
    }

    /** Vuelve a engancharse tras un [stop], si alguna vez se arrancó. */
    fun resume() {
        if (onChange == null) return
        sensor?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    /** Suelta el sensor y olvida el destinatario. */
    fun release() {
        manager.unregisterListener(this)
        onChange = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        SensorManager.getOrientation(rotation, orientation)
        val magnetic = normalizeAzimuthDeg(radToDeg(orientation[0].toDouble()))
        magneticAzimuthDeg = magnetic
        // magnético + declinación = geográfico, que es el convenio del motor
        onChange?.invoke(normalizeAzimuthDeg(magnetic + declinationDeg))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

/**
 * Última posición conocida, SOLO como respaldo.
 *
 * La fuente primaria de una foto de galería es su propio EXIF: revelar una foto
 * de la Axarquía desde el sofá tiene que dar el panorama de la Axarquía, no el
 * del sofá. El orden es EXIF -> manual -> GPS actual.
 */
object LastKnownPosition {
    @SuppressLint("MissingPermission")
    fun get(context: Context): Location? {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val proveedores = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
        return proveedores.firstNotNullOfOrNull { p ->
            try {
                manager.getLastKnownLocation(p)
            } catch (_: SecurityException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}
