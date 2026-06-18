# serve-plugins.ps1
# 1. Run gradle task to compile all plugins and make plugins.json
Write-Host "Building plugins..."
.\gradlew.bat makePluginsJson

# Check if build succeeded
if ($LASTEXITCODE -ne 0) {
    Write-Error "Failed to build plugins!"
    exit $LASTEXITCODE
}

# 2. Create builds directory
$buildsDir = Join-Path $PSScriptRoot "builds"
if (-not (Test-Path $buildsDir)) {
    New-Item -ItemType Directory -Path $buildsDir | Out-Null
}

# Clean old builds
Get-ChildItem -Path $buildsDir -Filter *.cs3 | Remove-Item -Force
Get-ChildItem -Path $buildsDir -Filter *.jar | Remove-Item -Force
Get-ChildItem -Path $buildsDir -Filter *.json | Remove-Item -Force

# 3. Copy built files to builds folder
Write-Host "Copying compiled plugins..."
Get-ChildItem -Path $PSScriptRoot -Recurse -Filter *.cs3 | Where-Object { $_.FullName -like "*\build\*" } | Copy-Item -Destination $buildsDir -Force
Get-ChildItem -Path $PSScriptRoot -Recurse -Filter *.jar | Where-Object { $_.FullName -like "*\build\*" } | Copy-Item -Destination $buildsDir -Force
Copy-Item -Path (Join-Path $PSScriptRoot "build\plugins.json") -Destination $buildsDir -Force

# 4. Generate local repo.json
$repoJsonContent = @'
{
    "name": "Phisher Repo (Local Dev)",
    "iconUrl": "https://raw.githubusercontent.com/phisher98/TVVVV/refs/heads/main/Icons/RepoIcon.png",
    "description": "Local development server for Phisher Repo",
    "manifestVersion": 1,
    "pluginLists": [
        "https://raw.githubusercontent.com/mahmoodnizamani94-png/cloudstream-extensions-phisher/builds/plugins.json"
    ]
}
'@
$repoJsonContent | Out-File -FilePath (Join-Path $buildsDir "repo.json") -Encoding utf8

# 5. Run local Node server
Write-Host "Starting Node.js development server..."
node (Join-Path $PSScriptRoot "server.js")
