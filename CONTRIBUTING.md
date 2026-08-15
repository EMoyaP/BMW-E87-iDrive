# Contribuir a BMW E87 iDrive

Gracias por ayudar a mejorar el proyecto. La prioridad es mantener la radio y las funciones OEM seguras.

## Flujo recomendado

1. Abre un issue describiendo la unidad, versión Android, MCU y CANBUS, sin publicar números de serie, IMEI o MAC.
2. Crea una rama desde `main`.
3. Mantén los cambios pequeños y documenta el comportamiento anterior y posterior.
4. Ejecuta `./gradlew lintDebug testDebugUnitTest assembleDebug`.
5. Incluye capturas para cambios de UI y una prueba reproducible para correcciones funcionales.

## Integraciones del vehículo

No se aceptarán:

- escrituras CAN/UART o comandos a MCU;
- cambios automáticos del perfil CANBUS o parámetros de fábrica;
- paquetes, acciones, índices o claves copiados de otra plataforma sin validar;
- datos simulados presentados como información real del vehículo;
- código que interfiera con cámara, marcha atrás, PDC, climatización o audio OEM.

Una señal propietaria solo puede proponerse cuando se haya identificado paquete, versión, componente, clave o índice,
tipo, rango, unidad y al menos tres ciclos de correlación sin falsos positivos. La primera implementación debe ser de
solo lectura y conservar el estado “No disponible” como fallback.

## Licencia de contribuciones

Al enviar una contribución aceptas publicarla bajo las mismas licencias del repositorio: PolyForm Noncommercial 1.0.0
para software y CC BY-NC-SA 4.0 para documentación y recursos originales.
