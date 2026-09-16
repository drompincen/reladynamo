#!/usr/bin/env python3
"""Generate Reladomo object XML, class list, runtime configs, and H2 DDL."""
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PKG = "com.reladynamo.demo.crm.domain"
INF = "[com.reladynamo.demo.crm.util.InfinityTimestamp.getInfinityDate()]"
MODELS = ROOT / "src" / "main" / "resources" / "reladomo" / "models"
MAIN_RT = ROOT / "src" / "main" / "resources" / "reladomo"
TEST_RT = ROOT / "src" / "test" / "resources" / "reladomo"
H2_DIR = ROOT / "src" / "main" / "resources" / "h2"

BITEMPORAL = "BITEMPORAL"
AUDIT = "AUDIT"
PLAIN = "PLAIN"

# attr: (name, javaType, extra_dict)
# extra: pk, nullable, maxLength, precision, scale

ENTITIES = []


def entity(name, flavour, table, attrs, rels=None):
    ENTITIES.append(
        {
            "name": name,
            "flavour": flavour,
            "table": table,
            "attrs": attrs,
            "rels": rels or [],
        }
    )


def A(name, java_type, **kw):
    return (name, java_type, kw)


def R(name, related, card, reverse, join):
    return {
        "name": name,
        "related": related,
        "card": card,
        "reverse": reverse,
        "join": join,
    }


def col(attr_name):
    out = []
    for ch in attr_name:
        if ch.isupper() and out:
            out.append("_")
        out.append(ch.upper())
    return "".join(out)


# --- A. Party and org (BITEMPORAL) ---
entity(
    "Customer",
    BITEMPORAL,
    "CUSTOMER",
    [
        A("customerId", "long", pk=True),
        A("name", "String", maxLength=128),
        A("legalName", "String", maxLength=256),
        A("industryId", "String", maxLength=32),
        A("segmentCode", "String", maxLength=32),
        A("status", "String", maxLength=32),
        A("annualRevenue", "BigDecimal", precision=18, scale=2),
        A("employeeCount", "int"),
        A("ownerRepId", "long"),
        A("createdDate", "Timestamp"),
    ],
    [
        R("industry", "Industry", "many-to-one", "customers", "Industry.industryCode = this.industryId"),
        R("ownerRep", "SalesRep", "many-to-one", "ownedCustomers", "SalesRep.repId = this.ownerRepId"),
    ],
)
entity(
    "Account",
    BITEMPORAL,
    "ACCOUNT",
    [
        A("accountId", "long", pk=True),
        A("customerId", "long"),
        A("accountNumber", "String", maxLength=64),
        A("accountType", "String", maxLength=32),
        A("openedDate", "Timestamp"),
        A("closedDate", "Timestamp", nullable=True),
        A("creditLimit", "BigDecimal", precision=18, scale=2),
        A("currency", "String", maxLength=3),
    ],
    [R("customer", "Customer", "many-to-one", "accounts", "Customer.customerId = this.customerId")],
)
entity(
    "Contact",
    BITEMPORAL,
    "CONTACT",
    [
        A("contactId", "long", pk=True),
        A("customerId", "long"),
        A("firstName", "String", maxLength=64),
        A("lastName", "String", maxLength=64),
        A("title", "String", maxLength=128),
        A("department", "String", maxLength=64),
        A("isPrimary", "boolean"),
        A("preferredChannel", "String", maxLength=32),
    ],
    [R("customer", "Customer", "many-to-one", "contacts", "Customer.customerId = this.customerId")],
)
entity(
    "Company",
    BITEMPORAL,
    "COMPANY",
    [
        A("companyId", "long", pk=True),
        A("name", "String", maxLength=128),
        A("duns", "String", maxLength=32),
        A("parentCompanyId", "long", nullable=True),
        A("countryCode", "String", maxLength=2),
    ],
    [
        R(
            "parentCompany",
            "Company",
            "many-to-one",
            "childCompanies",
            "Company.companyId = this.parentCompanyId",
        )
    ],
)
entity(
    "CustomerSegmentAssignment",
    BITEMPORAL,
    "CUSTOMER_SEGMENT_ASSIGNMENT",
    [
        A("assignmentId", "long", pk=True),
        A("customerId", "long"),
        A("segmentCode", "String", maxLength=32),
        A("assignedReason", "String", maxLength=256),
    ],
    [
        R("customer", "Customer", "many-to-one", "segmentAssignments", "Customer.customerId = this.customerId"),
        R(
            "segment",
            "CustomerSegment",
            "many-to-one",
            "assignments",
            "CustomerSegment.segmentCode = this.segmentCode",
        ),
    ],
)
entity(
    "TerritoryAssignment",
    BITEMPORAL,
    "TERRITORY_ASSIGNMENT",
    [
        A("assignmentId", "long", pk=True),
        A("customerId", "long"),
        A("territoryId", "long"),
        A("repId", "long"),
        A("isPrimary", "boolean"),
    ],
    [
        R("customer", "Customer", "many-to-one", "territoryAssignments", "Customer.customerId = this.customerId"),
        R("territory", "Territory", "many-to-one", "assignments", "Territory.territoryId = this.territoryId"),
        R("salesRep", "SalesRep", "many-to-one", "territoryAssignments", "SalesRep.repId = this.repId"),
    ],
)
entity(
    "CreditRating",
    BITEMPORAL,
    "CREDIT_RATING",
    [
        A("ratingId", "long", pk=True),
        A("customerId", "long"),
        A("ratingCode", "String", maxLength=16),
        A("scoreNumeric", "int"),
        A("agency", "String", maxLength=64),
    ],
    [R("customer", "Customer", "many-to-one", "creditRatings", "Customer.customerId = this.customerId")],
)
entity(
    "ConsentRecord",
    BITEMPORAL,
    "CONSENT_RECORD",
    [
        A("consentId", "long", pk=True),
        A("contactId", "long"),
        A("channel", "String", maxLength=32),
        A("granted", "boolean"),
        A("lawfulBasis", "String", maxLength=64),
        A("sourceRef", "String", maxLength=128),
    ],
    [R("contact", "Contact", "many-to-one", "consentRecords", "Contact.contactId = this.contactId")],
)

