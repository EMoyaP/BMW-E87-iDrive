# BMW E87 Data Builder

Utilidad Windows para preparar y publicar, con un único botón, los datos offline de la radio.

Incluye cinco provincias: Alicante, Murcia, Valencia, Albacete y Almería. Descarga las fuentes
oficiales DGT, obtiene de OSM carreteras y radares, conserva o actualiza la semilla Lufop y genera
un manifiesto SHA-256. La radio valida el lote completo antes de activar sus capas; los límites
TN-ITS se fusionan por fecha para conservar el historial de cambios. Los precios de gasolineras
quedan deliberadamente fuera.

La publicación se crea primero como borrador de GitHub. Solo se hace visible cuando todos los
archivos han sido subidos y validados. El token nunca se guarda en el proyecto ni se incorpora al
ejecutable; se introduce en la ventana o mediante `GITHUB_TOKEN`.

Para generar el EXE portable se necesita PyInstaller durante la compilación, no durante el uso:

```powershell
python -m pip install pyinstaller
.\desktop\BMW-E87-Data-Builder\build_portable.ps1
```

El ejecutable versionado se guarda en `BMW-E87-Data-Builder-portable/` sin reemplazar una copia
que ya esté abierta: `BMW-E87-Data-Builder-v1.1.0.exe`.

El paquete local queda en `build/data-builder/<versión>/`, incluso si GitHub falla. El constructor
no modifica las semillas fuente del proyecto. Las consultas OSM
se dividen por teselas y cada respuesta completa se conserva temporalmente en
`build/data-builder-cache`; si Overpass, Windows o la red interrumpen una provincia, el siguiente
intento reanuda las teselas validadas. La caché se elimina automáticamente cuando la provincia
termina. Un bloqueo del proyecto impide ejecutar dos constructores simultáneos sobre esa caché.
