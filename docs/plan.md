# Cuaderno: plan del proyecto

Escrito el 2026-11-15 por Claude (Fable 5.2) a pedido de Juanma, para que otro modelo
(probablemente Opus, en Claude Code en la PC) lo construya en muchas sesiones. Está en
español porque Juanma lo tiene que poder leer y aprobar; el código, los comentarios y los
commits van en inglés como en el resto de sus proyectos.

**Cómo leer esto si sos el modelo que lo va a construir:** este archivo es la premisa y
el orden. No es una especificación cerrada. Lo marcado **(origen: Claude)** es criterio
mío sin confirmar por Juanma; lo marcado **(origen: Juanma)** lo dijo él. Antes de cada
fase entrá en modo plan, mostrale el diseño en imágenes y esperá su OK.

## 1. Premisa

Una app propia para tomar apuntes a mano en la tablet (Galaxy Tab S10 FE con S-Pen), que
reemplace a Samsung Notes en el uso diario de la facultad, hecha a su gusto y con sus
datos en archivos suyos.

**Qué es:** cuadernos con páginas donde se escribe y dibuja con el lápiz, se anotan PDFs
y todo se puede exportar. **Qué no es (límite escrito):** no es un editor de texto, no
es un gestor de tareas, no es el cerebro (el cerebro guarda lo que importa; Cuaderno es
donde se escribe) y no lee libros (eso es Atril).

Es **un proyecto aparte y una app aparte** (origen: Juanma). La vinculación con los demás
dispositivos y con el resto del ecosistema se ve **recién cuando ande** (origen: Juanma):
ver la fase 6, que no se empieza antes.

## Prioridad número uno: cómo se siente escribir

**La sensación de escritura va antes que todo lo demás** (origen: Juanma, 2026-11-15):
"necesito optimizar todo para lograr el mínimo delay posible y que se sienta cómodo".

Qué significa para quien construye (origen: Claude):

- **Es el criterio que desempata.** Si una función nueva agrega retraso al trazo o lo
  vuelve menos cómodo, no entra hasta que deje de hacerlo. Ninguna fase se da por buena
  si escribir quedó peor que en la anterior.
- **Primero el trazo, después la app.** Las fases 0 y 1 son sobre todo esto: buffer
  frontal, predicción del movimiento, todas las muestras del lápiz sin esperar al cuadro,
  nada de trabajo pesado en el hilo que dibuja (ni guardar, ni recalcular miniaturas).
- **Cómodo es más que rápido:** que el grosor responda a la presión sin saltos, que la
  línea no tiemble ni se redondee de más, que la palma nunca manche, que el botón del
  lápiz borre al instante, que el zoom con dos dedos no deje marcas.
- **Se mide, además de sentirse.** Juanma juzga con la tablet contra Samsung Notes; al
  lado va un número repetible (retraso del trazo con la grabación de trazos reales, y
  video en cámara lenta del lápiz contra la tinta cuando haga falta) para saber si un
  cambio mejoró o empeoró sin depender de la memoria.
- **Se cuida en cada cambio.** Un test de rendimiento del trazo corre con los demás y
  avisa si un PR lo empeora.

## 2. Por qué ahora

- 2026-11-15 a la mañana: recomendé no clonar Samsung Notes y probar primero el trazo
  propio con una pizarra dentro de Enlace (origen: Claude).
- 2026-11-15 a la noche: la pizarra salió en Enlace v13 y Juanma la probó en la tablet:
  "anda muy bien, el delay yo no lo noto, lo siento como si estuviese usando Samsung
  Notes" (origen: Juanma). Con eso decidió hacer la app, sabiendo que son meses de
  trabajo.
- El código de esa pizarra está en `~/projects/enlace/app/src/main/java/com/enlace/app/modules/board/`
  (`BoardView.kt`, 280 líneas): trazo con presión, rechazo de palma, goma por trazo,
  deshacer. Es el punto de partida y la vara de comparación, no la base definitiva.

