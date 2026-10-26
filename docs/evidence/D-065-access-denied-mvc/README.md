# D-065 MVC 권한 거부 응답 증적

계획: [D-065-access-denied-mvc](../../plans/done/D-065-access-denied-mvc.md). 2026-10-21 병렬 부채 캠페인에서 GitHub Actions 결과를 내려받아 둔 사본이다. 2026-10-26 D-116 에서 이 README 를 더했다.

| 파일 | 내용 |
|---|---|
| `original-handler-manifest.json` | 수정 전 head `47457c3`, run 39588457231 의 `AccessDeniedMvcContractTest` 결과. tests 3, failures 2 |
| `TEST-com.peekcart.global.security.AccessDeniedMvcContractTest.xml.gz` | 위 run 의 JUnit XML |
| `pr211-original39588457231-platform.log.gz` | 위 run 의 platform job 로그 |
| `fixed-handler-manifest.json` | 수정 뒤 head `d882fec`, run 39588911211. tests 3, failures 0, errors 0, common-auth 테스트 60 |
| `fixed-AccessDeniedMvcContractTest.xml.gz` | 수정 뒤 run 의 JUnit XML |

원본 run 의 artifact 는 2026-10-28 과 2027-01-19 에 만료된다. 그 뒤로는 이 사본이 유일본이다.
