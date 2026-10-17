$ErrorActionPreference = 'Stop'
$destination = Join-Path $PSScriptRoot '.env'
if (Test-Path -LiteralPath $destination) {
    Write-Host 'Task3/.env already exists; credentials preserved.'
    exit 0
}
function New-Secret {
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
}
$settings = @(
    "MINIO_ROOT_USER=$(New-Secret)",
    "MINIO_ROOT_PASSWORD=$(New-Secret)",
    "REPORTS_S3_ACCESS_KEY=$(New-Secret)",
    "REPORTS_S3_SECRET_KEY=$(New-Secret)"
)
[IO.File]::WriteAllLines($destination, $settings, [Text.UTF8Encoding]::new($false))
Write-Host 'Created local Task3/.env with generated credentials.'
