# Investigación de proyectos públicos y rendimiento

Fecha de revisión: 2026-08-15.

## Proyectos comparados

| Proyecto | Qué demuestra | Qué se aprovecha | Qué no se copia |
|---|---|---|---|
| [FYT Launcher Mod](https://github.com/vasyl91/FYT-Launcher-Mod) | En ciertas unidades FYT, la APK CANBUS expone un array `DataCanbus.DATA[index]` y callbacks. El propio proyecto advierte que los índices dependen del coche, CANBUS y APK instalada. | Patrón de proveedor desacoplado, callback y diagnóstico del APK instalado. | Sus índices PSA, `PROXY.cmd(...)` y clases `com.syu.*`: no están verificados para JCRK01/CYA y el repositorio no aporta una licencia reutilizable clara. |
| [Android car samples](https://github.com/android/car-samples) | Uso oficial de `CarPropertyManager`, comprobación de propiedades y callbacks en Android Automotive. | Sonda opcional de propiedades estándar y estados “ausente/bloqueado/legible”. | No se presupone que Android 15 convencional incluya `android.car`. |
| [AAIdrive](https://github.com/BimmerGestalt/AAIdrive) | Integración multimedia BMW con ciclo de vida, caché y reducción de carátulas. | Lectura estándar de MediaSession y carátula reducida a lo necesario para la UI. | Su protocolo BMW Connected Apps es para NBT posteriores; no corresponde a un E87 con radio Android. |
| [smartgauges/canbox](https://github.com/smartgauges/canbox) | Las cajas CAN pueden traducir puertas, PDC, iluminación y cámara, pero cada modelo utiliza su propio protocolo. | Separación entre datos del vehículo, transporte de canbox y presentación. | Ninguna trama se transmite desde esta app. |
| [Android OBD Remote](https://github.com/darkspr1te/Android_obd_Remote) | Ejemplo de ingeniería inversa de la UART de una caja CAN externa concreta. | Método de correlación repetible y registro de cambios. | Su protocolo PSA/GPL no es el protocolo Hiworld de esta unidad. |
| [UGasolineras](https://github.com/EMoyaP/UGasolineras) | Radio GPS configurable, Gasóleo A y 7 km predeterminados, precios MITECO y apertura de rutas. | Requisitos funcionales, producto inicial y radio inicial. | No se incorpora WebView/Leaflet, sondeo cada 30 s, WorkManager, datos EV ni motor de rutas: no son necesarios para esta tarjeta. |

Hiworld documenta que algunas de sus integraciones muestran puertas, climatización, radar y temperatura, y proporciona
un modo de depuración CAN para determinados productos. No publica un SDK Android abierto con el contrato exacto de
JCRK01/CYA; por ello no se ha inventado ninguna API.

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
