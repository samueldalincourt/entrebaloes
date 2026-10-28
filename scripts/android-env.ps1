# Dot-source: . ./scripts/android-env.ps1
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr' }
if (-not $env:ANDROID_NDK) { $env:ANDROID_NDK = Join-Path $env:ANDROID_HOME 'ndk\28.2.13676358' }
$env:CARGO_HOME = Join-Path $projectRoot '.tools\cargo'
$env:RUSTUP_HOME = Join-Path $projectRoot '.tools\rustup'
$env:MLC_LLM_HOME = Join-Path $projectRoot '.tools\mlc-cache'
$env:PATH = (Join-Path $projectRoot '.tools\mlc-python\Scripts') + ';' +
    (Join-Path $env:CARGO_HOME 'bin') + ';' + (Join-Path $env:JAVA_HOME 'bin') + ';' +
    (Join-Path $env:ANDROID_HOME 'platform-tools') + ';' + $env:PATH
