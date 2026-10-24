#!/usr/bin/env python3
"""Direct recovery scenario orchestration using the existing signed fixture owner."""
import argparse
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import uuid

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/"scripts/signed-gateway"))
import lab as signed

# One exec for every declared topic, list on stdin: the same approach as scripts/saga-e2e-smoke.sh.
TOPIC_CREATE_LOOP=('while IFS="\t" read -r topic parts; do '
                   '/opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:29092 --create --if-not-exists '
                   '--topic "$topic" --partitions "$parts" --replication-factor 1 || exit 1; done')


def declared_topics():
    """Topics the services declare, with partition counts, from the inventory the E2E pre-creation uses."""
    rows=[line.split("\t") for line in signed.output(["bash",str(ROOT/"scripts/kafka-subscription-contract-lint.sh"),"--emit-topics"],60).splitlines()]
    signed.require(len(rows)>=10 and all(len(row)==2 and row[1].isdigit() for row in rows),"declared topic inventory unreadable")
    return rows


def partition_counts(described):
    """Topic summary lines of kafka-topics.sh --describe only; per-partition lines start with a tab."""
    return dict(re.findall(r"^Topic: (\S+)\s.*?PartitionCount: (\d+)",described,re.M))


def execute(out):
    fixture=signed.Lab("payment",out)
    fixture.command[4:4]=["-f",str(ROOT/"docker-compose.approval-recovery.yml")]
    primary=None

    def broker_exec(argv, name, body=None, timeout=30):
        identity=subprocess.check_output(fixture.command+["ps","-q","kafka"],env=fixture.env,text=True,timeout=10).strip()
        inspected=json.loads(signed.output(["docker","container","inspect",identity]))[0]
        labels=inspected["Config"]["Labels"]
        signed.require(inspected["Id"]==identity and labels.get("peekcart.fixture.nonce")==fixture.nonce
                       and labels.get("com.docker.compose.project")==fixture.project,"broker ownership differs")
        response=subprocess.run(["docker","exec",*(["-i"] if body is not None else []),identity,*argv],
                                input=body,stdin=subprocess.DEVNULL if body is None else None,text=True,capture_output=True,timeout=timeout)
        (fixture.out/(name+".stdout")).write_text(response.stdout)
        (fixture.out/(name+".stderr")).write_text(response.stderr)
        signed.require(response.returncode==0,"owned Kafka command failed")
        return response.stdout

    def kafka(args, name, body=None):
        return broker_exec(["/opt/kafka/bin/"+args[0],"--bootstrap-server","kafka:29092",*args[1:]],name,body)

    try:
        fixture.prepare()
        source=json.loads((fixture.out/"source-images.json").read_text())
        source["images"]["product-service"]=signed.output(["docker","image","inspect","--format","{{.Id}}","product-service:ci"])
        signed.atomic_json(fixture.out/"source-images.json",source)
        # Create declared topics before any service starts (D-107). If a consumer auto-creates an owner's topic
        # with one partition and the owner's KafkaAdmin grows it later, subscribed consumers ignore the new
        # partitions until their metadata refresh (5 minutes by default), and unkeyed duplicates there never land.
        fixture.run(fixture.command+["up","-d","--wait","--wait-timeout","300","kafka"],"kafka-startup.log",360)
        declared=declared_topics()
        broker_exec(["sh","-c",TOPIC_CREATE_LOOP],"topics-create","".join(topic+"\t"+parts+"\n" for topic,parts in declared),180)
        counts=partition_counts(kafka(["kafka-topics.sh","--describe"],"topics-describe"))
        signed.require(all(counts.get(topic)==parts for topic,parts in declared),"pre-created topic partitions differ")
        fixture.run(fixture.command+["up","-d","--wait","--wait-timeout","420","user-service","resource-service","product-service","gateway","pg-stub"],"startup.log",600)
        groups=("product-svc-approval-recovery-request-group","product-svc-approval-recovery-execute-group",
                "payment-svc-approval-recovery-snapshot-group","payment-svc-approval-recovery-result-group")
        deadline=time.monotonic()+90
        while True:
            ready=[]
            for group in groups:
                rows=[line.split() for line in kafka(["kafka-consumer-groups.sh","--describe","--members","--group",group],"ready-"+group).splitlines()]
                ready.append(any(len(row)>=5 and row[0]==group and row[4].isdigit() and int(row[4])>0 for row in rows))
            if all(ready):break
            signed.require(time.monotonic()<deadline,"actual recovery consumer assignment missing")
            time.sleep(1)
        fixture.phase("setup")
        fixture.run(fixture.command+["pause","product-service"],"pause-product.log",15)
        fixture.phase("execute-paused")
        fixture.run(fixture.command+["unpause","product-service"],"unpause-product.log",15)
        fixture.phase("completed")
        raw=kafka(["kafka-console-consumer.sh","--topic","payment.approval-recovery.execute","--from-beginning",
                   "--max-messages","2","--timeout-ms","15000","--consumer-property","enable.auto.commit=false"],"broker-execute")
        messages=[json.loads(line) for line in raw.splitlines() if line.startswith("{")]
        selected=[m for m in messages if m.get("payload",{}).get("orderId")==2001]
        signed.require(len(messages)==2 and len(selected)==1,"actual execution envelope inventory differs")
        original=selected[0]
        duplicate=dict(original,eventId=str(uuid.uuid4()))
        signed.atomic_json(fixture.out/"duplicate-event.json",duplicate)
        kafka(["kafka-console-producer.sh","--topic","payment.approval-recovery.execute","--producer-property","acks=all"],
              "duplicate-publish","\n".join(json.dumps(x) for x in (original,original,duplicate))+"\n")
        fixture.phase("duplicates")
        fixture.result["complete"]=True
    except BaseException as error:
        primary=type(error).__name__
        fixture.result["failureType"]=primary
    finally:
        try:fixture.run(fixture.command+["logs","--no-color"],"services.log",15)
        except BaseException as error:fixture.result["logCollectionFailure"]=type(error).__name__
        try:signed.cleanup(fixture.manifest,fixture.out)
        except BaseException as error:
            fixture.result["cleanupFailure"]=type(error).__name__
            primary=primary or "cleanup failure"
        shutil.rmtree(fixture.private,ignore_errors=True)
        if primary:fixture.result["complete"]=False
        signed.atomic_json(fixture.out/"result.json",fixture.result)
        try:signed.sanitize(fixture.out,fixture.nonce)
        except BaseException:primary=primary or "unsafe evidence"
    signed.require(primary is None,"recovery fixture failed; see bounded sanitized evidence")


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence",type=Path,required=True)
    execute(parser.parse_args().evidence)
