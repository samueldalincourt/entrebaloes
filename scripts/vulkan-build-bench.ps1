param([int]$Jobs = 4)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$sdkRoot = 'C:/Users/techb/AppData/Local/Android/Sdk'
$ndkRoot = Join-Path $sdkRoot 'ndk/28.2.13676358'
$cmakePath = Join-Path $sdkRoot 'cmake/3.22.1/bin/cmake.exe'
$ninjaPath = Join-Path $sdkRoot 'cmake/3.22.1/bin/ninja.exe'
$gitPath = 'C:/Users/techb/.cache/codex-runtimes/codex-primary-runtime/dependencies/native/git/cmd/git.exe'
$vsEnvironment = 'C:/Program Files/Microsoft Visual Studio/18/Insiders/VC/Auxiliary/Build/vcvars64.bat'
$dependencyRoot = Join-Path $projectRoot '.tools/vulkan-deps'
$vulkanSdkRoot = Join-Path $projectRoot '.tools/vulkan-sdk'
$buildRoot = Join-Path $projectRoot '.tools/vulkan-bench'

# Import only build-related variables. Do not change user/profile directories.
$environmentScript = Join-Path $projectRoot '.tools/vulkan-build-env.cmd'
@("@call `"$vsEnvironment`" >nul", '@set') | Set-Content -LiteralPath $environmentScript -Encoding ascii
$buildEnvironment = & cmd.exe /d /c $environmentScript
if ($LASTEXITCODE) { throw 'MSVC environment initialization failed' }
foreach ($line in $buildEnvironment) {
    if ($line -match '^(PATH|INCLUDE|LIB|LIBPATH|VCToolsInstallDir|WindowsSdkDir|WindowsSDKVersion)=(.*)$') {
        [Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process')
    }
}

New-Item -ItemType Directory -Path $dependencyRoot,$vulkanSdkRoot -Force | Out-Null
foreach ($repository in @('Vulkan-Headers', 'Vulkan-Hpp', 'SPIRV-Headers')) {
    $destination = Join-Path $dependencyRoot $repository
    if (-not (Test-Path (Join-Path $destination '.git'))) {
        & $gitPath clone --depth 1 "https://github.com/KhronosGroup/$repository.git" $destination
        if ($LASTEXITCODE) { throw "Clone failed: $repository" }
    }
}
$includeRoot = Join-Path $vulkanSdkRoot 'include'
New-Item -ItemType Directory -Path $includeRoot -Force | Out-Null
Copy-Item -Path (Join-Path $dependencyRoot 'Vulkan-Headers/include/*') -Destination $includeRoot -Recurse -Force
Copy-Item -Path (Join-Path $dependencyRoot 'Vulkan-Hpp/vulkan/*') -Destination (Join-Path $includeRoot 'vulkan') -Recurse -Force
Copy-Item -Path (Join-Path $dependencyRoot 'SPIRV-Headers/include/spirv') -Destination $includeRoot -Recurse -Force

$spirvBuild = Join-Path $dependencyRoot 'spirv-cmake'
& $cmakePath -S (Join-Path $dependencyRoot 'SPIRV-Headers') -B $spirvBuild -G Ninja "-DCMAKE_MAKE_PROGRAM=$ninjaPath" "-DCMAKE_INSTALL_PREFIX=$vulkanSdkRoot" -DSPIRV_HEADERS_ENABLE_TESTS=OFF -DSPIRV_HEADERS_ENABLE_INSTALL=ON
if ($LASTEXITCODE) { throw 'SPIRV-Headers configure failed' }
& $cmakePath --install $spirvBuild
if ($LASTEXITCODE) { throw 'SPIRV-Headers install failed' }

$revisions = @{}
foreach ($repository in @('Vulkan-Headers', 'Vulkan-Hpp', 'SPIRV-Headers')) {
    $revisions[$repository] = (& $gitPath -C (Join-Path $dependencyRoot $repository) rev-parse HEAD).Trim()
}
$revisions | ConvertTo-Json | Set-Content (Join-Path $vulkanSdkRoot 'dependency-revisions.json')

$configure = @('-S', (Join-Path $projectRoot '.tools/llama.cpp'), '-B', $buildRoot, '-G', 'Ninja',
    "-DCMAKE_MAKE_PROGRAM=$ninjaPath", "-DCMAKE_TOOLCHAIN_FILE=$ndkRoot/build/cmake/android.toolchain.cmake",
    '-DANDROID_ABI=arm64-v8a', '-DANDROID_PLATFORM=android-28', '-DANDROID_STL=c++_static',
    '-DCMAKE_BUILD_TYPE=Release', '-DBUILD_SHARED_LIBS=OFF', '-DGGML_NATIVE=OFF',
    '-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16', '-DGGML_OPENMP=OFF', '-DGGML_LLAMAFILE=OFF',
    '-DGGML_CPU_KLEIDIAI=OFF', '-DGGML_BACKEND_DL=OFF', '-DGGML_VULKAN=ON',
    '-DLLAMA_BUILD_COMMON=ON', '-DLLAMA_BUILD_TOOLS=ON', '-DLLAMA_BUILD_APP=OFF',
    '-DLLAMA_BUILD_TESTS=OFF', '-DLLAMA_BUILD_SERVER=OFF', '-DLLAMA_BUILD_EXAMPLES=OFF', '-DLLAMA_OPENSSL=OFF',
    "-DVulkan_INCLUDE_DIR=$includeRoot", "-DVulkan_LIBRARY=$ndkRoot/toolchains/llvm/prebuilt/windows-x86_64/sysroot/usr/lib/aarch64-linux-android/28/libvulkan.so",
    "-DVulkan_GLSLC_EXECUTABLE=$ndkRoot/shader-tools/windows-x86_64/glslc.exe",
    "-DSPIRV-Headers_DIR=$vulkanSdkRoot/share/cmake/SPIRV-Headers")
& $cmakePath @configure
if ($LASTEXITCODE) { throw 'Vulkan benchmark configure failed' }
& $cmakePath --build $buildRoot --target llama-bench -j $Jobs
if ($LASTEXITCODE) { throw 'Vulkan benchmark build failed' }
Write-Output (Join-Path $buildRoot 'bin/llama-bench')
