# Reladynamo Java Configuration & Bootstrap API — Design Document

**Agent:** `javaconfig`  
**Status:** design (not implementation)  
**Baseline:** Java 11 · Reladomo 18.1.0 · AWS SDK v2 · DynamoDBLocal 2.5.3 in-process  
**Verification:** all Reladomo binding claims checked with `javap` / sources against  
`reladomo-18.1.0.jar` (+ `reladomo-18.1.0-sources.jar`, `bitemporal-bank` generated classes).

---

## 0. Purpose and non-goals

### Purpose

Define the **Java-side configuration surface** (SDK-style fluent builders) and the **runtime bootstrap** that binds Reladomo portals to a DynamoDB persister instead of JDBC `*DatabaseObject` implementations — without changing user `*MithraObject.xml` or generated finder/list/abstract classes.

### Non-goals

- Designing the DynamoDB key grammar / GSI matrix (owned by schema agent).
- Designing the JSON item codec (owned by codec agent).
- Reimplementing bitemporal directors (forbidden; live above the persister).
- Forking Reladomo or inventing a new `DatabaseType`.

### Architecture constraints (already decided)

| Constraint | Implication for this design |
|---|---|
| Seam = `MithraObjectReader` / `MithraObjectPersister` / `MithraDatedObjectPersister` | Config must produce enough metadata to construct those, then bind them onto portals |
| Bitemporal logic stays in `TemporalDirector` | Config never expresses “terminate semantics”; only table/key/client/index |
| Generic = XML-driven object model | Runtime/config differs; object XML does not |
| Java 11 floor | No records, sealed types, text blocks, `Stream.toList()` in library API |

---

## 1. Fluent configuration API

### 1.1 Design principles

1. **AWS SDK v2 look-and-feel** — nested builders, terminal `build()`, immutable results.
2. **One internal model** — XML parsers and fluent builders both emit `ReladynamoConfig` (immutable).
3. **Null-hostile** — builders reject null arguments immediately; `build()` validates completeness with actionable messages.
4. **Defensive copies** — every collection/map accessor returns an unmodifiable snapshot; constructors copy inputs.
5. **Java 11-safe idioms** — final classes, private final fields, static factories, classic `instanceof`.

### 1.2 Package layout (proposed)

```
io.reladynamo.config
  ReladynamoConfig                 // root immutable model
  ReladynamoConfigBuilder
  ObjectMapping                    // one Reladomo class → table/keys/indexes
  ObjectMappingBuilder
  TableSpec / TableSpecBuilder
  KeyStrategy / KeyStrategyBuilder
  GlobalSecondaryIndexSpec / ...
  ClientSpec / ClientSpecBuilder
  CacheMode                        // enum: FULL, PARTIAL, NONE
  ReladynamoConfigException        // unchecked config validation errors
  ReladynamoXmlLoader              // XML → builder → ReladynamoConfig
io.reladynamo.bootstrap
  ReladynamoBootstrap              // wires Reladomo + DynamoDbPersister
  ReladynamoRuntime                // AutoCloseable handle (client + portals)
  PortalBinder                     // setMithraObjectReader swap
  NoOpConnectionManager            // satisfies Reladomo LocalObjectConfig init
io.reladynamo.client
  DynamoClientFactory
  DynamoClientLifecycle
  DynamoDbLocalSupport             // test-only helper (optional module)
io.reladynamo.observe
  QueryExplainPlan
  ReladynamoMetrics
  ReladynamoValidator
io.reladynamo.error
  DynamoExceptionTranslator
```

**Rejected alternative:** putting builders inside `io.reladynamo.dynamodb` only — rejected because Spring Boot auto-config and plain-Java bootstrap both need a stable, dependency-light config module that does not force an HTTP client onto compile classpath of pure unit tests of the model.

### 1.3 Shared internal model (the single source of truth)

```java
package io.reladynamo.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable Reladynamo runtime configuration.
 * Produced by {@link ReladynamoConfigBuilder} or {@link ReladynamoXmlLoader}.
 */
public final class ReladynamoConfig {

    private final ClientSpec client;
    private final List<ObjectMapping> objects;
    private final boolean validateOnStart;
    private final String mithraRuntimeResource; // optional classpath MithraRuntime XML

    ReladynamoConfig(
            ClientSpec client,
            List<ObjectMapping> objects,
            boolean validateOnStart,
            String mithraRuntimeResource) {
        this.client = Objects.requireNonNull(client, "client");
        this.objects = Collections.unmodifiableList(new ArrayList<ObjectMapping>(
                Objects.requireNonNull(objects, "objects")));
        this.validateOnStart = validateOnStart;
        this.mithraRuntimeResource = mithraRuntimeResource; // nullable = bootstrap synthesizes
    }

    public ClientSpec getClient() {
        return client;
    }

    public List<ObjectMapping> getObjects() {
        return objects;
    }

    public boolean isValidateOnStart() {
        return validateOnStart;
    }

    /** @return classpath resource for MithraRuntime XML, or null if bootstrap should synthesize */
    public String getMithraRuntimeResource() {
        return mithraRuntimeResource;
    }

    public ObjectMapping requireObject(String className) {
        for (ObjectMapping mapping : objects) {
            if (mapping.getClassName().equals(className)) {
                return mapping;
            }
        }
        throw new ReladynamoConfigException(
                "No ObjectMapping for className='" + className
                        + "'. Known: " + knownClassNames());
    }

    private List<String> knownClassNames() {
        List<String> names = new ArrayList<String>(objects.size());
        for (ObjectMapping mapping : objects) {
            names.add(mapping.getClassName());
        }
        return names;
    }
}
```

```java
public final class ObjectMapping {
    private final String className;          // e.g. "com.acme.domain.Customer"
    private final TableSpec table;
    private final KeyStrategy keyStrategy;
    private final List<GlobalSecondaryIndexSpec> indexes;
    private final CacheMode cacheMode;
    private final Map<String, String> attributeNameCompression; // optional short names

    // constructor validates non-blank className, non-null table/keyStrategy,
    // defensive copies of indexes + compression map ...

    public String getClassName() { return className; }
    public TableSpec getTable() { return table; }
    public KeyStrategy getKeyStrategy() { return keyStrategy; }
    public List<GlobalSecondaryIndexSpec> getIndexes() { return indexes; }
    public CacheMode getCacheMode() { return cacheMode; }
    public Map<String, String> getAttributeNameCompression() { return attributeNameCompression; }
}
```

