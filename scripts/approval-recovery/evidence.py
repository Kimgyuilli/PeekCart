#!/usr/bin/env python3
"""Independent inventory and durable distributed-state checks for the recovery fixture."""
import hashlib
import json
from pathlib import Path
import shutil
import sys
import tempfile

ROOT=Path(__file__).resolve().parents[2]
PHASES={
    "setup": {"request-role-None":401,"request-role-user":403,"request-role-outsider":403,"request-role-executor":403,
              "reject-2003":400,"reject-2004":400,
              **{name+str(order):status for order in (2001,2002) for name,status in
                 (("request-",200),("request-duplicate-",200),("wrong-approver-",403),("approve-",200),("same-actor-execute-",403))}},
    "execute-paused":{"concurrent-execute":200,"stale-snapshot-execute":200,"ack-required":200},
    "completed":{"happy-ack-completed":200,"stale-cas-review":200,"failed-event-no-double-stock":200},
    "duplicates":{"completed-execute-duplicate":200,"same-and-new-id-redelivery":200}}


def require(condition,message):
    if not condition:raise AssertionError(message)


def read(path):return json.loads(path.read_text())


def events(snapshot,side,order,kind):
    return [row for row in snapshot[side+"Outbox"] if row["aggregate_id"]==str(order) and row["event_type"]==kind]


def verify(directory,checkout,images):
    result=read(directory/"result.json")
    require(result.get("complete") is True and result.get("phases")==list(PHASES),"incomplete recovery execution")
    source=read(directory/"source-images.json")
    require(source["checkout"]==checkout and source["images"]==images and set(images)=={"user-service","gateway","payment-service","product-service"},"source/image identity mismatch")
    provenance=read(directory/"migration-provenance.json")
    for service in ("user","payment","product"):
        expected={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in (ROOT/(service+"-service/src/main/resources/db/migration")).glob("V*__*.sql")}
        require(bool(expected) and provenance[service]==expected,"migration source differs")
    for phase,expected in PHASES.items():
        report=read(directory/(phase+".json"))
        require(report.get("complete") is True and report.get("phase")==phase and report.get("service")=="payment","incomplete phase")
        cases=report["cases"]
        require(len(cases)==len(expected) and {c["case"]:c["status"] for c in cases}==expected,"case inventory/status differs")
    paused=read(directory/"execute-paused-state.json")
    for order in ("2001","2002"):
        state=paused["states"][order]
        require(state["approval"]["status"]=="RECOVERY_PENDING" and state["inventory"]["stock"]==3,"completed before ack")
        recovery=next(r for r in paused["recoveries"] if str(r["order_id"])==order)
        require(recovery["status"]=="EXECUTION_REQUESTED","ack-required recovery missing")
    final=read(directory/"duplicates-state.json")
    seed=read(directory/"seed-before.json")
    for order in ("2003","2004"):
        require(final["states"][order]==seed["states"][order],"invalid PG altered business state")
        require(not [r for r in final["recoveries"] if str(r["order_id"])==order],"invalid PG created recovery")
        require(any(str(a["order_id"])==order and a["action"]=="REQUEST_REJECTED" for a in final["audit"]),"PG rejection audit absent")
    happy,stale=final["states"]["2001"],final["states"]["2002"]
    require(happy["inventory"]["stock"]==5 and happy["reservation"]["status"]=="RELEASED" and happy["approval"]["failure_code"]=="RECOVERY_PROVEN_NO_CHARGE" and happy["payment"]["status"]=="FAILED","happy release/finalization differs")
    require(stale["inventory"]["stock"]==3 and stale["reservation"]["status"]=="APPROVAL_HELD" and stale["approval"]["status"]=="RECOVERY_PENDING","stale CAS released stock")
    for order,status in ((2001,"COMPLETED"),(2002,"REVIEW_REQUIRED")):
        recovery=next(r for r in final["recoveries"] if r["order_id"]==order)
        require(recovery["status"]==status and recovery["generation"]==8 and recovery["approved_by"]=="1" and recovery["executed_by"]=="2","recovery status/actor/fence differs")
        pg=json.loads(recovery["pg_evidence"])
        require(pg["paymentKey"]==f"e2e-fence-recovery-{order}" and pg["orderId"]==f"pc_recovery_{order}" and type(pg["balanceAmount"]) is int and pg["balanceAmount"]==0 and pg["totalAmount"]==10000 and pg["status"]=="ABORTED","stored direct PG evidence differs")
        require(hashlib.sha256(recovery["pg_evidence"].encode()).hexdigest()==recovery["evidence_digest"],"evidence digest differs")
        flow=(("payment","payment.approval-recovery.requested",1,"product","product-svc-approval-recovery-request-group"),
              ("product","stock.approval-recovery.snapshot",1,"payment","payment-svc-approval-recovery-snapshot-group"),
              ("payment","payment.approval-recovery.execute",1,"product","product-svc-approval-recovery-execute-group"),
              ("product","stock.approval-recovery.result",2 if order==2001 else 1,"payment","payment-svc-approval-recovery-result-group"))
        for side,kind,count,target,group in flow:
            rows=events(final,side,order,kind)
            require(len(rows)==count and all(r["status"]=="PUBLISHED" for r in rows),"actual outbox flow/count differs")
            for row in rows:
                envelope=json.loads(row["payload"])
                require(envelope["eventId"]==row["event_id"] and envelope["payload"]["recoveryId"]==recovery["id"],"envelope/recovery correlation differs")
                require(sum(i["event_id"]==row["event_id"] and i["consumer_group"]==group for i in final[target+"Inbox"])==1,"outbox inbox correlation missing/duplicate")
        require(len(events(final,"payment",order,"payment.failed"))==(1 if order==2001 else 0),"payment.failed finalization differs")
    original=[json.loads(line) for line in (directory/"broker-execute.stdout").read_text().splitlines() if line.startswith("{")]
    expected=[json.loads(r["payload"]) for r in final["paymentOutbox"] if r["event_type"]=="payment.approval-recovery.execute"]
    require(len(original)==2 and sorted(original,key=lambda e:e["eventId"])==sorted(expected,key=lambda e:e["eventId"]),"actual Kafka envelope differs from outbox")
    duplicate=read(directory/"duplicate-event.json")
    require(sum(i["event_id"]==duplicate["eventId"] and i["consumer_group"]=="product-svc-approval-recovery-execute-group" for i in final["productInbox"])==1,"new-id redelivery not consumed")
    calls=read(directory/"pg-application.json")["calls"]
    require(bool(calls) and all(c["method"]=="GET" for c in calls),"app reapproved/canceled instead of queried")
    cleanup=read(directory/"cleanup.json")
    require(cleanup["complete"] is True and {k:len(v) for k,v in cleanup["removed"].items()}=={"container":8,"network":1,"volume":1},"owned cleanup incomplete")
    require(read(directory/"artifact-scan.json")=={"safe":True,"removedFiles":[]},"unsafe evidence")


