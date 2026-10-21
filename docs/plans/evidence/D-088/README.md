# D-088 evidence

基準 actualmain3bb01bbc、code snapshotはsource.jsonのc70c989だ。inventory.jsonは動的24ファイルとliteral run consumersを記録する。CI23/inline selftest19/外部health対象4/ship-only writing1を区別する。独立oracleの結果ではなく、code/source確認用の有限inventoryだ。

## 実行

```bash
python3 -B scripts/ci-lint-wiring-lint.py
python3 -B scripts/ci-lint-health-selftest.py
```

正式実行はActionsで行う。外部helperは実際の4shell scriptを一時root/scriptsにcopyし、既存のdefault entrypointを正常/契約違反fixtureで実行する。cluster apply/ssh/Docker/Gradleは使わない。namespaceは本物のkubectl kustomizeによるlocal renderだ。各shellのearly-exit0/1とwiring checkerのearly-exit0/1、計10poisonがfixtureを失敗させることを別processで確認する。既存19inline selftestは配線のみ検査し、その19全ての外部early-exit生存を証明したとは書かない。

## 支持範囲

CI検査はYAML job/stepの支持条件(defaultまたは既存!cancelled)とliteral direct bash/Python commandに限定する。heredoc、multiline quote、先行control/function/exit、disabled/continue-on-error consumerは根拠にしない。arbitrary shell全実行経路を解釈するframeworkではない。新lintはliteral CI消費者とinline selftestまたは別途reviewされた外部対象が必要だ。

writing-lintはcommit/PR文体をshipで検査する単独例外で、.claude/commands/ship.md fenced commandsの実際の消費者を要求する。全repository文書をCI文体検査する方針は導入しない。

## 状態

minimal.txtは最小開発確認のみ。正式6methods/10poisonはActions待機。source.jsonのhashはcode snapshotに帰属し、未実行の全体検証成功を意味しない。plan checkboxは未検証のまま維持し、userのActions-first/draft許可に従いdraftPRから正式CIを開始する。ship preflightのblockedはauditにそのまま記録する。

既存4shellはbyte不変、workflow deltaは既存policy先頭の2行のみ。sharedTASKS/PHASE/masterはroot所有で未編集。別途Codexレビュー未呼出し。