# --- B. Addresses and channels ---
entity(
    "Address",
    BITEMPORAL,
    "ADDRESS",
    [
        A("addressId", "long", pk=True),
        A("customerId", "long"),
        A("addressType", "String", maxLength=32),
        A("line1", "String", maxLength=256),
        A("line2", "String", maxLength=256, nullable=True),
        A("city", "String", maxLength=64),
        A("state", "String", maxLength=64),
        A("postalCode", "String", maxLength=16),
        A("countryCode", "String", maxLength=2),
    ],
    [R("customer", "Customer", "many-to-one", "addresses", "Customer.customerId = this.customerId")],
)
entity(
    "ContactPhone",
    BITEMPORAL,
    "CONTACT_PHONE",
    [
        A("phoneId", "long", pk=True),
        A("contactId", "long"),
        A("phoneType", "String", maxLength=32),
        A("e164Number", "String", maxLength=32),
        A("isVerified", "boolean"),
    ],
    [R("contact", "Contact", "many-to-one", "phones", "Contact.contactId = this.contactId")],
)
entity(
    "ContactEmail",
    BITEMPORAL,
    "CONTACT_EMAIL",
    [
        A("emailId", "long", pk=True),
        A("contactId", "long"),
        A("emailAddress", "String", maxLength=256),
        A("isVerified", "boolean"),
        A("bounceCount", "int"),
    ],
    [R("contact", "Contact", "many-to-one", "emails", "Contact.contactId = this.contactId")],
)

