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

### El test de simetría no vigila el convenio de `atan2`

De los siete casos dorados, **solo el caso 2 vigila el orden de los argumentos
de `atan2`**, y lo hace por su valor concreto de 336°. Comprobado con
mutaciones:

- El **caso 3 (simetría)** pasa igual de verde con el convenio girado:
  intercambiar los argumentos refleja el azimut al convenio matemático y la
  propiedad ida/vuelta = 180° se conserva bajo esa reflexión.
- El **caso 4 (sentido)** tampoco lo caza: solo ejercita
  `destination_point_deg` y nunca llama a `azimuth_deg`.

Por eso el puerto añade **ida y vuelta** como segundo guardián independiente
(`caso4_idaYVueltaRecuperaElPuntoDePartida`): avanzar a un rumbo conocido con
`destinationPointDeg` y comprobar que `azimuthDeg` lo recupera. Cruza las dos
funciones y caza tanto el `atan2` cambiado como el `%` de arriba.

Al portar un módulo nuevo, la pregunta no es "¿pasan los tests?" sino **"¿qué
mutación tendría que hacer para que fallaran?"**. Si no hay ninguna, el test no
mide nada.