#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"
IMAGE="reops-raw-to-clean:cve-check"

echo "== Build native image with fresh base images =="
docker build --pull --no-cache -t "$IMAGE" .

# Runtime requires Kafka; scan without starting an unconfigured consumer.
echo "== Grype scan =="
grype "$IMAGE" -o table
