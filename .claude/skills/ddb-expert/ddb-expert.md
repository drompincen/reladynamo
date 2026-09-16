---
name: ddb-expert
description: This skill implements, reviews, tests, and troubleshoots Java 11 DynamoDB application code using AWS SDK for Java 2.x clients, including client and HTTP configuration, retries, batches, transactions, conditional writes, expressions, pagination, type conversion, DynamoDBLocal, and runtime observability; invoke it when the table model is already decided and the work is in or below `software.amazon.awssdk.services.dynamodb`.
user-invocable: true
---

# DynamoDB Java runtime expert

Own the Java application/runtime boundary. Defer access patterns, partition and sort keys, GSI/LSI
choice, projections as model design, capacity mode, cost design, hot keys, and migrations to
`/dynamodb-architect`.

## Client and transport

1. **Choose the lowest-level client that preserves the application's runtime shape, because mapping convenience becomes friction when schemas are dynamic.**
   Use `DynamoDbClient` for explicit `Map<String, AttributeValue>` control and adapter-style code.
   Use `DynamoDbEnhancedClient` for stable domain types, `TableSchema`, converters, and CRUD boilerplate.
   Avoid bean mapping when attribute names, types, or keys are generic and known only at runtime.
   Use `DynamoDbAsyncClient` for nonblocking pipelines; bound concurrency and never block event-loop threads.

2. **Create clients once and close them at shutdown, because each client owns reusable connection pools and transport resources.**
   Treat clients as thread-safe singletons; do not build one per request.
   Build an enhanced client over the same long-lived sync or async low-level client.
   Keep separate adaptive-retry clients per throttling resource if adaptive mode is chosen.

3. **Select the HTTP implementation deliberately, because its concurrency model determines connection pressure and tail latency.**
   Apache is the mature synchronous pooled default: tune `maxConnections`, acquisition, connect, and socket timeouts.
   Netty is the asynchronous choice: tune `maxConcurrency`, pending-acquire count, and event-loop ownership.
   `UrlConnectionHttpClient` is small and fast to initialize but lacks Apache's richer pooling/proxy controls.
   Match pool capacity to measured in-flight calls, not executor thread count alone.

4. **Bound both each attempt and the whole call, because socket timeouts alone permit retries to exceed an endpoint deadline.**
   `apiCallAttemptTimeout` covers one attempt; `apiCallTimeout` covers all attempts and backoff.
   Keep attempt timeout below call timeout and call timeout below the caller's deadline.
   Set connection-acquisition timeout so pool exhaustion fails diagnostically instead of appearing as service latency.
   Start from measured latency; DynamoDB guidance suggests 4-10 seconds rather than an unbounded call.

```java
import java.time.Duration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import software.amazon.awssdk.retries.api.BackoffStrategy;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

public final class DynamoClients {
    private DynamoClients() {}
    public static DynamoDbClient production() {
        BackoffStrategy backoff = BackoffStrategy.exponentialDelay(
                Duration.ofMillis(100), Duration.ofSeconds(5));
        StandardRetryStrategy retries = AwsRetryStrategy.standardRetryStrategy()
                .toBuilder().maxAttempts(6).backoffStrategy(backoff).build();
        return DynamoDbClient.builder()
                .credentialsProvider(DefaultCredentialsProvider.create())
                .region(DefaultAwsRegionProviderChain.builder().build().getRegion())
                .httpClientBuilder(ApacheHttpClient.builder()
                        .maxConnections(128)
                        .connectionAcquisitionTimeout(Duration.ofSeconds(1))
                        .connectionTimeout(Duration.ofSeconds(2))
                        .socketTimeout(Duration.ofSeconds(3)))
                .overrideConfiguration(o -> o
                        .apiCallAttemptTimeout(Duration.ofSeconds(4))
                        .apiCallTimeout(Duration.ofSeconds(12))
                        .retryStrategy(retries))
                .build();
    }
}
```

5. **Resolve credentials and Region through standard chains, because hard-coded secrets and implicit Regions fail across laptops, containers, and AWS runtimes.**
   `DefaultCredentialsProvider` checks system properties, environment, web identity, profiles, ECS, then EC2 metadata.
   `DefaultAwsRegionProviderChain` checks system property, environment, profile, then instance metadata.
   Pin a Region only when deployment configuration intentionally owns it.
   Override the endpoint only for DynamoDBLocal or an explicitly approved test service.

