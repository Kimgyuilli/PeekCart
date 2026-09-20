# preflight — D-026 배속 재측정 + D-002a 읽기 경로 천장 (2026-09-20)

> 계획서 `docs/plans/task-d026-d002a-read-ceiling-session.md` P0.
> **왜 이 파일이 있나 (계획 리뷰 라운드 1 지적 #13).** 계획서 §2 의 V1~V4·V12 는 **변하는 상태**다
> — 계정 소유, 쿼터, 자원 0건, AR 생성 시각. 계획 본문의 숫자만으로는 나중에 재검증할 수 없다.
> 명령·시각·원시 출력을 여기 남긴다. 원시 출력 전문: `.cache/preflight/raw.txt` (미추적).

- 수집 시각(UTC): `2026-09-19T16:44:53Z`
- git HEAD: `b0d8fd946e6049f914230e10a3aa870d3262a003` · 브랜치 `chore/d026-d002a-read-ceiling-session`
- gcloud configuration: **`peekcart`** (account `momens.dev@gmail.com` · project `peekcart-gke` · zone `asia-northeast3-a`)

## V1 — 프로젝트 소유 계정

**초안 전제가 틀렸다.** `peekcart-gke` 는 활성 계정 `rlarbdlf222@gmail.com` 에 **없다**.
소유는 `momens.dev@gmail.com`(프로젝트 번호 1076531038524). 기존 `gcloud config` 는 무관한
`momens-prod`(`project-5cd81b6a-35c0-4547-be8`)를 가리키고 있었고 그 계정엔
`compute.instances.list` 권한도 없다.

**조치**: 별도 configuration `peekcart` 생성·활성화. 기존 `default` 는 건드리지 않았다.
복귀 = `gcloud config configurations activate default` (세션 종료 시 P8 에서 수행).

## V3 — 자원 현황 (세션 시작 전)

| 자원 | 수 |
|---|---|
| `container clusters list` | **0** |
| `compute instances list` | **0** |

이 값이 P8 종료 검증의 **기준선**이다. 종료 후 같은 두 명령이 0 을 반환해야 한다.

## V4 — 쿼터 (asia-northeast3)

| metric | limit | usage |
|---|---|---|
| `E2_CPUS` | **24.0** | 0.0 |
| `CPUS` | 100.0 | 0.0 |
| `IN_USE_ADDRESSES` | 8.0 | 0.0 |
| `SSD_TOTAL_GB` | 250.0 | 0.0 |
| `DISKS_TOTAL_GB` | 2048.0 | 0.0 |

**d002a 계획서의 "쿼터 12 vCPU" 는 정정된다 — 24 다.** 계획 형상(노드 8 + loadgen 2 = 10)은
그대로 유지한다. 여유가 아니라 **기준선과의 비교 가능성**이 형상을 정하기 때문이다(N2).

## V2 — AR 이미지 (재빌드 필요 판정)

```
asia-northeast3-docker.pkg.dev/peekcart-gke/peekcart/product-service
  sha256:8f4cfaecd76a228b6c82893f448a6f12f57df495f00ffbcfd5656b00f831e741
  tags=latest  create=2026-09-14T22:07:45
```

**태그가 `latest` 하나뿐이고 2026-09-14 다.** D-026 캐시 커밋 `5536130`·테스트 `7a353ef` 는
**2026-09-18** 이다 → 이 이미지에는 `productStock` 캐시가 **없다**.

d002a 세션 노트의 "재빌드 불필요" 는 **그 시점에만 참이었고 지금은 거짓**이다.
그 판단이 overlay 에 `:latest` 로 박혀 있어 이번 세션이 구버전을 잴 뻔했다 → P2 가
**digest 로 핀**하는 이유(N3).

## V12 — 로컬 도구

| 도구 | 상태 |
|---|---|
| `gcloud` | 있음 |
| `kubectl` | 있음 |
| `kustomize` | v5.8.1 |
| `docker` | 있음 · 데몬 **running** (P2 빌드 가능) |
| `k6` | **없음** |

k6 부재는 문제가 아니다 — 기준선도 **loadgen VM 별도**에서 돌렸다(co-location 경합을 구조적으로
제거하려는 의도적 설계). 로컬 설치로 갈음하면 네트워크가 변수로 들어와 N2 를 위반한다.

## 부수 발견 — `gke-d002a` 는 기본 설정으로 렌더되지 않는다 (선재)

```
Error: accumulating resources from '../../base/namespace.yml': security;
file ... is not in or below '.../k8s/overlays/gke-d002a'
```

`../../base/*.yml` 을 **파일로 직접 참조**해 kustomize root 이탈 제한에 걸린다.
**이 세션 이전부터 그랬다** — HEAD 상태에서도 동일하게 실패하는 것을 확인했다.
즉 이 측정 overlay 는 기본 설정으로 **한 번도 렌더된 적이 없다**.

구조 수정은 이번 범위 밖이라, 렌더 명령에
`--load-restrictor LoadRestrictionsNone` 을 **정본으로 명시**하고 overlay 주석에 사유를 남겼다.

## P3 직전 재실행

위 V1·V3·V4 는 P3(클러스터 생성) 직전에 **다시 실행**한다 — 자원 0건 전제가 그 사이에
바뀌었을 수 있고, 그것을 모르고 만들면 쿼터 충돌이나 중복 과금이 난다.
