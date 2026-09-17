package com.reladynamo.demo.petstore.seed;

import static com.reladynamo.demo.petstore.Ids.*;
import static com.reladynamo.demo.petstore.runtime.Timestamps.DAY_2025_01_01;
import static com.reladynamo.demo.petstore.runtime.Timestamps.PROC_SEED;

import com.reladynamo.demo.petstore.Money;
import com.reladynamo.demo.petstore.domain.Adoption;
import com.reladynamo.demo.petstore.domain.Breed;
import com.reladynamo.demo.petstore.domain.Employee;
import com.reladynamo.demo.petstore.domain.FeedingSchedule;
import com.reladynamo.demo.petstore.domain.GroomingAppointment;
import com.reladynamo.demo.petstore.domain.Kennel;
import com.reladynamo.demo.petstore.domain.Payment;
import com.reladynamo.demo.petstore.domain.Pet;
import com.reladynamo.demo.petstore.domain.PetOwner;
import com.reladynamo.demo.petstore.domain.Product;
import com.reladynamo.demo.petstore.domain.ProductCategory;
import com.reladynamo.demo.petstore.domain.PurchaseOrder;
import com.reladynamo.demo.petstore.domain.PurchaseOrderLine;
import com.reladynamo.demo.petstore.domain.SalesOrder;
import com.reladynamo.demo.petstore.domain.SalesOrderLine;
import com.reladynamo.demo.petstore.domain.Shipment;
import com.reladynamo.demo.petstore.domain.Species;
import com.reladynamo.demo.petstore.domain.SpeciesFinder;
import com.reladynamo.demo.petstore.domain.StockLevel;
import com.reladynamo.demo.petstore.domain.Store;
import com.reladynamo.demo.petstore.domain.Supplier;
import com.reladynamo.demo.petstore.domain.Vaccination;
import com.reladynamo.demo.petstore.domain.VeterinaryVisit;
import com.reladynamo.demo.petstore.runtime.Timestamps;
import com.reladynamo.demo.petstore.runtime.Tx;
import java.sql.Timestamp;

/**
 * Deterministic ~70-row catalog. Audit-only inserts share {@link Timestamps#PROC_SEED} so later
 * demonstration amendments can use later, still-deterministic processing times.
 */
public final class PetstoreSeed {

    private static volatile boolean populated;

    private PetstoreSeed() {
    }

    public static synchronized void populate() {
        if (populated) {
            return;
        }
        Timestamp open = DAY_2025_01_01;
        if (SpeciesFinder.findByPrimaryKey(SPECIES_DOG, open) != null) {
            populated = true;
            return;
        }
        Tx.runAt(PROC_SEED, tx -> {
            insertCatalog(open);
            return null;
        });
        populated = true;
    }

