#!/usr/bin/env python3
"""Actual signed HTTP and DB observations. Initial held state is explicitly a fixture seed."""
import concurrent.futures
import json
from pathlib import Path
import sys
import time
import uuid

sys.path.insert(0, "/work/scripts")
import verify as signed

require, sql, request, write = signed.require, signed.sql, signed.request, signed.write
OUT = Path("/work/out")
PATH = "/api/v1/payments/approval-recoveries"
ORDERS = (2001, 2002, 2003, 2004)
ROLES = ("approver", "executor", "outsider", "user")
signed.EMAILS = {role: f"{role}-{signed.NONCE}@example.invalid" for role in ROLES}


def wait_for(label, predicate, seconds=90):
    deadline = time.monotonic() + seconds
    while True:
        value = predicate()
        if value:
            return value
        require(time.monotonic() < deadline, label + " timed out")
        time.sleep(.5)


def one(service, query, args=()):
    rows = sql(service, query, args)
    require(len(rows) == 1, "expected one fixture row")
    return rows[0]


def state(order):
    return {"payment": one("payment", "SELECT order_id,status FROM payments WHERE order_id=%s", (order,)),
            "approval": one("payment", "SELECT order_id,status,generation,failure_code FROM payment_approvals WHERE order_id=%s", (order,)),
            "reservation": one("product", "SELECT order_id,status,version,approval_fenced,recovery_id FROM stock_reservations WHERE order_id=%s", (order,)),
            "inventory": one("product", "SELECT product_id,stock FROM inventories WHERE product_id=%s", (order,))}


def evidence():
    return {"states": {str(order): state(order) for order in ORDERS},
            "recoveries": sql("payment", "SELECT * FROM approval_recoveries ORDER BY order_id"),
            "audit": sql("payment", "SELECT * FROM approval_recovery_audit ORDER BY id"),
            "paymentOutbox": sql("payment", "SELECT * FROM outbox_events ORDER BY id"),
            "productOutbox": sql("product", "SELECT * FROM outbox_events ORDER BY id"),
            "paymentInbox": sql("payment", "SELECT * FROM processed_events ORDER BY id"),
            "productInbox": sql("product", "SELECT * FROM processed_events ORDER BY id")}


def api(path, role, results, name, expected=200, method="POST"):
    token = signed.login(role) if role else None
    status, body = request("gateway", method, PATH + path, {"reason": "Owned fixture evidence reconciliation"} if method == "POST" else None, token)
    require(status == expected, f"{name}: expected{expected}, observed{status}")
    results.append({"case": name, "status": status})
    return body.get("data") if isinstance(body, dict) else None


