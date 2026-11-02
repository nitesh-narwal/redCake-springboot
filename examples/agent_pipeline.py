#!/usr/bin/env python3
"""
An agent pipeline on RedCake, with nothing but its own commands:

    producer --XADD--> [ stream: jobs ] --XREADGROUP--> workers --JSON.SET / VADD--> [ document + vector ]
                                  ^                        |
                                  +---- XAUTOCLAIM --------+   (a worker died: another takes its job)

* the RECORD (text, status, retry counter) is a JSON document      -> JSON.SET / JSON.GET / JSON.NUMINCRBY
* the JOBS are a stream with a consumer group                      -> XADD / XREADGROUP BLOCK / XACK / XAUTOCLAIM
* the EMBEDDINGS are a vector set, searched by similarity          -> VADD / VSIM

Run a server (java -jar target/redCake-0.0.1-SNAPSHOT.jar), then:   python3 examples/agent_pipeline.py [port]
Needs redis-py (pip install redis). RedCake speaks RESP2, so the client must say so: protocol=2.
The "embedding" is a toy (hashed words) so that the example has no model to download; swap in a real one.
"""
import hashlib
import math
import sys
import threading
import time

import redis

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 6379
DIM = 32
JOBS, GROUP = "jobs:embed", "embedders"


def connect():
    return redis.Redis(port=PORT, protocol=2, decode_responses=True)


def embed(text):
    """A bag-of-words hashed into DIM buckets, normalised. Similar words, similar vectors."""
    vector = [0.0] * DIM
    for word in text.lower().split():
        bucket = int(hashlib.md5(word.encode()).hexdigest(), 16) % DIM
        vector[bucket] += 1.0
    norm = math.sqrt(sum(v * v for v in vector)) or 1.0
    return [round(v / norm, 6) for v in vector]


# ---------------------------------------------------------------------------------------------- producer
def ingest(r, doc_id, text):
    """One key per record, and one job per record that needs work."""
    r.json().set(f"doc:{doc_id}", "$", {"id": doc_id, "text": text, "status": "new", "attempts": 0})
    return r.xadd(JOBS, {"doc": doc_id})


# ---------------------------------------------------------------------------------------------- workers
def process(r, entry_id, fields, crash=False):
    doc_id = fields["doc"]
    key = f"doc:{doc_id}"
    r.json().numincrby(key, "$.attempts", 1)
    r.json().set(key, "$.status", "processing")     # redis-py JSON-encodes the value
    if crash:                      # the worker dies here: no VADD, no XACK
        return
    text = r.json().get(key, "$.text")[0]
    r.execute_command("VADD", "docs:vec", "VALUES", DIM, *embed(text), doc_id)
    r.json().set(key, "$.status", "indexed")
    r.xack(JOBS, GROUP, entry_id)  # only now is the job done


def worker(name, stop, crash_first=False):
    r = connect()
    crash = crash_first
    while not stop.is_set():
        # BLOCK: the connection waits on the server (no polling) and is woken the moment a job arrives
        reply = r.xreadgroup(GROUP, name, {JOBS: ">"}, count=1, block=500)
        for _stream, entries in reply or []:
            for entry_id, fields in entries:
                process(r, entry_id, fields, crash=crash)
                if crash:
                    print(f"  [{name}] took {fields['doc']} and died without finishing it")
                    return
                print(f"  [{name}] indexed {fields['doc']}")


def janitor(stop):
    """Gives jobs that have been pending too long to a live worker (what a crashed worker left behind)."""
    r = connect()
    while not stop.is_set():
        _next, claimed, _deleted = r.xautoclaim(JOBS, GROUP, "janitor", min_idle_time=1000, start_id="0-0", count=10)
        for entry_id, fields in claimed:
            print(f"  [janitor] reclaimed {fields['doc']} (idle for 1 s)")
            process(r, entry_id, fields)
            print(f"  [janitor] indexed {fields['doc']}")
        time.sleep(0.3)


# ---------------------------------------------------------------------------------------------- the demo
DOCS = {
    "redis": "an in memory data store with strings hashes lists sets and streams",
    "jvm": "the java virtual machine runs bytecode with a garbage collector",
    "cake": "a chocolate cake needs flour sugar eggs and cocoa",
    "vector": "similarity search finds the nearest vectors of an embedding",
    "queue": "a durable job queue hands every job to exactly one worker",
    "bread": "bread needs flour water yeast and salt",
}

if __name__ == "__main__":
    r = connect()
    r.flushdb()
    r.xgroup_create(JOBS, GROUP, id="0", mkstream=True)

    stop = threading.Event()
    threads = [threading.Thread(target=worker, args=("alice", stop, True)),   # alice dies on her first job
               threading.Thread(target=worker, args=("bob", stop)),
               threading.Thread(target=janitor, args=(stop,))]
    for t in threads:
        t.start()

    print("producer: ingesting", len(DOCS), "documents")
    for doc_id, text in DOCS.items():
        ingest(r, doc_id, text)

    while r.xpending(JOBS, GROUP)["pending"] or r.xlen(JOBS) and r.xinfo_groups(JOBS)[0]["lag"]:
        time.sleep(0.2)
    time.sleep(0.5)
    stop.set()
    for t in threads:
        t.join()

    print("\nrecords (JSON documents):")
    for doc_id in DOCS:
        doc = r.json().get(f"doc:{doc_id}")
        print(f"  doc:{doc_id:<7} status={doc['status']:<8} attempts={doc['attempts']}")

    print("\nsimilarity search for 'flour and sugar for a cake':")
    hits = r.execute_command("VSIM", "docs:vec", "VALUES", DIM, *embed("flour and sugar for a cake"),
                             "COUNT", 3, "WITHSCORES")
    for name, score in zip(hits[::2], hits[1::2]):
        print(f"  {name:<8} score {float(score):.3f}")

    print("\nqueue state:", r.xpending(JOBS, GROUP)["pending"], "pending,", r.xlen(JOBS), "entries in the stream")
