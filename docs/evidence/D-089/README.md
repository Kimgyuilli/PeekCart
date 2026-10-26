# D-089 source and observation evidence

## Historical failure and current availability

The original [run38973369532](https://github.com/Kimgyuilli/PeekCart/actions/runs/38973369532), source92755566074fc28d78e53dd84b2b085a7fbfdb38, had payment publish117221839633 fail at2026-10-16T22:18:34Z with `unknown blob`/exit1; the other5 publishers succeeded. `payment-publish-failed.log.gz`, `user-publish-success.log.gz` and `original-run.json.gz` preserve those separate observations. The failing command substitution did not echo its captured stdout, so layer/request/server detail is absent. `recovery-run-jobs.json.gz` records6 successful publishers in the later distinct run38981041166; it does not establish the original cause.

The current Actions artifact API snapshot (`original-artifacts-now.json.gz`) lists16 retained artifacts but no original image payloads. Existing source retention is1day for image artifacts; this is current absence, not a claim that the API reports an expired payload entry. Build records precede publishing and cannot supply the missing registry push server trace.

Read-only anonymous GHCR GETs on2026-10-21T11:50Z found the original SHA tag present in5 repositories and absent(404) in payment. `historical-sha-current-availability.json.gz`,5 raw manifest responses and the payment error/response metadata preserve this. Current availability is not the original latest snapshot or a root-cause determination. No registry writes occurred. The pull token was held in memory only and was not saved. Missing historical evidence remains unresolved.

## Actual mixed-attempt Actions artifact behavior

[Run39586719840](https://github.com/Kimgyuilli/PeekCart/actions/runs/39586719840) retained5 successful shard jobs from the first execution and reran payment after its dependency-resolution failure. `retry-attempt1-jobs.json.gz`/`retry-attempt2-jobs.json.gz` preserve IDs and original start/end times; the attempt2 API labels even retained jobs with run_attempt2, so that field alone is not used to identify their execution origin.

`retry-run-artifacts.json.gz` gives the5 shard artifact creation times10:45–10:48Z and payment11:04:18Z. Actual attempt2 gate119185683049 `retry-attempt2-gate.log.gz` at11:04:31Z lists those exact6 artifact IDs, uses download-artifact@v4 with pattern shard-results-* and merge-multiple:false, and successfully downloads all6 into separate named directories. This is existing platform evidence for same-run old5+new1 download behavior. It is not a live execution of the new publication observer or proof that dependent jobs always rerun automatically.

## New implementation verification boundary

[implementation-source.json](implementation-source.json) binds exact working-tree source hashes and the minimal2-test development result. The actual observation CLI fixture covers6 different repository digests and5old+1new immutable evidence inputs; the workflow fixture executes the actual capture/observe shell bodies and propagates an injected exit23. The complete9-method/7-incorrect-implementation suite and release-gate selftests are wired to Actions and pending at this freeze. No local full suite, Gradle or Docker execution was performed.

The new observer runs on main after publishing; its first actual main output remains pending. The new job is read-only and cannot establish multi-repository atomicity, eliminate concurrent publishers, repair old tags, recover expired payloads or resolve the historical unknown blob cause. Tracking/detection/recovery is a partial resolution of D-089.

## Raw preservation and reproduction

[raw-manifest.json](raw-manifest.json) records every gzip hash plus decompressed byte count/SHA256. Decompress each entry and compare its raw hash to verify exact preservation. No raw bytes were edited. Before compression, a bounded scan for GitHub token forms, JWTs, private keys and unmasked Authorization headers returned0 matches across the source files; this is a pattern check, not a universal secret guarantee. Logs already mask their Actions secrets.

Read-only collection used gh api repos/Kimgyuilli/PeekCart/actions/jobs/JOB_ID/logs, actions/runs/RUN_ID/jobs, actions/runs/RUN_ID/attempts/N/jobs and actions/runs/RUN_ID/artifacts. Original run metadata used gh run view --json conclusion,databaseId,headSha,jobs,status. GHCR source responses used anonymous registry token exchange followed by GET /v2/kimgyuilli/peekcart-SERVICE/manifests/92755566074fc28d78e53dd84b2b085a7fbfdb38 with OCI/Docker manifest Accept headers. No token is needed to verify the committed response bytes/hashes.
