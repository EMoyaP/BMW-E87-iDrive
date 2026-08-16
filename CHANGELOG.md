# CHANGELOG

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
