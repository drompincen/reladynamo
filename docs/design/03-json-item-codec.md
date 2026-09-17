# Design: JSON Item Codec — `MithraDataObject` ⇄ DynamoDB Item

| Field | Value |
|---|---|
| Status | Design (not implemented) |
| Module | `reladynamo-ddb` (codec package) |
| Baseline | Java 11, Reladomo 18.1.0, AWS SDK v2 |
| Related skills | `/reladomo-expert`, `/dynamodb-architect`, `/java-expert`, `/tdd` |
| Agent | `codec` |
| Authority | **This document owns wire-format fidelity.** Where the schema agent's
  provisional type table disagrees (notably `BigDecimal`→`N`, `double`→`N`,
  null=omit), the codec decisions below win; schema must adopt them. |

---

## 0. Purpose and non-goals

### Purpose

The codec is the **only** place that translates a Reladomo `MithraDataObject` into a DynamoDB
`Map<String, AttributeValue>` and back. Every bit that Reladomo's H2 path would persist — including
nullable-primitive null bits, `BigDecimal` scale, and the four temporal boundary timestamps — must
survive a round trip through DynamoDBLocal and live DynamoDB.

The persister (`MithraDatedObjectPersister`) owns keys, transactions, and batching. The codec owns
**value fidelity**. Wrong codec decisions silently break “current row” queries and H2↔DDB
conformance.

### Non-goals

- Re-deriving bitemporal split/terminate/updateUntil rules (those live in `TemporalDirector`).
- Choosing table PK/SK grammar beyond the **encoding** of temporal components the schema layer
  embeds in keys (schema agent owns key shape; this doc owns the sortable timestamp string).
- Query planning (`Operation` → key condition). The codec only encodes/decodes items.
- AWS SDK v1 (`com.amazonaws.*`) — forbidden.

### Architecture placement

```
Finder / TemporalDirector
        │  MithraDataObject (already dated)
        ▼
DynamoDbPersister  ──uses──►  MithraItemCodec.encode(data) → Map<String,AttributeValue>
        │                     MithraItemCodec.decode(item) → MithraDataObject
        ▼
DynamoDbClient (SDK v2)
```

Codec instances are **per Mithra object type**, built once from generated `Attribute[]` metadata
(and XML-derived nullability/precision), then cached on the portal binding. No per-item reflection.

---

## 1. Type mapping table

### 1.1 Access path into `MithraDataObject`

Do **not** read fields by reflection. Use Reladomo's generated `Attribute` / `Extractor` API
(verified against `reladomo-18.1.0.jar`):

| Operation | API |
|---|---|
| Null test | `Extractor.isAttributeNull(owner)` |
| Set null | `Extractor.setValueNull(owner)` |
| Boxed get/set | `Extractor.valueOf` / `setValue` |
| Primitive get/set | e.g. `IntExtractor.intValueOf` / `setIntValue` |
| Nullable? | `Attribute.getMetaData().isNullable()` |
| Infinity | `AsOfAttribute.getInfinityDate()` / `TimestampAttribute.getAsOfAttributeInfinity()` |
| UTC helpers | `com.gs.fw.common.mithra.util.MithraTimestamp.UtcTimeZone`, `createUtcTime`, `CONVERT_TO_UTC` on `TimestampAttribute` |
| Precision constants | `TimestampAttribute.MILLISECOND_PRECISION` / `NANOSECOND_PRECISION` (byte) |

Trap: calling `intValueOf` on a null nullable int does **not** reliably throw at the extractor
boundary in all paths; generated data objects track null in a parallel null-bit structure. Always
call `isAttributeNull` **before** reading a primitive. Writing `0` without clearing the null bit
stores a false zero. Writing via `setIntValue` clears null; writing via `setValueNull` sets it.

`com.gs.fw.common.mithra.MithraNullPrimitiveException` exists for PK / non-nullable cases where the
database returned SQL NULL without a configured default — the codec must never produce that
situation for attributes it decoded as present-and-null into a non-nullable attribute; that is a
corrupt-item error.

**Cross-agent note:** the schema design's provisional table maps `BigDecimal`→`N` and
`float`/`double`→`N`. That violates this charter's bit-fidelity requirement (scale trim; exponent
and IEEE specials). Sections §1.3 override those mappings. Schema derivation must emit
`AttributeCodecSpec` entries that match **this** table, not the provisional one.

### 1.2 Exhaustive mapping

Legend for **Round-trip**: `exact` = bit/value identical after encode→DynamoDB→decode;
`value` = `compareTo`/`equals` as noted; `reject` = encode throws before write.

| Java / Reladomo type | `AttributeValue` | Wire encoding | Round-trip | Failure if wrong |
|---|---|---|---|---|
| `boolean` (non-null) | `BOOL` | `true` / `false` | exact | Storing as `N` `0/1` breaks typed decode; filter expressions differ |
| `boolean` nullable + null | `NULL` | `{"NULL": true}` | exact null bit | Omitting attribute vs `NULL` — see §1.4; writing `BOOL false` destroys null |
| `byte` | `N` | decimal string of signed value, e.g. `"-128"` | exact | Widening through `double` can lose integer identity for edge cases — never go via floating |
| `short` | `N` | decimal string | exact | Same |
| `int` | `N` | decimal string | exact | Same |
| `long` | `N` | decimal string of full signed long (`Long.toString`) | exact | `N` precision 38 digits ≫ 19 digits of `long` — safe |
| `float` | `B` (4 bytes) | IEEE-754 single, big-endian raw bits via `Float.floatToRawIntBits` | exact (incl. NaN payloads, ±Inf, −0.0) | **`N` is rejected as default** — see §1.3 |
| `double` | `B` (8 bytes) | IEEE-754 double, big-endian raw bits via `Double.doubleToRawLongBits` | exact | Same; DynamoDB `N` exponent max ~1e125 < `Double.MAX_VALUE` ~1e308 |
| `char` | `S` | single UTF-16 char as one-char String (BMP); for unpaired surrogates store as `N` code unit — see note | exact for BMP | Storing as `N` of code point alone is OK but less debuggable; empty string is invalid for char |
| `String` | `S` | UTF-8 bytes as DynamoDB string; **empty string allowed** for non-key attrs | exact | Empty string **forbidden** on table/index keys (2048/1024 byte key limits) — §1.5 |
| `java.sql.Date` | `S` | `yyyy-MM-dd` (UTC calendar date of the millis-in-UTC) | exact date | Local-TZ encode shifts the civil date — forbidden |
| `java.sql.Time` / Reladomo `com.gs.fw.common.mithra.util.Time` | `S` | `HH:mm:ss.SSS` (ms-of-day); prefer Reladomo `Time` accessors when the attribute is that type | exact ms-of-day | Using JVM-default `Time.valueOf` shifts wall time; see §2.6 |
| `java.sql.Timestamp` | `S` | Sortable UTC form §2 (`…SSS` or `…SSSSSSSSS`) | exact ms (and nanos if enabled) | Infinity / pre-epoch / TZ bugs — §2 |
| `BigDecimal` | `S` (canonical) | see §1.3 — **not** bare `N` | exact for **scale ≥ 0** (Reladomo DECIMAL); see negative-scale note | `N` trims trailing zeros → scale loss; >38 digits → service exception |
| `byte[]` | `B` | raw bytes (SDK handles base64 on the wire) | exact | Empty `B` allowed for non-key attrs |
| Nullable primitive (any) + null bit | `NULL` | `{"NULL": true}` | null bit restored via `setValueNull` | Writing typed zero/`false`/`0.0` without null — **classic Reladomo trap** |

