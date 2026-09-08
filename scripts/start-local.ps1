param(
    [switch]$InfrastructureOnly,
    [switch]$SkipBuild,
    [string]$Java8Home = "$env:TEMP/paicoding-java8/jdk8u504-b01",
    [int]$Port = 8080
)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $taskRoot
$taskOriginalKeys = @{}
Get-ChildItem Env: | ForEach-Object { $taskOriginalKeys[$_.Name] = $true }
foreach ($taskFile in @('.env', '.env.local')) {
    if (Test-Path -LiteralPath $taskFile) {
        foreach ($taskLine in Get-Content -LiteralPath $taskFile -Encoding UTF8) {
            if ($taskLine -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$') {
                $taskKey = $Matches[1]
                $taskValue = $Matches[2].Trim().Trim('"').Trim("'")
                if (-not $taskOriginalKeys.ContainsKey($taskKey)) {
                    [Environment]::SetEnvironmentVariable($taskKey, $taskValue, 'Process')
                }
            }
        }
    }
}
docker start paicoding-mysql paicoding-redis | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Existing paicoding-mysql and paicoding-redis containers are required.' }
$taskBinlog = docker exec paicoding-mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -e "SELECT @@log_bin,@@binlog_format,@@binlog_row_image,@@server_id;"'
if ($LASTEXITCODE -ne 0 -or $taskBinlog -notmatch '^1\s+ROW\s+FULL\s+[1-9][0-9]*$') { throw 'MySQL requires log_bin=ON, binlog_format=ROW, binlog_row_image=FULL, server_id>0.' }
$taskNetworks = @(docker network ls --format '{{.Name}}')
if ($taskNetworks -notcontains 'paicoding-business') {
    docker network create --subnet 172.30.91.0/24 paicoding-business | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not create business network; check subnet conflicts.' }
}
$taskNetwork = docker network inspect paicoding-business | ConvertFrom-Json
if ($taskNetwork[0].IPAM.Config.Subnet -notcontains '172.30.91.0/24') { throw 'Unexpected business network subnet.' }
$taskMysql = docker inspect paicoding-mysql | ConvertFrom-Json
if (-not $taskMysql[0].NetworkSettings.Networks.'paicoding-business') {
    docker network connect --alias paicoding-mysql paicoding-business paicoding-mysql
    if ($LASTEXITCODE -ne 0) { throw 'Could not attach existing MySQL to business network.' }
}
if (-not $env:PAICODING_CANAL_DB_USERNAME) { $env:PAICODING_CANAL_DB_USERNAME = 'paicoding_canal' }
if (-not $env:PAICODING_CANAL_DB_PASSWORD) { $env:PAICODING_CANAL_DB_PASSWORD = [Guid]::NewGuid().ToString('N') }
if (-not $env:PAICODING_MQ_USERNAME) { $env:PAICODING_MQ_USERNAME = 'paicoding' }
if (-not $env:PAICODING_MQ_PASSWORD) { $env:PAICODING_MQ_PASSWORD = [Guid]::NewGuid().ToString('N') }
# Generated secrets are hexadecimal. Existing custom values must be plain dotenv
# values, so PowerShell, Java and Compose all read exactly the same bytes.
foreach ($taskSecret in @($env:PAICODING_CANAL_DB_PASSWORD, $env:PAICODING_MQ_PASSWORD)) {
    if ($taskSecret -notmatch '^[a-zA-Z0-9_!@%+=.,:/?-]+$') { throw 'Use a dotenv-safe middleware password (letters, digits or _!@%+=.,:/?-).' }
}
if ($env:PAICODING_CANAL_DB_USERNAME -notmatch '^[a-zA-Z0-9_]{1,32}$' -or $env:PAICODING_CANAL_DB_USERNAME -eq 'root') { throw 'Use a dedicated Canal username.' }
# This launcher does not create database accounts or change database privileges.
$taskAccount = $env:PAICODING_CANAL_DB_USERNAME
$taskAccountCount = "SELECT COUNT(*) FROM mysql.user WHERE user='$taskAccount' AND host='172.30.91.10';" | docker exec -i paicoding-mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N'
if ($LASTEXITCODE -ne 0) { throw 'Could not inspect Canal account prerequisites.' }
$taskCanalReady = "$taskAccountCount".Trim() -eq '1'
$taskSettings = [ordered]@{
    DATABASE_NAME = 'pai_coding'
    SPRING_DATASOURCE_URL = 'jdbc:mysql://localhost:3307/pai_coding?useUnicode=true&allowPublicKeyRetrieval=true&autoReconnect=true&characterEncoding=UTF-8&useSSL=false&serverTimezone=Asia/Shanghai'
    SPRING_DATASOURCE_USERNAME = 'root'
    SPRING_REDIS_HOST = 'localhost'
    SPRING_REDIS_PORT = '6379'
    SPRING_REDIS_DATABASE = '0'
    PAICODING_MQ_HOST = '127.0.0.1'
    PAICODING_MQ_PORT = '5672'
    PAICODING_MQ_USERNAME = $env:PAICODING_MQ_USERNAME
    PAICODING_MQ_PASSWORD = $env:PAICODING_MQ_PASSWORD
    PAICODING_MQ_VHOST = '/'
    PAICODING_MQ_PUBLISHER_ENABLED = 'true'
    PAICODING_MQ_CONSUMER_ENABLED = 'true'
    PAICODING_ES_OPEN = $taskCanalReady.ToString().ToLowerInvariant()
    PAICODING_ES_HOSTS = '127.0.0.1:9201'
    PAICODING_ES_SCHEME = 'http'
    PAICODING_ES_USERNAME = ''
    PAICODING_ES_PASSWORD = ''
    PAICODING_ES_ARTICLE_INDEX = 'paicoding_article_v2'
    PAICODING_ES_ANALYZER = 'cjk'
    PAICODING_ES_SEARCH_ANALYZER = 'cjk'
    PAICODING_CANAL_ENABLED = $taskCanalReady.ToString().ToLowerInvariant()
    PAICODING_CANAL_HOST = '127.0.0.1'
    PAICODING_CANAL_PORT = '11111'
    PAICODING_CANAL_DESTINATION = 'paicoding'
    PAICODING_CANAL_DATABASE = 'pai_coding'
    PAICODING_CANAL_SOURCE_ID = 'paicoding-mysql-1'
    PAICODING_CANAL_MYSQL_ADDRESS = 'paicoding-mysql:3306'
    PAICODING_CANAL_DB_USERNAME = $env:PAICODING_CANAL_DB_USERNAME
    PAICODING_CANAL_DB_PASSWORD = $env:PAICODING_CANAL_DB_PASSWORD
}
$taskLocalLines = @()
if (Test-Path -LiteralPath '.env.local') {
    foreach ($taskLine in Get-Content -LiteralPath '.env.local' -Encoding UTF8) {
        if ($taskLine -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=' -and $taskSettings.Contains($Matches[1])) { continue }
        $taskLocalLines += $taskLine
    }
}
foreach ($taskKey in $taskSettings.Keys) {
    $taskLocalLines += "$taskKey=$($taskSettings[$taskKey])"
    [Environment]::SetEnvironmentVariable($taskKey, $taskSettings[$taskKey], 'Process')
}
[IO.File]::WriteAllLines((Join-Path $taskRoot '.env.local'), [string[]]$taskLocalLines, [Text.UTF8Encoding]::new($false))
$taskServices = @('rabbitmq', 'elasticsearch')
if ($taskCanalReady) { $taskServices += 'canal' }
docker compose --env-file .env.local -p paicoding-local -f docker-compose.local.yml up -d --wait --wait-timeout 180 @taskServices
if ($LASTEXITCODE -ne 0) { throw 'Business middleware startup failed.' }
if ($taskCanalReady) {
    # A running container alone does not prove that its password/permissions work.
    $taskDeadline = (Get-Date).AddSeconds(60)
    do {
        $taskReplication = "SELECT COUNT(*) FROM information_schema.processlist WHERE USER='$taskAccount' AND COMMAND LIKE 'Binlog Dump%';" | docker exec -i paicoding-mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N'
        if ($LASTEXITCODE -eq 0 -and [int]$taskReplication -gt 0) { break }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $taskDeadline)
    if ($LASTEXITCODE -ne 0 -or [int]$taskReplication -le 0) {
        throw 'Canal has not established Binlog replication. Check the dedicated account password, grants and Canal log before starting the application.'
    }
}
if (-not $taskCanalReady) {
    Write-Warning 'Canal account is not provisioned. Elasticsearch search and Canal remain disabled. See docs/本机业务库启动与验收.md.'
}
Write-Host 'Business database: paicoding-mysql / localhost:3307 / pai_coding. RabbitMQ:5672. Elasticsearch:9201.'
if ($InfrastructureOnly) { return }
if (-not $taskCanalReady) { throw 'Provision the restricted Canal account before starting the complete application.' }
if (-not (Test-Path -LiteralPath "$Java8Home/bin/java.exe")) { throw 'Java 8 not found. Specify -Java8Home.' }
$env:JAVA_HOME = $Java8Home
$env:PATH = "$Java8Home/bin;$env:PATH"
if (-not $SkipBuild) {
    & mvn -pl paicoding-web -am -DskipTests install
    if ($LASTEXITCODE -ne 0) { throw 'Project build failed.' }
}
Write-Host 'Starting PaiCoding. Use the URL printed by Spring Boot (.dev-port may select a saved port).'
& mvn -pl paicoding-web spring-boot:run "-Dspring-boot.run.arguments=--server.port=$Port --database.name=pai_coding"
if ($LASTEXITCODE -ne 0) { throw 'Application exited with an error.' }
