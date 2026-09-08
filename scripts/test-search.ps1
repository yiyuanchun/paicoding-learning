param([string]$Java8Home = "$env:TEMP/paicoding-java8/jdk8u504-b01")
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path $PSScriptRoot -Parent)
if (-not (Test-Path -LiteralPath "$Java8Home/bin/java.exe")) { throw 'Pass -Java8Home with your JDK 8 path.' }
$env:JAVA_HOME = $Java8Home
$env:PATH = "$Java8Home/bin;$env:PATH"
docker compose -p paicoding-search-it -f docker-compose.search-test.yml up -d
if ($LASTEXITCODE -ne 0) { throw 'Test services could not start.' }
& mvn -pl paicoding-service -am -DskipTests install
if ($LASTEXITCODE -ne 0) { throw 'Build failed.' }
& mvn -pl paicoding-service '-Dtest=SearchQueryTest,CanalAcknowledgementTest,SearchRealIntegrationTest' '-Dsearch.integration=true' test
if ($LASTEXITCODE -ne 0) { throw 'Search integration tests failed.' }
Write-Host 'Search tests passed. Test services remain running for inspection.'
