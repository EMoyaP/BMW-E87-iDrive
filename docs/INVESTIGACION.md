# Investigación técnica — BMW E87 + Hiworld BM03.10 / H1H2BM030A + Jancar/JCRK01

## 1. Datos confirmados por la unidad instalada
En la unidad del vehículo ya se han observado visualmente:
- velocidad;
- temperatura exterior;
- radar/PDC;
- pitidos del PDC al engranar R;
- estado/visualización de climatización;
- velocidad del ventilador;
- temperatura del climatizador.

Esto demuestra que el flujo vehículo → Hiworld → unidad Android ya transporta esos datos.

## 2. Qué soporta oficialmente Hiworld en sus integraciones
La documentación oficial de Hiworld enumera ajustes para:
- información de puertas;
- información de climatización;
- radar/PDC;
- temperatura exterior en barra de navegación;
- mapeo de mandos CAN;
- intercambio de puertas;
- canal de sonido;
- popup de climatización;
- parking radar.

Hiworld además publica soporte y actualizaciones de CAN APK para plataformas como Jancar.

## 3. Señales BMW E87/E90 documentadas en proyectos de ingeniería inversa
Fuentes abiertas sobre K-CAN E87/E90 documentan, entre otras:
- velocidad;
- freno de mano;
- PDC delantero/trasero;
- temperatura del motor;
- puertas;
- iluminación;
- marcha atrás;
- temperatura exterior;
- estado del climatizador;
- temperatura y velocidad del ventilador;
- botones del volante.

## 4. Decisión de arquitectura
No se accede directamente al bus CAN desde la APK.
La aplicación:
1. usa APIs Android estándar cuando son suficientes (GPS, apps instaladas y, solo si existe, Android Automotive);
2. intenta detectar las aplicaciones OEM;
3. ejecuta un diagnóstico pasivo de paquetes/servicios/receivers y ciertos broadcasts conocidos;
4. muestra un dato CAN únicamente cuando existe una fuente confirmada;
5. permite ocultar cualquier dato no disponible;
6. nunca inyecta mensajes CAN.

### Android 15 no equivale a Android Automotive
Una radio puede ejecutar Android 15 convencional y mantener CAN/MCU detrás de aplicaciones propietarias. Las
propiedades `HEADLIGHTS_STATE`, `HIGH_BEAM_LIGHTS_STATE`, `PARKING_BRAKE_ON`, `DOOR_POS` y
`SEAT_BELT_BUCKLED` forman parte de Android Automotive, no de Android móvil/tablet. Además, varias requieren permisos
privilegiados o de firma del fabricante. Por eso la APK detecta capacidades en ejecución y no presupone acceso.

### Alcance confirmado de Hiworld
La documentación pública de Hiworld demuestra que algunas integraciones pueden presentar puertas, climatización,
radar y temperatura exterior. No documenta un contrato Android público específico de JCRK01/CYA para leer esas
señales desde una APK de terceros, ni permite concluir que luces/freno/cinturón estén publicados. Esas señales quedan
pendientes de correlación en la unidad física.

## 5. Por qué no se codifican packageName inventados
JCRK01/CYA no dispone de documentación pública suficiente que fije los nombres de paquete exactos de Radio,
Bluetooth, S-Play o del servicio CAN en esta unidad concreta. La app realiza detección heurística y permite
reasignación manual desde modales.

## 6. Funciones del proyecto
- Aplicación normal abrible desde el launcher OEM, con UI 1280×720 adecuada para 9"; no declara HOME.
- Estética BMW E87 azul marino oscuro.
- Ajustes mediante modales.
- Accesos principales configurables.
- Seis accesos rápidos configurables con +.
- GPS como fuente real alternativa de velocidad.
- Modal de datos del vehículo.
- Diagnóstico pasivo integrado.
- Exportación del diagnóstico a archivo.
- Sin root.
- Sin modificación de MCU/CANBUS/firmware.

## 7. Qué confirman las fotografías de los menús de la radio

Las pantallas aportadas confirman que el firmware de la unidad conoce, como mínimo, dos entradas del vehículo:

- el ajuste automático día/noche declara que reacciona al estado de los faros;
- existe una opción habilitada de detección del freno de mano.

Esto **no demuestra todavía** que esos dos estados se publiquen a aplicaciones Android. Pueden llegar al MCU por los
cables discretos `ILL` y `PARK_IN`, por CANBUS, o por una combinación de ambos, y el firmware puede consumirlos sin
exponer ningún broadcast o servicio público. `Función de definición de socket` parece referirse a la asignación física
de conectores/pines del fabricante; no debe modificarse para buscar una API.

## 8. Sobre los “códigos publicados”

Sí existen códigos y proyectos públicos, pero están separados en tres niveles incompatibles entre sí:

1. tramas BMW K-CAN del vehículo;
2. protocolo serie de una caja CAN concreta;
3. índices, broadcasts o servicios que una plataforma Android concreta publica desde su APK CANBUS.

La aplicación necesita el nivel 3 de **esta** combinación JCRK01/CYA + APK CANBUS + Hiworld BM03.10. Copiar el índice
de otra plataforma puede leer otro dato o, si se reutiliza una orden de escritura, actuar sobre el coche. Por eso los
repositorios públicos se usan para diseñar el adaptador y el diagnóstico, no como tabla universal de códigos.
