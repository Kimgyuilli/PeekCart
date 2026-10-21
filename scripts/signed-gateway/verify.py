#!/usr/bin/env python3
"""Actual HTTP/JDBC fixtures; bearer values remain in memory, never in evidence."""
import base64
import hashlib
import json
import os
from pathlib import Path
import sys
import time
import urllib.error
import urllib.request

import pymysql
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import padding, rsa

SERVICE = os.environ["SIGNED_SERVICE"]
NONCE = os.environ["SIGNED_NONCE"]
OUT = Path("/work/out")
ROOT_ID = 1001
PATH = f"/api/v1/admin/deadletter/{SERVICE}/{ROOT_ID}"
BODY = {"action": "replay", "actor": "signed-fixture", "reason": "closed admission verification"}
DISABLED = "replay 진입점이 꺼져 있다 — app.dead-letter.replay.enabled=false"
PASSWORD = "Fixture-" + NONCE + "-123!"
EMAILS = {role: f"{role}-{NONCE}@example.invalid" for role in ("admin", "user")}


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def request(host, method, path, body=None, token=None, headers=None):
    selected = {"Content-Type": "application/json"}
    if token:
        selected["Authorization"] = "Bearer " + token
    selected.update(headers or {})
    req = urllib.request.Request(f"http://{host}:8080{path}", method=method, headers=selected,
                                 data=None if body is None else json.dumps(body).encode())
    try:
        with urllib.request.urlopen(req, timeout=5) as response:
            status, raw = response.status, response.read(65537)
    except urllib.error.HTTPError as failure:
        status, raw = failure.code, failure.read(65537)
    require(len(raw) <= 65536, "oversized fixture response")
    try:
        value = json.loads(raw)
    except (UnicodeDecodeError, json.JSONDecodeError):
        value = None
    return status, value


def sql(service, statement, arguments=()):
    dbname = "peekcart_" + service
    connection = pymysql.connect(host="mysql", user=dbname, password=dbname, database=dbname,
                                 connect_timeout=3, read_timeout=5, write_timeout=5,
                                 autocommit=True, cursorclass=pymysql.cursors.DictCursor)
    try:
        with connection.cursor() as cursor:
            cursor.execute(statement, arguments)
            return cursor.fetchall() if cursor.description else cursor.rowcount
    finally:
        connection.close()


def json_bytes(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, default=str).encode()


def write(name, value):
    target = OUT / name
    temp = target.with_suffix(target.suffix + ".tmp")
    temp.write_bytes(json_bytes(value))
    temp.replace(target)


def snapshot():
    return {"ledger": sql(SERVICE, "SELECT * FROM dead_letter_records ORDER BY id"),
            "outbox": sql(SERVICE, "SELECT * FROM outbox_events ORDER BY id")}


def login(role):
    status, value = request("gateway", "POST", "/api/v1/auth/login",
                            {"email": EMAILS[role], "password": PASSWORD})
    require(status == 200 and isinstance(value, dict), f"actual login status={status}")
    token = value.get("data", {}).get("accessToken")
    require(isinstance(token, str) and len(token.split(".")) == 3, "actual login lacks access JWT")
    return token


def claims(token):
    encoded = token.split(".")[1]
    return json.loads(base64.urlsafe_b64decode(encoded + "=" * (-len(encoded) % 4)))


def check_disabled(token, name, results):
    status, value = request("gateway", "POST", PATH, BODY, token)
    require(status == 200, f"{name}: expected closed endpoint200, observed{status}")
    require(isinstance(value, dict) and value.get("id") == ROOT_ID and value.get("rootId") == ROOT_ID,
            f"{name}: missing exact root identity")
    require(value.get("accepted") is False and value.get("rejections") == [DISABLED], f"{name}: wrong disabled contract")
    require(value.get("attemptId") is None and value.get("targetId") is None, f"{name}: replay attempt unexpectedly allocated")
    results.append({"case": name, "status": status, "disabled": True})


def rejected(host, path, status, name, results, token=None, headers=None, method="POST"):
    actual, _ = request(host, method, path, BODY if method == "POST" else None, token, headers)
    allowed = status if isinstance(status, tuple) else (status,)
    require(actual in allowed, f"{name}: expected{allowed}, observed{actual}")
    results.append({"case": name, "status": actual})


def migrations(service, location):
    files = sorted(Path(location).glob("V*__*.sql"))
    require(bool(files), "migration source inventory empty")
    rows = sql(service, "SELECT script,success FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank")
    require(sorted(row["script"] for row in rows) == sorted(file.name for file in files), "actual Flyway source inventory mismatch")
    require(all(row["success"] == 1 for row in rows), "failed Flyway migration")
    return {file.name: hashlib.sha256(file.read_bytes()).hexdigest() for file in files}


