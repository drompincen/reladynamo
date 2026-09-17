package com.reladynamo.demo.petstore.demo;

import com.reladynamo.demo.petstore.Ids;
import com.reladynamo.demo.petstore.Money;
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
import com.reladynamo.demo.petstore.runtime.Timestamps;
import com.reladynamo.demo.petstore.store.PhysicalStore;
import java.io.PrintStream;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Prints the six demonstrations as readable tables. */
public final class DemoPrinter {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private DemoPrinter() {
    }

    public static void printAll(PrintStream out) {
        out.println("=== Pet store unitemporal demo (Reladomo 18.1.0 / H2 / JDK 21) ===");
        out.println();
        out.println("Seeded + demonstration rows in H2: " + PhysicalStore.countAllDomainRows());
        out.println("Infinity sentinel: " + fmt(Timestamps.infinity()));
        out.println();
        printPriceWindow(out);
        printStockHistory(out);
        printNonAuditedCorrection(out);
        printAuditTrail(out);
        printTerminations(out);
        printUnitemporalApi(out);
    }

    private static void printPriceWindow(PrintStream out) {
        out.println("--- 1. Price change with a business-date window ---");
        Product june = ProductFinder.findByPrimaryKey(Ids.PRODUCT_PRICE_DEMO, Timestamps.DAY_2025_06_15);
        Product august = ProductFinder.findByPrimaryKey(Ids.PRODUCT_PRICE_DEMO, Timestamps.DAY_2025_08_15);
        out.println("ProductFinder.findByPrimaryKey(id, businessDate)  // ONE date, not two");
        out.printf("  as of 2025-06-15  unitPrice = %s%n", june.getUnitPrice());
        out.printf("  as of 2025-08-15  unitPrice = %s%n", august.getUnitPrice());
        out.println();
        out.println("Physical PRODUCT rows (FROM_Z / THRU_Z only -- no IN_Z / OUT_Z):");
        out.println(productTable(PhysicalStore.productRows(Ids.PRODUCT_PRICE_DEMO)));
        out.println("June sees the pre-change segment; August sees the post-change segment.");
        out.println();
    }

    private static void printStockHistory(PrintStream out) {
        out.println("--- 2. Stock level over time ---");
        int mar = qty(Timestamps.utcDate(2025, 3, 15));
        int jun = qty(Timestamps.DAY_2025_06_15);
        int oct = qty(Timestamps.DAY_2025_10_01);
        out.printf("  as of 2025-03-15  quantityOnHand = %d%n", mar);
        out.printf("  as of 2025-06-15  quantityOnHand = %d%n", jun);
        out.printf("  as of 2025-10-01  quantityOnHand = %d%n", oct);
        out.println();
        out.println("Physical STOCK_LEVEL rows:");
        out.println(stockTable(PhysicalStore.stockRows(Ids.STOCK_PRICE_DEMO)));
        out.println();
    }

    private static void printNonAuditedCorrection(PrintStream out) {
        out.println("--- 3. Non-audited correction DESTROYS the prior value ---");
        out.println("Seeded unitPrice = 9.99 as of 2025-01-01 (a wrong price).");
        out.println("Corrected as of the same business date to 14.99.");
        out.println("GenericNonAuditedTemporalDirector overwrites the segment. There is no");
        out.println("processingDate axis, so Reladomo cannot retain a prior-belief row.");
        out.println();
        Product now = ProductFinder.findByPrimaryKey(Ids.PRODUCT_CORRECTION_DEMO, Timestamps.DAY_2025_06_15);
        out.printf("  finder as of 2025-06-15  unitPrice = %s%n", now.getUnitPrice());
        ProductList allSlices = ProductFinder.findMany(
                ProductFinder.productId()
                        .eq(Ids.PRODUCT_CORRECTION_DEMO)
                        .and(ProductFinder.businessDate().equalsEdgePoint()));
        allSlices.setOrderBy(ProductFinder.businessDateFrom().ascendingOrderBy());
        out.printf("  equalsEdgePoint() slice count = %d (every business-date version)%n", allSlices.size());
        for (Product slice : allSlices) {
            out.printf(
                    "    [%s, %s)  unitPrice = %s%n",
                    fmt(slice.getBusinessDateFrom()), fmt(slice.getBusinessDateTo()), slice.getUnitPrice());
        }
        out.println();
        out.println("Physical PRODUCT rows for the corrected sku:");
        out.println(productTable(PhysicalStore.productRows(Ids.PRODUCT_CORRECTION_DEMO)));
        boolean oldPresent = PhysicalStore.productRows(Ids.PRODUCT_CORRECTION_DEMO).stream()
                .anyMatch(row -> row.unitPrice().compareTo(Money.of("9.99")) == 0);
        out.println("Old value 9.99 present in H2? " + oldPresent);
        out.println();
        out.println("THIS IS THE TEACHING POINT.");
        out.println("Engineers routinely assume Reladomo always keeps history. It does not.");
        out.println("The same kind of correction in the bitemporal CRM demo preserves the");
        out.println("prior belief as a closed processing-time rectangle. Here the old price");
        out.println("is genuinely unrecoverable -- not hidden, gone.");
        out.println();
    }