# --- C. Pipeline and revenue ---
entity(
    "Opportunity",
    BITEMPORAL,
    "OPPORTUNITY",
    [
        A("opportunityId", "long", pk=True),
        A("customerId", "long"),
        A("name", "String", maxLength=128),
        A("stageCode", "String", maxLength=32),
        A("amount", "BigDecimal", precision=18, scale=2),
        A("probability", "int"),
        A("expectedCloseDate", "Timestamp"),
        A("ownerRepId", "long"),
    ],
    [
        R("customer", "Customer", "many-to-one", "opportunities", "Customer.customerId = this.customerId"),
        R("stage", "PipelineStage", "many-to-one", "opportunities", "PipelineStage.stageCode = this.stageCode"),
        R("ownerRep", "SalesRep", "many-to-one", "opportunities", "SalesRep.repId = this.ownerRepId"),
    ],
)
entity(
    "Quote",
    BITEMPORAL,
    "QUOTE",
    [
        A("quoteId", "long", pk=True),
        A("opportunityId", "long"),
        A("quoteNumber", "String", maxLength=64),
        A("status", "String", maxLength=32),
        A("totalAmount", "BigDecimal", precision=18, scale=2),
        A("validUntil", "Timestamp"),
    ],
    [R("opportunity", "Opportunity", "many-to-one", "quotes", "Opportunity.opportunityId = this.opportunityId")],
)
entity(
    "QuoteLineItem",
    BITEMPORAL,
    "QUOTE_LINE_ITEM",
    [
        A("lineId", "long", pk=True),
        A("quoteId", "long"),
        A("productId", "long"),
        A("quantity", "int"),
        A("unitPrice", "BigDecimal", precision=18, scale=2),
        A("discountPct", "BigDecimal", precision=5, scale=2),
    ],
    [
        R("quote", "Quote", "many-to-one", "lineItems", "Quote.quoteId = this.quoteId"),
        R("product", "Product", "many-to-one", "quoteLineItems", "Product.productId = this.productId"),
    ],
)
entity(
    "Contract",
    BITEMPORAL,
    "CONTRACT",
    [
        A("contractId", "long", pk=True),
        A("customerId", "long"),
        A("contractNumber", "String", maxLength=64),
        A("status", "String", maxLength=32),
        A("startDate", "Timestamp"),
        A("endDate", "Timestamp"),
        A("autoRenew", "boolean"),
        A("tcv", "BigDecimal", precision=18, scale=2),
    ],
    [R("customer", "Customer", "many-to-one", "contracts", "Customer.customerId = this.customerId")],
)
entity(
    "Subscription",
    BITEMPORAL,
    "SUBSCRIPTION",
    [
        A("subscriptionId", "long", pk=True),
        A("contractId", "long"),
        A("productId", "long"),
        A("seats", "int"),
        A("mrr", "BigDecimal", precision=18, scale=2),
        A("billingCycle", "String", maxLength=32),
    ],
    [
        R("contract", "Contract", "many-to-one", "subscriptions", "Contract.contractId = this.contractId"),
        R("product", "Product", "many-to-one", "subscriptions", "Product.productId = this.productId"),
    ],
)
entity(
    "PriceBookEntry",
    BITEMPORAL,
    "PRICE_BOOK_ENTRY",
    [
        A("entryId", "long", pk=True),
        A("priceBookId", "long"),
        A("productId", "long"),
        A("unitPrice", "BigDecimal", precision=18, scale=2),
        A("currency", "String", maxLength=3),
    ],
    [
        R("priceBook", "PriceBook", "many-to-one", "entries", "PriceBook.priceBookId = this.priceBookId"),
        R("product", "Product", "many-to-one", "priceBookEntries", "Product.productId = this.productId"),
    ],
)

