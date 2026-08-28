# CHANGELOG

## 1.25.1 — 27/08/2026

- La capa complementaria de 1.297 radares fijos españoles `TYPE=1` queda exclusivamente como
  semilla local empaquetada. Se elimina de la radio toda descarga, release de GitHub, control de
  actualización y credencial relacionada con Lufop/RadarDroid.
- Al actualizar desde una versión anterior, la semilla estática sustituye una posible caché
  complementaria descargada previamente. DGT sigue siendo prioritaria ante coincidencias.
- Los radares fijos exclusivos de la semilla complementaria usan la misma locución opcional y los
  mismos recordatorios que un fijo DGT. Los móviles, semáforos, zonas y extremos de tramo siguen
  excluidos.
- `Actualizar todo` conserva OSM Alicante, gasolineras, radares DGT, límites DGT e INVIVE; no añade
  ninguna dependencia nueva de terceros.

## 1.25.0 — 27/08/2026

- Se añade una segunda capa local de **radares fijos españoles Lufop/RadarDroid**. La APK incluye
  una semilla procesada de 1.297 puntos `TYPE=1` para funcionar recién instalada y puede renovarla
  desde el release público `radar-data-current` del repositorio. Antes de reemplazar la caché se
  valida país, esquema, recuento y SHA-256; si falla, se conserva la base ya instalada.
- DGT sigue siendo la fuente prioritaria de radares fijos y de tramo. Lufop es un complemento
  visual de fijos: excluye móviles, semáforos, zonas y extremos de tramo, y no activa la locución
  reservada a radares fijos DGT.
- Actualizaciones incorpora una fila y botón independiente **RADARES LUFOP**. `ACTUALIZAR TODO`
  muestra su progreso, conserva las ventanas de 24 horas y no envía contraseñas, tokens ni APIs a
  la radio.
- Se incorpora `tools/radar-data-publisher`, una herramienta Windows portable con interfaz local,
  secretos DPAPI, importación manual autorizada, comprobación diaria de fecha pública y tareas
  opcionales al iniciar sesión y a las 00:00. Publica únicamente los dos assets verificados para
  que la radio los descargue sin conocer ninguna credencial.

## 1.24.4 — 27/08/2026

- Los radares **fijos** DGT incorporan un margen global de paso de ±100 m: la detección puede
  empezar 100 m antes y la tarjeta permanece como máximo 100 m después de cruzar el punto DGT
  confirmado. La cifra de distancia se conserva sin desplazamientos artificiales; se evita así
  convertir una coordenada DGT correcta en un error de 100 m.
- La locución local de radar fijo mantiene el aviso inicial y añade un único recordatorio al
  alcanzar 300 m. Ambos usan los OGG pre-renderizados ya incluidos y solicitan foco transitorio
  Android `MAY_DUCK`; no intervienen CAN, UART, MCU, Bluetooth OEM ni Android Auto.
- El registro de ejecución diferencia aviso inicial y recordatorio de 300 m para validar la
  secuencia en la radio física.

## 1.24.2 — 25/08/2026

- Corrige el lector de la semilla local de respaldo de radares DGT: acepta tanto TSV
  real como el separador literal de versiones antiguas. La base nacional incluida sigue
  siendo prioritaria; el cambio garantiza que Alicante conserva radares fijos offline
  incluso en compilaciones sin el recurso nacional generado.
- El verificador de rutas de QA acepta rutas GeoJSON/OSRM y mantiene nombres Unicode
  legibles en la consola de preparación de la APK.

## 1.24.1 — 25/08/2026

- Corrige la cadencia del algoritmo de límites cuando Android no publica `Location.speed`: reutiliza durante un máximo de 10 segundos la velocidad GPS que la app ya ha calculado entre posiciones, siempre que el nuevo punto esté a menos de 200 m. Así un desplazamiento real no se trata erróneamente como vehículo detenido; no altera ni inventa datos CAN.

## 1.24.0 — 24/08/2026

- Añadido matching local por trayectoria: con el vehículo detenido se conserva el tramo
  confirmado; en movimiento un tramo alternativo debe ser claramente mejor o persistir en dos
  lecturas antes de sustituirlo.
- Las vías OSM `unclassified` sin referencia se muestran como recomendación urbana S-7 de
  30 km/h, en azul y sin carácter de límite legal. Los límites DGT/OSM y los tramos físicos
  verificados conservan siempre prioridad.
- El replay de la ruta de Alicante refleja la recomendación contextual para QA offline.

## 1.23.0 — 24/08/2026

- El centro de Actualizaciones ahora ofrece **ACTUALIZAR TODO** con una fila de progreso para
  OSM Alicante, gasolineras, radares DGT, límites DGT e INVIVE, más una fase explícita de
  consolidación local y un resultado final `OK` o `Aviso`.
- Se mantienen botones unitarios para cada fuente y se muestra la fecha/hora de la última descarga
  correcta. OSM conserva el alcance provincial y su protección anti-bloqueo; las publicaciones
  oficiales DGT se descargan completas a escala nacional y cada fuente respeta su ventana de 24 h.
- La detección de red para gasolineras reconoce también una interfaz IP Bluetooth PAN si Android la
  publica. La caché antigua ya no se cuenta como descarga correcta en el resultado del modal.
- Se añade la capa DGT nacional de límites TN-ITS semanal, con historial de cambios, geometría
  inferida UTM30 corregida y selección por carretera, proximidad y sentido antes de OSM.
- El acceso de la esquina inferior derecha usa una rueda dentada y conserva Debug/USB, Permisos y
  Actualizaciones sin cambiar el launcher, CANBUS, MCU, PDC, cámara, climatización ni funciones OEM.

## 1.21.0 — 24/08/2026

- Se incorpora el inventario oficial **INVIVE** de la DGT como base local de zonas donde se
  intensifica la vigilancia de velocidad. Se muestra como `ZONA DE VIGILANCIA`, nunca como
  radar fijo, y no activa la locución de radares ni inventa un límite de velocidad.
- La APK incluye el inventario nacional DATEX II en la instalación y conserva una pequeña
  semilla de Alicante como respaldo de compilación. Las actualizaciones son transaccionales,
  provinciales y respetan el intervalo de 24 horas ya utilizado por las demás bases.
