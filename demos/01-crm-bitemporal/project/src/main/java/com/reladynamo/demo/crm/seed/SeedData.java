package com.reladynamo.demo.crm.seed;

import com.reladynamo.demo.crm.DemoIds;
import com.reladynamo.demo.crm.domain.Account;
import com.reladynamo.demo.crm.domain.Address;
import com.reladynamo.demo.crm.domain.Attachment;
import com.reladynamo.demo.crm.domain.Call;
import com.reladynamo.demo.crm.domain.Campaign;
import com.reladynamo.demo.crm.domain.CampaignMember;
import com.reladynamo.demo.crm.domain.CaseComment;
import com.reladynamo.demo.crm.domain.CaseEscalation;
import com.reladynamo.demo.crm.domain.Company;
import com.reladynamo.demo.crm.domain.ConsentRecord;
import com.reladynamo.demo.crm.domain.ConsentRecordFinder;
import com.reladynamo.demo.crm.domain.Contact;
import com.reladynamo.demo.crm.domain.ContactEmail;
import com.reladynamo.demo.crm.domain.ContactPhone;
import com.reladynamo.demo.crm.domain.Contract;
import com.reladynamo.demo.crm.domain.CreditRating;
import com.reladynamo.demo.crm.domain.Customer;
import com.reladynamo.demo.crm.domain.CustomerFinder;
import com.reladynamo.demo.crm.domain.CustomerSegment;
import com.reladynamo.demo.crm.domain.CustomerSegmentAssignment;
import com.reladynamo.demo.crm.domain.EmailMessage;
import com.reladynamo.demo.crm.domain.Industry;
import com.reladynamo.demo.crm.domain.Invoice;
import com.reladynamo.demo.crm.domain.InvoiceLine;
import com.reladynamo.demo.crm.domain.LeadSource;
import com.reladynamo.demo.crm.domain.Meeting;
import com.reladynamo.demo.crm.domain.MessageTemplate;
import com.reladynamo.demo.crm.domain.Note;
import com.reladynamo.demo.crm.domain.Opportunity;
import com.reladynamo.demo.crm.domain.OpportunityFinder;
import com.reladynamo.demo.crm.domain.OutreachEnrollment;
import com.reladynamo.demo.crm.domain.OutreachSequence;
import com.reladynamo.demo.crm.domain.OutreachStep;
import com.reladynamo.demo.crm.domain.Payment;
import com.reladynamo.demo.crm.domain.PipelineStage;
import com.reladynamo.demo.crm.domain.PriceBook;
import com.reladynamo.demo.crm.domain.PriceBookEntry;
import com.reladynamo.demo.crm.domain.PriceBookEntryFinder;
import com.reladynamo.demo.crm.domain.Product;
import com.reladynamo.demo.crm.domain.Quote;
import com.reladynamo.demo.crm.domain.QuoteLineItem;
import com.reladynamo.demo.crm.domain.SalesRep;
import com.reladynamo.demo.crm.domain.SalesRepFinder;
import com.reladynamo.demo.crm.domain.SlaPolicy;
import com.reladynamo.demo.crm.domain.Subscription;
import com.reladynamo.demo.crm.domain.SubscriptionFinder;
import com.reladynamo.demo.crm.domain.SupportCase;
import com.reladynamo.demo.crm.domain.Tag;
import com.reladynamo.demo.crm.domain.TaskItem;
import com.reladynamo.demo.crm.domain.Team;
import com.reladynamo.demo.crm.domain.Territory;
import com.reladynamo.demo.crm.domain.TerritoryAssignment;
import com.reladynamo.demo.crm.domain.TerritoryAssignmentFinder;
import com.reladynamo.demo.crm.domain.AddressFinder;
import com.reladynamo.demo.crm.util.DemoTimestamps;
import com.reladynamo.demo.crm.util.InfinityTimestamp;
import com.reladynamo.demo.crm.util.Transactions;

import java.math.BigDecimal;
import java.sql.Timestamp;

/**
 * Deterministic 100-record seed plus the bitemporal mutations the seven demos need.
 */
public final class SeedData
{
    public static final int LOGICAL_RECORD_COUNT = 100;

    private static int inserted;

    private SeedData()
    {
    }

    public static int load()
    {
        inserted = 0;
        Transactions.at(DemoTimestamps.P_INITIAL, new Runnable()
        {
            @Override
            public void run()
            {
                insertReference();
                insertPeople();
                insertParty();
                insertAddresses();
                insertPipeline();
                insertActivity();
                insertOutreach();
                insertSupport();
                insertBilling();
            }
        });
        applyHistory();
        if (inserted != LOGICAL_RECORD_COUNT)
        {
            throw new IllegalStateException(
                    "expected " + LOGICAL_RECORD_COUNT + " logical records but inserted " + inserted);
        }
        return inserted;
    }

    public static int lastInsertCount()
    {
        return inserted;
    }

