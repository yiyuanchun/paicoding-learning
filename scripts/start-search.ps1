# Compatibility entry point: all local middleware now shares paicoding-mysql.
param(
    [switch]$InfrastructureOnly,
    [switch]$SkipBuild,
    [string]$Java8Home = "$env:TEMP/paicoding-java8/jdk8u504-b01",
    [int]$Port = 8080
)
& (Join-Path $PSScriptRoot 'start-local.ps1') @PSBoundParameters