$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$utf8 = New-Object System.Text.UTF8Encoding($false)
$OutputEncoding = $utf8
[Console]::OutputEncoding = $utf8

if (!(Test-Path 'Task1/.env') -or !(Test-Path 'Task3/.env')) {
    throw 'Configure Task1 and Task3 first.'
}
if (!(Test-Path 'Task4/.env')) {
    $bytes = New-Object byte[] 32
    $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $random.GetBytes($bytes)
    $random.Dispose()
    $password = [Convert]::ToBase64String($bytes)
    [IO.File]::WriteAllText((Join-Path $PSScriptRoot '.env'), "CDC_PASSWORD=$password`n", $utf8)
}
$passwordLine = Get-Content 'Task4/.env' | Where-Object { $_ -match '^CDC_PASSWORD=' } | Select-Object -First 1
if (!$passwordLine) { throw 'Missing CDC_PASSWORD in Task4/.env.' }
$password = $passwordLine.Substring('CDC_PASSWORD='.Length)
New-Item -ItemType Directory -Force 'Task4/.local' | Out-Null
[IO.File]::WriteAllText((Join-Path $PSScriptRoot '.local/credentials.properties'), "password=$password`n", $utf8)
$dc = @('--env-file', 'Task1/.env', '--env-file', 'Task3/.env', '--env-file', 'Task4/.env',
    '-f', 'Task1/docker-compose.yaml', '-f', 'Task2/docker-compose.yaml', '-f', 'Task3/docker-compose.yaml', '-f', 'Task4/docker-compose.yaml')

function Invoke-Compose {
    docker compose @dc @args
    if ($LASTEXITCODE -ne 0) { throw 'Docker Compose command failed.' }
}

Invoke-Compose config --quiet
Invoke-Compose up -d --wait source-db clickhouse kafka
foreach ($topic in @('bionicpro.crm_events', 'bionicpro.transaction', 'bionicpro_connect_config', 'bionicpro_connect_offsets', 'bionicpro_connect_status', '__debezium-heartbeat.bionicpro')) {
    $topicConfig = if ($topic -like '*connect_*') { 'cleanup.policy=compact' } else { 'retention.ms=-1' }
    Invoke-Compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --create --if-not-exists --topic $topic --partitions 1 --replication-factor 1 --config $topicConfig
}
Get-Content -Raw 'Task4/sources/cdc.sql' | docker compose @dc exec -T source-db psql -U source_admin -d sources
if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL CDC migration failed.' }
Get-Content -Raw 'Task4/clickhouse/cdc.sql' | docker compose @dc exec -T clickhouse sh -c 'clickhouse-client --user "$CLICKHOUSE_USER" --password "$CLICKHOUSE_PASSWORD" --multiquery'
if ($LASTEXITCODE -ne 0) { throw 'ClickHouse migration failed.' }
Invoke-Compose up -d --wait kafka-connect
Invoke-Compose exec -T kafka-connect curl -fsS -o /dev/null -X PUT -H Content-Type:application/json --data-binary '@/config/crm-connector.json' http://localhost:8083/connectors/bionicpro-crm/config
$deadline = [DateTime]::UtcNow.AddSeconds(60)
do {
    $statusText = docker compose @dc exec -T kafka-connect curl -fsS http://localhost:8083/connectors/bionicpro-crm/status
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read connector status.' }
    $status = $statusText | ConvertFrom-Json
    if ($status.connector.state -eq 'FAILED' -or @($status.tasks | Where-Object { $_.state -eq 'FAILED' }).Count -gt 0) {
        throw 'Debezium failed. Inspect connector/task status in Kafka Connect.'
    }
    if ($status.connector.state -eq 'RUNNING' -and @($status.tasks).Count -eq 1 -and $status.tasks[0].state -eq 'RUNNING') {
        Write-Host 'Debezium connector and task: RUNNING'
        break
    }
    Start-Sleep -Seconds 2
} while ([DateTime]::UtcNow -lt $deadline)
if ($status.connector.state -ne 'RUNNING' -or @($status.tasks).Count -ne 1 -or $status.tasks[0].state -ne 'RUNNING') {
    throw 'Debezium did not become ready.'
}
Write-Host "`nCDC configured. Start API and Airflow as described in Task4/README.md."
