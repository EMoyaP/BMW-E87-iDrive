# Pruebas en la radio JCRK01/CYA

Todas las pruebas deben hacerse con el vehículo detenido, ventilado y con otra persona manejando los mandos. La app
solo observa Android: no escribe CAN/UART ni cambia ajustes de Hiworld/MCU.

## 1. Comprobar la plataforma

1. Abrir la app desde el launcher OEM.
2. Tocar `CAN / MCU · Diagnóstico` en la barra inferior.
3. Exportar el informe antes de accionar nada.
4. Revisar `PLATAFORMA ANDROID AUTOMOTIVE`:
   - `android.car.Car = no incluido`: es Android convencional; las señales dependerán del bridge propietario.
   - `no implementada`: AAOS existe, pero esa propiedad no está expuesta.
   - `bloqueada por permiso del fabricante`: la propiedad existe, pero esta APK instalada por el usuario no puede leerla.
   - `legible`: la barra debe cambiar con el estado real.
5. Copiar también `RECURSOS Y RENDIMIENTO DE ESTA UNIDAD`; permite confirmar RAM real, memoria disponible, ABI, PSS
   y tamaño instalado sin depender de la ficha comercial.

Antes de probar señales, fotografiar `Acerca del dispositivo de coche` y las páginas de versión de fábrica donde
aparezcan Android/build, MCU, CANBUS/Hiworld y versión de la APK CAN. Ocultar número de serie, IMEI y direcciones MAC.

## 2. Luces

1. Iniciar correlación `Luces: apagadas / cruce / largas`.
2. Esperar 3 segundos con luces apagadas.
3. Encender posición/diurnas, cruce, largas, antiniebla y emergencia, dejando 3 segundos entre estados.
4. Detener y exportar.
5. Si AAOS es legible, comprobar que la tarjeta muestra respectivamente `Apagadas`, `Diurnas`, `Cruce`, `Largas`,
   `Antiniebla…` o `Emergencia`. Las largas deben verse azules; cruce/diurnas, verdes; antiniebla/emergencia, ámbar.
6. Repetir observando si el sistema cambia brillo o modo noche. El diagnóstico registrará cambios relevantes de
   `Settings.System/Global`; esto puede revelar el puente de iluminación aunque no exista un broadcast CAN visible.

### Velocidad y prioridad de fuente

1. No es necesario ni seguro superar 120 km/h para probar el cambio de color; esa transición se valida con datos
   sintéticos en emulador, no conduciendo.
2. En la radio, exportar el diagnóstico tras recibir velocidad y buscar `vehicle.speed.source`. Si indica
   `ANDROID_AUTOMOTIVE`, esa lectura de vehículo tiene prioridad. Si no existe o está bloqueada, la UI debe indicar GPS
   como fallback y nunca presentar una lectura antigua durante más de diez segundos.
3. Si el sistema OEM muestra velocidad pero la APK solo obtiene GPS, realizar una captura USB `Otra señal` con ayuda de
   un acompañante y sin manejar la pantalla durante la marcha. No se asignará ningún código hasta repetirlo y confirmar
   su escala y unidad.

## 3. Freno de mano

1. Iniciar correlación `Freno de mano`.
2. Liberarlo y activarlo dos veces, esperando 3 segundos en cada posición.
3. Detener y exportar.
4. Si la propiedad es legible, debe alternar `Liberado` (verde) y `Activado` (rojo).
5. No desactivar la opción OEM `Detección de freno de mano`: se prueba el estado actual sin alterar el sistema.

## 4. Puerta del conductor

1. Iniciar correlación `Puerta del conductor`.
2. Abrir y cerrar únicamente esa puerta dos veces.
3. Repetir después con cada puerta y el portón, una por una.
4. Detener y exportar. Si AAOS es legible, la tarjeta muestra el número de puertas abiertas.

## 5. Cinturón del conductor

1. Sentado el conductor, iniciar correlación `Cinturón del conductor`.
2. Abrochar y desabrochar dos veces, con pausas de 3 segundos.
3. Detener y exportar. La app solo usa el área de conductor declarada por AAOS; no deduce el conductor por el lado del
   volante ni mezcla cinturones de asientos vacíos.

