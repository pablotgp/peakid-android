# PeakID — Documento funcional de negocio

**Versión 0.2 · agosto de 2026**
Fase 1 completada · Fase 2 en curso

---

## 1. El problema

Estás en la montaña, ves un pico impresionante, y no tienes forma cómoda de
saber cuál es. Los mapas exigen orientarse mentalmente: localizar dónde estás,
adivinar hacia dónde miras, y traducir curvas de nivel a lo que tienes
delante. La mayoría de la gente no lo hace, y se queda sin saberlo.

La idea nace de un viaje a los Dolomitas donde ese problema apareció
constantemente.

## 2. La solución

Una aplicación móvil que **apuntas hacia una montaña y te dice cuál es**, con
el nombre superpuesto sobre la imagen de la cámara, en tiempo real.

Además del nombre, información sobre esa cima: altitud, distancia, geología,
historia de sus ascensiones, rutas de subida.

### Cómo funciona, en una frase

La aplicación **no adivina** qué montaña estás mirando. Sabe dónde estás por
el GPS, calcula qué montañas deberían verse desde ese punto exacto, y coloca
las etiquetas donde corresponde.

Es un cálculo, no un reconocimiento. Eso significa que no se inventa
respuestas: o sabe la respuesta, o dice que no puede saberla.

## 3. Quién lo usaría

- **Senderistas y montañeros** que quieren identificar el paisaje sin
  conocerlo de antemano
- **Viajeros** en zonas de montaña
- **Curiosos** que ven una sierra desde su ventana y quieren saber qué es

## 4. Los tres principios de producto

### Funciona sin cobertura

En montaña rara vez hay datos móviles, y es justo donde se necesita la app.
Todo el cálculo ocurre en el teléfono, con datos descargados previamente por
regiones.

### Nunca miente

Un nombre equivocado es peor que ningún nombre. Cuando el sistema no puede
determinar algo con seguridad —porque faltan datos, porque hay niebla, o
porque la vista es ambigua— **lo dice** en lugar de dar una respuesta
plausible.

Esta decisión atraviesa todo el diseño: hay un estado explícito de "no lo sé"
en cada nivel del sistema, y se propaga hasta lo que ve el usuario.

### Responde al instante

La cámara no puede esperar. El cálculo debe ir al ritmo del movimiento del
teléfono.

## 5. El reto técnico principal

Hay una dificultad que define el proyecto: **la brújula del móvil no es
fiable**.

Se desvía entre 5 y 20 grados por interferencias magnéticas, calibración y la
diferencia entre el norte magnético y el geográfico. Y peor: las lecturas
sucesivas hacia el mismo punto no se repiten. Se comprobó en campo durante el
desarrollo.

A veinte kilómetros de distancia, diez grados de error son tres kilómetros y
medio de desplazamiento lateral. Suficiente para poner el nombre sobre la
montaña equivocada.

**La solución** es usar la propia imagen de la cámara: comparar la silueta de
las montañas que se ve con la silueta que el sistema ha calculado, y deducir
de ahí hacia dónde apunta realmente el teléfono. Es la única parte del
proyecto donde interviene inteligencia artificial, y su papel es acotado:
distinguir en la foto dónde acaba la montaña y empieza el cielo.

## 6. Estado actual

### Completado — el motor de cálculo

Dado cualquier punto con datos descargados, el sistema:

- determina qué montañas se ven y cuáles quedan tapadas
- calcula exactamente en qué dirección y a qué altura aparece cada una
- obtiene sus nombres y altitudes oficiales
- genera una imagen del panorama con las cimas etiquetadas

Funciona por línea de comandos. Un panorama completo tarda unos tres segundos.

### Validación

El motor se ha contrastado con **PeakFinder**, una aplicación comercial
existente, sobre 72 cimas de la zona de pruebas: **coincidencia total**, con
una desviación máxima de 0,085 grados.

También se ha comprobado contra soluciones matemáticas exactas, coincidiendo
hasta el sexto decimal.

