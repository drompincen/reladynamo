package com.reladynamo.demo.crm.demo;

import com.reladynamo.demo.crm.domain.Address;
import com.reladynamo.demo.crm.domain.ConsentRecord;
import com.reladynamo.demo.crm.domain.Customer;
import com.reladynamo.demo.crm.domain.CustomerList;
import com.reladynamo.demo.crm.domain.Opportunity;
import com.reladynamo.demo.crm.domain.PriceBookEntry;
import com.reladynamo.demo.crm.domain.SalesRep;
import com.reladynamo.demo.crm.domain.Subscription;
import com.reladynamo.demo.crm.domain.TerritoryAssignment;
import com.reladynamo.demo.crm.query.CrmAsOfQueries;
import com.reladynamo.demo.crm.util.AsOfPrinter;
import com.reladynamo.demo.crm.util.DemoTimestamps;

import java.io.PrintStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Walks the seven required bitemporal demonstrations and prints labelled tables.
 */
public final class Demonstrations
{
    private Demonstrations()
    {
    }

    public static void printAll(PrintStream out)
    {
        demo1RetroactiveAddress(out);
        demo2TerritoryReassignment(out);
        demo3OpportunityRestatement(out);
        demo4ConsentWithdrawal(out);
        demo5Terminate(out);
        demo6PriceUntil(out);
        demo7CustomerHistory(out);
    }

    public static void demo1RetroactiveAddress(PrintStream out)
    {
        AsOfPrinter.section(out, "1. Retroactive address correction");
        out.println("Acme moved on business date 2025-03-01; we only learned on processing date 2025-06-15.");
        out.println("Same business date 2025-04-01 queried from two processing dates must disagree.");
        Address thenKnown = CrmAsOfQueries.acmeHq(DemoTimestamps.B_QUERY_APR, DemoTimestamps.P_KNOWN_APR);
        Address nowKnown = CrmAsOfQueries.acmeHq(DemoTimestamps.B_QUERY_APR, DemoTimestamps.P_TODAY);
        AsOfPrinter.table(out,
                headers("asOfBusiness", "asOfProcessing", "line1", "city", "postal"),
                Arrays.asList(
                        addressRow("2025-04-01", "2025-04-02 (as known then)", thenKnown),
                        addressRow("2025-04-01", "2025-10-01 (as known now)", nowKnown)));
    }

    public static void demo2TerritoryReassignment(PrintStream out)
    {
        AsOfPrinter.section(out, "2. Territory reassignment effective in the past");
        out.println("Acme was reassigned West->East effective 2025-02-01; recorded 2025-08-01.");
        out.println("Commission owner is the assigned rep at the as-of rectangle.");
        TerritoryAssignment thenKnown = CrmAsOfQueries.acmeTerritory(
                DemoTimestamps.B_QUERY_APR, DemoTimestamps.P_KNOWN_APR);
        TerritoryAssignment nowKnown = CrmAsOfQueries.acmeTerritory(
                DemoTimestamps.B_QUERY_APR, DemoTimestamps.P_TODAY);
        AsOfPrinter.table(out,
                headers("asOfBusiness", "asOfProcessing", "territoryId", "repId", "commissionOwner"),
                Arrays.asList(
                        territoryRow("2025-04-01", "2025-04-02 (as known then)", thenKnown),
                        territoryRow("2025-04-01", "2025-10-01 (as known now)", nowKnown)));
    }

    public static void demo3OpportunityRestatement(PrintStream out)
    {
        AsOfPrinter.section(out, "3. Opportunity amount restated after close");
        out.println("Closed won 2025-05-15 at $100,000; restated to $85,000 on 2025-08-01.");
        out.println("The originally reported pipeline remains reproducible via the April/June processing date.");
        Opportunity thenKnown = CrmAsOfQueries.acmeOpportunity(
                DemoTimestamps.utc(2025, 6, 1), DemoTimestamps.utc(2025, 6, 2, 9, 0, 0));
        Opportunity nowKnown = CrmAsOfQueries.acmeOpportunity(
                DemoTimestamps.utc(2025, 6, 1), DemoTimestamps.P_TODAY);
        AsOfPrinter.table(out,
                headers("asOfBusiness", "asOfProcessing", "stage", "amount", "probability"),
                Arrays.asList(
                        opportunityRow("2025-06-01", "2025-06-02 (audit report)", thenKnown),
                        opportunityRow("2025-06-01", "2025-10-01 (as known now)", nowKnown)));
    }

