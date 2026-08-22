package peakid.app

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.PaddingValues
import peakid.engine.align.Cardinal
import peakid.engine.align.SeedEntry
import peakid.engine.align.nearestCardinal
import peakid.engine.align.sectorForCardinal
import peakid.engine.geo.normalizeAzimuthDeg
import peakid.engine.geo.wrapDeltaDeg

class MainActivity : ComponentActivity() {

    private lateinit var compass: Compass

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        compass = Compass(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(color = Color(0xFF0B1016)) {
                    AlignScreen(compass)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Elegir foto pausa esta actividad. Sin esto, el sensor se suelta al
        // abrir la galería y no vuelve nunca: la foto llega y la brújula está
        // muerta.
        compass.resume()
    }

    override fun onPause() {
        super.onPause()
        compass.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        compass.release()
    }
}

@Composable
private fun AlignScreen(compass: Compass) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember { AlignState() }
    val repo = remember { PackRepository(context) }
    val cache = remember { PanoramaCache() }
    var markMode by remember { mutableStateOf(false) }
    var showCrest by remember { mutableStateOf(true) }
    var latTexto by remember { mutableStateOf("") }
    var lonTexto by remember { mutableStateOf("") }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val detector = remember { DetectorDeCresta(context) }
    // el URI de la foto cargada: el detector vuelve a leerla a resolución
    // COMPLETA, porque el bitmap de pantalla está submuestreado y promediado
    var fotoUri by remember { mutableStateOf<Uri?>(null) }

    val permiso = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    fun cargar(uri: Uri, nombre: String) {
        scope.launch {
            state.status = "Cargando foto…"
            fotoUri = uri
            val foto = withContext(Dispatchers.IO) { PhotoSource.load(context, uri, nombre) }
            state.photo = foto
            android.util.Log.i(
                TAG,
                "foto '$nombre' ${foto.widthPx}x${foto.heightPx} " +
                    "exif: lat=${foto.seeds.latDeg} lon=${foto.seeds.lonDeg} " +
                    "az=${foto.seeds.azimuthDeg} hfov=${foto.seeds.hfovDeg} " +
                    "gpsInvalido=${foto.seeds.gpsInvalid}",
            )

            // ORDEN DE PRIORIDAD: EXIF primero. Para una foto de galería la
            // posición es la de la FOTO, no la del móvil ahora.
            val s = foto.seeds
            when {
                s.latDeg != null && s.lonDeg != null -> {
                    state.latDeg = s.latDeg
                    state.lonDeg = s.lonDeg
                    state.positionSource = PositionSource.EXIF
                }
                else -> {
                    val loc = LastKnownPosition.get(context)
                    if (loc != null) {
                        state.latDeg = loc.latitude
                        state.lonDeg = loc.longitude
                        state.positionSource = PositionSource.GPS
                    } else {
                        state.positionSource = PositionSource.MANUAL
                    }
                }
            }
            state.seedHfov = s.hfovDeg
                ?.let { SeedEntry(it, "exif") } ?: SeedEntry(65.0, "default")
            s.hfovDeg?.let { state.hfovDeg = it }

            // Estado limpio para la foto nueva: sin esto, la semilla de la foto
            // anterior sigue puesta y la siembra tardía nunca se activa. Y la
            // cresta, la inclinación y la PROCEDENCIA de la foto anterior se
            // quedaban pegadas a la nueva.
            state.reiniciarParaFotoNueva()
            state.azimuthTouched = false
            state.seedAzimuth = SeedEntry(0.0, "default")
            state.azimuthDeg = 0.0
            state.sectorStartDeg = 0.0
            state.sectorSpanDeg = 360.0

            // SEMILLA DEL AZIMUT: la dirección del EXIF si la foto la trae, y
            // si no la brújula — que es de lo que hablaba el plan al decir "la
            // brújula como pista". Sin esto el azimut arranca en 0°, mirando al
            // norte, y la línea proyectada sale donde no está la foto.
            //
            // La brújula solo dice hacia dónde apunta el móvil AHORA, que en
            // una foto de galería no tiene por qué ser hacia dónde apuntaba al
            // dispararla: es un punto de partida, no una medida de la foto. Se
            // acota el sector igualmente porque acotarlo mal cuesta UN ARRASTRE
            // —la tira sigue enseñando los 360° enteros y el sector correcto
            // sigue estando a la vista— y acertar ahorra buscar a ciegas.
            //
            // Sin lectura todavía, la semilla queda en "default" y es la
            // siembra TARDÍA de abajo la que la resuelve cuando el sensor hable.
            when {
                s.azimuthDeg != null ->
                    sembrarAzimut(state, s.azimuthDeg, "exif")
                // FOTO HECHA CON LA APP: la lectura es del disparo y se exporta
                // como `compass`. Es evidencia sobre ESTA foto.
                state.capturaBrujulaDeg != null ->
                    sembrarAzimut(state, state.capturaBrujulaDeg!!, "compass")
                // FOTO DE GALERÍA: la brújula dice hacia dónde apunta el móvil
                // ahora, no dónde miraba la foto. Sirve de punto de partida y se
                // exporta con OTRO nombre, para que no ensucie las medidas del
                // error del sensor: mezclarlas daría filas donde la "diferencia
                // brújula-ajuste" no mide nada.
                compass.positioned && state.compassAzimuthDeg != null ->
                    sembrarAzimut(state, state.compassAzimuthDeg!!, "compass_live")
            }

            // los campos se siembran con la posicion en uso: corregir un
            // numero es mucho mas facil que escribirlo entero, y ademas hace
            // VISIBLE de donde ha salido
            latTexto = "%.6f".format(state.latDeg)
            lonTexto = "%.6f".format(state.lonDeg)

            if (s.gpsInvalid) {
                state.status = "La foto trae bloque GPS pero no es utilizable. " +
                    "Introduce la posición a mano."
            }
            // NO se invalida la cache. El panorama depende de la POSICION, no
            // de la foto, y su clave ya esta cuantizada por posicion: si el
            // usuario carga otra foto del mismo sitio, la respuesta correcta es
            // la que ya esta calculada. Invalidar aqui forzaba un barrido de
            // ~20 s para reproducir exactamente el mismo panorama, y ademas
            // hacia parecer que "no se actualizaba" cuando lo que pasaba es que
            // no tenia por que cambiar.
            recalcular(state, repo, cache, scope)
        }
    }