**Lo que esa prueba demuestra y lo que no** (origen: Claude). Demuestra que el trazo
simple se siente bien en ese equipo. No demuestra nada de lo difícil: páginas con miles
de trazos, zoom y desplazamiento fluidos, PDFs de 200 páginas, selección con lazo, y
sobre todo **no perder nunca un apunte**. Una pizarra es más o menos el 5 % de una app de
notas. El plan está ordenado para chocar con lo difícil temprano.

## 3. Para qué la usa Juanma

Estudiante de ingeniería (circuitos, máquinas eléctricas, comunicaciones, electrónica).
Lo que hace con Samsung Notes hoy, y que la app tiene que cubrir antes de reemplazarla:

- Apuntes de clase a mano, con fórmulas, circuitos y gráficos.
- Resolver ejercicios y guías, muchas veces escribiendo arriba de un PDF.
- Hojas cuadriculadas para gráficos y diagramas.
- Modo oscuro siempre. Mismo aspecto que sus otras apps (paleta Nord).

Lo que dijo hasta ahora de cómo usa Samsung Notes (origen: Juanma, 2026-11-16):

- La barra de herramientas a la izquierda.
- Hojas tamaño A4, porque después pasa todo a PDF.
- Sin presión: la desactiva, quiere la línea siempre del mismo grosor.
- Que no gaste batería de más.

Falta relevar el resto (qué herramientas usa de verdad y cuáles nunca tocó).

## 4. Lo mejor de cada app

Lista de mi conocimiento de las dos apps, sin revisar versión por versión (origen:
Claude). Antes de copiar una función conviene mirarla en la tablet de Juanma (Samsung
Notes) o en un video (GoodNotes) y que él diga si la quiere.

### Samsung Notes

| Función | Por qué vale | Fase |
|---|---|---|
| El botón del S-Pen borra mientras se aprieta | Borrar sin cambiar de herramienta | 1 |
| Página continua hacia abajo | No pensar en cortes de hoja en clase | 2 |
| Mantener apretado al final de un trazo lo vuelve recta, círculo o rectángulo | Diagramas prolijos sin herramienta aparte | 3 |
| Resaltador que se endereza solo | Subrayar renglones | 3 |
| Lazo: mover, escalar, cambiar color, copiar | Reacomodar un ejercicio | 3 |
| Importar un PDF y escribir encima | Guías y apuntes de cátedra | 4 |
| Enderezar la letra y pasarla a texto | Lindo, no esencial | 5 |
| Buscar dentro de lo manuscrito | Encontrar un tema semanas después | 5 |
| Grabar audio sincronizado con lo escrito | Repasar una clase | 7 |
| Pantalla partida con otra app | Lo da Android gratis si la app lo permite | 1 |

### GoodNotes

| Función | Por qué vale | Fase |
|---|---|---|
| Cuadernos con tapa y plantillas de papel (liso, rayado, cuadriculado, punteado) | Orden por materia | 2 |
| Miniaturas de páginas: reordenar, duplicar, borrar | Manejar cuadernos largos | 2 |
| Pestañas con los documentos abiertos | Saltar entre la guía y el apunte | 2 |
| Tipos de lápiz (bolígrafo, pluma, pincel) con grosor y color guardados | Un toque para cambiar | 1 |
| Marcar páginas como favoritas e índice | Llegar rápido a lo que entra en el parcial | 4 |
| El PDF como documento de primera: índice, miniaturas, exportar anotado | Trabajar sobre material de cátedra | 4 |
| Cinta que tapa una respuesta y se levanta al tocar | Autoevaluación | 7 |
| Elementos reutilizables (símbolos guardados) | Resistencias, fuentes, ejes | 7 |
| Puntero láser | Explicarle a otro | 7 |

### Lo que no conviene perseguir

- **La nube de Samsung, Air Actions del S-Pen, y abrir el formato `.sdocx`.** Son
  cerrados. Los apuntes viejos entran exportados a PDF desde Samsung Notes.
