# Generate the *QA-only* signing identity, never a production key.
# Requires JDK keytool (from JAVA_HOME or Android Studio) and PowerShell 5+.
# Run this locally, not in a public CI job. Nothing confidential is printed.
$ErrorActionPreference = 'Stop'
$keytool = Get-Command keytool -ErrorAction SilentlyContinue
if ($null -eq $keytool -and $env:JAVA_HOME) {
    $candidate = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
    if (Test-Path $candidate) { $keytool = Get-Item $candidate }
}
if ($null -eq $keytool -and $env:ProgramFiles) {
    $candidate = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr\bin\keytool.exe'
    if (Test-Path $candidate) { $keytool = Get-Item $candidate }
}
if ($null -eq $keytool) { throw 'JDK keytool not found. Install JDK 17 or Android Studio.' }
$tool = $keytool.Source
$directory = Join-Path ([Environment]::GetFolderPath('UserProfile')) '.open-music'
New-Item -ItemType Directory -Path $directory -Force | Out-Null
$path = Join-Path $directory 'open-music-qa.p12'
if (Test-Path $path) {
    throw "QA key already exists at $path. Keep the existing key: do not rotate it."
}
$securePassword = Read-Host 'Create a QA signing password (at least 16 characters)' -AsSecureString
$password = [System.Net.NetworkCredential]::new('', $securePassword).Password
if ($password.Length -lt 16 -or $password.Contains([char]10) -or $password.Contains([char]13)) {
    throw 'Password must have at least 16 characters and no newlines.'
}
& $tool -genkeypair -noprompt -storetype PKCS12 -keystore $path `
    -storepass $password -alias 'openmusic-qa' -keypass $password `
    -keyalg RSA -keysize 3072 -validity 3650 `
    -dname 'CN=Open Music QA, OU=QA, O=Open Music'
if ($LASTEXITCODE -ne 0) { throw 'Could not create the QA keystore.' }
$payload = @{
    keystoreBase64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($path))
    password = $password
} | ConvertTo-Json -Compress
Set-Clipboard -Value $payload
Write-Host "QA key created: $path"
Write-Host 'The secret payload is in the clipboard. Paste it ONLY into GitHub Actions secret OPEN_MUSIC_QA_SIGNING.'
Write-Host 'Keep an encrypted backup of the .p12 and its password; losing them breaks stable QA upgrades.'
& $tool -list -v -storetype PKCS12 -keystore $path -storepass $password `
    -alias 'openmusic-qa' | Select-String -Pattern 'SHA1:'
$password = $null
$payload = $null
