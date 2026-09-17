package com.reladynamo.demo.crm;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.reladynamo.demo.crm.seed.SeedData;
import com.reladynamo.demo.crm.util.DemoTimestamps;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

class RequiredDemonstrationsTest
{
    private static final ReladomoTestHarness HARNESS = new ReladomoTestHarness();

    @BeforeAll
    static void setUp()
    {
        HARNESS.setUp();
    }

    @AfterAll
    static void tearDown()
    {
        HARNESS.tearDown();
    }

    @Test
    void should_seed_exactly_100_logical_records()
    {
        assertThat(SeedData.lastInsertCount()).isEqualTo(100);
    }

    @Test
    void should_return_old_address_when_processing_date_is_before_correction()
    {
        Address address = CrmAsOfQueries.acmeHq(DemoTimestamps.B_QUERY_APR, DemoTimestamps.P_KNOWN_APR);

        assertThat(address).isNotNull();
        assertThat(address.getLine1()).isEqualTo("100 Market St");
        assertThat(address.getPostalCode()).isEqualTo("94103");
    }

    @Test
    void should_return_new_address_when_processing_date_is_after_correction()
    {
        Address address = CrmAsOfQueries.acmeHq(DemoTimestamps.B_QUERY_APR, DemoTimestamps.P_TODAY);

        assertThat(address).isNotNull();
        assertThat(address.getLine1()).isEqualTo("200 Mission St");
        assertThat(address.getPostalCode()).isEqualTo("94105");
    }

    @Test
    void should_return_different_addresses_for_same_business_date_across_processing_dates()
    {
        Address thenKnown = CrmAsOfQueries.acmeHq(DemoTimestamps.B_QUERY_APR, DemoTimestamps.P_KNOWN_APR);
        Address nowKnown = CrmAsOfQueries.acmeHq(DemoTimestamps.B_QUERY_APR, DemoTimestamps.P_TODAY);

        assertThat(thenKnown.getLine1()).isNotEqualTo(nowKnown.getLine1());
    }

    @Test
    void should_return_west_territory_alice_when_processing_date_is_before_reassignment()
    {
        TerritoryAssignment assignment = CrmAsOfQueries.acmeTerritory(
                DemoTimestamps.B_QUERY_APR, DemoTimestamps.P_KNOWN_APR);

        assertThat(assignment).isNotNull();
        assertThat(assignment.getTerritoryId()).isEqualTo(DemoIds.TERRITORY_WEST);
        assertThat(assignment.getRepId()).isEqualTo(DemoIds.ALICE);
    }

    @Test
    void should_return_east_territory_bob_when_processing_date_is_after_reassignment()
    {
        TerritoryAssignment assignment = CrmAsOfQueries.acmeTerritory(
                DemoTimestamps.B_QUERY_APR, DemoTimestamps.P_TODAY);

        assertThat(assignment).isNotNull();
        assertThat(assignment.getTerritoryId()).isEqualTo(DemoIds.TERRITORY_EAST);
        assertThat(assignment.getRepId()).isEqualTo(DemoIds.BOB);
    }

    @Test
    void should_return_original_opportunity_amount_when_processing_date_is_before_restatement()
    {
        Opportunity opportunity = CrmAsOfQueries.acmeOpportunity(
                DemoTimestamps.utc(2025, 6, 1), DemoTimestamps.utc(2025, 6, 2, 9, 0, 0));

        assertThat(opportunity).isNotNull();
        assertThat(opportunity.getStageCode()).isEqualTo("CLOSED_WON");
        assertThat(opportunity.getAmount()).isEqualByComparingTo(new BigDecimal("100000.00"));
    }

    @Test
    void should_return_restated_opportunity_amount_when_processing_date_is_after_restatement()
    {
        Opportunity opportunity = CrmAsOfQueries.acmeOpportunity(
                DemoTimestamps.utc(2025, 6, 1), DemoTimestamps.P_TODAY);

        assertThat(opportunity).isNotNull();
        assertThat(opportunity.getStageCode()).isEqualTo("CLOSED_WON");
        assertThat(opportunity.getAmount()).isEqualByComparingTo(new BigDecimal("85000.00"));
    }