## 6. Criterio para aceptar una señal propietaria

No basta con que aparezca un número parecido. Para añadir un mapeo JCRK01/CYA debe repetirse el mismo cambio en al
menos tres ciclos, mantenerse estable al probar otra función y quedar identificado el paquete, acción, clave, tipo,
valores y unidad. Hasta entonces la UI seguirá mostrando `No disponible`.

## 7. Seguridad OEM

Durante todas las pruebas verificar por separado que cámara de marcha atrás, PDC, climatización, audio OEM y mandos
del volante continúan funcionando igual con la app abierta y cerrada. Si cualquiera cambia, cerrar la app y conservar
el informe para investigar antes de continuar.

No entrar ni modificar `Función de definición de socket`, protocolo CAN, modelo de coche ni parámetros de fábrica.

## 8. GPS, Internet y gasolineras

1. Conceder ubicación a la app y esperar a que la radio obtenga una posición GPS estable.
2. Dar Internet a la radio con cualquiera de sus mecanismos disponibles: Wi-Fi/hotspot del teléfono, SIM, Ethernet,
   tethering USB o tethering Bluetooth. Estar enlazado solo para llamadas/audio Bluetooth no equivale a tener Internet.
   Para probar Bluetooth, activar **Compartir Internet por Bluetooth** en el teléfono y, si el menú OEM lo ofrece,
   habilitar el perfil PAN/red en la radio. El pie debe mostrar `BT PAN`; si Android no crea una red validada, la app
   no puede forzar ese perfil ni reutilizar los datos internos del enlace Android Auto.
3. Abrir la app y comprobar que `Gasolineras · Diésel` deja de mostrar `Esperando ubicación`/`Consultando` y presenta
   `Más barata` y `Más cercana`. El pie debe indicar `7 km · MITECO` en la primera carga correcta.
4. Contrastar nombre y precio con el portal oficial. La distancia de la tarjeta es aproximada en línea recta; la ruta
   y distancia por carretera las determina Maps después de tocar la fila.
5. Tocar cada resultado. Google Maps debe abrirse con esa coordenada lista para navegar; si Maps no está instalado,
   debe aparecer otra aplicación compatible o el aviso de que no hay mapas.
   En este firmware el destino se abre en Maps local: el APK exportado de SpeedPlay no expone un puente verificable
   para enviar el destino al Google Maps proyectado por Android Auto.
6. Mantener pulsada la tarjeta, cambiar primero a 3 km y después a 10 km, y confirmar que título, resultados y pie se
   actualizan respecto de la posición actual. Restaurar Diésel y 7 km. Tocar el pie para probar la actualización manual.
7. Con datos ya visibles, quitar Internet y cerrar/abrir la app: los últimos valores deben mantenerse. Tocar el pie
   para forzar una actualización; al fallar debe indicar `CACHÉ`. Al recuperar conexión, la actualización debe
   reanudarse sin reiniciar la radio.

### 8.1. Actualizaciones de límites de velocidad

1. En la pantalla principal, tocar la llave inglesa de la esquina inferior derecha.
2. Comprobar que aparecen tres botones independientes: `DEBUG / USB`, `PERMISOS` y `ACTUALIZACIONES`.
3. Entrar en `ACTUALIZACIONES` y verificar los botones de límites y precios. El de límites requiere Wi-Fi.
4. Elegir `Zona GPS actual · 5 km` para una descarga pequeña o una sola provincia (`Alicante`, `Murcia`, `Valencia`
   o `Albacete`). No se debe iniciar una descarga nacional.
5. Esperar el mensaje de resultado y comprobar en el velocímetro la etiqueta `OSM <provincia>` cuando exista un tramo
   cercano. Desactivar Wi-Fi y confirmar que la lectura local continúa funcionando.

## 9. Validación HVAC, PDC y marcha — APK 1.14.0

1. Instalar la APK 1.14.0, abrirla con el coche detenido y esperar diez segundos. En `USB DEBUG` →
   `DATOS CAN EN VIVO · FUENTES` seleccionar `CAN OEM` y activar `Mostrar 0`.
