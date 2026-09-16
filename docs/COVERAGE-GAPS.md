# What the test suite does not cover

Finding 12 was found by writing the first test of a thing nobody had tested, and it contained a real
bug. That is an argument for enumerating the untested surface deliberately rather than discovering it
one embarrassment at a time.

This document is that enumeration. It is the counterpart to the gate: the gate says what passes, this
says what was never asked.

## The SPI is one-third implemented

`DynamoDbPersister` implements 32 methods of Reladomo's persister interfaces. **11 do something; 21
refuse by name.**

| Implemented | Refuses (`UnsupportedOperationException`) |
|---|---|
| `insert`, `delete`, `purge` | `findCursor`, `computeFunction` |
| `batchInsert`, `batchDelete`, `batchDeleteQuietly`, `batchPurge` | `refresh`, `refreshDatedObject` |
| `update` (×2) | `findAggregatedData` |
| `find`, `count` | `loadFullCache`, `reloadFullCache`, `renewCacheForOperation` |
| `setTxParticipationMode` | `extractDatabaseIdentifiers` (×2) |
| | `findForMassDelete`, `deleteUsingOperation`, `deleteBatchUsingOperation` |
| | `batchUpdate`, `multiUpdate` |
| | `prepareForMassDelete`, `prepareForMassPurge` (×2) |
| | **`getForDateRange`, `enrollDatedObject`** |

Refusing loudly is the right behaviour for an unimplemented method — an adapter returning an empty
list looks like a working query over an empty table. But "the write path is done" is a claim about
the 11, and a Reladomo application that touches any of the 21 will stop, not degrade.

`enrollDatedObject` and `getForDateRange` are the two most likely to be hit early: both are declared
on `MithraDatedObjectPersister` itself, so they exist precisely because dated objects need them.

**Measured, not assumed:** `BoundWritePathTest` drives an insert through a bound portal inside a real
Reladomo transaction, and it succeeds — so neither is reached on the insert path. They remain
unimplemented, and an update or a date-range read may still hit them; that is untested.

## Structural blind spots in the differential suite

| Area | Covered? | Note |
|---|---|---|
| Storage round trip | **48 tests** | writes via `DynamoDbWriter`, reads back, exact comparison |
| Query path (as-of translation) | **6 tests** | finder-driven; was broken (finding 12), now fixed |
| **Writes through a bound portal** | **5 tests** | insert, update and terminate driven by Reladomo in a real transaction; a bound update is compared against H2's version set, not merely checked for absence of an exception |
| Relationships: child rows stored | **1 test** | a bitemporal child round-trips with all four boundaries |
| Relationships: **deep-fetch navigation** | **3 tests** | finding 15 fixed — GSI on the foreign key with `IN` fan-out. Measured **1 query for 24 children across 8 parents** |
| Aggregation (`findAggregatedData`) | **no** | refuses |
| Cursors / streaming | **no** | refuses |
| Cache interaction | **partial** | `find()` populates the cache; eviction and refresh untested |
| Transactions across entities | **no** | `TransactWriteItems` caps make full equivalence impossible |
| Concurrency / optimistic locking | **no** | no test writes from two threads |
| Real AWS behaviour | **no** | DynamoDB Local only — no throttling, GSI lag, or IAM |
| Scale | **no** | largest table is tens of items |

## Why 48 green tests missed finding 12

Every storage-path test reads back with `pk = :pk` and **no sort-key condition**. That is the correct
way to fetch *every version of a row* for comparison — and it means none of them ever asked DynamoDB
an as-of question. The suite proved the write path thoroughly and the read path not at all, while
reporting a single number that read like both.

The gate now reports the two paths separately for that reason.

## How to use this document

Before claiming the adapter handles something, check whether it is on this list. If it is, either the
claim is wrong or this document is out of date — and both are worth knowing.
