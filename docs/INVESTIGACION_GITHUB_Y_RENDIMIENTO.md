# Investigación de proyectos públicos y rendimiento

Fecha de revisión: 2026-08-16.

## Validación 1.13.2 sobre las APK exportadas de la unidad

- `com.jancar.services.car.CarService` y `com.jancar.services.radio.RadioService` se consumen mediante componentes,
  descriptores y transacciones getter comprobados en las APK exportadas. No se usa `BIND_AUTO_CREATE`, no se registra
  un callback propietario y no se envían comandos CAN, MCU, UART o de sintonización.
- El APK de SpeedPlay contiene un `MediaPlaybackService` privado y no exportado. Solo será visible para iDrive cuando
  el propio SpeedPlay active su `MediaSession`; no se inicia desde esta aplicación. Su receptor exportado no contiene
  una operación verificable para enviar coordenadas o un destino a Android Auto.
- La red se elige entre todas las que Android publique con `INTERNET` y `VALIDATED`, usando `Network.openConnection`
  para admitir incluso una red validada que no sea la predeterminada. `TRANSPORT_BLUETOOTH` funciona como Bluetooth
  PAN únicamente si el firmware y el teléfono crean esa red; emparejamiento, A2DP o Android Auto no implican Internet.
- El registro de ejecución está limitado a 512 KiB, no guarda coordenadas y muestrea telemetría dinámica cada cinco
  segundos. Esto evita sondeos nuevos y mantiene acotadas las escrituras: reutiliza los lectores de un segundo que ya
  actualizan la UI y registra inmediatamente solo conexiones, errores y cambios discretos.
- QA limpia del 16/08/2026: `clean`, pruebas unitarias, Lint y `assembleDebug` correctos con target 35. El APK debug
  1.13.2 ocupa 2.405.782 bytes, sin bibliotecas nativas nuevas ni servicio persistente propio.

## Proyectos comparados

