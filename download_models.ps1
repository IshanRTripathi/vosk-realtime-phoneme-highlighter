$models = @(
    @{ url = "https://alphacephei.com/vosk/models/vosk-model-en-us-0.22.zip"; name = "vosk-model-en-us-0.22" },
    @{ url = "https://alphacephei.com/vosk/models/vosk-model-en-in-0.5.zip"; name = "vosk-model-en-in-0.5" }
)

New-Item -ItemType Directory -Force -Path "model" | Out-Null

foreach ($m in $models) {
    $zipPath = "model\$($m.name).zip"
    $destPath = "model\$($m.name)"
    if (-not (Test-Path $destPath)) {
        Write-Host "Downloading $($m.name)... (This may take a while)"
        # Use BitsTransfer for better reliability/speed on large files if possible, else WebRequest
        Start-BitsTransfer -Source $m.url -Destination $zipPath
        
        Write-Host "Extracting $($m.name)..."
        Expand-Archive -Path $zipPath -DestinationPath "model" -Force
        Remove-Item $zipPath
        Write-Host "$($m.name) installed."
    } else {
        Write-Host "$($m.name) already exists."
    }
}