    private static void insertCatalog(Timestamp open) {
        species(SPECIES_DOG, "Dog", "Canis familiaris", "MEDIUM");
        species(SPECIES_CAT, "Cat", "Felis catus", "EASY");
        species(SPECIES_BIRD, "Bird", "Melopsittacus undulatus", "EASY");

        breed(BREED_LAB, SPECIES_DOG, "Labrador Retriever", "LARGE", 12);
        breed(BREED_CORGI, SPECIES_DOG, "Pembroke Welsh Corgi", "SMALL", 13);
        breed(BREED_TABBY, SPECIES_CAT, "Domestic Tabby", "MEDIUM", 15);
        breed(BREED_SIAMESE, SPECIES_CAT, "Siamese", "MEDIUM", 15);
        breed(BREED_PARAKEET, SPECIES_BIRD, "Budgerigar", "SMALL", 8);
        breed(BREED_COCKATIEL, SPECIES_BIRD, "Cockatiel", "SMALL", 16);

        store(STORE_PARK_CITY, "Park City Pets", "1640 Park Ave", "Park City", "84060", "435-555-0100");
        store(STORE_SALT_LAKE, "Salt Lake Pets", "50 W Broadway", "Salt Lake City", "84101", "801-555-0140");

        kennel(KENNEL_A, STORE_PARK_CITY, "Alpine A", 6, "NORTH");
        kennel(KENNEL_B, STORE_PARK_CITY, "Canyon B", 4, "SOUTH");

        category(CATEGORY_FOOD, "Food", null);
        category(CATEGORY_TOY, "Toys", null);
        category(CATEGORY_CARE, "Care", null);

        product(PRODUCT_KIBBLE, "KIB-001", "Highland Kibble 5kg", CATEGORY_FOOD, "18.50");
        product(PRODUCT_WET_FOOD, "WET-002", "Salmon Tins 12pk", CATEGORY_FOOD, "24.00");
        product(PRODUCT_TOY_MOUSE, "TOY-003", "Felt Mouse", CATEGORY_TOY, "4.50");
        product(PRODUCT_LITTER, "LIT-004", "Pine Litter 10kg", CATEGORY_CARE, "16.00");
        product(PRODUCT_BIRD_SEED, "SED-005", "Millet Mix 2kg", CATEGORY_FOOD, "9.25");
        product(PRODUCT_SHAMPOO, "SHM-006", "Oatmeal Shampoo", CATEGORY_CARE, "11.00");
        product(PRODUCT_PRICE_DEMO, "PRC-100", "Price-window demo kibble", CATEGORY_FOOD, "10.00");
        product(PRODUCT_CORRECTION_DEMO, "COR-101", "Correction demo collar", CATEGORY_CARE, "9.99");

        stock(STOCK_KIBBLE, STORE_PARK_CITY, PRODUCT_KIBBLE, 40, 10);
        stock(STOCK_WET, STORE_PARK_CITY, PRODUCT_WET_FOOD, 24, 8);
        stock(STOCK_TOY, STORE_PARK_CITY, PRODUCT_TOY_MOUSE, 50, 15);
        stock(STOCK_LITTER, STORE_SALT_LAKE, PRODUCT_LITTER, 18, 6);
        stock(STOCK_PRICE_DEMO, STORE_PARK_CITY, PRODUCT_KIBBLE, 40, 12);

        owner(OWNER_ADA, "Ada", "Lovelace", "ada@example.com", "435-555-1001", "1 Analytical Engine Rd", "Park City", "84060");
        owner(OWNER_ALAN, "Alan", "Turing", "alan@example.com", "435-555-1002", "2 Enigma Ln", "Park City", "84060");
        owner(OWNER_GRACE, "Grace", "Hopper", "grace@example.com", "801-555-1003", "3 Compiler Ct", "Salt Lake City", "84101");
        owner(OWNER_LINUS, "Linus", "Torvalds", "linus@example.com", "801-555-1004", "4 Kernel Way", "Salt Lake City", "84101");

        pet(PET_NICO, "Nico", SPECIES_DOG, BREED_LAB, "M", "GOLD", "AVAILABLE", KENNEL_A, "350.00");
        pet(PET_NOVA, "Nova", SPECIES_CAT, BREED_TABBY, "F", "GREY", "AVAILABLE", KENNEL_B, "175.00");
        pet(PET_SABLE, "Sable", SPECIES_DOG, BREED_CORGI, "F", "SABLE", "AVAILABLE", KENNEL_A, "400.00");
        pet(PET_KIRA, "Kira", SPECIES_CAT, BREED_SIAMESE, "F", "SEAL", "AVAILABLE", KENNEL_B, "220.00");
        pet(PET_PIP, "Pip", SPECIES_CAT, BREED_TABBY, "M", "ORANGE", "AVAILABLE", KENNEL_B, "150.00");
        pet(PET_MANGO, "Mango", SPECIES_BIRD, BREED_PARAKEET, "M", "GREEN", "AVAILABLE", null, "45.00");
        pet(PET_ADOPTED_SEED, "Bean", SPECIES_DOG, BREED_LAB, "M", "BLACK", "ADOPTED", null, "0.00");
        pet(PET_BIRD, "Cleo", SPECIES_BIRD, BREED_COCKATIEL, "F", "GREY", "AVAILABLE", null, "85.00");
        pet(PET_TERMINATE_DEMO, "Wicket", SPECIES_DOG, BREED_CORGI, "M", "RED", "AVAILABLE", KENNEL_A, "380.00");

        feed(1, PET_NICO, "08:00", PRODUCT_KIBBLE, 250);
        feed(2, PET_NOVA, "08:00", PRODUCT_WET_FOOD, 80);
        feed(3, PET_SABLE, "17:00", PRODUCT_KIBBLE, 180);
        feed(4, PET_MANGO, "08:00", PRODUCT_BIRD_SEED, 20);

        visit(1, PET_NICO, EMPLOYEE_VET, "Wellness", "Healthy");
        visit(2, PET_NOVA, EMPLOYEE_VET, "Ear check", "Mild mite");
        visit(3, PET_SABLE, EMPLOYEE_VET, "Limp", "Strain");

        vax(1, PET_NICO, "RABIES", "B-1001");
        vax(2, PET_NOVA, "FVRCP", "B-1002");
        vax(3, PET_SABLE, "DHPP", "B-1003");

        groom(1, PET_NICO, EMPLOYEE_GROOMER, "BATH", "DONE");
        groom(2, PET_KIRA, EMPLOYEE_GROOMER, "NAIL", "BOOKED");

        employee(EMPLOYEE_VET, STORE_PARK_CITY, "Marie", "Curie", "VET", "48.00");
        employee(EMPLOYEE_GROOMER, STORE_PARK_CITY, "James", "Watt", "GROOMER", "22.00");
        employee(EMPLOYEE_CLERK, STORE_SALT_LAKE, "Katherine", "Johnson", "CLERK", "18.50");
        employee(EMPLOYEE_TERMINATE_DEMO, STORE_PARK_CITY, "Michael", "Faraday", "CLERK", "17.00");

        supplier(SUPPLIER_ACME, "Acme Pet Supply", "orders@acme.example", 5);
        supplier(SUPPLIER_PAWS, "Paws Wholesale", "hello@paws.example", 8);

        SalesOrder walkin = new SalesOrder(Timestamps.infinity());
        walkin.setOrderId(ORDER_WALKIN);
        walkin.setCustomerId(OWNER_ADA);
        walkin.setStoreId(STORE_PARK_CITY);
        walkin.setOrderTime(Timestamps.utcDateTime(2025, 2, 1, 11, 0));
        walkin.setStatus("PAID");
        walkin.setTotalAmount(Money.of("23.00"));
        walkin.insert();

        line(1, ORDER_WALKIN, PRODUCT_TOY_MOUSE, null, 2, "4.50");
        line(2, ORDER_WALKIN, PRODUCT_SHAMPOO, null, 1, "11.00");

        Payment walkinPay = new Payment(Timestamps.infinity());
        walkinPay.setPaymentId(1L);
        walkinPay.setOrderId(ORDER_WALKIN);
        walkinPay.setAmount(Money.of("23.00"));
        walkinPay.setPaidTime(Timestamps.utcDateTime(2025, 2, 1, 11, 5));
        walkinPay.setMethod("CARD");
        walkinPay.insert();

        SalesOrder audit = new SalesOrder(Timestamps.infinity());
        audit.setOrderId(ORDER_AUDIT_DEMO);
        audit.setCustomerId(OWNER_ALAN);
        audit.setStoreId(STORE_PARK_CITY);
        audit.setOrderTime(Timestamps.utcDateTime(2025, 3, 1, 15, 0));
        audit.setStatus("OPEN");
        audit.setTotalAmount(Money.of("49.95"));
        audit.insert();

        line(100, ORDER_AUDIT_DEMO, PRODUCT_KIBBLE, null, 2, "18.50");
        line(101, ORDER_AUDIT_DEMO, null, PET_PIP, 1, "12.95");

        Payment auditPay = new Payment(Timestamps.infinity());
        auditPay.setPaymentId(100L);
        auditPay.setOrderId(ORDER_AUDIT_DEMO);
        auditPay.setAmount(Money.of("49.95"));
        auditPay.setPaidTime(Timestamps.utcDateTime(2025, 3, 1, 15, 10));
        auditPay.setMethod("CASH");
        auditPay.insert();

        PurchaseOrder po = new PurchaseOrder(Timestamps.infinity());
        po.setPoId(PO_ACME);
        po.setSupplierId(SUPPLIER_ACME);
        po.setOrderedTime(Timestamps.utcDateTime(2025, 1, 20, 9, 0));
        po.setExpectedDate(Timestamps.utcDate(2025, 1, 27));
        po.setStatus("RECEIVED");
        po.insert();

        poLine(1, PO_ACME, PRODUCT_KIBBLE, 40, "11.00");
        poLine(2, PO_ACME, PRODUCT_WET_FOOD, 24, "14.00");

        Shipment shipment = new Shipment(Timestamps.infinity());
        shipment.setShipmentId(1L);
        shipment.setPoId(PO_ACME);
        shipment.setShippedTime(Timestamps.utcDateTime(2025, 1, 22, 8, 0));
        shipment.setReceivedTime(Timestamps.utcDateTime(2025, 1, 24, 14, 0));
        shipment.setCarrier("UPS");
        shipment.setTrackingRef("1Z999");
        shipment.insert();

        Adoption adopted = new Adoption(Timestamps.infinity());
        adopted.setAdoptionId(ADOPTION_SEED);
        adopted.setPetId(PET_ADOPTED_SEED);
        adopted.setOwnerId(OWNER_GRACE);
        adopted.setAdoptedTime(Timestamps.utcDateTime(2024, 12, 20, 13, 0));
        adopted.setFeeAmount(Money.of("150.00"));
        adopted.insert();
    }