    val galeria = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        // una foto de galería no tiene lectura de disparo, y arrastrar la de
        // una captura anterior sería atribuirle a esta foto un rumbo que no es
        // suyo
        state.capturaBrujulaDeg = null
        state.esperandoBrujulaDeCaptura = false
        uri?.let { cargar(it, nombreDe(context, it)) }
    }

    val camara = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { ok -> if (ok) cameraUri?.let { cargar(it, nombreDe(context, it)) } }

    LaunchedEffect(Unit) {
        permiso.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        // El estado de los paquetes se dice AL ARRANCAR, no al fallar. Antes
        // solo se descubría después de elegir una foto, que es tarde y confunde
        // el problema de datos con un problema de la foto.
        val instalados = repo.installed()
        android.util.Log.i(
            TAG, "packs: dir=${repo.packsDir()} bruto=${repo.rawEntries()}",
        )
        if (state.photo == null) {
            state.status = if (instalados.isEmpty()) {
                "Sin paquetes en ${repo.packsDir().absolutePath} — " +
                    "empuja uno antes de elegir foto." +
                    repo.rawEntries().takeIf { it.isNotEmpty() }
                        ?.joinToString(", ", prefix = " Hay: ").orEmpty()
            } else {
                "Paquetes: ${instalados.joinToString(", ")}. Elige una foto para empezar."
            }
        }
    }

    // El sensor arranca YA, y vuelve a arrancar en cuanto hay posición para
    // recalcular la declinación.
    //
    // Antes solo arrancaba tras conocerse la posición —es decir, DESPUÉS de
    // cargar la foto—, así que en el instante en que `cargar` leía la brújula
    // seguía valiendo null: la semilla caía siempre en "default", el azimut se
    // quedaba en 0° y el sector en 360°. La siembra existía y no se aplicaba
    // nunca. Arrancar pronto reduce la ventana; la siembra tardía de abajo la
    // cierra del todo, porque la primera lectura puede llegar en cualquier
    // momento y no hay orden que lo garantice.
    LaunchedEffect(state.latDeg, state.lonDeg) {
        if (!compass.available) return@LaunchedEffect
        val lat = if (state.hasPosition()) state.latDeg else null
        val lon = if (state.hasPosition()) state.lonDeg else null
        compass.start(lat, lon, 0.0) { az ->
            state.compassAzimuthDeg = az
            state.declinationDeg = compass.declinationDeg

            // primera lectura tras el disparo: esa es la del disparo
            if (state.esperandoBrujulaDeCaptura && compass.positioned) {
                state.capturaBrujulaDeg = az
                state.esperandoBrujulaDeCaptura = false
            }

            // SIEMBRA TARDÍA. Se aplica una sola vez y solo si nadie ha dicho
            // nada mejor: hay foto, la semilla sigue siendo el 0° por defecto,
            // el usuario no ha tocado azimut ni sector, y la declinación ya
            // corresponde a esta posición. `sembrarAzimut` deja la fuente en
            // "compass", así que la condición deja de cumplirse y esto no
            // vuelve a dispararse con cada muestra del sensor.
            if (compass.positioned &&
                state.photo != null &&
                state.seedAzimuth.source == "default" &&
                !state.azimuthTouched
            ) {
                val delDisparo = state.capturaBrujulaDeg
                if (delDisparo != null) {
                    sembrarAzimut(state, delDisparo, "compass")
                } else {
                    sembrarAzimut(state, az, "compass_live")
                }
            }
        }
    }

    Column(
        // safeDrawingPadding: desde targetSdk 35 el borde a borde es
        // obligatorio, así que sin esto los controles de arriba quedan DEBAJO
        // de la barra de estado y no se pueden pulsar.
        Modifier.fillMaxSize()
            .safeDrawingPadding()
            .padding(10.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                galeria.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            }) { Text("Galería") }
            Button(onClick = {
                val dir = File(context.getExternalFilesDir(null), "fotos").apply { mkdirs() }
                val destino = File(dir, "peakid_${System.currentTimeMillis()}.jpg")
                val uri = FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", destino,
                )
                cameraUri = uri
                // La lectura se toma en cuanto el sensor hable DESPUÉS de
                // disparar, no ahora: ahora el móvil apunta a la pantalla, no a
                // la montaña. La bandera la resuelve el `onChange` de abajo.
                state.capturaBrujulaDeg = null
                state.esperandoBrujulaDeCaptura = true
                camara.launch(uri)
            }) { Text("Cámara") }
        }

        Text(state.status, fontSize = 13.sp, color = Color(0xFFB8C6D4))

        val pano = state.panorama
        if (pano != null) {
            Text(
                "paquete ${state.packName} · ojo a ${"%.1f".format(pano.eyeM)} m · " +
                    "${pano.peaks.size} cimas · barrido ${pano.computeMs} ms" +
                    if (!pano.peaksComplete) " · registro de cimas INCOMPLETO en esta zona" else "",
                fontSize = 11.sp, color = Color(0xFF8FA3B5), fontFamily = FontFamily.Monospace,
            )
        }

        // 0) LA POSICION, que es de donde sale todo lo demas.
        //
        // El panorama depende de la POSICION, no de la foto: dos fotos del
        // mismo sitio dan el mismo panorama, y eso es correcto. Lo que faltaba
        // era poder DECIR el sitio. Una foto de galeria sin GPS utilizable caia
        // en la posicion actual del movil, sin alternativa, mientras el propio
        // aviso decia "introduce la posicion a mano". Pedir algo que la
        // interfaz no ofrece es peor que no decir nada.
        Text("0 · Posición del observador", fontSize = 12.sp, color = Color(0xFFFFC400))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = latTexto, onValueChange = { latTexto = it },
                label = { Text("lat") }, singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = lonTexto, onValueChange = { lonTexto = it },
                label = { Text("lon") }, singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = {
                val la = latTexto.trim().replace(',', '.').toDoubleOrNull()
                val lo = lonTexto.trim().replace(',', '.').toDoubleOrNull()
                if (la == null || lo == null ||
                    abs(la) > 90.0 || abs(lo) > 180.0
                ) {
                    // ni se aproxima ni se ignora: se dice
                    state.status = "Coordenadas no válidas: lat en [-90,90], " +
                        "lon en [-180,180], en grados decimales."
                } else {
                    state.latDeg = la
                    state.lonDeg = lo
                    state.positionSource = PositionSource.MANUAL
                    recalcular(state, repo, cache, scope)
                }
            }) { Text("Usar") }
        }
        Text(
            "posición ${"%.6f".format(state.latDeg)}, ${"%.6f".format(state.lonDeg)}" +
                " · fuente ${state.positionSource.json}" +
                if (state.positionSource != PositionSource.EXIF) {
                    "  ← la foto no dice dónde se tomó"
                } else {
                    ""
                },
            fontSize = 11.sp, color = Color(0xFF8FA3B5), fontFamily = FontFamily.Monospace,
        )

        // 1) acotar el sector, PRIMERO
        Text("1 · Arrastra para acotar el sector", fontSize = 12.sp, color = Color(0xFFFFC400))
        SectorStrip(state, Modifier.fillMaxWidth().height(90.dp))
        // RUMBOS CARDINALES en vez de grados.
        //
        // El arrastre pide el sector EN GRADOS, y el usuario no los sabe: nadie
        // mira una sierra y piensa "eso esta a 40°". Si sabe decir "miraba al
        // nordeste". Cada boton fija un sector de 90° centrado en su rumbo, que
        // es lo que acoto bien el caso de Nerja -la respuesta estaba en 40.6° y
        // el sector del NE va de 0° a 90°-. No hace falta precision, solo
        // senalar la zona; la precision la pone despues la busqueda.
        //
        // El arrastre sigue estando para quien quiera afinar: esto lo
        // complementa, no lo sustituye.
        Text(
            "¿Hacia dónde miraba la foto?",
            fontSize = 12.sp, color = Color(0xFFB8C6D4),
        )
        val porRumbo = { c: Cardinal ->
            val (inicio, amplitud) = sectorForCardinal(c)
            state.sectorStartDeg = inicio
            state.sectorSpanDeg = amplitud
            state.azimuthDeg = c.azimuthDeg
            state.azimuthTouched = true
            state.status = "Sector acotado al ${c.etiqueta} " +
                "(${fmt(inicio)}° → ${fmt(normalizeAzimuthDeg(inicio + amplitud))}°)"
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (c in Cardinal.entries.take(4)) {
                OutlinedButton(
                    onClick = { porRumbo(c) },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp),
                ) { Text(c.etiqueta, fontSize = 13.sp) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (c in Cardinal.entries.drop(4)) {
                OutlinedButton(
                    onClick = { porRumbo(c) },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp),
                ) { Text(c.etiqueta, fontSize = 13.sp) }
            }
        }

        // Volver a los 360 NO se puede conseguir arrastrando: el arrastre
        // produce arcos, nunca el circulo entero, y `sectorToRanges` lee un
        // arco de mas de 180 grados como el COMPLEMENTARIO. Sin este boton, una
        // vez acotado el sector no habia camino de vuelta a "no se donde
        // mirar", que es un estado legitimo.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                state.sectorStartDeg = 0.0
                state.sectorSpanDeg = 360.0
                state.azimuthTouched = true   // sigue siendo una eleccion suya
            }) { Text("Todo el horizonte (360°)") }
        }
        // El rumbo con NOMBRE, no solo en grados: "159°" no le dice nada a
        // nadie y "S" sí — y con el nombre el usuario puede DESMENTIR la
        // lectura ("yo no miraba al sur"), que es algo que un número no
        // permite. Vale tanto para el sector como para la brújula.
        val centroSector = normalizeAzimuthDeg(state.sectorStartDeg + state.sectorSpanDeg / 2.0)
        val rumboSector = if (state.sectorSpanDeg >= 359.0) {
            "todo el horizonte"
        } else {
            "hacia el ${nearestCardinal(centroSector).etiqueta}"
        }
        Text(
            "sector ${fmt(state.sectorStartDeg)}° → " +
                "${fmt(normalizeAzimuthDeg(state.sectorStartDeg + state.sectorSpanDeg))}°" +
                "  (${fmt(state.sectorSpanDeg)}° · $rumboSector)",
            fontSize = 11.sp, color = Color(0xFF8FA3B5), fontFamily = FontFamily.Monospace,
        )
        // Que la lectura del disparo quedó registrada tiene que VERSE: es el
        // dato que hace medible el error del sensor, y si se perdiera en
        // silencio el usuario exportaría creyendo que lo tiene.
        state.capturaBrujulaDeg?.let { d ->
            Text(
                "brújula EN EL DISPARO ${fmt(d)}° (${nearestCardinal(d).etiqueta}) " +
                    "· se exporta como semilla `compass`",
                fontSize = 11.sp, color = Color(0xFF8FD48F),
                fontFamily = FontFamily.Monospace,
            )
        }
        Text(
            state.compassAzimuthDeg?.let {
                "brújula ${fmt(it)}° (${nearestCardinal(it).etiqueta}) · " +
                    "declinación ${fmt(state.declinationDeg)}°" +
                    // Cuando la brújula ya sembró el sector, decirlo: los
                    // botones de rumbo pasan a ser una corrección, no un paso
                    // obligatorio. Preguntar lo que ya está contestado es
                    // trabajo que el usuario no debería hacer.
                    if (state.seedAzimuth.source == "compass" && !state.azimuthTouched) {
                        "  ← el sector ya viene de aquí"
                    } else {
                        ""
                    }
            } ?: "sin brújula",
            fontSize = 11.sp, color = Color(0xFF8FA3B5), fontFamily = FontFamily.Monospace,
        )

        if (state.photo != null) {
            PhotoOverlay(
                state,
                Modifier.fillMaxWidth().aspectRatio(
                    state.photo!!.widthPx.toFloat() / state.photo!!.heightPx,
                ).background(Color.Black),
                onMarkCrest = { c, r ->
                    // marcar a dedo sobre una cresta detectada la convierte en
                    // mezcla; se declara como marcada, que es lo conservador
                    state.crestDetected = false
                    state.crestCols.add(c); state.crestRows.add(r); state.crestVersion++
                },
                markMode = markMode,
                showCrest = showCrest,
            )
        }

        // 2) los dos controles
        Text("2 · Azimut y campo", fontSize = 12.sp, color = Color(0xFFFFC400))
        val lo = state.sectorStartDeg
        val span = state.sectorSpanDeg
        Slider(
            value = wrapDeltaDeg(state.azimuthDeg - lo).let {
                (if (it < 0) it + 360.0 else it).coerceIn(0.0, span)
            }.toFloat(),
            onValueChange = {
                state.azimuthTouched = true
                state.azimuthDeg = normalizeAzimuthDeg(lo + it)
            },
            valueRange = 0f..span.toFloat(),
        )
        Text(
            "azimut ${fmt(state.azimuthDeg)}°   ·   resolución ${"%.3f".format(span / 1000.0)} °/px",
            fontSize = 11.sp, color = Color(0xFF8FA3B5), fontFamily = FontFamily.Monospace,
        )
        Slider(
            value = state.hfovDeg.toFloat(),
            onValueChange = { state.hfovDeg = it.toDouble() },
            valueRange = 10f..80f,
        )
        Text("campo ${fmt(state.hfovDeg)}°", fontSize = 11.sp, color = Color(0xFF8FA3B5),
            fontFamily = FontFamily.Monospace)

        // 2b) LA BÚSQUEDA, que necesita una cresta
        Text("2b · Alineamiento automático", fontSize = 12.sp, color = Color(0xFFFFC400))
        val pista = pistaParaBuscar(state, compass)
        Text(
            "acotado por ${pista.fuente}" +
                if (pista.acotada) {
                    " · ±${"%.0f".format(pista.margenDeg)}° alrededor de " +
                        "${fmt(pista.centroDeg)}°"
                } else {
                    ""
                },
            fontSize = 11.sp,
            color = if (pista.acotada) Color(0xFF8FA3B5) else Color(0xFFFFA000),
            fontFamily = FontFamily.Monospace,
        )
        Button(
            enabled = state.panorama != null && state.crestCols.size >= 20,
            onClick = {
                scope.launch {
                    state.status = "Buscando el alineamiento…"
                    val r = runCatching {
                        withContext(Dispatchers.Default) { buscarAlineamiento(state, pista) }
                    }
                    r.onSuccess { res ->
                        state.searchResult = res
                        val mejor = res.candidates.firstOrNull()
                        if (mejor == null) {
                            state.status = "La búsqueda no ha encontrado ningún " +
                                "candidato. Queda el ajuste a mano."
                        } else {
                            state.aplicar(mejor.params)
                            state.searchUsed = true
                            // el punto de partida se congela AQUÍ: lo que el
                            // usuario mueva a partir de ahora es su revisión
                            state.marcarPuntoDePartidaAutomatico()
                            state.status = "Alineamiento propuesto: " +
                                "azimut ${fmt(mejor.params.azimuthDeg)}° · " +
                                "campo ${fmt(mejor.params.hfovDeg)}° · " +
                                "inclinación ${fmt(mejor.params.pitchDeg)}° · " +
                                "giro ${fmt(mejor.params.rollDeg)}° · " +
                                "error ${"%.1f".format(mejor.errorPx)} px " +
                                "(${"%.2f".format(mejor.errorDeg)}°) · " +
                                "cobertura ${"%.0f".format(100 * mejor.coverage)}%"
                        }
                        android.util.Log.i(TAG, "busqueda: ${state.status}")
                    }.onFailure {
                        state.status = "La búsqueda ha fallado: ${it.message}. " +
                            "Queda el ajuste a mano."
                        android.util.Log.w(TAG, "busqueda FALLA", it)
                    }
                }
            },
        ) { Text("Buscar alineamiento") }
        if (state.crestCols.size < 20) {
            Text(
                "Hace falta una cresta primero: pulsa «Detectar», o márcala a dedo.",
                fontSize = 11.sp, color = Color(0xFF6F8496),
            )
        }

        // LAS RESERVAS, en pantalla y no en la consola. Si el resultado tiene
        // pegas, el usuario tiene que verlas: presentarlo sin ellas sería
        // inventarse una certeza, que es lo único que este proyecto no hace.
        state.searchResult?.let { res ->
            val reservas = reservasDe(res, pista)
            if (reservas.isEmpty()) {
                Text(
                    "Sin reservas detectables. NO es garantía de acierto: " +
                        "comprueba que los topónimos caen sobre los bultos que " +
                        "les tocan.",
                    fontSize = 11.sp, color = Color(0xFF8FD48F),
                )
            } else {
                Column(
                    Modifier.fillMaxWidth()
                        .background(Color(0x33FF5252))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "NO se puede dar por bueno:",
                        fontSize = 12.sp, color = Color(0xFFFF8A80),
                    )
                    for (r in reservas) {
                        Text("· $r", fontSize = 11.sp, color = Color(0xFFFFCDD2))
                    }
                }
            }

            // Los candidatos alternativos, accesibles. Entre dos hipótesis no
            // decide el error en píxeles sino QUÉ CIMA CAE SOBRE QUÉ BULTO, y
            // eso solo puede mirarlo una persona: para mirarlo hace falta poder
            // ponerse en la otra hipótesis.
            if (res.candidates.size > 1) {
                Text(
                    "Otras hipótesis — aplícalas y compara los topónimos:",
                    fontSize = 11.sp, color = Color(0xFFB8C6D4),
                )
                for ((i, cand) in res.candidates.withIndex()) {
                    OutlinedButton(
                        onClick = {
                            state.aplicar(cand.params)
                            state.searchUsed = true
                            state.marcarPuntoDePartidaAutomatico()
                            state.status = "Aplicada la hipótesis ${i + 1}: " +
                                "azimut ${fmt(cand.params.azimuthDeg)}° · " +
                                "campo ${fmt(cand.params.hfovDeg)}°"
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "${i + 1}) az ${fmt(cand.params.azimuthDeg)}° · " +
                                "campo ${fmt(cand.params.hfovDeg)}° · " +
                                "error ${"%.1f".format(cand.errorPx)} px" +
                                if (cand.saturated) " · SATURADO" else "",
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }

        // 3) inclinación y giro: cerrados o a mano
        Text("3 · Inclinación y giro", fontSize = 12.sp, color = Color(0xFFFFC400))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = markMode,
                onClick = { markMode = !markMode },
                label = { Text("Marcar cresta (${state.crestCols.size})") },
            )
            Button(
                enabled = state.photo != null && fotoUri != null,
                onClick = {
                    val uri = fotoUri!!
                    val foto = state.photo!!
                    scope.launch {
                        state.status = "Detectando la cresta…"
                        val r = runCatching {
                            withContext(Dispatchers.Default) { detector.detectar(uri, foto) }
                        }
                        r.onSuccess { m ->
                            state.crestCols.clear()
                            state.crestRows.clear()
                            // Solo las columnas FIABLES alimentan el ajuste.
                            // `valid` no dice "encontré cresta" -la DP siempre
                            // devuelve un camino entero- sino "aquí el camino
                            // manda". Meter las demás sería alimentar el ajuste
                            // con el relleno.
                            var usadas = 0
                            for (i in m.crest.valid.indices) {
                                if (!m.crest.valid[i]) continue
                                state.crestCols.add(m.crest.columnsPx[i])
                                state.crestRows.add(m.crest.rowsPx[i])
                                usadas++
                            }
                            state.crestVersion++
                            state.crestDetected = true
                            state.detectorUsado = "modelo+dp"
                            val cobertura = 100.0 * usadas / m.crest.valid.size
                            state.status =
                                "Cresta detectada: $usadas columnas fiables de " +
                                    "${m.crest.valid.size} (${"%.0f".format(cobertura)}%) · " +
                                    m.resumen()
                            android.util.Log.i(TAG, "deteccion: ${state.status}")
                        }.onFailure {
                            state.status = "El detector ha fallado: ${it.message}. " +
                                "Marca la cresta a dedo."
                            android.util.Log.w(TAG, "detector FALLA", it)
                        }
                    }
                },
            ) { Text("Detectar") }
            Button(onClick = { state.status = state.solveAssist() }) { Text("Resolver") }
            OutlinedButton(onClick = {
                state.crestCols.clear(); state.crestRows.clear()
                state.crestDetected = false
                state.detectorUsado = null
                state.crestVersion++
            }) { Text("Borrar") }
        }
        // Poder APAGAR la cresta es parte de poder juzgarla: encima de la
        // silueta tapa justo lo que hay que mirar.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = showCrest, onCheckedChange = { showCrest = it })
            Text(
                if (state.crestDetected) "Ver la cresta detectada" else "Ver la cresta marcada",
                fontSize = 12.sp, color = Color(0xFFB8C6D4),
            )
        }
        Text(
            "Con la cresta marcada, inclinación y giro se resuelven en forma cerrada: " +
                "el residuo es una recta en x, su ordenada da la inclinación y su pendiente el giro. " +
                "Sin usar «Resolver», el alineamiento queda como MANUAL y sirve de referencia limpia.",
            fontSize = 11.sp, color = Color(0xFF6F8496),
        )
        Slider(
            value = state.pitchDeg.toFloat(),
            onValueChange = { state.pitchDeg = it.toDouble() },
            valueRange = -30f..30f,
        )
        Text("inclinación ${fmt(state.pitchDeg)}°", fontSize = 11.sp, color = Color(0xFF8FA3B5),
            fontFamily = FontFamily.Monospace)
        Slider(
            value = state.rollDeg.toFloat(),
            onValueChange = { state.rollDeg = it.toDouble() },
            valueRange = -15f..15f,
        )
        Text("giro ${fmt(state.rollDeg)}°", fontSize = 11.sp, color = Color(0xFF8FA3B5),
            fontFamily = FontFamily.Monospace)

        // 4) exportar
        Text("4 · Exportar", fontSize = 12.sp, color = Color(0xFFFFC400))
        OutlinedTextField(
            value = state.notes, onValueChange = { state.notes = it },
            label = { Text("notas de la captura") }, modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.reviewKind == ReviewKind.CORRECCION,
                onClick = { state.reviewKind = ReviewKind.CORRECCION },
                label = { Text("corrección") },
            )
            FilterChip(
                selected = state.reviewKind == ReviewKind.VERIFICACION,
                onClick = { state.reviewKind = ReviewKind.VERIFICACION },
                label = { Text("verificación") },
            )
            FilterChip(
                selected = state.lowConfidence,
                onClick = { state.lowConfidence = !state.lowConfidence },
                label = { Text("dudosa") },
            )
        }
        Button(
            enabled = state.photo != null && state.panorama != null,
            onClick = {
                val destino = state.export(context, state.photo!!.displayName)
                state.status = "Escrito ${destino.absolutePath}"
            },
        ) { Text("Exportar .align.json") }

        Spacer(Modifier.height(24.dp))
    }
}