    public static void demo4ConsentWithdrawal(PrintStream out)
    {
        AsOfPrinter.section(out, "4. Consent withdrawn effective a past date (GDPR)");
        out.println("Outreach email sent 2025-04-01 14:00. Consent withdrawn effective 2025-03-15, recorded 2025-07-01.");
        out.println("Lawful as known then; absent as known now - for the same business date as the send.");
        ConsentRecord thenKnown = CrmAsOfQueries.acmeEmailConsent(
                DemoTimestamps.B_QUERY_APR, DemoTimestamps.P_KNOWN_APR);
        ConsentRecord nowKnown = CrmAsOfQueries.acmeEmailConsent(
                DemoTimestamps.B_QUERY_APR, DemoTimestamps.P_TODAY);
        AsOfPrinter.table(out,
                headers("asOfBusiness", "asOfProcessing", "granted", "lawfulBasis", "outreachLawful"),
                Arrays.asList(
                        consentRow("2025-04-01", "2025-04-02 (as known then)", thenKnown),
                        consentRow("2025-04-01", "2025-10-01 (as known now)", nowKnown)));
    }

    public static void demo5Terminate(PrintStream out)
    {
        AsOfPrinter.section(out, "5. Terminate / expire Subscription and SalesRep");
        Subscription subBefore = CrmAsOfQueries.acmeSubscription(
                DemoTimestamps.B_BEFORE_SUB_END, DemoTimestamps.P_TODAY);
        Subscription subAfter = CrmAsOfQueries.acmeSubscription(
                DemoTimestamps.B_AFTER_SUB_END, DemoTimestamps.P_TODAY);
        SalesRep carolBefore = CrmAsOfQueries.carol(
                DemoTimestamps.B_BEFORE_CAROL_TERM, DemoTimestamps.P_TODAY);
        SalesRep carolAfter = CrmAsOfQueries.carol(
                DemoTimestamps.B_AFTER_CAROL_TERM, DemoTimestamps.P_TODAY);
        AsOfPrinter.table(out,
                headers("entity", "asOfBusiness", "asOfProcessing", "present", "detail"),
                Arrays.asList(
                        AsOfPrinter.row("Subscription", "2025-08-01", "2025-10-01",
                                present(subBefore), subBefore == null ? "" : "mrr=" + subBefore.getMrr()),
                        AsOfPrinter.row("Subscription", "2025-08-16", "2025-10-01",
                                present(subAfter), ""),
                        AsOfPrinter.row("SalesRep Carol", "2025-08-01", "2025-10-01",
                                present(carolBefore), carolBefore == null ? "" : carolBefore.getEmail()),
                        AsOfPrinter.row("SalesRep Carol", "2025-09-01", "2025-10-01",
                                present(carolAfter), "")));
    }