6. **Prefer `RetryStrategy` for new SDK versions and size its budget to the caller deadline, because legacy `RetryPolicy` can hide long retry tails.**
   Standard mode uses retry classification, exponential backoff, jitter, and circuit breaking.
   Adaptive mode adds client-side rate limiting and can delay first attempts; avoid it across unrelated tables.
   Retrying a non-idempotent application workflow requires application idempotency, not merely SDK retries.
   SDK request retries do not drain successful batch responses containing unprocessed entries.

## Batch operations

7. **Resubmit only `UnprocessedItems` until empty, because `BatchWriteItem` can return HTTP 200 after writing only part of the batch.**
   Send at most 25 put/delete requests and at most 16 MB per call; each stored item remains at most 400 KB.
   A batch cannot contain two operations on the same item and provides no all-or-nothing guarantee.
   Apply capped exponential backoff with full jitter between partial responses.
   Ignoring `UnprocessedItems` silently loses writes.

8. **Resubmit only `UnprocessedKeys` until empty, because `BatchGetItem` can return a successful partial read.**
   Request at most 100 items and at most 16 MB of response data per call.
   Results are unordered; correlate returned items by their complete primary keys.
   Preserve each table's projection and consistency settings when rebuilding the retry request.
   Stop at an explicit attempt or elapsed-time budget and surface remaining keys.

```java
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;
import java.util.List;

public final class DynamoBatches {
    private DynamoBatches() {}
    public static void writeAll(DynamoDbClient ddb,
                                Map<String, List<WriteRequest>> requests,
                                int maxAttempts) throws InterruptedException {
        Map<String, List<WriteRequest>> pending = requests;
        for (int attempt = 0; !pending.isEmpty(); attempt++) {
            if (attempt == maxAttempts) {
                throw new IllegalStateException("Batch write exhausted retries: " + pending.keySet());
            }
            BatchWriteItemResponse response = ddb.batchWriteItem(
                    BatchWriteItemRequest.builder().requestItems(pending).build());
            pending = response.unprocessedItems();
            if (!pending.isEmpty()) {
                sleepWithFullJitter(attempt, Duration.ofMillis(50), Duration.ofSeconds(5));
            }
        }
    }

    public static Map<String, List<Map<String, AttributeValue>>> getAll(
            DynamoDbClient ddb, Map<String, KeysAndAttributes> requests,
            int maxAttempts) throws InterruptedException {
        Map<String, KeysAndAttributes> pending = requests;
        Map<String, List<Map<String, AttributeValue>>> found = new java.util.HashMap<>();
        for (int attempt = 0; !pending.isEmpty(); attempt++) {
            if (attempt == maxAttempts) {
                throw new IllegalStateException("Batch get exhausted retries: " + pending.keySet());
            }
            BatchGetItemResponse response = ddb.batchGetItem(
                    BatchGetItemRequest.builder().requestItems(pending).build());
            response.responses().forEach((table, items) ->
                    found.computeIfAbsent(table, ignored -> new java.util.ArrayList<>()).addAll(items));
            pending = response.unprocessedKeys();
            if (!pending.isEmpty()) {
                sleepWithFullJitter(attempt, Duration.ofMillis(50), Duration.ofSeconds(5));
            }
        }
        return Collections.unmodifiableMap(found);
    }

    private static void sleepWithFullJitter(int attempt, Duration base, Duration cap)
            throws InterruptedException {
        long shift = Math.min(attempt, 20);
        long ceiling = Math.min(cap.toMillis(), base.toMillis() * (1L << shift));
        TimeUnit.MILLISECONDS.sleep(ThreadLocalRandom.current().nextLong(ceiling + 1));
    }
}
```

## Transactions and conditions

9. **Use transactions only for bounded cross-item invariants, because DynamoDB performs two underlying reads or writes per transactional item.**
   `TransactWriteItems` and `TransactGetItems` accept at most 100 distinct items totaling 4 MB.
   No transaction may target the same item with two actions.
   Transactional work has a two-operation capacity multiplier, including canceled work.
   Defer whether the invariant and item grouping are modeled correctly to `/dynamodb-architect`.