    private static void printAuditTrail(PrintStream out) {
        out.println("--- 4. Audit-only trail (SalesOrder, processingDate only) ---");
        SalesOrder current =
                SalesOrderFinder.findByPrimaryKey(Ids.ORDER_AUDIT_DEMO, Timestamps.infinity());
        SalesOrder asPaid =
                SalesOrderFinder.findByPrimaryKey(Ids.ORDER_AUDIT_DEMO, Timestamps.PROC_PAID);
        SalesOrder asOpen = SalesOrderFinder.findByPrimaryKey(
                Ids.ORDER_AUDIT_DEMO, new Timestamp(Timestamps.PROC_SEED.getTime() + 1_000L));
        out.printf("  as of processingDate=infinity     status = %s  total = %s%n",
                current.getStatus(), current.getTotalAmount());
        out.printf("  as of processingDate=2025-06-01   status = %s  total = %s%n",
                asPaid.getStatus(), asPaid.getTotalAmount());
        out.printf("  as of processingDate=2025-01-15   status = %s  total = %s%n",
                asOpen.getStatus(), asOpen.getTotalAmount());
        out.println();
        SalesOrderList versions = SalesOrderFinder.findMany(
                SalesOrderFinder.orderId()
                        .eq(Ids.ORDER_AUDIT_DEMO)
                        .and(SalesOrderFinder.processingDate().equalsEdgePoint()));
        versions.setOrderBy(SalesOrderFinder.processingDateFrom().ascendingOrderBy());
        out.printf("  equalsEdgePoint() retained %d processing-time versions:%n", versions.size());
        for (SalesOrder version : versions) {
            out.printf(
                    "    [%s, %s)  status=%-10s total=%s%n",
                    fmt(version.getProcessingDateFrom()),
                    fmt(version.getProcessingDateTo()),
                    version.getStatus(),
                    version.getTotalAmount());
        }
        out.println();
        out.println("Physical SALES_ORDER rows (IN_Z / OUT_Z -- no business-date columns):");
        out.println(orderTable(PhysicalStore.salesOrderRows(Ids.ORDER_AUDIT_DEMO)));
        out.println();
    }

    private static void printTerminations(PrintStream out) {
        out.println("--- 5. Terminate a Pet listing and an Employee ---");
        Pet before = PetFinder.findByPrimaryKey(Ids.PET_TERMINATE_DEMO, Timestamps.DAY_2025_08_14);
        Pet onDay = PetFinder.findByPrimaryKey(Ids.PET_TERMINATE_DEMO, Timestamps.DAY_2025_08_15);
        Pet after = PetFinder.findByPrimaryKey(Ids.PET_TERMINATE_DEMO, Timestamps.DAY_2025_09_01);
        out.printf("  Pet Wicket as of 2025-08-14 (day before adoption) = %s%n", nameStatus(before));
        out.printf("  Pet Wicket as of 2025-08-15 (adoption / terminate date) = %s%n", nameStatus(onDay));
        out.printf("  Pet Wicket as of 2025-09-01 = %s%n", nameStatus(after));
        out.println("Physical PET rows:");
        out.println(petTable(PhysicalStore.petRows(Ids.PET_TERMINATE_DEMO)));

        Employee employed =
                EmployeeFinder.findByPrimaryKey(Ids.EMPLOYEE_TERMINATE_DEMO, Timestamps.DAY_2025_09_30);
        Employee departed =
                EmployeeFinder.findByPrimaryKey(Ids.EMPLOYEE_TERMINATE_DEMO, Timestamps.DAY_2025_10_01);
        out.printf("  Employee Faraday as of 2025-09-30 = %s%n", empStatus(employed));
        out.printf("  Employee Faraday as of 2025-10-01 (departure) = %s%n", empStatus(departed));
        out.println("Physical EMPLOYEE rows:");
        out.println(employeeTable(PhysicalStore.employeeRows(Ids.EMPLOYEE_TERMINATE_DEMO)));
        out.println();
    }

