#!/usr/bin/env bash
# 빌드 artifact, e2e 로드 이미지, GHCR manifest 의 config digest 를 연결한다.
set -euo pipefail

fail() { echo "[ci-image-identity] $*" >&2; exit 1; }

image_id() {
  docker image inspect --format '{{.Id}}' "$1"
}

valid_id() {
  [[ "$1" =~ ^sha256:[0-9a-f]{64}$ ]]
}

record() {
  local tag="$1" dir="$2" id
  [[ -f "$dir/image.tar.gz" ]] || fail "image.tar.gz 부재: $dir"
  id="$(image_id "$tag")"
  valid_id "$id" || fail "이미지 ID 형식 오류: $id"
  printf '%s\n' "$id" > "$dir/image-id.txt"
  (cd "$dir" && sha256sum image.tar.gz > image.tar.gz.sha256)
}

load_image() {
  local tag="$1" dir="$2" expected actual
  [[ -f "$dir/image-id.txt" && -f "$dir/image.tar.gz.sha256" ]] || fail "이미지 메타데이터 부재: $dir"
  expected="$(cat "$dir/image-id.txt")"
  valid_id "$expected" || fail "기록된 이미지 ID 형식 오류: $expected"
  (cd "$dir" && sha256sum --check image.tar.gz.sha256) || fail "artifact checksum 불일치: $tag"
  gunzip -c "$dir/image.tar.gz" | docker load
  actual="$(image_id "$tag")"
  [[ "$actual" == "$expected" ]] || fail "로드 이미지 ID 불일치: $tag expected=$expected actual=$actual"
}

verify_remote() {
  local repo="$1" digest="$2" id_file="$3" expected remote
  [[ "$digest" =~ ^sha256:[0-9a-f]{64}$ ]] || fail "원격 digest 형식 오류: $digest"
  expected="$(cat "$id_file")"
  valid_id "$expected" || fail "기록된 이미지 ID 형식 오류: $expected"
  remote="$(docker buildx imagetools inspect --raw "$repo@$digest" |
    python3 -c 'import json,sys; print(json.load(sys.stdin).get("config", {}).get("digest", ""))')"
  [[ "$remote" == "$expected" ]] || fail "원격 config digest 불일치: expected=$expected actual=$remote"
}

self_test() {
  local tmp self id other
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN
  self="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"
  mkdir -p "$tmp/bin" "$tmp/artifact"
  id="sha256:$(printf 'a%.0s' {1..64})"
  other="sha256:$(printf 'b%.0s' {1..64})"
  cat > "$tmp/bin/docker" <<'SH'
#!/usr/bin/env bash
case "$1 $2 $3" in
  'image inspect --format') printf '%s\n' "$CI_IMAGE_TEST_ID" ;;
  'buildx imagetools inspect') printf '{"config":{"digest":"%s"}}\n' "$CI_IMAGE_TEST_REMOTE_ID" ;;
  'load  ') cat >/dev/null; echo 'Loaded test image' ;;
  *) exit 2 ;;
esac
SH
  chmod +x "$tmp/bin/docker"
  export PATH="$tmp/bin:$PATH" CI_IMAGE_TEST_ID="$id" CI_IMAGE_TEST_REMOTE_ID="$id"
  printf 'test-image' | gzip > "$tmp/artifact/image.tar.gz"
  bash "$self" record example:ci "$tmp/artifact"
  bash "$self" load example:ci "$tmp/artifact" >/dev/null
  bash "$self" verify-remote example "$id" "$tmp/artifact/image-id.txt"
  CI_IMAGE_TEST_ID="$other" bash "$self" load example:ci "$tmp/artifact" >/dev/null 2>&1 && fail '다른 로드 이미지 ID 를 허용함'
  CI_IMAGE_TEST_REMOTE_ID="$other" bash "$self" verify-remote example "$id" "$tmp/artifact/image-id.txt" >/dev/null 2>&1 && fail '다른 원격 config digest 를 허용함'
  printf 'corrupt' >> "$tmp/artifact/image.tar.gz"
  bash "$self" load example:ci "$tmp/artifact" >/dev/null 2>&1 && fail '손상된 artifact 를 허용함'
  echo 'ci-image-identity self-test OK (정상 2 · 불일치 3)'
}

case "${1:-}" in
  record) [[ $# -eq 3 ]] || fail 'record <tag> <artifact-dir>'; record "$2" "$3" ;;
  load) [[ $# -eq 3 ]] || fail 'load <tag> <artifact-dir>'; load_image "$2" "$3" ;;
  verify-remote) [[ $# -eq 4 ]] || fail 'verify-remote <repo> <manifest-digest> <image-id-file>'; verify_remote "$2" "$3" "$4" ;;
  --self-test) self_test ;;
  *) fail 'record|load|verify-remote|--self-test' ;;
esac
