# CHANGELOG

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