    private static void applyHistory()
    {
        final Timestamp inf = InfinityTimestamp.getInfinityDate();

        Transactions.at(DemoTimestamps.P_RENAME, new Runnable()
        {
            @Override
            public void run()
            {
                Customer acme = CustomerFinder.findByPrimaryKey(
                        DemoIds.ACME, DemoTimestamps.B_MOVE, inf);
                acme.setName("Acme Corporation");
                acme.setLegalName("Acme Corporation Inc.");
            }
        });

        Transactions.at(DemoTimestamps.P_PRICES, new Runnable()
        {
            @Override
            public void run()
            {
                PriceBookEntry promo = PriceBookEntryFinder.findByPrimaryKey(
                        DemoIds.PRICE_CORE, DemoTimestamps.B_PROMO_START, inf);
                promo.setUnitPriceUntil(money("80.00"), DemoTimestamps.B_PROMO_END);

                PriceBookEntry surcharge = PriceBookEntryFinder.findByPrimaryKey(
                        DemoIds.PRICE_CORE, DemoTimestamps.B_SURCHARGE_START, inf);
                surcharge.incrementUnitPriceUntil(money("5.00"), DemoTimestamps.B_SURCHARGE_END);
            }
        });

        Transactions.at(DemoTimestamps.P_REVENUE, new Runnable()
        {
            @Override
            public void run()
            {
                Customer acme = CustomerFinder.findByPrimaryKey(
                        DemoIds.ACME, DemoTimestamps.B_REVENUE, inf);
                acme.setAnnualRevenue(money("62000000.00"));
            }
        });

        Transactions.at(DemoTimestamps.P_CLOSE, new Runnable()
        {
            @Override
            public void run()
            {
                Opportunity opp = OpportunityFinder.findByPrimaryKey(
                        DemoIds.OPP_ACME, DemoTimestamps.B_CLOSE, inf);
                opp.setStageCode("CLOSED_WON");
                opp.setProbability(100);
            }
        });

        Transactions.at(DemoTimestamps.P_ADDRESS, new Runnable()
        {
            @Override
            public void run()
            {
                Address hq = AddressFinder.findByPrimaryKey(
                        DemoIds.ADDRESS_HQ, DemoTimestamps.B_MOVE, inf);
                hq.setLine1("200 Mission St");
                hq.setLine2("Floor 12");
                hq.setCity("San Francisco");
                hq.setState("CA");
                hq.setPostalCode("94105");
            }
        });

        Transactions.at(DemoTimestamps.P_CONSENT, new Runnable()
        {
            @Override
            public void run()
            {
                ConsentRecord consent = ConsentRecordFinder.findByPrimaryKey(
                        DemoIds.CONSENT_EMAIL, DemoTimestamps.B_WITHDRAW, inf);
                consent.setGranted(false);
                consent.setLawfulBasis("withdrawn");
                consent.setSourceRef("GDPR-DSAR-441");
            }
        });

        Transactions.at(DemoTimestamps.P_RESTATE, new Runnable()
        {
            @Override
            public void run()
            {
                TerritoryAssignment assignment = TerritoryAssignmentFinder.findByPrimaryKey(
                        DemoIds.TERRITORY_ASSIGNMENT_ACME, DemoTimestamps.B_TERRITORY, inf);
                assignment.setTerritoryId(DemoIds.TERRITORY_EAST);
                assignment.setRepId(DemoIds.BOB);

                Opportunity opp = OpportunityFinder.findByPrimaryKey(
                        DemoIds.OPP_ACME, DemoTimestamps.B_CLOSE, inf);
                opp.setAmount(money("85000.00"));
            }
        });

        Transactions.at(DemoTimestamps.P_TERM, new Runnable()
        {
            @Override
            public void run()
            {
                SalesRep carol = SalesRepFinder.findByPrimaryKey(
                        DemoIds.CAROL, DemoTimestamps.B_CAROL_TERM, inf);
                carol.setTerminationDate(DemoTimestamps.B_CAROL_TERM);
                carol.terminate();

                Subscription sub = SubscriptionFinder.findByPrimaryKey(
                        DemoIds.SUB_ACME, DemoTimestamps.B_SUB_END, inf);
                sub.terminate();
            }
        });
    }

    private static void insertReference()
    {
        insertIndustry("SOFT", "Software", "7372");
        insertIndustry("MFG", "Manufacturing", "3500");
        insertIndustry("FIN", "Financial Services", "6200");
        insertIndustry("HLTH", "Healthcare", "8000");

        insertSegment("ENT", "Enterprise", "1000+ employees");
        insertSegment("SMB", "SMB", "Under 1000 employees");
        insertSegment("STRAT", "Strategic", "Named accounts");

        insertStage("PROSPECT", "Prospect", 1, false, false);
        insertStage("QUALIFY", "Qualification", 2, false, false);
        insertStage("PROPOSAL", "Proposal", 3, false, false);
        insertStage("CLOSED_WON", "Closed Won", 4, true, true);
        insertStage("CLOSED_LOST", "Closed Lost", 5, true, false);

        insertLeadSource("WEB", "Website", "inbound");
        insertLeadSource("REF", "Referral", "partner");
        insertLeadSource("EVENT", "Conference", "field");

        insertProduct(DemoIds.PRODUCT_CORE, "CORE-1", "Core Platform", "Platform");
        insertProduct(DemoIds.PRODUCT_ADDON, "ADDON-1", "Analytics Add-on", "AddOn");
        insertProduct(DemoIds.PRODUCT_SUPPORT, "SUP-1", "Premium Support", "Services");

        PriceBook book = new PriceBook();
        book.setPriceBookId(DemoIds.PRICE_BOOK_STANDARD);
        book.setName("Standard USD");
        book.setCurrency("USD");
        book.setIsStandard(true);
        book.insert();
        bump();

        Tag tag = new Tag();
        tag.setTagId(DemoIds.TAG_VIP);
        tag.setName("VIP");
        tag.setColorHex("#FFD700");
        tag.insert();
        bump();
    }

