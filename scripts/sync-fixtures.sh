#!/usr/bin/env bash
# shared/fixtures is the source of truth; the iOS test bundle needs its own copy (SwiftPM resources must live inside the target).
set -euo pipefail
cd "$(dirname "$0")/.."
rm -f ios/EasyEsuiteKit/Tests/EasyEsuiteKitTests/Fixtures/*.json
cp shared/fixtures/*.json ios/EasyEsuiteKit/Tests/EasyEsuiteKitTests/Fixtures/
echo "synced $(ls shared/fixtures/*.json | wc -l | tr -d ' ') fixtures"