**Char note:** Reladomo `char` is a single Java `char` (UTF-16 code unit). Encode as `S` with
`String.valueOf(c)`. Reject storing as multi-char strings on decode.

**Sets (`SS`/`NS`/`BS`):** Reladomo object attributes are scalars, not sets. Do not use set types
for scalar attributes. (Rejected alternative: packing enums as `SS` — unnecessary and breaks
single-value update expressions.)

### 1.3 `BigDecimal` and binary floats — fidelity decisions

#### BigDecimal → why not bare `N`

DynamoDB `N` facts (AWS Naming Rules & Data Types):

1. Max **38 digits of precision**; exceeding → exception.
2. Exponent range roughly **1e-130 … 1e+125** (signed).
3. **Leading and trailing zeroes are trimmed.**

Java `BigDecimal` is unbounded and **scale-significant** under `equals` (though not under
`compareTo`). Reladomo XML declares `precision` and `scale` for decimal attributes; H2 `DECIMAL`
preserves scale. The H2↔DDB conformance suite must see identical values.

| Situation | Bare `N` result |
|---|---|
| `1.10` (scale 2) | Stored/returned as `1.1` → scale 1 — **data loss** |
| 39+ significant digits | `ValidationException` on write |
| Magnitude outside exponent range | `ValidationException` on write |
| `1E-200` | Out of range |
| Financial `DECIMAL(18,4)` inside 38 digits | Value OK, **scale still at risk** if trailing zeros |

**Decision: encode `BigDecimal` as `S` with canonical plain form.**

Canonical form rules:

1. Use `toPlainString()` (never `toString()`, which may use scientific notation).
2. Preserve the `BigDecimal`'s own scale for **scale ≥ 0** (trailing zeros appear in plain form,
   e.g. `1.10` → `"1.10"` → scale 2).
3. Reject `null` unboxed path — null uses `NULL` type when nullable.
4. On decode: `new BigDecimal(string)` — plain decimal strings round-trip non-negative scale.
5. **Negative scale caveat:** `new BigDecimal("1E+2")` has scale −2; `toPlainString()` yields
   `"100"` (scale 0), so `equals` fails even though `compareTo` is 0. Reladomo XML
   `precision`/`scale` decimals are scale ≥ 0 in practice. If a pathological negative-scale value
   appears, either reject on encode or fall back to `M` `{u: unscaled N-or-S, s: scale N}` —
   keep `S` as the default path.

```java
static AttributeValue encodeBigDecimal(BigDecimal value) {
    // value non-null
    return AttributeValue.builder().s(value.toPlainString()).build();
}

static BigDecimal decodeBigDecimal(AttributeValue av) {
    if (av.s() == null) {
        throw new CodecException("BigDecimal attribute expected S, got " + typeOf(av));
    }
    return new BigDecimal(av.s());
}
```

**Rejected alternatives:**

| Alternative | Why rejected |
|---|---|
| Always `N` | Scale loss + 38-digit ceiling + exponent ceiling; fails fidelity charter |
| `N` when precision≤38 else `S` | Dual representation complicates decode, GSIs, and evolution; scale still lost on `N` |
| Silent `round(MathContext(38))` | Corrupts financial data; conformance with H2 fails |
| `M` `{u:…, s:…}` always | Exact for all scales including negative; heavier; use only as escape for scale &lt; 0 or if plain-string ambiguity is ever observed |

**When would we ever use `N` for decimals?** Only for **derived projection attributes** used solely
in DynamoDB numeric conditions where scale is irrelevant and magnitude is proven in-range. That is
an index/projection concern, not the canonical item codec. Canonical item payload stays `S`.

**Precision/scale enforcement (resolved against Reladomo 18.1.0):**
`BigDecimalAttribute.getPrecision()` / `getScale()` expose the XML-declared limits. On encode,
if the in-memory `BigDecimal` has `precision()` (significant digits) exceeding metadata precision,
or a scale that cannot be represented without rounding beyond metadata scale, **reject** with
`CodecException` naming the attribute — never silently `setScale(..., RoundingMode.HALF_UP)`.
H2 with matching DDL rejects or rounds per column definition; silent codec coercion would make
DynamoDB diverge from the conformance oracle. On decode, reconstruct via `new BigDecimal(s)` and
leave Reladomo/application validation to compare against metadata if needed.

**Rejected:** coerce-on-write like some JDBC drivers. That hides bad producers and breaks bit
fidelity vs an H2 schema that was generated with the same precision/scale.

#### `float` / `double` → `B`, not `N`

`double` → DynamoDB `N` is **lossy or impossible** for a non-empty set of values:

1. **Exponent:** DynamoDB rejects magnitudes outside ~1e±125/130; `Double.MAX_VALUE` ≈ 1.7e308.
2. **Specials:** `NaN`, `+Infinity`, `-Infinity` have no `N` representation.
3. **Decimal conversion:** even in-range finite values are decimalized; although
   `Double.toString`/`parseDouble` round-trip in Java, DynamoDB's trim and cross-language
   reparse are an unnecessary risk against a “single bit” charter.
4. **−0.0:** decimal forms usually collapse to `0`.

**Decision: store IEEE-754 raw bits as `B`.**

```java
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

static AttributeValue encodeDouble(double v) {
    long bits = Double.doubleToRawLongBits(v); // preserves NaN payloads; use raw, not canonical
    ByteBuffer buf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
    buf.putLong(bits);
    buf.flip();
    return AttributeValue.builder().b(SdkBytes.fromByteBuffer(buf)).build();
}

static double decodeDouble(AttributeValue av) {
    if (av.b() == null) {
        throw new CodecException("double attribute expected B");
    }
    ByteBuffer buf = av.b().asByteBuffer().order(ByteOrder.BIG_ENDIAN);
    if (buf.remaining() != 8) {
        throw new CodecException("double B must be 8 bytes, got " + buf.remaining());
    }
    return Double.longBitsToDouble(buf.getLong());
}

static AttributeValue encodeFloat(float v) {
    int bits = Float.floatToRawIntBits(v);
    ByteBuffer buf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
    buf.putInt(bits);
    buf.flip();
    return AttributeValue.builder().b(SdkBytes.fromByteBuffer(buf)).build();
}

static float decodeFloat(AttributeValue av) {
    if (av.b() == null) {
        throw new CodecException("float attribute expected B");
    }
    ByteBuffer buf = av.b().asByteBuffer().order(ByteOrder.BIG_ENDIAN);
    if (buf.remaining() != 4) {
        throw new CodecException("float B must be 4 bytes, got " + buf.remaining());
    }
    return Float.intBitsToFloat(buf.getInt());
}
```

**Rejected alternatives:**

| Alternative | Why rejected |
|---|---|
| `N` via `Double.toString` | Fails outside DynamoDB range; specials; −0.0; charter violation |
| `S` hex of raw bits | Exact but 16 chars vs 8 bytes; use `B` |
| Reject `float`/`double` at bind time | Too harsh — Reladomo models commonly use `double` for quantities |
| Store as `N` “for queryability” | Scalar payload attrs are not DynamoDB key attributes; numeric conditions on payload use filters anyway. If a future access pattern needs numeric SK on a double, add an explicit projected fixed-point attribute — do not weaken the canonical encoding |

**Consequence:** you cannot write a DynamoDB `ConditionExpression` numeric comparison on a
`double` payload attribute without a projection. Acceptable: Reladomo evaluates such predicates in
its own operation layer / residual filter.

### 1.4 Reladomo null semantics for primitives (the trap)