    private static void printUnitemporalApi(PrintStream out) {
        out.println("--- 6. Unitemporal as-of query takes ONE date, not two ---");
        out.println(
                """
                Generated finder signatures (this project):
                  ProductFinder.findByPrimaryKey(long productId, Timestamp businessDate)
                  SalesOrderFinder.findByPrimaryKey(long orderId, Timestamp processingDate)

                Bitemporal CRM demo (contrast):
                  CustomerFinder.findByPrimaryKey(long customerId, Timestamp businessDate, Timestamp processingDate)

                Query construction is the same story:
                  ProductFinder.productId().eq(id).and(ProductFinder.businessDate().eq(oneDate))
                  // there is no ProductFinder.processingDate() -- the model has one AsOfAttribute.

                As-of is half-open: from <= asOf < thru. Infinity is 9999-12-01 23:59:00.000 UTC.
                """);
    }

    private static int qty(Timestamp asOf) {
        StockLevel stock = StockLevelFinder.findByPrimaryKey(Ids.STOCK_PRICE_DEMO, asOf);
        return stock.getQuantityOnHand();
    }

    private static String nameStatus(Pet pet) {
        return pet == null ? "<not listed>" : pet.getName() + " / " + pet.getStatus();
    }

    private static String empStatus(Employee employee) {
        return employee == null
                ? "<not employed>"
                : employee.getLastName() + " / " + employee.getRole();
    }

    private static String productTable(List<PhysicalStore.ProductRow> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("  %-23s %-23s %s%n", "FROM_Z", "THRU_Z", "UNIT_PRICE"));
        for (PhysicalStore.ProductRow row : rows) {
            sb.append(String.format("  %-23s %-23s %s%n", fmt(row.fromZ()), fmt(row.thruZ()), row.unitPrice()));
        }
        return sb.toString();
    }

    private static String stockTable(List<PhysicalStore.StockRow> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("  %-23s %-23s %s%n", "FROM_Z", "THRU_Z", "QTY"));
        for (PhysicalStore.StockRow row : rows) {
            sb.append(String.format("  %-23s %-23s %d%n", fmt(row.fromZ()), fmt(row.thruZ()), row.quantityOnHand()));
        }
        return sb.toString();
    }

    private static String orderTable(List<PhysicalStore.SalesOrderRow> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("  %-23s %-23s %-10s %s%n", "IN_Z", "OUT_Z", "STATUS", "TOTAL"));
        for (PhysicalStore.SalesOrderRow row : rows) {
            sb.append(String.format(
                    "  %-23s %-23s %-10s %s%n",
                    fmt(row.inZ()), fmt(row.outZ()), row.status(), row.totalAmount()));
        }
        return sb.toString();
    }

    private static String petTable(List<PhysicalStore.PetRow> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("  %-23s %-23s %-8s %s%n", "FROM_Z", "THRU_Z", "STATUS", "NAME"));
        for (PhysicalStore.PetRow row : rows) {
            sb.append(String.format(
                    "  %-23s %-23s %-8s %s%n", fmt(row.fromZ()), fmt(row.thruZ()), row.status(), row.name()));
        }
        return sb.toString();
    }

    private static String employeeTable(List<PhysicalStore.EmployeeRow> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("  %-23s %-23s %s%n", "FROM_Z", "THRU_Z", "ROLE"));
        for (PhysicalStore.EmployeeRow row : rows) {
            sb.append(String.format("  %-23s %-23s %s%n", fmt(row.fromZ()), fmt(row.thruZ()), row.role()));
        }
        return sb.toString();
    }

    private static String fmt(Timestamp timestamp) {
        if (timestamp == null) {
            return "<null>";
        }
        return TS.format(Instant.ofEpochMilli(timestamp.getTime()));
    }
}