# --- D. Activity (AUDIT) ---
entity(
    "Call",
    AUDIT,
    "CALL_RECORD",
    [
        A("callId", "long", pk=True),
        A("contactId", "long"),
        A("customerId", "long"),
        A("repId", "long"),
        A("direction", "String", maxLength=16),
        A("startTime", "Timestamp"),
        A("durationSeconds", "int"),
        A("outcomeCode", "String", maxLength=32),
        A("notes", "String", maxLength=4000),
    ],
    [
        R("contact", "Contact", "many-to-one", "calls", "Contact.contactId = this.contactId"),
        R("customer", "Customer", "many-to-one", "calls", "Customer.customerId = this.customerId"),
        R("salesRep", "SalesRep", "many-to-one", "calls", "SalesRep.repId = this.repId"),
    ],
)
entity(
    "EmailMessage",
    AUDIT,
    "EMAIL_MESSAGE",
    [
        A("messageId", "long", pk=True),
        A("contactId", "long"),
        A("repId", "long"),
        A("direction", "String", maxLength=16),
        A("subject", "String", maxLength=256),
        A("sentTime", "Timestamp"),
        A("openedTime", "Timestamp", nullable=True),
        A("clickedTime", "Timestamp", nullable=True),
    ],
    [
        R("contact", "Contact", "many-to-one", "emailMessages", "Contact.contactId = this.contactId"),
        R("salesRep", "SalesRep", "many-to-one", "emailMessages", "SalesRep.repId = this.repId"),
    ],
)
entity(
    "Meeting",
    AUDIT,
    "MEETING",
    [
        A("meetingId", "long", pk=True),
        A("customerId", "long"),
        A("repId", "long"),
        A("subject", "String", maxLength=256),
        A("startTime", "Timestamp"),
        A("endTime", "Timestamp"),
        A("locationType", "String", maxLength=32),
        A("outcomeCode", "String", maxLength=32),
    ],
    [
        R("customer", "Customer", "many-to-one", "meetings", "Customer.customerId = this.customerId"),
        R("salesRep", "SalesRep", "many-to-one", "meetings", "SalesRep.repId = this.repId"),
    ],
)
entity(
    "TaskItem",
    AUDIT,
    "TASK_ITEM",
    [
        A("taskId", "long", pk=True),
        A("customerId", "long", nullable=True),
        A("repId", "long"),
        A("subject", "String", maxLength=256),
        A("dueDate", "Timestamp"),
        A("status", "String", maxLength=32),
        A("priority", "String", maxLength=16),
    ],
    [
        R("customer", "Customer", "many-to-one", "tasks", "Customer.customerId = this.customerId"),
        R("salesRep", "SalesRep", "many-to-one", "tasks", "SalesRep.repId = this.repId"),
    ],
)
entity(
    "Note",
    AUDIT,
    "NOTE_RECORD",
    [
        A("noteId", "long", pk=True),
        A("entityType", "String", maxLength=64),
        A("entityId", "long"),
        A("repId", "long"),
        A("body", "String", maxLength=4000),
        A("createdTime", "Timestamp"),
    ],
    [R("salesRep", "SalesRep", "many-to-one", "notes", "SalesRep.repId = this.repId")],
)
entity(
    "CaseComment",
    AUDIT,
    "CASE_COMMENT",
    [
        A("commentId", "long", pk=True),
        A("caseId", "long"),
        A("authorRepId", "long"),
        A("body", "String", maxLength=4000),
        A("createdTime", "Timestamp"),
        A("isPublic", "boolean"),
    ],
    [
        R("supportCase", "SupportCase", "many-to-one", "comments", "SupportCase.caseId = this.caseId"),
        R("authorRep", "SalesRep", "many-to-one", "caseComments", "SalesRep.repId = this.authorRepId"),
    ],
)
entity(
    "Attachment",
    AUDIT,
    "ATTACHMENT",
    [
        A("attachmentId", "long", pk=True),
        A("entityType", "String", maxLength=64),
        A("entityId", "long"),
        A("fileName", "String", maxLength=256),
        A("mimeType", "String", maxLength=128),
        A("sizeBytes", "long"),
        A("storageRef", "String", maxLength=256),
    ],
    [],
)