- La detección combina los extremos oficiales del tramo INVIVE con la carretera local OSM y el
  rumbo GPS. Los radares fijos o de tramo conservan prioridad visual sobre una zona INVIVE.
- El mapa local de Alicante pasa al esquema v4 e incorpora la referencia de carretera (`ref`)
  para evitar asociar una zona INVIVE a una vía paralela solo por proximidad.
- El menú de Actualizaciones y el diagnóstico informan por separado del estado y la fecha de
  INVIVE. La integración es de solo lectura y no modifica CANBUS, MCU ni servicios OEM.

## 1.20.0 — 24/08/2026

- Alicante se entrega completamente precargado para la primera ejecución: 112.709 geometrías
  transitables OSM, límites genéricos y 89 vías con `maxspeed:forward/backward`, inventario
  nacional de radares fijos/tramo DGT y una instantánea oficial de precios de diésel fechada.
- La identificación de carretera y la calidad del dato son dos fases independientes. Primero se
  elige el tramo por distancia al segmento, rumbo y continuidad; solo después se resuelve señal
  verificada, `maxspeed` por sentido, `maxspeed` genérico o recomendación azul por clase.
- Las señales físicas verificadas durante la ruta de pruebas se aplican por vía, sentido y zona;
  nunca atraen la posición hacia una carretera paralela ni se muestran en sentido contrario.
- El caché de gasolineras cambia de versión para que una actualización de APK descarte datos
  binarios antiguos y arranque desde la semilla oficial incluida, incluso sin Internet.
- `ACTUALIZAR TODO · ALICANTE` puede actualizar el mapa provincial sin esperar una posición GPS;
  conserva el límite de una actualización correcta cada 24 h y encadena radares y precios.
- Una descarga provincial antigua se rechaza antes de sustituir el mapa local si no contiene el
  esquema direccional v3, evitando degradar silenciosamente la base incluida.

## 1.19.0 — 24/08/2026

- La selección local de vía combina distancia, rumbo de marcha y continuidad con el tramo
  anterior. En vías paralelas y cruces, un `maxspeed` explícito solo prevalece si su trazado es
  compatible con la dirección real del vehículo.
- Parado se conserva el último tramo confirmado durante la marcha; al arrancar sin historial se
  utiliza exclusivamente la cercanía. Un pequeño desplazamiento del GPS ya no cambia de calle.
- La frecuencia de consulta se adapta a la velocidad: 5 s parado y progresivamente hasta 350 ms
  a partir de 100 km/h, siempre limitada por la frecuencia física que publique el receptor GPS.
- El GPS solicita hasta 2 Hz cuando el hardware lo permite. La consulta sigue siendo local y no
  necesita Internet durante la conducción.
- Se evita registrar dos veces los listeners GPS, se agrupan redibujos CAN/GPS y se muestrean las
  trazas CAN repetitivas para reducir CPU, memoria y tamaño del log sin perder cambios reales.
- Las actualizaciones automáticas de mapas y radares esperan una conexión Android validada; las
  manuales conservan el intento sobre conexiones PAN publicadas por la unidad.
- Nuevas pruebas cubren rumbo bidireccional, cruces, vías perpendiculares, continuidad al detenerse
  y cadencia adaptativa por velocidad.

## 1.18.1 — 23/08/2026

- El ordenador de a bordo muestra la unidad detrás de la cifra grande: `772 km`,
  `10,8 l/100` y `30,0 °C`. Las etiquetas vuelven a ser cortas para conservar una línea clara.

## 1.18.0 — 23/08/2026

- Se sustituye la semilla de Alicante limitada a `maxspeed` por un mapa local completo de
  **112.709** vías transitables OpenStreetMap: 8.882 límites explícitos y 103.827 clases de
  vía para el respaldo azul DGT. La consulta sigue siendo íntegramente local durante la marcha.
- Al actualizar desde una versión previa se reemplaza exclusivamente la base `e87_speed_limits`
  de iDrive; no se modifican CAN, MCU, PDC, climatización, aplicaciones OEM, diagnósticos ni
  precios de gasolineras.
- El botón de Actualizaciones de Alicante sincroniza en una sola acción el mapa vial completo,
  los radares fijos/tramo DGT y los precios. El mapa remoto es un TSV comprimido importado en
  flujo, evitando cargar respuestas provinciales grandes de Overpass en memoria.
- El reloj y la fecha se ajustan al ancho disponible. El ordenador de a bordo muestra cada valor
  en su propia línea con la unidad en la etiqueta y el número grande separado; se retiró el texto
  de relleno de valores disponibles.

## 1.17.1 — 23/08/2026

- Cuando OpenStreetMap no publica `maxspeed`, el cuadrado azul usa una referencia genérica DGT
  por clase de vía: autopista/autovía 120, carretera convencional principal/secundaria/terciaria
  90, vía rápida 90, local 50, residencial 30 y zona de convivencia/servicio 20 km/h. Sigue
  siendo una recomendación contextual, nunca un límite legal ni un disparador del aviso naranja.
- La migración de base local recalcula las recomendaciones ya almacenadas al actualizar la APK;
  no hace falta descargar de nuevo una provincia para sustituir valores genéricos anteriores.
- La elección local prioriza un `maxspeed` explícito cuando ambos trazados pertenecen a la misma
  calzada práctica (tolerancia de 8 m), evitando que una vía auxiliar sin etiqueta oculte una
  señal legal cercana.

## 1.17.0 — 23/08/2026

- El mapa local conserva dos tipos de señal claramente diferenciados: círculo rojo únicamente
  para un `maxspeed` explícito de OpenStreetMap y cuadrado azul para una velocidad aconsejada
  calculada de forma conservadora a partir de la clasificación de la vía cuando no existe
  `maxspeed`. La recomendación no colorea en naranja el velocímetro ni se anuncia como límite
  legal.
- La actualización automática limitada a la zona GPS guarda también la clase de las vías
  transitables. La semilla incluida mantiene los límites explícitos de Alicante, Murcia,
  Valencia y Albacete sin convertir una descarga provincial completa en un paquete pesado.
- El aviso visual de radar conserva exclusivamente fijos y de tramo DGT. Se añade una locución
  opcional y local para **radares fijos DGT**: “Atención. Radar fijo. Límite X kilómetros por
  hora.” cuando la vía tiene un límite explícito cercano. Sin ese límite no inventa una cifra.
  No solicita foco de audio, no utiliza controles móviles ni afecta CAN, MCU, PDC o Bluetooth.
