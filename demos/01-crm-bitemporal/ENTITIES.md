# Demo 1 — CRM, bitemporal · entity model

**JDK 11.** ~44 entities. H2 first; the DynamoDB layer must later reproduce every result identically.

Package root: `com.reladynamo.demo.crm`

## Temporal flavours used

| Flavour | Reladomo shape | Used for |
|---|---|---|
| **BITEMPORAL** | `businessDate` (from/thru) + `processingDate` (in/out) | Facts that can be corrected retroactively and where "what did we believe on date X" is a real question |
| **AUDIT** | `processingDate` only (audit-only) | Immutable activity records — we never restate when a call happened, only when we recorded it |
| **PLAIN** | no `AsOfAttribute` | Reference/lookup data |

`infinityDate` for every `AsOfAttribute`: `9999-12-01 23:59:00.000`.
All timestamps UTC. Every PK is a `long` unless stated.

---

## A. Party and org (BITEMPORAL)

| # | Entity | Key attributes | Relationships |
|---|---|---|---|
| 1 | `Customer` | customerId PK, name, legalName, industryId, segmentCode, status, annualRevenue (BigDecimal), employeeCount int, ownerRepId, createdDate | → Industry, → SalesRep(owner), ←many Contact, ←many Opportunity, ←many Account |
| 2 | `Account` | accountId PK, customerId, accountNumber, accountType, openedDate, closedDate nullable, creditLimit BigDecimal, currency | → Customer |
| 3 | `Contact` | contactId PK, customerId, firstName, lastName, title, department, isPrimary boolean, preferredChannel | → Customer, ←many ContactEmail/ContactPhone |
| 4 | `Company` | companyId PK, name, duns, parentCompanyId nullable, countryCode | self-ref parent |
| 5 | `CustomerSegmentAssignment` | assignmentId PK, customerId, segmentCode, assignedReason | → Customer, → CustomerSegment |
| 6 | `TerritoryAssignment` | assignmentId PK, customerId, territoryId, repId, isPrimary boolean | → Customer, → Territory, → SalesRep |
| 7 | `CreditRating` | ratingId PK, customerId, ratingCode, scoreNumeric int, agency | → Customer |
| 8 | `ConsentRecord` | consentId PK, contactId, channel, granted boolean, lawfulBasis, sourceRef | → Contact |

## B. Addresses and channels (BITEMPORAL)

| # | Entity | Key attributes |
|---|---|---|
| 9 | `Address` | addressId PK, customerId, addressType, line1, line2 nullable, city, state, postalCode, countryCode |
| 10 | `ContactPhone` | phoneId PK, contactId, phoneType, e164Number, isVerified boolean |
| 11 | `ContactEmail` | emailId PK, contactId, emailAddress, isVerified boolean, bounceCount int |

## C. Pipeline and revenue (BITEMPORAL)

| # | Entity | Key attributes | Relationships |
|---|---|---|---|
| 12 | `Opportunity` | opportunityId PK, customerId, name, stageCode, amount BigDecimal, probability int, expectedCloseDate, ownerRepId | → Customer, → PipelineStage, → SalesRep |
| 13 | `Quote` | quoteId PK, opportunityId, quoteNumber, status, totalAmount BigDecimal, validUntil | → Opportunity |
| 14 | `QuoteLineItem` | lineId PK, quoteId, productId, quantity int, unitPrice BigDecimal, discountPct BigDecimal | → Quote, → Product |
| 15 | `Contract` | contractId PK, customerId, contractNumber, status, startDate, endDate, autoRenew boolean, tcv BigDecimal | → Customer |
| 16 | `Subscription` | subscriptionId PK, contractId, productId, seats int, mrr BigDecimal, billingCycle | → Contract, → Product |
| 17 | `PriceBookEntry` | entryId PK, priceBookId, productId, unitPrice BigDecimal, currency | → PriceBook, → Product |

## D. Activity (AUDIT — processingDate only)

| # | Entity | Key attributes |
|---|---|---|
| 18 | `Call` | callId PK, contactId, customerId, repId, direction, startTime, durationSeconds int, outcomeCode, notes |
| 19 | `EmailMessage` | messageId PK, contactId, repId, direction, subject, sentTime, openedTime nullable, clickedTime nullable |
| 20 | `Meeting` | meetingId PK, customerId, repId, subject, startTime, endTime, locationType, outcomeCode |
| 21 | `TaskItem` | taskId PK, customerId nullable, repId, subject, dueDate, status, priority |
| 22 | `Note` | noteId PK, entityType, entityId, repId, body, createdTime |
| 23 | `CaseComment` | commentId PK, caseId, authorRepId, body, createdTime, isPublic boolean |
| 24 | `Attachment` | attachmentId PK, entityType, entityId, fileName, mimeType, sizeBytes long, storageRef |