```java
public final class TableSpec {
    private final String tableName;
    private final BillingMode billingMode; // ON_DEMAND or PROVISIONED
    private final Long readCapacityUnits;  // required iff PROVISIONED
    private final Long writeCapacityUnits;

    // getters ...
}

public final class KeyStrategy {
    private final String partitionKeyAttribute; // Dynamo attribute name, e.g. "PK"
    private final String sortKeyAttribute;      // e.g. "SK"
    private final String partitionKeyTemplate;  // e.g. "CUSTOMER#{customerId}"
    private final String sortKeyTemplate;       // e.g. "{processingDateFrom}#{businessDateFrom}"
    private final String infinityToken;         // lexicographic max sentinel for open thru

    // getters ...
}

public final class GlobalSecondaryIndexSpec {
    private final String indexName;
    private final String partitionKeyAttribute;
    private final String sortKeyAttribute;     // nullable
    private final String partitionKeyTemplate;
    private final String sortKeyTemplate;      // nullable
    private final ProjectionType projection;   // KEYS_ONLY, INCLUDE, ALL
    private final List<String> projectedAttributes; // for INCLUDE

    // getters ...
}

public final class ClientSpec {
    private final String region;                 // nullable → default provider chain
    private final String endpointOverride;       // nullable; used for Local / VPC endpoints
    private final CredentialsMode credentialsMode; // DEFAULT_CHAIN, STATIC, PROFILE, ANONYMOUS_LOCAL
    private final String profileName;            // for PROFILE
    private final Integer apiCallTimeoutMillis;
    private final Integer apiCallAttemptTimeoutMillis;
    private final Integer maxRetries;            // SDK retry; layered under Reladomo tx retry
    private final HttpClientType httpClientType; // URL_CONNECTION (Java 11 default) or APACHE / NETTY optional
    private final boolean ownedByReladynamo;     // if true, bootstrap closes client on shutdown
    // Optional prebuilt client is NOT stored here — injected at bootstrap time (see §3)
}
```

Enums are ordinary Java 11 enums (`CacheMode`, `BillingMode`, `ProjectionType`, `CredentialsMode`, `HttpClientType`).

### 1.4 Fluent builder — full worked example

Bitemporal `Customer` with table, composite key strategy, email GSI, and shared client:

```java
import io.reladynamo.config.BillingMode;
import io.reladynamo.config.CacheMode;
import io.reladynamo.config.CredentialsMode;
import io.reladynamo.config.HttpClientType;
import io.reladynamo.config.ProjectionType;
import io.reladynamo.config.ReladynamoConfig;

public final class CustomerReladynamoExample {

    public static ReladynamoConfig create() {
        return ReladynamoConfig.builder()
                .validateOnStart(true)
                .client(ReladynamoConfig.clientBuilder()
                        .region("us-west-2")
                        .credentialsMode(CredentialsMode.DEFAULT_CHAIN)
                        .apiCallTimeoutMillis(10_000)
                        .apiCallAttemptTimeoutMillis(3_000)
                        .maxRetries(3)
                        .httpClientType(HttpClientType.URL_CONNECTION)
                        .ownedByReladynamo(true)
                        .build())
                .addObject(ReladynamoConfig.objectBuilder()
                        .className("com.acme.domain.Customer")
                        .cacheMode(CacheMode.PARTIAL)
                        .table(ReladynamoConfig.tableBuilder()
                                .tableName("Reladynamo_Customer")
                                .billingMode(BillingMode.ON_DEMAND)
                                .build())
                        .keyStrategy(ReladynamoConfig.keyStrategyBuilder()
                                .partitionKeyAttribute("PK")
                                .sortKeyAttribute("SK")
                                .partitionKeyTemplate("CUSTOMER#{customerId}")
                                .sortKeyTemplate("{processingDateFrom}#{businessDateFrom}")
                                .infinityToken("9999-12-01T23:59:00.000Z")
                                .build())
                        .addGlobalSecondaryIndex(ReladynamoConfig.gsiBuilder()
                                .indexName("GsiEmail")
                                .partitionKeyAttribute("GSI1PK")
                                .sortKeyAttribute("GSI1SK")
                                .partitionKeyTemplate("EMAIL#{email}")
                                .sortKeyTemplate("CUSTOMER#{customerId}")
                                .projection(ProjectionType.INCLUDE)
                                .addProjectedAttribute("firstName")
                                .addProjectedAttribute("lastName")
                                .build())
                        .putAttributeCompression("customerId", "cid")
                        .putAttributeCompression("email", "em")
                        .build())
                .build();
    }

    private CustomerReladynamoExample() {}
}
```

Equivalent conceptual XML (`reladynamo.xml`) — schema owned by schema agent; shown here only to prove parity:

```xml
<reladynamo validateOnStart="true">
  <client region="us-west-2" credentialsMode="DEFAULT_CHAIN"
          apiCallTimeoutMillis="10000" apiCallAttemptTimeoutMillis="3000"
          maxRetries="3" httpClientType="URL_CONNECTION" ownedByReladynamo="true"/>
  <object className="com.acme.domain.Customer" cacheMode="PARTIAL">
    <table name="Reladynamo_Customer" billingMode="ON_DEMAND"/>
    <keyStrategy partitionKeyAttribute="PK" sortKeyAttribute="SK"
                 partitionKeyTemplate="CUSTOMER#{customerId}"
                 sortKeyTemplate="{processingDateFrom}#{businessDateFrom}"
                 infinityToken="9999-12-01T23:59:00.000Z"/>
    <gsi name="GsiEmail" partitionKeyAttribute="GSI1PK" sortKeyAttribute="GSI1SK"
         partitionKeyTemplate="EMAIL#{email}" sortKeyTemplate="CUSTOMER#{customerId}"
         projection="INCLUDE">
      <project attribute="firstName"/>
      <project attribute="lastName"/>
    </gsi>
    <compress from="customerId" to="cid"/>
    <compress from="email" to="em"/>
  </object>
</reladynamo>
```

### 1.5 XML is just a front-end onto the builder

**Explicit contract:** `ReladynamoXmlLoader` must not construct `ReladynamoConfig` fields by hand. It parses XML into the **same** builders the programmatic API uses, then calls `build()`.

```java
package io.reladynamo.config;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;

public final class ReladynamoXmlLoader {

    public ReladynamoConfig load(InputStream xml) {
        Objects.requireNonNull(xml, "xml");
        try {
            XMLInputFactory factory = XMLInputFactory.newFactory();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
            XMLStreamReader reader = factory.createXMLStreamReader(xml);
            ReladynamoConfigBuilder root = ReladynamoConfig.builder();
            // ... walk elements, call root.client(...), root.addObject(...), etc.
            // Never: new ReladynamoConfig(...fields...)
            return root.build();
        } catch (ReladynamoConfigException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ReladynamoConfigException(
                    "Failed to parse reladynamo.xml: " + ex.getMessage(), ex);
        }
    }
}
```

**Rationale:** one validation path (`build()`), one equality/hashCode surface for tests, zero XML-vs-builder drift.  
**Rejected alternative:** separate `ReladynamoConfigFactory.fromXml` that mutates package-private fields — rejected because it duplicates validation and lets XML invent states the builder cannot express.

### 1.6 Null-hostile validation at `build()`

Rules enforced by `ReladynamoConfigBuilder.build()` (and nested builders):

| Check | Message shape |
|---|---|
| Zero objects | `ReladynamoConfig must declare at least one <object>/ObjectMapping` |
| Duplicate `className` | `Duplicate ObjectMapping for className='X' (indexes 0 and 2)` |
| Blank table name | `ObjectMapping 'com.acme.Customer': tableName must be non-blank` |
| Missing PK/SK attrs | `ObjectMapping '…': keyStrategy requires partitionKeyAttribute and sortKeyAttribute` |
| Blank templates | `ObjectMapping '…': partitionKeyTemplate must be non-blank` |
| PROVISIONED without RCU/WCU | `Table 'T': billingMode=PROVISIONED requires readCapacityUnits and writeCapacityUnits > 0` |
| INCLUDE projection with empty list | `GSI 'GsiEmail': projection=INCLUDE requires at least one projected attribute` |
| PROFILE without profileName | `ClientSpec: credentialsMode=PROFILE requires profileName` |
| Negative timeouts / retries | `ClientSpec: maxRetries must be >= 0 (was -1)` |