- **Igualar el reconocimiento de letra de Samsung.** Hay una alternativa gratis y local
  (ML Kit Digital Ink Recognition, con español), pero es peor. Va al final y solo para
  buscar; no prometerle "pasar a texto" como en Samsung.
- **iPad, web, colaboración en vivo.** Fuera de alcance.

## 5. Cómo encararlo técnicamente

Todo esto es recomendación (origen: Claude). Las decisiones grandes se confirman con una
prueba chica en la fase 0, no por lo que dice este documento.

### Plataforma

- Android nativo en Kotlin, pensado para la tablet. En el celular alcanza con que abra y
  se pueda leer. `minSdk 29` (lo pide el dibujo de baja latencia).
- La superficie de dibujo es una vista propia. El resto de las pantallas (biblioteca,
  barras, diálogos) puede ir en Compose o en vistas armadas en código como Enlace:
  decidirlo en la fase 0 con una pantalla hecha de cada forma y que el criterio sea cuál
  se prueba y se renderiza a PNG más fácil.
- Sin servicios pagos ni nube propia. Sin cuentas en la app hasta la fase 6.

### Motor de tinta

Dos caminos, a comparar en la tablet en la fase 0:

1. **Jetpack Ink (`androidx.ink`)**: la librería de Google para esto. Trae el trazo en
   curso de baja latencia (dibuja en el buffer frontal y predice el movimiento),
   pinceles, geometría para goma y lazo, y guardado de trazos. La versión estable 1.0.0
   salió el 2025-12-17 y hay alfas de la 1.1.0 (buscado en la web el 2026-11-15: volver a
   mirar la página de versiones antes de fijar la dependencia).
2. **Seguir con el `BoardView` propio**, sumándole a mano el buffer frontal
   (`androidx.graphics:graphics-core`, `LowLatencyCanvasView` o
   `CanvasFrontBufferedRenderer`) y la predicción (`androidx.input:input-motionprediction`).

Espero que gane el 1: resuelve latencia, goma parcial y lazo, que a mano son semanas. El
2 queda como plan B y como referencia de "así se sentía bien". Sea cual sea, **los
trazos se guardan en un formato nuestro**, no en el de la librería (ver abajo), para
poder cambiar de motor sin perder apuntes.

### Formato y guardado: la parte que no puede fallar

Principio del ecosistema: no perder información. En una app de apuntes eso es el
producto.

- **Un archivo por cuaderno**, abierto y documentado: un zip con `manifest.json`, una
  entrada por página con sus trazos, y una carpeta con lo adjunto (el PDF de fondo,
  imágenes). Coordenadas en puntos de página, no en píxeles de pantalla, para que se vea
  igual en cualquier equipo.
- **Los archivos son la verdad.** El índice de la biblioteca (títulos, miniaturas,
  búsqueda) es una caché que se puede regenerar leyendo los archivos.
- **Guardado automático y atómico:** escribir a un archivo temporal y renombrar; nunca
  dejar un cuaderno a medio escribir. Guardar al pausar la app y cada pocos segundos de
  escritura.
- **Versiones:** conservar las últimas copias de cada cuaderno y una papelera. Borrar
  pide confirmación.
- **Siempre exportable** a PDF desde la fase 1, aunque sea una página. Es la salida de
  emergencia si el proyecto se abandona.
- El formato lleva número de versión desde el primer día y un test que abre archivos
  viejos guardados en el repo.

### Rendimiento

Fijar números y medirlos, no opinar. Propuesta inicial a ajustar con la tablet:

- El trazo en curso no se nota atrasado respecto de Samsung Notes (lo juzga Juanma) y se
  mide con una grabación de trazos reales reproducida.
- Página con 5.000 trazos: desplazar y hacer zoom sin tirones.
- Abrir un PDF de 200 páginas: primera página visible en menos de 2 segundos.
- Deshacer y goma responden en el mismo cuadro.