    private static void species(long id, String common, String scientific, String care) {
        Species species = new Species(DAY_2025_01_01);
        species.setSpeciesId(id);
        species.setCommonName(common);
        species.setScientificName(scientific);
        species.setCareLevel(care);
        species.insert();
    }

    private static void breed(long id, long speciesId, String name, String size, int years) {
        Breed breed = new Breed(DAY_2025_01_01);
        breed.setBreedId(id);
        breed.setSpeciesId(speciesId);
        breed.setName(name);
        breed.setSizeCategory(size);
        breed.setTypicalLifespanYears(years);
        breed.insert();
    }

    private static void store(long id, String name, String line1, String city, String postal, String phone) {
        Store store = new Store(DAY_2025_01_01);
        store.setStoreId(id);
        store.setName(name);
        store.setAddressLine1(line1);
        store.setCity(city);
        store.setPostalCode(postal);
        store.setPhone(phone);
        store.setOpenedDate(Timestamps.utcDate(2020, 6, 1));
        store.insert();
    }

    private static void kennel(long id, long storeId, String label, int capacity, String zone) {
        Kennel kennel = new Kennel(DAY_2025_01_01);
        kennel.setKennelId(id);
        kennel.setStoreId(storeId);
        kennel.setLabel(label);
        kennel.setCapacity(capacity);
        kennel.setZone(zone);
        kennel.insert();
    }