2. Fotografiar `HVACINFO` con el clima apagado y después ajustar, uno por vez, temperatura izquierda, temperatura
   derecha y ventilador. Anotar la temperatura exterior que muestra el cuadro. Si `outsideTemp` coincide, la tarjeta
   debe mostrarla; sentinelas o cifras fuera de −60…100 °C no deben llegar a la UI.
3. Con otra persona ayudando, engranar P, R, N y D sin mover el vehículo. La escala inferior debe resaltar cada letra;
   al engranar R debe aparecer el aviso de marcha atrás sin alterar cámara ni PDC OEM.
4. Con R engranada y freno pisado, acercar un obstáculo de forma segura a cada zona PDC. Fotografiar `RADARINFO / PDC`
   en varias distancias. No asumir centímetros hasta comparar los valores y repeticiones exportados.
5. Pulsar `EXPORTAR` y copiar el nuevo `e87_runtime_session_*.log.txt`. Debe contener líneas `CAN OEM CALLBACK` para
   los eventos recibidos y `CAN OEM GETTERS` con Dashboard, Cabin, Light, HVAC y Radar en la misma muestra.
6. Confirmar de nuevo que velocidad GPS, cámara, PDC, climatización, audio OEM y mandos del volante funcionan igual con
   la app abierta y cerrada.
8. Mover el vehículo más de 500 m y comprobar que cambian las distancias. Al salir suficientemente de la zona cacheada
   los resultados deben cambiar de inmediato desde la cobertura de 150 km. Mantener la app abierta más de diez minutos
   y verificar que el pie actualiza su hora sin perder los resultados. Una nueva carga nacional solo debe ser necesaria
   cada 24 horas o al aproximarse al borde de la cobertura.
9. Vigilar durante 15 minutos que audio, Android Auto, cámara, PDC, climatización y mandos del volante no cambian. Esta
   función solo usa GPS y red de Android y no accede a CAN/MCU.

## 9. Teléfono y Bluetooth

1. Conceder `Dispositivos cercanos` cuando Android lo solicite. No se requiere permiso de escaneo.
2. Sin móvil conectado, confirmar que la tarjeta indica `Ningún terminal conectado` y no muestra `Phone` como nombre.
3. Conectar el teléfono desde el menú OEM y volver a la app. Si el firmware publica manos libres/A2DP estándar, debe
   aparecer el nombre configurado en el móvil y el texto `Conectado por Bluetooth`.
4. Tocar la tarjeta y comprobar que abre el menú de teléfono con contactos/teclado de la radio. Si abre BT Music u otra
   app, volver, mantener pulsada la tarjeta y seleccionar manualmente la actividad de Teléfono/Bluetooth correcta.
5. Realizar dos ciclos conectar/desconectar y dos ciclos abrir teléfono/volver. El estado debe cambiar sin cerrar la app.
6. Si un móvil OEM conectado sigue apareciendo como no detectado, conservar ese texto: indica que el firmware no
   publica sus perfiles en Android. Anotar paquete y versión de la aplicación Bluetooth para una futura adaptación de
   lectura específica; no asumir que un dispositivo emparejado está actualmente conectado.

## 10. Radio y emisora actual

1. Abrir `Ajustes > Acceso a contenido multimedia` y autorizar iDrive.
2. Mantener pulsada la tarjeta Radio y asignar la aplicación OEM real si la detección automática no acierta.
3. Abrir la radio OEM, sintonizar una emisora con RDS y volver a iDrive.
4. Esperar hasta dos segundos: debe aparecer el título, frecuencia o texto que la aplicación OEM publique.
5. Cambiar de emisora y comprobar que la tarjeta se actualiza; tocarla debe regresar a la radio OEM.
6. Si aparece `Emisora no expuesta`, exportar el diagnóstico con la radio reproduciendo. Ese resultado significa que
   no existe una MediaSession/notificación pública utilizable; no deducir índices CAN ni comandos MCU.

## 11. Captura USB DEBUG