Reladomo allows `nullable="true"` on Java primitive types. The generated data object stores:

- the primitive field (default `0` / `false`), **and**
- a null flag (null-bits / per-attribute), exposed through `isAttributeNull` / `setValueNull`.

SQL `NULL` ≠ primitive default. H2 stores SQL NULL; JDBC sets the null flag. DynamoDB must too.

**Encode algorithm (per attribute):**

```text
if attribute.isAttributeNull(data):
    if !meta.isNullable():
        fail hard (corrupt in-memory object)
    write AttributeValue.NULL
else:
    write typed encoding of primitive/object value
```

**Decode algorithm:**

```text
if attribute absent from item:
    if schemaVersion implies attribute existed:
        treat as NULL if nullable, else fail
    else:
        // schema evolution: new attr on old item
        leave default + setValueNull if nullable, else apply XML default if any
else if av.nul() == true:
    attribute.setValueNull(data)
else:
    attribute.setXxxValue(data, decoded)
```

**Wrong patterns (must never ship):**

```java
// WRONG: collapses null and zero
int q = quantityAttr.intValueOf(data);
item.put("quantity", AttributeValue.builder().n(Integer.toString(q)).build());

// WRONG: decode always setIntValue — clears null bit incorrectly when av is NULL
quantityAttr.setIntValue(data, Integer.parseInt(av.n()));
```

**Correct sketch:**

```java
import com.gs.fw.common.mithra.attribute.Attribute;
import com.gs.fw.common.mithra.extractor.IntExtractor;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

@SuppressWarnings("unchecked")
void encodeInt(Attribute<?, Integer> attr, Object data, Map<String, AttributeValue> item) {
    String name = wireName(attr);
    if (attr.isAttributeNull(data)) {
        item.put(name, AttributeValue.builder().nul(true).build());
        return;
    }
    IntExtractor<Object, Integer> ints = (IntExtractor<Object, Integer>) attr;
    int v = ints.intValueOf(data);
    item.put(name, AttributeValue.builder().n(Integer.toString(v)).build());
}

@SuppressWarnings("unchecked")
void decodeInt(Attribute<?, Integer> attr, Object data, AttributeValue av) {
    if (Boolean.TRUE.equals(av.nul())) {
        attr.setValueNull(data);
        return;
    }
    if (av.n() == null) {
        throw new CodecException("int attribute expected N or NULL");
    }
    IntExtractor<Object, Integer> ints = (IntExtractor<Object, Integer>) attr;
    ints.setIntValue(data, Integer.parseInt(av.n()));
}
```

Reference types (`String`, `BigDecimal`, `Timestamp`, `byte[]`, `Date`, `Time`): null means Java
`null`. Still persist as DynamoDB `NULL` (not attribute omission) for attributes that exist in the
bound schema version, so “explicit null” is distinguishable from “added in a later schema version”
when combined with `_rd_v` (§3.3).

**Reconciling with schema's “omit nulls for sparse GSIs”:**

| Attribute role | Null encoding | Why |
|---|---|---|
| Non-key payload | DynamoDB `NULL` type | Restores Reladomo null bit; pairs with `_rd_v` for evolution |
| Table PK/SK component | **Illegal** — reject before write | DynamoDB forbids null keys |
| GSI key attribute | **Omit** the attribute (do not write `NULL`) | Sparse GSI: missing key attr ⇒ no index row; `NULL` type cannot be an index key |

So the schema agent's “omit” rule is correct **for index keys**; it is wrong as a blanket payload
policy because omitting a known-at-version-N nullable primitive collapses “SQL NULL” into the same
shape as “attribute not in item,” which only `_rd_v` can disambiguate — and even then, rewriting
history rows without the attribute is ambiguous. **Default payload policy: explicit `NULL`.**

### 1.5 String limits and empty strings

| Limit | Value | Codec behaviour |
|---|---|---|
| Item size | **400 KB** (names + values) | Pre-write size estimate; policy §4 |
| Attribute name | practical ≪ 64 KB; index key names ≤ 255 chars | Prefer short wire names if compression on |
| Partition key string | **2048 bytes** UTF-8 | Key builder validates; codec helper `utf8Length` |
| Sort key string | **1024 bytes** UTF-8 | Same |
| Empty string non-key | **Allowed** (DynamoDB) | Encode `""` as `S` with empty value |
| Empty string as key | **Forbidden** | Reject at key-build time with actionable error |

`mustTrimString()` from `AttributeMetaData`: if true, trim on encode to match Reladomo JDBC
behaviour; document that trimming is applied once on write (decode returns stored form).

---

## 2. Temporal encoding

This is the highest-value part of the codec. Sort-key range queries for
`businessDateFrom <= X` / processing-time windows depend on **lexicographic order ≡ chronological
order** in UTC.

### 2.1 Requirements

1. Millisecond precision preserved exactly on round trip for payload attributes.
2. Encoding fixed-width, UTC, lexicographically sortable for use inside SK composites.
3. Configured Reladomo infinity sentinel encodes so it **sorts last** among legal business
   timestamps for that model and **decodes to the exact same `Timestamp` value**
   (`getTime` + `getNanos`).
4. Pre-epoch (negative millis) timestamps sort before epoch.
5. No dependency on JVM default timezone.

### 2.2 Why UTC-only

`java.sql.Timestamp` is a thin wrapper over epoch millis + nanos; its `toString()` uses the **JVM
default TZ**, which is poison for persisted forms. Reladomo already exposes UTC helpers
(`com.gs.fw.common.mithra.util.MithraTimestamp.UtcTimeZone`, `createUtcTime`,
`TimestampAttribute.CONVERT_TO_UTC`). DynamoDB has no datetime type — strings compare as UTF-8 bytes.

**Rule:** every temporal encode/decode path uses `ZoneOffset.UTC` /
`com.gs.fw.common.mithra.util.MithraTimestamp.UtcTimeZone` only. Never `TimeZone.getDefault()`,
never `LocalDateTime` without an explicit offset, never `Timestamp.valueOf(String)` to build
fixtures (that parses in the JVM default zone and will fail conformance on CI agents in
`America/Los_Angeles`).

**Rejected:** store “database local” wall time like some JDBC modes. The adapter is not JDBC; H2
reference tests must pin session TZ to UTC for parity. Reladomo's
`TimestampAttribute.timezoneConversion` / `CONVERT_TO_UTC` still ends at a UTC instant before the
codec sees the value — the codec stores that instant, not a wall-clock string.

### 2.3 Chosen payload + SK string format

**Primary format (`TemporalWireFormat.ISO_UTC_MILLIS`):**

```
yyyy-MM-dd'T'HH:mm:ss.SSS'Z'
```

- Always **24 characters** for years 0000–9999 (example: `2026-09-12T13:45:01.123Z`).
  Note: some sibling drafts say “23 chars”; that is a counting error — do not generate 23-char
  keys or lexicographic comparisons will break at the `Z` boundary.
- Infinity example (common Reladomo default from XML
  `infinityDate="9999-12-01 23:59:00.0"`): `9999-12-01T23:59:00.000Z` — sorts after any
  realistic business/processing timestamp with a 4-digit year.
- Aligns with schema agent's SK grammar embedding the same UTC pattern inside
  `v1#P#<processingFrom>#B#<businessFrom>`.

**Nanos policy:**

