# Restart only the dedicated integration-test RabbitMQ; never the application's broker.
$ErrorActionPreference = 'Stop'
$taskCompose = Join-Path (Split-Path -Parent $PSScriptRoot) 'docker-compose.mq-test.yml'
$taskBase = 'http://127.0.0.1:15673/api'
$taskHeaders = @{ Authorization = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('mqtest:mq-test-only')) }
$taskQueue = 'paicoding.persistence.probe.' + [Guid]::NewGuid().ToString('N')
$taskQueueUrl = "$taskBase/queues/mq-integration/$taskQueue"
$taskPayload = 'persistent:' + $taskQueue
function Invoke-ProbeApi([string]$Method, [string]$Url, $Body) {
    $taskJson = $Body | ConvertTo-Json -Depth 10
    Invoke-RestMethod -Method $Method -Uri $Url -Headers $taskHeaders -ContentType 'application/json' -Body $taskJson -TimeoutSec 5
}
try {
    Invoke-ProbeApi 'Put' $taskQueueUrl @{ durable = $true; auto_delete = $false; arguments = @{ 'x-queue-type' = 'classic' } } > $null
    $taskResult = Invoke-ProbeApi 'Post' "$taskBase/exchanges/mq-integration/amq.default/publish" @{
        properties = @{ delivery_mode = 2; content_type = 'text/plain'; message_id = $taskQueue }
        routing_key = $taskQueue; payload = $taskPayload; payload_encoding = 'string'
    }
    if (-not $taskResult.routed) { throw 'Probe message was not routed.' }
    $taskBefore = @(Invoke-ProbeApi 'Post' "$taskQueueUrl/get" @{ count = 1; ackmode = 'ack_requeue_true'; encoding = 'auto'; truncate = 50000 })
    if ($taskBefore.Count -ne 1 -or $taskBefore[0].payload -ne $taskPayload -or $taskBefore[0].properties.delivery_mode -ne 2) {
        throw 'Persistent message was not present before restart.'
    }
    Write-Output 'Before restart: durable queue contains the persistent probe message.'
    docker compose -p paicoding-mq-it -f $taskCompose restart rabbitmq
    if ($LASTEXITCODE -ne 0) { throw 'Test RabbitMQ restart failed.' }
    $taskReady = $false
    for ($taskAttempt = 0; $taskAttempt -lt 20; $taskAttempt++) {
        try {
            $taskQueueInfo = Invoke-RestMethod -Uri $taskQueueUrl -Headers $taskHeaders -TimeoutSec 2
            if ($taskQueueInfo.durable) { $taskReady = $true; break }
        } catch { Start-Sleep -Seconds 1 }
    }
    if (-not $taskReady) { throw 'Test broker did not become ready after restart.' }
    $taskAfter = @(Invoke-ProbeApi 'Post' "$taskQueueUrl/get" @{ count = 1; ackmode = 'ack_requeue_false'; encoding = 'auto'; truncate = 50000 })
    if ($taskAfter.Count -ne 1 -or $taskAfter[0].payload -ne $taskPayload -or $taskAfter[0].properties.delivery_mode -ne 2) {
        throw 'Persistent message was lost or changed after restart.'
    }
    Write-Output "PASS: identical persistent message survived RabbitMQ restart and was acknowledged. Queue=$taskQueue"
} finally {
    # Remove only this script's unique probe queue; business queues remain intact.
    try { Invoke-RestMethod -Method Delete -Uri $taskQueueUrl -Headers $taskHeaders -TimeoutSec 5 > $null }
    catch { Write-Warning "Probe queue cleanup failed: $taskQueue" }
}