10. **Supply one stable `ClientRequestToken` per logical transactional write, because retries after an ambiguous timeout must not apply the mutation twice.**
    The token provides idempotency for 10 minutes after the initial request completes.
    Reusing it with changed parameters within that window raises `IdempotentParameterMismatchException`.
    Persist or deterministically derive the token across process and transport retries.
    Do not confuse request idempotency with a business uniqueness condition.

11. **Inspect cancellation reasons by action index, because `TransactionCanceledException` otherwise hides which condition or item failed.**
    `cancellationReasons()` aligns positionally with `transactItems()`.
    Check each reason's `code()`, `message()`, and optional `item()`; expect codes such as `ConditionalCheckFailed`, `TransactionConflict`, and `ValidationError`.
    Request old values on condition failure where the action supports `ReturnValuesOnConditionCheckFailure.ALL_OLD`.
    Treat a condition failure as a domain outcome and a transaction conflict as retryable contention.

```java
import java.util.Map;
import java.util.UUID;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.ReturnValuesOnConditionCheckFailure;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

public final class ConditionalCreate {
    private ConditionalCreate() {}
    public static void create(DynamoDbClient ddb, String table, String id) {
        Map<String, AttributeValue> item = Map.of(
                "pk", AttributeValue.builder().s("ORDER#" + id).build(),
                "version", AttributeValue.builder().n("1").build());
        Put put = Put.builder().tableName(table).item(item)
                .conditionExpression("attribute_not_exists(#pk)")
                .expressionAttributeNames(Map.of("#pk", "pk"))
                .returnValuesOnConditionCheckFailure(ReturnValuesOnConditionCheckFailure.ALL_OLD)
                .build();
        try {
            ddb.transactWriteItems(TransactWriteItemsRequest.builder()
                    .clientRequestToken(UUID.randomUUID().toString())
                    .transactItems(TransactWriteItem.builder().put(put).build())
                    .build());
        } catch (TransactionCanceledException e) {
            for (int i = 0; i < e.cancellationReasons().size(); i++) {
                CancellationReason reason = e.cancellationReasons().get(i);
                System.err.println(i + ": " + reason.code() + " " + reason.message());
            }
            throw e;
        }
    }
}
```

12. **Guard state transitions with a server-side condition, because read-then-write races cannot implement optimistic locking.**
    Create with `attribute_not_exists(#pk)` and update with `#version = :expected`.
    Increment the version in the same `UpdateItem`; catch `ConditionalCheckFailedException` as stale state.
    `ReturnValuesOnConditionCheckFailure.ALL_OLD` can return the rejected item's prior image without a second read.
    Enhanced-client version extensions help stable beans but obscure generic adapters and custom condition composition.

13. **Alias every untrusted or ambiguous expression token, because string concatenation causes reserved-word failures and expression injection.**
    Put attribute paths in `ExpressionAttributeNames`, especially names such as `status`, `size`, or containing dots/hyphens.
    Put all data in `ExpressionAttributeValues`; never quote or escape user data into expression text.
    Generate placeholders from validated structure and keep maps collision-free.
    Expressions are a grammar, not a SQL string builder.

## Reads and pagination

14. **Consume paginators or advance `ExclusiveStartKey` until absent, because one Query or Scan response is capped before the logical result ends.**
    Sync paginators iterate pages lazily; async paginators publish with backpressure.
    Manual loops must feed `lastEvaluatedKey()` into the next request unchanged.
    An empty page with a nonempty continuation key is not end-of-results.
    Return an opaque continuation token for bounded request paths instead of draining the table.

15. **Interpret `Limit` as items evaluated, because filters run after DynamoDB reads the candidate items.**
    A request with `limit(20)` and a filter can return 0-20 items and still have more pages.
    `FilterExpression` does not reduce consumed read capacity and commonly creates surprise bills.
    `ProjectionExpression` reduces payload and decoding, not capacity charged for the item read.
    Defer a read requiring different keys or an index to `/dynamodb-architect`.

16. **Request strong consistency only on supported resources, because GSIs accept only eventually consistent reads.**
    `GetItem`, base-table Query/Scan, and LSI reads can use `consistentRead(true)`.
    A GSI Query or Scan with strong consistency is rejected.
    Strong reads consume more capacity and do not make several separate calls atomic.

17. **Parallelize Scan with a fixed segment contract, because mismatched `Segment` and `TotalSegments` values skip or duplicate work.**
    Use segment values `0..TotalSegments-1` and the same total for every worker and resumed page.
    Persist each segment's `LastEvaluatedKey` independently.
    Bound worker count; parallel Scan reduces elapsed time but increases concurrent read pressure.
    Keep Scan out of latency-sensitive paths unless explicitly bounded.