- Reladomo `TimestampAttribute` exposes `MILLISECOND_PRECISION` and `NANOSECOND_PRECISION`.
- **Default for Reladynamo:** preserve **milliseconds** in SK and payload as required by the
  charter; on encode, if `getNanos() % 1_000_000 != 0`, either:
  - **strict mode (default for conformance):** use extended form
    `yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSS'Z'` (33 chars, still fixed-width, still sortable), or
  - **millis mode:** reject with clear error if sub-ms nanos present.

**Recommendation:** support both via codec config `timestampFractionDigits = 3 | 9`, default **3**
for SK compactness (fits better inside 1024-byte SK), and **9** for payload attribute values when
the object metadata declares nanosecond precision. SK components always use the same digit count
for a given table so ordering stays consistent.

**OPEN QUESTION:** Confirm against the project's sample models whether any as-of column relies on
sub-ms. Until then default SK=3, payload=max(3, metadata).

### 2.4 Infinity

```java
Timestamp infinity = asOfAttribute.getInfinityDate();
```

Encode using the same formatter as every other timestamp (no separate token like `"INF"`).

Why not a token:

| Approach | Sort order | Round-trip to same sentinel | Rejected because |
|---|---|---|---|
| Literal `INF` / `~` | Must carefully pick charset | Needs special decode branch | Breaks ISO consumers; easy to get sort wrong vs `9999-…` |
| `Long.MAX_VALUE` millis as number SK | OK if fixed-width | Must map back to configured infinity, not to `new Timestamp(Long.MAX_VALUE)` | Diverges from Reladomo XML infinity |
| ISO of configured infinity | Natural string max among 4-digit years if infinity is 9999-… | Decode identity via equality with configured infinity | **Chosen** |

**Decode identity rule:** after parsing millis/nanos, if the value `equals` the bound infinity
timestamp (time + nanos), return `asOf.getInfinityDate()` (or a copy with identical fields) so
Reladomo's infinity comparisons stay aligned with the configured sentinel — not a “same looking”
date that differs by 1 ms.

If a model configured infinity at e.g. `4000-01-01`, ISO still works; “sorts last” is then “last
among dates ≤ infinity”, which is what current-row encoding uses. Document that infinity must be
≥ any business timestamp the app will write — that is already a Reladomo modelling rule.

### 2.5 Pre-epoch / negative timestamps

ISO-8601 with a **signed, fixed 4-digit year** does not cover `Timestamp` values whose UTC year is
outside 0000–9999 (possible via extreme millis).

**Policy:**

1. For years **0000–9999**: ISO form above (no leading `+`).
2. For years outside that range: fall back to **fixed-width signed epoch millis** form for SK:

```
E + 19-digit zero-padded unsigned(millis - Long.MIN_VALUE)   // conceptual
```

Safer implementation without unsigned overflow:

```java
// 1 character sign ('A' for negative, 'B' for non-negative) + 19 digit abs magnitude
// 'A' < 'B' so all negative millis sort before all non-negative — BUT
// within negatives, lexicographic order of absolute value is WRONG.
```

Correct approach for full `long` range — **biased fixed-width decimal:**

```java
static String encodeMillisSortable(long millis) {
    // Map Long.MIN_VALUE..Long.MAX_VALUE → 0..2^64-1 as BigInteger, then 20-digit decimal
    BigInteger biased = BigInteger.valueOf(millis).subtract(BigInteger.valueOf(Long.MIN_VALUE));
    String s = biased.toString();
    if (s.length() > 20) {
        throw new CodecException("biased millis exceeds 20 digits");
    }
    StringBuilder sb = new StringBuilder(20);
    for (int i = s.length(); i < 20; i++) {
        sb.append('0');
    }
    sb.append(s);
    return sb.toString();
}
```

Lexical order of the 20-digit string equals numeric order of millis.

**Recommendation:**

- **Default SK temporal component:** ISO UTC millis for the Reladomo-practical range; validate on
  encode that UTC year ∈ [0000, 9999]; fail fast otherwise with message suggesting
  `TemporalWireFormat.BIASED_EPOCH_MILLIS`.
- **Config escape:** `biased-epoch` for scientific/historical models.

Payload attributes may always store ISO when in range, else biased form with an explicit prefix
`E20:` so decode can branch. Prefer one format per table.

### 2.6 Java 11-safe encode/decode

```java
package com.reladynamo.ddb.codec;

import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.Objects;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * UTC timestamp wire codec. Java 11 baseline — no records, no text blocks, no Stream.toList().
 *
 * <p><b>Conversion rule (critical for pre-epoch):</b> always use {@link Timestamp#toInstant()}.
 * Do <em>not</em> write {@code Instant.ofEpochSecond(ts.getTime() / 1000L, ts.getNanos())}.
 * {@code Timestamp.getTime()} is overridden to fold nanos into the millis value, while
 * {@code getNanos()} returns the full nano-of-second. Truncating {@code getTime()/1000} toward
 * zero then re-adding nanos <em>double-counts</em> the fractional second for negative timestamps
 * (example: {@code getTime()=-1500} with {@code nanos=500_000_000} becomes −500 ms under the
 * wrong formula, −1500 ms under {@code toInstant()}). OpenJDK's {@code toInstant()} uses the
 * internal second-truncated base millis — that is the correct path.
 */
public final class TimestampWireCodec {

    public static final DateTimeFormatter ISO_UTC_MILLIS =
            new DateTimeFormatterBuilder()
                    .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
                    .appendFraction(ChronoField.MILLI_OF_SECOND, 3, 3, true)
                    .appendLiteral('Z')
                    .toFormatter()
                    .withZone(ZoneOffset.UTC);

    public static final DateTimeFormatter ISO_UTC_NANOS =
            new DateTimeFormatterBuilder()
                    .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
                    .appendFraction(ChronoField.NANO_OF_SECOND, 9, 9, true)
                    .appendLiteral('Z')
                    .toFormatter()
                    .withZone(ZoneOffset.UTC);

    private final DateTimeFormatter formatter;
    private final int fractionDigits;
    private final Timestamp infinityOrNull;

    public TimestampWireCodec(int fractionDigits, Timestamp infinityOrNull) {
        if (fractionDigits != 3 && fractionDigits != 9) {
            throw new IllegalArgumentException("fractionDigits must be 3 or 9");
        }
        this.fractionDigits = fractionDigits;
        this.formatter = fractionDigits == 3 ? ISO_UTC_MILLIS : ISO_UTC_NANOS;
        this.infinityOrNull = infinityOrNull == null ? null : copyTimestamp(infinityOrNull);
    }

    public String encode(Timestamp ts) {
        Objects.requireNonNull(ts, "timestamp");
        // MUST use toInstant() — see class Javadoc. Wrong for pre-epoch if built from getTime()/1000.
        Instant instant = ts.toInstant();
        if (fractionDigits == 3 && (ts.getNanos() % 1_000_000) != 0) {
            throw new CodecException(
                    "sub-millisecond nanos present but fractionDigits=3; "
                            + "configure fractionDigits=9 or truncate at the Reladomo boundary");
        }
        try {
            return formatter.format(instant);
        } catch (DateTimeException ex) {
            throw new CodecException(
                    "Timestamp out of ISO year range for sortable form: " + ts.getTime(), ex);
        }
    }

    public Timestamp decode(String wire) {
        Objects.requireNonNull(wire, "wire");
        Instant instant = formatter.parse(wire, Instant::from);
        // Timestamp.from(Instant) is Java 8+ and correctly reconstructs millis+nanos.
        Timestamp ts = Timestamp.from(instant);
        if (infinityOrNull != null
                && ts.getTime() == infinityOrNull.getTime()
                && ts.getNanos() == infinityOrNull.getNanos()) {
            return copyTimestamp(infinityOrNull);
        }
        return ts;
    }

    public AttributeValue toAttributeValue(Timestamp ts) {
        return AttributeValue.builder().s(encode(ts)).build();
    }

    public Timestamp fromAttributeValue(AttributeValue av) {
        if (av.s() == null) {
            throw new CodecException("Timestamp expected S, got non-S AttributeValue");
        }
        return decode(av.s());
    }

    private static Timestamp copyTimestamp(Timestamp src) {
        Timestamp copy = new Timestamp(src.getTime());
        copy.setNanos(src.getNanos());
        return copy;
    }
}
```