- El selector de Actualizaciones permite activar o desactivar la locución de radares fijos.
- Se aumentó la lectura del ordenador de a bordo y se rediseñaron las dos estaciones de servicio:
  etiqueta, nombre, distancia y precio grande en ese orden, para mejorar la lectura a distancia.
- Se añadieron pruebas unitarias para la tabla de recomendaciones y para la frase de voz, además
  de la actualización de esquema de caché local de límites.

## 1.16.2 — 23/08/2026

- DEBUG incorpora una casilla voluntaria para guardar posición GPS en los logs. Está desactivada
  por defecto, permanece activa hasta que el usuario la desmarca y deja constancia explícita del
  cambio de privacidad en el registro.
- Con la opción activa se exportan latitud, longitud, proveedor, precisión, velocidad y hora; con
  la opción desactivada los diagnósticos y el log vuelven a omitir las coordenadas.

## 1.16.1 — 23/08/2026

- Se corrige el fallo raíz que impedía los límites de vía: el proveedor GPS tenía permiso, pero
  no se iniciaba al entrar en la aplicación. Ahora se inicia y detiene con la pantalla, se reactiva
  tras conceder permisos y registra los proveedores activos sin guardar coordenadas.
- En Android 11 o posterior se solicita además una posición puntual al arrancar para reducir el
  tiempo de espera en frío; siguen activos los callbacks GPS, network y passive disponibles.
- La detección de límite calcula ahora la distancia al tramo completo de OSM, no únicamente a
  sus vértices. Esto evita que un punto GPS situado entre dos nodos separados deje el límite en
  blanco. La tolerancia continúa siendo prudente y depende de la precisión GPS publicada.
- Al actualizar desde versiones anteriores se comprueba la semilla por provincia y se completa
  Alicante (además de Murcia, Valencia y Albacete) si faltaba, sin sobrescribir una provincia
  descargada posteriormente por el usuario.
- Durante el arranque no se infiere una provincia con rectángulos geográficos solapados: si aún
  no hay un tramo local que identifique la zona, la actualización automática usa únicamente el
  área GPS de 5 km hasta disponer de una coincidencia fiable.
- El registro de sesión indica precisión, radio de búsqueda y resultado del límite local sin
  guardar coordenadas. La exportación USB escribe con dos modos SAF y verifica contenido para no
  informar éxito si el proveedor de la radio deja TXT vacíos.
- La actualización provincial de radares DGT ya no convierte provincias desconocidas en Alicante;
  solo importa registros cuya provincia coincide explícitamente con la seleccionada.

## 1.16.0 — 23/08/2026

- El aviso de radar abandona la columna estrecha del límite: la esfera queda arriba y el radar
  ocupa una franja completa debajo, con vía, distancia, acercamiento y señal `VÍA` legibles.
- El aviso utiliza directamente el pictograma de señal de radar proporcionado para el proyecto,
  escalado sin deformar y con una franja más alta para mantener nítidos la cabecera, las ondas y
  el coche. Se renovó la captura de portada; identifica explícitamente sus datos como simulación.

- La APK incluye el inventario nacional DATEX II de la DGT de radares fijos y de velocidad media
  (tramo), de aproximadamente 2 MB antes de compresión. La consulta durante la marcha usa solo
  SQLite local; no hay peticiones de red por cada posición GPS.
- El nuevo aviso inferior del velocímetro aparece únicamente si el vehículo se aproxima o está
  junto a un fijo/tramo. Indica vía, distancia y sentido de aproximación; se oculta al alejarse
  para no dejar avisos obsoletos en pantalla.
- Los controles móviles se excluyen de forma explícita del importador, de la base local y de la
  interfaz. No se infieren ubicaciones ni se utilizan fuentes colaborativas para móviles.
- El valor del círculo del aviso se etiqueta `VÍA` y solo muestra el límite de carretera verificado
  por la base OSM local. La DGT no publica un límite para cada registro que pueda presentarse como
  límite de radar.
- Actualizaciones añade `RADARES FIJOS Y DE TRAMO`, con selección de Alicante, Murcia, Valencia o
  Albacete. Al iniciar o reabrir la aplicación se comprueba en segundo plano la provincia GPS
  cuando Android expone una red IP; una actualización correcta se limita a una por provincia y 24 h.
- La tarea de compilación descarga la semilla nacional de la publicación DGT y mantiene una semilla
  de respaldo de Alicante si se construye sin red. La aplicación y el informe conservan atribución
  y URL de origen de la DGT.

## 1.15.2 — 23/08/2026

- El texto bajo `ACTUALIZAR` del indicador de límite se sustituye por el estado de la última base instalada: provincia
  y fecha/hora de la última actualización satisfactoria. Si aún no existe una descarga, identifica la base local incluida
  de Alicante, Murcia, Valencia y Albacete.
- Se elimina la duplicación de velocidad del panel izquierdo: el velocímetro derecho es la única presentación de la
  velocidad y el ordenador de a bordo usa el espacio liberado para autonomía, consumo, temperatura y otros valores reales.
- El velocímetro recupera la escala real del cuadro E87 (0, 20, 40 … 260 km/h) y rellena únicamente el aro exterior,
  sin sector triangular. Cuando existe un `maxspeed` local verificado, su punto se marca en naranja y tanto el aro como
  la cifra pasan a naranja al superarlo; sin límite local verificado, el aro y la cifra permanecen verdes.
- La consulta de límite próximo reduce la caché de 3 segundos/80 m a menos de un ciclo GPS: se ejecuta localmente en el
  siguiente fix de conducción (normalmente 1 s), sin nuevas solicitudes de red.
- La coincidencia local descarta fixes GPS de precisión superior a 30 m y limita la carretera candidata a 20–60 m según
  dicha precisión. Ante vías paralelas o posición ambigua muestra `—`, no un límite posiblemente incorrecto.

## 1.15.1 — 23/08/2026

- Gasolineras conserva la actualización ya automática: cuando Android publica una red IP válida, actualiza los
  precios cercanos y mantiene la caché local de 150 km.
