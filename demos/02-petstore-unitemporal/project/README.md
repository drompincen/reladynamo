# Pet store unitemporal demo

A standalone Reladomo 18.1.0 reference implementation of a pet-store domain on **H2**,
compiled for **JDK 21**. Every entity is **unitemporal**: exactly one `AsOfAttribute`.

This is the counterpart to the bitemporal CRM demo. Same Reladomo, same infinity
sentinel (`9999-12-01 23:59:00.000` UTC), different temporal shape.

| Flavour | Reladomo director | Axis | Used for |
|---|---|---|---|
| **BUSINESS** | `GenericNonAuditedTemporalDirector` | `businessDate` (`FROM_Z` / `THRU_Z`) | Price, stock, staffing, listings — real-world validity, no prior-belief trail |
| **AUDIT** | `AuditOnlyTemporalDirector` | `processingDate` (`IN_Z` / `OUT_Z`) | Orders, payments, vet records — keep every edit |
| **PLAIN** | none | — | `ProductCategory` lookup |

**The teaching point:** Reladomo does **not** always keep history. A non-audited
correction of `Product.unitPrice` overwrites the business-date segment. The old
value is gone from the finder *and* from H2. The same kind of correction in the
bitemporal CRM demo preserves a closed processing-time rectangle. Engineers who
assume “Reladomo always keeps history” will lose data.

A unitemporal as-of query takes **one** date, not two:

```java
ProductFinder.findByPrimaryKey(productId, businessDate);   // BUSINESS
SalesOrderFinder.findByPrimaryKey(orderId, processingDate); // AUDIT

// bitemporal CRM contrast:
// CustomerFinder.findByPrimaryKey(customerId, businessDate, processingDate);
```

There is no `ProductFinder.processingDate()`.

## Domain (22 entities)

**Animals (BUSINESS):** `Pet`, `Species`, `Breed`, `Kennel`, `FeedingSchedule`

**Health (AUDIT):** `VeterinaryVisit`, `Vaccination`, `GroomingAppointment`

**Commerce:** `Product` (BUSINESS), `ProductCategory` (PLAIN), `StockLevel` (BUSINESS),
`SalesOrder` / `SalesOrderLine` / `Payment` (AUDIT),
`PurchaseOrder` / `PurchaseOrderLine` / `Shipment` (AUDIT)

**People and places:** `PetOwner` (BUSINESS), `Adoption` (AUDIT),
`Employee` (BUSINESS), `Store` (BUSINESS), `Supplier` (BUSINESS)

Seed is a compact deterministic catalog (~80 physical rows after the six
demonstrations mutate dedicated demo keys).

## How to run

JDK 21, Maven 3.9+. From this directory:

```bash
mvn -q clean test
mvn -q exec:java
```

`exec:java` runs `com.reladynamo.demo.petstore.PetstoreApp`. Reladomo sources are
generated in `generate-sources` via `reladomogen` (`maven-antrun-plugin`), not the
unavailable `reladomo-mithra-plugin`.

Pinned versions: Reladomo `18.1.0`, H2 `2.1.210`, JUnit 5.11.4, AssertJ 3.27.0.

## What each demonstration proves

Tests in `PetstoreDemonstrationsTest` assert the same facts the printer shows.
`UnitemporalDirectorTest` asserts every entity’s director/as-of shape.

### 1. Price change with a business-date window

`Product` sku `PRC-100` is seeded at `10.00` from 2025-01-01. A setter at
business date 2025-07-01 splits the segment. June still sees `10.00`; August
sees `12.50`. Physical rows have `FROM_Z` / `THRU_Z` only — no `IN_Z` / `OUT_Z`.

### 2. Stock level over time

`StockLevel` 100 is restated on 2025-03-01, 2025-05-01, and 2025-09-01. Any past
business date returns the quantity that was in effect then. Four physical
segments, no processing-time copies.

### 3. Non-audited correction destroys the prior value

This is the demonstration that matters.

Sku `COR-101` is seeded at the **wrong** price `9.99`. The correction is applied
as of the original from-date (`2025-01-01`) to `14.99`.

`GenericNonAuditedTemporalDirector` rewrites the open segment in place. There is
no processing-date axis, so Reladomo has nowhere to park the prior belief.

The test then:

- queries every `equalsEdgePoint()` business-date slice — none is `9.99`
- reads the `PRODUCT` table with raw JDBC, bypassing Reladomo — one row, `14.99`
- asserts `9.99` is absent from H2

The old value is **genuinely unrecoverable**, not hidden behind an as-of query.
In the bitemporal CRM demo the same kind of correction would close the old
processing rectangle (`OUT_Z = now`) and insert a new current-belief row.

### 4. Audit-only trail

`SalesOrder` 100 is amended twice (`OPEN` → `PAID` → `FULFILLED`) at
deterministic processing times. `AuditOnlyTemporalDirector` retains every
version. `equalsEdgePoint()` returns three rows; physical `SALES_ORDER` has
`IN_Z` / `OUT_Z` and **no** business-date columns.

### 5. Terminate

Pet `Wicket` is terminated on adoption (2025-08-15). The listing is visible on
2025-08-14 and gone on the terminate date (half-open: `from <= asOf < thru`).
Employee Faraday is terminated on 2025-10-01. `terminate()` shortens `THRU_Z`;
it does not physically delete the row.

### 6. Unitemporal as-of query takes one date

Generated `findByPrimaryKey` on both BUSINESS and AUDIT objects takes
`(long id, Timestamp asOf)` — one date. Each finder has exactly one
`AsOfAttribute`. BUSINESS as-of is `businessDate` (`isProcessingDate == false`);
AUDIT as-of is `processingDate` (`isProcessingDate == true`).

## Printed output (`mvn -q exec:java`)

