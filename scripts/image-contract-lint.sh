#!/usr/bin/env bash
# image-contract-lint.sh - D-015 image repository ref contract gate
#
# Purpose:
#   Fail when the image reference CI builds/pushes diverges from the reference
#   K8s manifests pull / rewrite. A mismatch means the cluster pulls an image CI
#   never produced — the D-015 break: CI pushed `peekcart`, manifests and the GKE
#   rewrite source referenced `peakcart`, so base deploy / GHCR->AR copy fail.
#
# What is compared:
#   The full `owner/repo` path of each GHCR reference (registry host stripped,
#   tag stripped, lowercased) — not just the repo basename, so an owner-segment
#   drift is caught too. CI's IMAGE_NAME uses the GitHub Actions expression
#   ${{ github.repository_owner }}; it is resolved to EXPECTED_OWNER below so the
#   owner segment is comparable statically.
#
# Sources:
#   - .github/workflows/ci.yml                    env.IMAGE_NAME (what CI pushes)
#   - k8s/base/services/peekcart/deployment.yml   container image (what cluster pulls)
#   - k8s/overlays/gke/kustomization.yml          images[].name (rewrite source)
#
# Exit:
#   0 - refs consistent
#   1 - mismatch
#   2 - a reference could not be extracted (file structure changed)

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

# Resolve the CI owner expression to the *runtime* owner so a runtime owner drift
# (CI pushes to a different owner than the manifests reference) is caught, not
# just an in-file edit. GitHub Actions sets GITHUB_REPOSITORY_OWNER; fall back to
# the known owner for local runs. Lowercased for GHCR.
EXPECTED_OWNER="${GITHUB_REPOSITORY_OWNER:-kimgyuilli}"
EXPECTED_OWNER="${EXPECTED_OWNER,,}"

OWNER_TOKEN='${{ github.repository_owner }}'

# Extract the first non-comment GHCR ref on a "<key>:" line. Returns empty
# (not a failure) when nothing matches, so the validation loop below can report
# exit 2 instead of `set -e` aborting at the assignment.
extract() {
    local key="$1" file="$2"
    { grep -E "${key}:[[:space:]]*ghcr\.io/" "$file" \
        | grep -vE '^[[:space:]]*#' \
        | head -1 \
        | sed -E "s/.*${key}:[[:space:]]*//; s/[[:space:]]*(#.*)?$//"; } || true
}

# Normalize a GHCR ref to "owner/repo": resolve the CI owner expression, drop the
# registry host, strip the :tag, lowercase.
normalize() {
    local ref="$1"
    ref="${ref/$OWNER_TOKEN/$EXPECTED_OWNER}"
    ref="${ref#ghcr.io/}"
    ref="${ref%:*}"
    printf '%s' "${ref,,}"
}

ci_raw="$(extract 'IMAGE_NAME' .github/workflows/ci.yml)"
dep_raw="$(extract 'image' k8s/base/services/peekcart/deployment.yml)"
gke_raw="$(extract 'name' k8s/overlays/gke/kustomization.yml)"

for pair in "ci.yml:$ci_raw" "deployment.yml:$dep_raw" "gke/kustomization.yml:$gke_raw"; do
    if [[ -z "${pair#*:}" ]]; then
        echo "[D-015] could not extract image ref from ${pair%%:*} — file structure changed?" >&2
        exit 2
    fi
done

ci_path="$(normalize "$ci_raw")"
dep_path="$(normalize "$dep_raw")"
gke_path="$(normalize "$gke_raw")"

if [[ "$ci_path" == "$dep_path" && "$ci_path" == "$gke_path" ]]; then
    echo "[D-015] image ref contract OK: '$ci_path' (ci == base == gke)"
    exit 0
fi

echo "[D-015] image ref mismatch — cluster would pull an image CI never pushed:" >&2
echo "  ci.yml IMAGE_NAME           -> $ci_path  ($ci_raw)" >&2
echo "  base deployment image       -> $dep_path  ($dep_raw)" >&2
echo "  gke kustomization image src -> $gke_path  ($gke_raw)" >&2
exit 1
