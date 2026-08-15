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
3. Abrir la app y comprobar que `Gasolineras · Diésel` deja de mostrar `Esperando ubicación`/`Consultando` y presenta
   `Más barata` y `Más cercana`. El pie debe indicar `7 km · MITECO` en la primera carga correcta.
4. Contrastar nombre y precio con el portal oficial. La distancia de la tarjeta es aproximada en línea recta; la ruta
   y distancia por carretera las determina Maps después de tocar la fila.
5. Tocar cada resultado. Google Maps debe abrirse con esa coordenada lista para navegar; si Maps no está instalado,
   debe aparecer otra aplicación compatible o el aviso de que no hay mapas.
6. Mantener pulsada la tarjeta, cambiar primero a 3 km y después a 10 km, y confirmar que título, resultados y pie se
   actualizan respecto de la posición actual. Restaurar Diésel y 7 km. Tocar el pie para probar la actualización manual.
7. Con datos ya visibles, quitar Internet y cerrar/abrir la app: los últimos valores deben mantenerse. Tocar el pie
   para forzar una actualización; al fallar debe indicar `CACHÉ`. Al recuperar conexión, la actualización debe
   reanudarse sin reiniciar la radio.
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
4. Volver a `USB DEBUG > Iniciar captura guiada` y elegir **una sola señal**. Mantener el estado inicial tres segundos,
   cambiarlo al menos tres veces con pausas de tres segundos y pulsar `DETENER` antes de pasar a la siguiente señal.
5. Para puertas, hacer un archivo independiente por cada puerta y portón. Para luces, recorrer apagadas, posición,
   cruce, largas y antiniebla. Para clima, separar temperaturas y ventilador. Para PDC/marcha atrás, usar ayudante y un
   obstáculo seguro; no desplazarse ni mirar la pantalla durante la maniobra.
6. Repetir hasta obtener archivos separados para luces, freno, cada puerta, cinturón, temperatura exterior,
   temperaturas de clima, ventilador y PDC/marcha atrás. La captura se actualiza cada cinco segundos y termina sola a
   los diez minutos para evitar listeners olvidados.
7. Expulsar la USB desde Android si el firmware ofrece esa opción y copiar todos los `e87_*.txt` al ordenador. Si la
   USB se desconectó antes de tiempo, usar `EXPORTAR` para recuperar el último estado conservado internamente.
8. Un archivo sin eventos no significa que el coche carezca de la señal: significa que la APK normal no la observó por
   broadcasts registrados, ajustes legibles o Android Automotive público. En ese caso se necesitará inspeccionar las
   APK OEM exportadas o una captura autorizada por ADB; no se probarán APIs o índices de otra plataforma a ciegas.