`ReladynamoConfigException` extends `IllegalStateException` (config-time, not Reladomo runtime). It is **not** a `MithraDatabaseException` — misconfiguration must fail before traffic.

Builder setters:

```java
public ReladynamoConfigBuilder addObject(ObjectMapping mapping) {
    objects.add(Objects.requireNonNull(mapping, "mapping"));
    return this;
}
```

### 1.7 Immutability and equality

- All model classes are `final`, fields `private final`.
- `equals`/`hashCode` cover every field used by runtime (enables assertability without AWS — §7).
- `toString` is structured and free of secrets (never print static credentials if we ever add them; prefer profile/chain).

### 1.8 What this API deliberately does **not** configure

| Concern | Where it lives instead |
|---|---|
| Reladomo attribute types / as-of columns | Existing `*MithraObject.xml` + generated Finder |
| Bitemporal mutation policy | `TemporalDirector` |
| Query planning Operation → Query/Scan | Persister / planner module |
| Item codec Timestamp precision | Codec module (consumes `ObjectMapping` compression map) |

---

## 2. Runtime bootstrap and portal binding — the crux

### 2.1 What `readConfiguration` does today (verified)

Call chain from Reladomo 18.1.0 sources / bytecode:

```
MithraManagerProvider.getMithraManager()
  .readConfiguration(InputStream)
      → MithraConfigurationManager.parseConfiguration(is)
            → MithraRuntimeUnmarshaller.parse → MithraRuntimeType
      → MithraConfigurationManager.initializeRuntime(MithraRuntimeType)
            → lazyInitObjectsWithCallback(...)
                 → lazyInitLocalObjects   // ConnectionManager + *DatabaseObject
                 → lazyInitRemoteObjects
                 → lazyInitPureObjects    // PureObjects + *ObjectFactory
                 → lazyInitReplicatedObjects
            → then initialize portals / optionally load full caches
```

`MithraManager.readConfiguration` delegates to `configManager.readConfiguration(is)`.  
`MithraConfigurationManager.readConfiguration` is the two-liner: `parseConfiguration(is)` then `initializeRuntime(type)`.

Local (JDBC) object init (`LocalObjectConfig.initializeObject`), verified from sources:

1. `instantiateDatabaseObject(className)` → concatenates literal `"DatabaseObject"` (bytecode `ldc #232`) then `Class.forName` + `newInstance` + cast to `MithraDatabaseObject`
2. `setConnectionManager(...)`, schema, optional load-operation provider
3. `PostInitializeHook.callbackAfterDatabaseObjectInitialize(...)` if present
4. `instantiateFullCache` or `instantiatePartialCache` on the DatabaseObject
5. Generated Finder constructs the portal (step below)
6. `PostInitializeHook.callbackAfterInitialize(className, portal, ...)`

Generated Finder portal construction — **verified** against `bitemporalbank.domain.CustomerFinder.initializePortal` in `bitemporal-bank-16.0.0-SNAPSHOT.jar`:

```text
new MithraTransactionalPortal(...)
aload_0                          // deserializer = CustomerDatabaseObject
...
checkcast MithraObjectPersister  // DBO is the persister
invokespecial MithraTransactionalPortal.<init>(..., MithraObjectPersister)
```

`MithraTransactionalPortal` constructor (sources + bytecode) then dual-wires the **same** instance:

```java
super(..., mithraObjectPersister,
      (MithraTuplePersister) mithraObjectPersister,  // same DBO as tuple persister
      true);
```

So at construction: `mithraObjectReader == mithraTuplePersister == CustomerDatabaseObject`.  
After Strategy A’s `setMithraObjectReader(dynamoPersister)`: **only** the reader/persister field moves; the tuple field still points at the JDBC DBO.

Pure object init (`PureObjectConfig.initializeObject`), verified:

1. `instantiateDeserializer` → concatenates literal `"ObjectFactory"` (bytecode `ldc #235`)
2. Cast to `MithraPureObjectFactory`, `setFactoryParameter`
3. `instantiateFullCache` (always full for pure)
4. Portal `setPureHome(true, …)`, `PURE_PERSISTER_ID` (constant `0`)

`PureMithraObjectPersister` (verified via `javap -public`): implements `MithraObjectPersister` + `MithraDatedObjectPersister` + `MithraTuplePersister`; write methods are empty; `loadFullCache` / `reloadFullCache` cast the portal deserializer to `MithraPureObjectFactory`. **Structural precedent for a non-JDBC SPI implementation — not durable storage.**

Remote analogue (same `CustomerFinder`): when config is remote, Finder constructs `new RemoteMithraObjectPersister(...)` and passes that instead of the DBO — proving Reladomo already supports a non-DBO persister instance at portal construction time. Strategy A achieves the same end-state **after** construction via the public setter.

Portal reader/persister field (verified from `MithraAbstractObjectPortal` sources):

```java
public MithraObjectPersister getMithraObjectPersister() {
    return (MithraObjectPersister) this.mithraObjectReader;
}

public void setMithraObjectReader(MithraObjectReader mithraObjectReader) {
    this.mithraObjectReader = mithraObjectReader;
}

public MithraDatabaseObject getDatabaseObject() {
    return (MithraDatabaseObject) objectFactory; // deserializer/DBO — NOT swapped by setMithraObjectReader
}
```

There is **no public setter** for `mithraTuplePersister` (assigned only in `MithraAbstractObjectPortal` constructor; nulled in `destroy()`).

### 2.2 Binding strategy enumeration

| # | Strategy | How it works | Pros | Cons | Viable? |
|---|---|---|---|---|---|
| A | **Post-init swap via `setMithraObjectReader`** | Run normal MithraRuntime (LocalObjectConfig) with a no-op JDBC connection manager; after portals exist, replace reader with `DynamoDbPersister` | No Reladomo fork; works with existing `*DatabaseObject`; generic; uses public API | Tuple-persister field still points at JDBC DatabaseObject unless addressed; `getDatabaseObject()` still returns JDBC DBO | **Yes — primary** |
| B | Pure-object factory route | Configure `<PureObjects>`; supply `*ObjectFactory` that installs `DynamoDbPersister` | Avoids JDBC connection manager | Requires `className + "ObjectFactory"` generation (`MithraPureObject` generator path). Existing transactional `*MithraObject.xml` produce `*DatabaseObject`, not `*ObjectFactory` (verified: `instantiateDeserializer` vs `instantiateDatabaseObject`). Changing generation violates “XML must not change” | **No for generic path** |
| C | Subclass generated `*DatabaseObject` | Hand-write each `CustomerDatabaseObject` to delegate SPI to shared Dynamo code | Tuple + reader same instance from construction; Reladomo discovers via `className + "DatabaseObject"` | Per-entity boilerplate; regeneration risk; not “XML-only generic” | **Fallback** |
| D | Custom `MithraObjectPortal` subclass | Replace portal type entirely | Full control | Finder.initializePortal hard-codes `MithraTransactionalPortal` / `MithraReadOnlyPortal` (verified in bitemporal-bank). Would require bytecode rewrite or forked generator | **No** |
| E | Custom MithraRuntime config type | Add `<DynamoObjects>` beside `<ConnectionManager>` / `<PureObjects>` | Clean XML story | Reladomo unmarshaller schema is closed; no extension SPI for new runtime element types in 18.1.0 | **No without fork** |
| F | PostInitializeHook only (no later swap) | Hook runs during init — but portal already constructed with JDBC DBO as persister **before** `callbackAfterInitialize` | Timing is right for swap inside the hook | Same as A; hook is private to `lazyInitObjectsWithCallback` unless we call `zLazyInitObjectsWithCallback` / package APIs | Partial — use after `readConfiguration` instead |