def setup(results):
    provenance = {name: signed.migrations(name, path) for name, path in
                  (("user", "/work/migrations/user"), ("payment", "/work/migrations/resource"), ("product", "/work/migrations/product"))}
    write("migration-provenance.json", provenance)
    for expected_id, role in enumerate(ROLES, 1):
        status, _ = request("gateway", "POST", "/api/v1/auth/signup",
                            {"email": signed.EMAILS[role], "password": signed.PASSWORD, "name": "Recovery fixture " + role})
        require(status == 201, "fixture signup failed")
        row = one("user", "SELECT id FROM users WHERE email=%s", (signed.EMAILS[role],))
        require(row["id"] == expected_id, "fresh fixture principal identity differs")
        if role != "user":
            require(sql("user", "UPDATE users SET role='ADMIN' WHERE id=%s AND role='USER'", (expected_id,)) == 1, "own ADMIN promotion failed")
        claims = signed.claims(signed.login(role))
        require(str(claims["sub"]) == str(expected_id) and claims["role"] == ("USER" if role == "user" else "ADMIN"), "actual principal differs")
    require(not sql("payment", "SELECT id FROM payments") and not sql("product", "SELECT id FROM stock_reservations"), "warm fixture rows")
    sql("product", "INSERT INTO categories(id,name) VALUES(1,'recovery fixture')")
    for order in ORDERS:
        key, pgid = f"e2e-fence-recovery-{order}", f"pc_recovery_{order}"
        sql("product", "INSERT INTO products(id,category_id,name,price,status,created_at) VALUES(%s,1,'fixture',5000,'ON_SALE',UTC_TIMESTAMP(6))", (order,))
        sql("product", "INSERT INTO inventories(product_id,stock,updated_at) VALUES(%s,3,UTC_TIMESTAMP(6))", (order,))
        sql("product", "INSERT INTO stock_reservations(order_id,status,items,source_event_id,reserved_at,created_at,approval_fenced,version) "
            "VALUES(%s,'APPROVAL_HELD',%s,%s,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),true,4)",
            (order, json.dumps([{"productId": order, "quantity": 2}]), str(uuid.uuid4())))
        sql("payment", "INSERT INTO payments(order_id,user_id,payment_key,pg_order_id,amount,status,created_at) VALUES(%s,1,%s,%s,10000,'PENDING',UTC_TIMESTAMP(6))", (order,key,pgid))
        sql("payment", "INSERT INTO payment_approvals(order_id,user_id,payment_key,pg_order_id,amount,status,generation,failure_code,requested_at,dispatch_deadline) "
            "VALUES(%s,1,%s,%s,10000,'FAILED',7,'MANUALLY_RESOLVED',UTC_TIMESTAMP(6)-INTERVAL 1 DAY,UTC_TIMESTAMP(6)-INTERVAL 1 HOUR)", (order,key,pgid))
        if order != 2003:  # genuine stub404: no approval identity registered for this key.
            status, _ = request("pg-stub", "POST", "/v1/payments/confirm", {"paymentKey":key,"orderId":pgid+"_wrong" if order==2004 else pgid,"amount":10000})
            require(status == 500, "fence fixture registration did not remain unresolved")
            require(request("pg-stub", "POST", "/__resolve-approval/"+key)[0] == 200, "fixture PG resolution failed")
    write("seed-before.json", evidence())
    write("pg-bootstrap.json", request("pg-stub", "GET", "/__ledger")[1])
    require(request("pg-stub", "DELETE", "/__ledger")[0] == 200, "stub observation boundary reset failed")
    for role in (None, "user", "outsider", "executor"):
        api("/orders/2001", role, results, "request-role-"+str(role), 401 if role is None else 403)
    for order in (2003,2004):
        before = state(order)
        api(f"/orders/{order}", "approver", results, "reject-"+str(order), 400)
        require(state(order) == before, "invalid PG evidence changed business state")
        require(not sql("payment", "SELECT id FROM approval_recoveries WHERE order_id=%s", (order,)), "invalid PG created recovery")
    ids = {}
    for order in (2001,2002):
        response = api(f"/orders/{order}", "approver", results, "request-"+str(order))
        ids[str(order)] = response["id"]
        duplicate = api(f"/orders/{order}", "approver", results, "request-duplicate-"+str(order))
        require(duplicate["id"] == response["id"], "request idempotency differs")
        wait_for("actual reservation snapshot", lambda: one("payment", "SELECT status FROM approval_recoveries WHERE id=%s", (response["id"],))["status"] == "READY_FOR_APPROVAL")
        api("/"+response["id"]+"/approve", "executor", results, "wrong-approver-"+str(order), 403)
        api("/"+response["id"]+"/approve", "approver", results, "approve-"+str(order))
        api("/"+response["id"]+"/execute", "approver", results, "same-actor-execute-"+str(order), 403)
    write("recovery-ids.json", ids)


def execute_paused(results):
    ids = json.loads((OUT/"recovery-ids.json").read_text())
    # Product is paused by the owner before this phase. This is a deterministic stale snapshot injection.
    require(sql("product", "UPDATE stock_reservations SET version=version+1 WHERE order_id=2002 AND version=4") == 1, "stale snapshot fixture changed unexpectedly")
    token = signed.login("executor")
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(request,"gateway","POST",PATH+"/"+ids["2001"]+"/execute",{"reason":"Concurrent owned execution"},token) for _ in range(2)]
        replies = [f.result(timeout=10) for f in futures]
    require(all(status == 200 and body["data"]["id"] == ids["2001"] for status,body in replies), "concurrent execute identity/status differs")
    results.append({"case":"concurrent-execute","status":200,"responses":2})
    api("/"+ids["2002"]+"/execute", "executor", results, "stale-snapshot-execute")
    for order in (2001,2002):
        wait_for("execution publication", lambda: sql("payment", "SELECT id FROM outbox_events WHERE aggregate_id=%s AND event_type='payment.approval-recovery.execute' AND status='PUBLISHED'", (str(order),)))
        row=one("payment", "SELECT status FROM approval_recoveries WHERE id=%s", (ids[str(order)],))
        require(row["status"] == "EXECUTION_REQUESTED" and state(order)["approval"]["status"] == "RECOVERY_PENDING" and state(order)["inventory"]["stock"] == 3, "completed/released before actual Product ack")
    results.append({"case":"ack-required","status":200})