```java
import java.util.HashMap;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

public final class QueryPages {
    private QueryPages() {}

    public static long countOpen(DynamoDbClient ddb, String table, String account) {
        Map<String, AttributeValue> start = null;
        long count = 0;
        do {
            QueryRequest.Builder request = QueryRequest.builder().tableName(table)
                    .keyConditionExpression("#pk = :pk")
                    .filterExpression("#state = :open")
                    .expressionAttributeNames(Map.of("#pk", "pk", "#state", "status"))
                    .expressionAttributeValues(Map.of(
                            ":pk", AttributeValue.builder().s(account).build(),
                            ":open", AttributeValue.builder().s("OPEN").build()))
                    .limit(100);
            if (start != null) request.exclusiveStartKey(start);
            QueryResponse page = ddb.query(request.build());
            count += page.count();
            start = page.lastEvaluatedKey().isEmpty()
                    ? null : new HashMap<>(page.lastEvaluatedKey());
        } while (start != null);
        return count;
    }
}
```

## Values and hard limits

18. **Treat `AttributeValue` as a tagged union, because reading the wrong member silently produces null-like defaults or conversion faults.**
    Handle `S`, `N`, `B`, `BOOL`, `NULL`, `M`, `L`, `SS`, `NS`, and `BS` explicitly.
    Decode `B` and `BS` as `SdkBytes`; copy with `asByteArray()` when ownership must escape the SDK object.
    Reject mixed-type sets and empty sets before sending the request.
    Preserve unknown map members in adapters when forward compatibility matters.

19. **Encode numbers through canonical `BigDecimal` strings, because DynamoDB `N` is a decimal string rather than an IEEE-754 value.**
    Use `new BigDecimal(text)`, validate `precision() <= 38`, then send `toPlainString()` or a deliberate exponent form.
    Nonzero magnitude is `1E-130` through `9.9999999999999999999999999999999999999E+125`, with symmetric negatives.
    Reject NaN and infinities; they are not DynamoDB numbers.
    Never round implicitly: call `setScale(scale, explicitRoundingMode)` only when the domain requires it.

20. **Distinguish absent, NULL, and empty values, because they have different condition and update semantics.**
    Omit an attribute for absence; use `AttributeValue.builder().nul(true)` for DynamoDB NULL.
    Empty strings and binaries are allowed for non-key attributes, but key attributes must be nonempty.
    Empty string, number, and binary sets are rejected; empty lists and maps are allowed.
    Validate at repository boundaries so `ValidationException` does not surface deep in a batch.

21. **Encode time in a fixed-width UTC format, because DynamoDB string ordering is bytewise lexicographic ordering.**
    Use `DateTimeFormatter` with UTC and a fixed fractional width, such as `yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSS'Z'`.
    Convert `java.sql.Timestamp` with `timestamp.toInstant()` before formatting.
    Parse with the same formatter; do not mix second, millisecond, and nanosecond widths.
    Encode positive infinity as an explicit versioned sentinel such as `9999-12-31T23:59:59.999999999Z`; reject real values at or beyond it.

22. **Enforce service size limits before serialization, because one oversized value can reject an otherwise valid batch or transaction.**
    An item is at most 400 KB including UTF-8 attribute names and values.
    A partition-key value is at most 2048 bytes and a sort-key value at most 1024 bytes.
    Transactions contain at most 100 distinct items and 4 MB in aggregate.
    Move large blobs to S3 with an immutable key, checksum, length, and lifecycle contract, or use bounded overflow chunks with ordered links.
    Defer the overflow item key model and access path to `/dynamodb-architect`.

## Failures and observability

23. **Classify service exceptions before retrying, because permanent request faults become load-amplifying retry storms.**
    Retry with jitter: `ProvisionedThroughputExceededException`, `RequestLimitExceededException`, `InternalServerErrorException`, and `TransactionConflictException`.
    AWS marks `ItemCollectionSizeLimitExceededException` retryable, but repeated attempts cannot help until the oversized LSI item collection changes.
    Do not retry unchanged `ValidationException` or `ResourceNotFoundException`; fix the request or table lifecycle.
    Treat `ConditionalCheckFailedException` and conditional cancellation reasons as expected concurrency or domain outcomes.
    Honor the SDK's retry classification for transport failures, then cap total elapsed time and attempts.