### 2.3 Recommendation

**Primary: Strategy A — Local MithraRuntime init + `setMithraObjectReader` swap.**  
**Confidence: High (≈85%)** for the read/write persister path, based on:

- Generated `CustomerFinder.initializePortal` casting the DatabaseObject to `MithraObjectPersister`
- Public `setMithraObjectReader` mutating the exact field `getMithraObjectPersister()` reads
- Successful pattern analogue: remote client portals construct with `RemoteMithraObjectPersister` instead of the DBO

**Confidence: Medium (≈55%)** that leaving `mithraTuplePersister` as the JDBC DatabaseObject is safe for normal dated transactional workloads that do not use Reladomo temp-tuple / analytic temp tables.

**Fallback if primary fails (tuple path or getDatabaseObject SQL leakage):** Strategy C — thin hand-written `*DatabaseObject` subclasses that extend the generated abstract class and implement persistence by delegation to one shared `DynamoDbPersister`. Confidence for wiring: **High (≈90%)**. Cost: one empty-ish class per entity (or a tiny codegen step Reladynamo owns, not Reladomo’s).

**Kill criterion (from project plan):** if neither A nor C can bind without forking Reladomo, stop and re-plan.

### 2.4 Exact bootstrap sequence (primary)

```text
1. Build ReladynamoConfig (fluent or XML → builder → build)
2. Optionally config.validate() / ReladynamoValidator.validate(config, client)  // §5
3. Construct DynamoDbClient from ClientSpec (or accept injected client)         // §3
4. For each ObjectMapping, construct DynamoDbPersister(mapping, client, codec, planner)
5. Ensure MithraRuntime is initialized:
   a. If config.mithraRuntimeResource != null:
        MithraManagerProvider.getMithraManager()
            .readConfiguration(openClasspath(resource));
   b. Else synthesize MithraRuntimeType / XML stream:
        - one ConnectionManager pointing at NoOpConnectionManager
        - MithraObjectConfiguration per ObjectMapping.className
        - cache type mapped from CacheMode
        - then initializeRuntime(type)  OR  readConfiguration(synthesizedStream)
6. PortalBinder.bindAll(config):
     for each ObjectMapping:
       portal = Class.forName(className + "Finder")
                    .getMethod("getMithraObjectPortal").invoke(null)
       // or MithraManager.initializePortal(className) if lazy
       cast to MithraAbstractObjectPortal (runtime check)
       portal.setMithraObjectReader(persisterFor(className))
       assert portal.getMithraObjectPersister() == persister
7. If validateOnStart: run table/GSI existence checks
8. Return ReladynamoRuntime (AutoCloseable) holding client lifecycle + config
```

Synthesized MithraRuntime sketch (logical):

```xml
<MithraRuntime>
  <ConnectionManager className="io.reladynamo.bootstrap.NoOpConnectionManager">
    <MithraObjectConfiguration className="com.acme.domain.Customer" cacheType="partial"/>
  </ConnectionManager>
</MithraRuntime>
```

`NoOpConnectionManager` implements `SourcelessConnectionManager` (verified interface):

```java
public final class NoOpConnectionManager implements SourcelessConnectionManager {
    public static final NoOpConnectionManager INSTANCE = new NoOpConnectionManager();

    public BulkLoader createBulkLoader() {
        throw new MithraBusinessException(
                "Reladynamo NoOpConnectionManager does not support JDBC bulk load");
    }
    public Connection getConnection() {
        throw new MithraBusinessException(
                "Reladynamo NoOpConnectionManager does not provide JDBC connections; "
                + "portal must be bound to DynamoDbPersister via ReladynamoBootstrap");
    }
    public DatabaseType getDatabaseType() { /* return a harmless stub or H2 type only if required at init */ 
        throw new MithraBusinessException("No JDBC DatabaseType under Reladynamo");
    }
    public TimeZone getDatabaseTimeZone() { return TimeZone.getTimeZone("UTC"); }
    public String getDatabaseIdentifier() { return "reladynamo-noop"; }
}
```

**OPEN QUESTION:** Does `instantiatePartialCache` / portal construction invoke `getDatabaseType()` or `getConnection()` on the connection manager during init (before any finder call)? If yes, `NoOpConnectionManager` must return a non-throwing stub `DatabaseType` instead of throwing. This cannot be answered from `MithraConfigurationManager` alone; it requires a walking-skeleton spike against a generated dated transactional object. **Do not invent the answer — spike it.**

### 2.5 PortalBinder sketch

```java
package io.reladynamo.bootstrap;

import com.gs.fw.common.mithra.MithraObjectPortal;
import com.gs.fw.common.mithra.portal.MithraAbstractObjectPortal;
import com.gs.fw.common.mithra.portal.MithraObjectReader;
import com.gs.fw.common.mithra.transaction.MithraObjectPersister;

public final class PortalBinder {

    public void bind(MithraObjectPortal portal, MithraObjectPersister persister) {
        Objects.requireNonNull(portal, "portal");
        Objects.requireNonNull(persister, "persister");
        if (!(persister instanceof MithraObjectReader)) {
            throw new IllegalArgumentException(
                    "persister must implement MithraObjectReader; got "
                            + persister.getClass().getName());
        }
        if (!(portal instanceof MithraAbstractObjectPortal)) {
            throw new IllegalStateException(
                    "Expected MithraAbstractObjectPortal, got "
                            + portal.getClass().getName());
        }
        MithraAbstractObjectPortal abstractPortal = (MithraAbstractObjectPortal) portal;
        abstractPortal.setMithraObjectReader((MithraObjectReader) persister);
        MithraObjectPersister bound = abstractPortal.getMithraObjectPersister();
        if (bound != persister) {
            throw new IllegalStateException(
                    "setMithraObjectReader did not stick for portal "
                            + portal.getBusinessClassName());
        }
    }
}
```

### 2.6 Tuple-persister gap — mitigation plan

Because `setMithraObjectReader` does not update `mithraTuplePersister`, and because `MithraTransactionalPortal` initially set both fields from the same DBO:

1. **Design requirement on `DynamoDbPersister`:** like `PureMithraObjectPersister`, implement `MithraTuplePersister` as well as `MithraDatedObjectPersister`. That does not by itself fix Strategy A (the portal still holds the old tuple reference), but it makes Fallback C and any future dual-set path compile-clean.
2. **Spike:** exercise deep-fetch / `in` / set-based operations that create `TupleTempContext` under Dynamo binding; observe whether JDBC DBO `insertTuples*` / `destroyTempContext` are invoked.
3. If invoked and fail: **Fallback C** (DBO subclass that *is* the Dynamo persister from construction — both fields correct), or **controlled reflection** to assign the private `mithraTuplePersister` field (last resort; pin Reladomo 18.1.0; add a test that fails if the field is renamed). Prefer Fallback C over reflection for production.
4. Note: `getTableNameForQuery` on the portal always delegates to `getDatabaseObject()` (the DBO / `objectFactory`). That path is SQL-shaped. Dynamo query planning must not go through it; if Reladomo invokes it after bind, Strategy A is insufficient for that call site.

