package com.reladynamo.demo.petstore.demo;

import com.reladynamo.demo.petstore.Ids;
import com.reladynamo.demo.petstore.Money;
import com.reladynamo.demo.petstore.domain.Adoption;
import com.reladynamo.demo.petstore.domain.Employee;
import com.reladynamo.demo.petstore.domain.EmployeeFinder;
import com.reladynamo.demo.petstore.domain.Pet;
import com.reladynamo.demo.petstore.domain.PetFinder;
import com.reladynamo.demo.petstore.domain.Product;
import com.reladynamo.demo.petstore.domain.ProductFinder;
import com.reladynamo.demo.petstore.domain.SalesOrder;
import com.reladynamo.demo.petstore.domain.SalesOrderFinder;
import com.reladynamo.demo.petstore.domain.StockLevel;
import com.reladynamo.demo.petstore.domain.StockLevelFinder;
import com.reladynamo.demo.petstore.runtime.Timestamps;
import com.reladynamo.demo.petstore.runtime.Tx;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The six required demonstrations. {@link #apply()} mutates dedicated seed rows once; tests and
 * {@code main()} then read the resulting unitemporal state.
 */
public final class Demonstrations {

    private static final AtomicBoolean APPLIED = new AtomicBoolean();

    private Demonstrations() {
    }

    public static void apply() {
        if (!APPLIED.compareAndSet(false, true)) {
            return;
        }
        applyPriceWindow();
        applyStockHistory();
        applyNonAuditedCorrection();
        applyAuditTrail();
        applyTerminations();
    }

    /** Demo 1: unitPrice change effective 2025-07-01 splits the business-date window. */
    private static void applyPriceWindow() {
        Tx.run(tx -> {
            Product product = ProductFinder.findByPrimaryKey(Ids.PRODUCT_PRICE_DEMO, Timestamps.DAY_2025_07_01);
            product.setUnitPrice(Money.of("12.50"));
            return null;
        });
    }

    /** Demo 2: stock quantity restated on successive business dates. */
    private static void applyStockHistory() {
        Tx.run(tx -> {
            StockLevel stock = StockLevelFinder.findByPrimaryKey(Ids.STOCK_PRICE_DEMO, Timestamps.DAY_2025_03_01);
            stock.setQuantityOnHand(55);
            return null;
        });
        Tx.run(tx -> {
            StockLevel stock = StockLevelFinder.findByPrimaryKey(Ids.STOCK_PRICE_DEMO, Timestamps.DAY_2025_05_01);
            stock.setQuantityOnHand(28);
            return null;
        });
        Tx.run(tx -> {
            StockLevel stock = StockLevelFinder.findByPrimaryKey(Ids.STOCK_PRICE_DEMO, Timestamps.DAY_2025_09_01);
            stock.setQuantityOnHand(70);
            return null;
        });
    }

    /**
     * Demo 3: correct a wrong price as of the original from-date. Non-audited directors overwrite
     * the segment; the prior value is destroyed.
     */
    private static void applyNonAuditedCorrection() {
        Tx.run(tx -> {
            Product product =
                    ProductFinder.findByPrimaryKey(Ids.PRODUCT_CORRECTION_DEMO, Timestamps.DAY_2025_01_01);
            product.setUnitPrice(Money.of("14.99"));
            return null;
        });
    }

    /** Demo 4: amend a SalesOrder; AuditOnlyTemporalDirector retains every processing version. */
    private static void applyAuditTrail() {
        Tx.runAt(Timestamps.PROC_PAID, tx -> {
            SalesOrder order =
                    SalesOrderFinder.findByPrimaryKey(Ids.ORDER_AUDIT_DEMO, Timestamps.infinity());
            order.setStatus("PAID");
            order.setTotalAmount(Money.of("54.95"));
            return null;
        });
        Tx.runAt(Timestamps.PROC_FULFILLED, tx -> {
            SalesOrder order =
                    SalesOrderFinder.findByPrimaryKey(Ids.ORDER_AUDIT_DEMO, Timestamps.infinity());
            order.setStatus("FULFILLED");
            return null;
        });
    }

    /** Demo 5: terminate a Pet listing on adoption and an Employee on departure. */
    private static void applyTerminations() {
        Tx.run(tx -> {
            Pet pet = PetFinder.findByPrimaryKey(Ids.PET_TERMINATE_DEMO, Timestamps.DAY_2025_08_15);
            pet.terminate();
            return null;
        });
        Tx.runAt(Timestamps.PROC_ADOPTION, tx -> {
            Adoption adoption = new Adoption(Timestamps.infinity());
            adoption.setAdoptionId(Ids.ADOPTION_TERMINATE_DEMO);
            adoption.setPetId(Ids.PET_TERMINATE_DEMO);
            adoption.setOwnerId(Ids.OWNER_ADA);
            adoption.setAdoptedTime(Timestamps.DAY_2025_08_15);
            adoption.setFeeAmount(Money.of("380.00"));
            adoption.insert();
            return null;
        });
        Tx.run(tx -> {
            Employee employee =
                    EmployeeFinder.findByPrimaryKey(Ids.EMPLOYEE_TERMINATE_DEMO, Timestamps.DAY_2025_10_01);
            employee.terminate();
            return null;
        });
    }
}