private const val TAG = "peakid"

/**
 * Semiancho del sector que se acota alrededor de la semilla.
 *
 * ±30° es la incertidumbre razonable de una brújula de móvil, y deja la
 * resolución del deslizador en 0.06 °/px en vez de 0.36.
 */
private const val SECTOR_SEMIANCHO_DEG = 30.0

/**
 * Pone la semilla del azimut y acota el sector a su alrededor.
 *
 * Es idempotente en lo que importa: deja `seedAzimuth.source` distinto de
 * "default", que es la condición que la siembra tardía usa para no repetirse
 * con cada muestra del sensor.
 */
private fun sembrarAzimut(state: AlignState, valorDeg: Double, fuente: String) {
    val az = normalizeAzimuthDeg(valorDeg)
    state.seedAzimuth = SeedEntry(az, fuente)
    state.azimuthDeg = az
    state.sectorStartDeg = normalizeAzimuthDeg(az - SECTOR_SEMIANCHO_DEG)
    state.sectorSpanDeg = 2 * SECTOR_SEMIANCHO_DEG
}

private fun recalcular(
    state: AlignState,
    repo: PackRepository,
    cache: PanoramaCache,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    scope.launch {
        android.util.Log.i(
            TAG,
            "recalcular: lat=${state.latDeg} lon=${state.lonDeg} " +
                "fuente=${state.positionSource} paquetes=${repo.installed()} " +
                "dir=${repo.packsDir()}",
        )
        if (state.latDeg == 0.0 && state.lonDeg == 0.0) {
            state.status = "Sin posición: la foto no trae GPS y no hay última conocida."
            return@launch
        }
        state.status = "Calculando el panorama…"
        val resultado = withContext(Dispatchers.IO) {
            when (val eleccion = repo.forObserver(state.latDeg, state.lonDeg)) {
                is PackChoice.Serves -> {
                    state.packName = eleccion.pack.name
                    Result.success(cache.get(eleccion.pack, state.latDeg, state.lonDeg, 1.7))
                }
                // Las dos situaciones se distinguen para el usuario, no se
                // funden en un "no se puede".
                is PackChoice.OutsideObserverRadius -> Result.failure(
                    IllegalStateException(
                        "Estás fuera de la zona del paquete «${eleccion.nearest}» " +
                            "(a ${(eleccion.distanceM / 1000).toInt()} km de su centro). " +
                            "Hace falta el paquete de esta comarca.",
                    ),
                )
                PackChoice.NoPacksInstalled -> Result.failure(
                    IllegalStateException(
                        // La receta es de UNA línea porque el directorio ya lo
                        // ha creado la app (ver PackRepository.packsDir). La
                        // anterior mandaba crearlo con `adb shell mkdir`, que
                        // lo deja a nombre del usuario shell con modo
                        // drwxrws---: los ficheros quedan ahí y `listFiles()`
                        // devuelve vacío. Medido en el Galaxy A17.
                        //
                        // Y va acompañada de lo que la app VE, no solo de lo
                        // que debería haber: sin eso, "no hay paquete" y "hay
                        // un paquete mal empujado" dan el mismo mensaje.
                        buildString {
                            append("No hay ningún paquete utilizable.\n\n")
                            append("Empuja uno (el directorio ya existe, no hay que crearlo):\n")
                            append("adb push packs/axarquia ")
                            append(repo.packsDir().absolutePath)
                            append("/\n\nBuscado en: ")
                            append(repo.packsDir().absolutePath)
                            val bruto = repo.rawEntries()
                            if (bruto.isEmpty()) {
                                append("\nContenido: vacío")
                            } else {
                                append("\nContenido:\n  ")
                                append(bruto.joinToString("\n  "))
                            }
                        },
                    ),
                )
            }
        }
        resultado
            .onSuccess {
                state.panorama = it
                state.status = "Panorama listo."
                android.util.Log.i(
                    TAG,
                    "panorama OK: paquete=${state.packName} ojo=${it.eyeM} " +
                        "cimas=${it.peaks.size} completo=${it.peaksComplete} " +
                        "barrido=${it.computeMs}ms",
                )
            }
            .onFailure {
                state.panorama = null
                state.status = it.message ?: "error"
                android.util.Log.w(TAG, "panorama FALLA: ${it.message}", it)
            }
    }
}

private fun fmt(v: Double) = "%.2f".format(v)

private fun nombreDe(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: "foto.jpg"
}