**OPEN QUESTION:** In 18.1.0 dated transactional find paths used by conformance (findOne/findMany/insert/update/terminate), does the portal ever call `getMithraTuplePersister()` or `getTableNameForQuery`? Jar inspection shows the fields/methods exist and are wired for JDBC; whether the conformance API surface reaches them under partial-cache dated use is **not** determined without a walking-skeleton run.

### 2.7 Bootstrap and runtime handle (Java 11 sketch)

```java
package io.reladynamo.bootstrap;

import io.reladynamo.config.ReladynamoConfig;
import io.reladynamo.config.ObjectMapping;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ReladynamoBootstrap {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private ReladynamoConfig config;
        private DynamoDbClient dynamoDbClient; // optional inject
        private boolean ownClient = true;

        public Builder config(ReladynamoConfig config) {
            this.config = Objects.requireNonNull(config, "config");
            return this;
        }

        public Builder dynamoDbClient(DynamoDbClient client) {
            this.dynamoDbClient = Objects.requireNonNull(client, "client");
            this.ownClient = false;
            return this;
        }

        public ReladynamoRuntime start() {
            Objects.requireNonNull(config, "config");
            DynamoDbClient client = dynamoDbClient != null
                    ? dynamoDbClient
                    : new DynamoClientFactory().create(config.getClient());
            boolean closeClient = ownClient && config.getClient().isOwnedByReladynamo();

            // 1) MithraRuntime init (user resource or synthesized) — §2.4
            // 2) Build DynamoDbPersister per ObjectMapping
            // 3) PortalBinder.bindAll
            // 4) optional validateOnStart
            Map<String, /*DynamoDbPersister*/ Object> persisters =
                    new LinkedHashMap<String, Object>();
            // ... populate and bind ...

            return new ReladynamoRuntime(config, client, closeClient,
                    Collections.unmodifiableMap(persisters));
        }
    }
}

public final class ReladynamoRuntime implements AutoCloseable {
    private final ReladynamoConfig config;
    private final DynamoDbClient client;
    private final boolean closeClient;
    private final Map<String, Object> persistersByClass;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    ReladynamoRuntime(ReladynamoConfig config, DynamoDbClient client,
                      boolean closeClient, Map<String, Object> persistersByClass) {
        this.config = config;
        this.client = client;
        this.closeClient = closeClient;
        this.persistersByClass = persistersByClass;
    }

    public ReladynamoConfig getConfig() { return config; }

    public DynamoDbClient getClient() { return client; }

    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (closeClient) {
            client.close();
        }
    }
}
```

**Rejected alternative:** making `start()` mutate a global singleton without returning a handle — rejected because tests cannot isolate client shutdown and Spring cannot own lifecycle cleanly.

### 2.8 What we will not do

- Call `DatabaseType` / invent SQL dialects as the integration seam.
- Re-derive bitemporal splits in the binder or persister.
- Require users to switch object XML to `MithraPureObject`.
- Fork Reladomo or add a custom MithraRuntime element type in v1.

---

## 3. Client lifecycle and resources

### 3.1 Construction

```java
public final class DynamoClientFactory {

    public DynamoDbClient create(ClientSpec spec) {
        Objects.requireNonNull(spec, "spec");
        DynamoDbClientBuilder builder = DynamoDbClient.builder();

        if (spec.getRegion() != null) {
            builder.region(Region.of(spec.getRegion()));
        }
        // else: DefaultAwsRegionProviderChain

        builder.credentialsProvider(credentialsProvider(spec));

        if (spec.getEndpointOverride() != null) {
            builder.endpointOverride(URI.create(spec.getEndpointOverride()));
        }

        ClientOverrideConfiguration.Builder overrides = ClientOverrideConfiguration.builder();
        if (spec.getApiCallTimeoutMillis() != null) {
            overrides.apiCallTimeout(Duration.ofMillis(spec.getApiCallTimeoutMillis().longValue()));
        }
        if (spec.getApiCallAttemptTimeoutMillis() != null) {
            overrides.apiCallAttemptTimeout(
                    Duration.ofMillis(spec.getApiCallAttemptTimeoutMillis().longValue()));
        }
        if (spec.getMaxRetries() != null) {
            overrides.retryPolicy(RetryPolicy.builder()
                    .numRetries(spec.getMaxRetries().intValue())
                    .build());
            // Prefer RetryMode.ADAPTIVE when available on the pinned SDK version;
            // keep explicit numRetries for determinism in tests.
        }
        builder.overrideConfiguration(overrides.build());

        builder.httpClientBuilder(httpClientBuilder(spec.getHttpClientType()));
        return builder.build();
    }
}
```

**Default HTTP client:** `UrlConnectionHttpClient` (Java 11-safe, no extra native deps).  
**Rejected default:** Netty async client — rejected as baseline because it pulls netty and complicates Java 11 test classpaths; offer as opt-in via `HttpClientType.NETTY` for high-concurrency deployments.

### 3.2 Sharing and ownership

| Mode | Behavior |
|---|---|
| `ownedByReladynamo=true` (default for factory-built clients) | `ReladynamoRuntime.close()` calls `client.close()` |
| Injected client via `ReladynamoBootstrap.builder().dynamoDbClient(existing)` | Reladynamo never closes it; `ownedByReladynamo=false` forced |
| Spring bean | Spring owns lifecycle; auto-config sets owned=false |

One `DynamoDbClient` is shared across all `ObjectMapping`s in a `ReladynamoConfig` unless a future per-object override is added (not in v1 — YAGNI).

### 3.3 Credentials and region

Resolution order mirrors AWS SDK v2:

1. Explicit `ClientSpec.region` / credentials mode  
2. Else default provider chains (`DefaultAwsRegionProviderChain`, `DefaultCredentialsProvider`)

Modes:

- `DEFAULT_CHAIN` — production default  
- `PROFILE` — `ProfileCredentialsProvider.create(profileName)`  
- `ANONYMOUS_LOCAL` — for DynamoDBLocal (dummy credentials)  
- `STATIC` — **test-only**; builders accept `AwsBasicCredentials` via bootstrap override, not via XML (secrets must not live in XML)

### 3.4 Retry, timeouts, and interaction with Reladomo

Two layers:

1. **SDK retries** (`ClientSpec.maxRetries`) — handle throttling / transient HTTP at the wire.  
2. **Reladomo transaction retries** — `executeTransactionalCommand(cmd, retryCount)` / `TransactionStyle` (`getRetries()`, `getTimeout()`, `isRetriableAfterTimeout()`), driven by `MithraBusinessException.isRetriable()`.

Design rule: translator sets `setRetriable(true)` only for **idempotent-safe** transient failures after SDK retries are exhausted (or for errors SDK did not retry). Avoid double-amplification: keep SDK retries modest (e.g. 3) and let Reladomo own business-level command re-execution.

### 3.5 DynamoDBLocal in-process (no Docker)