    private static void category(long id, String name, Long parentId) {
        ProductCategory category = new ProductCategory();
        category.setCategoryId(id);
        category.setName(name);
        if (parentId == null) {
            category.setParentCategoryIdNull();
        } else {
            category.setParentCategoryId(parentId);
        }
        category.insert();
    }

    private static void product(long id, String sku, String name, long categoryId, String price) {
        Product product = new Product(DAY_2025_01_01);
        product.setProductId(id);
        product.setSku(sku);
        product.setName(name);
        product.setCategoryId(categoryId);
        product.setUnitPrice(Money.of(price));
        product.setIsActive(true);
        product.insert();
    }

    private static void stock(long id, long storeId, long productId, int qty, int reorder) {
        StockLevel stock = new StockLevel(DAY_2025_01_01);
        stock.setStockId(id);
        stock.setStoreId(storeId);
        stock.setProductId(productId);
        stock.setQuantityOnHand(qty);
        stock.setReorderPoint(reorder);
        stock.insert();
    }

    private static void owner(
            long id,
            String first,
            String last,
            String email,
            String phone,
            String line1,
            String city,
            String postal) {
        PetOwner owner = new PetOwner(DAY_2025_01_01);
        owner.setOwnerId(id);
        owner.setFirstName(first);
        owner.setLastName(last);
        owner.setEmail(email);
        owner.setPhone(phone);
        owner.setAddressLine1(line1);
        owner.setCity(city);
        owner.setPostalCode(postal);
        owner.insert();
    }