Cómo se llega: los trazos terminados se cachean en mosaicos por página y nivel de zoom;
solo se redibuja lo que cambió; un índice espacial dice qué trazos toca la goma o el
lazo sin recorrerlos todos.

### PDF

- Mostrar: `PdfRenderer` de Android (gratis, incluido).
- Exportar el PDF anotado conservando el texto original seleccionable no sale con las
  APIs de Android solas. Probar una librería libre (PdfBox-Android) en la fase 4; si no
  alcanza, exportar cada página como imagen con la tinta encima y avisarle a Juanma del
  límite.

### Pruebas

- **Modelo en Kotlin puro** (trazos, páginas, formato, deshacer, lazo) con tests de JVM
  rápidos. La mayor parte de la lógica tiene que vivir ahí.
- **Pantallas reales a PNG sin dispositivo** (Robolectric), como `tools/screens.sh` de
  Enlace: sirve para proponer diseños en imágenes y para ver un cambio antes de instalar.
- **Grabaciones de trazos reales:** una vez, grabar en la tablet lo que manda el S-Pen
  mientras Juanma escribe una página, y guardarlo en el repo. Reproducirlo en los tests
  da imágenes de referencia y mediciones repetibles sin la tablet.
- **Lo que solo se ve en la mano** (cómo se siente el trazo, la palma, el botón del
  lápiz) lo prueba Juanma con una lista corta al final de cada fase.

### Publicación

Igual que Enlace: se firma en la PC, se publica en GitHub Releases y la app ofrece
actualizarse sola. El actualizador ya está copiado en Bitácora y en Enlace: esta es la
tercera app, así que toca pasarlo a una librería compartida en vez de copiarlo otra vez
(idea ya anotada en `ideas.md` del cerebro el 2026-11-12). Lo mismo con la paleta Nord y
las piezas de interfaz de `Ui.kt`.

## 6. Fases

Cada fase termina con una versión instalada que Juanma usa y juzga. No se empieza la
siguiente sin su OK. Las duraciones no se estiman: dependen de cuántas sesiones haga por
semana con el plan Pro.

| Fase | Qué entrega | Se da por buena cuando |
|---|---|---|
| **0. Bases** | Repo, tests y chequeos automáticos, publicación con actualizador, relevamiento de qué usa de Samsung Notes, comparación de los dos motores de tinta en la tablet, formato de archivo escrito | Juanma eligió el motor escribiendo con los dos (gana el que se siente mejor, no el que trae más funciones), hay una medición del retraso del trazo, y aprobó el diseño de la pantalla de escritura |
| **1. Escribir una página** | Lápiz y resaltador con presión, goma (por trazo y parcial), deshacer y rehacer, zoom y desplazamiento con dos dedos, rechazo de palma, guardado automático, exportar a PDF | Escribir se siente igual o mejor que en Samsung Notes (lo dice Juanma), toma los apuntes de una clase entera y no pierde nada, ni cerrando la app a la fuerza |
| **2. Cuadernos** | Páginas (agregar, borrar, reordenar, miniaturas), papeles (liso, rayado, cuadriculado, punteado), biblioteca con carpetas por materia, papelera | Usa la app una semana de cursada en paralelo con Samsung Notes |
| **3. Lazo y formas** | Seleccionar, mover, escalar, recolorear, copiar entre páginas; formas que se enderezan; pegar imágenes | Rehace un ejercicio reacomodando partes sin reescribir |
| **4. PDF** | Importar, anotar, miniaturas e índice, favoritos, exportar anotado | Resuelve una guía de cátedra completa encima del PDF. **Acá debería poder dejar Samsung Notes** |
| **5. Texto y búsqueda** | Cajas de texto escrito con teclado; reconocimiento local para buscar en lo manuscrito | Encuentra un tema buscando una palabra que escribió a mano |
| **6. Ecosistema** | Copia de los cuadernos al Drive del cerebro, mandar una página a la PC, y lo que Juanma pida para los otros dispositivos | A definir con él cuando llegue |
| **7. Extras** | Audio sincronizado, cinta para autoevaluación, símbolos guardados, puntero | Una por una, si las pide |