    private static void insertPeople()
    {
        Timestamp hired = DemoTimestamps.utc(2020, 1, 15);
        insertTeam(DemoIds.TEAM_WEST, "West Coast", "WEST", DemoIds.ALICE);
        insertTeam(DemoIds.TEAM_EAST, "East Coast", "EAST", DemoIds.BOB);

        insertRep(DemoIds.ALICE, "Alice", "Nguyen", "alice@reladynamo.test",
                DemoIds.TEAM_WEST, 0L, true, hired, money("1200000.00"));
        insertRep(DemoIds.BOB, "Bob", "Okoye", "bob@reladynamo.test",
                DemoIds.TEAM_EAST, DemoIds.ALICE, false, hired, money("900000.00"));
        insertRep(DemoIds.CAROL, "Carol", "Singh", "carol@reladynamo.test",
                DemoIds.TEAM_WEST, DemoIds.ALICE, false, hired, money("600000.00"));

        insertTerritory(DemoIds.TERRITORY_WEST, "US West", "WEST", "US");
        insertTerritory(DemoIds.TERRITORY_EAST, "US East", "EAST", "US");
    }

    private static void insertParty()
    {
        Timestamp created = DemoTimestamps.B_START;
        insertCustomer(DemoIds.ACME, "Acme Corp", "Acme Corp LLC", "SOFT", "ENT",
                "ACTIVE", money("50000000.00"), 420, DemoIds.ALICE, created);
        insertCustomer(DemoIds.GLOBEX, "Globex", "Globex International", "MFG", "SMB",
                "ACTIVE", money("18000000.00"), 90, DemoIds.BOB, created);
        insertCustomer(DemoIds.INITECH, "Initech", "Initech Inc.", "FIN", "SMB",
                "ACTIVE", money("9000000.00"), 40, DemoIds.CAROL, created);

        insertAccount(DemoIds.ACCOUNT_ACME, DemoIds.ACME, "ACME-001", "RECEIVABLE", money("250000.00"));
        insertAccount(DemoIds.ACCOUNT_GLOBEX, DemoIds.GLOBEX, "GLX-001", "RECEIVABLE", money("80000.00"));
        insertAccount(DemoIds.ACCOUNT_INITECH, DemoIds.INITECH, "INI-001", "RECEIVABLE", money("40000.00"));

        insertContact(DemoIds.CONTACT_PRIMARY, DemoIds.ACME, "Dana", "Walsh",
                "VP Operations", "Ops", true, "EMAIL");
        insertContact(DemoIds.CONTACT_ACME_OTHER, DemoIds.ACME, "Evan", "Cole",
                "Buyer", "Procurement", false, "PHONE");
        insertContact(DemoIds.CONTACT_GLOBEX, DemoIds.GLOBEX, "Fay", "Ibarra",
                "CTO", "Technology", true, "EMAIL");
        insertContact(DemoIds.CONTACT_INITECH, DemoIds.INITECH, "Gus", "Park",
                "Controller", "Finance", true, "EMAIL");

        insertCompany(DemoIds.COMPANY_HOLDING, "Acme Holdings", "111222333", 0L, true, "US");
        insertCompany(DemoIds.COMPANY_OPCO, "Acme Operating", "111222334", DemoIds.COMPANY_HOLDING, false, "US");

        insertSegmentAssignment(1L, DemoIds.ACME, "ENT", "Named strategic account");
        insertSegmentAssignment(2L, DemoIds.GLOBEX, "SMB", "Mid-market fit");
        insertSegmentAssignment(3L, DemoIds.INITECH, "SMB", "Finance vertical");

        insertTerritoryAssignment(DemoIds.TERRITORY_ASSIGNMENT_ACME, DemoIds.ACME,
                DemoIds.TERRITORY_WEST, DemoIds.ALICE, true);
        insertTerritoryAssignment(DemoIds.TERRITORY_ASSIGNMENT_GLOBEX, DemoIds.GLOBEX,
                DemoIds.TERRITORY_EAST, DemoIds.BOB, true);

        insertCredit(1L, DemoIds.ACME, "A", 82, "S&P");
        insertCredit(2L, DemoIds.GLOBEX, "BBB", 71, "Moody's");

        insertConsent(DemoIds.CONSENT_EMAIL, DemoIds.CONTACT_PRIMARY, "EMAIL", true,
                "consent", "web-form-2025");
        insertConsent(DemoIds.CONSENT_GLOBEX, DemoIds.CONTACT_GLOBEX, "EMAIL", true,
                "consent", "event-badge");
    }