- Los límites de velocidad se actualizan automáticamente cuando hay GPS e Internet Android. La provincia se obtiene
  primero de la carretera OSM local y, como respaldo, de la zona GPS; Alicante es el respaldo inicial mientras se
  adquiere posición. Solo se descargan Alicante, Murcia, Valencia o Albacete, nunca España completa.
- Cada provincia registra su propia hora de actualización satisfactoria. Un límite de 24 horas evita volver a
  consultar Alicante si está reciente, sin bloquear una actualización pendiente de Murcia, Valencia o Albacete.
- El modal `ACTUALIZACIONES` muestra red actual, provincia GPS elegida y fecha/hora de la última actualización correcta
  de gasolineras y límites. Las solicitudes se ligan a la red que Android haya expuesto (Wi-Fi, Ethernet o PAN), sin
  activar ni configurar ninguna conexión OEM.

## 1.15.0 — 22/08/2026

- Se revisa la pantalla principal: el resumen del ordenador de a bordo ocupa el panel izquierdo, la tarjeta multimedia
  deja de ocupar espacio cuando la unidad no publica una sesión fiable y el menú completo permanece accesible desde el
  botón `MENÚ COMPLETO` y la cabecera.
- El velocímetro usa GPS como fuente visible validada en esta radio, conserva el color verde hasta 120 km/h y naranja por
  encima, y oculta campos sin datos reales.
- Se añade `SpeedLimitRepository`, una base SQLite local (`e87_speed_limits.db`) que consulta los límites próximos sin
  conexión. La APK incorpora semillas de Alicante, Murcia, Valencia y Albacete; desde la llave inglesa se separan los
  accesos `DEBUG / USB`, `PERMISOS` y `ACTUALIZACIONES`. Esta última permite actualizar precios de gasolineras y elegir
  una zona GPS de 5 km o una única provincia por Wi-Fi, sin descargar España completa; no se muestra una señal inventada
  cuando la base no tiene cobertura.
- Se corrige la lectura de las semillas en APK: Android puede desempaquetar un recurso `.gz` y publicarlo como `.tsv`, por
  lo que el importador acepta ambos empaquetados y valida que la base local no quede vacía.
- Se añade la atribución de OpenStreetMap/Overpass y se actualiza la versión de compilación a 1.15.0.

## 1.14.1 — 17/08/2026

- El ordenador de a bordo pasa a ser completamente dinámico: oculta toda fila sin un valor real disponible y elimina
  la marcha de la lista. Cuando CAN OEM publique una marcha válida, se dibuja en grande dentro de la esfera, bajo la
  velocidad; si no hay dato, no se reserva espacio ni se muestra un guion.
- La tarjeta de gasolineras aumenta la tipografía del precio y separa la distancia en un valor más grande y resaltado,
  conservando las dos filas y el área táctil de cada estación.
- Se añadió un respaldo Bluetooth OEM de solo lectura basado en el contrato exacto exportado por
  `com.jancar.btservice`: consulta `getBluetoothState` y `getCurrentDeviceName` y solo muestra el nombre cuando el
  servicio confirma el estado `CONNECTED`. No empareja, conecta ni modifica el módulo Bluetooth.
- El panel de permisos aclara que `INTERNET` se concede al instalar y no tiene diálogo en tiempo de ejecución. Bluetooth
  PAN debe ser creado por el sistema de la radio; la aplicación aprovecha la red que Android publique, pero no puede
  activarla ni convertir por sí misma una conexión Android Auto en acceso IP.
- Se mantienen las cuatro rutas multimedia seguras: broadcast pasivo de SpeedPlay, puente `MediaService` Jancar,
  `MediaSession` y notificaciones. Siguen requiriendo una prueba física porque los últimos registros no contenían
  metadatos publicados por SpeedPlay durante Android Auto.
- QA completada con tests unitarios, Lint, compilación debug y ejecución visual en emulador Android 15 a 1280×720.

## 1.14.0 — 17/08/2026

- Se analizaron las capturas físicas del 17/08 y los contratos `Parcelable` de la APK OEM exacta. El lector pasivo
  reconoce ahora los callbacks verificados `HvacInfo`, `RadarInfo` y `SteerWheelInfo`, además de Cabin, Light y
  Dashboard; no se envía ningún comando ni se escribe CAN/UART.
- `HvacInfo` añade una nueva ruta validada por rango para temperatura exterior, consignas izquierda/derecha, nivel de
  ventilador y estado del climatizador. Los valores no plausibles y sentinelas quedan solo en USB DEBUG.
- `RadarInfo` registra los ocho sensores delanteros/traseros y los laterales publicados por la unidad. La UI solo
  muestra PDC activo cuando el propio Parcel publica un indicador de activación; las distancias permanecen en
  diagnóstico hasta confirmar su escala en una prueba física.
- La prueba guiada P/R confirmó en esta unidad `GearShiftPosition 0=P` y `1=R`; se habilita la marcha del getter OEM y
  el aviso de marcha atrás. Velocidad y RPM del getter siguen rechazadas: los logs demostraron una rampa interna falsa
  y un sentinel, por lo que la velocidad fiable continúa siendo GPS salvo callback CAN vivo validado.
- El registro de sesión guarda cada callback interpretado con valores brutos, incluidos 0 y sentinelas, y toma cada dos
  segundos una instantánea simultánea de Dashboard, Cabin, Light, HVAC y Radar. Los nuevos getters están aislados:
  si este firmware rechazase uno, no se pierden autonomía, consumo, marcha ni los demás datos ya funcionales.
- Se conserva la semántica prudente para puertas, cinturones, freno y tipos de luz: las capturas entregadas no muestran
  cambios fiables en sus campos actuales y por tanto no se generan avisos falsos.

## 1.13.5 — 16/08/2026

- Se corrige el inspector CAN para ocultar los sentinelas OEM `Integer.MIN_VALUE` y su equivalente `float`; ya no se
  interpretan como luces de emergencia, puertas abiertas o cinturones sin abrochar.
- Se corrige la escritura SAF de USB DEBUG: el informe usa el modo estándar `w` del proveedor de documentos, compatible
  con los selectores de almacenamiento de estas radios.
- Los estados de puertas y cinturón procedentes de `CabinInfo` quedan en modo observación hasta validar en esta unidad
  el significado de sus valores 0/1. La fila inferior conserva únicamente estados publicados por una fuente verificada.