`java.sql.Date` / time-of-day helpers (UTC civil forms — never `Date.valueOf` / `Time.valueOf`):

```java
package com.reladynamo.ddb.codec;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public final class DateTimeWire {
    private static final DateTimeFormatter TIME_OF_DAY =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private DateTimeWire() {}

    public static String encodeSqlDate(java.sql.Date date) {
        // Interpret the stored millis as an Instant and take the UTC civil date.
        Instant i = Instant.ofEpochMilli(date.getTime());
        return DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC).format(i);
    }

    public static java.sql.Date decodeSqlDate(String wire) {
        LocalDate d = LocalDate.parse(wire);
        long millis = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        return new java.sql.Date(millis);
    }

    /** Prefer this path when the Reladomo attribute is {@code com.gs.fw.common.mithra.util.Time}. */
    public static String encodeReladomoTime(com.gs.fw.common.mithra.util.Time time) {
        int nano = time.getNano();
        int millis = nano / 1_000_000;
        return String.format(
                java.util.Locale.ROOT,
                "%02d:%02d:%02d.%03d",
                time.getHour(),
                time.getMinute(),
                time.getSecond(),
                millis);
    }

    public static com.gs.fw.common.mithra.util.Time decodeReladomoTime(String wire) {
        LocalTime t = LocalTime.parse(wire, TIME_OF_DAY);
        // Factory name may vary slightly by Reladomo build — bind via TimeExtractor at impl time.
        return com.gs.fw.common.mithra.util.Time.withMillis(
                t.getHour(), t.getMinute(), t.getSecond(), t.getNano() / 1_000_000);
    }

    /** Fallback if a model truly uses {@code java.sql.Time}. */
    public static String encodeSqlTime(java.sql.Time time) {
        Instant i = Instant.ofEpochMilli(time.getTime());
        return TIME_OF_DAY.withZone(ZoneOffset.UTC).format(i);
    }
}
```

**Footgun:** `java.sql.Date.valueOf(LocalDate)` and `Timestamp.valueOf(String)` use the JVM default
timezone. Production codec code and all tests must use the UTC Instant paths above.

**Reladomo `Time` vs `java.sql.Time`:** generated attributes commonly use
`com.gs.fw.common.mithra.util.Time` via `TimeExtractor` (hour/minute/second/nano accessors), not
JDBC `java.sql.Time`. The wire form is identical (`HH:mm:ss.SSS`); the Java accessor path differs.

**Verified (javap against `reladomo-18.1.0.jar`):** public factories are
`Time.withMillis(int hour, int minute, int second, int millis)` and
`Time.withNanos(int hour, int minute, int second, int nanos)`, plus accessors
`getHour`/`getMinute`/`getSecond`/`getMillisecond`/`getNano`. The encode/decode snippets above
are therefore frozen for 18.1.0 — use `withMillis` for the default ms wire form.

### 2.7 Property-based tests (jqwik) — `/tdd`

Contract: encode∘decode = identity on millis (and nanos when fractionDigits=9), and
`encode(a).compareTo(encode(b))` has the same sign as `Long.compare(a.getTime(), b.getTime())`
within the supported year range. Infinity round-trips to the configured sentinel.

```java
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.Year;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

final class TimestampWireCodecProperties {

    /**
     * Build infinity from an Instant — NEVER via {@code Timestamp.valueOf(...)}, which is
     * JVM-default-TZ dependent and will disagree with the UTC ISO wire form on non-UTC agents.
     */
    private static final Timestamp INFINITY =
            Timestamp.from(Instant.parse("9999-12-01T23:59:00Z"));

    private final TimestampWireCodec codec = new TimestampWireCodec(3, INFINITY);

    @Provide
    Arbitrary<Timestamp> timestampsInIsoRange() {
        // Years 0001-9999 in UTC millis; shrink-friendly
        long min = Instant.parse("0001-01-01T00:00:00Z").toEpochMilli();
        long max = Instant.parse("9999-12-31T23:59:59.999Z").toEpochMilli();
        return Arbitraries.longs().between(min, max).map(millis -> {
            Timestamp ts = new Timestamp(millis);
            // normalize nanos to millis-only for fractionDigits=3
            int millisPart = (int) (Math.floorMod(millis, 1000L) * 1_000_000L);
            ts.setNanos(millisPart);
            return ts;
        });
    }

    @Property(tries = 2000)
    void should_round_trip_millis_when_timestamp_in_iso_range(
            @ForAll("timestampsInIsoRange") Timestamp original) {
        // Arrange
        // Act
        String wire = codec.encode(original);
        Timestamp decoded = codec.decode(wire);

        // Assert
        assertThat(decoded.getTime()).isEqualTo(original.getTime());
        assertThat(decoded.getNanos()).isEqualTo(original.getNanos());
    }

    @Property(tries = 1000)
    void should_preserve_chronological_lexicographic_order_when_both_in_range(
            @ForAll("timestampsInIsoRange") Timestamp a,
            @ForAll("timestampsInIsoRange") Timestamp b) {
        String wa = codec.encode(a);
        String wb = codec.encode(b);
        int lex = wa.compareTo(wb);
        int chron = Long.compare(a.getTime(), b.getTime());
        assertThat(Integer.signum(lex)).isEqualTo(Integer.signum(chron));
        assertThat(wa).hasSize(24);
        assertThat(wb).hasSize(24);
    }

    @Test
    void should_round_trip_infinity_to_configured_sentinel() {
        // Arrange / Act
        String wire = codec.encode(INFINITY);
        Timestamp decoded = codec.decode(wire);

        // Assert
        assertThat(wire).isEqualTo("9999-12-01T23:59:00.000Z");
        assertThat(decoded.getTime()).isEqualTo(INFINITY.getTime());
        assertThat(decoded.getNanos()).isEqualTo(INFINITY.getNanos());
    }

    @Test
    void should_sort_infinity_after_ordinary_business_date() {
        Timestamp business = Timestamp.from(Instant.parse("2026-09-12T10:00:00Z"));
        assertThat(codec.encode(INFINITY).compareTo(codec.encode(business))).isPositive();
    }

    @Test
    void should_encode_independent_of_jvm_default_timezone() {
        java.util.TimeZone original = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/Los_Angeles"));
            Timestamp ts = Timestamp.from(Instant.parse("2026-09-12T10:00:00.123Z"));
            assertThat(codec.encode(ts)).isEqualTo("2026-09-12T10:00:00.123Z");
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Tokyo"));
            assertThat(codec.encode(ts)).isEqualTo("2026-09-12T10:00:00.123Z");
        } finally {
            java.util.TimeZone.setDefault(original);
        }
    }

    /**
     * Guards the getTime()/1000 + getNanos() trap: for −1500 ms the wrong formula yields −500.
     * RED evidence if encode uses Instant.ofEpochSecond(ts.getTime()/1000L, ts.getNanos()).
     */
    @Test
    void should_round_trip_negative_millis_with_nonzero_nanos_residue() {
        TimestampWireCodec c = new TimestampWireCodec(3, null);
        Timestamp original = new Timestamp(-1500L); // getTime()=-1500; nanos typically 500_000_000
        // Normalize to millis-only fraction for fractionDigits=3
        int millisPart = (int) (Math.floorMod(-1500L, 1000L) * 1_000_000L);
        original.setNanos(millisPart);

        String wire = c.encode(original);
        Timestamp decoded = c.decode(wire);

        assertThat(decoded.getTime()).isEqualTo(original.getTime());
        assertThat(decoded.getNanos()).isEqualTo(original.getNanos());
        assertThat(wire).isEqualTo("1969-12-31T23:59:58.500Z");
    }

    @Property(tries = 500)
    void should_sort_pre_epoch_before_post_epoch(
            @ForAll @LongRange(min = -86_400_000L * 365 * 50, max = -1L) long pre,
            @ForAll @LongRange(min = 0L, max = 86_400_000L * 365 * 50) long post) {
        TimestampWireCodec c = new TimestampWireCodec(3, null);
        Timestamp a = new Timestamp(pre);
        a.setNanos((int) (Math.floorMod(pre, 1000L) * 1_000_000L));
        Timestamp b = new Timestamp(post);
        b.setNanos((int) (Math.floorMod(post, 1000L) * 1_000_000L));
        // Only assert when both fall in ISO year range — property assumes encode succeeds
        String wa = c.encode(a);
        String wb = c.encode(b);
        assertThat(wa.compareTo(wb)).isNegative();
    }
}
```

