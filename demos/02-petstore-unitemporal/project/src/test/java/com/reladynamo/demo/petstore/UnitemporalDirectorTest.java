package com.reladynamo.demo.petstore;

import static org.assertj.core.api.Assertions.assertThat;

import com.gs.fw.common.mithra.attribute.AsOfAttribute;
import com.reladynamo.demo.petstore.domain.AdoptionFinder;
import com.reladynamo.demo.petstore.domain.BreedFinder;
import com.reladynamo.demo.petstore.domain.EmployeeFinder;
import com.reladynamo.demo.petstore.domain.FeedingScheduleFinder;
import com.reladynamo.demo.petstore.domain.GroomingAppointmentFinder;
import com.reladynamo.demo.petstore.domain.KennelFinder;
import com.reladynamo.demo.petstore.domain.PaymentFinder;
import com.reladynamo.demo.petstore.domain.PetFinder;
import com.reladynamo.demo.petstore.domain.PetOwnerFinder;
import com.reladynamo.demo.petstore.domain.ProductCategoryFinder;
import com.reladynamo.demo.petstore.domain.ProductFinder;
import com.reladynamo.demo.petstore.domain.PurchaseOrderFinder;
import com.reladynamo.demo.petstore.domain.PurchaseOrderLineFinder;
import com.reladynamo.demo.petstore.domain.SalesOrderFinder;
import com.reladynamo.demo.petstore.domain.SalesOrderLineFinder;
import com.reladynamo.demo.petstore.domain.ShipmentFinder;
import com.reladynamo.demo.petstore.domain.SpeciesFinder;
import com.reladynamo.demo.petstore.domain.StockLevelFinder;
import com.reladynamo.demo.petstore.domain.StoreFinder;
import com.reladynamo.demo.petstore.domain.SupplierFinder;
import com.reladynamo.demo.petstore.domain.VaccinationFinder;
import com.reladynamo.demo.petstore.domain.VeterinaryVisitFinder;
import com.reladynamo.demo.petstore.runtime.PetstoreHarness;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class UnitemporalDirectorTest {

    @BeforeAll
    static void start() {
        PetstoreHarness.start();
    }

    @Test
    void businessEntitiesHaveExactlyOneBusinessDateAsOfAttribute() {
        assertBusiness(PetFinder.getAsOfAttributes());
        assertBusiness(SpeciesFinder.getAsOfAttributes());
        assertBusiness(BreedFinder.getAsOfAttributes());
        assertBusiness(KennelFinder.getAsOfAttributes());
        assertBusiness(FeedingScheduleFinder.getAsOfAttributes());
        assertBusiness(ProductFinder.getAsOfAttributes());
        assertBusiness(StockLevelFinder.getAsOfAttributes());
        assertBusiness(PetOwnerFinder.getAsOfAttributes());
        assertBusiness(EmployeeFinder.getAsOfAttributes());
        assertBusiness(StoreFinder.getAsOfAttributes());
        assertBusiness(SupplierFinder.getAsOfAttributes());
    }

    @Test
    void auditEntitiesHaveExactlyOneProcessingDateAsOfAttribute() {
        assertAudit(VeterinaryVisitFinder.getAsOfAttributes());
        assertAudit(VaccinationFinder.getAsOfAttributes());
        assertAudit(GroomingAppointmentFinder.getAsOfAttributes());
        assertAudit(SalesOrderFinder.getAsOfAttributes());
        assertAudit(SalesOrderLineFinder.getAsOfAttributes());
        assertAudit(PaymentFinder.getAsOfAttributes());
        assertAudit(PurchaseOrderFinder.getAsOfAttributes());
        assertAudit(PurchaseOrderLineFinder.getAsOfAttributes());
        assertAudit(ShipmentFinder.getAsOfAttributes());
        assertAudit(AdoptionFinder.getAsOfAttributes());
    }

    @Test
    void productCategoryIsPlainWithNoAsOfAttribute() {
        assertThat(ProductCategoryFinder.getAsOfAttributes()).isNull();
    }

    private static void assertBusiness(AsOfAttribute<?>[] asOf) {
        assertThat(asOf).hasSize(1);
        assertThat(asOf[0].getAttributeName()).isEqualTo("businessDate");
        assertThat(asOf[0].isProcessingDate()).isFalse();
    }

    private static void assertAudit(AsOfAttribute<?>[] asOf) {
        assertThat(asOf).hasSize(1);
        assertThat(asOf[0].getAttributeName()).isEqualTo("processingDate");
        assertThat(asOf[0].isProcessingDate()).isTrue();
    }
}
