# PeakID Android

App que identifica montañas sobre una fotografía. Primera versión: el usuario
elige o hace una foto, la app la alinea y superpone los nombres de las cimas.

## Relación con el motor

El motor geométrico está en el repo `peakid` (Python), validado contra 72
cimas de una implementación independiente. Aquí NO se reimplementa desde cero:
se porta lo necesario y se reutilizan sus convenciones.

Las convenciones de `docs/peakid-motor-CLAUDE.md` son vinculantes: mismo
convenio de azimut, misma corrección de curvatura, mismos criterios de
diagnóstico. Un cambio de convenio entre motor y app sería el peor bug posible.

## Alcance de la primera versión

- Foto, no vídeo en vivo. Pero la arquitectura debe dejar sitio al vídeo.
- Datos precalculados y descargados por región. Sin conexión en uso.
- Android primero; el código compartible debe poder reutilizarse en iOS.

## No negociable

- Funciona sin cobertura
- Nunca inventa: si el alineamiento no es fiable, lo dice y ofrece ajuste manual
- El detector de silueta es el modelo SegFormer-B0 ADE20K, ya validado

## Trampas del puerto (medidas, no teóricas)

### El operador `%` no significa lo mismo en Python y en Kotlin

En Python el resto toma el signo del DIVISOR: `-10.0 % 360.0` es `350.0`. En
Kotlin toma el signo del DIVIDENDO: `-10.0 % 360.0` es `-10.0`. Lo mismo en C,
C++, Java, Swift, JavaScript y Rust.

El motor escribe `az % 360.0` al normalizar un azimut y
`(x + 180) % 360 - 180` en `destination_point_deg` y en `project_profile`.
**Traducir eso literalmente produce azimuts negativos en todo el cuadrante
noroeste**, y no fallan de forma visible: las cuentas intermedias siguen
saliendo y el resultado parece un ángulo. El caso dorado 2 está a 336°, que por
esa vía sale −24°.

Regla: **ningún punto del motor portado usa `% 360.0` ni
`(x + 180) % 360 - 180` directamente.** Siempre `normalizeAzimuthDeg` y
`wrapDeltaDeg` de `peakid.engine.geo`.

### El caso 3 no vigila el convenio de `atan2`, y el caso 4 solo a medias

Comprobado con mutaciones en los dos lenguajes, no deducido.

- El **caso 3 (simetría)** pasa igual de verde con el convenio girado:
  intercambiar los argumentos de `atan2` refleja el azimut al convenio
  matemático y la propiedad ida/vuelta = 180° se conserva bajo esa reflexión.
- El **caso 4 tiene dos lecturas y hacen falta las dos.** La del azimut
  (rumbos cardinales: norte da 0°, este da 90°) sí caza la mutación. La de
  `destination_point` (avanzar con azimut 0° sube la latitud) NO la caza,
  porque nunca llama a `azimuth_deg`.

**Esto ya se cobró una pieza.** El primer puerto de `geo` implementó el caso 4
solo en su lectura de `destination_point`, siguiendo el enunciado del contrato
al pie de la letra, y perdió un guardián sin que nada avisara: 15 tests en
verde y el caso figurando como cubierto. El enunciado sugiere esa mitad; los
tests de Python tenían las dos. De ahí la regla:

> **Al portar, portar contra los TESTS, no contra el enunciado del contrato.**
> Y comprobar con una mutación que el caso portado caza lo mismo que cazaba el
> original.

Hoy el convenio tiene tres guardianes en Kotlin (`caso2_azimut`,
`caso4_sentidoDelAzimutEnRumbosCardinales`, `caso4_idaYVueltaRecuperaElPuntoDePartida`),
los mismos que en Python.

Al portar un módulo nuevo, la pregunta no es "¿pasan los tests?" sino **"¿qué
mutación tendría que hacer para que fallaran?"**. Si no hay ninguna, el test no
mide nada.

### Al portar, CONTAR los tests del original

Ampliación de la regla anterior, y la segunda vez que el mismo patrón se cobra
una pieza. No basta con portar contra los tests en vez de contra el enunciado:
hay que **contar cuántos tests cubren cada caso**, porque traducir los que uno
identifica deja fuera los que no.