**TDD evidence expectations (when implementing):**

1. RED: `should_round_trip_infinity_to_configured_sentinel` fails if encode uses
   `timestamp.toString()` (default TZ / wrong fraction).
2. GREEN: UTC formatter path.
3. Property failures must print jqwik seed; keep seeds in the defect log.

Also add an **example-based** test that deliberately sets default TZ to `America/Los_Angeles` and
proves encode output unchanged — guards against TZ leakage.

---

## 3. Item layout

### 3.1 Attribute naming: full names vs compression map

| Scheme | Wire key example | Debuggability | Size |
|---|---|---|---|
| Full Reladomo attribute names | `businessDateFrom` | Excellent in console | Pays UTF-8 length every item |
| Short map `a0`,`a1`,… | `a3` | Needs side table | ~80%+ name-byte savings on verbose models |
| Column names from XML | `BUSINESS_DATE_FROM` | Familiar to SQL users | Often longer |

Micro-estimate (10 typical attributes, §research): full name bytes ≈ 124; 2-char codes ≈ 20;
**~84% name overhead reduction**. On a 1 KB item that is ~100 bytes (~10%). On a 20 KB item it is
noise. On millions of tiny bitemporal versions, name bytes dominate WCU rounding (1 KB write
units).

**Recommendation:**

1. **Default: full Reladomo attribute names** (`Attribute.getAttributeName()` / XML `name`), plus
   fixed system keys `pk`, `sk`, `_rd_v` (Reladynamo schema/codec version), optional `_rd_t`
   (type discriminator for single-table layouts). Prefix `_rd_` reserves the namespace so a
   domain attribute literally named `v` or `_v` cannot collide.
2. **Optional compression map** in `reladynamo.xml`:
   `attributeNames="full|compressed"`. Compressed mode writes `a`+index and stores the map in
   portal/config (not duplicated per row). System keys stay uncompressed and unprefixed-short
   (`pk`/`sk`/`_rd_v`).
3. Do **not** compress first version of the product: debuggability and conformance clarity beat
   ~100 B/item. Revisit when cost dashboards show name bytes matter.

**Column names vs attribute names:** schema may prefer XML `columnName` (`BUSINESS_DATE_FROM`) for
SQL familiarity. Codec default is Reladomo **attribute** name (`businessDateFrom`) because that is
what finders, logs, and generated code speak. Make `wireNameSource=attribute|column` configurable;
default `attribute`.

**Rejected:** hash names (`md5` prefix) — hostile to ops, collision risk, no savings vs `a0`.

### 3.2 Reserved words — `ExpressionAttributeNames` mandatory

DynamoDB reserved words include common tokens such as `name`, `status`, `size`, `type`, `date`,
`timestamp`, `value`, `count`, … Reladomo models use these constantly.

Even when the wire name is not reserved today, `#`/`:` are special in expressions.

**Rule:** every `UpdateExpression`, `ConditionExpression`, `ProjectionExpression`, and
`FilterExpression` built by Reladynamo **must** use `ExpressionAttributeNames` for **all**
user-attribute references — not only known reserved words. Implementation: always emit `#a0 = :v0`
style placeholders from the codec/persister's name allocator.

**Rejected:** “escape only when reserved” — incomplete lists and future reserved-word additions
cause production-only failures.

Keys `pk`/`sk` are short and non-reserved; still alias them for consistency.

### 3.3 Schema-version attribute `_rd_v`

Every item includes:

```text
_rd_v : N = "<non-negative integer>"
```

- Set by the codec from `ObjectCodecSpec.schemaVersion` (starts at `1`).
- Decode switches on `_rd_v` for renames/removals (§5).
- Missing `_rd_v` on read ⇒ treat as version `1` **only** if a compatibility flag
  `legacyUnversioned=true` is set; otherwise reject (fail fast beats silent mis-decode).
- Name chosen to match the schema agent's `_rd_v` reserved attribute (not bare `_v`).

Evolution uses `_rd_v` together with per-version attribute maps — not DynamoDB table versioning.
Changing the **temporal SK grammar** is a table/key migration (`v1`→`v2` in the SK itself per
schema design), not a silent `_rd_v` bump alone.

### 3.4 Nesting relationships as `M`/`L` vs separate items

| Approach | When | Trade-off |
|---|---|---|
| **Separate items** (default) | Normal Reladomo relationships | Matches Reladomo identity/cache; deep-fetch = queries; updates do not rewrite parent; item-size safe |
| Embed as `M` / `L` | Rare: truly owned, bounded, read-always-with-parent tiny docs | Faster single GetItem; loses independent lifecycle; blows 400 KB; breaks temporal versioning of children |

**Rule:** **never embed** another `MithraDataObject` graph in the parent item by default. Reladomo
relationships are joins, not document nests; the TemporalDirector versions **rows**, not trees.
Store relationship edges only if the schema agent designs edge items for access patterns — still
separate items, not nested `M`.

**Rejected:** auto-embed `deepFetch` targets — guarantees item-size and consistency pain.

### 3.5 Example item shape (bitemporal Position)

```text
pk     = "Position#1001#42"       // object#accountId#productId (schema agent owns grammar)
sk     = "v1#P#2026-09-12T10:00:00.000Z#B#2026-01-01T00:00:00.000Z"
                                  // schema grammar; temporal components use this codec
_rd_v  = 1
_rd_t  = "Position"               // optional; useful if single-table override enabled
accountId            N  "1001"
productId            N  "42"
quantity             B  <8 byte IEEE double bits>
businessDateFrom     S  "2026-01-01T00:00:00.000Z"
businessDateThru     S  "9999-12-01T23:59:00.000Z"
processingDateFrom   S  "2026-09-12T10:00:00.000Z"
processingDateThru   S  "9999-12-01T23:59:00.000Z"
```

---

## 4. Item size (400 KB)

### 4.1 Detection

Before `PutItem`/`UpdateItem`/`TransactWriteItems`, compute estimated size:

```
sum over attributes:
  utf8(name) + valueBytes(AttributeValue)
```

