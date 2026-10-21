# D-078 finite Kafka retry observations

The first two runs failed and remain failed evidence. The third product shard passed; its whole CI is a separate pending requirement. This establishes a finite controlled correctness fixture, not D-078 part5 sustained-load completion or a jitter performance advantage.

| Run / source | Result | Durable originals |
| --- | --- | --- |
| 39627945741 / f2ffe2e | Four dependency-injection failures before setup, zero retry rows; ambiguous handler. Run later superseded/canceled. | first-product.xml.gz, first-product-job.log.gz, first-failure.json |
| 39628723409 / 1881ddd | Four failures,108 rows, all nonoptimistic, no inventory arrival. Invalid44-character eventId versus VARCHAR36; precise old exception chain unavailable. Run later superseded. | second-product.xml.gz, second-product-job.log.gz, second-failure.json |
| 39630546332 / b933806 | Product job119327788981 SUCCESS,47 suites/240 cases/zero failure/error/skip; target4/0,81 rows. | third-product-xml.tar.gz (all47 original XMLs), third-product-job.log.gz, third-observation.json |

Third checkout is `b09fd148271c5ea7f57a9fd334e655bee4dbb9cb`, merging measured source `b933806004801b10e0617c32626af691bea4df76` into actual CI base `7320ffdd4a72a1bdde77bc9424c8d11ecff18ba3`. Author branch originally starts at8f0d2ee. Seed1792598420536 controls class order only; actual runner Temurin17.0.20+1 is in the job log. Test uses real MySQL8.0.46/Kafka3.8.1/Redis7 singleton fixtures and the real listener/transaction/handler/sleeper. No external Slack/PG call.

| Scenario, one observation per mode | Fixed | Jitter |
| --- | --- | --- |
| Original records / original deliveries / optimistic failure callbacks | 3 / 6 / 3 | 3 / 5 / 2 |
| Additional duplicate deliveries | 3 | 3 |
| Final stock / reservation,outbox,processed rows | 97 / 3,3,3 | 97 / 3,3,3 |
| Observed matching transient DLQ records at broker-end snapshot | 0 | 0 |
| Persistent deliveries / DLQ records | 4 / 1 | 4 / 1 |
| Persistent issued delays(ms), then STOP | 1000,5000,30000 | 793,4973,29107 |
| Persistent failure-log intervals(ms), rounded | 1011.90,5015.24,30034.70 | 813.83,5013.70,29133.38 |
| Persistent original source coordinate | order.created/0/4 | order.created/0/5 |
| Persistent final stock / reservation,outbox,processed rows | 100 / 0,0,0 | 100 / 0,0,0 |

Each transient mode logged three distinct consumer threads reading version0 at one barrier identity and exactly one completed generation. Both converged with all failure callbacks optimistic; duplicate delivery caused no second database effect. Four cleanup rows say stopped=true. Persistent failure is explicitly injected after a real read. Original topic/partition/offset/key/value and final database counts are verified by the executed passing assertions; the XML is not an independent database or DLQ-header dump. Duplicate records are excluded from the original-delivery denominator above. Issued-delay lists exclude STOP; the raw rows/manifest preserve it.

Raw XML testcase display names repeat between the two parameterized methods; scenario mapping is given by the explicit result rows and source methods. Total case durations include unequal setup/rebalance and must not be compared as performance measurements. One burst per mode, unseeded jitter, controlled initial contention and no sustained traffic do not establish a conflict-rate improvement, acceptable DLQ rate, operating TPS, pessimistic-lock promotion or GKE/node cause. The original1Timeout+4Broken barrier event remains unresolved; scheduler adequacy is separate.

The JSON files record compressed and original-byte SHA256 values. The third manifest lists every XML member hash, fixture source hash, exact checkout parents, selected rows and case counts. Inspect originals with `tar -xOzf third-product-xml.tar.gz product-service/build/test-results/test/TEST-com.peekcart.product.infrastructure.StockRetryLoadIntegrationTest.xml` and `gzip -dc third-product-job.log.gz`. Archives preserve original bytes and omit redundant HTML reports. No nonfixture credentials were identified.
