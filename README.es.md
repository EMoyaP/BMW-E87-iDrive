# BMW E87 iDrive

**Español** · [🇬🇧 Read in English](README.md)

[![Android](https://img.shields.io/badge/Android-API%2030--35-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![Estado](https://img.shields.io/badge/estado-validaci%C3%B3n%20en%20hardware-orange)](#estado-del-proyecto)
[![Licencia](https://img.shields.io/badge/licencia-PolyForm%20Noncommercial-35618f)](LICENSE)

**BMW E87 iDrive** es una aplicación Android horizontal para una radio Android de 9 pulgadas instalada en un BMW Serie 1 E87. Proporciona una pantalla de conducción clara, inspirada en iDrive, pero se ejecuta como una aplicación normal: el launcher OEM, CANBUS, MCU, cámara, PDC, climatización y las funciones originales siguen funcionando como antes.

Reúne información de conducción obtenida por GPS, precios españoles de gasolineras cercanas, límites de velocidad guardados localmente, integración multimedia estándar de Android y herramientas de diagnóstico de solo lectura para el entorno JCRK01/CYA/Hiworld identificado en la radio de referencia.

> **Proyecto independiente, no oficial y no comercial.** BMW, iDrive, el emblema BMW y las denominaciones de modelos pertenecen a BMW AG o a sus entidades vinculadas. Se usan únicamente para identificar el vehículo y el contexto técnico; no implican patrocinio ni aprobación. Consulta [NOTICE.md](NOTICE.md) y [LICENSE-ASSETS.md](LICENSE-ASSETS.md).

## Panel principal

![Panel principal: ordenador de a bordo, velocímetro GPS, límite local, radar fijo DGT cercano, gasolineras y accesos OEM](docs/screenshots/bmw-e87-ui-v1.16.0-dgt-radars-preview.png)

*Simulación documental en emulador: autonomía 700 km, consumo medio 6,0 l/100 km, exterior 34 °C y radar fijo cercano. La radio solo mostrará los valores que publiquen sus fuentes reales.*

El panel está diseñado para pantallas de coche 1280×720 / 16:9. Incluye vehículo central, ordenador de a bordo dinámico, tarjetas de aplicaciones OEM configurables y una fila contextual de estado del vehículo.

### Qué muestra

- **Velocímetro GPS.** GPS es la fuente de velocidad validada en la radio física. La esfera E87 de 0–260 km/h rellena progresivamente solo su aro exterior. Un límite local verificado se marca en naranja y, al superarlo, la cifra y el aro pasan a naranja; sin límite local verificado, ambos se mantienen en verde.
- **Límite de la vía local.** La señal se consulta en una base SQLite local, nunca se descarga durante la conducción. Un círculo rojo solo representa un `maxspeed` explícito; el cuadrado azul es una velocidad aconsejada conservadora por tipo de vía cuando la zona GPS se ha actualizado, nunca un límite legal ni un disparador naranja del velocímetro. Las señales físicas y el cuadro del coche siguen siendo la referencia legal.
- **Ordenador de a bordo dinámico.** Autonomía, consumo medio, temperatura exterior, climatización y otros valores aparecen solo cuando la radio publica una lectura plausible. Si un dato no está disponible se oculta.
- **Aviso de radares fijos y de tramo.** La APK incorpora localmente el inventario nacional de la DGT. El panel aparece únicamente ante un radar fijo/tramo próximo mientras disminuye la distancia y desaparece si el coche se aleja. Los controles móviles se excluyen expresamente. La locución opcional solo se emite una vez para un radar **fijo** DGT y no toma el foco de audio. El valor circular es el límite verificado de la **vía** en el mapa local, no un límite de radar inventado.
- **Zonas de vigilancia INVIVE.** Una tarjeta propia muestra `ZONA DE VIGILANCIA` al aproximarse o circular por un tramo oficial de intensificación. Nunca se representa como radar, no activa la locución de radar fijo y no aporta un límite legal; la señal de velocidad continúa procediendo exclusivamente del mapa local.

![Zona INVIVE independiente de radares y del límite local](docs/screenshots/bmw-e87-ui-v1.21.0-invive.png)
- **Gasolineras.** La más barata y la más cercana se calculan respecto al GPS de la radio para el combustible seleccionado. Al pulsar un resultado se navega mediante una aplicación de mapas compatible.
- **Tarjetas OEM.** Radio, Android Auto, teléfono y aplicaciones abren la aplicación OEM asignada. Los metadatos solo aparecen si Android o un servicio OEM los publica realmente.

## Límite de seguridad

| La aplicación hace | La aplicación nunca hace |
|---|---|
| Se ejecuta desde el launcher OEM como app normal | Sustituir el launcher o registrarse como `HOME` |
| Lee GPS, sesiones multimedia Android y getters OEM pasivos verificados | Transmitir CAN, escribir UART o abrir puertos serie |
| Muestra datos expuestos por la unidad y validados | Inventar tramas o mapeos JCRK01/CYA/Hiworld |
| Abre aplicaciones OEM existentes e intents estándar de mapas | Cambiar MCU, Hiworld, fábrica o definición de sockets |
| Exporta diagnóstico a una carpeta USB autorizada | Copiar datos privados, particiones o firmware indiscriminadamente |

Las radios Android aftermarket no comparten un protocolo CAN universal. Un paquete o una clase observados en otra unidad no se convierten en un comando para este coche.

## Funcionalidades

### Conducción e información del vehículo

- GPS como única velocidad visible tras rechazar muestras CAN crudas discordantes detectadas en esta radio.
- Escala real del E87: 0, 20, 40 … 260 km/h, con aro de progreso proporcional y legible. El límite local verificado recibe su propia marca/etiqueta naranja, también para valores como 30, 50, 70, 90, 100 o 120 km/h.
- Autonomía y consumo medio cuando el cuadro OEM publica valores válidos.
- Temperatura exterior mediante HVAC solo cuando su valor es plausible; si no, se oculta.
- Marcha grande dentro de la esfera únicamente si se recibe una marcha actual verificada; no reserva hueco vacío.
- Fila inferior contextual para luces, marcha atrás, freno de mano, cinturón y puertas. Los valores desconocidos o sentinelas se descartan para evitar avisos falsos.
- Botón de mantenimiento verde cuando OEM confirma que no hay avisos, naranja cuando publica uno activo y neutro cuando no existe una fuente verificada.

### Gasolineras, GPS y límites sin conexión

- Diésel y radio de 7 km como valores predeterminados, ambos configurables.
- Precios procedentes del servicio oficial español, filtrados localmente y guardados en una caché de 150 km alrededor del vehículo.
- Los precios próximos se actualizan cada diez minutos mientras la app está visible y Android publica una red IP.
- La APK incorpora un mapa completo de carretera de **Alicante**: clases de **112.709** vías transitables, límites `maxspeed` genéricos y 89 geometrías que publican `maxspeed:forward/backward`. La vía se identifica primero por posición, rumbo y continuidad; después se aplica el dato más fiable de esa misma vía y sentido. Murcia, Valencia y Albacete mantienen sus semillas compactas.
- La primera ejecución también dispone sin red de la instantánea oficial de precios de diésel de Alicante incluida en la APK, con su fecha de origen visible. Al recuperar Internet, la caché se sustituye por el endpoint oficial del Ministerio.
- La APK incorpora además el inventario nacional DATEX II de la DGT para radares fijos y de tramo (unos 2 MB antes de comprimir). Durante la marcha la coincidencia se realiza por completo en local; no consulta un servicio de radares en cada posición GPS.
- También incluye el inventario nacional DATEX II de **tramos INVIVE**. Esta base señala zonas de intensificación de vigilancia, no radares ni límites de velocidad. La detección cruza la carretera oficial con la referencia y geometría OSM local para reducir coincidencias con vías paralelas.
- La búsqueda de la vía próxima es local y se ejecuta con cada fix GPS de conducción (normalmente alrededor de una vez por segundo); no realiza una consulta de red al cambiar el límite.
- OSM mantiene el alcance provincial de Alicante y su ventana anti-bloqueo. Las capas oficiales de
  DGT (límites TN-ITS, radares e INVIVE) se descargan completas a escala nacional y se consultan
  después en local. Una descarga correcta de cada fuente no se repite durante 24 horas; los precios
  conservan su actualización automática cada 10 minutos.
- El botón **Actualizar todo** abre un modal secuencial con una barra por fuente y una fase final de
  consolidación. También puedes actualizar OSM Alicante, gasolineras, radares, límites DGT o INVIVE
  por separado. Cada fila conserva la fecha y hora de su última descarga correcta.

### Herramientas, diagnóstico y actualizaciones

![Menú de herramientas: Debug/USB, Permisos y Actualizaciones independientes](docs/screenshots/bmw-e87-ui-v1.16.0-tools.png)

La rueda dentada inferior derecha abre tres herramientas separadas:

1. **Debug / USB** — diagnóstico pasivo, pruebas guiadas de correlación, exportación USB y registros de ejecución.
2. **Permisos** — ubicación, dispositivos Bluetooth cercanos y acceso multimedia Android.
3. **Actualizaciones** — un botón para actualizar todo y botones unitarios para OSM Alicante,
   gasolineras, radares DGT, límites DGT e INVIVE. OSM es la única descarga parcial; las capas
   oficiales se guardan completas. La importación y consolidación son locales y solo sustituyen
   las cachés propias de iDrive.

El panel principal muestra provincia, fecha y hora de la última actualización correcta de la base de límites. Antes de instalar una descarga identifica la base local incluida.

![Panel de actualizaciones: límites provinciales, radares fijos/tramo DGT y precios de gasolineras](docs/screenshots/bmw-e87-ui-v1.16.0-updates.png)

USB DEBUG incluye asistentes para puertas, luces, freno de mano, cinturones, marcha atrás/PDC, climatización y pruebas personalizadas. El registro conserva valor bruto, interpretación, fuente, hora y transición anterior → nueva para todas las fuentes disponibles. Un selector opcional de privacidad permite añadir coordenadas GPS precisas al log para diagnosticar límites de vía; está desactivado por defecto, permanece activo hasta desmarcarlo y queda indicado en cada informe exportado. También puede exportar un inventario OEM y diagnóstico limitado a una carpeta USB elegida por el usuario. Un candidato verde significa *candidato fuerte pendiente de validar*, no un código CAN propietario confirmado.

![Vista de correlación de candidatos de USB DEBUG](docs/screenshots/bmw-e87-usb-wizard-live-strong-v1.11.0.png)

Ninguna función de diagnóstico transmite tráfico CAN, escribe UART, modifica ajustes OEM ni inicia un servicio de vehículo únicamente para interrogarlo.

### Permisos y conectividad

![Panel de permisos: estado actual y explicación de red Android](docs/screenshots/bmw-e87-ui-v1.15.2-permissions.png)

- **Ubicación** habilita velocidad GPS, límites locales y distancias a gasolineras.
- **Dispositivos cercanos / Bluetooth** se solicita solo cuando Android lo exige para identificar un terminal Bluetooth mediante APIs públicas.
- **Acceso multimedia** permite a Android conceder visibilidad de sesiones o notificaciones estándar. El título y los controles solo aparecen si la fuente publica una sesión controlable.
- **Internet** es un permiso normal concedido durante la instalación; no existe un diálogo posterior para solicitarlo.
- El tethering Bluetooth funciona solo si el firmware de la radio crea y publica una interfaz IP. Emparejar un teléfono, activar Android Auto o activar tethering en el móvil no garantiza que una app normal de la radio reciba Internet.

### Multimedia, radio y teléfono

La cadena multimedia segura es: `MediaSession` de Android, broadcast pasivo verificado de SpeedPlay, puente multimedia OEM de solo lectura y, como último recurso, acceso a notificaciones cuando Android lo permite. Los metadatos y controles se habilitan exclusivamente si la fuente expone una sesión estándar controlable.

El mismo criterio se aplica a FM y Bluetooth: frecuencia, RDS y nombre del terminal se muestran solo si un servicio Android/OEM los publica. iDrive no adivina la emisora ni afirma una conexión telefónica sin una lectura confirmada.

### Fuente INVIVE

INVIVE se integra desde el [conjunto oficial del NAP de la DGT](https://nap.dgt.es/en/dataset/tramos-invive), con atribución y fecha de actualización visibles en diagnóstico. La aplicación no transforma estos tramos en radares fijos ni deduce de ellos una velocidad máxima.

## Instalación y primer uso

1. Descarga la APK adjunta a la última [release de GitHub](../../releases).
2. Cópiala a una memoria USB, instálala en la radio y abre **iDrive** desde el launcher OEM normal.
3. Pulsa la rueda dentada, elige **Permisos** y concede ubicación precisa. Bluetooth cercano y multimedia son opcionales.
4. Espera a que GPS obtenga posición. Velocidad y distancias empiezan a usarla; los límites ya cuentan con la base local incluida.
5. Mantén pulsada una tarjeta para elegir su aplicación OEM. Una pulsación corta abre la asignada.
6. Antes de probar señales del vehículo, selecciona una carpeta USB en **Debug / USB** y exporta una lectura base.

Lee [Pruebas en la radio](docs/PRUEBAS_EN_LA_RADIO.md) antes de realizar pruebas físicas con el vehículo.

## Hardware de referencia

| Elemento | Referencia observada durante la validación |
|---|---|
| Vehículo | BMW 118d E87 LCI, 2010, automático |
| Radio Android | 9 pulgadas, 1280×720, 4/64 GB; comercializada como Android 15 |
| Plataforma efectiva | Rockchip `rk3326_r` / `rk30sdk`, API 30, release declarado 13, 4 GB de RAM, `armeabi-v7a` |
| Software OEM | Jancar IVI Services, `CanBusContentProvider`, `CarService`, `NavigationService`, Autochips |
| MCU | MM40-0-2025.07.23_15:06 |
| Adaptador CAN | Hiworld BM03.10 / familia H1H2BM030A; entorno JCRK01/CYA |
| Perfil configurado | Hiworld BMW X1 2009–2015 All |

Estos datos describen una radio probada; no garantizan que otra radio aparentemente similar sea compatible.

## Arquitectura

| Componente | Responsabilidad |
|---|---|
| `MainActivity` | Interfaz, navegación interna y ciclo de vida visible |
| `VehicleDataRepository` | Agregación prudente y prioridad de fuentes |
| `GpsSpeedProvider` | Posición y velocidad GPS validada |
| `SpeedLimitRepository` | Límites SQLite locales y actualizaciones OSM provinciales |
| `DgtSpeedRepository` | Delta semanal nacional de límites DGT, historial y coincidencia local por vía/sentido |
| `RadarRepository` | Caché local DGT de radares fijos/tramo y actualizaciones provinciales cada 24 horas |
| `InviveRepository` | Caché nacional DGT de zonas de vigilancia INVIVE, separada de radares y límites |
| `FuelStationProvider` | Precios oficiales, caché local y selección por distancia |
| `JancarCarProvider` | Getters de solo lectura verificados en APK OEM exportadas |
| `MediaSessionProvider` | Metadatos y acciones multimedia estándar cuando se exponen |
| `BluetoothDeviceProvider` / `JancarBluetoothProvider` | Estado Bluetooth público y pasivo verificado |
| `DiagnosticEngine` / `UsbDiagnosticRecorder` | Inspección pasiva, logs y exportación USB controlada |

El proyecto está escrito en Java con APIs del framework Android. No usa dependencias de ejecución de terceros, WebView ni código nativo.

## Privacidad y fuentes de datos

- Precios de carburantes: servicio oficial español de [Precios de carburantes](https://sedeaplicaciones.minetur.gob.es/ServiciosRESTCarburantes/PreciosCarburantes/help).
- Límites: datos `maxspeed` de OpenStreetMap mediante servicios Overpass públicos; consulta [NOTICE.md](NOTICE.md) para atribución y condiciones.
- Radares fijos y de tramo: [publicación DATEX II oficial de la DGT](https://infocar.dgt.es/datex2/dgt/PredefinedLocationsPublication/radares/content.xml), guardada localmente. Los controles móviles no forman parte de esta función.
- Límites oficiales: [feed TN-ITS semanal de límites de velocidad de la DGT](https://infocar.dgt.es/tnits/limitesVelocidad.xml). La APK instala la fotografía nacional disponible en la compilación y las actualizaciones incorporan sus cambios en SQLite sin borrar el historial.
- Zonas de vigilancia: [dataset INVIVE del NAP/DGT](https://nap.dgt.es/en/dataset/tramos-invive), guardado como zona de vigilancia y nunca como radar fijo.
- Las coordenadas del coche no se envían al servicio de precios. El filtrado y las distancias se calculan en la radio. Una app de mapas recibe la coordenada destino solo después de pulsar una gasolinera.
- La app usa exclusivamente la red IP que Android publique: Wi‑Fi, Ethernet o Bluetooth PAN si el firmware de la radio realmente la crea.

## Estado del proyecto

Versión actual: **1.24.2**.

La APK se ha instalado y probado como aplicación normal en la radio de referencia. Funcionan dentro de su límite verificado la velocidad GPS, gasolineras, límites locales, coincidencia local de radares fijos/tramo DGT, zonas INVIVE, accesos OEM estáticos, diagnóstico USB y valores concretos del ordenador de a bordo. La versión 1.24.2 añade un lector resistente de la semilla offline de radares y una herramienta repetible de QA para rutas GeoJSON/OSRM. La fuente DGT tiene prioridad solo cuando coincide por vía, geometría y sentido; después se usa OSM y, como último respaldo visual, la recomendación azul por clase. El QA en emulador valida GPS, matching local, panel de radar y semilla de gasolineras; CAN, Android Auto, Bluetooth PAN y voz siguen requiriendo validación en la radio.

Aspectos que siguen dependiendo del hardware y no se presentan como universales:

- el significado fino de luces, puertas, cinturón y freno requiere correlación repetida en el vehículo exacto;
- Android Auto/SpeedPlay puede no exponer una sesión multimedia estándar, por lo que título y controles de Spotify pueden seguir sin estar disponibles;
- Internet por Bluetooth PAN depende de que el firmware de la radio publique una interfaz IP;
- RDS, nombre Bluetooth, distancias PDC, RPM y marcha actual requieren un valor vivo y plausible del servicio OEM correspondiente;
- la APK distribuida está firmada para pruebas; para producción sigue siendo necesaria una clave de firma release permanente.

## Compilar desde código fuente

Requisitos: JDK 17 y Android SDK 35.

```powershell
.\gradlew.bat clean lintDebug testDebugUnitTest assembleDebug
```

La APK debug se genera en `app/build/outputs/apk/debug/app-debug.apk`.

Consulta [CHANGELOG.md](CHANGELOG.md), [CONTRIBUTING.md](CONTRIBUTING.md) y [SECURITY.md](SECURITY.md) para historial de versiones, contribuciones y comunicación responsable.

## Licencia y marcas

- Código: [PolyForm Noncommercial License 1.0.0](LICENSE).
- Documentación y recursos originales: [CC BY-NC-SA 4.0](LICENSE-ASSETS.md).
- Marcas de terceros, atribuciones y exclusiones: [NOTICE.md](NOTICE.md).

La licencia permite estudio, modificación y uso no comercial. **No** es una licencia open source aprobada por la OSI y no concede derechos sobre marcas, emblemas o diseños de BMW ni de terceros.

Copyright 2026 Eugenio Moya Pérez.