# --- E. Outreach ---
entity(
    "Campaign",
    BITEMPORAL,
    "CAMPAIGN",
    [
        A("campaignId", "long", pk=True),
        A("name", "String", maxLength=128),
        A("campaignType", "String", maxLength=32),
        A("status", "String", maxLength=32),
        A("budget", "BigDecimal", precision=18, scale=2),
        A("startDate", "Timestamp"),
        A("endDate", "Timestamp"),
        A("ownerRepId", "long"),
    ],
    [R("ownerRep", "SalesRep", "many-to-one", "ownedCampaigns", "SalesRep.repId = this.ownerRepId")],
)
entity(
    "CampaignMember",
    BITEMPORAL,
    "CAMPAIGN_MEMBER",
    [
        A("memberId", "long", pk=True),
        A("campaignId", "long"),
        A("contactId", "long"),
        A("memberStatus", "String", maxLength=32),
        A("respondedDate", "Timestamp", nullable=True),
    ],
    [
        R("campaign", "Campaign", "many-to-one", "members", "Campaign.campaignId = this.campaignId"),
        R("contact", "Contact", "many-to-one", "campaignMemberships", "Contact.contactId = this.contactId"),
    ],
)
entity(
    "OutreachSequence",
    BITEMPORAL,
    "OUTREACH_SEQUENCE",
    [
        A("sequenceId", "long", pk=True),
        A("name", "String", maxLength=128),
        A("isActive", "boolean"),
        A("ownerRepId", "long"),
    ],
    [R("ownerRep", "SalesRep", "many-to-one", "ownedSequences", "SalesRep.repId = this.ownerRepId")],
)
entity(
    "OutreachStep",
    BITEMPORAL,
    "OUTREACH_STEP",
    [
        A("stepId", "long", pk=True),
        A("sequenceId", "long"),
        A("stepNumber", "int"),
        A("channel", "String", maxLength=32),
        A("delayDays", "int"),
        A("templateId", "long"),
    ],
    [
        R("sequence", "OutreachSequence", "many-to-one", "steps", "OutreachSequence.sequenceId = this.sequenceId"),
        R("template", "MessageTemplate", "many-to-one", "steps", "MessageTemplate.templateId = this.templateId"),
    ],
)
entity(
    "OutreachEnrollment",
    AUDIT,
    "OUTREACH_ENROLLMENT",
    [
        A("enrollmentId", "long", pk=True),
        A("sequenceId", "long"),
        A("contactId", "long"),
        A("enrolledTime", "Timestamp"),
        A("currentStepNumber", "int"),
        A("status", "String", maxLength=32),
    ],
    [
        R("sequence", "OutreachSequence", "many-to-one", "enrollments", "OutreachSequence.sequenceId = this.sequenceId"),
        R("contact", "Contact", "many-to-one", "enrollments", "Contact.contactId = this.contactId"),
    ],
)
entity(
    "MessageTemplate",
    BITEMPORAL,
    "MESSAGE_TEMPLATE",
    [
        A("templateId", "long", pk=True),
        A("name", "String", maxLength=128),
        A("channel", "String", maxLength=32),
        A("subject", "String", maxLength=256),
        A("body", "String", maxLength=4000),
    ],
    [],
)

# --- F. Support ---
entity(
    "SupportCase",
    BITEMPORAL,
    "SUPPORT_CASE",
    [
        A("caseId", "long", pk=True),
        A("customerId", "long"),
        A("contactId", "long"),
        A("subject", "String", maxLength=256),
        A("status", "String", maxLength=32),
        A("priority", "String", maxLength=16),
        A("openedTime", "Timestamp"),
        A("closedTime", "Timestamp", nullable=True),
        A("assignedRepId", "long"),
    ],
    [
        R("customer", "Customer", "many-to-one", "supportCases", "Customer.customerId = this.customerId"),
        R("contact", "Contact", "many-to-one", "supportCases", "Contact.contactId = this.contactId"),
        R("assignedRep", "SalesRep", "many-to-one", "assignedCases", "SalesRep.repId = this.assignedRepId"),
    ],
)
entity(
    "CaseEscalation",
    AUDIT,
    "CASE_ESCALATION",
    [
        A("escalationId", "long", pk=True),
        A("caseId", "long"),
        A("escalatedToRepId", "long"),
        A("reason", "String", maxLength=256),
        A("escalatedTime", "Timestamp"),
    ],
    [
        R("supportCase", "SupportCase", "many-to-one", "escalations", "SupportCase.caseId = this.caseId"),
        R("escalatedToRep", "SalesRep", "many-to-one", "escalations", "SalesRep.repId = this.escalatedToRepId"),
    ],
)
entity(
    "SlaPolicy",
    BITEMPORAL,
    "SLA_POLICY",
    [
        A("policyId", "long", pk=True),
        A("name", "String", maxLength=128),
        A("priority", "String", maxLength=16),
        A("firstResponseMins", "int"),
        A("resolutionMins", "int"),
    ],
    [],
)