**Contra el panorama de PeakFinder hay TRES tests, no dos.** Se portaron los dos
evidentes —azimuts y "el perfil alcanza cada cima"— y quedó fuera
`test_peakfinder_visibilidad`, que recorre el rayo hasta cada cima con
`check_visibility`. Ese tercero es **el único guardián de `SUMMIT_MARGIN_M` en
todo el proyecto.**

Medido: sin él, quitar el margen de cima no rompía nada — 50 tests en verde con
el auto-bloqueo reintroducido. Con él, la misma mutación tumba 16 de las 72
cimas, tres de ellas dominantes.

Las dos preguntas se parecen y NO son la misma:

- «¿el horizonte llega a la altura de esta cima?» es una propiedad del
  **barrido**: mira el máximo del perfil en un azimut.
- «¿esta cima se ve?» **recorre el rayo hasta ella**, y ahí vive el auto-bloqueo
  de la ladera del propio pico.

Un caso del contrato puede necesitar varios tests porque tiene varias lecturas
—ya pasó con el caso 4— o porque valida etapas distintas del mismo camino, como
aquí. Antes de dar un módulo por portado: contar.

### Un guardián sobre datos uniformes no mide nada

El test del sector que cruza el norte se escribió sobre un paquete sintético de
**mar llano** y comparaba la elevación devuelta contra la de la muestra
esperada. Como todas las elevaciones valían lo mismo, la comparación se cumplía
**eligiera la muestra que eligiera**: parecía vigilar el cruce del norte y no
vigilaba nada. Lo delató una mutación (`%` a pelo en la distancia angular) que
ese test dejó pasar y que solo cazaron las 72 cimas, sobre terreno real.

Regla: **un test sobre datos constantes no distingue el acierto del azar.** Si
el dato de prueba es liso, lo que hay que arreglar es el fixture. El sustituto
usa un perfil fabricado con una elevación distinta por muestra y comprueba el
ÍNDICE elegido, no un valor que coincidiría igualmente. Mismo criterio que el
patrón `(fila*7 + columna*13)` de los tiles sintéticos del motor, y por el mismo
motivo.

### Sustituir un dato de prueba puede desactivar la métrica que lo usa

Los cuatro tests de búsqueda del motor extraen la cresta con el detector
heurístico, que aquí no se porta. Se sustituyó por la línea proyectada, que es
más limpia — y **rompió el test de ambigüedad sin tocar la búsqueda**.

El motivo: con una cresta exacta el error del mejor candidato sale **cero**, y
el margen de ambigüedad es RELATIVO, `(alternativa − mejor) / mejor`. Cero en el
denominador da infinito, la búsqueda nunca puede declararse ambigua y el test
pasa a medir nada. En Python no ocurre porque ningún detector devuelve filas
fraccionarias: la cresta viene cuantizada a píxeles enteros y el error nunca
es 0.

La corrección es cuantizar la cresta sintética, que es la cantidad mínima de
realismo que hace significativa la métrica. Regla: **al sustituir un dato de
prueba por otro "mejor", comprobar qué propiedades del original usaba la
métrica.** Una entrada más limpia que la real puede degenerar el criterio en vez
de afinarlo.

### Los bordes del campo no distinguen pinhole de lineal

`az ± hfov/2` cae en `x = W` y `x = 0` con las DOS proyecciones, por
construcción. El test de centro y bordes —el que uno escribe primero— no puede
cazar una regresión a la aproximación lineal.

Hace falta un punto INTERMEDIO: a media distancia del borde las dos divergen de
forma medible (con 65° de campo, ~4% del ancho, unos 150 px en una foto de
4000). El motor lo tiene documentado en un comentario pero no como test; el
puerto añade `laProyeccionNoEsLineal`, que es el único que caza esa mutación.

### Otras dos, menores pero medidas

- **El paquete es LITTLE-endian; los `.hgt` son BIG-endian.** Conviven las dos
  convenciones en el proyecto. Leer el paquete al revés no revienta: 2065 sale
  como 4360, que sigue pareciendo una altitud. El `dtype` se lee del manifest y
  no se supone.
- **Charset por defecto de la plataforma.** En JDK 17 sobre Windows en español
  el defecto es windows-1252, y `bufferedReader()` convertía "Mojón de tres
  Términos" en "MojÃ³n". Los nombres de cima son claves de búsqueda en los
  tests, así que toda lectura de texto va con UTF-8 explícito.