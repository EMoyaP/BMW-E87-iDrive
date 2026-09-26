# Vista de conducción automática

La vista de conducción ocupa la pantalla al recibir dos posiciones GPS fiables por encima de 5 km/h. DrivingDashboardView dibuja una esfera abierta a la izquierda, el E87 original en el centro y el límite a la derecha. Autonomía y consumo aparecen debajo de la esfera y la temperatura bajo el coche. Los avisos aparecen debajo del límite, con prioridad radar sobre INVIVE. Se usa un fundido con escala suave de 360 ms al entrar y 280 ms al volver. El menú sigue accesible arriba a la derecha.

El valor de velocidad y el arco se vuelven naranjas únicamente al superar un límite explícito. Sin límite, o con velocidad aconsejada, permanecen verdes. No se crean nuevas fuentes de datos ni locuciones duplicadas: se reutilizan los resultados de los proveedores y las tarjetas existentes. Los valores ausentes se muestran como raya.

La vista habitual vuelve tras 60 segundos continuos por debajo de 2 km/h. Posiciones duplicadas, precisión superior a 25 m, ausencia de velocidad y pérdidas de más de 10 segundos no acumulan tiempo de detención. Se usa el tiempo monotónico del GPS. Herramientas permite desactivar la función.

Pruebas: DrivingViewPolicyTest cubre parada de un minuto, stop breve, reinicio de la cuenta por movimiento, duplicados y pérdida de GPS. DrivingGaugeScaleTest comprueba la escala y el umbral de exceso de velocidad. Compilación, tests y lint verificados. Las capturas driving-cluster-v1.26.0.png, driving-cluster-overspeed-v1.26.0.png y driving-cluster-radar-v1.26.0.png proceden de la app real en el emulador con GPS simulado. Pendiente la validación física.

La revisión arquitectónica más amplia (coordinador de actualizaciones, paquetes de semillas reproducibles y extracción general de tarjetas) no forma parte de este cambio de presentación y sigue pendiente. El ciclo de vida al abrir Android Auto se conserva.
