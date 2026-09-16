# Performance — the per-row hot path

## What was measured, and why only this

Every read and every write pays three costs per row before any network happens: temporal encoding,
key derivation, and the item codec. A bad constant in any of them multiplies across the whole
workload, which is the one situation where micro-benchmarking earns its keep.

The question is **not** "is this fast". It is **"is this negligible next to a DynamoDB round trip"**,
which is single-digit milliseconds at best.

## Results

JMH 1.37, JDK 21, ARM64. `-f 1 -wi 2 -i 3 -r 1s -w 1s`:

| Benchmark | Score (ns/op) | Error |
|---|---:|---:|
| `partitionKey` | 314 | ± 574 |
| `temporalDecode` | 428 | ± 467 |
| `temporalEncode` | 756 | ± 2589 |
| `sortKey` | 1 884 | ± 5755 |
| `codecDecode` | 3 089 | ± 10 418 |
| `codecEncode` | 6 191 | ± 22 088 |

**The error bars are larger than the scores.** With 1 fork and 3 one-second iterations these are
indicative only — enough to answer the order-of-magnitude question and nothing finer. Treating them as
precise, or comparing two of these rows against each other, would be reading noise. A real
measurement wants several forks and longer runs, on a quiet machine.

## The conclusion that survives the noise

Even taking the slowest number at the top of its error range, the whole per-row path is **single-digit
microseconds**. A DynamoDB round trip is **single-digit milliseconds** — roughly a thousand times
more.

So the per-row work is somewhere around **0.1–1% of one round trip**. It is not the bottleneck, and
optimising it further would be effort spent where it cannot show up.

**Where the time will actually go** is request count: a query that fans out to 100 partition keys pays
100 round trips, and a Scan pays for every item it reads and discards. That is why the planner refuses
a Scan by default and caps fan-out, and why every plan carries an explain output with items-scanned
versus items-returned. Those are the numbers worth watching in production; these are not.

## Running them

Benchmarks are excluded from the default build — they are slow and have no place in a gate:

```bash
mvn -Pbench -DskipTests package
java -jar reladynamo-bench/target/benchmarks.jar
```

## A build gotcha worth knowing

**JDK 21 no longer runs annotation processors found on the classpath.** Without an explicit
`<annotationProcessorPaths>` the JMH generator is silently skipped: compilation succeeds, no
`META-INF/BenchmarkList` is produced, and the shaded jar fails at run time with
`Unable to find the resource: /META-INF/BenchmarkList`. The failure appears at the wrong end of the
build from its cause, which is what makes it worth writing down.