- La velocidad valida discrepancias entre CAN, JCRK01/CYA y GPS para rechazar una muestra CAN estancada de 14 km/h
  cuando el resto de fuentes confirma que el vehículo está parado.
- `EXPORTAR` dentro del diagnóstico guarda ahora directamente en la carpeta USB autorizada el informe y el registro de
  sesión; si no hay carpeta autorizada, abre el selector USB. Se mantiene la copia interna de recuperación.
- La radio muestra `Sin información RDS` cuando solo está disponible la frecuencia, sin el texto técnico de procedencia.
- La lectura multimedia busca también sesiones/notificaciones estándar de Spotify y SpeedPlay cuando existen; los
  controles solo se habilitan si Android publica acciones compatibles.
- El acceso de gasolineras intenta también redes IP Android con capacidad INTERNET aunque la radio no las marque como
  validadas, conservando caché y límites de tiempo si la petición falla.

## 1.13.4 — 16/08/2026

- Se añadió al diagnóstico el botón `DATOS CAN EN VIVO · FUENTES`.
- El modal permite cambiar entre `CAN OEM`, `JCRK01 / CYA`, `Android Automotive` y `GPS`, actualizar la lectura cada
  segundo y mostrar u ocultar valores cero sin modificar la selección automática del ordenador de a bordo.
- CAN OEM muestra los campos completos de `DashBoardInfo`, `CabinInfo` y `LightInfo` que la aplicación ya tiene
  verificados en la APK de la unidad, incluyendo puertas, cinturones, ventanas, techo, combustible, temperaturas y
  pedales. Se dejan fuera getters cuyo formato de respuesta no está confirmado.
- El modo por defecto oculta ceros para facilitar la correlación de señales activas; se puede activar `Mostrar 0` para
  comparar el estado completo.

## 1.13.3 — 16/08/2026

- Se integró la pista del home OEM: el estilo original que muestra el coche recibe sus datos desde `com.can.activity`,
  cuyo contrato exportado publica `ICanUI -> CanBusManager -> ICanBus`.
- La aplicación enlaza ese servicio solo si ya está activo, sin `BIND_AUTO_CREATE`, y lee de forma pasiva
  `getDashBoardInfo`, `getCabinInfo` y `getLightInfo`. Se incorporan velocidad, autonomía, consumo medio, RPM,
  temperatura exterior, refrigerante, puertas, intermitentes y cinturones cuando la unidad los expone.
- La prioridad queda: CanBusManager OEM, CarService Jancar, Android Automotive público y GPS solo para velocidad.
  Se conserva el registro de procedencia y de los valores crudos para validar el resultado en la radio.
- QA superada con tests unitarios, Lint y compilación debug. La validación final del Binder requiere probar la APK en la
  unidad con el servicio CAN OEM activo; si el servicio no está publicado, se mantienen los respaldos anteriores.

## 1.13.2 — 16/08/2026

- Se verificaron en las APK exportadas de esta unidad los contratos Binder de `CarService` y `RadioService`. El lector
  de radio usa únicamente `getFreq`, `getBand` y `getPSText`; el ordenador de a bordo conserva los getters Jancar
  confirmados, Android Automotive público y GPS como fuentes ordenadas, sin iniciar servicios OEM ni escribir buses.
- La velocidad GPS incorpora una estimación prudente entre posiciones GPS cuando el receptor no publica `speed`.
  Cada campo registra qué fuente fue finalmente seleccionada o si ninguna ruta ofreció un valor.
- Se eliminó el falso estado de neumáticos: el getter correspondiente de este firmware devuelve siempre cero y no
  constituye evidencia de presión correcta. Los avisos verdes se basan solo en informes OEM realmente publicados.
- La tarjeta de radio puede mostrar banda, frecuencia y texto RDS/PS publicados por el servicio OEM. Como el contrato
  no incluye un estado remoto de encendido, el diagnóstico advierte que puede tratarse de la última emisora sintonizada.
- El lector multimedia vuelve a enlazar el `NotificationListenerService`, enumera sesiones/notificaciones activas y
  usa metadatos y controles únicamente cuando SpeedPlay publica una `MediaSession` Android estándar.
- Las gasolineras aceptan cualquier red que Android publique con `INTERNET` y `VALIDATED`, aunque no sea la red
  predeterminada. Esto incluye Bluetooth PAN si la radio lo crea; la aplicación no puede activar el tethering del
  teléfono ni convertir por sí sola una conexión Android Auto en acceso IP.
- Se inspeccionó el APK exacto de SpeedPlay: no expone un Intent, URI o comando verificable para enviar un destino a
  la proyección Android Auto. Las gasolineras abren Google Maps local y el diagnóstico documenta esta limitación.
- Cada inicio de proceso sobrescribe un registro de sesión acotado a 512 KiB, sin coordenadas. Anota proveedores,
  valores crudos, fuente elegida, red, radio, multimedia y errores; `USB DEBUG`, la exportación OEM y la completa lo
  guardan además como `e87_runtime_session_YYYYMMDD_HHMMSS.log`.
- QA superada: pruebas unitarias, Lint y compilación debug con SDK Android disponible.

## 1.13.1 — 16/08/2026

- Se integró un adaptador de solo lectura para el `CarService` Jancar identificado en la APK exacta de la unidad. Usa
  únicamente transacciones getter verificadas, no crea el servicio, no registra callbacks y no escribe CAN, MCU o UART.
- El ordenador de a bordo prioriza velocidad Jancar y puede leer consumo, RPM, autonomía y temperatura exterior;
  Android Automotive público y GPS permanecen como fuentes alternativas con caducidad acotada.
- La barra inferior añade marcha atrás y mantiene ocultos los estados normales. El nuevo botón de listado abre avisos
  OEM/TPMS y mantenimiento, con estado azul, verde o naranja según disponibilidad y alertas reales.
- Multimedia prioriza la sesión de SpeedPlay/Android Auto y habilita play/pausa, anterior y siguiente únicamente si la
  sesión anuncia las acciones estándar correspondientes. La radio conserva un botón seguro para abrir la app OEM
  cuando no publica metadatos.
- GPS escucha proveedores GPS, red y pasivo. La tarjeta de gasolineras muestra la hora local de actualización y permite
  forzar un refresco no bloqueante mediante `↻`.
