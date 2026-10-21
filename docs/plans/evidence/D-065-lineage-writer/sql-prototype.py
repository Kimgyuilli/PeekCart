"""Disposable MySQL design probe, not application/JPA or production evidence."""
import concurrent.futures
import json
import os
import sys
import threading
import time
from pathlib import Path

import pymysql
from pymysql.constants import CLIENT

STATE = Path('/probe/state.json')


def connect(db='probe'):
    c = pymysql.connect(host='mysql', user='root', password='prototype-only',
                        database=db, autocommit=False, connect_timeout=5,
                        read_timeout=10, write_timeout=10, client_flag=CLIENT.FOUND_ROWS)
    with c.cursor() as cur:
        cur.execute('SET SESSION innodb_lock_wait_timeout=5')
        cur.execute("SET SESSION sql_mode='STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION'")
    return c


def execute(c, sql, args=None):
    with c.cursor() as cur:
        cur.execute(sql, args)
        return cur.fetchall() if cur.description else (cur.rowcount, cur.lastrowid)


def allocate(c, table='alloc'):
    _, identifier = execute(c, 'INSERT INTO ' + table + ' VALUES(NULL)')
    assert 0 < identifier < 9223372036854775807, identifier
    execute(c, 'DELETE FROM ' + table + ' WHERE id=%s', (identifier,))
    return identifier


def admit(c, identifier, coord, root=None):
    affected, _ = execute(c, '''INSERT INTO ledger(id,coord,root,attempts)
        VALUES(%s,%s,%s,1) ON DUPLICATE KEY UPDATE id=ledger.id''',
        (identifier, coord, root or identifier))
    rows = execute(c, 'SELECT id,root,attempts FROM ledger WHERE coord=%s FOR UPDATE', (coord,))
    if not rows:
        raise RuntimeError('PK collision has no matching coordinate; never acknowledge')
    inserted = rows[0][0] == identifier
    if not inserted:
        execute(c, 'UPDATE ledger SET attempts=attempts+1 WHERE coord=%s', (coord,))
    return inserted, affected, rows[0]


def passed(name, detail=None):
    print(json.dumps({'pass': name, 'detail': detail}), flush=True)


if sys.argv[1] == 'after-restart':
    previous = json.loads(STATE.read_text())['last_reserved']
    with connect() as c:
        assert execute(c, 'SELECT COUNT(*) FROM alloc')[0][0] == 0
        current = allocate(c)
        assert current > previous, (previous, current)
        c.commit()
    passed('empty reservation table counter survives normal restart', [previous, current])
    sys.exit(0)

with connect(None) as c:
    execute(c, 'CREATE DATABASE probe')
    c.commit()
with connect() as c:
    execute(c, 'CREATE TABLE old_writer(id BIGINT AUTO_INCREMENT PRIMARY KEY, root BIGINT NOT NULL)')
    execute(c, 'INSERT IGNORE INTO old_writer(id) VALUES(NULL)')
    assert execute(c, 'SELECT root FROM old_writer') == ((0,),)
    assert execute(c, 'SELECT COUNT(*) FROM old_writer WHERE root IS NULL OR root=id') == ((0,),)
    c.commit()
    passed('negative control: naive NOT NULL silently loses root aggregation')
    execute(c, '''CREATE TABLE ledger(id BIGINT AUTO_INCREMENT PRIMARY KEY,
        coord VARCHAR(64) NOT NULL UNIQUE, root BIGINT NULL, attempts INT NOT NULL)''')
    execute(c, 'CREATE TABLE alloc(id BIGINT AUTO_INCREMENT PRIMARY KEY)')
    identifier = allocate(c)
    result = admit(c, identifier, 'root')
    c.commit()
    assert result[0] and result[2][1] == identifier
    passed('empty database and first explicit self root', identifier)

# Keep an allocated/deleted reservation uncommitted, then demand another connection progress.
held = connect()
held_id = allocate(held)
with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
    def independent_allocation():
        with connect() as c:
            value = allocate(c)
            c.commit()
            return value
    future = pool.submit(independent_allocation)
    concurrent_id = future.result(timeout=3)
    assert concurrent_id != held_id
held.rollback()
held.close()
passed('no shared allocation row lock retained until transaction end', [held_id, concurrent_id])

# Root contention must not stall unrelated ID allocation.
root_owner = connect()
root_id = execute(root_owner, "SELECT id FROM ledger WHERE coord='root' FOR UPDATE")[0][0]
reserved = threading.Event()

def waits_for_root():
    with connect() as c:
        child_id = allocate(c)
        reserved.set()
        execute(c, 'SELECT id FROM ledger WHERE id=%s FOR UPDATE', (root_id,))
        result = admit(c, child_id, 'correlated-child', root_id)
        c.commit()
        return child_id, result[2][1]

with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
    waiting = pool.submit(waits_for_root)
    assert reserved.wait(3)
    assert not waiting.done()
    unrelated = pool.submit(independent_allocation).result(timeout=3)
    root_owner.commit()
    child_id, parent_id = waiting.result(timeout=3)
    assert parent_id == root_id and child_id != parent_id