def completed(results):
    ids=json.loads((OUT/"recovery-ids.json").read_text())
    for order, expected in ((2001,"COMPLETED"),(2002,"REVIEW_REQUIRED")):
        wait_for("actual Product result", lambda o=order,e=expected: one("payment", "SELECT status FROM approval_recoveries WHERE id=%s", (ids[str(o)],))["status"] == e)
    require(state(2001)["inventory"]["stock"] == 5 and state(2001)["reservation"]["status"] == "RELEASED", "happy stock release differs")
    require(state(2001)["approval"]["status"] == "FAILED" and state(2001)["approval"]["failure_code"] == "RECOVERY_PROVEN_NO_CHARGE", "ack did not finalize approval")
    require(state(2002)["inventory"]["stock"] == 3 and state(2002)["reservation"]["status"] == "APPROVAL_HELD" and state(2002)["approval"]["status"] == "RECOVERY_PENDING", "stale CAS released protection")
    failed = wait_for("payment failed published", lambda: sql("payment", "SELECT event_id FROM outbox_events WHERE aggregate_id='2001' AND event_type='payment.failed' AND status='PUBLISHED'"))
    wait_for("product received payment failed", lambda: sql("product", "SELECT id FROM processed_events WHERE event_id=%s", (failed[0]["event_id"],)))
    require(state(2001)["inventory"]["stock"] == 5, "payment.failed double restored stock")
    results.extend([{"case":"happy-ack-completed","status":200},{"case":"stale-cas-review","status":200},{"case":"failed-event-no-double-stock","status":200}])


def duplicates(results):
    new_id=json.loads((OUT/"duplicate-event.json").read_text())["eventId"]
    wait_for("new-id duplicate processed", lambda: sql("product", "SELECT id FROM processed_events WHERE event_id=%s AND consumer_group='product-svc-approval-recovery-execute-group'", (new_id,)))
    replies = wait_for("duplicate Product replies published", lambda: (lambda rows: rows if len(rows)==2 else None)(
        sql("product", "SELECT event_id FROM outbox_events WHERE aggregate_id='2001' AND event_type='stock.approval-recovery.result' AND status='PUBLISHED'")))
    for reply in replies:
        wait_for("duplicate ack received", lambda e=reply["event_id"]: sql("payment", "SELECT id FROM processed_events WHERE event_id=%s AND consumer_group='payment-svc-approval-recovery-result-group'", (e,)))
    ids=json.loads((OUT/"recovery-ids.json").read_text())
    api("/"+ids["2001"]+"/execute", "executor", results, "completed-execute-duplicate")
    require(state(2001)["inventory"]["stock"] == 5, "redelivery restored stock again")
    require(len(sql("payment", "SELECT id FROM outbox_events WHERE aggregate_id='2001' AND event_type='payment.approval-recovery.execute'")) == 1, "duplicate HTTP created extra execution")
    require(len(sql("payment", "SELECT id FROM outbox_events WHERE aggregate_id='2001' AND event_type='payment.failed'")) == 1, "duplicate finalization published twice")
    calls=request("pg-stub","GET","/__ledger")[1]["calls"]
    require(bool(calls) and all(c["method"] == "GET" for c in calls), "recovery unexpectedly confirmed/canceled PG")
    write("pg-application.json", {"calls":calls})
    results.append({"case":"same-and-new-id-redelivery","status":200})


if __name__ == "__main__":
    phase=sys.argv[1]
    report={"service":"payment","phase":phase,"complete":False,"cases":[]}
    try:
        {"setup":setup,"execute-paused":execute_paused,"completed":completed,"duplicates":duplicates}[phase](report["cases"])
        write(phase+"-state.json", evidence())
        report["complete"]=True
    finally:
        write(phase+".json",report)
