# Downloads the 4 Vosk models into app/src/main/assets/
$ErrorActionPreference = "Stop"

$dest = "app/src/main/assets"
if (-not (Test-Path $dest)) { New-Item -ItemType Directory -Path $dest | Out-Null }

$models = @(
    "vosk-model-small-en-in-0.4",
    "vosk-model-small-gu-0.42",
    "vosk-model-small-hi-0.22",
    "vosk-model-small-te-0.42"
)

foreach ($m in $models) {
    if (Test-Path "$dest/$m") {
        Write-Host "OK $m already present, skipping"
        continue
    }
    $url = "https://alphacephei.com/vosk/models/$m.zip"
    $zip = Join-Path $env:TEMP "$m.zip"
    Write-Host "Downloading $m ..."
    Invoke-WebRequest -Uri $url -OutFile $zip
    Write-Host "Extracting $m ..."
    Expand-Archive -Path $zip -DestinationPath $dest -Force
    Remove-Item $zip
    Write-Host "OK $m done"
}

Write-Host ""
Write-Host "All Vosk models installed in $dest" -ForegroundColor Green