package com.reladynamo.demo.crm.query;

import com.gs.fw.common.mithra.finder.Operation;
import com.reladynamo.demo.crm.DemoIds;
import com.reladynamo.demo.crm.domain.Address;
import com.reladynamo.demo.crm.domain.AddressFinder;
import com.reladynamo.demo.crm.domain.ConsentRecord;
import com.reladynamo.demo.crm.domain.ConsentRecordFinder;
import com.reladynamo.demo.crm.domain.Customer;
import com.reladynamo.demo.crm.domain.CustomerFinder;
import com.reladynamo.demo.crm.domain.CustomerList;
import com.reladynamo.demo.crm.domain.Opportunity;
import com.reladynamo.demo.crm.domain.OpportunityFinder;
import com.reladynamo.demo.crm.domain.PriceBookEntry;
import com.reladynamo.demo.crm.domain.PriceBookEntryFinder;
import com.reladynamo.demo.crm.domain.SalesRep;
import com.reladynamo.demo.crm.domain.SalesRepFinder;
import com.reladynamo.demo.crm.domain.Subscription;
import com.reladynamo.demo.crm.domain.SubscriptionFinder;
import com.reladynamo.demo.crm.domain.TerritoryAssignment;
import com.reladynamo.demo.crm.domain.TerritoryAssignmentFinder;

import java.sql.Timestamp;

public final class CrmAsOfQueries
{
    private CrmAsOfQueries()
    {
    }

    public static Address acmeHq(Timestamp businessDate, Timestamp processingDate)
    {
        return AddressFinder.findByPrimaryKey(DemoIds.ADDRESS_HQ, businessDate, processingDate);
    }

    public static TerritoryAssignment acmeTerritory(Timestamp businessDate, Timestamp processingDate)
    {
        return TerritoryAssignmentFinder.findByPrimaryKey(
                DemoIds.TERRITORY_ASSIGNMENT_ACME, businessDate, processingDate);
    }

    public static Opportunity acmeOpportunity(Timestamp businessDate, Timestamp processingDate)
    {
        return OpportunityFinder.findByPrimaryKey(DemoIds.OPP_ACME, businessDate, processingDate);
    }

    public static ConsentRecord acmeEmailConsent(Timestamp businessDate, Timestamp processingDate)
    {
        return ConsentRecordFinder.findByPrimaryKey(DemoIds.CONSENT_EMAIL, businessDate, processingDate);
    }

    public static Subscription acmeSubscription(Timestamp businessDate, Timestamp processingDate)
    {
        return SubscriptionFinder.findByPrimaryKey(DemoIds.SUB_ACME, businessDate, processingDate);
    }

    public static SalesRep carol(Timestamp businessDate, Timestamp processingDate)
    {
        return SalesRepFinder.findByPrimaryKey(DemoIds.CAROL, businessDate, processingDate);
    }

    public static PriceBookEntry corePrice(Timestamp businessDate, Timestamp processingDate)
    {
        return PriceBookEntryFinder.findByPrimaryKey(DemoIds.PRICE_CORE, businessDate, processingDate);
    }

    public static CustomerList acmeHistoryRectangles()
    {
        Operation operation = CustomerFinder.customerId().eq(DemoIds.ACME)
                .and(CustomerFinder.businessDate().equalsEdgePoint())
                .and(CustomerFinder.processingDate().equalsEdgePoint());
        CustomerList list = CustomerFinder.findMany(operation);
        list.setOrderBy(CustomerFinder.processingDateFrom().ascendingOrderBy()
                .and(CustomerFinder.businessDateFrom().ascendingOrderBy()));
        return list;
    }

    public static Customer acme(Timestamp businessDate, Timestamp processingDate)
    {
        return CustomerFinder.findByPrimaryKey(DemoIds.ACME, businessDate, processingDate);
    }
}
