$ErrorActionPreference = 'Stop'
$projectRoot = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$output = Join-Path $projectRoot 'BMW-E87-Data-Builder-portable'
python -m PyInstaller --noconfirm --clean --onefile --windowed `
  --name 'BMW-E87-Data-Builder' `
  --paths (Join-Path $projectRoot 'tools') `
  --distpath $output `
  --workpath (Join-Path $projectRoot 'build\pyinstaller-data-builder') `
  --specpath (Join-Path $projectRoot 'build\pyinstaller-data-builder') `
  (Join-Path $PSScriptRoot 'bmw_e87_data_builder.py')
Write-Host "Portable creado en $output"