def controls(directory,checkout,images):
    verify(directory,checkout,images)
    mutations=[("result.json",lambda x:x.update(complete=False)),
               ("source-images.json",lambda x:x.update(checkout="wrong")),
               ("setup.json",lambda x:x["cases"].pop()),
               ("duplicates-state.json",lambda x:x["states"]["2001"]["inventory"].update(stock=7)),
               ("duplicates-state.json",lambda x:x["states"]["2002"]["inventory"].update(stock=5)),
               ("duplicates-state.json",lambda x:x["productInbox"].clear()),
               ("execute-paused-state.json",lambda x:x["recoveries"][0].update(status="COMPLETED")),
               ("cleanup.json",lambda x:x.update(complete=False))]
    for name,mutate in mutations:
        with tempfile.TemporaryDirectory() as temp:
            copy=Path(temp)/"evidence";shutil.copytree(directory,copy)
            value=read(copy/name);mutate(value);(copy/name).write_text(json.dumps(value))
            try:verify(copy,checkout,images)
            except (AssertionError,KeyError,StopIteration):continue
            raise AssertionError("altered recovery evidence accepted: "+name)
    print("actual recovery evidence: 24 cases, four event hops, stock/CAS/ack/idempotency and eight negative mutations PASS")


if __name__=="__main__":
    directory,checkout,archives=Path(sys.argv[1]),sys.argv[2],Path(sys.argv[3])
    images={name:(archives/("recovery-image-"+name)/"image-id.txt").read_text().strip() for name in ("user-service","gateway","payment-service","product-service")}
    controls(directory,checkout,images)
