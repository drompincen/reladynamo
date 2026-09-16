package com.reladynamo.demo.petstore;

import com.gs.fw.common.mithra.MithraManagerProvider;
import com.gs.fw.common.mithra.portal.MithraAbstractObjectPortal;
import com.gs.fw.common.mithra.portal.MithraObjectReader;
import com.reladynamo.demo.petstore.domain.Product;
import com.reladynamo.demo.petstore.domain.ProductFinder;
import com.reladynamo.demo.petstore.runtime.H2PetstoreConnectionManager;
import com.reladynamo.demo.petstore.runtime.PetstoreBootstrap;
import com.reladynamo.demo.petstore.runtime.Timestamps;
import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.key.DefaultKeyStrategy;
import io.reladynamo.core.mapping.MithraObjectXmlParser;
import io.reladynamo.core.plan.PhysicalDesign;
import io.reladynamo.core.plan.PlannerConfig;
import io.reladynamo.core.plan.QueryPlanner;
import io.reladynamo.ddb.codec.ItemCodec;
import io.reladynamo.ddb.exec.QueryPlanExecutor;
import io.reladynamo.ddb.exec.TableCreator;
import io.reladynamo.ddb.persist.DynamoDbPersister;
import io.reladynamo.ddb.persist.DynamoDbWriter;
import io.reladynamo.testkit.LocalDynamoDb;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Case 10 extra evidence: the unitemporal (non-audited) director on Product. A previous
 * agent judged this would hit the {@code refresh} wall; that wall has since been implemented.
 * This rebinds Product, disconnects H2, and runs insert / as-of find / same-segment correction.
 */
class PetstoreBoundPortalTest {

    private static LocalDynamoDb ddb;
    private static DynamoDbPersister adapter;
    private static MithraAbstractObjectPortal portal;
    private static MithraObjectReader jdbcReader;

    @BeforeAll
    static void start() {
        PetstoreBootstrap.start();
        ddb = LocalDynamoDb.start();
        EntityMapping mapping = new MithraObjectXmlParser().parse(loadXml("/reladomo/Product.xml"));
        PhysicalDesign design = PhysicalDesign.builder(mapping)
                .infinityFrom(ProductFinder.getFinderInstance())
                .build();
        new TableCreator(ddb.client()).create(design);
        ItemCodec codec = new ItemCodec(mapping);
        DynamoDbWriter writer = new DynamoDbWriter(
                ddb.client(), mapping, codec, new DefaultKeyStrategy(), design.gsis());
        adapter = new DynamoDbPersister(
                ProductFinder.getFinderInstance(), mapping, writer,
                new QueryPlanner(), new QueryPlanExecutor(ddb.client(), codec),
                design, PlannerConfig.builder().build());
        portal = (MithraAbstractObjectPortal) ProductFinder.getMithraObjectPortal();
        jdbcReader = portal.getDatabaseObject();
    }

    @AfterAll
    static void stop() {
        H2PetstoreConnectionManager.getInstance().reconnectRelationalSource();
        if (portal != null && jdbcReader != null) {
            portal.setMithraObjectReader(jdbcReader);
        }
        if (ddb != null) {
            ddb.close();
        }
    }

    @Test
    void unitemporal_product_round_trips_with_h2_disconnected() {
        portal.getCache().clear();
        portal.clearQueryCache();
        portal.setMithraObjectReader(adapter);
        H2PetstoreConnectionManager.getInstance().disconnectRelationalSource();
        try {
            Timestamp asOf = Timestamps.DAY_2025_01_01;
            MithraManagerProvider.getMithraManager().executeTransactionalCommand(tx -> {
                Product product = new Product(asOf);
                product.setProductId(99001L);
                product.setSku("SPI-UNI");
                product.setName("bound-unitemporal");
                product.setCategoryId(1L);
                product.setUnitPrice(Money.of("9.99"));
                product.setIsActive(true);
                product.insert();
                return null;
            });

            portal.getCache().clear();
            portal.clearQueryCache();

            Product found = ProductFinder.findByPrimaryKey(99001L, asOf);
            assertThat(found)
                    .as("unitemporal findByPrimaryKey must be served by the adapter with H2 down")
                    .isNotNull();
            assertThat(found.getSku()).isEqualTo("SPI-UNI");
            assertThat(found.getUnitPrice()).isEqualByComparingTo("9.99");

            MithraManagerProvider.getMithraManager().executeTransactionalCommand(tx -> {
                Product current = ProductFinder.findByPrimaryKey(99001L, asOf);
                current.setUnitPrice(Money.of("14.99"));
                return null;
            });

            portal.getCache().clear();
            portal.clearQueryCache();
            Product corrected = ProductFinder.findByPrimaryKey(99001L, asOf);
            assertThat(corrected.getUnitPrice())
                    .as("same-segment non-audited correction must destroy the prior value")
                    .isEqualByComparingTo("14.99");
        } catch (RuntimeException e) {
            String msg = String.valueOf(e.getMessage());
            Throwable c = e;
            while (c.getCause() != null && c.getCause() != c) {
                c = c.getCause();
            }
            String root = String.valueOf(c.getMessage());
            if (root.contains("not implemented yet") || msg.contains("not implemented yet")) {
                fail("petstore unitemporal rebind hit a named SPI refusal: " + root);
            }
            throw e;
        } finally {
            H2PetstoreConnectionManager.getInstance().reconnectRelationalSource();
            portal.setMithraObjectReader(jdbcReader);
        }
    }

    private static String loadXml(String path) {
        InputStream in = PetstoreBoundPortalTest.class.getResourceAsStream(path);
        if (in == null) {
            throw new IllegalStateException("missing classpath resource " + path);
        }
        StringBuilder xml = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                xml.append(line).append('\n');
            }
        } catch (Exception e) {
            throw new IllegalStateException("could not read " + path, e);
        }
        return xml.toString();
    }
}