```
=== Pet store unitemporal demo (Reladomo 18.1.0 / H2 / JDK 21) ===

Seeded + demonstration rows in H2: 80
Infinity sentinel: 9999-12-01 23:59:00

--- 1. Price change with a business-date window ---
ProductFinder.findByPrimaryKey(id, businessDate)  // ONE date, not two
  as of 2025-06-15  unitPrice = 10.00
  as of 2025-08-15  unitPrice = 12.50

Physical PRODUCT rows (FROM_Z / THRU_Z only -- no IN_Z / OUT_Z):
  FROM_Z                  THRU_Z                  UNIT_PRICE
  2025-01-01 00:00:00     2025-07-01 00:00:00     10.00
  2025-07-01 00:00:00     9999-12-01 23:59:00     12.50

June sees the pre-change segment; August sees the post-change segment.

--- 2. Stock level over time ---
  as of 2025-03-15  quantityOnHand = 55
  as of 2025-06-15  quantityOnHand = 28
  as of 2025-10-01  quantityOnHand = 70

Physical STOCK_LEVEL rows:
  FROM_Z                  THRU_Z                  QTY
  2025-01-01 00:00:00     2025-03-01 00:00:00     40
  2025-03-01 00:00:00     2025-05-01 00:00:00     55
  2025-05-01 00:00:00     2025-09-01 00:00:00     28
  2025-09-01 00:00:00     9999-12-01 23:59:00     70


--- 3. Non-audited correction DESTROYS the prior value ---
Seeded unitPrice = 9.99 as of 2025-01-01 (a wrong price).
Corrected as of the same business date to 14.99.
GenericNonAuditedTemporalDirector overwrites the segment. There is no
processingDate axis, so Reladomo cannot retain a prior-belief row.

  finder as of 2025-06-15  unitPrice = 14.99
  equalsEdgePoint() slice count = 1 (every business-date version)
    [2025-01-01 00:00:00, 9999-12-01 23:59:00)  unitPrice = 14.99

Physical PRODUCT rows for the corrected sku:
  FROM_Z                  THRU_Z                  UNIT_PRICE
  2025-01-01 00:00:00     9999-12-01 23:59:00     14.99

Old value 9.99 present in H2? false

THIS IS THE TEACHING POINT.
Engineers routinely assume Reladomo always keeps history. It does not.
The same kind of correction in the bitemporal CRM demo preserves the
prior belief as a closed processing-time rectangle. Here the old price
is genuinely unrecoverable -- not hidden, gone.

--- 4. Audit-only trail (SalesOrder, processingDate only) ---
  as of processingDate=infinity     status = FULFILLED  total = 54.95
  as of processingDate=2025-06-01   status = PAID  total = 54.95
  as of processingDate=2025-01-15   status = OPEN  total = 49.95

  equalsEdgePoint() retained 3 processing-time versions:
    [2025-01-15 09:00:00, 2025-06-01 10:00:00)  status=OPEN       total=49.95
    [2025-06-01 10:00:00, 2025-07-01 10:00:00)  status=PAID       total=54.95
    [2025-07-01 10:00:00, 9999-12-01 23:59:00)  status=FULFILLED  total=54.95

Physical SALES_ORDER rows (IN_Z / OUT_Z -- no business-date columns):
  IN_Z                    OUT_Z                   STATUS     TOTAL
  2025-01-15 09:00:00     2025-06-01 10:00:00     OPEN       49.95
  2025-06-01 10:00:00     2025-07-01 10:00:00     PAID       54.95
  2025-07-01 10:00:00     9999-12-01 23:59:00     FULFILLED  54.95


--- 5. Terminate a Pet listing and an Employee ---
  Pet Wicket as of 2025-08-14 (day before adoption) = Wicket / AVAILABLE
  Pet Wicket as of 2025-08-15 (adoption / terminate date) = <not listed>
  Pet Wicket as of 2025-09-01 = <not listed>
Physical PET rows:
  FROM_Z                  THRU_Z                  STATUS   NAME
  2025-01-01 00:00:00     2025-08-15 00:00:00     AVAILABLE Wicket

  Employee Faraday as of 2025-09-30 = Faraday / CLERK
  Employee Faraday as of 2025-10-01 (departure) = <not employed>
Physical EMPLOYEE rows:
  FROM_Z                  THRU_Z                  ROLE
  2025-01-01 00:00:00     2025-10-01 00:00:00     CLERK


--- 6. Unitemporal as-of query takes ONE date, not two ---
Generated finder signatures (this project):
  ProductFinder.findByPrimaryKey(long productId, Timestamp businessDate)
  SalesOrderFinder.findByPrimaryKey(long orderId, Timestamp processingDate)

Bitemporal CRM demo (contrast):
  CustomerFinder.findByPrimaryKey(long customerId, Timestamp businessDate, Timestamp processingDate)

Query construction is the same story:
  ProductFinder.productId().eq(id).and(ProductFinder.businessDate().eq(oneDate))
  // there is no ProductFinder.processingDate() -- the model has one AsOfAttribute.

As-of is half-open: from <= asOf < thru. Infinity is 9999-12-01 23:59:00.000 UTC.
```

## Layout

```
src/main/resources/reladomo/   MithraObject XML, MithraRuntime.xml, class list
src/main/resources/h2/         schema.sql (logical PK + FROM_Z or IN_Z unique)
src/main/java/.../domain/      hand-written Reladomo concrete / List / DatabaseObject
src/main/java/.../runtime/     H2 connection manager, bootstrap, harness
src/main/java/.../seed/        deterministic catalog
src/main/java/.../store/       raw JDBC row dumps used by tests
src/test/java/...              JUnit 5 + AssertJ
target/generated-sources/      *Abstract, *Finder, *Data (reladomogen)
```