# --- G. Billing ---
entity(
    "Invoice",
    BITEMPORAL,
    "INVOICE",
    [
        A("invoiceId", "long", pk=True),
        A("customerId", "long"),
        A("contractId", "long"),
        A("invoiceNumber", "String", maxLength=64),
        A("issueDate", "Timestamp"),
        A("dueDate", "Timestamp"),
        A("totalAmount", "BigDecimal", precision=18, scale=2),
        A("status", "String", maxLength=32),
    ],
    [
        R("customer", "Customer", "many-to-one", "invoices", "Customer.customerId = this.customerId"),
        R("contract", "Contract", "many-to-one", "invoices", "Contract.contractId = this.contractId"),
    ],
)
entity(
    "InvoiceLine",
    BITEMPORAL,
    "INVOICE_LINE",
    [
        A("lineId", "long", pk=True),
        A("invoiceId", "long"),
        A("productId", "long"),
        A("description", "String", maxLength=256),
        A("quantity", "int"),
        A("unitPrice", "BigDecimal", precision=18, scale=2),
    ],
    [
        R("invoice", "Invoice", "many-to-one", "lines", "Invoice.invoiceId = this.invoiceId"),
        R("product", "Product", "many-to-one", "invoiceLines", "Product.productId = this.productId"),
    ],
)
entity(
    "Payment",
    AUDIT,
    "PAYMENT",
    [
        A("paymentId", "long", pk=True),
        A("invoiceId", "long"),
        A("amount", "BigDecimal", precision=18, scale=2),
        A("paidTime", "Timestamp"),
        A("method", "String", maxLength=32),
        A("referenceCode", "String", maxLength=64),
    ],
    [R("invoice", "Invoice", "many-to-one", "payments", "Invoice.invoiceId = this.invoiceId")],
)

# --- H. People ---
entity(
    "SalesRep",
    BITEMPORAL,
    "SALES_REP",
    [
        A("repId", "long", pk=True),
        A("firstName", "String", maxLength=64),
        A("lastName", "String", maxLength=64),
        A("email", "String", maxLength=256),
        A("teamId", "long"),
        A("managerRepId", "long", nullable=True),
        A("hireDate", "Timestamp"),
        A("terminationDate", "Timestamp", nullable=True),
        A("quotaAmount", "BigDecimal", precision=18, scale=2),
    ],
    [
        R("team", "Team", "many-to-one", "members", "Team.teamId = this.teamId"),
        R("manager", "SalesRep", "many-to-one", "directReports", "SalesRep.repId = this.managerRepId"),
    ],
)
entity(
    "Team",
    BITEMPORAL,
    "TEAM",
    [
        A("teamId", "long", pk=True),
        A("name", "String", maxLength=128),
        A("regionCode", "String", maxLength=16),
        A("managerRepId", "long"),
    ],
    [R("managerRep", "SalesRep", "many-to-one", "managedTeams", "SalesRep.repId = this.managerRepId")],
)
entity(
    "Territory",
    BITEMPORAL,
    "TERRITORY",
    [
        A("territoryId", "long", pk=True),
        A("name", "String", maxLength=128),
        A("regionCode", "String", maxLength=16),
        A("countryCode", "String", maxLength=2),
    ],
    [],
)