Use AWS's published item-size rules (Capacity Unit Calculations / Constraints docs):

| Type | Contribution (approx.) |
|---|---|
| String | UTF-8 bytes of name + UTF-8 bytes of value |
| Number | UTF-8 bytes of name + ~`(digits/2)+1` (leading/trailing zeros already trimmed on wire) |
| Binary | UTF-8 bytes of name + **raw** byte length (not base64) |
| BOOL / NULL | UTF-8 bytes of name + small fixed overhead |
| Map / List | name + recursive sum of nested elements |

Implement `ItemSizeEstimator.estimate(Map<String,AttributeValue>)` with unit tests against known
fixtures; prefer slightly **over**-estimating rather than under-estimating (false reject is safer
than a service `ValidationException` mid-transaction). Do not invent WCU billing rounding here —
only the **400 KB hard service limit**. For `String` attributes, also consult
`AttributeMetaData.getStringMaxLength()` when non-zero and reject oversize values before the item
aggregate check.

If `estimate > 400 * 1024`, apply the configured `ItemSizeOverflowPolicy` **before** the network
call (failing early avoids wasted WCU and partial transactions).

### 4.2 Strategies when exceeded

| Policy | Behaviour | Pros | Cons |
|---|---|---|---|
| `REJECT` (**default**) | Throw `ItemTooLargeException` with attribute size breakdown | Safe; forces model fix | App must handle |
| `S3_POINTER` | Put oversized payload attrs to S3; store `{s3:bucket,key,sha256,len}` as `M` | Handles large BLOBs | Consistency, IAM, not in DynamoDBLocal by default; temporal versions multiply objects |
| `OVERFLOW_CHAIN` | Split into `sk#part#0001` continuation items | Keeps data in DDB | Complex reads; breaks single-item atomicity unless `TransactWriteItems`; hostile to GSIs |

**Recommendation:** default **`REJECT`**. Opt-in `S3_POINTER` only for explicit `byte[]`
attributes marked `overflow="s3"` in config. Do **not** implement `OVERFLOW_CHAIN` in v1 —
document as future.

Config sketch:

```xml
<itemSize policy="reject" s3Bucket="${OVERFLOW_BUCKET}" s3ThresholdBytes="350000"/>
```

---

## 5. Evolution

### 5.1 Compatibility rules

| Change | Forward (new codec reads old item) | Backward (old codec reads new item) |
|---|---|---|
| Add nullable attribute | Absent → `setValueNull` | Old codec ignores unknown wire keys |
| Add non-null attribute with XML default | Apply default when absent | Old codec ignores |
| Add non-null without default | **Migration required** before cutover | N/A |
| Remove attribute | New codec ignores wire key (or strips on rewrite) | Old codec needs value — keep writing until old readers retired **or** bump major and dual-write |
| Rename attribute | `_rd_v` map: read old name, write new name; optional dual-write period | Dual-read both names while old live |
| Change wire type (e.g. double `N`→`B`) | Versioned decoder branch on `_rd_v` | Do not change in place without version bump |

**Rules of thumb:**

1. Bump `_rd_v` on any wire-format change (type, temporal fraction digits, name compression).
2. Readers understand all `_rd_v` ≤ current (forward compatibility of the fleet).
3. Writers emit only current `_rd_v`.
4. Old writers + new readers: OK if new readers accept prior `_rd_v`.
5. New writers + old readers: only OK if wire remains a backward-compatible expansion (additive
   fields). Type changes require stopping old readers first or dual-encoding.

### 5.2 Decode pipeline with versions

```text
read _rd_v (default 1 if legacy permitted)
select AttributeBinding[] for version
for each binding:
  resolve wire name (current or alias list)
  if missing → null/default policy
  else decode with that version's TypeCodec
```

### 5.3 What never evolves in place

Primary key attribute **values** and table key schema — immutable per `/dynamodb-architect`.
Temporal SK encoding changes require a new table or dual-write migration, not a quiet codec tweak.

---

## 6. Performance

### 6.1 Hot path allocation rules

On `encode(MithraDataObject)` / `decode`:

| Do | Do not |
|---|---|
| Reuse per-type `ObjectEncoder` with `AttributeEncoder[]` array | `Class.getMethod` / reflective field access per item |
| Precompute wire names, nullability, type handlers at bind time | Build fresh `HashMap` capacity 16 and resize; pre-size `new HashMap<>(attrCount * 2)` |
| Primitive extractors (`intValueOf`) | Box via `valueOf` on tight loops |
| Thread-safe immutable codec after bind | Mutate formatter/symbols per call |
| `ByteBuffer.allocate(8)` for doubles — consider thread-local scratch later if JMH says so | Share mutable static buffers without care |

### 6.2 Caching

```text
Portal binding
  └── DynamoObjectMapping (per Mithra class)
        ├── KeyCodec
        ├── MithraItemCodec   // cached singleton per mapping
        │     └── AttributeEncoder[n]
        └── ItemSizeEstimator
```

Creation happens once at runtime startup / first use; look up by
`MithraObjectPortal` or finder class.

### 6.3 When JMH is warranted

Benchmark only after a correct conformance suite exists:

1. `encode`/`decode` throughput for a typical bitemporal object (≤20 attrs) — baseline.
2. Compare reflective generic encoder vs generated/`Attribute` encoder (expect Attribute API win).
3. Double as `B` vs mistaken `N` (correctness first; perf secondary).
4. Item size estimator cost vs PutItem latency (estimator should be ≪ I/O).

JMH not warranted for one-off design validation; warranted before claiming “zero reflection” or
optimizing with thread-local buffers.

---

## 7. Public API sketch (Java 11)

```java
package com.reladynamo.ddb.codec;

import com.gs.fw.common.mithra.MithraDataObject;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

public interface MithraItemCodec {
    Map<String, AttributeValue> encode(MithraDataObject data);

    MithraDataObject decode(Map<String, AttributeValue> item);

    int estimateEncodedSize(MithraDataObject data);

    int schemaVersion();
}

public final class CodecOptions {
    private final ItemSizeOverflowPolicy overflowPolicy;
    private final AttributeNaming naming;
    private final int timestampFractionDigits;
    private final boolean legacyUnversioned;

    // constructor + getters only — no records (Java 11)
    public CodecOptions(
            ItemSizeOverflowPolicy overflowPolicy,
            AttributeNaming naming,
            int timestampFractionDigits,
            boolean legacyUnversioned) {
        this.overflowPolicy = overflowPolicy;
        this.naming = naming;
        this.timestampFractionDigits = timestampFractionDigits;
        this.legacyUnversioned = legacyUnversioned;
    }

    public static CodecOptions defaults() {
        return new CodecOptions(
                ItemSizeOverflowPolicy.REJECT,
                AttributeNaming.FULL,
                3,
                false);
    }

    public ItemSizeOverflowPolicy overflowPolicy() { return overflowPolicy; }
    public AttributeNaming naming() { return naming; }
    public int timestampFractionDigits() { return timestampFractionDigits; }
    public boolean legacyUnversioned() { return legacyUnversioned; }
}

enum ItemSizeOverflowPolicy { REJECT, S3_POINTER }
enum AttributeNaming { FULL, COMPRESSED }
```

Factory binds Reladomo `Attribute[]` from the portal/finder (XML-driven, no hand-coding per
entity):

```java
public final class MithraItemCodecFactory {
    public MithraItemCodec create(
            Class<? extends MithraDataObject> dataClass,
            com.gs.fw.common.mithra.attribute.Attribute[] attributes,
            com.gs.fw.common.mithra.attribute.AsOfAttribute[] asOfAttributes,
            CodecOptions options) {
        // build AttributeEncoder[] once; capture infinity per as-of
        ...
    }
}
```