Verified API on DynamoDBLocal 2.5.3:

```java
AmazonDynamoDBLocal embedded = DynamoDBEmbedded.create();
DynamoDbClient client = embedded.dynamoDbClient(); // SDK v2
// ... tests ...
embedded.shutdown();
```

Test helper:

```java
public final class DynamoDbLocalSupport implements AutoCloseable {
    private final AmazonDynamoDBLocal embedded;
    private final DynamoDbClient client;

    public static DynamoDbLocalSupport start() {
        AmazonDynamoDBLocal embedded = DynamoDBEmbedded.create();
        return new DynamoDbLocalSupport(embedded, embedded.dynamoDbClient());
    }

    public DynamoDbClient client() { return client; }

    public void close() {
        embedded.shutdown();
    }
}
```

Bootstrap for tests:

```java
ReladynamoRuntime runtime = ReladynamoBootstrap.builder()
        .config(config)
        .dynamoDbClient(local.client())  // injected → not closed by Reladynamo
        .start();
```

### 3.6 Thread-safety of the config graph

| Object | Thread-safe after publication? | Notes |
|---|---|---|
| `ReladynamoConfig` and nested specs | Yes | Immutable; safe to share |
| `ReladynamoConfigBuilder` | No | Single-threaded build |
| `DynamoDbClient` (SDK v2) | Yes | Documented thread-safe |
| `DynamoDbPersister` | Yes for stateless fields; tx staging is per-`MithraTransaction` | Must not store mutable request state in instance fields |
| `ReladynamoRuntime` | Publish-safe; close once | `close()` idempotent via `AtomicBoolean` |
| `NoOpConnectionManager` | Yes | Stateless singleton |
| Portal after bind | Reladomo’s rules | Bind **before** serving traffic; do not rebind concurrently |

---

## 4. Spring Boot and plain-Java integration

### 4.1 Module split

| Artifact | Dependencies | Role |
|---|---|---|
| `reladynamo-core` | reladomo, aws sdk dynamodb, slf4j | config model, bootstrap, persister SPI impl |
| `reladynamo-spring-boot-starter` | optional Spring Boot | auto-config only |
| `reladynamo-test-kit` | DynamoDBLocal, JUnit 5 | Local support + fixtures |

**Core must not depend on Spring.** Starter is optional.

### 4.2 Plain `main()` path

```java
public final class App {
    public static void main(String[] args) throws Exception {
        ReladynamoConfig config = ReladynamoConfig.builder()
                // ... as in §1.4
                .build();

        try (ReladynamoRuntime runtime = ReladynamoBootstrap.builder()
                .config(config)
                .start()) {
            // Finder APIs now hit DynamoDB
            // CustomerFinder.findOne(...);
        }
    }
}
```

Or XML:

```java
ReladynamoConfig config = new ReladynamoXmlLoader()
        .load(App.class.getResourceAsStream("/reladynamo.xml"));
```

### 4.3 Spring Boot auto-configuration shape

```java
@Configuration
@ConditionalOnClass(MithraManagerProvider.class)
@EnableConfigurationProperties(ReladynamoProperties.class)
public class ReladynamoAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(ReladynamoRuntime.class)
    public ReladynamoRuntime reladynamoRuntime(
            ReladynamoProperties properties,
            ObjectProvider<DynamoDbClient> clientProvider) {
        ReladynamoConfig config = properties.toConfig(); // properties → builders → build()
        ReladynamoBootstrap.Builder bootstrap = ReladynamoBootstrap.builder().config(config);
        DynamoDbClient existing = clientProvider.getIfAvailable();
        if (existing != null) {
            bootstrap.dynamoDbClient(existing);
        }
        return bootstrap.start();
    }
}
```

`application.yml` (illustrative):

```yaml
reladynamo:
  validate-on-start: true
  client:
    region: us-west-2
    max-retries: 3
  objects:
    - class-name: com.acme.domain.Customer
      cache-mode: PARTIAL
      table:
        name: Reladynamo_Customer
        billing-mode: ON_DEMAND
      key-strategy:
        partition-key-attribute: PK
        sort-key-attribute: SK
        partition-key-template: "CUSTOMER#{customerId}"
        sort-key-template: "{processingDateFrom}#{businessDateFrom}"
```

Properties binding must call the **same builders** (XML/properties/fluent → one model).

**Rejected alternative:** requiring Spring `DynamoDbClient` from Spring Cloud AWS as mandatory — rejected so plain Java and tests work without Spring.

---

## 5. Observability and diagnostics

### 5.1 Structured logging

Use SLF4J (Reladomo already does). Log markers / MDC keys:

| Key | Meaning |
|---|---|
| `reladynamo.class` | Reladomo business class |
| `reladynamo.table` | DynamoDB table |
| `reladynamo.op` | GetItem / Query / PutItem / TransactWriteItems / … |
| `reladynamo.index` | Index name or `PRIMARY` |
| `reladynamo.requestId` | AWS request id when present |

Info: bind success, validate success.  
Debug: explain plans.  
Warn: retries, throttling translated to retriable Mithra exceptions.  
Error: non-retriable translation with cause preserved.

### 5.2 Explain-plan surface

```java
public final class QueryExplainPlan {
    private final String className;
    private final String tableName;
    private final String indexName;          // or "PRIMARY"
    private final String accessMethod;       // GET_ITEM, QUERY, SCAN
    private final String keyConditionSummary;
    private final String filterExpressionSummary; // residual; may be null
    private final boolean consistentRead;
    private final Integer scannedCount;      // from response when available
    private final Integer returnedCount;
    private final Long capacityUnits;        // optional

    // getters, toString, equals/hashCode — Java 11 value class
}
```

API:

```java
public interface ExplainPlanListener {
    void onPlan(QueryExplainPlan plan);
}

// ReladynamoBootstrap.builder().explainPlanListener(...)
// or ThreadLocal capture for tests: ExplainPlanCapture.enter() / exit()
```

Semantics: residual filters are **post-read** (DynamoDB architect rule) — explain plan must show scanned vs returned so users do not mistake filters for key design.

### 5.3 Metrics

Minimal counters/timers (Micrometer optional via interface; no hard dependency in core):

- `reladynamo.ddb.ops` (tags: op, table, outcome)
- `reladynamo.ddb.retries`
- `reladynamo.ddb.latency`
- `reladynamo.bind.success` / `reladynamo.validate.failure`

Provide `ReladynamoMetricsSink` SPI with a no-op default and a Micrometer adapter in an optional module.

### 5.4 `validate()` entry point

```java
public final class ReladynamoValidator {

    public ValidationReport validate(ReladynamoConfig config, DynamoDbClient client) {
        List<String> errors = new ArrayList<String>();
        List<String> warnings = new ArrayList<String>();

        // 1. Config graph already validated at build(); re-check referential bits
        // 2. For each ObjectMapping:
        //    DescribeTable → table exists
        //    KeySchema attribute names match KeyStrategy attribute names
        //    Each GSI name exists; key attrs match; projection sufficient for INCLUDE list
        // 3. Class.forName(className), Class.forName(className + "Finder"),
        //    Class.forName(className + "DatabaseObject") — fail fast if generation missing
        // 4. Warn if cacheMode=FULL on unbounded dated table

        return new ValidationReport(errors, warnings);
    }
}
```