    private static void insertAddresses()
    {
        insertAddress(DemoIds.ADDRESS_HQ, DemoIds.ACME, "HQ", "100 Market St", null,
                "San Francisco", "CA", "94103", "US");
        insertAddress(DemoIds.ADDRESS_ACME_BILLING, DemoIds.ACME, "BILLING", "100 Market St", "Suite 400",
                "San Francisco", "CA", "94103", "US");
        insertAddress(DemoIds.ADDRESS_GLOBEX, DemoIds.GLOBEX, "HQ", "1 Globex Way", null,
                "Boston", "MA", "02110", "US");
        insertAddress(DemoIds.ADDRESS_INITECH, DemoIds.INITECH, "HQ", "8 Initech Plaza", null,
                "Austin", "TX", "78701", "US");

        insertPhone(1L, DemoIds.CONTACT_PRIMARY, "WORK", "+14155550101", true);
        insertPhone(2L, DemoIds.CONTACT_GLOBEX, "WORK", "+16175550102", true);
        insertPhone(3L, DemoIds.CONTACT_INITECH, "MOBILE", "+15125550103", false);

        insertEmail(1L, DemoIds.CONTACT_PRIMARY, "dana.walsh@acme.test", true, 0);
        insertEmail(2L, DemoIds.CONTACT_GLOBEX, "fay.ibarra@globex.test", true, 0);
        insertEmail(3L, DemoIds.CONTACT_INITECH, "gus.park@initech.test", false, 1);
    }

    private static void insertPipeline()
    {
        insertOpportunity(DemoIds.OPP_ACME, DemoIds.ACME, "Acme platform expansion",
                "PROPOSAL", money("100000.00"), 60, DemoTimestamps.B_CLOSE, DemoIds.ALICE);
        insertOpportunity(DemoIds.OPP_GLOBEX, DemoIds.GLOBEX, "Globex analytics",
                "QUALIFY", money("45000.00"), 30, DemoTimestamps.utc(2025, 9, 1), DemoIds.BOB);
        insertOpportunity(DemoIds.OPP_INITECH, DemoIds.INITECH, "Initech support uplift",
                "PROSPECT", money("12000.00"), 10, DemoTimestamps.utc(2025, 11, 1), DemoIds.CAROL);

        insertQuote(DemoIds.QUOTE_ACME, DemoIds.OPP_ACME, "Q-ACME-100", "SENT", money("100000.00"));
        insertQuote(DemoIds.QUOTE_GLOBEX, DemoIds.OPP_GLOBEX, "Q-GLX-200", "DRAFT", money("45000.00"));

        insertQuoteLine(1L, DemoIds.QUOTE_ACME, DemoIds.PRODUCT_CORE, 10, money("10000.00"), money("0.00"));
        insertQuoteLine(2L, DemoIds.QUOTE_ACME, DemoIds.PRODUCT_SUPPORT, 1, money("0.00"), money("0.00"));
        insertQuoteLine(3L, DemoIds.QUOTE_GLOBEX, DemoIds.PRODUCT_ADDON, 5, money("9000.00"), money("10.00"));

        insertContract(DemoIds.CONTRACT_ACME, DemoIds.ACME, "C-ACME-1", "ACTIVE", money("360000.00"));
        insertContract(DemoIds.CONTRACT_GLOBEX, DemoIds.GLOBEX, "C-GLX-1", "ACTIVE", money("96000.00"));

        insertSubscription(DemoIds.SUB_ACME, DemoIds.CONTRACT_ACME, DemoIds.PRODUCT_CORE, 40, money("10000.00"));
        insertSubscription(DemoIds.SUB_GLOBEX, DemoIds.CONTRACT_GLOBEX, DemoIds.PRODUCT_ADDON, 8, money("4000.00"));

        insertPrice(DemoIds.PRICE_CORE, DemoIds.PRODUCT_CORE, money("100.00"));
        insertPrice(DemoIds.PRICE_ADDON, DemoIds.PRODUCT_ADDON, money("40.00"));
        insertPrice(DemoIds.PRICE_SUPPORT, DemoIds.PRODUCT_SUPPORT, money("25.00"));
    }