# --- I. Reference (PLAIN) ---
entity(
    "Industry",
    PLAIN,
    "INDUSTRY",
    [
        A("industryCode", "String", pk=True, maxLength=32),
        A("name", "String", maxLength=128),
        A("sicCode", "String", maxLength=16),
    ],
)
entity(
    "CustomerSegment",
    PLAIN,
    "CUSTOMER_SEGMENT",
    [
        A("segmentCode", "String", pk=True, maxLength=32),
        A("name", "String", maxLength=128),
        A("description", "String", maxLength=256),
    ],
)
entity(
    "PipelineStage",
    PLAIN,
    "PIPELINE_STAGE",
    [
        A("stageCode", "String", pk=True, maxLength=32),
        A("name", "String", maxLength=128),
        A("sortOrder", "int"),
        A("isClosed", "boolean"),
        A("isWon", "boolean"),
    ],
)
entity(
    "LeadSource",
    PLAIN,
    "LEAD_SOURCE",
    [
        A("sourceCode", "String", pk=True, maxLength=32),
        A("name", "String", maxLength=128),
        A("channel", "String", maxLength=32),
    ],
)
entity(
    "Product",
    PLAIN,
    "PRODUCT",
    [
        A("productId", "long", pk=True),
        A("sku", "String", maxLength=64),
        A("name", "String", maxLength=128),
        A("productFamily", "String", maxLength=64),
        A("isActive", "boolean"),
    ],
)
entity(
    "PriceBook",
    PLAIN,
    "PRICE_BOOK",
    [
        A("priceBookId", "long", pk=True),
        A("name", "String", maxLength=128),
        A("currency", "String", maxLength=3),
        A("isStandard", "boolean"),
    ],
)
entity(
    "Tag",
    PLAIN,
    "TAG",
    [
        A("tagId", "long", pk=True),
        A("name", "String", maxLength=64),
        A("colorHex", "String", maxLength=7),
    ],
)


SQL_TYPES = {
    "long": "bigint",
    "int": "int",
    "boolean": "boolean",
    "String": "varchar",
    "BigDecimal": "decimal",
    "Timestamp": "datetime",
}


def attr_xml(name, java_type, kw):
    parts = [
        f'        <Attribute name="{name}" javaType="{java_type}" columnName="{col(name)}"',
    ]
    if kw.get("pk"):
        parts.append(' primaryKey="true"')
    if kw.get("nullable"):
        parts.append(' nullable="true"')
    if java_type == "String":
        parts.append(f' maxLength="{kw.get("maxLength", 64)}"')
    if java_type == "BigDecimal":
        parts.append(f' precision="{kw.get("precision", 18)}" scale="{kw.get("scale", 2)}"')
    parts.append("/>")
    return "".join(parts)


def asof_xml(flavour):
    lines = []
    if flavour == BITEMPORAL:
        lines.append(
            f'''        <AsOfAttribute name="businessDate" fromColumnName="BUSINESS_DATE_FROM" toColumnName="BUSINESS_DATE_THRU"
                       toIsInclusive="false" isProcessingDate="false"
                       infinityDate="{INF}"/>'''
        )
        lines.append(
            f'''        <AsOfAttribute name="processingDate" fromColumnName="IN_Z" toColumnName="OUT_Z"
                       toIsInclusive="false" isProcessingDate="true"
                       infinityDate="{INF}"
                       defaultIfNotSpecified="{INF}"/>'''
        )
    elif flavour == AUDIT:
        lines.append(
            f'''        <AsOfAttribute name="processingDate" fromColumnName="IN_Z" toColumnName="OUT_Z"
                       toIsInclusive="false" isProcessingDate="true"
                       infinityDate="{INF}"
                       defaultIfNotSpecified="{INF}"/>'''
        )
    return "\n".join(lines)


def rel_xml(rel):
    return (
        f'        <Relationship name="{rel["name"]}" relatedObject="{rel["related"]}" '
        f'cardinality="{rel["card"]}" reverseRelationshipName="{rel["reverse"]}">'
        f'{rel["join"]}</Relationship>'
    )


