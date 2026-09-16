#!/usr/bin/env bash

set -Eeuo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "$script_dir/.." && pwd)"
output_root="$repo_root/app/src/main/jniLibs"
ndk_home="${ANDROID_NDK_HOME:-}"
go_command="${GO_COMMAND:-go}"

die() {
    printf 'build-android.sh: %s\n' "$*" >&2
    exit 1
}

if [[ -z "$ndk_home" ]]; then
    die "ANDROID_NDK_HOME is not set. Gradle supplies the resolved Android NDK."
fi
[[ -d "$ndk_home" ]] || die "Android NDK directory does not exist: $ndk_home"

if ! command -v "$go_command" >/dev/null 2>&1; then
    die "Go command '$go_command' was not found; refusing to package stale JNI binaries."
fi
"$go_command" version >/dev/null 2>&1 ||
    die "Go command '$go_command' is not usable."

case "$(uname -s)" in
    Linux)
        host_tag="linux-x86_64"
        ;;
    Darwin)
        if [[ -d "$ndk_home/toolchains/llvm/prebuilt/darwin-arm64" ]]; then
            host_tag="darwin-arm64"
        else
            host_tag="darwin-x86_64"
        fi
        ;;
    *)
        die "Unsupported host for this script: $(uname -s). Use build-android.ps1 on Windows."
        ;;
esac

toolchain="$ndk_home/toolchains/llvm/prebuilt/$host_tag/bin"
[[ -d "$toolchain" ]] || die "NDK LLVM toolchain directory does not exist: $toolchain"

declare -a abis=("arm64-v8a" "armeabi-v7a" "x86" "x86_64")
declare -a go_arches=("arm64" "arm" "386" "amd64")
declare -a compilers=(
    "aarch64-linux-android24-clang"
    "armv7a-linux-androideabi24-clang"
    "i686-linux-android24-clang"
    "x86_64-linux-android24-clang"
)

for compiler in "${compilers[@]}"; do
    [[ -x "$toolchain/$compiler" ]] ||
        die "Android API 24 compiler is missing from the NDK: $toolchain/$compiler"
done

cd -- "$script_dir"

# These packages contain the loader, archive/unpack integrity checks, and the
# Android command entry point. Run them before producing any replacement files.
(
    unset GOOS GOARCH GOARM CGO_ENABLED CC
    "$go_command" test ./pkg/usenet/pool ./pkg/media/loader ./pkg/media/unpack ./cmd/nuvio-nntp
)

stage_root="$(mktemp -d "${TMPDIR:-/tmp}/nuvio-nntp.XXXXXX")"
cleanup() {
    rm -rf -- "$stage_root"
}
trap cleanup EXIT

for index in "${!abis[@]}"; do
    abi="${abis[$index]}"
    go_arch="${go_arches[$index]}"
    compiler="${compilers[$index]}"
    stage_dir="$stage_root/$abi"
    mkdir -p -- "$stage_dir"

    (
        export GOOS=android
        export GOARCH="$go_arch"
        export CGO_ENABLED=1
        export CC="$toolchain/$compiler"
        if [[ "$abi" == "armeabi-v7a" ]]; then
            export GOARM=7
        else
            unset GOARM
        fi

        "$go_command" build \
            -buildmode=pie \
            -trimpath \
            -ldflags="-s -w -buildid=" \
            -o "$stage_dir/libnuvionntp.so" \
            ./cmd/nuvio-nntp
    )
done

# Do not modify the packaged set until every ABI has compiled successfully.
for abi in "${abis[@]}"; do
    output_dir="$output_root/$abi"
    mkdir -p -- "$output_dir"
    cp -- "$stage_root/$abi/libnuvionntp.so" "$output_dir/libnuvionntp.so"
    chmod 0644 "$output_dir/libnuvionntp.so"
done