    private static void insertActivity()
    {
        Timestamp t1 = DemoTimestamps.utc(2025, 1, 20, 16, 0, 0);
        insertCall(1L, DemoIds.CONTACT_PRIMARY, DemoIds.ACME, DemoIds.ALICE, "OUTBOUND", t1, 1800, "CONNECTED");
        insertCall(2L, DemoIds.CONTACT_GLOBEX, DemoIds.GLOBEX, DemoIds.BOB, "INBOUND", t1, 600, "VOICEMAIL");
        insertCall(3L, DemoIds.CONTACT_INITECH, DemoIds.INITECH, DemoIds.CAROL, "OUTBOUND", t1, 900, "CONNECTED");

        EmailMessage outreach = new EmailMessage(InfinityTimestamp.getInfinityDate());
        outreach.setMessageId(1L);
        outreach.setContactId(DemoIds.CONTACT_PRIMARY);
        outreach.setRepId(DemoIds.ALICE);
        outreach.setDirection("OUTBOUND");
        outreach.setSubject("Q2 enablement sequence");
        outreach.setSentTime(DemoTimestamps.OUTREACH_SENT);
        outreach.insert();
        bump();

        EmailMessage inbound = new EmailMessage(InfinityTimestamp.getInfinityDate());
        inbound.setMessageId(2L);
        inbound.setContactId(DemoIds.CONTACT_GLOBEX);
        inbound.setRepId(DemoIds.BOB);
        inbound.setDirection("INBOUND");
        inbound.setSubject("Pricing question");
        inbound.setSentTime(DemoTimestamps.utc(2025, 1, 22, 11, 0, 0));
        inbound.insert();
        bump();

        insertMeeting(1L, DemoIds.ACME, DemoIds.ALICE, "Discovery", "onsite", "POSITIVE");
        insertMeeting(2L, DemoIds.GLOBEX, DemoIds.BOB, "Demo", "video", "NEUTRAL");

        insertTask(1L, DemoIds.ACME, DemoIds.ALICE, "Send security packet", "OPEN", "HIGH");
        insertTask(2L, DemoIds.GLOBEX, DemoIds.BOB, "Schedule follow-up", "OPEN", "MED");

        insertNote(1L, "Customer", DemoIds.ACME, DemoIds.ALICE, "Warm intro from partner.");
        insertNote(2L, "Opportunity", DemoIds.OPP_ACME, DemoIds.ALICE, "Legal requested MSA redlines.");

        CaseComment comment = new CaseComment(InfinityTimestamp.getInfinityDate());
        comment.setCommentId(1L);
        comment.setCaseId(DemoIds.CASE_ACME);
        comment.setAuthorRepId(DemoIds.ALICE);
        comment.setBody("Initial triage complete.");
        comment.setCreatedTime(DemoTimestamps.utc(2025, 1, 25, 10, 0, 0));
        comment.setIsPublic(false);
        comment.insert();
        bump();

        Attachment attachment = new Attachment(InfinityTimestamp.getInfinityDate());
        attachment.setAttachmentId(1L);
        attachment.setEntityType("Customer");
        attachment.setEntityId(DemoIds.ACME);
        attachment.setFileName("msa.pdf");
        attachment.setMimeType("application/pdf");
        attachment.setSizeBytes(245760L);
        attachment.setStorageRef("s3://crm/msa.pdf");
        attachment.insert();
        bump();
    }

    private static void insertOutreach()
    {
        Campaign campaign = new Campaign(DemoTimestamps.B_START);
        campaign.setCampaignId(DemoIds.CAMPAIGN_Q1);
        campaign.setName("Q1 Nurture");
        campaign.setCampaignType("EMAIL");
        campaign.setStatus("ACTIVE");
        campaign.setBudget(money("25000.00"));
        campaign.setStartDate(DemoTimestamps.B_START);
        campaign.setEndDate(DemoTimestamps.utc(2025, 3, 31));
        campaign.setOwnerRepId(DemoIds.ALICE);
        campaign.insert();
        bump();

        insertCampaignMember(1L, DemoIds.CAMPAIGN_Q1, DemoIds.CONTACT_PRIMARY, "SENT");
        insertCampaignMember(2L, DemoIds.CAMPAIGN_Q1, DemoIds.CONTACT_GLOBEX, "RESPONDED");

        OutreachSequence sequence = new OutreachSequence(DemoTimestamps.B_START);
        sequence.setSequenceId(DemoIds.SEQUENCE_NURTURE);
        sequence.setName("New logo nurture");
        sequence.setIsActive(true);
        sequence.setOwnerRepId(DemoIds.ALICE);
        sequence.insert();
        bump();

        MessageTemplate template = new MessageTemplate(DemoTimestamps.B_START);
        template.setTemplateId(DemoIds.TEMPLATE_INTRO);
        template.setName("Intro");
        template.setChannel("EMAIL");
        template.setSubject("Quick introduction");
        template.setBody("Hello from Reladynamo.");
        template.insert();
        bump();

        insertStep(1L, DemoIds.SEQUENCE_NURTURE, 1, "EMAIL", 0, DemoIds.TEMPLATE_INTRO);
        insertStep(2L, DemoIds.SEQUENCE_NURTURE, 2, "EMAIL", 3, DemoIds.TEMPLATE_INTRO);

        OutreachEnrollment enrollment = new OutreachEnrollment(InfinityTimestamp.getInfinityDate());
        enrollment.setEnrollmentId(1L);
        enrollment.setSequenceId(DemoIds.SEQUENCE_NURTURE);
        enrollment.setContactId(DemoIds.CONTACT_PRIMARY);
        enrollment.setEnrolledTime(DemoTimestamps.utc(2025, 1, 10, 9, 0, 0));
        enrollment.setCurrentStepNumber(1);
        enrollment.setStatus("ACTIVE");
        enrollment.insert();
        bump();
    }

    private static void insertSupport()
    {
        SupportCase supportCase = new SupportCase(DemoTimestamps.B_START);
        supportCase.setCaseId(DemoIds.CASE_ACME);
        supportCase.setCustomerId(DemoIds.ACME);
        supportCase.setContactId(DemoIds.CONTACT_PRIMARY);
        supportCase.setSubject("SSO timeout");
        supportCase.setStatus("OPEN");
        supportCase.setPriority("HIGH");
        supportCase.setOpenedTime(DemoTimestamps.utc(2025, 1, 24, 15, 0, 0));
        supportCase.setAssignedRepId(DemoIds.ALICE);
        supportCase.insert();
        bump();

        CaseEscalation escalation = new CaseEscalation(InfinityTimestamp.getInfinityDate());
        escalation.setEscalationId(1L);
        escalation.setCaseId(DemoIds.CASE_ACME);
        escalation.setEscalatedToRepId(DemoIds.BOB);
        escalation.setReason("SLA risk");
        escalation.setEscalatedTime(DemoTimestamps.utc(2025, 1, 26, 9, 0, 0));
        escalation.insert();
        bump();

        SlaPolicy sla = new SlaPolicy(DemoTimestamps.B_START);
        sla.setPolicyId(DemoIds.SLA_GOLD);
        sla.setName("Gold");
        sla.setPriority("HIGH");
        sla.setFirstResponseMins(60);
        sla.setResolutionMins(480);
        sla.insert();
        bump();
    }

