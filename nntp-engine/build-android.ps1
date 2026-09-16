param(
    [string]$NdkHome = $env:ANDROID_NDK_HOME,
    [string]$GoCommand = "go"
)

$ErrorActionPreference = "Stop"

if ($GoCommand -eq "go" -and -not [string]::IsNullOrWhiteSpace($env:GO_COMMAND)) {
    $GoCommand = $env:GO_COMMAND
}

if ([string]::IsNullOrWhiteSpace($NdkHome)) {
    throw "Set ANDROID_NDK_HOME or pass -NdkHome."
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$outputRoot = Join-Path $repoRoot "app/src/main/jniLibs"
$toolchain = Join-Path $NdkHome "toolchains/llvm/prebuilt/windows-x86_64/bin"

$targets = @(
    @{ Abi = "arm64-v8a"; GoArch = "arm64"; Cc = "aarch64-linux-android24-clang.cmd" },
    @{ Abi = "armeabi-v7a"; GoArch = "arm"; GoArm = "7"; Cc = "armv7a-linux-androideabi24-clang.cmd" },
    @{ Abi = "x86"; GoArch = "386"; Cc = "i686-linux-android24-clang.cmd" },
    @{ Abi = "x86_64"; GoArch = "amd64"; Cc = "x86_64-linux-android24-clang.cmd" }
)

if (-not (Test-Path -LiteralPath $NdkHome -PathType Container)) {
    throw "Android NDK directory does not exist: $NdkHome"
}
if (-not (Get-Command $GoCommand -ErrorAction SilentlyContinue)) {
    throw "Go command '$GoCommand' was not found; refusing to package stale JNI binaries."
}
& $GoCommand version | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "Go command '$GoCommand' is not usable."
}
if (-not (Test-Path -LiteralPath $toolchain -PathType Container)) {
    throw "NDK LLVM toolchain directory does not exist: $toolchain"
}
foreach ($target in $targets) {
    $compiler = Join-Path $toolchain $target.Cc
    if (-not (Test-Path -LiteralPath $compiler -PathType Leaf)) {
        throw "Android API 24 compiler is missing from the NDK: $compiler"
    }
}

Push-Location $PSScriptRoot
$stageRoot = Join-Path ([IO.Path]::GetTempPath()) ("nuvio-nntp-" + [Guid]::NewGuid().ToString("N"))
try {
    # Keep the loader, archive/unpack integrity checks, and Android command
    # entry point as a precondition for replacing any packaged binary.
    & $GoCommand test ./pkg/usenet/pool ./pkg/media/loader ./pkg/media/unpack ./cmd/nuvio-nntp
    if ($LASTEXITCODE -ne 0) {
        throw "Focused NNTP integrity tests failed."
    }

    New-Item -ItemType Directory -Force -Path $stageRoot | Out-Null
    foreach ($target in $targets) {
        $env:GOOS = "android"
        $env:GOARCH = $target.GoArch
        $env:CGO_ENABLED = "1"
        $env:CC = Join-Path $toolchain $target.Cc
        if ($target.GoArm) { $env:GOARM = $target.GoArm } else { Remove-Item Env:GOARM -ErrorAction SilentlyContinue }

        $stageDir = Join-Path $stageRoot $target.Abi
        New-Item -ItemType Directory -Force -Path $stageDir | Out-Null
        $outputFile = Join-Path $stageDir "libnuvionntp.so"
        & $GoCommand build -buildmode=pie -trimpath -ldflags="-s -w -buildid=" -o $outputFile ./cmd/nuvio-nntp
        if ($LASTEXITCODE -ne 0) { throw "Go build failed for $($target.Abi)." }
    }

    # Do not modify the packaged set until every ABI has compiled successfully.
    foreach ($target in $targets) {
        $outputDir = Join-Path $outputRoot $target.Abi
        New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
        Copy-Item -Force `
            (Join-Path (Join-Path $stageRoot $target.Abi) "libnuvionntp.so") `
            (Join-Path $outputDir "libnuvionntp.so")
    }
} finally {
    Pop-Location
    if (Test-Path -LiteralPath $stageRoot) {
        Remove-Item -Recurse -Force -LiteralPath $stageRoot
    }
}
