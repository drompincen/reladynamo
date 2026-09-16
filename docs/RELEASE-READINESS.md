# Release readiness — 0.1.0

An honest assessment of what this is, what it proves, and what would have to be true before someone
depends on it in production.

## What exists

| | |
|---|---|
| Adapter source | 57 classes across `core`, `ddb`, `test-kit` |
| Persister SPI | **11 of 32 methods implemented**; **20 refuse by name**, 1 (`setTxParticipationMode`) is a silent no-op — see `docs/COVERAGE-GAPS.md` |
| Adapter tests | **254**, all green |
| Differential tests (H2 vs DynamoDB) | **48**, all green |
| Demo tests | **45** across three standalone projects, on both stores |
| Design documents | 4 945 lines, every API claim `javap`-verified |
| Conformance findings | 11 recorded, none an adapter divergence |
| Gates | 7, all passing — differential covers 48 storage-path + 6 query-path |

## What is genuinely proven

- **The seam works.** A custom persister binds to a Reladomo portal with one public call, and that
  single call binds both read and write. Demonstrated in a running JVM, not inferred.
- **Bitemporal semantics are inherited, not reimplemented.** 48 differential tests across 14 temporal
  operations and three directors show H2 and DynamoDB producing identical row sets, all four temporal
  boundaries compared exactly.
- **It is generic.** 60+ entities from three unrelated demo domains parse, key and round-trip. All
  three demos run against DynamoDB with their object model XML **byte-identical** — verified by digest.
- **Java 11 runs**, not just compiles: 110 core tests executed on a real Temurin JDK 11.
- **The MIT claim holds.** No ASL- or MPL-licensed dependency reaches compile or runtime scope,
  enforced by a build rule rather than by intention.

## What is NOT proven — read this before adopting

- **No live AWS.** Everything is DynamoDB Local. Throttling, adaptive capacity, GSI propagation delay,
  IAM, and real network failure modes are untested. This is the largest gap.
- **No load at scale.** The largest test table holds tens of items. Hot partitions, large item
  collections, and pagination under real volume are unexercised.
- **Query-path equivalence is now proven for the current-row query** — 6 finder-driven differential
  tests, after fixing finding 12. It remains unproven for the wider query surface: relationships,
  aggregation and cursors are untested or unimplemented (`docs/COVERAGE-GAPS.md`).
- ~~Infinity resolved from XML text~~ — **fixed at the root** (finding 13): `InfinityResolver` reads
  it from the generated `AsOfAttribute`, and `PhysicalDesign.Builder.infinityFrom` applies it wherever
  a design is built from a parsed mapping.
- **Transactions are not equivalent and cannot be.** `TransactWriteItems` caps at 100 items and 4 MB,
  and DynamoDB has no interactive transaction. Reladomo scopes wider than that are not atomic. This is
  a permanent limitation, not a gap to close.
- ~~Deep-fetch does not work~~ — **fixed** (finding 15). A GSI on the foreign key plus `IN` fan-out
  against it. Measured **1 query for 24 children across 8 parents**, with the Scan refusal intact for
  entities that have no such index.
- **`reladynamo-ddb` has never run on Java 11**, only compiled for it — DynamoDB Local publishes no
  `linux-aarch64` native, so it could not execute on this machine. CI on x86-64 closes that.

## Before 0.1.0 ships

- [ ] Run the differential suite against **real DynamoDB**, at least once, in a throwaway account
- [ ] Exercise relationships and deep-fetch differentially
- [ ] **Fix finding 12** and restore `FinderDrivenDifferentialTest` — currently the only proof of
      query-path equivalence, and it fails
- [ ] Establish the `japicmp` baseline — meaningless before a first release, essential from the second
- [ ] Decide the artifact coordinates and publishing target
- [ ] A load test large enough to page

## Versioning

`japicmp` is deliberately **not** configured yet. It compares a build against a published baseline,
and there is no published baseline until 0.1.0 exists. Adding it now would be a plugin that always
passes — which is worse than no check, because it looks like one. It becomes meaningful for 0.1.1.

## The honest summary

The architecture is proven and the semantics are verified against a reference implementation more
thoroughly than most adapters ever are. What has not happened is contact with a real AWS endpoint
under real load.

**Suitable for evaluation, prototyping and design review. Not for production data.**

Finding 12 is fixed and the current-row query now works through the adapter. What has still not
happened is contact with a real AWS endpoint under load, and two thirds of the persister SPI remains
unimplemented — see `docs/COVERAGE-GAPS.md` before assuming any given operation is supported.