24. **Record capacity and AWS request metadata at the call boundary, because latency alone cannot distinguish throttling, expensive reads, and support-worthy service faults.**
    Set `ReturnConsumedCapacity.TOTAL` or `INDEXES` on sampled or diagnostic requests.
    Publish SDK client metrics with a `MetricPublisher`; keep label cardinality bounded.
    Read `response.responseMetadata().requestId()` and exception request IDs for AWS Support cases.
    Log operation, table or index, attempt count, consumed capacity, status code, and throttling reason without item data.

## DynamoDBLocal 2.5.3 testing

25. **Run DynamoDBLocal 2.5.3 in-process with an explicit native path, because missing `sqlite4java` binaries is the classic startup failure.**
    Add `com.amazonaws:DynamoDBLocal:2.5.3` from the AWS DynamoDBLocal Maven repository in test scope.
    Set `sqlite4java.library.path` to the extracted `DynamoDBLocal_lib` directory before server startup.
    A missing or wrong platform library surfaces as `UnsatisfiedLinkError` or “no sqlite4java-* in java.library.path”.
    Start on a free loopback port and use static fake credentials plus `Region.US_EAST_1` in the test client.

26. **Create and delete tables per test boundary, because shared local state makes passing tests order-dependent.**
    Wait for table ACTIVE after create and for absence after delete.
    `-inMemory` discards state on shutdown and requires `-sharedDb`; use it for isolated suites.
    `-sharedDb` stores all credentials and Regions in one database file; without it, those values select the file.
    DynamoDBLocal approximates API behavior, not IAM, Regions, latency, throttling, streams, backups, or production capacity.

```java
import java.net.URI;
import java.nio.file.Path;
import com.amazonaws.services.dynamodbv2.local.server.DynamoDBProxyServer;
import com.amazonaws.services.dynamodbv2.local.main.ServerRunner;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

public final class LocalDynamoRuntime implements AutoCloseable {
    private final DynamoDBProxyServer server;
    private final DynamoDbClient client;
    public LocalDynamoRuntime(Path nativeLibDir, int port) throws Exception {
        System.setProperty("sqlite4java.library.path", nativeLibDir.toAbsolutePath().toString());
        this.server = ServerRunner.createServerFromCommandLineArgs(new String[] {
                "-inMemory", "-sharedDb", "-port", Integer.toString(port)
        });
        server.start();
        this.client = DynamoDbClient.builder()
                .endpointOverride(URI.create("http://127.0.0.1:" + port))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("local", "local")))
                .build();
    }

    public DynamoDbClient client() { return client; }

    @Override public void close() throws Exception {
        client.close();
        server.stop();
    }
}
```

Both AWS SDK v2 and DynamoDBLocal 2.5.3 run on Java 11. Keep examples and test harnesses free of
records, sealed classes, virtual threads, `Stream.toList()`, and APIs introduced after Java 11.

## Common pitfalls

| Failure mode | Concrete symptom | Correction |
|---|---|---|
| Ignore `UnprocessedItems` | HTTP 200, missing rows, no exception | Drain the returned subset with capped exponential backoff and jitter. |
| Ignore `UnprocessedKeys` | Partial result looks like “not found” | Retry only returned keys and correlate by complete key. |
| Treat filter as selectivity | High RCUs and bill, few returned items | Move selection into a key condition; defer the model to `/dynamodb-architect`. |
| Treat `Limit` as result count | Sparse or empty pages before later matches | Continue from `LastEvaluatedKey` and bound work separately. |
| Retry throttling as network noise | Rising tail latency and retry volume | Record throttling reason, cap retries, and diagnose the named resource. |
| Send empty key/set values | `ValidationException` at runtime | Validate keys as nonempty and sets as nonempty before batching. |
| Build a client per request | Socket churn, pool exhaustion, latency spikes | Reuse one configured client and close it at process shutdown. |
| Concatenate expressions | Reserved-word errors or altered expression meaning | Alias names and bind every value. |
| Use enhanced beans for runtime schemas | Converter sprawl and lost unknown attributes | Use the low-level client and explicit `AttributeValue` maps. |
| Omit call timeouts | Requests outlive HTTP deadlines during retries | Set attempt and whole-call budgets coherently. |
| Reuse transaction token with changed input | `IdempotentParameterMismatchException` | Bind one stable token to one immutable logical request. |
| Miss sqlite native path | DynamoDBLocal fails with `UnsatisfiedLinkError` | Extract native libraries and set `sqlite4java.library.path` before startup. |