## E. Outreach and campaigns

| # | Entity | Flavour | Key attributes |
|---|---|---|---|
| 25 | `Campaign` | BITEMPORAL | campaignId PK, name, campaignType, status, budget BigDecimal, startDate, endDate, ownerRepId |
| 26 | `CampaignMember` | BITEMPORAL | memberId PK, campaignId, contactId, memberStatus, respondedDate nullable |
| 27 | `OutreachSequence` | BITEMPORAL | sequenceId PK, name, isActive boolean, ownerRepId |
| 28 | `OutreachStep` | BITEMPORAL | stepId PK, sequenceId, stepNumber int, channel, delayDays int, templateId |
| 29 | `OutreachEnrollment` | AUDIT | enrollmentId PK, sequenceId, contactId, enrolledTime, currentStepNumber int, status |
| 30 | `MessageTemplate` | BITEMPORAL | templateId PK, name, channel, subject, body |

## F. Support

| # | Entity | Flavour | Key attributes |
|---|---|---|---|
| 31 | `SupportCase` | BITEMPORAL | caseId PK, customerId, contactId, subject, status, priority, openedTime, closedTime nullable, assignedRepId |
| 32 | `CaseEscalation` | AUDIT | escalationId PK, caseId, escalatedToRepId, reason, escalatedTime |
| 33 | `SlaPolicy` | BITEMPORAL | policyId PK, name, priority, firstResponseMins int, resolutionMins int |

## G. Billing (BITEMPORAL unless noted)

| # | Entity | Flavour | Key attributes |
|---|---|---|---|
| 34 | `Invoice` | BITEMPORAL | invoiceId PK, customerId, contractId, invoiceNumber, issueDate, dueDate, totalAmount BigDecimal, status |
| 35 | `InvoiceLine` | BITEMPORAL | lineId PK, invoiceId, productId, description, quantity int, unitPrice BigDecimal |
| 36 | `Payment` | AUDIT | paymentId PK, invoiceId, amount BigDecimal, paidTime, method, referenceCode |

## H. People and structure

| # | Entity | Flavour | Key attributes |
|---|---|---|---|
| 37 | `SalesRep` | BITEMPORAL | repId PK, firstName, lastName, email, teamId, managerRepId nullable, hireDate, terminationDate nullable, quotaAmount BigDecimal |
| 38 | `Team` | BITEMPORAL | teamId PK, name, regionCode, managerRepId |
| 39 | `Territory` | BITEMPORAL | territoryId PK, name, regionCode, countryCode |

## I. Reference data (PLAIN — no AsOfAttribute)

| # | Entity | Key attributes |
|---|---|---|
| 40 | `Industry` | industryCode PK (String), name, sicCode |
| 41 | `CustomerSegment` | segmentCode PK (String), name, description |
| 42 | `PipelineStage` | stageCode PK (String), name, sortOrder int, isClosed boolean, isWon boolean |
| 43 | `LeadSource` | sourceCode PK (String), name, channel |
| 44 | `Product` | productId PK, sku, name, productFamily, isActive boolean |
| 45 | `PriceBook` | priceBookId PK, name, currency, isStandard boolean |
| 46 | `Tag` | tagId PK, name, colorHex |

---

## Required demonstrations (these are the point of the demo)

1. **Retroactive address correction** — a `Customer` moved on 2025-03-01 but we only learned on 2025-06-15.
   Show the address "as of business date 2025-04-01" differs depending on whether you ask
   *as we knew it on 2025-04-02* versus *as we know it today*.
2. **Territory reassignment effective in the past**, and the commission recalculation it implies.
3. **Opportunity amount restated** after close — the originally reported pipeline must remain
   reproducible for an audit.
4. **Consent withdrawn effective a past date** (GDPR) — outreach that was lawful when sent must still
   show as lawful *as known then*, while today's view shows consent absent.
5. **Terminate / expire** a `Subscription` and a `SalesRep` (termination date), then query both before
   and after.
6. **`updateUntil` / `incrementUntil`** on `PriceBookEntry` — a price valid only for a bounded window.
7. **Full history reconstruction** for one `Customer` — every processing-date snapshot, printed.