root_owner.close()
passed('root waiter allows unrelated allocation and writes child parent in first insert', unrelated)

# Same coordinate: every caller allocates independently; exactly one insert and all attempts preserved.
barrier = threading.Barrier(8)

def same_coordinate(index):
    with connect() as c:
        identifier = allocate(c)
        barrier.wait(timeout=5)
        result = admit(c, identifier, 'concurrent')
        c.commit()
        return result[0], result[1]

with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
    results = list(pool.map(same_coordinate, range(8)))
assert sum(r[0] for r in results) == 1, results
with connect() as c:
    assert execute(c, "SELECT attempts FROM ledger WHERE coord='concurrent'") == ((8,),)
passed('eight concurrent coordinate duplicates classify by ID, not found-rows', results)

# Establish an RR snapshot before a competing committed coordinate appears.
stale = connect()
assert execute(stale, "SELECT id FROM ledger WHERE coord='late-winner'") == ()
with connect() as winner:
    winner_id = allocate(winner)
    admit(winner, winner_id, 'late-winner')
    winner.commit()
assert execute(stale, "SELECT id FROM ledger WHERE coord='late-winner'") == ()
loser_id = allocate(stale)
result = admit(stale, loser_id, 'late-winner')
assert not result[0] and result[2][0] == winner_id
stale.commit()
stale.close()
passed('current read finds concurrent winner beyond RR snapshot', [winner_id, loser_id])

with connect() as c:
    rollback_id = allocate(c)
    admit(c, rollback_id, 'rolled-back')
    c.rollback()
    assert execute(c, "SELECT id FROM ledger WHERE coord='rolled-back'") == ()
    assert execute(c, 'SELECT COUNT(*) FROM alloc') == ((0,),)
    next_id = allocate(c)
    assert next_id > rollback_id
    c.commit()
passed('ledger and reservation rollback; gaps allowed', [rollback_id, next_id])

with connect() as c:
    prior = execute(c, "SELECT id,attempts FROM ledger WHERE coord='root'")[0]
    try:
        admit(c, prior[0], 'different-coordinate')
        raise AssertionError('unexpected primary-key collision was acknowledged')
    except RuntimeError:
        c.rollback()
    assert execute(c, "SELECT id,attempts FROM ledger WHERE coord='root'")[0] == prior
    assert execute(c, "SELECT id FROM ledger WHERE coord='different-coordinate'") == ()
passed('unexpected primary-key collision fails without attempt mutation')

with connect() as c:
    execute(c, 'INSERT INTO ledger VALUES(1000000000000,\'legacy-high\',NULL,1)')
    execute(c, 'CREATE TABLE bootstrap LIKE alloc')
    execute(c, 'INSERT INTO bootstrap(id) SELECT MAX(id) FROM ledger HAVING MAX(id)>0')
    execute(c, 'DELETE FROM bootstrap')
    c.commit()
    bootstrapped = allocate(c, 'bootstrap')
    assert bootstrapped == 1000000000001
    assert execute(c, "SELECT root FROM ledger WHERE coord='legacy-high'") == ((None,),)
    c.commit()
passed('bootstrap beyond high legacy ID without rewriting legacy root', bootstrapped)

with connect() as c:
    execute(c, 'CREATE TABLE boundary LIKE alloc')
    execute(c, 'INSERT INTO boundary(id) VALUES(9223372036854775807)')
    execute(c, 'DELETE FROM boundary')
    c.commit()
    try:
        allocate(c, 'boundary')
    except AssertionError as error:
        assert error.args == (9223372036854775807,), error
        c.rollback()
    except pymysql.MySQLError as error:
        assert error.args[0] == 1467, error
        c.rollback()
    else:
        raise AssertionError('allocation beyond signed BIGINT maximum was accepted')
passed('explicit range guard rejects exhausted BIGINT before ledger insert')

# Test-only contract schema after legacy backfill: same writer still supplies complete lineage.
with connect() as c:
    execute(c, 'UPDATE ledger SET root=id WHERE root IS NULL')
    execute(c, 'ALTER TABLE ledger MODIFY root BIGINT NOT NULL, ADD CONSTRAINT positive_root CHECK(root>0)')
    value = allocate(c)
    assert admit(c, value, 'contract-root')[0]
    c.commit()
    try:
        execute(c, "INSERT INTO ledger(coord,attempts) VALUES('missing-root',1)")
        raise AssertionError('missing root accepted')
    except pymysql.MySQLError as error:
        assert error.args[0] == 1364, error
        c.rollback()
passed('same explicit writer works with test-only NOT NULL root, raw omission rejected')

with connect() as c:
    last_reserved = allocate(c)
    c.commit()
    assert execute(c, 'SELECT COUNT(*) FROM alloc') == ((0,),)
STATE.write_text(json.dumps({'last_reserved': last_reserved}))
passed('ready for normal restart; no TRUNCATE used', last_reserved)