- Diagnóstico incorpora `PERMISOS`, con estado de ubicación, Bluetooth y acceso multimedia, solicitud de permisos,
  apertura del panel de escucha de notificaciones y acceso a los ajustes de la aplicación.
- La versión se ajusta al hardware real Android 13/API 30 observado, manteniendo compilación y target API 35.

## 1.12.0 — 16/08/2026

- Las cuatro capturas físicas identificaron una unidad Rockchip `rk3326_r`/`rk30sdk`, API efectiva 30, ABI
  `armeabi-v7a`, 4 GB de RAM, heap de 192 MB y 42,4–45,0 MB PSS. La app diferencia ahora la etiqueta de firmware
  Android 13/15 de la compatibilidad real Android 11/API 30.
- El diagnóstico inicial se sustituyó por un resumen legible en la pantalla de 9 pulgadas. El informe completo sigue
  disponible al exportar o compartir y añade autoridades, permisos, procesos y componentes OEM detallados.
- Se añadió `EXPORTAR DATOS OEM`: con confirmación y una carpeta SAF autorizada guarda el inventario y copia los APK
  instalados relacionados con CAN, vehículo, `CarService`, cluster, MCU o marcha atrás. La extracción no carga APK,
  no enlaza servicios, no consulta URI desconocidas y continúa si un archivo individual falla.
- La selección incluye tanto los núcleos observados (`com.can.activity`, `com.jancar.services`, launcher, radio y
  ajustes) como otros paquetes detectados por sus componentes. La copia está limitada a 100 MB por archivo y 250 MB
  por sesión; los binarios OEM no se publican en el repositorio.
- El informe separa ordenador de a bordo y GPS: muestra procedencia, edad y disponibilidad de velocidad, autonomía,
  consumo, temperatura exterior y temperatura de motor; del GPS registra proveedor, activación, precisión y velocidad,
  pero omite coordenadas.
- La sonda AAOS pública reconoce también `RANGE_REMAINING` (metros convertidos a km) e
  `INSTANTANEOUS_FUEL_ECONOMY` (L/100 km). En la radio Jancar convencional no se usan como sustituto del puente OEM.
- La cuarta fila del ordenador de a bordo utiliza temperatura exterior en vez de la consigna del climatizador.
- La investigación encontró implementaciones públicas del Binder de radio Jancar, pero no el contrato exacto de
  `CanBusContentProvider`/`CarService`; por seguridad todavía no se inicia ni enlaza ninguno.
- La pantalla presenta dos acciones distintas: `EXPORTAR RADIO / CAN` para una extracción rápida y
  `EXPORTACIÓN COMPLETA` para inventariar y copiar todos los APK/splits legibles. La segunda admite hasta 16 GB en la
  USB de 20 GB indicada y mantiene un límite FAT32-compatible de 2 GB por archivo.
- El inventario completo añade fingerprint, build, parche, kernel, ABI, funciones, bibliotecas, paquetes de
  actualización y resolución pasiva de `SYSTEM_UPDATE_SETTINGS`. Copia además los `build.prop`, `prop.default` y
  certificados OTA públicos que Android permita leer; nunca intenta particiones, datos privados, MCU o firmware Hiworld.
- La barra `ESTADO DEL VEHÍCULO` deja de dibujar cinco estados vacíos. Solo aparecen estados reales y activos: luces
  encendidas, freno aplicado, cinturón sin abrochar y puertas abiertas. Estados normales o no disponibles quedan
  ocultos; una llave inglesa compacta y fija a la derecha abre el diagnóstico y sus herramientas.

## 1.11.1 — 15/08/2026

- Los candidatos medios y fuertes del asistente `USB DEBUG` se guardan de forma atómica en el almacenamiento privado
  de la aplicación y se agregan entre sesiones distintas, incluso después de reiniciar la radio o actualizar la APK.
- El registro distingue `OBSERVADO`, `REPETIDO` y `LISTO PARA REVISAR`. Este último exige tres sesiones diferentes
  con evidencia fuerte, pero nunca activa automáticamente un mapeo ni presenta la señal como confirmada.
- `USB DEBUG > Ver candidatos guardados` permite revisar valores, pasos, fuente, puntuación e historial sin conectar
  la memoria USB. El borrado exige confirmación y no afecta a los TXT exportados.
- El historial está acotado a 200 candidatos, 20 sesiones y 16 valores por candidato para mantener constantes el uso
  de disco y memoria. Se incluye también en cada informe de diagnóstico y captura USB.

## 1.11.0 — 15/08/2026

- `USB DEBUG` incorpora un asistente visual a pantalla grande con preparación, progreso y maniobras concretas para
  luces/intermitentes, freno de mano, las cuatro puertas y portón, cinturón, temperatura exterior, climatización,
  ventilador, marcha atrás/PDC y una señal libre.
- Cada maniobra toma su propia línea base, exige tres segundos de estabilización y permite repetir u omitir el paso.
  Los candidatos aparecen en directo como fuertes, medios o débiles según fuente, cambio, repetición y coincidencia
  semántica; nunca se presentan como códigos CAN confirmados.
- El TXT USB incluye el resultado ordenado de cada paso, valores anterior/actual, número de cambios, fuente y criterio
  de puntuación. Se mantienen el autoguardado de cinco segundos, el límite de diez minutos y la copia interna.
- Se añadieron pruebas unitarias del clasificador y del catálogo de nueve planes guiados, incluida la carrocería E87
  de cuatro puertas más portón.
- La sonda Android Automotive pública intenta además `ENV_OUTSIDE_TEMPERATURE`, `GEAR_SELECTION`/`CURRENT_GEAR`,
  `HVAC_TEMPERATURE_SET`, `HVAC_FAN_SPEED` y `HVAC_POWER_ON`. Los permisos privilegiados denegados quedan registrados;
  no se escriben propiedades ni se promueve ninguna observación propietaria a dato de vehículo.

## 1.10.1 — 15/08/2026

- Se hicieron visibles en la portada de GitHub las atribuciones de BMW, iDrive, el emblema, las denominaciones de
  modelo y las restantes marcas de terceros. `NOTICE.md` y `LICENSE-ASSETS.md` identifican además los recursos que
  contienen esos elementos y aclaran que quedan fuera de las licencias del repositorio.
