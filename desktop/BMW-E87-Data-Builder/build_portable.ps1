$ErrorActionPreference = 'Stop'
$projectRoot = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$output = Join-Path $projectRoot 'BMW-E87-Data-Builder-portable'
$dist = Join-Path $projectRoot 'build\pyinstaller-data-builder\dist'
python -m PyInstaller --noconfirm --clean --onefile --windowed `
  --name 'BMW-E87-Data-Builder' `
  --paths (Join-Path $projectRoot 'tools') `
  --distpath $dist `
  --workpath (Join-Path $projectRoot 'build\pyinstaller-data-builder') `
  --specpath (Join-Path $projectRoot 'build\pyinstaller-data-builder') `
  (Join-Path $PSScriptRoot 'bmw_e87_data_builder.py')
New-Item -ItemType Directory -Path $output -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $dist 'BMW-E87-Data-Builder.exe') `
  -Destination (Join-Path $output 'BMW-E87-Data-Builder.exe') -Force
Write-Host "Portable creado en $output"
