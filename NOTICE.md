# Avisos y atribuciones

Required Notice: Copyright 2026 Eugenio Moya Pérez.

## Proyecto independiente

BMW E87 iDrive es un proyecto comunitario e independiente. No está afiliado, patrocinado, certificado ni respaldado
por BMW AG, Hiworld, Camecho, JCRK, CYA, Google, Spotify ni los fabricantes de la unidad Android o de la caja CANBUS.

## Marcas, nombres y logotipos

BMW, iDrive, el emblema circular BMW, BMW Serie 1, E87 y las demás denominaciones, emblemas y elementos identificativos
de BMW citados o representados en este repositorio pertenecen a BMW AG o a sus entidades vinculadas. Se utilizan
exclusivamente para describir el vehículo compatible y el contexto técnico del proyecto. Su presencia en el nombre,
la interfaz, las capturas o el icono de la aplicación no implica origen oficial, aprobación, patrocinio ni asociación
con BMW AG.

Android, Android Auto, Google Maps y YouTube son marcas de Google LLC. Spotify es una marca de Spotify AB. La marca
denominativa y los logotipos Bluetooth pertenecen a Bluetooth SIG, Inc. Hiworld, Camecho, JCRK y CYA se mencionan
únicamente como identificadores observados del hardware o software; cualquier derecho sobre esos nombres corresponde
a sus respectivos titulares.

Ninguna licencia de este repositorio concede derechos para usar, modificar, sublicenciar o redistribuir marcas,
logotipos, nombres comerciales, imagen corporativa o diseños protegidos de terceros. En particular, el emblema BMW
incluido en el icono de la aplicación queda expresamente fuera de las licencias `LICENSE` y `LICENSE-ASSETS.md`; el
autor del proyecto no reivindica su propiedad. La atribución y el carácter no comercial del proyecto no sustituyen
una autorización del titular de la marca.

Como referencia sobre la titularidad y protección de las marcas, denominaciones de modelos, logotipos y emblemas BMW,
consulta el [aviso legal oficial de BMW Group](https://www.bmwgroup.com/en/general/legal-disclaimer.html).

Si eres titular de derechos y consideras que alguna atribución o uso debe corregirse, abre una incidencia en el
repositorio para que pueda revisarse.

## Datos de carburantes

La aplicación consulta en tiempo de ejecución el servicio público de precios de carburantes del Ministerio para la
Transición Ecológica y el Reto Demográfico. El repositorio no incorpora ni redistribuye el conjunto nacional de datos.
La disponibilidad, exactitud y condiciones del servicio dependen de su organismo responsable.

La tarjeta de gasolineras toma como referencia funcional el proyecto
[UGasolineras](https://github.com/EMoyaP/UGasolineras), publicado por Eugenio Moya Pérez bajo CC BY-NC 4.0. No se han
copiado sus dependencias WebView/Leaflet ni un motor de rutas; la integración de este repositorio es nativa y abre la
aplicación de mapas instalada.

## Límites de velocidad

Los datos de límites de velocidad usados para la base local proceden de [OpenStreetMap](https://www.openstreetmap.org/)
y de sus colaboradores, bajo los términos de la [Open Data Commons Open Database License (ODbL)](https://opendatacommons.org/licenses/odbl/).
La aplicación los obtiene, cuando el usuario lo solicita mediante Wi-Fi, usando un servicio público compatible con la
API Overpass. OpenStreetMap y sus colaboradores no garantizan la exactitud, integridad o actualidad de los límites; el
dato mostrado es orientativo y no sustituye la señalización vial.

## Radares fijos y de tramo

La aplicación incorpora una copia local del inventario DATEX II de **cinemómetros fijos y de
velocidad media** publicado por la Dirección General de Tráfico (DGT), obtenida desde su
[publicación oficial de localizaciones predefinidas](https://infocar.dgt.es/datex2/dgt/PredefinedLocationsPublication/radares/content.xml).
La ficha de datos de la DGT identifica el conjunto como
[Radares fijos DGT](https://nap.dgt.es/es/dataset/radares-fijos-dgt) y publica su licencia
CC BY 4.0. Se mantiene la atribución a DGT y la URL de origen en la aplicación, los informes y
esta documentación.

Solo se importan los conjuntos `CabinasCinemometro` (fijos) y
`CinemometrosVelocidadMedia` (tramo). No se importan, estiman ni muestran controles móviles.
Las coordenadas y los límites mostrados son informativos: la señalización, las instrucciones de
las autoridades y el cuadro del vehículo son siempre la referencia.

## Complemento Lufop / RadarDroid

Además del inventario DGT, la APK integra una fotografía local complementaria de **radares fijos**
españoles procesada una vez del paquete RadarDroid de
[Lufop.net](https://lufop.net/en/radardroid-updated-speed-camera-database/). La radio no contiene
credenciales, no automatiza el acceso al proveedor y no descarga esa fuente. Solo se convierte la
categoría que el fichero identifica como fija (`TYPE=1`); semáforos, controles de tramo, zonas y
controles móviles se mantienen fuera de la alerta fija.

La base se conserva separada de la DGT y respeta su atribución: **Datos: Lufop.net y colaboradores
de OpenStreetMap — ODbL 1.0**. Si los dos inventarios coinciden en el entorno de un punto, la DGT
conserva prioridad. Los fijos exclusivos de la semilla local pueden activar la locución de aviso.

## Límites oficiales y zonas INVIVE

La capa nacional de límites oficiales se obtiene del feed TN-ITS semanal de la DGT:
[limitesVelocidad.xml](https://infocar.dgt.es/tnits/limitesVelocidad.xml), referenciado también en el
[NAP de la DGT](https://nap.dgt.es/en/dataset/limites_de_velocidad). La aplicación conserva la
fotografía instalada y mezcla las altas, modificaciones y bajas posteriores en una base SQLite local.
La geometría se utiliza solo cuando su sistema de coordenadas es identificable o ha sido marcada como
inferencia técnica; un registro no coincidente por carretera, proximidad y sentido no se muestra.

Las zonas de intensificación de vigilancia proceden del conjunto oficial
[INVIVE](https://nap.dgt.es/en/dataset/tramos-invive) y de su publicación DATEX II nacional:
[tramos_invive/content.xml](https://infocar.dgt.es/datex2/dgt/PredefinedLocationsPublication/tramos_invive/content.xml).
Se presentan como `ZONA DE VIGILANCIA`, nunca como radar fijo y nunca como un límite de velocidad.

## Seguridad del vehículo

Este software se entrega sin garantía. No sustituye sistemas de seguridad, instrumentación homologada ni avisos del
vehículo. Toda validación debe realizarse con el vehículo detenido y sin modificar MCU, CANBUS o parámetros de fábrica.