- El valor de velocidad, su lectura en la fila y el arco activo del velocímetro se muestran en verde hasta 120 km/h y
  en naranja por encima. Sin una lectura real se conserva el estado neutro `—`.
- Se añadió lectura opcional y de solo lectura de `PERF_VEHICLE_SPEED_DISPLAY`/`PERF_VEHICLE_SPEED` mediante Android
  Automotive público, convirtiendo m/s a km/h y registrando propiedad, valor y fuente en el diagnóstico.
- Una velocidad de vehículo reciente tiene ahora prioridad sobre GPS; GPS se utiliza únicamente como fallback. Las
  lecturas de vehículo mayores de tres segundos y las GPS mayores de diez se descartan para evitar valores congelados.
- `CAR_SPEED` solo se solicita en sistemas que declaran la característica Automotive. No se interpreta como velocidad
  ningún extra o índice JCRK01/CYA que todavía no haya sido confirmado en la unidad física.

## 1.10.0 — 15/08/2026

- Se añadió `USB DEBUG` al diagnóstico integrado. Detecta volúmenes extraíbles montados y utiliza el selector oficial
  de Android para que el usuario autorice una carpeta sin solicitar acceso general al almacenamiento.
- Las capturas se guardan como TXT fechado, se actualizan cada cinco segundos y conservan una copia interna de
  recuperación si la USB se retira o falla durante la prueba.
- La captura guiada incluye luces, freno, puertas, cinturón, temperatura, climatización, PDC/marcha atrás y una señal
  libre. Mantiene las sondas pasivas al quedar la app oculta y se detiene automáticamente a los diez minutos.
- Durante una sesión explícita se comparan también claves legibles no preclasificadas de `Settings.System/Global`,
  omitiendo nombres potencialmente sensibles. Fuera de debug se mantiene el filtro ligero anterior.
- Los cambios leídos mediante Android Automotive público se incorporan al registro con campo, valor y fuente. No se
  leen tramas CAN/UART, `logcat` global ni índices `com.syu`, y no se envían órdenes propietarias.

## 1.9.1 — 15/08/2026

- El nombre visible en el launcher se acortó a `iDrive`; la aplicación continúa siendo una actividad normal y no se
  registra como launcher `HOME`.
- Se sustituyó el icono genérico por un emblema BMW sobre fondo azul marino, con margen seguro para máscaras
  circulares y redondeadas. También se declaró como `roundIcon` para launchers que utilicen esa variante.
- El recurso empaquetado se optimizó a 512×512 para limitar su impacto en tamaño y memoria; el máster se conserva en
  `docs/assets/generated`.

## 1.9.0 — 15/08/2026

- La tarjeta `Radio` abre al tocarla la aplicación OEM detectada o asignada; una pulsación larga permite corregir
  manualmente la actividad cuando el firmware usa nombres propietarios.
- Mientras la app está visible, la tarjeta actualiza cada dos segundos la emisora, frecuencia o texto RDS que la
  aplicación de radio publique mediante `MediaSession`, sin enviar controles de reproducción.
- Como respaldo se leen únicamente `EXTRA_TITLE` y `EXTRA_TEXT` de la notificación de esa misma aplicación. No se
  inventan broadcasts, índices MCU o protocolos propietarios.
- Si la radio no publica ninguno de esos mecanismos, la tarjeta indica `Emisora no expuesta` y el informe de
  diagnóstico identifica el paquete asignado, permiso, fuente y campos observados para probarlo en la unidad física.

## 1.8.0 — 15/08/2026

- La tarjeta `Teléfono / Bluetooth` abre al tocarla la aplicación de teléfono asignada, donde la radio puede ofrecer
  contactos, teclado y llamadas. Una pulsación larga conserva la selección manual de la actividad OEM correcta.
- Se dejó de mostrar el nombre de la app (`Phone`) como si fuera el móvil conectado. La tarjeta presenta ahora el
  nombre real que Android publique mediante perfiles manos libres/A2DP o rutas públicas de audio Bluetooth; esta
  segunda fuente mejora la compatibilidad con radios que actúan como receptor y no como teléfono emisor.
- Sin conexión muestra `Ningún terminal conectado`; también diferencia Bluetooth desactivado, adaptador inexistente,
  permiso pendiente y firmware que no expone los perfiles, sin deducir estados propietarios.
- Se añadió únicamente `BLUETOOTH_CONNECT`/Dispositivos cercanos en Android 12–15, además de permisos Bluetooth legacy
  hasta Android 11. No se solicita escaneo, no se empareja y no se inicia ni termina ninguna conexión.
- La detección de la aplicación de teléfono prioriza dialer, contactos, phone, Bluetooth y hands-free, y penaliza
  explícitamente `BT Music` para evitar abrir el reproductor en vez del menú de llamadas.
- Se verificó en Android 15 que la tarjeta sin terminal muestra el estado correcto y que tocarla abre
  `com.android.dialer`. Dos ciclos completos abrir–volver finalizaron sin excepciones.

## 1.7.0 — 15/08/2026

- Se sustituyó la caché mínima por una cobertura móvil de 150 km centrada en el GPS. La tarjeta continúa filtrando
  estrictamente el radio elegido —7 km por defecto—, pero puede recalcular resultados al instante durante el trayecto
  sin volver a descargar España al cambiar de posición.
- La cobertura nacional se renueva cada 24 horas o al acercarse al borde seguro de la zona de 150 km. Se procesa en
  streaming y en la prueba de Madrid ocupa 143.522 bytes en disco.
- Cada diez minutos, mientras la app está visible, solo se actualizan los precios de las provincias detectadas alrededor
  del vehículo mediante el endpoint oficial `FiltroProvinciaProducto`; al entrar en otra zona provincial también se
  seleccionan sus precios sin bloquear el recálculo GPS.
- Medición real para Diésel: respuesta nacional 4.302.377 bytes frente a 322.345 bytes para Madrid, una reducción del
  92,5 % en una actualización local de una provincia.
- Las distancias y las dos selecciones visibles se recalculan cada 500 m usando la caché, independientemente del ciclo
  online de diez minutos. Cambiar solo el radio no provoca descarga; cambiar combustible prepara su propia cobertura.
- Se verificó un desplazamiento GPS Madrid–Guadalajara: los resultados cambiaron de Alcampo/Blanca a Ballenoil/Repsol
  sin modificar el archivo de caché ni realizar una nueva descarga nacional.