1. Formatear una memoria USB en un formato que la radio ya pueda leer y crear en ella una carpeta `IDRIVE_DEBUG`.
2. Con el coche inmovilizado, conectar la USB antes de abrir iDrive. Entrar en `CAN / MCU · Diagnóstico` y pulsar
   `USB DEBUG`.
3. La primera vez, pulsar `SELECCIONAR USB`, elegir `IDRIVE_DEBUG` en el selector de Android y confirmar `USAR ESTA
   CARPETA`. Esta autorización queda guardada para reinicios posteriores mientras el identificador del volumen siga
   siendo válido.
4. El asistente visual se abre automáticamente después de autorizar la carpeta. En usos posteriores, entrar en
   `USB DEBUG > Abrir asistente visual` y elegir una prueba. La pantalla inicial indica el estado exacto que debe quedar
   preparado antes de tomar la línea base.
5. Pulsar `EMPEZAR` y seguir una sola instrucción cada vez. El botón de avance se habilita tras tres segundos. La zona
   inferior muestra en directo hasta seis candidatos con valor anterior/actual, fuente y clasificación:
   - **FUERTE, verde:** evidencia útil por fuente, semántica o repetición; todavía no es un código confirmado.
   - **MEDIO, ámbar:** cambio plausible que conviene repetir.
   - **DÉBIL, gris:** evento de contexto o ruido posiblemente no relacionado.
6. Si se accionó el mando antes de leer la instrucción, pulsar `REPETIR PASO`: se descarta ese intento y se toma una
   línea base nueva. Usar `OMITIR` para antiniebla, PDC u otra maniobra no disponible. `CAPTURADO · SIGUIENTE` conserva
   la evidencia y muestra la siguiente acción.
7. El plan de puertas recorre conductor, acompañante, traseras izquierda/derecha y portón, abriendo y cerrando cada
   elemento por separado. El plan de luces incluye posición, cruce, largas, ambos intermitentes, antiniebla opcional,
   emergencia y restauración. Freno y cinturón repiten el ciclo para mejorar la correlación.
8. Para PDC/marcha atrás es obligatorio un ayudante, coche inmóvil, pie en el freno y freno de mano aplicado. Si se
   prueba distancia, el ayudante acerca manualmente un objeto amplio al sensor; el coche no se desplaza. Confirmar al
   final que cámara, radar y pitidos OEM siguen funcionando normalmente.
9. La captura se actualiza cada cinco segundos y finaliza automáticamente a los diez minutos. `DETENER Y GUARDAR`
   conserva los pasos realizados aunque no se haya terminado el plan.
10. Al completar un paso no omitido, sus candidatos medios y fuertes quedan también en el registro privado de la app.
   Entrar en `USB DEBUG > Ver candidatos guardados` para comprobar sus valores y estado. Una sesión interrumpida con
   `DETENER Y GUARDAR` conserva la evidencia parcial; `OMITIR` descarta la evidencia incidental del paso.
11. Repetir la misma prueba en tres sesiones distintas. La misma señal fuerte debe pasar de `OBSERVADO` a `REPETIDO`
   y después a `LISTO PARA REVISAR`; nunca debe aparecer como confirmada ni cambiar por sí sola la UI del vehículo.
12. Expulsar la USB desde Android si el firmware ofrece esa opción y copiar todos los `e87_*.txt` y
   `e87_runtime_session_*.log` al ordenador. El LOG se sobrescribe al iniciar un proceso nuevo, está limitado a
   512 KiB y no contiene coordenadas; sí indica qué proveedor entregó cada dato, red, radio, multimedia y errores. Si la
   USB se desconectó antes de tiempo, usar `EXPORTAR` para recuperar el último estado conservado internamente.
13. Un archivo sin eventos no significa que el coche carezca de la señal: significa que la APK normal no la observó por
   broadcasts registrados, ajustes legibles o Android Automotive público. En ese caso se necesitará inspeccionar las
   APK OEM exportadas o una captura autorizada por ADB; no se probarán APIs o índices de otra plataforma a ciegas.