def object_xml(ent):
    asof = asof_xml(ent["flavour"])
    attrs = "\n".join(attr_xml(*a) for a in ent["attrs"])
    rels = "\n".join(rel_xml(r) for r in ent["rels"])
    body = []
    if asof:
        body.append(asof)
    body.append(attrs)
    if rels:
        body.append(rels)
    inner = "\n".join(body)
    return f'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<MithraObject objectType="transactional"
              xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
              xsi:noNamespaceSchemaLocation="reladomoobject.xsd">
        <PackageName>{PKG}</PackageName>
        <ClassName>{ent["name"]}</ClassName>
        <DefaultTable>{ent["table"]}</DefaultTable>
{inner}
</MithraObject>
'''


def h2_col_def(name, java_type, kw):
    sql = SQL_TYPES[java_type]
    if java_type == "String":
        sql = f"varchar({kw.get('maxLength', 64)})"
    elif java_type == "BigDecimal":
        sql = f"decimal({kw.get('precision', 18)},{kw.get('scale', 2)})"
    null = "NULL" if kw.get("nullable") else "NOT NULL"
    return f"    {col(name)} {sql} {null}"


def table_sql(ent):
    cols = [h2_col_def(*a) for a in ent["attrs"]]
    pk_cols = [col(a[0]) for a in ent["attrs"] if a[2].get("pk")]
    uniq = list(pk_cols)
    if ent["flavour"] == BITEMPORAL:
        cols.append("    BUSINESS_DATE_FROM datetime NOT NULL")
        cols.append("    BUSINESS_DATE_THRU datetime NOT NULL")
        cols.append("    IN_Z datetime NOT NULL")
        cols.append("    OUT_Z datetime NOT NULL")
        uniq += ["BUSINESS_DATE_FROM", "IN_Z"]
    elif ent["flavour"] == AUDIT:
        cols.append("    IN_Z datetime NOT NULL")
        cols.append("    OUT_Z datetime NOT NULL")
        uniq += ["IN_Z"]
    create = f"CREATE TABLE {ent['table']}\n(\n" + ",\n".join(cols) + "\n);"
    idx = f"CREATE UNIQUE INDEX {ent['table']}_PK ON {ent['table']} ({', '.join(uniq)});"
    return create + "\n" + idx + "\n"


def runtime_xml(connection_manager, extra_property=None):
    props = ""
    if extra_property:
        k, v = extra_property
        props = f'\n        <Property name="{k}" value="{v}"/>'
    configs = []
    for ent in ENTITIES:
        cache = "full" if ent["flavour"] == PLAIN else "partial"
        configs.append(
            f'        <MithraObjectConfiguration className="{PKG}.{ent["name"]}" cacheType="{cache}"/>'
        )
    return f'''<?xml version="1.0" encoding="UTF-8"?>
<MithraRuntime>
    <ConnectionManager className="{connection_manager}">{props}
{chr(10).join(configs)}
    </ConnectionManager>
</MithraRuntime>
'''


def class_list_xml():
    resources = "\n".join(f'    <MithraObjectResource name="{e["name"]}"/>' for e in ENTITIES)
    return f'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Mithra xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:noNamespaceSchemaLocation="reladomoobject.xsd">
{resources}
</Mithra>
'''


def main():
    MODELS.mkdir(parents=True, exist_ok=True)
    MAIN_RT.mkdir(parents=True, exist_ok=True)
    TEST_RT.mkdir(parents=True, exist_ok=True)
    H2_DIR.mkdir(parents=True, exist_ok=True)

    for ent in ENTITIES:
        (MODELS / f"{ent['name']}.xml").write_text(object_xml(ent), encoding="utf-8")

    (MODELS / "ReladomoClassList.xml").write_text(class_list_xml(), encoding="utf-8")
    (MAIN_RT / "ReladomoRuntimeConfig.xml").write_text(
        runtime_xml("com.reladynamo.demo.crm.util.H2ConnectionManager"),
        encoding="utf-8",
    )
    (TEST_RT / "TestReladomoRuntimeConfig.xml").write_text(
        runtime_xml(
            "com.gs.fw.common.mithra.test.ConnectionManagerForTests",
            extra_property=("resourceName", "crm"),
        ),
        encoding="utf-8",
    )

    ddl = ["-- H2 schema for crm-bitemporal-demo. Types match Reladomo H2DatabaseType 18.1.0.", ""]
    for ent in ENTITIES:
        ddl.append(f"-- {ent['name']} ({ent['flavour']})")
        ddl.append(table_sql(ent))
    (H2_DIR / "schema.sql").write_text("\n".join(ddl), encoding="utf-8")
    print(f"Wrote {len(ENTITIES)} MithraObject XML files, runtime configs, and schema.sql")


if __name__ == "__main__":
    main()