## 1.6.0 — 15/08/2026

- Se sustituyó la tarjeta decorativa de navegación por una tarjeta GPS de gasolineras con dos destinos accionables:
  la de menor precio y la más cercana dentro del radio configurado.
- Diésel (Gasóleo A habitual) y 7 km son los valores iniciales, siguiendo el proyecto UGasolineras; se pueden elegir
  ocho combustibles oficiales y radios de 3 a 50 km desde Ajustes o manteniendo pulsada la tarjeta.
- Los precios proceden del endpoint oficial MITECO filtrado por producto. El JSON nacional se procesa en streaming y
  solo se conservan las estaciones del área elegida; en la prueba de Madrid la caché ocupó 8.852 bytes.
- La app usa la red predeterminada de Android, independientemente de si llega por Wi-Fi/hotspot, SIM, Ethernet o
  tethering USB/Bluetooth. La caché permite mostrar el último resultado cuando no hay red.
- Se añadió caducidad de precios de dos horas, recálculo local por desplazamiento y actualización al recuperar
  conectividad. Las descargas automáticas por movimiento se limitan a una cada 30 minutos para proteger datos, CPU y
  batería; la actualización manual permanece disponible.
- Al tocar una estación se abre navegación en Google Maps; si no está instalado, se usa un intent geográfico estándar.
- La ubicación solo se usa localmente para filtrar y medir distancia aproximada; no se incorpora a la petición MITECO.
- Se mantuvo la app como `MAIN` + `LAUNCHER`, sin HOME, boot receiver ni servicio de actualización residente.

## 1.5.0 — 15/08/2026

- Se rediseñó la barra de estado del vehículo para acercarla a la referencia GPT: insignia circular, coche frontal
  blanco y título limpio sin subtítulo técnico.
- Luces, freno, cinturón, puertas y avisos se presentan en una sola línea, con pictogramas y separadores verticales,
  sin cinco tarjetas visualmente pesadas.
- La barra completa incorpora margen exterior, degradado, borde fino y cuatro esquinas redondeadas sin recortes.
- Se aumentó el radio de menús, paneles y accesos, y se añadió separación real entre las tarjetas de la fila inferior
  para evitar cruces y medias curvas en los bordes.
- La barra y sus estados siguen siendo componentes nativos y dinámicos; solo la insignia utiliza un recorte ligero.
- La insignia incorpora un recorte fotorealista generado del frontal de un E87 azul marino, optimizado a 128×128 y
  28 KB; el original con transparencia se conserva en `docs/assets/generated`.

## 1.4.0 — 15/08/2026

- Se investigaron implementaciones públicas FYT, Android Automotive, AAIdrive y canbox sin importar índices ni
  protocolos que no estén confirmados para JCRK01/CYA + Hiworld.
- El diagnóstico observa de forma pasiva cambios relevantes de `Settings.System/Global`, útil para correlacionar el
  ajuste OEM de brillo con los faros y otras señales sin escribir CAN/UART.
- Se añadió al informe el perfil real de la unidad: RAM total/disponible, límite de heap, PSS, APK, CPU y ABI.
- El inventario de paquetes se ejecuta una sola vez en segundo plano y se redujeron falsos positivos heurísticos.
- Se corrigió el botón `DETENER` de las sesiones de correlación y se verificó el ciclo completo en Android 15.
- Se agruparon las consultas de detección automática, se redujo el bitmap central en memoria y se acotaron/cacharon
  las carátulas multimedia a 256 px.
- Se retiró del APK un recurso antiguo no utilizado, conservándolo en `docs/assets/archive`.
- APK reducido de 3.174.756 a 1.959.121 bytes; PSS de referencia reducido de 48.304 a 43.373 KB.

## 1.2.0 — 15/08/2026

- Se reconstruyó la composición 1280×720 para igualar la referencia: cabecera completa, menú lateral limitado a la zona superior, fila de accesos a ancho completo y barra OEM inferior.
- Se integró una nueva imagen central BMW E87 con perspectiva frontal, iluminación azul y suelo reflectante, manteniendo el activo anterior.
- Se ajustaron la paleta negro/azul, los degradados, bordes, selección naranja y proporciones de los paneles.
- El ordenador de a bordo incorpora filas con iconos, separadores y velocímetro 0–260; los valores no disponibles siguen mostrando `—`.
- La barra inferior muestra Luces, Freno de mano, Cinturón, Puertas y Avisos sin inventar estados.
- Se retiró de la cabecera el estado BT/Wi-Fi/temperatura duplicado y se reservó esa zona para una posible superposición OEM.
- Los cuatro paneles inferiores ahora tienen composiciones propias: mapa decorativo sin ruta simulada, radio sin frecuencia inventada, Android Auto y Bluetooth.
- Multimedia puede leer carátula, título, artista y estado desde MediaSession cuando el usuario concede el acceso estándar de Android 15.
- La UI se verificó en un emulador Android 15/API 35 a 1280×720.

## 1.1.0 — 15/08/2026

- La APK deja de declararse como launcher HOME: la radio arranca con el sistema OEM y BMW E87 iDrive se abre como aplicación normal.
- Se mantiene la selección heurística, pero la selección manual guarda paquete y `ComponentName`; una elección manual no se sobrescribe.
- Se sustituyó la frecuencia FM fija por un acceso neutral a la app Radio configurada.
- Se añadió una silueta dibujada del BMW E87 azul marino, sin telemetría simulada.
- La silueta se sustituyó por un activo fotográfico propio, transparente y específico para el panel central, para ajustarse al diseño de referencia.
- Se separaron modelo de datos de vehículo, fuentes y repositorio; la velocidad GPS conserva fuente y timestamp.
- Se mejoró el diagnóstico pasivo: build, componentes, filtros, candidatos por rol, eventos acotados y sesiones de correlación manual.
- Se añadió exportación/compartición del informe con proveedor interno de solo lectura.
- Se añadió lectura oportunista de MediaSession estándar, sin controlar reproducción ni usar APIs JCRK01 inventadas.
- Se añadió modo día/noche manual o automático por hora local.
- Se añadió icono de aplicación y permiso explícito de estado de red.
- Compilación verificada con Gradle 8.11.1, AGP 8.7.3, JDK 17, compileSdk 35 y SDK local `C:\Android`.