    private static void insertBilling()
    {
        Invoice invoice = new Invoice(DemoTimestamps.B_START);
        invoice.setInvoiceId(DemoIds.INVOICE_ACME);
        invoice.setCustomerId(DemoIds.ACME);
        invoice.setContractId(DemoIds.CONTRACT_ACME);
        invoice.setInvoiceNumber("INV-1001");
        invoice.setIssueDate(DemoTimestamps.utc(2025, 1, 31));
        invoice.setDueDate(DemoTimestamps.utc(2025, 2, 15));
        invoice.setTotalAmount(money("10000.00"));
        invoice.setStatus("PAID");
        invoice.insert();
        bump();

        InvoiceLine line = new InvoiceLine(DemoTimestamps.B_START);
        line.setLineId(1L);
        line.setInvoiceId(DemoIds.INVOICE_ACME);
        line.setProductId(DemoIds.PRODUCT_CORE);
        line.setDescription("January seats");
        line.setQuantity(40);
        line.setUnitPrice(money("250.00"));
        line.insert();
        bump();

        Payment payment = new Payment(InfinityTimestamp.getInfinityDate());
        payment.setPaymentId(1L);
        payment.setInvoiceId(DemoIds.INVOICE_ACME);
        payment.setAmount(money("10000.00"));
        payment.setPaidTime(DemoTimestamps.utc(2025, 2, 10, 12, 0, 0));
        payment.setMethod("ACH");
        payment.setReferenceCode("ACH-8891");
        payment.insert();
        bump();
    }

    private static void insertIndustry(String code, String name, String sic)
    {
        Industry industry = new Industry();
        industry.setIndustryCode(code);
        industry.setName(name);
        industry.setSicCode(sic);
        industry.insert();
        bump();
    }

    private static void insertSegment(String code, String name, String description)
    {
        CustomerSegment segment = new CustomerSegment();
        segment.setSegmentCode(code);
        segment.setName(name);
        segment.setDescription(description);
        segment.insert();
        bump();
    }

    private static void insertStage(String code, String name, int order, boolean closed, boolean won)
    {
        PipelineStage stage = new PipelineStage();
        stage.setStageCode(code);
        stage.setName(name);
        stage.setSortOrder(order);
        stage.setIsClosed(closed);
        stage.setIsWon(won);
        stage.insert();
        bump();
    }

    private static void insertLeadSource(String code, String name, String channel)
    {
        LeadSource source = new LeadSource();
        source.setSourceCode(code);
        source.setName(name);
        source.setChannel(channel);
        source.insert();
        bump();
    }

    private static void insertProduct(long id, String sku, String name, String family)
    {
        Product product = new Product();
        product.setProductId(id);
        product.setSku(sku);
        product.setName(name);
        product.setProductFamily(family);
        product.setIsActive(true);
        product.insert();
        bump();
    }

    private static void insertTeam(long id, String name, String region, long managerRepId)
    {
        Team team = new Team(DemoTimestamps.B_START);
        team.setTeamId(id);
        team.setName(name);
        team.setRegionCode(region);
        team.setManagerRepId(managerRepId);
        team.insert();
        bump();
    }

    private static void insertRep(long id, String first, String last, String email, long teamId,
                                  long managerId, boolean noManager, Timestamp hireDate, BigDecimal quota)
    {
        SalesRep rep = new SalesRep(DemoTimestamps.B_START);
        rep.setRepId(id);
        rep.setFirstName(first);
        rep.setLastName(last);
        rep.setEmail(email);
        rep.setTeamId(teamId);
        if (noManager)
        {
            rep.setManagerRepIdNull();
        }
        else
        {
            rep.setManagerRepId(managerId);
        }
        rep.setHireDate(hireDate);
        rep.setQuotaAmount(quota);
        rep.insert();
        bump();
    }

    private static void insertTerritory(long id, String name, String region, String country)
    {
        Territory territory = new Territory(DemoTimestamps.B_START);
        territory.setTerritoryId(id);
        territory.setName(name);
        territory.setRegionCode(region);
        territory.setCountryCode(country);
        territory.insert();
        bump();
    }

    private static void insertCustomer(long id, String name, String legal, String industry, String segment,
                                       String status, BigDecimal revenue, int employees, long owner,
                                       Timestamp created)
    {
        Customer customer = new Customer(DemoTimestamps.B_START);
        customer.setCustomerId(id);
        customer.setName(name);
        customer.setLegalName(legal);
        customer.setIndustryId(industry);
        customer.setSegmentCode(segment);
        customer.setStatus(status);
        customer.setAnnualRevenue(revenue);
        customer.setEmployeeCount(employees);
        customer.setOwnerRepId(owner);
        customer.setCreatedDate(created);
        customer.insert();
        bump();
    }