    private static void pet(
            long id,
            String name,
            long speciesId,
            long breedId,
            String sex,
            String color,
            String status,
            Long kennelId,
            String listPrice) {
        Pet pet = new Pet(DAY_2025_01_01);
        pet.setPetId(id);
        pet.setName(name);
        pet.setSpeciesId(speciesId);
        pet.setBreedId(breedId);
        pet.setDateOfBirth(Timestamps.utcDate(2023, 4, 1));
        pet.setSex(sex);
        pet.setColorCode(color);
        pet.setMicrochipId("CHIP-" + id);
        pet.setStatus(status);
        if (kennelId == null) {
            pet.setKennelIdNull();
        } else {
            pet.setKennelId(kennelId);
        }
        pet.setListPrice(Money.of(listPrice));
        pet.insert();
    }

    private static void feed(long id, long petId, String time, long productId, int grams) {
        FeedingSchedule schedule = new FeedingSchedule(DAY_2025_01_01);
        schedule.setScheduleId(id);
        schedule.setPetId(petId);
        schedule.setFeedTimeOfDay(time);
        schedule.setProductId(productId);
        schedule.setQuantityGrams(grams);
        schedule.insert();
    }

    private static void visit(long id, long petId, long vetId, String reason, String diagnosis) {
        VeterinaryVisit visit = new VeterinaryVisit(Timestamps.infinity());
        visit.setVisitId(id);
        visit.setPetId(petId);
        visit.setVetId(vetId);
        visit.setVisitTime(Timestamps.utcDateTime(2025, 2, 10, 9, 30));
        visit.setReason(reason);
        visit.setDiagnosis(diagnosis);
        visit.insert();
    }

    private static void vax(long id, long petId, String code, String batch) {
        Vaccination vaccination = new Vaccination(Timestamps.infinity());
        vaccination.setVaccinationId(id);
        vaccination.setPetId(petId);
        vaccination.setVaccineCode(code);
        vaccination.setAdministeredTime(Timestamps.utcDateTime(2025, 2, 10, 10, 0));
        vaccination.setBatchNumber(batch);
        vaccination.setNextDueDate(Timestamps.utcDate(2026, 2, 10));
        vaccination.insert();
    }

    private static void groom(long id, long petId, long groomerId, String service, String status) {
        GroomingAppointment appointment = new GroomingAppointment(Timestamps.infinity());
        appointment.setAppointmentId(id);
        appointment.setPetId(petId);
        appointment.setGroomerId(groomerId);
        appointment.setScheduledTime(Timestamps.utcDateTime(2025, 3, 5, 14, 0));
        appointment.setServiceCode(service);
        appointment.setStatus(status);
        appointment.insert();
    }

    private static void employee(long id, long storeId, String first, String last, String role, String rate) {
        Employee employee = new Employee(DAY_2025_01_01);
        employee.setEmployeeId(id);
        employee.setStoreId(storeId);
        employee.setFirstName(first);
        employee.setLastName(last);
        employee.setRole(role);
        employee.setHireDate(Timestamps.utcDate(2024, 3, 1));
        employee.setHourlyRate(Money.of(rate));
        employee.insert();
    }

    private static void supplier(long id, String name, String email, int leadDays) {
        Supplier supplier = new Supplier(DAY_2025_01_01);
        supplier.setSupplierId(id);
        supplier.setName(name);
        supplier.setContactEmail(email);
        supplier.setLeadTimeDays(leadDays);
        supplier.insert();
    }

    private static void line(long id, long orderId, Long productId, Long petId, int qty, String price) {
        SalesOrderLine line = new SalesOrderLine(Timestamps.infinity());
        line.setLineId(id);
        line.setOrderId(orderId);
        if (productId == null) {
            line.setProductIdNull();
        } else {
            line.setProductId(productId);
        }
        if (petId == null) {
            line.setPetIdNull();
        } else {
            line.setPetId(petId);
        }
        line.setQuantity(qty);
        line.setUnitPrice(Money.of(price));
        line.insert();
    }

    private static void poLine(long id, long poId, long productId, int qty, String cost) {
        PurchaseOrderLine line = new PurchaseOrderLine(Timestamps.infinity());
        line.setPoLineId(id);
        line.setPoId(poId);
        line.setProductId(productId);
        line.setQuantity(qty);
        line.setUnitCost(Money.of(cost));
        line.insert();
    }

}
