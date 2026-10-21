#!/usr/bin/env python3
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from dlq_contract_preflight import (COORD, GRANTS, LEDGER, LINEAGE, OUTBOX, REPLAY,
                                    Rejected, classify, digest)


def fixture():
    ledger = {key: None for key in LINEAGE}
    ledger.update(id=10, root_record_id=None, cluster_id='fixture', topic_generation=1,
                  origin_topic='topic', origin_partition=0, origin_offset=10,
                  failed_consumer_group='group', payload='never-log-this-payload')
    outbox = {key: None for key in REPLAY}
    outbox.update(id=10, record_kind=None, event_type='order.created', aggregate_type='Order')
    rows = {LEDGER: [ledger], OUTBOX: [outbox]}
    columns = {table: {key: {'type': 'text', 'nullable': 'YES', 'default': None}
                       for key in row[0]} for table, row in rows.items()}
    columns[LEDGER]['root_record_id']['type'] = 'bigint'
    columns[OUTBOX]['record_kind']['type'] = 'varchar(10)'
    snapshot = dict(scope='disposable-local-fixture', grants=sorted(GRANTS), rows=rows,
                    columns=columns, unique_indexes={LEDGER: [['id'], list(COORD)], OUTBOX: [['id']]})
    evidence = {'scope': snapshot['scope'], **{table: {'10': {
        'row_sha256': digest(rows[table][0]), 'origin': 'constructed-pre-replay-fixture'}}
        for table in rows}}
    return snapshot, evidence


class PreflightTest(unittest.TestCase):
    def test_valid_fixture_is_only_a_local_candidate_not_rollout_permission(self):
        snapshot, evidence = fixture()
        before = copy.deepcopy(snapshot)
        report = classify(snapshot, evidence)
        self.assertEqual(report['candidates'], {LEDGER: [10], OUTBOX: [10]})
        self.assertFalse(report['rollout_ready'])
        self.assertEqual(snapshot, before)

    def test_rejects_unknown_provenance_even_without_replay_metadata(self):
        snapshot, evidence = fixture()
        evidence[LEDGER]['10']['origin'] = 'trust-me'
        with self.assertRaisesRegex(Rejected, '^legacy_provenance_required$'):
            classify(snapshot, evidence)

    def test_changed_payload_invalidates_row_bound_evidence(self):
        snapshot, evidence = fixture()
        snapshot['rows'][LEDGER][0]['payload'] += 'changed'
        with self.assertRaisesRegex(Rejected, '^legacy_provenance_required$'):
            classify(snapshot, evidence)

    def test_missing_column_in_row_is_not_interpreted_as_null(self):
        snapshot, evidence = fixture()
        del snapshot['rows'][OUTBOX][0]['destination_topic']
        with self.assertRaisesRegex(Rejected, '^incomplete_row_export$'):
            classify(snapshot, evidence)

    def test_stale_proof_cannot_silently_be_reused_after_backfill(self):
        snapshot, evidence = fixture()
        snapshot['rows'][LEDGER][0]['root_record_id'] = 10
        with self.assertRaisesRegex(Rejected, '^stale_legacy_evidence$'):
            classify(snapshot, evidence)

    def test_duplicate_coordinate_rejected_even_with_claimed_unique_index(self):
        snapshot, evidence = fixture()
        other = copy.deepcopy(snapshot['rows'][LEDGER][0])
        other.update(id=11, root_record_id=11)
        snapshot['rows'][LEDGER].append(other)
        with self.assertRaisesRegex(Rejected, '^duplicate_coordinate$'):
            classify(snapshot, evidence)

    def test_missing_unique_index_is_rejected_on_empty_data_too(self):
        snapshot, _ = fixture()
        snapshot['rows'] = {LEDGER: [], OUTBOX: []}
        snapshot['unique_indexes'][LEDGER] = [['id']]
        with self.assertRaisesRegex(Rejected, '^coordinate_unique_index_missing$'):
            classify(snapshot, {'scope': snapshot['scope']})

    def test_boolean_id_is_not_numeric_identity(self):
        snapshot, evidence = fixture()
        snapshot['rows'][LEDGER][0]['id'] = True
        with self.assertRaisesRegex(Rejected, '^invalid_primary_id$'):
            classify(snapshot, evidence)

    def test_wrong_scope_and_grant_are_rejected(self):
        for mutation, reason in ((lambda s: s.update(scope='production'), 'fixture_scope_required'),
                                 (lambda s: s.update(grants=['SELECT']), 'required_schema_grant_missing')):
            snapshot, evidence = fixture()
            mutation(snapshot)
            with self.subTest(reason=reason), self.assertRaisesRegex(Rejected, '^' + reason + '$'):
                classify(snapshot, evidence)

    def test_cli_malformed_and_payload_errors_fail_closed_without_echo(self):
        script = str(Path(__file__).resolve().parents[1] / 'dlq_contract_preflight.py')
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            snapshot, evidence = fixture()
            snapshot['rows'][LEDGER][0]['payload'] += 'changed'
            (root / 'evidence.json').write_text(json.dumps(evidence))
            for content in (json.dumps(snapshot), '{invalid secret-payload', '{}', '[]'):
                (root / 'snapshot.json').write_text(content)
                result = subprocess.run([sys.executable, script, '--snapshot', str(root / 'snapshot.json'),
                                         '--evidence', str(root / 'evidence.json')],
                                        text=True, capture_output=True, timeout=5)
                self.assertEqual(result.returncode, 1)
                self.assertNotIn('payload', result.stdout + result.stderr)
                self.assertEqual(result.stderr, '')
                self.assertFalse(json.loads(result.stdout)['rollout_ready'])


if __name__ == '__main__':
    unittest.main()