    private static void insertAccount(long id, long customerId, String number, String type, BigDecimal limit)
    {
        Account account = new Account(DemoTimestamps.B_START);
        account.setAccountId(id);
        account.setCustomerId(customerId);
        account.setAccountNumber(number);
        account.setAccountType(type);
        account.setOpenedDate(DemoTimestamps.B_START);
        account.setCreditLimit(limit);
        account.setCurrency("USD");
        account.insert();
        bump();
    }

    private static void insertContact(long id, long customerId, String first, String last, String title,
                                      String dept, boolean primary, String channel)
    {
        Contact contact = new Contact(DemoTimestamps.B_START);
        contact.setContactId(id);
        contact.setCustomerId(customerId);
        contact.setFirstName(first);
        contact.setLastName(last);
        contact.setTitle(title);
        contact.setDepartment(dept);
        contact.setIsPrimary(primary);
        contact.setPreferredChannel(channel);
        contact.insert();
        bump();
    }

    private static void insertCompany(long id, String name, String duns, long parentId, boolean root, String country)
    {
        Company company = new Company(DemoTimestamps.B_START);
        company.setCompanyId(id);
        company.setName(name);
        company.setDuns(duns);
        if (root)
        {
            company.setParentCompanyIdNull();
        }
        else
        {
            company.setParentCompanyId(parentId);
        }
        company.setCountryCode(country);
        company.insert();
        bump();
    }

    private static void insertSegmentAssignment(long id, long customerId, String segment, String reason)
    {
        CustomerSegmentAssignment assignment = new CustomerSegmentAssignment(DemoTimestamps.B_START);
        assignment.setAssignmentId(id);
        assignment.setCustomerId(customerId);
        assignment.setSegmentCode(segment);
        assignment.setAssignedReason(reason);
        assignment.insert();
        bump();
    }

    private static void insertTerritoryAssignment(long id, long customerId, long territoryId, long repId,
                                                  boolean primary)
    {
        TerritoryAssignment assignment = new TerritoryAssignment(DemoTimestamps.B_START);
        assignment.setAssignmentId(id);
        assignment.setCustomerId(customerId);
        assignment.setTerritoryId(territoryId);
        assignment.setRepId(repId);
        assignment.setIsPrimary(primary);
        assignment.insert();
        bump();
    }

    private static void insertCredit(long id, long customerId, String code, int score, String agency)
    {
        CreditRating rating = new CreditRating(DemoTimestamps.B_START);
        rating.setRatingId(id);
        rating.setCustomerId(customerId);
        rating.setRatingCode(code);
        rating.setScoreNumeric(score);
        rating.setAgency(agency);
        rating.insert();
        bump();
    }

    private static void insertConsent(long id, long contactId, String channel, boolean granted,
                                      String basis, String source)
    {
        ConsentRecord consent = new ConsentRecord(DemoTimestamps.B_START);
        consent.setConsentId(id);
        consent.setContactId(contactId);
        consent.setChannel(channel);
        consent.setGranted(granted);
        consent.setLawfulBasis(basis);
        consent.setSourceRef(source);
        consent.insert();
        bump();
    }

    private static void insertAddress(long id, long customerId, String type, String line1, String line2,
                                      String city, String state, String postal, String country)
    {
        Address address = new Address(DemoTimestamps.B_START);
        address.setAddressId(id);
        address.setCustomerId(customerId);
        address.setAddressType(type);
        address.setLine1(line1);
        address.setLine2(line2);
        address.setCity(city);
        address.setState(state);
        address.setPostalCode(postal);
        address.setCountryCode(country);
        address.insert();
        bump();
    }

    private static void insertPhone(long id, long contactId, String type, String e164, boolean verified)
    {
        ContactPhone phone = new ContactPhone(DemoTimestamps.B_START);
        phone.setPhoneId(id);
        phone.setContactId(contactId);
        phone.setPhoneType(type);
        phone.setE164Number(e164);
        phone.setIsVerified(verified);
        phone.insert();
        bump();
    }

    private static void insertEmail(long id, long contactId, String email, boolean verified, int bounces)
    {
        ContactEmail contactEmail = new ContactEmail(DemoTimestamps.B_START);
        contactEmail.setEmailId(id);
        contactEmail.setContactId(contactId);
        contactEmail.setEmailAddress(email);
        contactEmail.setIsVerified(verified);
        contactEmail.setBounceCount(bounces);
        contactEmail.insert();
        bump();
    }

    private static void insertOpportunity(long id, long customerId, String name, String stage, BigDecimal amount,
                                          int probability, Timestamp close, long owner)
    {
        Opportunity opportunity = new Opportunity(DemoTimestamps.B_START);
        opportunity.setOpportunityId(id);
        opportunity.setCustomerId(customerId);
        opportunity.setName(name);
        opportunity.setStageCode(stage);
        opportunity.setAmount(amount);
        opportunity.setProbability(probability);
        opportunity.setExpectedCloseDate(close);
        opportunity.setOwnerRepId(owner);
        opportunity.insert();
        bump();
    }