## 7. Cuándo frenar

Escrito ahora para decidir en frío (origen: Claude):

- Si al terminar la fase 1 escribir una clase entera se siente peor que en Samsung Notes
  y dos intentos de arreglo no lo cambian: frenar y volver a Samsung Notes.
- Si la app pierde un apunte real una vez: se para todo lo nuevo hasta entender por qué y
  cubrirlo con un test.
- Hasta que la fase 4 esté aprobada, Samsung Notes sigue siendo donde vive lo importante.
  Cuaderno se usa en paralelo, no en reemplazo.

## 8. Cómo trabajarlo con otro modelo

Para Juanma, que dirige y prueba, y para el modelo que construye:

- **Una sesión, un cambio, un PR.** Las sesiones largas pierden el hilo y gastan cupo.
- **Todo lo decidido se escribe en el repo** (`docs/decisions.md`, `docs/status.md`). Lo
  que queda solo en la charla se pierde: pasó el mismo 2026-11-15 con cinco ideas de
  Enlace de las que quedó el nombre y no el detalle.
- **Cada sesión empieza leyendo** `CLAUDE.md` y `docs/status.md`, y termina
  actualizándolos junto con `proyectos/cuaderno.md` del cerebro.
- **Cada fase empieza en modo plan**, con el diseño en imágenes (render oscuro) y una
  sola recomendación.
- **Cada resultado dice cómo se verificó** y qué puede mirar Juanma en un minuto.
- Este plan se corrige cuando la realidad lo contradiga; el cambio se anota en
  `docs/decisions.md` con la fecha y de quién fue.

## 9. Encaje con el ecosistema

Lista de "Sumar una herramienta nueva"
(https://claude.ai/artifact/PHQmVerxfbkkCipzxGaF2d):

| Punto | Estado |
|---|---|
| Propuesta antes del código | Este documento; falta el OK de Juanma al plan |
| Una sola función, con su límite escrito | Sección 1 |
| Contratos probados, sin tocar la base de otra app | El formato de archivo es el contrato; lo demás, en la fase 6 |
| Diseño compartido | Paleta Nord; pasar las piezas comunes a una librería en la fase 0 |
| Repo propio, rama y PR por cambio | `~/projects/cuaderno`, solo local por ahora; crear el repo en GitHub lo decide Juanma |
| `CLAUDE.md` corto | Hecho; medir con `claude-budget` |
| Lo importante al cerebro, secretos nunca | `proyectos/cuaderno.md`; los cuadernos, en la fase 6 |
| Lo repetido se automatiza con rastro | Publicación y actualizador compartidos |
| Gratis y local primero | Sin servicios pagos |
| Diagrama y tabla de piezas del documento | Pendiente: sumarlo cuando Juanma apruebe |

## 10. Decisiones abiertas para Juanma

1. **Nombre.** "Cuaderno" lo propuse yo (origen: Claude).
2. **Papel por defecto:** oscuro con tinta clara, como la pizarra, o claro como una hoja.
   Afecta cómo se ven los PDFs y lo que se exporta.
3. **Qué usa de verdad de Samsung Notes** (primera tarea de la fase 0).
4. **Repo en GitHub:** hace falta para que la app se actualice sola, y para eso los
   demás son públicos. Los apuntes nunca van al repo.

## Fuentes

- Versiones de Jetpack Ink: https://developer.android.com/jetpack/androidx/releases/ink
- Dibujo de baja latencia: https://developer.android.com/reference/androidx/graphics/lowlatency/package-summary
- Guía de Google para lápiz (latencia y predicción): https://developer.android.com/codelabs/large-screens/advanced-stylus-support
