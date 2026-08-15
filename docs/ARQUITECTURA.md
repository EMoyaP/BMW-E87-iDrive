# Arquitectura

## MainActivity
Aplicación normal abrible desde el launcher OEM; no declara la categoría HOME y no se inicia con el arranque del sistema. Los paneles se configuran mediante AlertDialog modales para aprovechar una pantalla de 9".

## GpsSpeedProvider / VehicleDataRepository
Usa `Location.getSpeed()` y convierte m/s a km/h. `VehicleValue` conserva fuente y timestamp, por lo que GPS no se confunde con CAN. El repositorio es el punto único para añadir en el futuro un adaptador JCRK01/CYA confirmado.

## FuelStationProvider

Consume los endpoints públicos MITECO `FiltroProducto/{id}` y
`FiltroProvinciaProducto/{provincia}/{producto}` mediante `HttpURLConnection` y `JsonReader`, sin WebView ni
bibliotecas de red. La primera respuesta nacional se lee secuencialmente y se descarta cada estación situada a más de
150 km del GPS; la ubicación no forma parte de la URL ni del cuerpo de la petición.

El proveedor usa la red predeterminada que decida Android y observa cualquier red con capacidad de Internet. Un
movimiento de 500 m recalcula las dos selecciones sobre la cobertura de 150 km, pero la UI solo admite estaciones del
radio configurado. La cobertura se reconstruye cada 24 horas o cerca de su borde seguro. Cada 10 minutos, únicamente
mientras la actividad está visible, se renuevan las provincias detectadas en los 30 km próximos al GPS (máximo cuatro)
y se fusionan por `IDEESS`. Recuperar red reevalúa la caché sin descargar si todavía está vigente.

El endpoint oficial no admite un círculo GPS arbitrario. El refresco provincial es la aproximación oficial más pequeña
que conserva estaciones de varios municipios y evita omisiones habituales; cerca de límites se consultan todas las
provincias detectadas alrededor del vehículo. No existe Worker, servicio de fondo ni actualización con la app cerrada.

Las distancias mostradas son geodésicas aproximadas para mantener bajo el consumo. Al tocar una fila se delega en
Google Maps o en un manejador `geo:` compatible, que calcula la ruta por carretera.

## AndroidAutomotiveProvider
Sonda opcional de solo lectura cargada por reflexión, de modo que el APK sigue funcionando en Android convencional.
Comprueba la presencia real de `android.car` y de cada propiedad antes de leerla. Puede interpretar propiedades estándar
de luces, freno de estacionamiento, puertas y cinturón del conductor si el fabricante las implementa y concede acceso.
No contiene llamadas de escritura. Una señal bloqueada o ausente se conserva como no disponible y su motivo aparece
en el informe de diagnóstico.

## BluetoothDeviceProvider

Proveedor pasivo basado exclusivamente en `BluetoothAdapter`, `BluetoothProfile` y `AudioManager`. Solicita proxies de
lectura para `HEADSET`/`A2DP` y combina sus dispositivos conectados con las rutas públicas Bluetooth A2DP, SCO y BLE;
esta última vía cubre mejor una radio que actúa como receptor. Publica únicamente el nombre de producto/dispositivo.
Escucha cambios del adaptador, ACL y dispositivos de audio, pero no escanea, empareja, conecta ni modifica Bluetooth.

En Android 12 o posterior exige `BLUETOOTH_CONNECT`, presentado por el sistema como `Dispositivos cercanos`; en Android
11 o anterior usa los permisos Bluetooth normales limitados con `maxSdkVersion=30`. Si el firmware OEM mantiene el
móvil únicamente en su MCU/app propietaria y no lo expone al stack Android, la UI lo declara no detectado/no expuesto.
La tarjeta abre la actividad de teléfono almacenada por `AppRepository`, independiente del proveedor de estado.

## MediaSessionProvider / MediaNotificationListener

La tarjeta multimedia lee `MediaSession` estándar sin enviar botones ni controlar la reproducción y reduce las
carátulas a un máximo de 256 px. Para Radio filtra estrictamente por el paquete OEM asignado: primero busca su sesión
y después el título/texto de una notificación de ese mismo paquete. Si ninguna fuente pública existe, conserva el
estado no disponible y añade la observación al diagnóstico, sin sondear CAN o MCU.

## DiagnosticEngine
Módulo pasivo integrado:
- enumera build, paquetes relevantes, labels, activities, services, receivers, providers y filtros launcher visibles;
- calcula candidatos heurísticos por rol sin fijar package names propietarios;
- escucha un conjunto limitado de broadcasts candidatos sin transmitir ninguno;
- observa cambios de ajustes Android relacionados con iluminación, brillo, noche, freno, puertas y marcha atrás;
- inventaría componentes una vez, fuera del hilo de UI y con prioridad baja;
- informa RAM, PSS, heap, APK, CPU y ABI de la unidad física;
- conserva timestamp, acción y extras acotados;
- permite sesiones manuales de correlación y genera un informe exportable.

## Integración CAN futura
`VehicleDataRepository` es el punto de integración. Cuando una prueba en la unidad identifique un servicio/broadcast real de JCRK01/CYA y se pueda documentar con evidencia, se añade un adaptador específico sin cambiar la UI. Los extras genéricos no se promocionan automáticamente a datos CAN.

La barra inferior consume exclusivamente `VehicleValue`: además del valor muestra su procedencia. Los colores son
semánticos (verde seguro, azul largas, ámbar atención, rojo alerta y gris sin fuente), pero nunca se colorea un estado
como real cuando el proveedor no lo ha confirmado.

## App normal, no HOME
El manifest solo declara `MAIN` + `LAUNCHER`. No hay `HOME`, receiver de boot ni servicio residente: la radio arranca con su launcher OEM y esta app solo se ejecuta cuando el usuario la abre.

## Seguridad
No se abre UART, no se escribe CAN, no se modifica el protocolo Hiworld y no se requiere root.