Un dato ilustrativo: buscando el punto más alto alrededor de las coordenadas
de La Maroma, el sistema localizó una cumbre que las enciclopedias no
registraban en esa posición. Resultó ser una cima con nombre propio —Mojón de
Tres Términos— situada exactamente allí, con una diferencia de tres metros
respecto a la base de datos oficial.

### En curso — el alineamiento con la cámara

Existe una herramienta de escritorio donde se carga una fotografía y se ajusta
manualmente hasta que la silueta calculada encaja con la real. Funciona, y ha
producido el primer alineamiento válido.

Hay también una búsqueda automática que propone el ajuste. Acierta en
condiciones favorables y falla en las difíciles, de forma ya caracterizada.

## 7. Lo aprendido durante el desarrollo

Tres hallazgos que condicionan el producto:

**El campo de visión importa mucho.** Con una foto de ángulo estrecho
—teleobjetivo— la silueta no tiene suficientes rasgos distintivos y podría
encajar en muchos puntos del horizonte. La aplicación debe favorecer el
encuadre horizontal y advertir cuando la vista sea poco concluyente.

**El alineamiento necesita un punto de partida.** No es viable buscar a ciegas
en los 360 grados. Pero esto no es un problema: la brújula, aunque falle por
quince grados, acota el espacio de búsqueda de sobra. El sistema no tiene que
encontrar la orientación desde cero, solo corregir una aproximación.

**Los objetos en primer plano estorban.** Pueblos, edificios y arbolado justo
bajo la línea de cumbre tienen más contraste que la propia montaña,
especialmente con calima. Es el caso difícil que justifica usar un modelo
entrenado en lugar de un filtro convencional.

## 8. Plan

| Fase | Contenido | Estado |
|---|---|---|
| 1 | Motor de cálculo geométrico | **Completada** |
| 2 | Alineamiento con la cámara | En curso |
| 3 | Aplicación móvil | Pendiente |
| 4 | Contenido informativo de cada cima | Pendiente |

### Fase 2 — lo que queda

- Reunir un conjunto de fotografías alineadas manualmente, que servirá para
  medir la calidad del sistema automático
- Entrenar el modelo que separa cielo de montaña en la imagen
- Integrarlo con la búsqueda automática

Las etiquetas de entrenamiento se obtienen sin trabajo manual: existen
millones de fotografías públicas con coordenadas, y para cada una el sistema
puede generar la silueta correcta a partir de su posición.

### Fase 3 — la aplicación

Motor portado a código nativo para iOS y Android, descarga de datos por
regiones, y seguimiento de la orientación con los sensores del teléfono.

## 9. Riesgos

| Riesgo | Impacto | Cómo se mitiga |
|---|---|---|
| El alineamiento automático no alcanza la precisión necesaria | Alto | Ajuste manual como alternativa: es lo que hacen las apps existentes y funciona |
| Los datos de elevación no capturan agujas y torres estrechas | Medio | Datos de mayor detalle donde existan; las altitudes vienen de otra fuente |
| Rendimiento insuficiente en móviles antiguos | Medio | Optimización del modelo y menor frecuencia de cálculo |

**El riesgo principal está acotado.** Aunque el alineamiento automático nunca
funcionara, la aplicación con ajuste manual sigue siendo útil y equivalente a
las alternativas del mercado. La inteligencia artificial mejora la
experiencia; no condiciona la viabilidad del producto.

## 10. Diferenciación

Existen aplicaciones parecidas —PeakFinder, PeakVisor—. Dos huecos
identificados:

**La experiencia de calibración.** Todas exigen ajustar manualmente y lo
resuelven con interfaces incómodas. Un alineamiento automático fiable sería
una diferencia notable.

**El contenido.** Actualmente se limitan al nombre y la altitud. Hay espacio
para información contextual real —geología, historia, rutas— y para poder
preguntar en lenguaje natural sobre lo que se está viendo.