    private static void insertQuote(long id, long opportunityId, String number, String status, BigDecimal total)
    {
        Quote quote = new Quote(DemoTimestamps.B_START);
        quote.setQuoteId(id);
        quote.setOpportunityId(opportunityId);
        quote.setQuoteNumber(number);
        quote.setStatus(status);
        quote.setTotalAmount(total);
        quote.setValidUntil(DemoTimestamps.utc(2025, 6, 30));
        quote.insert();
        bump();
    }

    private static void insertQuoteLine(long id, long quoteId, long productId, int qty, BigDecimal price,
                                        BigDecimal discount)
    {
        QuoteLineItem line = new QuoteLineItem(DemoTimestamps.B_START);
        line.setLineId(id);
        line.setQuoteId(quoteId);
        line.setProductId(productId);
        line.setQuantity(qty);
        line.setUnitPrice(price);
        line.setDiscountPct(discount);
        line.insert();
        bump();
    }

    private static void insertContract(long id, long customerId, String number, String status, BigDecimal tcv)
    {
        Contract contract = new Contract(DemoTimestamps.B_START);
        contract.setContractId(id);
        contract.setCustomerId(customerId);
        contract.setContractNumber(number);
        contract.setStatus(status);
        contract.setStartDate(DemoTimestamps.B_START);
        contract.setEndDate(DemoTimestamps.utc(2026, 1, 1));
        contract.setAutoRenew(true);
        contract.setTcv(tcv);
        contract.insert();
        bump();
    }

    private static void insertSubscription(long id, long contractId, long productId, int seats, BigDecimal mrr)
    {
        Subscription subscription = new Subscription(DemoTimestamps.B_START);
        subscription.setSubscriptionId(id);
        subscription.setContractId(contractId);
        subscription.setProductId(productId);
        subscription.setSeats(seats);
        subscription.setMrr(mrr);
        subscription.setBillingCycle("MONTHLY");
        subscription.insert();
        bump();
    }

    private static void insertPrice(long id, long productId, BigDecimal unitPrice)
    {
        PriceBookEntry entry = new PriceBookEntry(DemoTimestamps.B_START);
        entry.setEntryId(id);
        entry.setPriceBookId(DemoIds.PRICE_BOOK_STANDARD);
        entry.setProductId(productId);
        entry.setUnitPrice(unitPrice);
        entry.setCurrency("USD");
        entry.insert();
        bump();
    }

    private static void insertCall(long id, long contactId, long customerId, long repId, String direction,
                                   Timestamp start, int duration, String outcome)
    {
        Call call = new Call(InfinityTimestamp.getInfinityDate());
        call.setCallId(id);
        call.setContactId(contactId);
        call.setCustomerId(customerId);
        call.setRepId(repId);
        call.setDirection(direction);
        call.setStartTime(start);
        call.setDurationSeconds(duration);
        call.setOutcomeCode(outcome);
        call.setNotes("seed");
        call.insert();
        bump();
    }

    private static void insertMeeting(long id, long customerId, long repId, String subject, String location,
                                      String outcome)
    {
        Meeting meeting = new Meeting(InfinityTimestamp.getInfinityDate());
        meeting.setMeetingId(id);
        meeting.setCustomerId(customerId);
        meeting.setRepId(repId);
        meeting.setSubject(subject);
        meeting.setStartTime(DemoTimestamps.utc(2025, 1, 21, 17, 0, 0));
        meeting.setEndTime(DemoTimestamps.utc(2025, 1, 21, 18, 0, 0));
        meeting.setLocationType(location);
        meeting.setOutcomeCode(outcome);
        meeting.insert();
        bump();
    }

    private static void insertTask(long id, long customerId, long repId, String subject, String status,
                                   String priority)
    {
        TaskItem task = new TaskItem(InfinityTimestamp.getInfinityDate());
        task.setTaskId(id);
        task.setCustomerId(customerId);
        task.setRepId(repId);
        task.setSubject(subject);
        task.setDueDate(DemoTimestamps.utc(2025, 2, 1));
        task.setStatus(status);
        task.setPriority(priority);
        task.insert();
        bump();
    }

    private static void insertNote(long id, String entityType, long entityId, long repId, String body)
    {
        Note note = new Note(InfinityTimestamp.getInfinityDate());
        note.setNoteId(id);
        note.setEntityType(entityType);
        note.setEntityId(entityId);
        note.setRepId(repId);
        note.setBody(body);
        note.setCreatedTime(DemoTimestamps.utc(2025, 1, 18, 9, 0, 0));
        note.insert();
        bump();
    }

    private static void insertCampaignMember(long id, long campaignId, long contactId, String status)
    {
        CampaignMember member = new CampaignMember(DemoTimestamps.B_START);
        member.setMemberId(id);
        member.setCampaignId(campaignId);
        member.setContactId(contactId);
        member.setMemberStatus(status);
        member.insert();
        bump();
    }

    private static void insertStep(long id, long sequenceId, int number, String channel, int delay, long templateId)
    {
        OutreachStep step = new OutreachStep(DemoTimestamps.B_START);
        step.setStepId(id);
        step.setSequenceId(sequenceId);
        step.setStepNumber(number);
        step.setChannel(channel);
        step.setDelayDays(delay);
        step.setTemplateId(templateId);
        step.insert();
        bump();
    }

    private static BigDecimal money(String value)
    {
        return new BigDecimal(value);
    }

    private static void bump()
    {
        inserted++;
    }
}
