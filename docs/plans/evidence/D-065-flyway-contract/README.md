# D065 actual Flyway evidence

`first-attempt`는 exactfd521 / Actions39582861319의 실패 증거다. 52child 뒤 실제MySQL restart 명령은 성공했지만 기존endpoint의30초readiness가ConnectException으로실패했다. complete=false, 후속24child와재시작보존단언은미실행이다. 해당실행의restart뒤port는기록하지않아역사적원인을단정하지않는다.

`actions-39583989047.tar.gz`는 수정headf834의 Actions 원본JSON/로그/SQLfixture/JUnit/cleanup이다. checkout은PR merge ref이며 별도manifest에exactSHA와모든파일rawSHA256을보존한다. 임의로실제실행을재구성한fixture가아니다.

- 일반payment54 XML/334 PASS와baseline testcase multiset정확일치
- 전용1test PASS103.298초, 실제4서비스, Flyway11.7.2, uniquechildJVM76(migrate48/validate28), 의도된실패28
- 동일immutablecontainerID의StartedAt증가 및 hostport32775에서32776변경을실측했다. 새endpoint로readiness와후속24child검증이통과했다.
- fresh/upgrade, callback거부후명시적fixture입력수정으로재개, checksum변조차단후정확원문복원, 첫DDL·앞DMLcommit/둘째DDL실패와failedhistory차단, 실제DBrestart뒤schema/data/history/allocator보존을검증했다. repair/clean/history삭제는수행하지않았다.
- alwayscleanup은이번실행의Testcontainers4개를제거하고성공했다.

현재repo의 `scripts/flyway-contract-evidence.py`의verify함수에추출한lab/JUnit/정상JUnit디렉터리와현재원문migrationroot를제공하면같은증거완전성검사를실행할수있다. source가바뀌면원문manifest불일치로실패해야한다. 15개hermeticcontrols는검사기반례이며이actualSQL/JVM증거와다르다.

제품migration변경, 실제운영legacy표본/oldwriter중지,배포또는운영DDL은포함하지않는다. 성공한과거head의증거이며최신main통합후최종head의별도Actions가필요하다. 전체D065완료를뜻하지않는다.
