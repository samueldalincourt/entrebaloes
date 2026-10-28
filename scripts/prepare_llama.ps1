param([string]$Git = 'git')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$source = Join-Path $projectRoot '.tools\llama.cpp'
$revision = '7ecb57f7adbea2928aa468ad2aecf05b10725ad4'
if (-not (Test-Path -LiteralPath (Join-Path $source '.git'))) {
    & $Git clone --depth 1 --no-checkout https://github.com/ggml-org/llama.cpp.git $source
    if ($LASTEXITCODE -ne 0) { throw 'Falha ao baixar llama.cpp' }
}
$current = & $Git -C $source rev-parse HEAD
if ($current -ne $revision) {
    & $Git -C $source fetch --depth 1 origin $revision
    if ($LASTEXITCODE -ne 0) { throw 'Falha ao buscar a revisão fixada' }
    & $Git -C $source checkout --detach $revision
    if ($LASTEXITCODE -ne 0) { throw 'Falha ao selecionar a revisão fixada' }
}
Write-Output "llama.cpp pronto: $revision. O Gradle compilará o runtime junto com o app."
