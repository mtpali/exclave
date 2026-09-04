#!/bin/bash

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CGO_LDFLAGS="-Wl,-z,max-page-size=16384" bash "$script_dir/with_amnezia_core.sh" \
  gomobile bind -v -androidapi 21 -trimpath -ldflags="-s -buildid=" -tags="with_clash" \
  "github.com/exclavenetwork/libexclavecore" || exit 1

proj=../../app/libs
if [ -d $proj ]; then
  cp -vf libexclavecore.aar $proj
fi
