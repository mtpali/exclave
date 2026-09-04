#!/usr/bin/env bash

set -euo pipefail

if [[ $# -eq 0 ]]; then
    echo "Usage: $0 <command> [arguments...]" >&2
    exit 2
fi

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
core_module="github.com/exclavenetwork/exclave-core/v5"
core_patch="$script_dir/patches/exclave-core-amneziawg.patch"
task_build_dir="$(mktemp -d)"
patched_core="$task_build_dir/exclave-core"
wrapper_module="$task_build_dir/wrapper"

cleanup() {
    chmod -R u+w "$task_build_dir" 2>/dev/null || true
    rm -rf -- "$task_build_dir"
}
trap cleanup EXIT

cd "$script_dir"
GOWORK=off go mod download "$core_module"
core_source="$(GOWORK=off go list -m -f '{{.Dir}}' "$core_module")"
if [[ -z "$core_source" || ! -d "$core_source" ]]; then
    echo "Unable to locate $core_module in the Go module cache" >&2
    exit 1
fi

mkdir -p "$patched_core"
cp -a "$core_source/." "$patched_core/"
chmod -R u+w "$patched_core"
patch --batch --forward --silent -p1 -d "$patched_core" < "$core_patch"

mkdir -p "$wrapper_module"
cp "$script_dir/go.mod" "$script_dir/go.sum" "$wrapper_module/"
(
    cd "$wrapper_module"
    GOWORK=off go mod edit -replace="$core_module=$patched_core"
    GOWORK=off "$@"
)