Callable from:

- `ReladynamoBootstrap` when `validateOnStart=true`
- CI main: `ReladynamoValidateMain`
- Tests against DynamoDBLocal after `CreateTable`

Failures throw `ReladynamoConfigException` summarizing all errors (not only the first).

---

## 6. Error taxonomy

### 6.1 Reladomo exception contract (verified)

```
MithraException (RuntimeException)
  └── MithraBusinessException          // isRetriable, isTimedOut, ifRetriableWaitElseThrow
        ├── MithraDatabaseException
        │     └── MithraUniqueIndexViolationException
        ├── MithraConfigurationException
        ├── MithraTransactionException
        │     └── MithraOptimisticLockException
        └── …
```

Callers expect **Reladomo** types from finder/ persister failures, not AWS types.

### 6.2 Translation table

| AWS / SDK condition | Reladomo type | `setRetriable` | Notes |
|---|---|---|---|
| `ProvisionedThroughputExceededException` | `MithraDatabaseException` | **true** | After SDK retries exhausted |
| `RequestLimitExceededException` | `MithraDatabaseException` | **true** | Account throttling |
| `InternalServerErrorException` | `MithraDatabaseException` | **true** | Transient service |
| `TransactionConflictException` | `MithraDatabaseException` | **true** | Concurrent tx |
| `SdkServiceException.isThrottlingException()` | `MithraDatabaseException` | **true** | Catch-all throttle |
| `ConditionalCheckFailedException` (optimistic / existence) | `MithraOptimisticLockException` or `MithraUniqueIndexViolationException` | **false** (usually) | Map by Reladynamo condition intent |
| `ConditionalCheckFailedException` (duplicate insert) | `MithraUniqueIndexViolationException` | **false** | |
| `ResourceNotFoundException` / `TableNotFoundException` | `MithraDatabaseException` | **false** | Config/ops error |
| `TransactionCanceledException` | `MithraDatabaseException` (inspect cancellation reasons) | depends | Reason `TransactionConflict` → retriable; `ConditionalCheckFailed` → not |
| `SdkClientException` (timeouts, IO) | `MithraDatabaseException` + `setTimedOut(true)` when timeout | **true** if retryable network | Align with `TransactionStyle.isRetriableAfterTimeout` |
| Unchecked mapping/codec bugs | `MithraBusinessException` or ISE | **false** | Programming errors |

Translator API:

```java
public final class DynamoExceptionTranslator {

    public MithraDatabaseException translate(String action, String table, RuntimeException ex) {
        // never return AWS type; always wrap with action + table + request id if present
        // preserve cause via constructor (message, nestedException)
    }
}
```

### 6.3 Retry interaction with Reladomo transactions

Verified: `MithraBusinessException.ifRetriableWaitElseThrow` decrements retries and calls `MithraManager.sleepBeforeTransactionRetry()` when `isRetriable()`.

Portal find paths catch `MithraBusinessException` and retry using that helper (seen in portal bytecode). Transactional commands use `TransactionStyle.getRetries()`.

**Rules for Reladynamo:**

1. SDK retries first (narrow, fast).  
2. If still failing with throttle/conflict → translate with `setRetriable(true)` so Reladomo can re-run the **whole** `TransactionalCommand`.  
3. Commands must remain idempotent at the Dynamo condition level (client request tokens / attribute_not_exists) — persister concern, called out here because config owns retry knobs.  
4. Do **not** set retriable on unique-index / permanent validation failures.

---

## 7. Testability (per `/tdd`)

### 7.1 Config model without AWS

Contract tests assert builder ↔ XML parity and validation:

```java
abstract class ReladynamoConfigContractTest {

    protected abstract ReladynamoConfig loadCustomerConfig();

    @Test
    void should_map_customer_table_and_gsi_when_config_is_valid() {
        ReladynamoConfig config = loadCustomerConfig();
        ObjectMapping customer = config.requireObject("com.acme.domain.Customer");
        assertThat(customer.getTable().getTableName()).isEqualTo("Reladynamo_Customer");
        assertThat(customer.getIndexes()).hasSize(1);
        assertThat(customer.getIndexes().get(0).getIndexName()).isEqualTo("GsiEmail");
    }

    @Test
    void should_reject_duplicate_class_names_when_build_is_called() {
        assertThatThrownBy(() -> ReladynamoConfig.builder()
                .client(minimalClient())
                .addObject(minimalCustomer())
                .addObject(minimalCustomer())
                .build())
                .isInstanceOf(ReladynamoConfigException.class)
                .hasMessageContaining("Duplicate ObjectMapping");
    }
}

final class ReladynamoConfigFluentTest extends ReladynamoConfigContractTest {
    protected ReladynamoConfig loadCustomerConfig() {
        return CustomerReladynamoExample.create();
    }
}

final class ReladynamoConfigXmlTest extends ReladynamoConfigContractTest {
    protected ReladynamoConfig loadCustomerConfig() {
        return new ReladynamoXmlLoader()
                .load(getClass().getResourceAsStream("/reladynamo-customer.xml"));
    }
}
```

RED evidence requirement when implementing: first run must fail on missing builder validation before implementation exists.

### 7.2 Bootstrap against DynamoDBLocal

```java
@Test
void should_bind_portal_to_dynamo_persister_when_bootstrap_starts() throws Exception {
    try (DynamoDbLocalSupport local = DynamoDbLocalSupport.start();
         ReladynamoRuntime runtime = ReladynamoBootstrap.builder()
                 .config(CustomerReladynamoExample.create())
                 .dynamoDbClient(local.client())
                 .start()) {

        MithraObjectPortal portal = CustomerFinder.getMithraObjectPortal();
        assertThat(portal.getMithraObjectPersister())
                .isInstanceOf(DynamoDbPersister.class);
    }
}
```

Conformance suite (shared H2 vs Dynamo) is outside this document’s implementation scope but **bootstrap must be exercisable** in-process without Docker — satisfied by `DynamoDbLocalSupport`.

### 7.3 What not to mock

- Do not mock `ReladynamoConfig` value objects in unit tests — build real ones.  
- Do not mock Reladomo portals for binder tests — use generated test domain + Local.  
- Mock only at the owned HTTP boundary if testing translator in isolation (`DynamoDbClient` stub returning faults).

---

## 8. Decisions log (rationale + rejected alternatives)

| Decision | Rationale | Rejected alternative | Why rejected |
|---|---|---|---|
| Single immutable `ReladynamoConfig` model | Prevents XML/builder drift | Separate XmlConfig vs FluentConfig types | Duplicated validation; equality tests fork |
| XML loader drives builders | One `build()` gate | Direct field construction from XML | Can create illegal states |
| Portal bind via `setMithraObjectReader` | Public API; works with existing DatabaseObjects | PureObjects / ObjectFactory | Requires different generator artifact (`*ObjectFactory`) |
| NoOp JDBC ConnectionManager for MithraRuntime | Reladomo LocalObjectConfig requires a CM class | Skip `readConfiguration` and only reflect into Finder statics | Still need cache/portal init that Reladomo owns |
| URL Connection HTTP client default | Java 11-friendly, lean | Netty default | Extra native/deps weight |
| Optional Spring starter module | Library must not require Spring | Spring-only bootstrap | Violates plain-Java requirement |
| Map AWS errors → Mithra*Exception | Preserve Reladomo caller contract | Propagate `DynamoDbException` | Breaks retry/`isRetriable` integration |
| Config equality for tests | Assertability without AWS | Snapshot toString only | Brittle |

