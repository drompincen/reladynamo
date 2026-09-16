# Demo 2 — Pet store, unitemporal · entity model

**JDK 21.** ~22 entities. H2 first; DynamoDB layer must reproduce results identically.

Package root: `com.reladynamo.demo.petstore`

## Temporal flavour — unitemporal throughout

**One `AsOfAttribute` per entity, not two.** This demo exists to prove the adapter handles the
single-axis case correctly, and to contrast with Demo 1.

| Flavour | Reladomo shape | Used for |
|---|---|---|
| **BUSINESS** | `businessDate` only, non-audited (`GenericNonAuditedTemporalDirector`) | Things with a real-world validity window and no need to retain what we used to believe: price, stock level, staffing |
| **AUDIT** | `processingDate` only (`AuditOnlyTemporalDirector`) | Records where we keep an audit trail of our own edits |
| **PLAIN** | none | Lookup data |

`infinityDate`: `9999-12-01 23:59:00.000`. UTC. PKs are `long` unless stated.

**Deliberate contrast with Demo 1:** the same *kind* of correction that produces a preserved
prior-belief row in the CRM demo produces an in-place split here. The README must show both and
explain why, because this is the single most misunderstood thing about Reladomo.

---

## A. Animals (BUSINESS)

| # | Entity | Key attributes | Relationships |
|---|---|---|---|
| 1 | `Pet` | petId PK, name, speciesId, breedId nullable, dateOfBirth, sex, colorCode, microchipId nullable, status, kennelId nullable, listPrice BigDecimal | → Species, → Breed, → Kennel |
| 2 | `Species` | speciesId PK, commonName, scientificName, careLevel | ←many Breed |
| 3 | `Breed` | breedId PK, speciesId, name, sizeCategory, typicalLifespanYears int | → Species |
| 4 | `Kennel` | kennelId PK, storeId, label, capacity int, zone | → Store |
| 5 | `FeedingSchedule` | scheduleId PK, petId, feedTimeOfDay, productId, quantityGrams int | → Pet, → Product |

## B. Health (AUDIT)

| # | Entity | Key attributes |
|---|---|---|
| 6 | `VeterinaryVisit` | visitId PK, petId, vetId, visitTime, reason, diagnosis, followUpDate nullable |
| 7 | `Vaccination` | vaccinationId PK, petId, vaccineCode, administeredTime, batchNumber, nextDueDate |
| 8 | `GroomingAppointment` | appointmentId PK, petId, groomerId, scheduledTime, serviceCode, status |

## C. Commerce (BUSINESS unless noted)

| # | Entity | Flavour | Key attributes |
|---|---|---|---|
| 9 | `Product` | BUSINESS | productId PK, sku, name, categoryId, unitPrice BigDecimal, isActive boolean |
| 10 | `ProductCategory` | PLAIN | categoryId PK, name, parentCategoryId nullable |
| 11 | `StockLevel` | BUSINESS | stockId PK, storeId, productId, quantityOnHand int, reorderPoint int |
| 12 | `SalesOrder` | AUDIT | orderId PK, customerId, storeId, orderTime, status, totalAmount BigDecimal |
| 13 | `SalesOrderLine` | AUDIT | lineId PK, orderId, productId nullable, petId nullable, quantity int, unitPrice BigDecimal |
| 14 | `Payment` | AUDIT | paymentId PK, orderId, amount BigDecimal, paidTime, method |
| 15 | `PurchaseOrder` | AUDIT | poId PK, supplierId, orderedTime, expectedDate, status |
| 16 | `PurchaseOrderLine` | AUDIT | poLineId PK, poId, productId, quantity int, unitCost BigDecimal |
| 17 | `Shipment` | AUDIT | shipmentId PK, poId, shippedTime, receivedTime nullable, carrier, trackingRef |

## D. People and places

| # | Entity | Flavour | Key attributes |
|---|---|---|---|
| 18 | `PetOwner` | BUSINESS | ownerId PK, firstName, lastName, email, phone, addressLine1, city, postalCode |
| 19 | `Adoption` | AUDIT | adoptionId PK, petId, ownerId, adoptedTime, feeAmount BigDecimal, returnedTime nullable |
| 20 | `Employee` | BUSINESS | employeeId PK, storeId, firstName, lastName, role, hireDate, hourlyRate BigDecimal |
| 21 | `Store` | BUSINESS | storeId PK, name, addressLine1, city, postalCode, phone, openedDate |
| 22 | `Supplier` | BUSINESS | supplierId PK, name, contactEmail, leadTimeDays int |

---

## Required demonstrations

1. **Price change with a business-date window** — `Product.unitPrice` changes effective 2025-07-01;
   query the price as of June and as of August and show the split segment.
2. **Stock level over time** — `StockLevel` adjusted repeatedly; show the value on any past business date.
3. **Non-audited correction** — fix a wrong price *without* creating a prior-belief row, and show
   explicitly that the old value is **gone**, unlike Demo 1. This contrast is the teaching point.
4. **Audit-only trail** — amend a `SalesOrder` and show every processing-time version retained.
5. **Terminate** a `Pet` listing on adoption, and an `Employee` on departure.
6. **Unitemporal as-of query** — one date parameter, not two. Show the API difference plainly.