---

## 8. Test plan (conformance + TDD)

Per `/tdd` and Chapter 6 of the project plan:

1. **Unit (jqwik):** `TimestampWireCodecProperties`, `BigDecimalScaleProperties`,
   `DoubleBitsProperties` (NaN, −0.0, MaxValue), `NullablePrimitiveCodecTest`.
2. **Unit examples:** empty string non-key; empty string key rejected; item size reject;
   reserved-word update expression uses `#n`.
3. **Contract:** abstract `ItemCodecContractTest` with in-memory round trip; later
   `DynamoDbLocalCodecIT` writes PutItem/GetItem through DynamoDBLocal 2.5.3.
4. **Differential:** same `MithraDataObject` graphs persisted via H2 Reladomo path vs DDB codec
   path; assert four timestamps + payload equality.

RED evidence for nullable trap (must appear in implementation log):

```text
expected isQuantityNull=true but was false
```

after a codec that wrote `N:0` for null.

---

## 9. Decisions summary

| Decision | Choice | Rejected | Why |
|---|---|---|---|
| Integers / long | `N` decimal string | `S` | Exact, queryable, within 38 digits |
| `BigDecimal` | `S` plain | bare `N` | Trailing-zero trim loses scale; 38-digit cap |
| `BigDecimal` meta overflow | Reject on encode | Silent coerce | Matches H2 DDL fidelity; uses `getPrecision`/`getScale` |
| `float`/`double` | `B` raw IEEE bits | `N` | Range/specials/−0; bit fidelity |
| `boolean` | `BOOL` | `N` 0/1 | Native; clear null distinction |
| Null primitives | DynamoDB `NULL` + `setValueNull` | omit or zero | Matches SQL NULL / Reladomo null bits |
| GSI key nulls | Omit attribute (sparse) | `NULL` type on GSI key | DynamoDB forbids null index keys |
| Timestamps | UTC ISO fixed fraction; infinity = configured sentinel ISO | TZ local / `INF` token / epoch without bias | Sortable SK + exact infinity |
| Names | Full attribute names by default; optional compress | Hash names; column names as default | Ops clarity; finder-aligned |
| Version attr | `_rd_v` | bare `_v` | Namespace; matches schema agent |
| Expressions | Always `ExpressionAttributeNames` | Escape when reserved | Future-proof |
| Oversized items | Reject by default | Chain split in v1 | Simplicity/correctness |
| Relationships | Separate items | Embed `M`/`L` | Temporal row versioning |
| `infinityIsNull` | Reject at config (schema) | Encode null thru | Breaks sortable SK / current-row queries |

### Cross-agent authority (schema vs codec)

The schema agent's provisional type table maps `BigDecimal`→`N`, `float`/`double`→`N`, and
null→omit. **Those mappings are incorrect for the fidelity charter** and must not be copied into
implementation. Schema owns PK/SK grammar and access-pattern indexes; this codec document owns
AttributeValue wire types for payload attributes. Where they conflict, **codec wins** for value
encoding; schema wins for key composition using this document's temporal string form.

---

## 10. OPEN QUESTIONS

1. **OPEN QUESTION:** Do any first-party object models require **nanosecond** as-of columns in SK
   range queries, or is millisecond SK sufficient with nanos only on payload?
2. **OPEN QUESTION:** For `java.sql.Date` decode, confirm H2 reference path's timezone behaviour
   under `MithraTestResource` so UTC civil-date encoding matches JDBC reads exactly
   (`timezoneConversion` / `MithraTimestamp` calendar path).
3. **OPEN QUESTION:** Is there a need for a dual-write `double` projection as `N` for rare numeric
   filter pushdown, or is residual filtering always acceptable?
4. **OPEN QUESTION:** Compression map storage — portal config only vs `_rd_cmap` metadata item per
   table for self-describing dumps.
5. **OPEN QUESTION:** Should `_rd_t` type discriminator be mandatory in single-table layouts only,
   or always written for ops clarity?
6. **OPEN QUESTION:** Reladomo XSD lists primitives + String/Date/Time/Timestamp/BigDecimal/byte[].
   `char` appears in this charter and in some models — confirm whether generator emits `char`
   attributes in 18.1.0 sample apps; if not, keep the mapping but mark as rarely exercised.

**Resolved this revision (no longer open):**

- `Time.withMillis` / `Time.withNanos` are the 18.1.0 public factories (javap-verified).
- `BigDecimal` out-of-meta precision/scale → **reject on encode** via
  `BigDecimalAttribute.getPrecision()` / `getScale()` (no silent coerce).

---

## 11. Implementation order (when coding starts)

1. `TimestampWireCodec` + jqwik properties (RED→GREEN).
2. Scalar encoders + nullable primitive tests.
3. `BigDecimal`/`double` fidelity tests.
4. `MithraItemCodecFactory` binding to real `Attribute[]`.
5. Item size estimator + reject policy.
6. DynamoDBLocal round-trip IT.
7. Plug into persister; run differential harness.

---

## Appendix A — DynamoDB numeric limits (reference)

- Precision: 38 digits.
- Positive range: 1E-130 … ≈9.99E+125.
- Negative range: ≈−9.99E+125 … −1E-130.
- Leading/trailing zeros trimmed.
- Empty string/binary allowed for **non-key** attributes.
- PK string ≤ 2048 bytes; SK string ≤ 1024 bytes; item ≤ 400 KB.

## Appendix B — Reladomo API anchors (javap-verified 18.1.0)

- `com.gs.fw.common.mithra.extractor.Extractor#isAttributeNull` / `#setValueNull`
- `com.gs.fw.common.mithra.extractor.IntExtractor#intValueOf` / `#setIntValue`
- `com.gs.fw.common.mithra.attribute.AttributeMetaData#isNullable` / `#mustTrimString` / `#getStringMaxLength`
- `com.gs.fw.common.mithra.attribute.AsOfAttribute#getInfinityDate` / `#getFromAttribute` / `#getToAttribute`
- `com.gs.fw.common.mithra.attribute.TimestampAttribute#MILLISECOND_PRECISION` / `#NANOSECOND_PRECISION` / `#getAsOfAttributeInfinity` / `#CONVERT_TO_UTC`
- `com.gs.fw.common.mithra.util.MithraTimestamp#UtcTimeZone` / `#createUtcTime`
- `com.gs.fw.common.mithra.MithraNullPrimitiveException`
- Structural precedent: `com.gs.fw.common.mithra.portal.PureMithraObjectPersister`

## Appendix B.1 — Minimal `CodecException` (Java 11)

```java
package com.reladynamo.ddb.codec;

public class CodecException extends RuntimeException {
    public CodecException(String message) {
        super(message);
    }

    public CodecException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

## Appendix C — Worked null+temporal encode pseudocode

```java
public Map<String, AttributeValue> encode(MithraDataObject data) {
    Map<String, AttributeValue> item = new HashMap<>(expectedSize);
    item.put("_rd_v", AttributeValue.builder().n(Integer.toString(schemaVersion)).build());
    for (int i = 0; i < encoders.length; i++) {
        encoders[i].put(data, item);
    }
    // pk/sk added by KeyCodec, not duplicated here unless codec owns them
    int size = estimator.estimate(item);
    if (size > MAX_ITEM_BYTES) {
        overflowPolicy.handle(data, item, size);
    }
    return item;
}
```

---

*End of design document.*
