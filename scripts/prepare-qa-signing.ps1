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
$tool = if ($keytool -is [System.IO.FileInfo]) { $keytool.FullName } else { $keytool.Source }
$directory = Join-Path ([Environment]::GetFolderPath('UserProfile')) '.open-music'
New-Item -ItemType Directory -Path $directory -Force | Out-Null
$path = Join-Path $directory 'open-music-qa.p12'
$existing = Test-Path $path
$prompt = if ($existing) { 'Enter the EXISTING QA key password (no key rotation)' } else { 'Create a QA signing password (at least 16 characters)' }
$securePassword = Read-Host $prompt -AsSecureString
$password = [System.Net.NetworkCredential]::new('', $securePassword).Password
if ($password.Length -lt 16 -or $password.Contains([char]10) -or $password.Contains([char]13)) {
    throw 'Password must have at least 16 characters and no newlines.'
}
# Java keytool supports the :env modifier for password arguments.
# The password is available only to this process and child keytool processes,
# never as plain text in their command lines. It is still sensitive in memory.
$env:OPEN_MUSIC_QA_KEYTOOL_PASSWORD = $password
try {
    if (-not $existing) {
        & $tool -genkeypair -noprompt -storetype PKCS12 -keystore $path `
            -storepass:env OPEN_MUSIC_QA_KEYTOOL_PASSWORD `
            -alias 'openmusic-qa' -keypass:env OPEN_MUSIC_QA_KEYTOOL_PASSWORD `
            -keyalg RSA -keysize 3072 -validity 3650 `
            -dname 'CN=Open Music QA, OU=QA, O=Open Music'
        if ($LASTEXITCODE -ne 0) { throw 'Could not create the QA keystore.' }
    }
    $certificate = & $tool -list -v -storetype PKCS12 -keystore $path `
        -storepass:env OPEN_MUSIC_QA_KEYTOOL_PASSWORD -alias 'openmusic-qa' 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw 'QA keystore/password check failed; nothing copied.'
    }

    # Keep the payload in the local clipboard only; never print it or write it
    # into the repository. Clear the clipboard manually after setting the secret.
    $payload = @{
        keystoreBase64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($path))
        password = $password
    } | ConvertTo-Json -Compress
    Set-Clipboard -Value $payload
    Write-Host "QA key preserved: $path"
    Write-Host 'Paste the clipboard payload ONLY into the GitHub Actions repository secret OPEN_MUSIC_QA_SIGNING.'
    Write-Host 'Then clear the clipboard and back up the password-protected .p12 separately.'
    Write-Host 'Verify you can retrieve both the backup file and its password before proceeding.'
    $certificate | Select-String -Pattern 'SHA1:'
} finally {
    Remove-Item Env:OPEN_MUSIC_QA_KEYTOOL_PASSWORD -ErrorAction SilentlyContinue
    $password = $null
    $payload = $null
}
