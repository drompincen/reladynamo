package com.reladynamo.demo.petstore;

import static org.assertj.core.api.Assertions.assertThat;

import com.reladynamo.demo.petstore.domain.Employee;
import com.reladynamo.demo.petstore.domain.EmployeeFinder;
import com.reladynamo.demo.petstore.domain.Pet;
import com.reladynamo.demo.petstore.domain.PetFinder;
import com.reladynamo.demo.petstore.domain.Product;
import com.reladynamo.demo.petstore.domain.ProductFinder;
import com.reladynamo.demo.petstore.domain.ProductList;
import com.reladynamo.demo.petstore.domain.SalesOrder;
import com.reladynamo.demo.petstore.domain.SalesOrderFinder;
import com.reladynamo.demo.petstore.domain.SalesOrderList;
import com.reladynamo.demo.petstore.domain.StockLevel;
import com.reladynamo.demo.petstore.domain.StockLevelFinder;
import com.reladynamo.demo.petstore.runtime.PetstoreHarness;
import com.reladynamo.demo.petstore.runtime.Timestamps;
import com.reladynamo.demo.petstore.store.PhysicalStore;
import java.lang.reflect.Method;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PetstoreDemonstrationsTest {

    @BeforeAll
    static void start() {
        PetstoreHarness.start();
    }

    @Test
    void priceChangeSplitsBusinessDateWindow() {
        Product june = ProductFinder.findByPrimaryKey(Ids.PRODUCT_PRICE_DEMO, Timestamps.DAY_2025_06_15);
        Product august = ProductFinder.findByPrimaryKey(Ids.PRODUCT_PRICE_DEMO, Timestamps.DAY_2025_08_15);

        assertThat(june).isNotNull();
        assertThat(august).isNotNull();
        assertThat(june.getUnitPrice()).isEqualByComparingTo("10.00");
        assertThat(august.getUnitPrice()).isEqualByComparingTo("12.50");

        List<PhysicalStore.ProductRow> rows = PhysicalStore.productRows(Ids.PRODUCT_PRICE_DEMO);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).unitPrice()).isEqualByComparingTo("10.00");
        assertThat(rows.get(0).thruZ()).isEqualTo(Timestamps.DAY_2025_07_01);
        assertThat(rows.get(1).unitPrice()).isEqualByComparingTo("12.50");
        assertThat(rows.get(1).fromZ()).isEqualTo(Timestamps.DAY_2025_07_01);
        assertThat(rows.get(1).thruZ()).isEqualTo(Timestamps.infinity());
    }

    @Test
    void stockLevelIsQueryableOnAnyPastBusinessDate() {
        assertThat(qty(Timestamps.utcDate(2025, 2, 1))).isEqualTo(40);
        assertThat(qty(Timestamps.utcDate(2025, 3, 15))).isEqualTo(55);
        assertThat(qty(Timestamps.DAY_2025_06_15)).isEqualTo(28);
        assertThat(qty(Timestamps.DAY_2025_10_01)).isEqualTo(70);

        List<PhysicalStore.StockRow> rows = PhysicalStore.stockRows(Ids.STOCK_PRICE_DEMO);
        assertThat(rows).hasSize(4);
        assertThat(rows).extracting(PhysicalStore.StockRow::quantityOnHand).containsExactly(40, 55, 28, 70);
    }

    @Test
    void nonAuditedCorrectionDestroysThePriorValue() {
        Product asOfJune =
                ProductFinder.findByPrimaryKey(Ids.PRODUCT_CORRECTION_DEMO, Timestamps.DAY_2025_06_15);
        assertThat(asOfJune).isNotNull();
        assertThat(asOfJune.getUnitPrice()).isEqualByComparingTo("14.99");

        ProductList everySlice = ProductFinder.findMany(
                ProductFinder.productId()
                        .eq(Ids.PRODUCT_CORRECTION_DEMO)
                        .and(ProductFinder.businessDate().equalsEdgePoint()));
        assertThat(everySlice).isNotEmpty();
        assertThat(everySlice)
                .allSatisfy(slice -> assertThat(slice.getUnitPrice()).isEqualByComparingTo("14.99"));
        assertThat(everySlice)
                .noneSatisfy(slice -> assertThat(slice.getUnitPrice()).isEqualByComparingTo("9.99"));

        List<PhysicalStore.ProductRow> physical = PhysicalStore.productRows(Ids.PRODUCT_CORRECTION_DEMO);
        assertThat(physical).hasSize(1);
        assertThat(physical.getFirst().unitPrice()).isEqualByComparingTo("14.99");
        assertThat(physical)
                .extracting(PhysicalStore.ProductRow::unitPrice)
                .noneMatch(price -> price.compareTo(Money.of("9.99")) == 0);

        assertThat(ProductFinder.getAsOfAttributes()).hasSize(1);
        assertThat(ProductFinder.getAsOfAttributes()[0].getAttributeName()).isEqualTo("businessDate");
        assertThat(ProductFinder.getAttributeByName("processingDate")).isNull();
    }

    @Test
    void auditOnlyTrailRetainsEveryProcessingVersion() {
        SalesOrderList versions = SalesOrderFinder.findMany(
                SalesOrderFinder.orderId()
                        .eq(Ids.ORDER_AUDIT_DEMO)
                        .and(SalesOrderFinder.processingDate().equalsEdgePoint()));
        versions.setOrderBy(SalesOrderFinder.processingDateFrom().ascendingOrderBy());

        assertThat(versions).hasSize(3);
        assertThat(versions).extracting(SalesOrder::getStatus).containsExactly("OPEN", "PAID", "FULFILLED");
        assertThat(versions.get(0).getTotalAmount()).isEqualByComparingTo("49.95");
        assertThat(versions.get(1).getTotalAmount()).isEqualByComparingTo("54.95");
        assertThat(versions.get(2).getTotalAmount()).isEqualByComparingTo("54.95");
        assertThat(versions.get(0).getProcessingDateTo()).isNotEqualTo(Timestamps.infinity());
        assertThat(versions.get(1).getProcessingDateTo()).isNotEqualTo(Timestamps.infinity());
        assertThat(versions.get(2).getProcessingDateTo()).isEqualTo(Timestamps.infinity());

        SalesOrder current = SalesOrderFinder.findByPrimaryKey(Ids.ORDER_AUDIT_DEMO, Timestamps.infinity());
        assertThat(current.getStatus()).isEqualTo("FULFILLED");

        List<PhysicalStore.SalesOrderRow> physical = PhysicalStore.salesOrderRows(Ids.ORDER_AUDIT_DEMO);
        assertThat(physical).hasSize(3);
        assertThat(physical).extracting(PhysicalStore.SalesOrderRow::status)
                .containsExactly("OPEN", "PAID", "FULFILLED");
    }

    @Test
    void terminateEndsPetListingAndEmployeeOnTheBoundary() {
        Pet dayBefore = PetFinder.findByPrimaryKey(Ids.PET_TERMINATE_DEMO, Timestamps.DAY_2025_08_14);
        Pet onDay = PetFinder.findByPrimaryKey(Ids.PET_TERMINATE_DEMO, Timestamps.DAY_2025_08_15);
        Pet after = PetFinder.findByPrimaryKey(Ids.PET_TERMINATE_DEMO, Timestamps.DAY_2025_09_01);

        assertThat(dayBefore).isNotNull();
        assertThat(dayBefore.getName()).isEqualTo("Wicket");
        assertThat(onDay).isNull();
        assertThat(after).isNull();

        List<PhysicalStore.PetRow> petRows = PhysicalStore.petRows(Ids.PET_TERMINATE_DEMO);
        assertThat(petRows).hasSize(1);
        assertThat(petRows.getFirst().thruZ()).isEqualTo(Timestamps.DAY_2025_08_15);

        Employee employed =
                EmployeeFinder.findByPrimaryKey(Ids.EMPLOYEE_TERMINATE_DEMO, Timestamps.DAY_2025_09_30);
        Employee departed =
                EmployeeFinder.findByPrimaryKey(Ids.EMPLOYEE_TERMINATE_DEMO, Timestamps.DAY_2025_10_01);
        assertThat(employed).isNotNull();
        assertThat(employed.getLastName()).isEqualTo("Faraday");
        assertThat(departed).isNull();

        List<PhysicalStore.EmployeeRow> employeeRows = PhysicalStore.employeeRows(Ids.EMPLOYEE_TERMINATE_DEMO);
        assertThat(employeeRows).hasSize(1);
        assertThat(employeeRows.getFirst().thruZ()).isEqualTo(Timestamps.DAY_2025_10_01);
    }

    @Test
    void unitemporalAsOfQueryTakesOneDateNotTwo() {
        Method productKey = Arrays.stream(ProductFinder.class.getMethods())
                .filter(method -> method.getName().equals("findByPrimaryKey"))
                .findFirst()
                .orElseThrow();
        Method orderKey = Arrays.stream(SalesOrderFinder.class.getMethods())
                .filter(method -> method.getName().equals("findByPrimaryKey"))
                .findFirst()
                .orElseThrow();

        assertThat(productKey.getParameterTypes())
                .containsExactly(long.class, Timestamp.class);
        assertThat(orderKey.getParameterTypes()).containsExactly(long.class, Timestamp.class);

        Product product = ProductFinder.findOne(
                ProductFinder.productId()
                        .eq(Ids.PRODUCT_PRICE_DEMO)
                        .and(ProductFinder.businessDate().eq(Timestamps.DAY_2025_06_15)));
        assertThat(product.getUnitPrice()).isEqualByComparingTo("10.00");

        assertThat(ProductFinder.getAsOfAttributes()).hasSize(1);
        assertThat(SalesOrderFinder.getAsOfAttributes()).hasSize(1);
        assertThat(ProductFinder.getAsOfAttributes()[0].isProcessingDate()).isFalse();
        assertThat(SalesOrderFinder.getAsOfAttributes()[0].isProcessingDate()).isTrue();
    }

    @Test
    void seedIsCompactDeterministicCatalog() {
        int rows = PhysicalStore.countAllDomainRows();
        assertThat(rows).isBetween(60, 90);
    }

    private static int qty(Timestamp asOf) {
        StockLevel stock = StockLevelFinder.findByPrimaryKey(Ids.STOCK_PRICE_DEMO, asOf);
        assertThat(stock).isNotNull();
        return stock.getQuantityOnHand();
    }
}