    public static void demo6PriceUntil(PrintStream out)
    {
        AsOfPrinter.section(out, "6. updateUntil / incrementUntil on PriceBookEntry");
        out.println("Base $100. updateUntil $80 for [2025-04-01, 2025-07-01). incrementUntil +$5 for [2025-07-01, 2025-08-01).");
        PriceBookEntry pre = CrmAsOfQueries.corePrice(DemoTimestamps.B_PRE_PROMO, DemoTimestamps.P_TODAY);
        PriceBookEntry promo = CrmAsOfQueries.corePrice(DemoTimestamps.B_IN_PROMO, DemoTimestamps.P_TODAY);
        PriceBookEntry surcharge = CrmAsOfQueries.corePrice(DemoTimestamps.B_IN_SURCHARGE, DemoTimestamps.P_TODAY);
        PriceBookEntry post = CrmAsOfQueries.corePrice(DemoTimestamps.B_POST_SURCHARGE, DemoTimestamps.P_TODAY);
        AsOfPrinter.table(out,
                headers("asOfBusiness", "asOfProcessing", "unitPrice", "window"),
                Arrays.asList(
                        priceRow("2025-02-01", pre, "base"),
                        priceRow("2025-05-01", promo, "promo updateUntil"),
                        priceRow("2025-07-15", surcharge, "surcharge incrementUntil"),
                        priceRow("2025-09-01", post, "after bounded windows")));
    }

    public static void demo7CustomerHistory(PrintStream out)
    {
        AsOfPrinter.section(out, "7. Full history reconstruction for Customer Acme");
        out.println("Every processing-date version (edge-point query on both temporal axes).");
        CustomerList history = CrmAsOfQueries.acmeHistoryRectangles();
        List<List<String>> rows = new ArrayList<List<String>>();
        for (int i = 0; i < history.size(); i++)
        {
            Customer customer = history.get(i);
            rows.add(AsOfPrinter.row(
                    AsOfPrinter.ts(customer.getProcessingDateFrom()),
                    AsOfPrinter.ts(customer.getProcessingDateTo()),
                    AsOfPrinter.ts(customer.getBusinessDateFrom()),
                    AsOfPrinter.ts(customer.getBusinessDateTo()),
                    customer.getName(),
                    money(customer.getAnnualRevenue()),
                    customer.getStatus()));
        }
        AsOfPrinter.table(out,
                headers("inZ", "outZ", "businessFrom", "businessThru", "name", "annualRevenue", "status"),
                rows);
        out.println("Rectangle count: " + history.size());
    }

    private static List<String> headers(String... names)
    {
        return Arrays.asList(names);
    }

    private static List<String> addressRow(String business, String processing, Address address)
    {
        if (address == null)
        {
            return AsOfPrinter.row(business, processing, "(none)", "", "");
        }
        return AsOfPrinter.row(business, processing, address.getLine1(), address.getCity(), address.getPostalCode());
    }

    private static List<String> territoryRow(String business, String processing, TerritoryAssignment assignment)
    {
        if (assignment == null)
        {
            return AsOfPrinter.row(business, processing, "(none)", "", "");
        }
        String owner = assignment.getRepId() == 1L ? "Alice" : assignment.getRepId() == 2L ? "Bob" : Long.toString(assignment.getRepId());
        return AsOfPrinter.row(business, processing,
                Long.toString(assignment.getTerritoryId()),
                Long.toString(assignment.getRepId()),
                owner);
    }

    private static List<String> opportunityRow(String business, String processing, Opportunity opportunity)
    {
        if (opportunity == null)
        {
            return AsOfPrinter.row(business, processing, "(none)", "", "");
        }
        return AsOfPrinter.row(business, processing, opportunity.getStageCode(),
                money(opportunity.getAmount()), Integer.toString(opportunity.getProbability()));
    }

    private static List<String> consentRow(String business, String processing, ConsentRecord consent)
    {
        if (consent == null)
        {
            return AsOfPrinter.row(business, processing, "false", "(none)", "NO");
        }
        return AsOfPrinter.row(business, processing,
                Boolean.toString(consent.isGranted()),
                consent.getLawfulBasis(),
                consent.isGranted() ? "YES" : "NO");
    }

    private static List<String> priceRow(String business, PriceBookEntry entry, String window)
    {
        return AsOfPrinter.row(business, "2025-10-01",
                entry == null ? "(none)" : money(entry.getUnitPrice()),
                window);
    }

    private static String present(Object value)
    {
        return value == null ? "NO" : "YES";
    }

    private static String money(BigDecimal value)
    {
        return value == null ? "" : value.toPlainString();
    }
}