| Proyecto | Qué demuestra | Qué se aprovecha | Qué no se copia |
|---|---|---|---|
| [FYT Launcher Mod](https://github.com/vasyl91/FYT-Launcher-Mod) | En ciertas unidades FYT, la APK CANBUS expone un array `DataCanbus.DATA[index]` y callbacks. El propio proyecto advierte que los índices dependen del coche, CANBUS y APK instalada. | Patrón de proveedor desacoplado, callback y diagnóstico del APK instalado. | Sus índices PSA, `PROXY.cmd(...)` y clases `com.syu.*`: no están verificados para JCRK01/CYA y el repositorio no aporta una licencia reutilizable clara. |
| [Android car samples](https://github.com/android/car-samples) | Uso oficial de `CarPropertyManager`, comprobación de propiedades y callbacks en Android Automotive. | Sonda opcional de propiedades estándar y estados “ausente/bloqueado/legible”. | No se presupone que Android 15 convencional incluya `android.car`. |
| [AAIdrive](https://github.com/BimmerGestalt/AAIdrive) | Integración multimedia BMW con ciclo de vida, caché y reducción de carátulas. | Lectura estándar de MediaSession y carátula reducida a lo necesario para la UI. | Su protocolo BMW Connected Apps es para NBT posteriores; no corresponde a un E87 con radio Android. |
| [smartgauges/canbox](https://github.com/smartgauges/canbox) | Las cajas CAN pueden traducir puertas, PDC, iluminación y cámara, pero cada modelo utiliza su propio protocolo. | Separación entre datos del vehículo, transporte de canbox y presentación. | Ninguna trama se transmite desde esta app. |
| [Android OBD Remote](https://github.com/darkspr1te/Android_obd_Remote) | Ejemplo de ingeniería inversa de la UART de una caja CAN externa concreta. | Método de correlación repetible y registro de cambios. | Su protocolo PSA/GPL no es el protocolo Hiworld de esta unidad. |
| [UGasolineras](https://github.com/EMoyaP/UGasolineras) | Radio GPS configurable, Gasóleo A y 7 km predeterminados, precios MITECO y apertura de rutas. | Requisitos funcionales, producto inicial y radio inicial. | No se incorpora WebView/Leaflet, sondeo cada 30 s, WorkManager, datos EV ni motor de rutas: no son necesarios para esta tarjeta. |
| [OpenRadioFM](https://github.com/kapi21/OpenRadioFM) | Publica AIDL y acciones de servicio para un motor de radio Jancar en ciertas unidades. | Confirma que existe una familia Binder Jancar y permite comprobar pasivamente si dos acciones se resuelven localmente. | No se copia el AIDL ni se enlaza el servicio: abrirlo o controlarlo afecta a la fuente de audio y el contrato de esta unidad aún no está confirmado. |
| [Yecon CAN](https://github.com/wert3232/yecon/tree/master/packages/apps/CAN) | Un firmware Android antiguo usa el mismo paquete `com.can.activity` y modelos genéricos de puertas, freno, velocidad y temperatura. | Solo evidencia de posible linaje y vocabulario para buscar dentro del APK físico. | No contiene la clase exacta `CanBusContentProvider`, su contrato no coincide de forma demostrable y no ofrece una licencia reutilizable clara. |

Hiworld documenta que algunas de sus integraciones muestran puertas, climatización, radar y temperatura, y proporciona
un modo de depuración CAN para determinados productos. No publica un SDK Android abierto con el contrato exacto de
JCRK01/CYA; por ello no se ha inventado ninguna API.

## Resultado de la unidad física del 16/08/2026

Cuatro capturas USB aportadas desde la radio coinciden en:

- `Build.MANUFACTURER=rockchip`, modelo/dispositivo/producto `rk3326_r`, placa `rk30sdk`.
- SDK 30, release declarado `13`, cuatro CPU, ABI exclusivamente `armeabi-v7a`, 4.096 MB de RAM y heap de 192 MB.
- Sin característica `android.hardware.type.automotive` y sin clase `android.car.Car`.
- `com.can.activity` instala `com.autoai.canbus.provider.CanBusContentProvider`.
- `com.jancar.services` instala `CarService`, `RadioService`, `ClusterService` y `NavigationService`.
- Ninguna captura pública produjo candidatos para puertas, freno de mano, cinturón o temperatura exterior.

SDK 30 corresponde a Android 11. Por ello la aplicación mantiene `targetSdk 35`, pero todas las rutas ejecutadas en la
radio se validan contra API 30 y no contra la etiqueta comercial. La lista oficial de firmware Hiworld incluye
[`2696-H1H2BM030A-230208.iap`](https://www.hiworldtech.com/page158), compatible nominalmente con la familia BM030A,
pero no publica un SDK Android o esquema del proveedor CAN.

## Decisiones aplicadas

- Sin bibliotecas externas ni frameworks de UI: el DEX y la superficie de ataque se mantienen pequeños.
- Sin sondeo agresivo. Paquetes/componentes se enumeran una sola vez en un hilo de prioridad baja.
- El diagnóstico escucha cambios de ajustes y broadcasts pasivamente; nunca emite órdenes.
- La imagen central se decodifica a una resolución acorde al panel, evitando mantener un bitmap de 1536×1024 completo.
- Las carátulas multimedia se limitan a 256 px y se reutilizan mientras no cambie su generación.
- La detección automática de accesos hace una consulta agrupada y no decodifica iconos durante el arranque.
- Una imagen antigua de 1,16 MB que no usa la aplicación se conserva en `docs/assets/archive`, fuera del APK.
- La tarjeta usa `JsonReader` en streaming: no crea un árbol JSON nacional. Conserva una cobertura móvil de 150 km y
  aplica aparte el radio visible configurado. En Madrid, la caché de Diésel ocupa 143.522 bytes.
- La cobertura nacional se renueva cada 24 horas o al aproximarse a su borde. Las actualizaciones de precio cada diez
  minutos usan `FiltroProvinciaProducto` para un máximo de cuatro provincias próximas, no el listado nacional.
- Medición sin compresión: producto Diésel nacional, 4.302.377 bytes; provincia de Madrid, 322.345 bytes. Para una sola
  provincia la transferencia periódica se reduce un 92,5 %. En límites provinciales el tamaño será la suma de las
  provincias realmente presentes alrededor del GPS.
- La descarga usa la red predeterminada de Android; el código no depende de Android Auto ni de un transporte concreto.
  Bluetooth por sí solo no ofrece Internet: funcionará cuando la radio reciba tethering Bluetooth o cualquier otra red.

## Medición de referencia en emulador Android 15

Estas cifras sirven para detectar regresiones; la radio física tendrá GPU, almacenamiento y firmware distintos.

| Medida | Antes | Después |
|---|---:|---:|
| PSS del proceso tras 10 s | 48.304 KB | 43.373 KB |
| PSS del heap nativo | 20.408 KB | 16.500 KB |
| Arranque frío medido | ~1.935 ms | 1.871–2.049 ms |

La mejora final de PSS observada es de aproximadamente 4,8 MB (10,2 %). El proceso multimedia puede quedar cacheado por
Android cuando se concede acceso a notificaciones; es un servicio pasivo y el sistema puede recuperarlo bajo presión.
El diagnóstico 1.4.0 muestra en la propia radio RAM total/disponible, límite de heap, PSS, tamaño instalado, CPU y ABI.

### Medición de la tarjeta de gasolineras (1.7.0)

Prueba Android 15/API 35, 1280×720, posición GPS de Madrid y datos reales ya cargados:

| Medida | Resultado |
|---|---:|
| Arranque frío final | 1.544 ms |
| PSS estabilizado 1.7–1.9 | 46.312–47.470 KB |
| APK debug instalable 1.9.0 | 2.013.907 bytes |
| Caché móvil de Diésel/150 km | 143.522 bytes |

La cobertura mayor mantiene el PSS alrededor de 46–47 MB, todavía sin WebView, mapa o base de datos. El temporizador
de diez minutos y la lectura multimedia cada dos segundos solo existen mientras la actividad está visible; no hay
servicio propio de actualización en segundo plano.

## Criterio de incorporación de un código propietario

Un código solo pasa a producción cuando el informe de la unidad permite fijar: paquete y versión de APK CANBUS,
clase/servicio o acción, clave/índice, tipo, rango, unidad y al menos tres ciclos de correlación sin falsos positivos.
Incluso entonces, el primer adaptador será exclusivamente de lectura. Cualquier API que permita escribir queda fuera.

## Captura USB y límites de Android 15 (1.10.0)

- Android representa USB y SD como `StorageVolume`, pero una APK normal necesita que el usuario elija una carpeta con
  [`ACTION_OPEN_DOCUMENT_TREE`](https://developer.android.com/training/data-storage/shared/documents-files). El permiso
  URI se conserva con `takePersistableUriPermission`; no se solicitan permisos generales de archivos.
- `StorageVolume.createOpenDocumentTreeIntent()` solo propone el volumen inicial: el selector puede permitir otra
  ubicación y la app debe respetar la elegida. En JCRK01/CYA habrá que confirmar que su proveedor de documentos muestra
  ambas tomas USB.
- Desde Android 4.1, solo aplicaciones privilegiadas pueden leer el `logcat` global con `READ_LOGS`. La APK instalada
  por el usuario guarda sus propias observaciones; no finge que puede interceptar el registro de MCU/CANBUS.
- FYT Launcher Mod demuestra un registrador de índices `DataCanbus.DATA[]`, pero depende de `com.syu` y del APK CANBUS
  FYT instalado. Ese mecanismo no se incorpora a JCRK01/CYA sin evidencia del firmware físico.
- Una prueba completa en Android 15 creó, reescribió en segundo plano y cerró un TXT de 6.229 bytes sin excepciones.
  La escritura se limita a 0,2 Hz, la sesión a diez minutos y los eventos en memoria a 500. El PSS mostrado durante el
  diagnóstico fue aproximadamente 50 MiB y el APK debug 1.10.0 quedó en torno a 2,34 MB.

## Asistente visual y propiedades AAOS verificadas (1.11.0)

- Los nombres `ENV_OUTSIDE_TEMPERATURE`, `GEAR_SELECTION`, `CURRENT_GEAR`, `HVAC_TEMPERATURE_SET`, `HVAC_FAN_SPEED` y
  `HVAC_POWER_ON` se verificaron en el código fuente oficial
  [`VehiclePropertyIds`](https://android.googlesource.com/platform/packages/services/Car/+/refs/heads/main/car-lib/src/android/car/VehiclePropertyIds.java).
  Los permisos declarados se contrastaron con las constantes oficiales de
  [`Car`](https://android.googlesource.com/platform/packages/services/Car/+/refs/heads/main/car-lib/src/android/car/Car.java).
- Las propiedades de clima, puertas, asientos y luces suelen exigir permisos `signature|privileged`; declararlas no
  permite que una APK instalada por el usuario los obtenga. La sonda registra `bloqueada por permiso del fabricante`
  y no intenta elevar privilegios ni escribir propiedades.
- El asistente refresca como máximo cada 750 ms una lista limitada a ocho candidatos y reescribe el TXT cada cinco
  segundos. No añade un nuevo sondeo de MCU/CAN. La prueba 1280×720 con un candidato cambiando mostró 52.493 KB PSS;
  el aumento frente al panel normal se debe principalmente al diálogo y sus vistas temporales.

## Historial persistente de candidatos (1.11.1)

- Se utiliza un único JSON privado con escritura transaccional `AtomicFile`; no se añadió base de datos, servicio,
  dependencia de ejecución ni permiso. Solo se escribe al completar o guardar un paso, no durante cada refresco.
- El límite es 200 candidatos, 20 sesiones, 16 pasos y 16 valores por candidato. Las observaciones débiles se descartan
  y las cadenas se truncan a 240 caracteres, por lo que el almacenamiento y su lectura inicial quedan acotados.
- En Android 15 se verificó una captura fuerte de prueba (`off → on`), la conservación tras finalizar el proceso y
  volver a abrir la app, su inclusión en el informe y el borrado con confirmación. El archivo de una entrada ocupó
  526 bytes; el PSS se estabilizó en 46.392 KB y el APK debug 1.12.0 ocupa 2.380.248 bytes.

## Extracción OEM pasiva y ordenador de a bordo (1.12.0)

- `PackageManager` enumera autoridades, permisos y componentes sin iniciar procesos OEM. El exportador selecciona
  paquetes por evidencias en sus nombres de paquete o componente (`canbus`, `canbox`, `CarService`, `vehicle`,
  `cluster`, `backcar`, `mcu`) y añade los cinco consumidores principales observados.
- Los APK se copian por bloques de 64 KiB en el ejecutor de E/S ya existente. El preparado del inventario también se
  realiza en un hilo de prioridad mínima; no bloquea la UI ni mantiene un servicio residente.
- Un error de lectura o escritura de un APK se registra y no cancela los restantes. Los archivos parciales se eliminan
  cuando el proveedor SAF lo permite. Límites: 100 MB por APK y 250 MB por extracción.
- La documentación oficial de Android define
  [`RANGE_REMAINING`](https://developer.android.com/reference/android/car/VehiclePropertyIds#RANGE_REMAINING) en metros e
  [`INSTANTANEOUS_FUEL_ECONOMY`](https://developer.android.com/reference/android/car/VehiclePropertyIds#INSTANTANEOUS_FUEL_ECONOMY)
  en L/100 km. Solo se leen en AAOS público; no se extrapolan al proveedor Jancar.
- `Location.getSpeed()` solo es válido cuando `hasSpeed()` es verdadero y se expresa en m/s. La app mantiene esa
  comprobación, convierte a km/h y descarta el fallback GPS después de diez segundos. El informe técnico nunca exporta
  las coordenadas del vehículo.

## Identificación y actualización de firmware

- La búsqueda pública de `rk3326_r` conduce al árbol de dispositivo Rockchip Android R/11, que además registra el cambio
  de esa plataforma a API 30. Esto refuerza que la base real de la radio es Android 11 aunque el release se presente
  como 13 o el producto se comercialice como 15:
  [árbol RK3326 Android R](https://gitlab.com/rockchip_android_r/rk/device/rockchip/rk3326).
- `rk3326_r` identifica una plataforma de referencia, no una imagen instalable universal. Una actualización también
  depende de particiones, device tree, pantalla, audio, MCU, periféricos y firma del integrador Jancar/CYA.
- Hiworld publica `2696-H1H2BM030A-230208.iap` para la familia BM030A, pero ese `.iap` corresponde al CANBUS Hiworld,
  no al sistema Android Rockchip. No debe confundirse ni instalarse automáticamente como firmware de radio.
- La exportación completa conserva los APK de actualización y todos los manifests/componentes visibles, además de
  `Build.FINGERPRINT`, `Build.DISPLAY`, parche, kernel, bibliotecas, funciones y archivos públicos de propiedades/OTA.
  No ejecuta `SYSTEM_UPDATE_SETTINGS`; únicamente registra qué actividad lo resolvería.
- El límite para la USB indicada de 20 GB es 16 GB totales, 2 GB por archivo y bloques de E/S de 64 KiB. No se mantienen
  APK en memoria, no se accede a datos de usuario y cada fallo se registra sin cancelar el resto.
