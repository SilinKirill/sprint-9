$ErrorActionPreference = 'Stop'
$projectPath = Split-Path -Parent $PSScriptRoot
$localPath = Join-Path $projectPath '.local'
$tlsPath = Join-Path $localPath 'tls'
New-Item -ItemType Directory -Path $tlsPath -Force | Out-Null

function New-RandomSecret {
    $bytes = New-Object byte[] 32
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($bytes) } finally { $random.Dispose() }
    return [Convert]::ToBase64String($bytes).Replace('+','-').Replace('/','_')
}

$envPath = Join-Path $projectPath '.env'
if (-not (Test-Path -LiteralPath $envPath)) {
    $envText = "BIONICPRO_CLIENT_SECRET=$(New-RandomSecret)`nLDAP_ADMIN_PASSWORD=$(New-RandomSecret)`nYANDEX_ENABLED=false`nYANDEX_CLIENT_ID=`nYANDEX_CLIENT_SECRET=`n"
    [IO.File]::WriteAllText($envPath, $envText, [Text.UTF8Encoding]::new($false))
}
$keyPath = Join-Path $localPath 'token-encryption.key'
if (-not (Test-Path -LiteralPath $keyPath)) {
    [IO.File]::WriteAllText($keyPath, (New-RandomSecret), [Text.UTF8Encoding]::new($false))
}

$certificate = Join-Path $tlsPath 'tls.crt'
if (-not (Test-Path -LiteralPath $certificate)) {
    $opensslCommand = Get-Command openssl -ErrorAction SilentlyContinue
    $opensslPath = if ($opensslCommand) { $opensslCommand.Source } else { Join-Path $env:ProgramFiles 'Git\usr\bin\openssl.exe' }
    if (-not (Test-Path -LiteralPath $opensslPath)) {
        throw 'OpenSSL is required. Install Git for Windows with bundled OpenSSL, or add openssl to PATH.'
    }
    & $opensslPath req -x509 -newkey rsa:2048 -nodes -sha256 -days 365 `
        -keyout (Join-Path $tlsPath 'tls.key') -out $certificate `
        -subj '/CN=localhost' `
        -addext 'subjectAltName=DNS:localhost,DNS:openldap,DNS:keycloak,IP:127.0.0.1' `
        -addext 'basicConstraints=critical,CA:TRUE' 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'Certificate generation failed.' }
}
Copy-Item -LiteralPath $certificate -Destination (Join-Path $tlsPath 'ca.crt') -Force
Write-Output 'Local secrets and TLS certificate are ready. Existing values were preserved.'
