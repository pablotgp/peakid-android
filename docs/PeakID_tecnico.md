# PeakID — Documento técnico

**Versión 0.3 · agosto de 2026**
Explicación módulo a módulo de todo lo construido

---

## Índice

1. [Cómo está organizado el proyecto](#1-cómo-está-organizado-el-proyecto)
2. [Los datos de partida](#2-los-datos-de-partida)
3. [`geo/` — la calculadora geométrica](#3-geo--la-calculadora-geométrica)
4. [`dem/` — leer el mapa de alturas](#4-dem--leer-el-mapa-de-alturas)
5. [`horizon/` — qué se ve y qué no](#5-horizon--qué-se-ve-y-qué-no)
6. [`peaks/` — ponerle nombre](#6-peaks--ponerle-nombre)
7. [`render/` — dibujarlo](#7-render--dibujarlo)
8. [`peakid/` — la línea de comandos](#8-peakid--la-línea-de-comandos)
9. [`align/` — encajar con la foto](#9-align--encajar-con-la-foto)
10. [`skyline/` y `segmentation/` — encontrar la cresta](#10-skyline-y-segmentation--encontrar-la-cresta)
11. [El arnés de evaluación](#11-el-arnés-de-evaluación)
12. [Cómo sabemos que funciona](#12-cómo-sabemos-que-funciona)
13. [Los bugs que aparecieron](#13-los-bugs-que-aparecieron-y-qué-enseñan)
14. [Lo que falta](#14-lo-que-falta)

---

## 1. Cómo está organizado el proyecto

```
peakid/
├── CLAUDE.md              las reglas de la casa
├── data/                  mapas de alturas descargados (.hgt)
├── cache/                 respuestas guardadas de internet
├── models/                el modelo de segmentación (.onnx)
├── Dataset/               fotos de referencia y sus alineamientos
├── src/
│   ├── geo/               distancias, direcciones, ángulos
│   ├── dem/               lectura del mapa de alturas
│   ├── horizon/           qué se ve desde un punto
│   ├── peaks/             nombres de las cimas
│   ├── render/            generación de imágenes
│   └── align/             encaje con fotografías
│       ├── search.py      búsqueda automática de parámetros
│       ├── skyline.py     camino de coste mínimo
│       ├── segmentation.py  el modelo de cielo
│       └── gui.py         la ventana de ajuste
├── peakid/                la interfaz de línea de comandos
├── scripts/               arnés de evaluación
└── tests/                 78 pruebas automáticas
```

Cada módulo depende solo de los anteriores. `geo/` no depende de nadie; todo
lo demás depende de `geo/`. Esa cadena es importante: si la base fallara, todo
lo construido encima fallaría también.

### El fichero de reglas

`CLAUDE.md` no es documentación: es un contrato. Fija por escrito cómo se
miden los ángulos, en qué orden van las coordenadas, qué correcciones son
obligatorias y qué no se puede hacer.

Existe porque este proyecto tiene un modo de fallo peculiar: **los errores
producen resultados creíbles**. Un signo invertido genera un panorama
perfectamente plausible pero girado noventa grados. No hay ninguna señal de
alarma. Sin reglas escritas de antemano, media aplicación acaba usando un
criterio y la otra media el contrario, y eso es indetectable a posteriori.

---

## 2. Los datos de partida

El sistema usa tres fuentes, y cada una hace solo lo que sabe hacer.

### El mapa de alturas (SRTM)

Ficheros con extensión `.hgt` que contienen la altitud de cada punto del
terreno. Cada fichero cubre un cuadrado de un grado de latitud por uno de
longitud, con un dato cada 30 metros.

No tienen cabecera ni metadatos: son 26 millones de bytes seguidos, y toda la
información de cómo interpretarlos está en el nombre. `N36W005` significa que
la esquina inferior izquierda está a 36° norte y 5° oeste.

Actualmente descargados: **23 tiles de Andalucía** (N36 a N39, W1 a W6).

### OpenStreetMap

Base de datos colaborativa que, además de calles y edificios, contiene cientos
de miles de puntos marcados como cimas, con nombre y altitud oficial. Se
consulta mediante un servicio llamado Overpass.

### La cámara

Todavía no integrada en el flujo automático, pero es la tercera pata: sirve
para corregir hacia dónde apunta realmente el dispositivo.

### El reparto de responsabilidades

| Fuente | Para qué sirve | Para qué NO sirve |
|---|---|---|
| Mapa de alturas | Saber qué terreno hay entre tú y la montaña | Dar la altitud exacta de una cumbre |
| OpenStreetMap | Nombre y altitud oficial de las cimas | Saber si algo está tapado |
| Cámara | Corregir la orientación | Identificar montañas |

Esta separación tiene una razón medida: el mapa de alturas **subestima las
cumbres**, porque promedia cuadrados de 30 metros y la cima real casi nunca
cae en el centro de uno. En La Maroma se midió una diferencia de 3,4 metros.
En agujas estrechas será mayor.

---

## 3. `geo/` — la calculadora geométrica

Cinco funciones, ninguna de más de diez líneas. Es la base de todo.

### Qué hace cada una

**`haversine_m`** — distancia entre dos coordenadas, en metros. No vale el
teorema de Pitágoras porque la Tierra es curva: un grado de longitud son 111
kilómetros en el ecuador pero 89 en Málaga.

**`azimuth_deg`** — la dirección de un punto a otro, en grados de brújula
(norte = 0, este = 90, sur = 180, oeste = 270).

**`destination_point_deg`** — la operación inversa: dada una posición, una
dirección y una distancia, dónde acabas. Es la que hace avanzar el rayo por el
terreno.

**`curvature_drop_m`** — cuánto se ha hundido el suelo por la curvatura del
planeta a una distancia dada.

**`elevation_deg`** — cuánto hay que levantar la vista para ver un punto.

### La corrección de curvatura

Cuando miras muy lejos, el suelo se va cayendo por debajo de ti porque el
planeta es redondo. En sentido contrario, el aire desvía ligeramente la luz
hacia abajo y te deja ver un poco más allá.

```
caída = (1 − 0,13) · distancia² / (2 · 6.371.000)
```

Equivalente práctico: `caída ≈ 0,0683 · km²`

| Distancia | Caída |
|---|---|
| 10 km | 6,8 m |
| 30 km | 61,5 m |
| 60 km | 245,9 m |
| 200 km | 2.731 m |

Sin esta corrección, aparecen cimas que en realidad están ocultas.

### Tres decisiones de diseño

**La curvatura no se puede desactivar.** No hay ningún parámetro para ello.
Podría haberlo, pero entonces alguien lo pondría en "falso" sin darse cuenta y
el error sería invisible. Al no existir la opción, el error es imposible.

**El ángulo de elevación recibe la distancia, no las coordenadas.** Parece un
detalle menor pero es rendimiento puro: en un barrido del horizonte esta
función se llama millones de veces, y la distancia siempre se conoce de
antemano. Recalcularla cada vez sería tirar tiempo.

**Grados fuera, radianes dentro.** Los humanos pensamos en grados; el
ordenador calcula en radianes. Cada función traduce al entrar y al salir, y
los nombres de variable llevan el sufijo (`azimuth_deg`, `lat_rad`) para que
el error se vea al leer el código.

---

## 4. `dem/` — leer el mapa de alturas

Convierte un fichero de bytes en una función que responde "¿a qué altitud está
esta coordenada?".

### Los detalles que importan

**Deducir la resolución por el tamaño.** Un fichero de 25.934.402 bytes es de
30 metros de resolución; uno de 2.884.802 es de 90. Cualquier otro tamaño
significa fichero corrupto, y ahí el programa se detiene en vez de leer
basura.

**Orden de bytes (big-endian).** Cada altura ocupa dos bytes, y hay que leerlos
en el orden correcto. Al revés, 2.065 metros se convierten en 4.360. La
mayoría de ordenadores actuales usan el orden contrario por defecto, así que
hay que especificarlo.

**La inversión de filas.** Lo más contraintuitivo del formato: la primera fila
del fichero es la más al **norte**, pero la latitud crece hacia el norte. Fila
y latitud van en direcciones opuestas. Si se ignora, el mapa sale reflejado
como en un espejo, y sigue pareciendo un mapa normal.

**Interpolación bilineal.** Solo hay datos cada 30 metros. Para un punto
intermedio se toman las cuatro celdas que lo rodean y se hace una media donde
pesa más la más cercana. Su función no es recuperar detalle —no puede—, sino
evitar que la altitud dé saltos bruscos al barrer el horizonte.

**Los huecos.** El radar que midió todo esto desde el espacio no llegó a
todas partes. Los puntos sin dato se marcan con el número −32.768. Si se
tratara como una altitud habría un agujero de 32 kilómetros de profundidad.
El código los detecta y los excluye del promedio.

### Tres decisiones de diseño

**Lectura perezosa (`memmap`).** El fichero se deja en el disco y solo se
traen a memoria los trozos que se consultan. Con veinte tiles abiertos esto es
la diferencia entre 500 MB y unos pocos.

**Caché de tiles abiertos.** Un barrido del horizonte consultaría el mismo
fichero cientos de miles de veces.

**Un tile que falta lanza un error, nunca devuelve cero.** Esta es de las más
importantes. Un cero significaría "aquí hay mar", así que faltar datos crearía
un océano imaginario donde hay montañas, y el horizonte saldría mal sin ningún
aviso.

---

## 5. `horizon/` — qué se ve y qué no

Aquí está el corazón del sistema.

### La regla central

Desde el observador se avanza en línea recta sobre el mapa de alturas, dando
pasos de 30 metros. En cada paso se calcula cuánto hay que levantar la vista
para ver ese punto.

> **Un punto es visible solo si hay que levantar la vista más que para
> cualquier cosa encontrada antes en esa misma dirección.**

Una montaña más alta pero más lejana puede quedar oculta tras una más baja y
cercana. Lo que manda es la relación entre altura y distancia, no la altitud.

### Las dos funciones

**`check_visibility`** — ¿se ve esta cima concreta? Recorre el rayo hasta el
objetivo y se detiene en cuanto encuentra un obstáculo. La altitud del
objetivo se pasa como dato (viene de OpenStreetMap), no se lee del mapa.

**`horizon_profile`** — ¿cuál es la silueta completa? Repite el trazado en
1.800 direcciones, en pasos de 0,2 grados. Aquí no puede haber parada
anticipada: hay que recorrer el rayo entero, porque un pico lejano puede
superar a uno cercano más bajo.

### Los tres estados

El sistema **no devuelve un sí o un no**. Devuelve uno de tres:

- **VISIBLE** — hay línea de visión confirmada
- **BLOCKED** — el terreno lo tapa, con la distancia del obstáculo
- **UNKNOWN** — el rayo salió de la zona con datos descargados antes de poder
  decidir

El tercero es esencial. Con solo tiles de Andalucía, casi todos los rayos
largos se salen de cobertura. Sin ese estado, la falta de datos se disfrazaría
de respuesta válida.

Se distingue además del caso de un hueco puntual dentro de un mapa que sí
existe: ese se salta y se sigue, porque es un fallo local, no ausencia de
datos.

### El margen de cima

Constante `SUMMIT_MARGIN_M = 200`: el rayo deja de buscar obstáculos en los
últimos 200 metros antes del objetivo.

Sin esto se producía un fallo curioso llamado **auto-bloqueo**: la ladera final
del propio pico, que el mapa registra ligeramente por debajo de la cota
oficial pero un poco más cerca del observador, ganaba el ángulo por centésimas
y tapaba su propia cumbre. Medido: Cima de Tejeda salía como no visible por
terreno de 2.068,3 metros situado a 13 metros de la cima oficial de 2.069.

El valor coincide con el radio de recolocación de `peaks/` porque describen lo
mismo: la incertidumbre de posición de una cumbre.

### Rendimiento

1.800 rayos por hasta 5.000 muestras son nueve millones de consultas. Con un
bucle normal tardaría minutos. Se resuelve calculando cada rayo entero de una
vez con operaciones vectorizadas.

**Tiempo real: 3,2 segundos** para el barrido completo.

---

## 6. `peaks/` — ponerle nombre

El barrido produce una silueta anónima. Este módulo le pone nombres.

### El proceso

1. **Consultar OpenStreetMap** — todos los puntos marcados como cima en un
   radio dado. La respuesta se guarda en disco: el servicio es gratuito y
   tiene límite de peticiones.

2. **Recolocar cada cima** — las coordenadas de OpenStreetMap están puestas a
   ojo y pueden desviarse decenas de metros. Se busca el punto más alto del
   mapa de alturas en un radio de 200 metros y se usa ese.

3. **Decidir la altitud** — se prefiere el dato oficial de OpenStreetMap. Si
   no existe, o si discrepa del mapa en más de 100 metros (errores de tecleo,
   unidades en pies), se usa el del mapa. Cada resultado registra de dónde
   salió su altitud.

4. **Comprobar la visibilidad** de cada una individualmente.

5. **Ordenar por dirección**, que es el orden natural para recorrer un
   panorama.

Los resultados marcados como inciertos **no se descartan**: aparecen con su
marca, igual que los sectores dudosos del dibujo.

### Comprobación

Desde Torre del Mar devuelve **61 cimas reales**, todas verificadas.

---

## 7. `render/` — dibujarlo

Genera una imagen del panorama: eje horizontal la dirección, eje vertical la
altura angular, la silueta como área rellena.

### Tres colores, tres significados

- **Azul oscuro** — terreno con datos completos
- **Ámbar** — el rayo se quedó sin cobertura; lo dibujado es un mínimo
  garantizado, puede haber más detrás
- **Gris con trama** — sin datos

La trama existe además del color para que la información no dependa solo de
distinguir colores.

### Etiquetas de cimas

Los nombres se dibujan girados 90 grados. Un nombre en horizontal ocuparía
unos 50 grados de arco y sería imposible; en vertical ocupa unos 4.

Cuando dos etiquetas compiten por el mismo hueco, gana la de **mayor ángulo
aparente** — lo que más ocupa en el cielo, que es lo que domina visualmente.
No la más alta en metros: La Maroma manda por sus 6,4 grados, no por sus 2.069
metros.

Los descartes se contabilizan por separado según su causa: fuera del sector
visible, o perdió la competencia por el hueco. Son cosas distintas y mezclarlas
ocultaría información.

### Recorte de sector

Se puede dibujar solo un tramo, por ejemplo de 340° a 40°. Los ángulos se
"desenrollan" para que el cruce por el norte no parta la silueta en dos.

---

## 8. `peakid/` — la línea de comandos

La capa de presentación. No contiene matemática.

```
python -m peakid panorama --lat L --lon L [--eye M] [--az-min A --az-max B]
python -m peakid peaks    --lat L --lon L [--csv fichero.csv]
python -m peakid visible  --lat L --lon L --target-lat L --target-lon L
python -m peakid align    --lat L --lon L --photo f.jpg [--search]
```

### Errores esperables, sin mensajes de programador

Faltar un mapa, caerse el servicio de OpenStreetMap o pedir coordenadas sin
cobertura **no son fallos del programa**: son situaciones normales de uso.
Salen como un mensaje de una línea con su código de salida propio.

Los mensajes de error de programador se reservan para bugs de verdad.

### Un detalle que costó

Los parámetros van con nombre (`--lat`, `--lon`) y no por posición. Escribir
`peakid panorama 36.745 -4.097` haría que el sistema interpretara `-4.097`
como una opción desconocida. Y en España las longitudes son casi siempre
negativas.

---

## 9. `align/` — encajar con la foto

La fase 2. Está en curso.

### La proyección

Convierte un punto del panorama (dirección + altura angular) en un píxel de la
fotografía, dados cuatro parámetros:

| Parámetro | Qué es |
|---|---|
| **azimut** | hacia dónde apunta el centro de la imagen |
| **campo de visión** | cuántos grados abarca de lado a lado |
| **inclinación** | cuánto está levantada o bajada la cámara |
| **giro** | cuánto está torcida |

La conversión **no es una regla de tres**. Una cámara es una proyección en
perspectiva, así que hacia los bordes de la imagen los grados se comprimen.
Con 65 grados de campo, usar una aproximación lineal erraría unos 150 píxeles
en los extremos de una foto de 4.000.

Esta proyección es la misma que usará la aplicación móvil. No es código de
andamiaje.

### La herramienta de ajuste manual

Una ventana donde se carga la foto, se superpone la silueta calculada, y se
ajustan los parámetros hasta que encajan. Al guardar se escribe un fichero con
la posición, los parámetros, las notas y la procedencia.

**Por qué existe:** cuando el sistema automático diga "el azimut es 25,8
grados", hay que poder comprobar si acierta. La respuesta correcta la produce
una persona. Sin eso no hay forma de medir nada.

Y si el sistema automático nunca llegara a funcionar bien, esta herramienta ya
es la aplicación en versión manual.

#### Lo que la hizo usable

La primera versión era impracticable: cuatro parámetros acoplados y un
deslizador de dirección que recorría 360 grados en mil píxeles, es decir un
grado por píxel de ratón. Imposible ajustar con precisión.

Tres cambios lo resolvieron:

**Acotar el sector.** Un panel superior muestra el panorama completo de 360
grados y el usuario arrastra para marcar el arco aproximado que cubre la foto.
Los deslizadores pasan a moverse solo dentro de ese arco: con 30 grados de
rango, la resolución mejora de 1,09 a 0,06 grados por píxel. La etiqueta del
deslizador muestra la resolución conseguida.

**Inclinación y giro automáticos.** Fijados dirección y campo de visión, los
otros dos se resuelven por cálculo directo, no por búsqueda. El fundamento es
que el desplazamiento vertical entre la línea proyectada y la cresta real es
una recta: su altura da la inclinación y su pendiente el giro. Verificado
contra la proyección, recupera ambos valores con error de centésimas.

Así el usuario maneja dos controles en lugar de cuatro. Se pueden congelar
para retoque manual.

**Etiquetas de cimas sobre la foto.** Cada pico dibuja una guía hasta su píxel
con su nombre y altitud. Es el criterio que de verdad permite juzgar si un
alineamiento es correcto: si las cimas altas caen sobre los bultos altos, está
bien.

#### La tira ampliada

Bajo la imagen principal, una banda alrededor de la línea proyectada estirada
verticalmente. Sirve para el ajuste fino. Las zonas fuera de la fotografía se
marcan con trama, el mismo convenio de "sin dato" que usa el generador de
imágenes.

#### Procedencia

Cada alineamiento registra **cómo se produjo**: si se usó búsqueda automática,
si el ajuste de inclinación y giro fue automático, qué detector se empleó, y
si una persona revisó el resultado.

Esto no es burocracia. Un alineamiento que sale de la búsqueda y se acepta sin
mirar no sirve como referencia para evaluar al detector que lo produjo. La
distinción que importa no es "usó búsqueda" sino "aceptó sin revisar", y se
registra si el usuario corrigió los valores y cuánto.

Un caso ilustra por qué hace falta un campo declarado y no deducirlo: en una
foto la corrección consistió en cambiar de hipótesis entre candidatos
comparando topónimos. No se movió ningún deslizador, pero fue la corrección
más importante del proyecto.

### La búsqueda automática

Extrae la silueta de la fotografía y prueba miles de combinaciones buscando la
que minimiza la distancia contra el panorama calculado.

Detalles medidos:

- La comparación se hace **en píxeles, no en grados**. En grados, un campo de
  visión estrecho gana siempre por construcción, porque la misma discrepancia
  se encoge al dividir por una distancia focal mayor.
- La **inclinación y el giro no se exploran**: se resuelven por cálculo
  directo. Eso elimina dos dimensiones enteras de la rejilla, y es lo que hizo
  viable el barrido completo de 360 grados.
- La rejilla del campo de visión es **geométrica**, no lineal: un paso de 2,5
  grados vale un 3% a 75 grados pero un 17% a 15, justo donde más precisión
  hace falta.
- Los límites de inclinación y giro son **físicos**: ±30 y ±15 grados. El de
  inclinación estaba en 10 y era demasiado estrecho — fotografiar una cima de
  2.500 metros desde un valle a 9 kilómetros exige mirar 14,5 grados hacia
  arriba. El giro sin acotar era un grado de libertad falso con el que el
  ajuste se retorcía hasta encajar ruido.
- **Tiempo:** 3,8 segundos el barrido completo, 0,8 con pistas.

Sin pista de dirección, la búsqueda barre los 360 grados. Antes se centraba en
una semilla de cero que no significaba nada y exploraba ±20 grados, así que la
respuesta correcta casi nunca estaba dentro.

Se devuelven hasta cinco candidatos **separados al menos 10 grados entre sí**,
no los cinco mejores absolutos, que suelen ser variaciones del mismo. El
usuario salta entre ellos y compara por topónimos.

### El hallazgo más importante de la fase 2

El sistema **detecta cuándo su propia respuesta no es fiable**: compara la
puntuación del mejor candidato con la del mejor candidato lejano. Si están
empatados, el resultado es ambiguo y lo dice.

Y al medirlo apareció algo incómodo: **incluso la fotografía que encajaba bien
resulta ambigua** cuando se busca en los 360 grados completos. El margen es de
0,02 frente a un umbral de 0,25. Acotando la búsqueda a una zona razonable
sube a 0,73.

La conclusión reencuadra la fase 2: **el sistema no puede encontrar la
orientación desde cero, y no hace falta que lo haga**. Su trabajo es corregir
una orientación aproximada. La brújula, aunque falle por quince grados, acota
el espacio de sobra.

### El límite de la métrica

Un caso real lo demostró. Tres alineamientos de la misma foto, separados 25
grados entre sí, con errores de 29,1, 14,2 y 9,5 píxeles. El de menor error
—el que la métrica prefería— colocaba un cerro de 708 metros sobre el macizo
dominante y dejaba fuera del encuadre una cima de 2.069 metros que la foto
muestra con claridad.

**El error en píxeles ordena candidatos dentro de una hipótesis, no entre
hipótesis.** Para elegir entre hipótesis hacen falta los topónimos, que es
información que la búsqueda no usa.

Y la comprobación de ambigüedad no protege de esto: contrasta el mejor
candidato contra un alternativo lejano concreto, y no cubre el caso de un
continuo de óptimos parecidos.

---

---

## 10. `skyline/` y `segmentation/` — encontrar la cresta

Este es el trabajo de la fase 2, y donde entra la inteligencia artificial.

### El problema

La proyección compara dos cosas: el panorama calculado y la silueta de la
fotografía. Para obtener la segunda hay que responder, columna a columna, a
qué altura está la montaña. Y eso equivale a encontrar **dónde acaba el cielo
y empieza el terreno**.

Con cielo despejado es fácil. Con nubes, no. Una nube es tan "no-cielo" como
una montaña bajo un criterio de color, y además su borde suele tener más
contraste que una cresta lejana lavada por la calima.

Medido sobre una fotografía con cielo cubierto: el detector se enganchaba a
los bordes de las nubes, solo tocaba la loma real en una parte de la imagen, y
llegaba a picar en los árboles del fondo del valle. Dispersión de las filas
detectadas: **1.070 píxeles**.

### Intento 1: camino de coste mínimo (descartado)

La primera hipótesis fue que el problema era la **discontinuidad**: el
detector decide cada columna por separado, así que puede saltar de la cresta a
una nube y volver sin pagar nada.

La solución teórica es tratar la silueta como un camino continuo desde la
primera columna hasta la última, y buscar el de menor coste con una
penalización por saltar entre columnas vecinas.

**Se implementó, se midió, y empeoraba las cinco fotografías de referencia:**
residuos de 368 a 1.970 píxeles frente a los 1,5 a 31 del detector original.

El diagnóstico fue que la hipótesis era incorrecta. El problema no era la
discontinuidad sino **la evidencia**. Forzar un camino continuo sobre una
evidencia que no distingue nube de montaña produce una línea suave y
equivocada, y eso es peor que un desastre visiblemente disperso: una línea
suave resulta creíble.

El código se conserva, pero rechaza explícitamente ejecutarse sobre evidencia
de color, con el resultado medido documentado para que nadie lo reactive.

### Intento 2: modelo de segmentación (funciona)

Un modelo entrenado sí sabe qué es una nube. Se usa **SegFormer-B0** ajustado
sobre el conjunto ADE20K, que tiene una clase específica para el cielo.

Decisiones de integración:

**Sin PyTorch.** El modelo se ejecuta con ONNX Runtime, que ocupa 14 megas en
Windows frente a los 122 de PyTorch (526 en Linux). Ya existía una versión
exportada públicamente, así que ni siquiera hizo falta un entorno temporal
para convertirlo. El fichero del modelo pesa 15 megas.

**Dependencia opcional.** Si falta la biblioteca o el fichero, el sistema
recurre al detector anterior y lo avisa una vez por máquina. Quien no la tenga
sigue teniendo una herramienta que funciona.

**La resolución importaba más de lo esperado.** El primer intento daba 8,4
píxeles de residuo, y la causa no era que el modelo situara mal la cresta sino
que no podía resolverla: produce su salida a un cuarto de la resolución de
entrada, así que a 512 píxeles cada paso equivalía a 32 píxeles de fotografía.
Subiendo la entrada a 1.536 el residuo bajó a 2,0. A 2.048 no mejora y triplica
el tiempo.

### El camino de coste mínimo, ahora sí

La técnica descartada vuelve, pero **encima del modelo** en lugar de sobre
evidencia de color. Y con un añadido: el modelo dice de qué zona es la
frontera, y el gradiente de la imagen a resolución completa dice qué fila
exacta, dentro de una banda estrecha.

Esa banda es esencial. El borde de una nube es tan fuerte como el de una
cresta, así que sin acotar la búsqueda a la zona que el modelo señala, el
término de borde reintroduce exactamente el fallo que el modelo vino a
resolver.

### Resultado

Sobre la fotografía con nubes que motivó todo:

| | dispersión | cobertura |
|---|---|---|
| detector original | 1.070 px | 78% |
| modelo + camino mínimo | **232 px** | **100%** |

Y en las cinco fotografías que ya funcionaban, el error de dirección es
indistinguible: entre 0,00 y 0,25 grados, por debajo del paso de 0,2 grados
con que se calcula el panorama.

El detector por defecto es ahora **modelo + camino mínimo**, con respaldo
automático al original.

---

## 11. El arnés de evaluación

Un programa aparte que recorre las fotografías de referencia y compara los
detectores. No está entre las pruebas automáticas porque depende de imágenes
que no se versionan.

Reporta tres cosas **por fotografía, sin promediar**:

1. **Residuo directo** — con los parámetros del alineamiento manual fijos,
   cuánto se separa la cresta detectada de la línea proyectada.
2. **Error de dirección** — cuánto se desvía el resultado de la búsqueda
   automática respecto al alineamiento manual.
3. **Robustez** — qué fracción de columnas se desvía más de tres veces la
   mediana. Mide "se ha ido a las nubes".

No promedia porque con cinco o seis casos una media escondería que una empeora
mucho y otra mejora mucho.

### Por qué el arnés es la pieza más valiosa de la fase 2

Sin él, el camino de coste mínimo habría entrado en producción. Sobre la
fotografía de las nubes producía una línea visiblemente más limpia que antes,
y mirándola parecía una mejora. Los números dijeron que empeoraba las cinco
restantes.

### El problema de las referencias

Al medir apareció algo que invalidaba el criterio de aceptación: las cinco
referencias **se generaron con la búsqueda automática**, que usa el detector
original. Comparar contra ellas premia parecerse a ese detector, no acertar.

Consecuencias registradas:

- Un residuo del orden de un píxel en contra de otro detector **no es
  evidencia de que sea peor**.
- Lo comparable hoy es el error de dirección, la cobertura y la dispersión.
- Cuando existan referencias producidas sin búsqueda, el residuo volverá a ser
  una métrica válida.

El arnés imprime este aviso junto al veredicto, y desaparecerá solo cuando
haya una referencia limpia.

### La regla dura sobre la procedencia

Se intentó deducir automáticamente si una referencia estaba contaminada,
comparando los valores finales con los de partida. **Falló en dos de cinco**:
en una, la semilla venía de la búsqueda pero el usuario retocó después; en
otra, la búsqueda se lanzó desde la ventana y los valores de partida guardados
eran los previos.

De ahí la regla: **sin campo de procedencia declarado, no cuenta como
revisada**. "No hay pruebas de que esté contaminada" no equivale a "consta que
está limpia".

---

## 12. Cómo sabemos que funciona

**78 pruebas automáticas**, con valores conocidos de antemano y prohibición
explícita de ajustarlos para que pasen. Los siete casos del contrato inicial
están verificados; ninguno queda pendiente.

### Solución matemática exacta

Desde 10 metros sobre el nivel del mar, el punto más alto del horizonte marino
tiene solución cerrada: −0,094688 grados a 12.102 metros. El sistema devuelve
el mismo valor **hasta el sexto decimal**, por un camino de cálculo
completamente distinto.

### Validación contra una implementación independiente

Se extrajeron 72 cimas de un panorama generado por PeakFinder, con nombre,
coordenadas, altitud, distancia y dirección.

- Dirección: peor discrepancia **0,085 grados** frente a un umbral de 0,5
- Silueta: alcanza la altura de las 72 cimas
- **0 discrepancias sobre 72**

Es la única prueba capaz de detectar un error de criterio compartido por todos
los módulos internos.

### Comprobación de que las pruebas sirven

Desplazando el observador 500 metros fallan 59 de 72; a 2 kilómetros, 71;
intercambiando latitud y longitud, las 72. Una prueba que pasa siempre no vale
nada.

### Confirmación cruzada

Buscando el punto más alto alrededor de las coordenadas de La Maroma según
Wikipedia, el sistema lo localizó desplazado unos 170 metros, con 2.065,6
metros. La base de datos de PeakFinder registra ahí la cima *Mojón de Tres
Términos*, con 2.065 metros.

Coincidencia de unos 3 metros en posición y 60 centímetros en altitud, hallada
sin ninguna referencia externa.

---

## 13. Los bugs que aparecieron y qué enseñan

Todos comparten el mismo patrón: **producían resultados creíbles**.

| Bug | Qué pasaba | Cómo se detectó |
|---|---|---|
| Truncamiento en vez de redondeo hacia abajo | Abría el mapa del cuadrado de al lado, con altitudes perfectamente verosímiles | Prueba con coordenada de longitud oeste |
| Auto-bloqueo | Las cumbres se tapaban a sí mismas | La prueba contra PeakFinder |
| Caché indexada por nombre de fichero | Dos directorios con el mismo nombre de mapa colisionaban | Aparecería con mapas de prueba |
| Búsqueda fuera del espacio explorado | Devolvía un campo de visión mayor que el máximo permitido | Al medir contra una foto real |
| Métrica en grados | Favorecía sistemáticamente los campos de visión estrechos | Al comparar candidatos |
| Coordenadas GPS inválidas | El EXIF vacío producía valores no numéricos que entraban al motor sin avisar | Al leer los metadatos de fotos propias |
| Límite de inclinación demasiado estrecho | Imposible ajustar una foto tomada desde un valle hacia una cima alta | Al probar con los Picos de Europa |
| **Recorte de la métrica de error** | Cada residuo se limitaba a 40 píxeles, así que un desajuste de 350 daba el mismo número que uno de 45 | Al medir el residuo real por columna |

Ese último merece atención aparte: **hacía indistinguible un ajuste mediocre de
uno catastrófico**. Un error cercano al tope no significaba "casi bueno" sino
"sin medir". Contaminó al menos un informe intermedio, y la corrección fue
añadir un indicador de qué fracción de columnas está saturada.

### Y dos falsas alarmas

**La orientación de la fotografía.** Se sospechó que la tira ampliada leía los
píxeles sin girar. No era cierto: la deformación vertical de la tira es
deliberada, y a esa proporción el paisaje se vuelve irreconocible.

**Los nombres que faltaban.** El Naranjo de Bulnes no aparecía en la lista de
cimas pese a dominar la vista. No faltaba en OpenStreetMap: está registrado con
su nombre asturiano, *Picu Urriellu*, y el castellano en un campo secundario.
Ahora se muestran ambos, lo que importa en toda la España con lengua propia.

### Y una hipótesis que se midió y era falsa

**La orientación de la fotografía.** Se sospechó que la tira ampliada leía los
píxeles sin girar. No era cierto: la deformación vertical de la tira es
deliberada, y a esa proporción el paisaje se vuelve irreconocible.

**El fallo con Sierra Nevada.** La búsqueda automática se fue 105 grados de su
sitio. Resultó que faltaba el mapa de esa zona y además el espacio de búsqueda
no incluía la respuesta correcta. No probó nada de lo que se creía.

De ahí salió una mejora: el programa ahora **imprime siempre el espacio que ha
explorado**, para que un fallo así sea visible sin adivinarlo.

**El camino de coste mínimo sobre color.** La hipótesis era que el detector
fallaba con nubes por decidir cada columna aisladamente. Se implementó, se
midió, y empeoraba las cinco fotografías de referencia. El problema no era la
discontinuidad sino la evidencia. Descartarlo costó una tarde y evitó meter en
producción algo que parecía razonable.

---

## 14. Lo que falta

### Inmediato — referencias limpias

Alinear varias fotografías **sin usar la búsqueda automática**, con el ajuste
de inclinación y giro desactivado. Es lo único que falta para que el arnés
pueda decidir con una métrica no sesgada.

```
python -m peakid align --photo FOTO --lat LAT --lon LON --no-auto-pitch-roll
```

Sin la opción de búsqueda y sin lanzarla desde la ventana.

Con una sola referencia limpia el arnés recupera el criterio; con tres o
cuatro, la comparación entre detectores vuelve a ser fiable a escala de píxel.

### Corto plazo — ampliar el conjunto

- Reunir entre diez y veinte fotografías con condiciones variadas
- Criterios que funcionan, aprendidos midiendo: **horizontal** mejor que
  vertical, campo de visión amplio, varias cimas distinguibles a lo ancho,
  cresta despejada sin vallas ni postes, y sin recortar
- Casos que fallan: una sola forma dominante, teleobjetivo, obstáculos anchos
  en primer plano, y posición de disparo desconocida

### Medio plazo — mejoras del detector

- Evaluar si el modelo actual basta o conviene uno ajustado sobre un conjunto
  específico de montaña
- Objetivo de rendimiento para móvil: por debajo de 30 milisegundos
- Cuantización del modelo

### Largo plazo — la aplicación

- Motor portado a código nativo para iOS y Android
- Descarga de datos por regiones
- Seguimiento de orientación con los sensores del teléfono
- Contenido informativo de cada cima

### Anotado para más adelante

**Mapas de mayor resolución.** El modelo actual de 30 metros no reproduce
agujas ni torres estrechas. Se comprobó con una fotografía de los Picos de
Europa: la línea calculada pasa por la base de las agujas, no por sus puntas.

Es una limitación conocida y con solución disponible: los Dolomitas publican
datos de 1 a 2 metros, y el instituto geográfico español publica datos
similares para toda la península. El inconveniente es que no siguen el formato
actual de cuadrados de un grado.

Conviene distinguir dos cosas: **colocar bien la etiqueta** funciona con 30
metros, porque la dirección de la cima es correcta aunque el relieve esté
suavizado. **Reproducir la silueta fina** no.
