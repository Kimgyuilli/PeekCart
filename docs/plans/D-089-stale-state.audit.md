# D-089-stale-state audit

## 2026-10-24 09:36 — diff 리뷰
- 상태: 의도적 생략(file: 사용자 지시 (2026-09-22): Codex 리뷰를 호출하지 않는다.)
- 검증: `ci-publication-state-selftest.py` 11 테스트 0 실패, 잘못된 구현 11종(신규 4) 전부 거부. 신규 양성 2건은 원래 관측기(`origin/main` 6052c2f 의 `ci-publication-state.py`)에서 exit 1 stale 로 실패해 false failure 를 재현했고, 신규 음성 1건은 원래 관측기에서도 통과(fail-closed 유지). `ci-release-gate-lint.sh` OK, `--self-test` 85/85, `ci-lint-wiring-lint.py`·`ci-lint-health-selftest.py` OK
- 미실행: actionlint(로컬 바이너리 없음, `ci.yml` 무변경), 전체 `./gradlew test`(Java 변경 없음, PR CI 가 수행), 실제 main 관측(publication-state 는 main push 에서만 돈다)
- 범위 밖 관찰: `cancel-in-progress` 로 publish 가 중간에 취소되고 다음 push 가 publish 하지 못하면 latest 가 섞인 채 남고 이를 알리는 observer 가 없다. root 에 보고

## 2026-10-24 — /ship
- PR: https://github.com/Kimgyuilli/PeekCart/pull/234
- precheck: preflight ok, consistency ok(warnings 0), review health ok, 문체 lint 통과(커밋·본문·제목)
- 갱신: D-092 와 병렬로 진행해 TASKS·progress 는 root 기록 PR 에서 갱신한다. 병합은 사용자 확인 뒤 진행한다
