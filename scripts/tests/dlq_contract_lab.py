#!/usr/bin/env python3
"""Disposable MySQL experiment for D-065; no existing DB/container is accepted.

Uses actual migration SQL through mysql CLI (not Flyway/JPA). No published ports,
network access, production connection options, or deployable contract migration.
"""
import argparse
import copy
import json
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from dlq_contract_preflight import (COORD, GRANTS, LEDGER, OUTBOX, Rejected,
                                    classify, digest)

SERVICES = ('order', 'product', 'payment', 'notification')
TABLES = (LEDGER, OUTBOX)


def command(args, data=None, timeout=40, check=True):
    result = subprocess.run(args, input=data, text=True, capture_output=True, timeout=timeout)
    if check and result.returncode:
        # Fixture SQL errors are kept, but never include command lines or connection arguments.
        raise RuntimeError(result.stderr.strip())
    return result


def owner_check(condition, message='invalid fixture ownership metadata'):
    if not condition:
        raise RuntimeError(message)


class Lab:
    def __init__(self, evidence):
        self.name = 'peakcart-d065-contract-' + uuid.uuid4().hex[:10]
        self.nonce = uuid.uuid4().hex
        self.evidence = evidence
        self.results = []
        self.cleanup_completed = False

    def save_owner(self, identifier=None):
        owner = {'version': 2, 'name': self.name, 'nonce': self.nonce}
        if identifier is not None:
            owner_check(re.fullmatch(r'[0-9a-f]{64}', identifier))
            owner['id'] = identifier
        temporary = self.evidence / 'container.json.tmp'
        temporary.write_text(json.dumps(owner))
        temporary.replace(self.evidence / 'container.json')

    def sql(self, statement, service='order', root=False, check=True):
        args = ['docker', 'exec', '-i', self.name, 'mysql', '--batch', '--skip-column-names',
                '--raw', '--default-character-set=utf8mb4', '--user=' + ('root' if root else 'contract_lab')]
        if service:
            args.append('lab_' + service)
        return command(args, statement, check=check)

    def start(self):
        # Persist ownership before Docker can create anything. A lost create response
        # must not make the separate cleanup depend on an ID we never received.
        self.save_owner()
        created = command(['docker', 'run', '--detach', '--name', self.name, '--network', 'none',
                 '--label', 'peekcart.fixture=dlq-contract',
                 '--label', 'peekcart.fixture.run=' + self.nonce, '--tmpfs', '/var/lib/mysql',
                 '-e', 'MYSQL_ALLOW_EMPTY_PASSWORD=yes', 'mysql:8.0.46'])
        self.save_owner(created.stdout.strip())
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            ping = command(['docker', 'exec', self.name, 'mysqladmin', '--user=root', 'ping'],
                           timeout=3, check=False)
            initialization = command(['docker', 'logs', self.name], timeout=3, check=False)
            if ping.returncode == 0 and 'MySQL init process done. Ready for start up.' in initialization.stdout:
                break
            time.sleep(.5)
        else:
            raise RuntimeError('owned MySQL readiness timeout')
        self.sql("CREATE USER 'contract_lab'@'localhost';", service=None, root=True)
        self.version = self.sql('SELECT VERSION()', service=None, root=True).stdout.strip()
        assert self.version == '8.0.46', self.version
        self.image = command(['docker', 'inspect', self.name, '--format', '{{.Image}}']).stdout.strip()

    def stop(self):
        cleanup_owned(self.evidence)
        self.cleanup_completed = True

    def reset(self, service='order'):
        self.sql(f'DROP DATABASE IF EXISTS lab_{service}; CREATE DATABASE lab_{service}; '
                 f"GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,REFERENCES ON lab_{service}.* TO 'contract_lab'@'localhost';",
                 service=None, root=True)
        files = sorted((ROOT / (service + '-service/src/main/resources/db/migration')).glob('V*.sql'),
                       key=lambda path: int(re.match(r'V(\d+)__', path.name)[1]))
        for path in files:
            self.sql(path.read_text(), service)
        self.seed(service)
        return files

    def seed(self, service):
        self.sql("""
INSERT INTO dead_letter_records
(id,root_record_id,cluster_id,topic_generation,origin_topic,origin_partition,origin_offset,
 failed_consumer_group,origin_kind,payload,status,attempt_count,occurred_at,original_timestamp)
VALUES (10,NULL,'fixture',1,'order.created',0,10,'fixture-group','RESOLVED_ORIGIN','legacy-secret-marker','OPEN',2,'2026-01-01 00:00:00.123456',1767225600123),
(20,20,'fixture',1,'order.created',0,20,'fixture-group','RESOLVED_ORIGIN','root-before-image','ACKED',3,'2026-01-01 00:00:01.123456',1767225601123),
(21,20,'fixture',1,'order.created',0,21,'fixture-group','RESOLVED_ORIGIN','child-before-image','OPEN',4,'2026-01-01 00:00:02.123456',1767225602123);
INSERT INTO outbox_events
(id,aggregate_type,aggregate_id,event_type,event_id,payload,status,retry_count,created_at,record_kind)
VALUES(10,'Order','10','order.created','00000000-0000-0000-0000-000000000010','legacy-domain','PENDING',0,'2026-01-01 00:00:00.123456',NULL),
(20,'Order','20','order.created','00000000-0000-0000-0000-000000000020','domain-before-image','PUBLISHED',1,'2026-01-01 00:00:01.123456','DOMAIN'),
(30,'DLQ_REPLAY','20','__replay__','00000000-0000-0000-0000-000000000030','replay-before-image','PENDING',2,'2026-01-01 00:00:02.123456','REPLAY');
UPDATE outbox_events SET destination_topic='order.created',destination_partition=0,
 source_record_timestamp=1767225601123,replay_root_record_id=20,target_consumer_group='fixture-group',
 replay_target_event_id='00000000-0000-0000-0000-000000000020',replay_headers='{}' WHERE id=30;
""", service)

    def export(self, service='order'):
        result = {'scope': 'disposable-local-fixture', 'columns': {}, 'unique_indexes': {}, 'rows': {}}
        for table in TABLES:
            column_rows = self.sql(f"SELECT JSON_OBJECT('name',COLUMN_NAME,'type',COLUMN_TYPE,'nullable',IS_NULLABLE,'default',COLUMN_DEFAULT) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='{table}' ORDER BY ORDINAL_POSITION", service)
            result['columns'][table] = {item.pop('name'): item for item in
                                       map(json.loads, column_rows.stdout.splitlines())}
            pairs = ','.join(f"'{column}',`{column}`" for column in result['columns'][table])
            rows = self.sql(f'SELECT JSON_OBJECT({pairs}) FROM {table} ORDER BY id', service)
            result['rows'][table] = list(map(json.loads, rows.stdout.splitlines()))
            indices = self.sql(f"SELECT JSON_OBJECT('name',INDEX_NAME,'column',COLUMN_NAME) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='{table}' AND NON_UNIQUE=0 ORDER BY INDEX_NAME,SEQ_IN_INDEX", service)
            unique = {}
            for item in map(json.loads, indices.stdout.splitlines()):
                unique.setdefault(item['name'], []).append(item['column'])
            result['unique_indexes'][table] = list(unique.values())
        privileges = self.sql("SELECT PRIVILEGE_TYPE FROM information_schema.SCHEMA_PRIVILEGES WHERE TABLE_SCHEMA=DATABASE() AND GRANTEE=CONCAT(CHAR(39),'contract_lab',CHAR(39),'@',CHAR(39),'localhost',CHAR(39)) ORDER BY PRIVILEGE_TYPE", service)
        result['grants'] = privileges.stdout.splitlines()
        return result

    @staticmethod
    def proof(snapshot):
        # These exact rows were constructed by seed(), not inferred from NULL metadata.
        return {'scope': 'disposable-local-fixture', **{
            table: {'10': {'row_sha256': digest(next(row for row in snapshot['rows'][table]
                                                  if row['id'] == 10)),
                            'origin': 'constructed-pre-replay-fixture'}} for table in TABLES}}

    def passed(self, name, detail=None):
        item = {'pass': name, 'detail': detail}
        self.results.append(item)
        print(json.dumps(item, sort_keys=True), flush=True)

    def rejected(self, name, snapshot, proof, reason):
        before = digest(snapshot)
        try:
            classify(snapshot, proof)
        except Rejected as error:
            assert str(error) == reason, (name, str(error))
        else:
            raise AssertionError(name + ': accepted invalid fixture')
        assert digest(snapshot) == before
        self.passed(name, reason)

    def backfill(self, snapshot, proof, service='order', injection=''):
        decision = classify(snapshot, proof)
        # Not a concurrency fence: only a stale snapshot check for this owned fixture.
        assert digest(self.export(service)) == decision['snapshot_sha256'], 'stale snapshot'
        statements = ['SET SESSION sql_mode=\'STRICT_ALL_TABLES\';', 'START TRANSACTION;']
        for table, field, value in ((LEDGER, 'root_record_id', 'id'), (OUTBOX, 'record_kind', "'DOMAIN'")):
            identifiers = decision['candidates'][table]
            if identifiers:
                statements.append(f"UPDATE {table} SET {field}={value} WHERE {field} IS NULL AND id IN ({','.join(map(str, identifiers))});")
        statements.extend([injection, 'COMMIT;'])
        return self.sql('\n'.join(statements), service, check=not injection)

    def run(self):
        migration_files = {}
        for service in SERVICES:
            files = self.reset(service)
            migration_files[service] = [p.name for p in files]
            before = self.export(service)
            assert set(before['grants']) == GRANTS
            proof = self.proof(before)
            decision = classify(before, proof)
            assert decision['candidates'] == {LEDGER: [10], OUTBOX: [10]}
            assert not decision['rollout_ready']
            self.backfill(before, proof, service)
            expected = copy.deepcopy(before)
            expected['rows'][LEDGER][0]['root_record_id'] = 10
            expected['rows'][OUTBOX][0]['record_kind'] = 'DOMAIN'
            after = self.export(service)
            assert after == expected, 'non-candidate field changed'
            empty = {'scope': 'disposable-local-fixture'}
            self.backfill(after, empty, service)
            assert self.export(service) == after
            self.passed(service + ': actual migration chain, minimal grants, exact backfill, repeat unchanged', len(files))
        self.evidence.joinpath('migration-files.json').write_text(json.dumps(migration_files, indent=2))

        self.reset()
        baseline = self.export()
        proof = self.proof(baseline)
        self.evidence.joinpath('fixture-before.json').write_text(json.dumps(baseline, indent=2))
        self.evidence.joinpath('fixture-provenance.json').write_text(json.dumps(proof, indent=2))
        for name, mutation, expected in [
            ('zero root', "UPDATE dead_letter_records SET root_record_id=0 WHERE id=21", 'invalid_root_id'),
            ('negative root', "UPDATE dead_letter_records SET root_record_id=-1 WHERE id=21", 'invalid_root_id'),
            ('orphan root', "UPDATE dead_letter_records SET root_record_id=999 WHERE id=21", 'orphan_root'),
            ('cycle', "UPDATE dead_letter_records SET root_record_id=21 WHERE id=20", 'noncanonical_root_or_cycle'),
            ('multi-hop root', "UPDATE dead_letter_records SET root_record_id=10 WHERE id=20", 'noncanonical_root_or_cycle'),
            ('unknown kind', "UPDATE outbox_events SET record_kind='UNKNOWN' WHERE id=20", 'unknown_record_kind'),
            ('domain replay metadata', "UPDATE outbox_events SET destination_topic='hidden' WHERE id=20", 'domain_or_legacy_replay_conflict'),
            ('default kind', "ALTER TABLE outbox_events ALTER record_kind SET DEFAULT 'DOMAIN'", 'unexpected_column_default'),
            ('coordinate index missing', "ALTER TABLE dead_letter_records DROP INDEX uk_dead_letter_records_origin", 'coordinate_unique_index_missing'),
        ]:
            self.reset()
            self.sql(mutation)
            before = self.export()
            self.rejected(name, before, proof, expected)
            assert self.export() == before

        self.reset()
        snapshot = self.export()
        self.rejected('unproven NULL', snapshot, {'scope': 'disposable-local-fixture'}, 'legacy_provenance_required')
        self.sql("UPDATE outbox_events SET event_type='__replay__' WHERE id=10")
        snapshot = self.export()
        self.rejected('NULL replay sentinel despite fixture proof', snapshot, self.proof(snapshot), 'domain_or_legacy_replay_conflict')
        self.reset()
        self.sql("UPDATE dead_letter_records SET last_replay_attempt_id='attempt' WHERE id=10")
        snapshot = self.export()
        self.rejected('NULL lineage with replay metadata', snapshot, self.proof(snapshot), 'ambiguous_legacy_metadata')
        self.reset()
        self.sql("UPDATE dead_letter_records SET payload='changed-after-proof' WHERE id=10")
        self.rejected('stale row proof', self.export(), proof, 'legacy_provenance_required')
        self.reset()
        self.sql('REVOKE ALTER ON lab_order.* FROM contract_lab@localhost', root=True)
        self.rejected('insufficient actual service grant', self.export(), proof, 'required_schema_grant_missing')

        self.reset()
        before = self.export()
        failure = self.backfill(before, self.proof(before), injection='SELECT * FROM missing_failure_injection;')
        assert failure.returncode != 0 and '1146' in failure.stderr
        assert self.export() == before, 'DML rollback did not preserve complete before-image'
        self.passed('backfill DML error rolls back both tables', {'mysql_error': 1146, 'before_sha256': digest(before)})

        self.reset()
        self.sql('ALTER TABLE dead_letter_records DROP INDEX uk_dead_letter_records_origin')
        self.sql("UPDATE dead_letter_records SET origin_offset=20 WHERE id=21")
        duplicate = self.export()
        assert self.sql('SELECT COUNT(*) FROM dead_letter_records WHERE origin_offset=20').stdout.strip() == '2'
        self.rejected('actual duplicate coordinate without unique index', duplicate, proof, 'coordinate_unique_index_missing')

        self.contract_experiments()

    def contract_experiments(self):
        self.reset()
        before = self.export()
        self.backfill(before, self.proof(before))
        filled = self.export()
        failure = self.sql("ALTER TABLE dead_letter_records MODIFY root_record_id BIGINT NOT NULL; ALTER TABLE outbox_events MODIFY nonexistent_column BIGINT NOT NULL;", check=False)
        assert failure.returncode != 0 and '1054' in failure.stderr
        self.sql('ROLLBACK;')
        partial = self.export()
        assert partial['rows'] == filled['rows']
        assert partial['columns'][LEDGER]['root_record_id']['nullable'] == 'NO'
        assert partial['columns'][OUTBOX]['record_kind']['nullable'] == 'YES'
        self.evidence.joinpath('partial-ddl-schema.json').write_text(json.dumps(partial['columns'], indent=2))
        self.passed('second ALTER failure does not undo first ALTER', {'mysql_error': 1054, 'rows_sha256': digest(partial['rows'])})
        self.sql('ALTER TABLE outbox_events MODIFY record_kind VARCHAR(10) NOT NULL')
        self.passed('explicit local checkpoint continuation preserves rows', digest(self.export()['rows']))

        old_insert = """INSERT IGNORE INTO dead_letter_records
(cluster_id,topic_generation,origin_topic,origin_partition,origin_offset,failed_consumer_group,
origin_kind,event_id,original_key,original_timestamp,payload,payload_truncated,exception_type,
exception_message,status,attempt_count,occurred_at)
VALUES('fixture',1,'order.created',0,99,'fixture-group','RESOLVED_ORIGIN',NULL,NULL,NULL,
'old-writer',0,NULL,NULL,'OPEN',1,'2026-01-01 00:00:03.123456');"""
        for mode in ('STRICT_ALL_TABLES', ''):
            self.sql('DELETE FROM dead_letter_records WHERE origin_offset=99')
            result = self.sql(f"SET SESSION sql_mode='{mode}';" + old_insert +
                              'GET DIAGNOSTICS @insert_count = ROW_COUNT; SHOW WARNINGS; SELECT @insert_count; SELECT root_record_id FROM dead_letter_records WHERE origin_offset=99;')
            assert '1364' in result.stdout and result.stdout.splitlines()[-2:] == ['1', '0'], result.stdout
            self.sql('UPDATE dead_letter_records SET root_record_id=id WHERE root_record_id IS NULL')
            assert self.sql('SELECT root_record_id FROM dead_letter_records WHERE origin_offset=99').stdout.strip() == '0'
            self.evidence.joinpath('old-ignore-' + ('strict' if mode else 'nonstrict') + '.txt').write_text(result.stdout)
            self.passed('old INSERT IGNORE silently coerces root; old self update cannot repair: ' + (mode or 'non-strict'), {'warning': 1364, 'affected_rows': 1, 'root': 0})
        self.sql('DELETE FROM dead_letter_records WHERE origin_offset=99')
        plain = self.sql("SET SESSION sql_mode='STRICT_ALL_TABLES';" + old_insert.replace('INSERT IGNORE', 'INSERT'), check=False)
        assert plain.returncode != 0 and '1364' in plain.stderr
        self.passed('strict plain missing-root INSERT fails', 1364)
        missing_kind = self.sql("SET SESSION sql_mode='STRICT_ALL_TABLES'; INSERT INTO outbox_events (aggregate_type,aggregate_id,event_type,event_id,payload,status,retry_count,created_at) VALUES('Order','99','order.created','00000000-0000-0000-0000-000000000099','fixture','PENDING',0,NOW(6))", check=False)
        assert missing_kind.returncode != 0 and '1364' in missing_kind.stderr
        self.passed('strict missing-kind INSERT fails without DEFAULT', 1364)
        # Omitted kind can also coerce to an invalid empty string with IGNORE.
        ignored = self.sql("SET SESSION sql_mode='STRICT_ALL_TABLES'; INSERT IGNORE INTO outbox_events (aggregate_type,aggregate_id,event_type,event_id,payload,status,retry_count,created_at) VALUES('Order','99','order.created','00000000-0000-0000-0000-000000000099','fixture','PENDING',0,NOW(6)); SHOW WARNINGS; SELECT LENGTH(record_kind) FROM outbox_events WHERE aggregate_id='99'")
        assert '1364' in ignored.stdout and ignored.stdout.splitlines()[-1] == '0'
        self.passed('NOT NULL alone also permits ignored empty kind', 1364)

        self.reset()
        snapshot = self.export()
        proof = self.proof(snapshot)
        # Deliberately place a legacy writer after preflight, then after backfill.
        classify(snapshot, proof)
        self.sql(old_insert)
        try:
            self.backfill(snapshot, proof)
        except AssertionError as error:
            assert str(error) == 'stale snapshot'
        else:
            raise AssertionError('stale snapshot accepted')
        self.passed('legacy writer after preflight invalidates snapshot')
        self.reset()
        snapshot = self.export()
        self.backfill(snapshot, self.proof(snapshot))
        self.sql(old_insert)
        self.rejected('legacy writer after backfill has unproven NULL', self.export(),
                      {'scope': 'disposable-local-fixture'}, 'legacy_provenance_required')
        failed = self.sql("SET SESSION sql_mode='STRICT_ALL_TABLES'; ALTER TABLE dead_letter_records MODIFY root_record_id BIGINT NOT NULL", check=False)
        assert failed.returncode != 0
        assert self.export()['columns'][LEDGER]['root_record_id']['nullable'] == 'YES'
        self.passed('old writer NULL prevents strict ALTER; no automatic repair', {'mysql_error': re.search(r'ERROR (\d+)', failed.stderr)[1]})
        self.additional_boundaries(old_insert)

    def additional_boundaries(self, old_insert):
        self.reset()
        before = self.export()
        self.backfill(before, self.proof(before))
        # A pre-DDL DML update commits even though a later DDL fails and ROLLBACK follows.
        failure = self.sql("START TRANSACTION; UPDATE dead_letter_records SET note='implicit-commit-marker' WHERE id=20; ALTER TABLE dead_letter_records MODIFY root_record_id BIGINT NOT NULL; ALTER TABLE outbox_events MODIFY missing_column INT;", check=False)
        assert failure.returncode != 0 and '1054' in failure.stderr
        self.sql('ROLLBACK;')
        assert self.sql('SELECT note FROM dead_letter_records WHERE id=20').stdout.strip() == 'implicit-commit-marker'
        self.passed('ALTER commits preceding DML despite later failure and ROLLBACK', 1054)

        self.reset()
        before = self.export()
        classify(before, self.proof(before))
        args = ['docker', 'exec', '-i', self.name, 'mysql', '--batch', '--skip-column-names',
                '--user=contract_lab', 'lab_order']
        writer = subprocess.Popen(args, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                  stderr=subprocess.PIPE, text=True)
        try:
            writer.stdin.write("START TRANSACTION; UPDATE dead_letter_records SET root_record_id=id WHERE id=10; UPDATE outbox_events SET record_kind='DOMAIN' WHERE id=10; SELECT SLEEP(3); COMMIT;")
            writer.stdin.close()
            writer.stdin = None
            deadline = time.monotonic() + 3
            while time.monotonic() < deadline:
                active = self.sql("SELECT COUNT(*) FROM information_schema.INNODB_TRX WHERE trx_query LIKE 'SELECT SLEEP(3)%'", root=True).stdout.strip()
                if active == '1':
                    break
                time.sleep(.05)
            else:
                raise AssertionError('backfill transaction did not reach observed pause')
            self.sql(old_insert)
            stdout, stderr = writer.communicate(timeout=10)
            assert writer.returncode == 0, stderr
        finally:
            if writer.poll() is None:
                writer.kill()
                writer.communicate(timeout=5)
        self.rejected('concurrent old writer during backfill commits new unproven NULL', self.export(),
                      {'scope': 'disposable-local-fixture'}, 'legacy_provenance_required')

        self.reset()
        # Bootstrap the reservation counter after fixture legacy rows, exactly like V14.
        reserved = self.sql('INSERT INTO dead_letter_record_ids(id) SELECT MAX(id) FROM dead_letter_records HAVING MAX(id)>0; DELETE FROM dead_letter_record_ids; START TRANSACTION; INSERT INTO dead_letter_record_ids VALUES(NULL); SELECT LAST_INSERT_ID(); DELETE FROM dead_letter_record_ids WHERE id=LAST_INSERT_ID(); COMMIT;').stdout.strip()
        identifier = int(reserved)
        self.sql(old_insert)
        old = self.sql('SELECT id FROM dead_letter_records WHERE origin_offset=99').stdout.strip()
        assert int(old) == identifier, (old, identifier)
        before_collision = self.export()['rows']
        explicit = old_insert.replace('INSERT IGNORE', 'INSERT').replace('(cluster_id,', '(id,root_record_id,cluster_id,').replace("VALUES('fixture'", f"VALUES({identifier},{identifier},'fixture'").replace(",0,99,'fixture-group'", ",0,100,'fixture-group'").rstrip().rstrip(';')
        collision = self.sql('START TRANSACTION;' + explicit + ' ON DUPLICATE KEY UPDATE attempt_count=dead_letter_records.attempt_count+1; SELECT ROW_COUNT(); SELECT COUNT(*) FROM dead_letter_records WHERE origin_offset=100; ROLLBACK;')
        assert collision.stdout.splitlines() == ['2', '0'], collision.stdout
        assert self.export()['rows'] == before_collision
        self.passed('legacy AUTO_INCREMENT consumes reserved ID; upsert count is not coordinate dedup',
                    {'reserved_id': identifier, 'affected_rows': 2, 'matching_coordinate_rows': 0})