## Review checklist

- Confirm every batch drains unprocessed entries and has a bounded jittered retry budget.
- Confirm transaction tokens survive caller retries and cancellation reasons are mapped by index.
- Confirm conditions implement concurrency at the write, not through read-then-write.
- Confirm pagination continues on a nonempty key even when a filtered page is empty.
- Confirm number, empty-value, timestamp, binary, and size validation occurs before the SDK call.
- Confirm clients, transports, pools, timeouts, credentials, Regions, retries, metrics, and shutdown are explicit.
- Confirm DynamoDBLocal tests isolate tables and load the correct sqlite native library.
- Confirm modelling conclusions are handed to `/dynamodb-architect` instead of duplicated here.

## Field notes — learned building a real adapter, not read from the docs

Each of these cost debugging time on a working project. They are the things the API reference does
not tell you.

### `N` is lossy for two different reasons, and both bite

**Trailing zeros are trimmed.** `1.10` stored as `N` comes back as `1.1` — equal in value, different
in scale. For money that is silent data loss: the amount survives and the presentation and rounding
semantics do not. Store `BigDecimal` as `S` via `toPlainString()`, and validate against DynamoDB's
38-significant-digit envelope anyway so a later switch back stays possible.

**Doubles do not round-trip.** `N` is a decimal type; many `double` values have no exact decimal
form at DynamoDB's precision. If exact round-trip matters — and for a persistence adapter it does —
encode the bits rather than the decimal rendering, and prove it with a property test over
`MIN_VALUE`, `MAX_VALUE`, subnormals and negative zero.

The cost: an `S`-encoded number cannot be used in a native numeric `BETWEEN`. Usually acceptable;
know it before you choose.

### A `.n()` that returns null is usually your own encoding, not a bug

If you stored a number as `S` for the reasons above, `attributeValue.n()` is null and
`Double.parseDouble(null)` throws a `NullPointerException` from inside `FloatingDecimal`, which names
nothing useful. **Decode through your codec, never by reaching for the raw `AttributeValue`.** A test
that bypasses the codec is testing a different system from the one you ship.

### DynamoDB Local: the parts that waste an afternoon

- It is a **JNI wrapper over SQLite**. `sqlite4java.library.path` must point at unpacked native
  libraries before the server starts, or you get an `UnsatisfiedLinkError` that reads like a missing
  jar. Unpack `com.almworks.sqlite4java` natives with `maven-dependency-plugin` and set the property
  from surefire.
- `ServerRunner.createServerFromCommandLineArgs` is in
  **`com.amazonaws.services.dynamodbv2.local.main`**, while `DynamoDBProxyServer` is in
  `...local.server`. Guessing that both live in `.server` produces code that will not compile.
- **There is no `linux-aarch64` native.** On ARM Linux DynamoDB Local simply cannot start — no
  workaround short of an x86-64 runner. Plan CI accordingly if your developers are on ARM.
- `-inMemory -sharedDb` is the combination you usually want: isolation per run, and one database
  regardless of which credentials or region a client presents.

### The Amazon Software Licence is a distribution hazard, not a test hazard

DynamoDB Local ships under the ASL — not OSI-approved, and carrying a field-of-use restriction.
Depending on it is fine; **distributing it is not**. A test-support module that declares it at
`compile` scope leaks it into every consumer's transitive closure.

Use `provided` where your own code must compile against it, `test` everywhere else, and enforce it:
a `maven-enforcer` banned-dependencies rule on compile and runtime scope catches what review misses —
in one real case, a transitive pull through an unrelated Apache-licensed test utility.

### Assert that an "empty" plan issues no call

Where your query layer can prove a predicate unsatisfiable, the correct number of DynamoDB requests
is **zero**. Assert it against a counting client wrapper rather than trusting it: an implementation
that sends the query anyway and returns nothing looks identical from the outside, right up until the
bill arrives.

The same wrapper is the only way to tell a batched fetch from an N+1 — comparing returned data cannot
distinguish them, and on DynamoDB every avoidable round trip is a billed request.