    @Test
    void should_show_consent_granted_when_processing_date_is_before_withdrawal()
    {
        ConsentRecord consent = CrmAsOfQueries.acmeEmailConsent(
                DemoTimestamps.B_QUERY_APR, DemoTimestamps.P_KNOWN_APR);

        assertThat(consent).isNotNull();
        assertThat(consent.isGranted()).isTrue();
        assertThat(consent.getLawfulBasis()).isEqualTo("consent");
    }

    @Test
    void should_show_consent_absent_when_processing_date_is_after_withdrawal()
    {
        ConsentRecord consent = CrmAsOfQueries.acmeEmailConsent(
                DemoTimestamps.B_QUERY_APR, DemoTimestamps.P_TODAY);

        assertThat(consent).isNotNull();
        assertThat(consent.isGranted()).isFalse();
        assertThat(consent.getLawfulBasis()).isEqualTo("withdrawn");
    }

    @Test
    void should_find_subscription_before_terminate_and_not_after()
    {
        Subscription before = CrmAsOfQueries.acmeSubscription(
                DemoTimestamps.B_BEFORE_SUB_END, DemoTimestamps.P_TODAY);
        Subscription after = CrmAsOfQueries.acmeSubscription(
                DemoTimestamps.B_AFTER_SUB_END, DemoTimestamps.P_TODAY);

        assertThat(before).isNotNull();
        assertThat(before.getMrr()).isEqualByComparingTo(new BigDecimal("10000.00"));
        assertThat(after).isNull();
    }

    @Test
    void should_find_sales_rep_before_terminate_and_not_after()
    {
        SalesRep before = CrmAsOfQueries.carol(DemoTimestamps.B_BEFORE_CAROL_TERM, DemoTimestamps.P_TODAY);
        SalesRep after = CrmAsOfQueries.carol(DemoTimestamps.B_AFTER_CAROL_TERM, DemoTimestamps.P_TODAY);

        assertThat(before).isNotNull();
        assertThat(before.getEmail()).isEqualTo("carol@reladynamo.test");
        assertThat(after).isNull();
    }

    @Test
    void should_apply_updateUntil_and_incrementUntil_on_price_book_entry()
    {
        PriceBookEntry pre = CrmAsOfQueries.corePrice(DemoTimestamps.B_PRE_PROMO, DemoTimestamps.P_TODAY);
        PriceBookEntry promo = CrmAsOfQueries.corePrice(DemoTimestamps.B_IN_PROMO, DemoTimestamps.P_TODAY);
        PriceBookEntry surcharge = CrmAsOfQueries.corePrice(DemoTimestamps.B_IN_SURCHARGE, DemoTimestamps.P_TODAY);
        PriceBookEntry post = CrmAsOfQueries.corePrice(DemoTimestamps.B_POST_SURCHARGE, DemoTimestamps.P_TODAY);

        assertThat(pre.getUnitPrice()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(promo.getUnitPrice()).isEqualByComparingTo(new BigDecimal("80.00"));
        assertThat(surcharge.getUnitPrice()).isEqualByComparingTo(new BigDecimal("105.00"));
        assertThat(post.getUnitPrice()).isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    void should_reconstruct_every_processing_version_of_customer()
    {
        CustomerList history = CrmAsOfQueries.acmeHistoryRectangles();

        assertThat(history.size()).isGreaterThanOrEqualTo(3);

        Customer first = history.get(0);
        assertThat(first.getName()).isEqualTo("Acme Corp");
        assertThat(first.getAnnualRevenue()).isEqualByComparingTo(new BigDecimal("50000000.00"));

        boolean sawRename = false;
        boolean sawRevenue = false;
        for (int i = 0; i < history.size(); i++)
        {
            Customer customer = history.get(i);
            if ("Acme Corporation".equals(customer.getName()))
            {
                sawRename = true;
            }
            if (customer.getAnnualRevenue().compareTo(new BigDecimal("62000000.00")) == 0)
            {
                sawRevenue = true;
            }
        }
        assertThat(sawRename).isTrue();
        assertThat(sawRevenue).isTrue();
    }
}
