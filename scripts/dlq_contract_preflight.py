#!/usr/bin/env python3
"""Read-only classification of a complete local fixture export; never a rollout gate.

Evidence identifies deliberately constructed fixtures, not independently authenticated
production provenance. No database connection, mutation, or deployment switch exists.
"""
import argparse
import hashlib
import json
import sys

LEDGER = 'dead_letter_records'
OUTBOX = 'outbox_events'
COORD = ('cluster_id', 'topic_generation', 'origin_topic', 'origin_partition',
         'origin_offset', 'failed_consumer_group')
REPLAY = ('destination_topic', 'destination_partition', 'record_key',
          'source_record_timestamp', 'replay_target_event_id', 'replay_headers',
          'replay_root_record_id', 'target_consumer_group')
LINEAGE = ('publication_status', 'outbox_event_id', 'last_replay_attempt_id',
           'last_replay_target_group', 'last_replay_payload_digest', 'last_replay_by',
           'replay_deadline', 'replay_policy', 'last_replay_settled_at',
           'publication_override_by', 'publication_override_reason')
GRANTS = {'SELECT', 'INSERT', 'UPDATE', 'DELETE', 'CREATE', 'ALTER', 'INDEX', 'REFERENCES'}


class Rejected(ValueError):
    """Only a fixed reason code is exposed; row payloads and credentials stay private."""


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':'),
                                     ensure_ascii=False).encode()).hexdigest()


def require(condition, code):
    if not condition:
        raise Rejected(code)


def positive(value):
    return type(value) is int and 0 < value <= 9223372036854775807


def classify(snapshot, evidence):
    require(isinstance(snapshot, dict) and isinstance(evidence, dict), 'invalid_export_or_evidence')
    require(snapshot.get('scope') == 'disposable-local-fixture', 'fixture_scope_required')
    require(evidence.get('scope') == snapshot['scope'], 'evidence_scope_required')
    require(set(snapshot['grants']) >= GRANTS, 'required_schema_grant_missing')
    candidates = {LEDGER: [], OUTBOX: []}
    for table, field, sql_type in ((LEDGER, 'root_record_id', 'bigint'),
                                   (OUTBOX, 'record_kind', 'varchar(10)')):
        columns = snapshot['columns'][table]
        require(columns[field]['type'] == sql_type, 'unexpected_column_type')
        require(columns[field]['default'] is None, 'unexpected_column_default')
        require(columns[field]['nullable'] in ('YES', 'NO'), 'invalid_nullability')
        rows = snapshot['rows'][table]
        require(all(set(row) == set(columns) for row in rows), 'incomplete_row_export')
        require(all(positive(row['id']) for row in rows), 'invalid_primary_id')
        require(len({row['id'] for row in rows}) == len(rows), 'duplicate_primary_id')
        require(any(index == ['id'] for index in snapshot['unique_indexes'][table]),
                'primary_unique_index_missing')
        required = {'id', field} | (set(COORD + LINEAGE) if table == LEDGER
                                   else set(REPLAY + ('event_type', 'aggregate_type')))
        require(required <= set(columns), 'required_columns_missing')
        proof = evidence.get(table, {})
        for row in rows:
            if row[field] is None:
                require(columns[field]['nullable'] == 'YES', 'null_in_contract_export')
                item = proof.get(str(row['id']))
                require(isinstance(item, dict) and item.get('row_sha256') == digest(row)
                        and item.get('origin') == 'constructed-pre-replay-fixture',
                        'legacy_provenance_required')
                metadata = LINEAGE if table == LEDGER else REPLAY
                require(all(row[key] is None for key in metadata), 'ambiguous_legacy_metadata')
                candidates[table].append(row['id'])
        # Reject stale approval manifests after any row mutation, even when NULL was filled.
        row_map = {str(row['id']): row for row in rows}
        for identifier, item in proof.items():
            require(identifier in row_map and item.get('row_sha256') == digest(row_map[identifier]),
                    'stale_legacy_evidence')
    ledger = snapshot['rows'][LEDGER]
    require(list(COORD) in snapshot['unique_indexes'][LEDGER], 'coordinate_unique_index_missing')
    coords = [tuple(row[key] for key in COORD) for row in ledger]
    require(all(all(value is not None for value in coord) for coord in coords), 'null_coordinate')
    require(len(set(coords)) == len(coords), 'duplicate_coordinate')
    root_by_id = {row['id']: row['root_record_id'] for row in ledger}
    for row in ledger:
        root = row['root_record_id']
        if root is None:
            continue  # Requires row-bound fixture provenance above.
        require(positive(root), 'invalid_root_id')
        require(root in root_by_id, 'orphan_root')
        parent = root_by_id[root]
        require(parent == root or (parent is None and root in candidates[LEDGER]),
                'noncanonical_root_or_cycle')
    for row in snapshot['rows'][OUTBOX]:
        kind = row['record_kind']
        require(kind in (None, 'DOMAIN', 'REPLAY'), 'unknown_record_kind')
        sentinel = row['event_type'] == '__replay__' or row['aggregate_type'] == 'DLQ_REPLAY'
        if kind != 'REPLAY':
            require(not sentinel and all(row[key] is None for key in REPLAY),
                    'domain_or_legacy_replay_conflict')
        else:
            # This checks shape, not replay eligibility or retained-ledger existence.
            require(row['event_type'] == '__replay__' and row['aggregate_type'] == 'DLQ_REPLAY',
                    'replay_sentinel_missing')
            require(positive(row['replay_root_record_id']) and
                    row['destination_topic'] is not None and
                    type(row['destination_partition']) is int and row['destination_partition'] >= 0
                    and row['target_consumer_group'] is not None, 'incomplete_replay_shape')
    return {'snapshot_sha256': digest(snapshot), 'candidates': candidates,
            'rollout_ready': False, 'scope': 'disposable-local-fixture'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--snapshot', required=True)
    parser.add_argument('--evidence', required=True)
    args = parser.parse_args()
    try:
        with open(args.snapshot) as source:
            snapshot = json.load(source)
        with open(args.evidence) as source:
            evidence = json.load(source)
        print(json.dumps(classify(snapshot, evidence), sort_keys=True))
        return 0
    except Rejected as error:
        print(json.dumps({'rejected': str(error), 'rollout_ready': False}))
    except (KeyError, TypeError, ValueError, AttributeError, OSError):
        print(json.dumps({'rejected': 'invalid_export_or_evidence', 'rollout_ready': False}))
    return 1


if __name__ == '__main__':
    sys.exit(main())