14. Solo aceptar un candidato como base de una futura implementación si repite el mismo patrón en al menos tres ciclos,
   no cambia al accionar otra función y el informe identifica paquete/acción o propiedad, clave, valores y unidad.
15. Usar `BORRAR HISTORIAL` únicamente después de copiar los TXT. El borrado requiere confirmación, no modifica la USB
   y no puede recuperarse salvo desde un informe exportado. Desinstalar la app o borrar sus datos también elimina este
   registro.

## 12. Exportar contratos OEM de CAN y ordenador de a bordo

1. Conectar la memoria USB y abrir `Diagnóstico de la unidad`.
2. Pulsar `EXPORTAR RADIO / CAN`. Si todavía no existe permiso, elegir la carpeta `IDRIVE_DEBUG`; la confirmación vuelve
   a aparecer automáticamente.
3. Leer la lista y confirmar `EXPORTAR A USB`. Esta acción no requiere accionar luces, puertas ni mover el vehículo.
4. Esperar al diálogo `Exportación terminada`. No retirar la memoria mientras el botón indique `EXPORTANDO…`.
5. Expulsar la USB de forma segura y copiar al PC:
   - `e87_oem_inventory_*.txt`;
   - `e87_oem_export_result_*.txt`;
   - todos los `*_oem_*.apk`.
6. No publicar los APK OEM en GitHub. Deben analizarse localmente para localizar contratos, nombres de campos, tipos,
   unidades y permisos de `CanBusContentProvider`, `CarService`, launcher y ajustes.
7. El TXT `e87_oem_export_result` indica cada archivo copiado u omitido. Si un paquete falla, repetir con espacio libre
   suficiente y la USB conectada directamente a la radio.
8. Una vez analizados los APK se preparará una versión de lectura. Solo entonces deben repetirse capturas de velocidad,
   autonomía, consumo, temperatura exterior, puertas, luces, freno, cinturón, marcha atrás y PDC para verificar valores.
9. Para el GPS, comprobar en el inventario `GPS ANDROID ESTÁNDAR`: proveedor `gps`, permiso concedido, posición reciente,
   precisión razonable y velocidad publicada. Las coordenadas se omiten intencionadamente.

## 13. Inventario completo para identificar y buscar firmware

1. Utilizar una USB con al menos 20 GB libres y formato compatible con la radio. Abrir el diagnóstico y pulsar
   `EXPORTACIÓN COMPLETA`.
2. Confirmar `COPIAR TODO A USB` y mantener iDrive visible. La preparación enumera todos los paquetes y la copia puede
   tardar varios minutos; no desconectar la memoria hasta el diálogo final.
3. La extracción está limitada a 16 GB totales y 2 GB por archivo. El resultado lista todo archivo copiado u omitido.
4. Copiar al PC `e87_firmware_inventory_*.txt`, `e87_firmware_export_result_*.txt`, todos los `oem_*.apk` y los archivos
   `firmware_*` (`build.prop`, `prop.default`, certificados OTA) que hayan resultado legibles.
5. El inventario incluye fingerprint, build ID/display, parche de seguridad, kernel, placa, ABI, bibliotecas, funciones,
   permisos, componentes de actualización y la actividad pública de actualización si existe.
6. Analizar primero `com.jancar.ota`, `com.jancar.settings`, los paquetes con `update/ota/firmware`, sus manifests y
   cadenas. Esto puede revelar nombres de archivo, servidor, versión y comprobación de firma esperados.
7. No instalar ningún paquete encontrado únicamente por coincidencia de `rk3326_r`. Deben coincidir como mínimo placa,
   fingerprint/fabricante, resolución, MCU, CANBUS Hiworld, configuración de audio, cámara y método de firma/actualización.
8. La APK normal no puede respaldar `boot`, `recovery`, `super`, MCU ni memoria interna de Hiworld. Si fueran necesarios,
   preparar una captura ADB/root separada y específica después de comprobar que la unidad ofrece ese acceso.
9. No publicar APK ni firmware OEM en GitHub. El repositorio solo debe conservar conclusiones, hashes y adaptadores de
   lectura cuya licencia permita su distribución.