---

## 9. OPEN QUESTIONS (do not invent)

1. **Does portal init call `ConnectionManager.getConnection()` / `getDatabaseType()` before any finder use?** Determines whether `NoOpConnectionManager` may throw or must stub. Needs walking-skeleton spike.

2. **Is `mithraTuplePersister` exercised on the conformance dated transactional path after reader swap?** No public setter exists; if yes, Strategy A is incomplete and Fallback C (or reflection) is required.

3. **Does `getDatabaseObject()` get invoked on Dynamo-bound portals for non-SQL paths** (notifications, extractDatabaseIdentifiers, multi-update helpers)? It casts `objectFactory` to `MithraDatabaseObject` — still the JDBC DBO after Strategy A. Spike must call representative APIs.

4. **Can we synthesize `MithraRuntimeType` purely in Java without XML?** Public setters exist on `MithraRuntimeTypeAbstract` / nested types, but connection-manager instantiation from `className` still uses reflection. Confirm property injection for `NoOpConnectionManager` singleton vs zero-arg ctor.

5. **Exact DynamoDBLocal native library load requirements on Windows/WSL for in-process sqlite4java** in this environment — jar is present (2.5.3); runtime packaging for tests not verified here.

6. **Optimistic-lock attribute mapping** from Reladomo XML to Dynamo condition expressions — owned partly by codec/persister; config may need an explicit `optimisticLockAttribute` override. Defer until persister design lands; do not invent attribute names here.

---

## 10. Implementation order (when coding starts)

1. `ReladynamoConfig` + builders + validation tests (no AWS).  
2. `ReladynamoXmlLoader` parity tests.  
3. `DynamoClientFactory` + Local support.  
4. Walking-skeleton: Strategy A bind on one dated transactional object; record OPEN QUESTIONS 1–3 answers.  
5. Exception translator + retry tests with stub client.  
6. `validate()` + Spring starter (last; optional).

---

## 11. Appendix — verification evidence

Commands run against local Maven artifacts (Windows `javap` from JDK 21.0.11; equivalent to the task’s WSL `javap.exe` path):

```text
javap -cp reladomo-18.1.0.jar -public com.gs.fw.common.mithra.MithraManager
javap -cp reladomo-18.1.0.jar -public com.gs.fw.common.mithra.util.MithraConfigurationManager
javap -cp reladomo-18.1.0.jar -public com.gs.fw.common.mithra.MithraManagerProvider
javap -cp reladomo-18.1.0.jar -public com.gs.fw.common.mithra.MithraBusinessException
javap -cp reladomo-18.1.0.jar -public com.gs.fw.common.mithra.MithraDatabaseException
javap -cp reladomo-18.1.0.jar -public com.gs.fw.common.mithra.portal.MithraObjectReader
javap -cp reladomo-18.1.0.jar -public com.gs.fw.common.mithra.transaction.MithraObjectPersister
javap -cp reladomo-18.1.0.jar -public com.gs.fw.common.mithra.transaction.MithraDatedObjectPersister
javap -cp reladomo-18.1.0.jar -public com.gs.fw.common.mithra.portal.PureMithraObjectPersister
javap -cp reladomo-18.1.0.jar -private com.gs.fw.common.mithra.portal.MithraAbstractObjectPortal
javap -cp reladomo-18.1.0.jar -c -p com.gs.fw.common.mithra.portal.MithraTransactionalPortal
javap -cp reladomo-18.1.0.jar -c -p com.gs.fw.common.mithra.util.MithraConfigurationManager
javap -cp bitemporal-bank-…jar;reladomo-18.1.0.jar -c -p bitemporalbank.domain.CustomerFinder
javap -cp DynamoDBLocal-2.5.3.jar -public com.amazonaws.services.dynamodbv2.local.embedded.DynamoDBEmbedded
javap -cp DynamoDBLocal-2.5.3.jar -public com.amazonaws.services.dynamodbv2.local.shared.access.AmazonDynamoDBLocal
javap -cp sdk-core-2.25.50.jar -public software.amazon.awssdk.core.retry.RetryPolicy
```

Sources also extracted from `reladomo-18.1.0-sources.jar` into `_reladomo_src/` for line-accurate reading of `MithraConfigurationManager`, `MithraAbstractObjectPortal`, `MithraTransactionalPortal`, `PureMithraObjectPersister`, `MithraBusinessException`, `MithraManager`.

Key facts confirmed:

| Fact | Evidence |
|---|---|
| `readConfiguration` = `parseConfiguration` + `initializeRuntime` | `MithraConfigurationManager` sources L278–281; `MithraManager` delegates to configManager |
| Local DBO naming `className + "DatabaseObject"` | bytecode `ldc "DatabaseObject"` in `instantiateDatabaseObject` |
| Pure factory naming `className + "ObjectFactory"` | bytecode `ldc "ObjectFactory"` in `instantiateDeserializer` |
| Finder builds `MithraTransactionalPortal` with DBO cast to `MithraObjectPersister` | `CustomerFinder.initializePortal` bytecode |
| Portal ctor dual-casts same persister to `MithraTuplePersister` | `MithraTransactionalPortal` sources L60–66 |
| Public swap hook `setMithraObjectReader` | `MithraAbstractObjectPortal` sources L558–561 |
| `getMithraObjectPersister()` casts reader | sources L201–204 |
| No public tuple-persister setter | `javap -private` fields; only ctor + `destroy()` assign |
| `getDatabaseObject()` casts `objectFactory` (deserializer), not reader | sources L457–460 |
| Retriable flag + wait helper | `MithraBusinessException.isRetriable/setRetriable/ifRetriableWaitElseThrow`; portal find paths call it |
| `SourcelessConnectionManager` SPI | `getConnection`, `getDatabaseType`, `getDatabaseTimeZone`, `getDatabaseIdentifier`, `createBulkLoader` |
| DynamoDBLocal in-process SDK v2 | `DynamoDBEmbedded.create()` → `AmazonDynamoDBLocal`; default method `dynamoDbClient()` |
| AWS SDK v2 `RetryPolicy.builder().numRetries(...)` | `sdk-core-2.25.50` `RetryPolicy`; `ClientOverrideConfiguration.Builder.retryPolicy` |

---

## 12. Summary

Reladynamo’s Java configuration API is an SDK-style builder graph that shares one immutable `ReladynamoConfig` with XML and Spring properties front-ends. Runtime bootstrap initializes Reladomo through the normal `readConfiguration` / LocalObjectConfig path (so generated `*DatabaseObject` and Finder portal construction still run), then **binds** each portal to `DynamoDbPersister` via `MithraAbstractObjectPortal.setMithraObjectReader`. That is the verified, fork-free integration seam (Strategy A, ~85% confidence on the main persister path; Fallback C if tuple/`getDatabaseObject` SQL leakage appears). Pure-object configuration is the wrong primary route for existing transactional XML because it requires `*ObjectFactory` generation. Client lifecycle, observability, error translation, and testability keep AWS details behind Reladomo’s exception and transaction-retry contracts, with DynamoDBLocal in-process as the bootstrap test oracle.