def seed():
    source = {"user": migrations("user", "/work/migrations/user"),
              SERVICE: migrations(SERVICE, "/work/migrations/resource")}
    for role, email in EMAILS.items():
        status, _ = request("gateway", "POST", "/api/v1/auth/signup",
                            {"email": email, "password": PASSWORD, "name": "Signed fixture " + role})
        require(status == 201, f"actual signup status={status}")
    require(sql("user", "UPDATE users SET role='ADMIN' WHERE email=%s AND role='USER'", (EMAILS["admin"],)) == 1,
            "fixture ADMIN promotion did not select one own user")
    require(not sql(SERVICE, "SELECT id FROM dead_letter_records"), "resource fixture is not fresh")
    sql(SERVICE, "INSERT INTO dead_letter_records (id,root_record_id,cluster_id,topic_generation,origin_topic,"
        "origin_partition,origin_offset,failed_consumer_group,origin_kind,status,attempt_count,occurred_at) "
        "VALUES (%s,%s,%s,1,'fixture.topic',0,1,'fixture.group','RESOLVED_ORIGIN','OPEN',1,'2026-10-21 00:00:00.123456')",
        (ROOT_ID, ROOT_ID, "signed-" + NONCE))
    write("migration-provenance.json", source)
    write("before.json", snapshot())


def baseline(results):
    seed()
    admin = login("admin")
    user = login("user")
    require(claims(admin).get("role") == "ADMIN" and claims(user).get("role") == "USER", "actual login roles differ")
    require(bool(claims(admin).get("family_id")), "actual login family missing")
    check_disabled(admin, "admin-real-signed-chain", results)
    rejected("gateway", PATH, 403, "user-role", results, user)
    forged = {"X-User-Id": "1", "X-User-Role": "ADMIN", "X-User-Family-Id": "forged", "X-Internal-Auth": "forged"}
    rejected("gateway", PATH, 403, "user-with-forged-trusted-headers", results, user, forged)
    rejected("gateway", PATH, 401, "missing-bearer-with-forged-headers", results, headers=forged)
    rejected("gateway", PATH, 401, "malformed-bearer", results, "not-a-jwt")
    segments = admin.split(".")
    # Attacker-only key exists in memory; genuine User/Gateway private keys never reach this runner.
    attacker = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    signature = attacker.sign((segments[0] + "." + segments[1]).encode(), padding.PKCS1v15(), hashes.SHA256())
    segments[2] = base64.urlsafe_b64encode(signature).decode().rstrip("=")
    rejected("gateway", PATH, 401, "invalid-rsa-signature", results, ".".join(segments))
    rejected("resource-service", f"/actuator/deadletter/{ROOT_ID}", 401, "direct-plaintext-admin", results, headers=forged)
    rejected("gateway", f"/api/v1/admin/deadletter/{SERVICE}/not-a-number", 404, "non-numeric-route", results, admin)
    rejected("gateway", "/actuator/deadletter", 404, "gateway-actuator-not-public", results, admin, method="GET")
    rejected("gateway", f"/api/v1/admin/deadletter/{SERVICE}/%2e%2e/health", (400, 404), "encoded-traversal-route", results, admin)
    check_disabled(admin, "admin-restored-after-negative-requests", results)


def main():
    require(SERVICE in {"order", "product", "payment", "notification"}, "unsupported resource")
    phase = sys.argv[1]
    require(phase in {"baseline", "wrong-key", "restored", "expired", "final"}, "unsupported phase")
    results = []
    report = {"service": SERVICE, "phase": phase, "complete": False, "cases": results}
    try:
        if phase == "baseline":
            baseline(results)
        elif phase == "wrong-key":
            rejected("gateway", PATH, 401, "wrong-internal-public-key", results, login("admin"))
        elif phase == "expired":
            token = login("admin")
            check_disabled(token, "short-lived-real-token-before-expiry", results)
            expiration = float(claims(token)["exp"])
            wait = expiration + 1 - time.time()
            require(0 < wait < 15, "fixture issuer TTL was not bounded short-lived")
            time.sleep(wait)
            rejected("gateway", PATH, 401, "real-user-token-expired", results, token)
        else:
            check_disabled(login("admin"), phase + "-real-chain", results)
        before = json.loads((OUT / "before.json").read_text())
        after = snapshot()
        require(json_bytes(before) == json_bytes(after), "ledger/outbox changed during closed replay or rejection")
        write(phase + "-after.json", after)
        report["unchangedSha256"] = hashlib.sha256(json_bytes(after)).hexdigest()
        report["complete"] = True
    except BaseException as failure:
        # No exception message/body/token: only fixture case assertions are written above.
        report["failureType"] = type(failure).__name__
        raise
    finally:
        write(phase + ".json", report)


if __name__ == "__main__":
    main()
