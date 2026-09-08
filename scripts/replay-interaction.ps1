param(
    [Parameter(Mandatory = $true)][string]$PayloadFile,
    [ValidateSet('notification', 'statistics', 'activity')][string]$Consumer = 'notification',
    [int]$Count = 1,
    [string]$ManagementUrl = 'http://127.0.0.1:15672',
    [string]$VirtualHost = '/',
    [string]$Username = $env:PAICODING_MQ_USERNAME,
    [string]$Password = $env:PAICODING_MQ_PASSWORD
)
$ErrorActionPreference = 'Stop'
if ($Count -lt 1 -or $Count -gt 100) { throw 'Count must be between 1 and 100.' }
if ([string]::IsNullOrWhiteSpace($Username) -or [string]::IsNullOrEmpty($Password)) {
    throw 'Set PAICODING_MQ_USERNAME and PAICODING_MQ_PASSWORD.'
}
$payload = Get-Content -LiteralPath $PayloadFile -Raw -Encoding UTF8
$event = $payload | ConvertFrom-Json
if (-not $event.eventKey -or -not $event.aggregateKey -or -not $event.aggregateVersion) {
    throw 'Use the complete payload copied from mq_outbox; do not change its business key.'
}
$authBytes = [Text.Encoding]::UTF8.GetBytes($Username + ':' + $Password)
$headers = @{ Authorization = 'Basic ' + [Convert]::ToBase64String($authBytes) }
$vhost = [Uri]::EscapeDataString($VirtualHost)
$body = @{
    properties = @{
        delivery_mode = 2
        content_type = 'application/json'
        message_id = $event.eventKey
        headers = @{ 'x-attempt' = 0 }
    }
    routing_key = 'paicoding.' + $Consumer + '.queue'
    payload = $payload
    payload_encoding = 'string'
} | ConvertTo-Json -Depth 10
for ($i = 0; $i -lt $Count; $i++) {
    $result = Invoke-RestMethod -Method Post -Uri "$ManagementUrl/api/exchanges/$vhost/amq.default/publish" -Headers $headers -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body))
    if (-not $result.routed) { throw 'Message was not routed. Check queue, vhost and application startup.' }
}
Write-Output "Published $Count persistent message(s) to the $Consumer queue. eventKey=$($event.eventKey)"
