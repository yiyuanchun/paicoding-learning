# Reversible smoke check against the business database. A unique suffix is added
# to one published title, observed through Canal/ES/HTTP, then conditionally removed.
param([long]$ArticleId = 14, [string]$BaseUrl = 'http://127.0.0.1:8080')
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path $PSScriptRoot -Parent)
function Invoke-ProbeSql([string]$Sql) {
    $taskResult = $Sql | docker exec -i paicoding-mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --batch --skip-column-names'
    if ($LASTEXITCODE -ne 0) { throw 'Business SQL check failed.' }
    return $taskResult
}
if ($ArticleId -le 0) { throw 'Invalid article ID.' }
$taskOriginalHex = [string](Invoke-ProbeSql "SELECT HEX(title) FROM pai_coding.article WHERE id=$ArticleId AND status=1 AND deleted=0;")
if ($taskOriginalHex -notmatch '^[0-9A-F]+$') { throw 'Published article not found.' }
$taskOriginalTitle = [Text.Encoding]::UTF8.GetString([byte[]]@(for ($i=0;$i -lt $taskOriginalHex.Length;$i+=2) { [Convert]::ToByte($taskOriginalHex.Substring($i,2),16) }))
$taskMarker = 'business_canal_' + [Guid]::NewGuid().ToString('N').Substring(0,12)
$taskExpectedTitle = $taskOriginalTitle + ' ' + $taskMarker
$taskExpectedHex = ([BitConverter]::ToString([Text.Encoding]::UTF8.GetBytes($taskExpectedTitle))).Replace('-','')
$taskProof = [ordered]@{ database='pai_coding'; mysqlContainer='paicoding-mysql'; index='paicoding_article_v2'; articleId=$ArticleId; marker=$taskMarker; titleRestored=$false }
try {
    $taskRows = Invoke-ProbeSql "UPDATE pai_coding.article SET title=CONVERT(0x$taskExpectedHex USING utf8mb4),update_time=update_time WHERE id=$ArticleId AND HEX(title)='$taskOriginalHex'; SELECT ROW_COUNT();"
    if ([string]$taskRows -ne '1') { throw 'Article changed concurrently; no update was made.' }
    $taskDeadline = (Get-Date).AddSeconds(45)
    do {
        $taskDoc = Invoke-RestMethod "http://127.0.0.1:9201/paicoding_article_v2/_doc/$ArticleId"
        if ($taskDoc._source.title -eq $taskExpectedTitle) { break }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $taskDeadline)
    if ($taskDoc._source.title -ne $taskExpectedTitle) { throw 'Canal incremental update timed out.' }
    $taskProof.sqlUpdateReachedEs = $true
    $taskResult = Invoke-RestMethod "$BaseUrl/search/api/list?key=$taskMarker&page=1&size=5"
    $taskJson = $taskResult | ConvertTo-Json -Depth 20
    $taskProof.httpSuccess = $taskResult.status.code -eq 0
    $taskProof.httpHighlight = $taskJson -match '<mark>' -and $taskJson -match $taskMarker
    if (-not $taskProof.httpSuccess -or -not $taskProof.httpHighlight) { throw 'Search endpoint did not return highlighted probe.' }
    $taskPage = Invoke-WebRequest "$BaseUrl/search?key=$taskMarker" -UseBasicParsing
    $taskProof.pageHighlight = $taskPage.StatusCode -eq 200 -and $taskPage.Content -match '<mark>' -and $taskPage.Content -match $taskMarker
    if (-not $taskProof.pageHighlight) { throw 'Search page did not render highlighted probe.' }
} finally {
    # Never overwrite another editor's newer title. Both writes retain update_time.
    Invoke-ProbeSql "UPDATE pai_coding.article SET title=CONVERT(0x$taskOriginalHex USING utf8mb4),update_time=update_time WHERE id=$ArticleId AND HEX(title)='$taskExpectedHex';" | Out-Null
    $taskCurrentHex = [string](Invoke-ProbeSql "SELECT HEX(title) FROM pai_coding.article WHERE id=$ArticleId;")
    $taskProof.titleRestored = $taskCurrentHex -eq $taskOriginalHex
    $taskProof.checkedAt = (Get-Date).ToString('o')
    New-Item -ItemType Directory -Force -Path logs | Out-Null
    $taskProof | ConvertTo-Json | Set-Content -LiteralPath logs/business-search-proof.json -Encoding UTF8
    if (-not $taskProof.titleRestored) { throw 'Title changed concurrently; manual review is required. Newer content was preserved.' }
}
$taskDeadline = (Get-Date).AddSeconds(45)
do {
    $taskDoc = Invoke-RestMethod "http://127.0.0.1:9201/paicoding_article_v2/_doc/$ArticleId"
    if ($taskDoc._source.title -eq $taskOriginalTitle) { break }
    Start-Sleep -Milliseconds 500
} while ((Get-Date) -lt $taskDeadline)
if ($taskDoc._source.title -ne $taskOriginalTitle) { throw 'MySQL title restored but ES restoration timed out.' }
$taskProof.esTitleRestored = $true
$taskProof | ConvertTo-Json | Set-Content -LiteralPath logs/business-search-proof.json -Encoding UTF8
$taskProof | ConvertTo-Json