def cleanup_owned(directory):
    state = directory / 'container.json'
    if not state.exists():
        return
    owner = json.loads(state.read_text())
    owner_check(owner['version'] == 2)
    owner_check(re.fullmatch(r'peakcart-d065-contract-[0-9a-f]{10}', owner['name']))
    owner_check(re.fullmatch(r'[0-9a-f]{32}', owner['nonce']))
    if 'id' in owner:
        owner_check(re.fullmatch(r'[0-9a-f]{64}', owner['id']))
    present = command(['docker', 'ps', '-aq', '--no-trunc', '--filter',
                       'name=^/' + owner['name'] + '$']).stdout.strip()
    if not present:
        return
    owner_check(re.fullmatch(r'[0-9a-f]{64}', present))
    if 'id' in owner:
        owner_check(present == owner['id'], 'owned container ID changed; refusing cleanup')
    observed = json.loads(command(['docker', 'inspect', present]).stdout)[0]
    owner_check(observed['Name'] == '/' + owner['name'])
    owner_check(observed['Config']['Labels'].get('peekcart.fixture') == 'dlq-contract')
    owner_check(observed['Config']['Labels'].get('peekcart.fixture.run') == owner['nonce'],
                'ownership nonce mismatch; refusing cleanup')
    # Delete the inspected immutable ID, never a name that might be reused.
    command(['docker', 'rm', '-f', present])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence-dir', type=Path, required=True)
    parser.add_argument('--cleanup', action='store_true')
    parser.add_argument('--fail-after-start', action='store_true', help='fixture cleanup negative control')
    args = parser.parse_args()
    if args.cleanup:
        cleanup_owned(args.evidence_dir)
        return
    args.evidence_dir.mkdir(parents=True, exist_ok=False)
    lab = Lab(args.evidence_dir)
    source_paths = [ROOT / 'scripts/dlq_contract_preflight.py', Path(__file__).resolve(),
                    ROOT / 'scripts/tests/test_dlq_contract_preflight.py',
                    ROOT / 'scripts/tests/test_dlq_contract_cleanup.py', ROOT / '.github/workflows/ci.yml']
    source_paths += [path for service in SERVICES for path in
                     (ROOT / (service + '-service/src/main/resources/db/migration')).glob('V*.sql')]
    import hashlib
    args.evidence_dir.joinpath('source-manifest.json').write_text(json.dumps({
        'git_head': command(['git', '-C', str(ROOT), 'rev-parse', 'HEAD']).stdout.strip(),
        'files': {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
                  for path in sorted(source_paths)}}, indent=2))
    started = time.monotonic()
    completed = False
    try:
        lab.start()
        if args.fail_after_start:
            raise RuntimeError('injected failure after owned MySQL startup')
        lab.run()
        completed = True
    finally:
        try:
            lab.stop()
        finally:
            args.evidence_dir.joinpath('results.json').write_text(json.dumps({
                'mysql_version': getattr(lab, 'version', None), 'image': getattr(lab, 'image', None),
                'results': lab.results, 'elapsed_seconds': round(time.monotonic() - started, 3),
                'cleaned_up': lab.cleanup_completed, 'completed': completed, 'rollout_ready': False}, indent=2))
    print(json.dumps({'passed': len(lab.results), 'cleaned_up': True, 'rollout_ready': False}))


if __name__ == '__main__':
    main()
