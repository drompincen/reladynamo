package com.reladynamo.demo.petstore;

import com.gs.fw.common.mithra.MithraDatedTransactionalObject;
import com.gs.fw.common.mithra.MithraList;
import com.gs.fw.common.mithra.finder.RelatedFinder;
import com.reladynamo.demo.petstore.domain.ProductFinder;
import com.reladynamo.demo.petstore.domain.ProductList;
import com.reladynamo.demo.petstore.domain.SalesOrderFinder;
import com.reladynamo.demo.petstore.domain.SalesOrderList;
import com.reladynamo.demo.petstore.runtime.PetstoreHarness;
import io.reladynamo.core.bridge.MithraDataAccessor;
import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.config.TemporalMapping;
import io.reladynamo.core.key.DefaultKeyStrategy;
import io.reladynamo.core.mapping.MithraObjectXmlParser;
import io.reladynamo.ddb.codec.ItemCodec;
import io.reladynamo.ddb.exec.TableCreator;
import io.reladynamo.ddb.persist.DynamoDbWriter;
import io.reladynamo.testkit.LocalDynamoDb;
import io.reladynamo.testkit.diff.RowSetDiff;
import io.reladynamo.testkit.diff.TemporalRowSetDiffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unitemporal teaching point, on the demo's own XML: every entity here has exactly one
 * {@code AsOfAttribute}. {@link Product} is business-date-only (non-audited);
 * {@link com.reladynamo.demo.petstore.domain.SalesOrder} is processing-date-only (audit-only).
 *
 * <p>The mapping is parsed from the same files the generated classes came from. H2 is seeded
 * exactly as the existing demonstrations, those rows are mirrored through {@link DynamoDbWriter},
 * and {@link TemporalRowSetDiffer} compares the stores. A real divergence is left failing.
 *
 * <p>Finding 5 in {@code docs/CONFORMANCE-FINDINGS.md}: correcting the <em>same</em> open
 * business segment destroys the prior value (nowhere to keep the previous belief); dating a
 * change <em>later</em> splits the timeline. DynamoDB must reproduce the destruction — not
 * helpfully preserve an extra row. The sort key for business-only is
 * {@code v1#B#<businessDateFrom>}, so a same-segment put overwrites.
 *
 * <p>This does not rebind Reladomo finders onto DynamoDB. Finder materialisation is a separate
 * seam, already gated in the adapter module.
 */
class PetstoreDynamoDifferentialTest
{
    private static final BigDecimal ORIGINAL_CORRECTION_PRICE = new BigDecimal("9.99");
    private static final BigDecimal CORRECTED_PRICE = new BigDecimal("14.99");
    private static final BigDecimal JUNE_PRICE = new BigDecimal("10.00");
    private static final BigDecimal AUGUST_PRICE = new BigDecimal("12.50");

    private static LocalDynamoDb ddb;
    private static EntityMapping productMapping;
    private static EntityMapping orderMapping;
    private static Store products;
    private static Store orders;

    private List<Map<String, Object>> h2Products;
    private List<Map<String, Object>> h2Orders;
    private List<Map<String, Object>> ddbProducts;
    private List<Map<String, Object>> ddbOrders;

    @BeforeAll
    static void startStores()
    {
        PetstoreHarness.start();
        ddb = LocalDynamoDb.start();

        // The mapping comes from the SAME XML the generated objects came from — that is what
        // makes this a test of the generic adapter rather than of a hand-written mapping.
        MithraObjectXmlParser parser = new MithraObjectXmlParser();
        productMapping = parser.parse(loadDemoXml("Product.xml"));
        orderMapping = parser.parse(loadDemoXml("SalesOrder.xml"));

        TableCreator creator = new TableCreator(ddb.client());
        creator.create(productMapping);
        creator.create(orderMapping);

        products = new Store(ddb, productMapping);
        orders = new Store(ddb, orderMapping);
    }

    @AfterAll
    static void stopStores()
    {
        if (ddb != null)
        {
            ddb.close();
        }
    }

    @BeforeEach
    void mirrorH2IntoDynamo()
    {
        h2Products = extractDated(ProductFinder.getFinderInstance(), allProductVersions());
        h2Orders = extractDated(SalesOrderFinder.getFinderInstance(), allOrderVersions());

        products.purgeAll(products.scanAll());
        orders.purgeAll(orders.scanAll());

        products.push(h2Products);
        orders.push(h2Orders);

        ddbProducts = products.scanAll();
        ddbOrders = orders.scanAll();
    }

    @Test
    void demo_xml_parses_as_one_axis_each_flavour()
    {
        assertThat(productMapping.temporal().flavour())
                .as("Product.xml must stay business-date-only; if the parser cannot consume it "
                        + "unchanged that is a conformance finding, not a reason to edit the XML")
                .isEqualTo(TemporalMapping.Flavour.BUSINESS_ONLY);
        assertThat(productMapping.temporal().hasBusinessDate()).isTrue();
        assertThat(productMapping.temporal().hasProcessingDate()).isFalse();

        assertThat(orderMapping.temporal().flavour())
                .as("SalesOrder.xml must stay processing-date-only; if the parser cannot consume it "
                        + "unchanged that is a conformance finding, not a reason to edit the XML")
                .isEqualTo(TemporalMapping.Flavour.AUDIT_ONLY);
        assertThat(orderMapping.temporal().hasProcessingDate()).isTrue();
        assertThat(orderMapping.temporal().hasBusinessDate()).isFalse();

        assertThat(ProductFinder.getAsOfAttributes()).hasSize(1);
        assertThat(ProductFinder.getAsOfAttributes()[0].isProcessingDate()).isFalse();
        assertThat(SalesOrderFinder.getAsOfAttributes()).hasSize(1);
        assertThat(SalesOrderFinder.getAsOfAttributes()[0].isProcessingDate()).isTrue();
    }

    @Test
    void product_and_sales_order_row_sets_round_trip_identically()
    {
        assertIdentical("PRODUCT", h2Products, ddbProducts);
        assertIdentical("SALES_ORDER", h2Orders, ddbOrders);
    }

    @Test
    void same_segment_correction_destroys_the_prior_value_in_both_stores()
    {
        List<Map<String, Object>> h2 = rowsFor(h2Products, "productId", Ids.PRODUCT_CORRECTION_DEMO);
        List<Map<String, Object>> ddbRows = rowsFor(ddbProducts, "productId", Ids.PRODUCT_CORRECTION_DEMO);

        assertThat(h2)
                .as("H2 reference after in-place correction must be a single physical row:%n%s",
                        describeRows(h2))
                .hasSize(1);
        assertThat(pricesOf(h2))
                .as("H2 must keep only the corrected price; 9.99 is gone from the finder AND the store")
                .containsExactly(CORRECTED_PRICE);
        assertThat(pricesOf(h2)).noneMatch(price -> price.compareTo(ORIGINAL_CORRECTION_PRICE) == 0);

        assertIdentical("PRODUCT correction " + Ids.PRODUCT_CORRECTION_DEMO, h2, ddbRows);

        assertThat(ddbRows)
                .as("DynamoDB must reproduce the destruction, not preserve an extra 9.99 row:%n%s",
                        describeRows(ddbRows))
                .hasSize(1);
        assertThat(pricesOf(ddbRows)).containsExactly(CORRECTED_PRICE);
        assertThat(pricesOf(ddbRows)).noneMatch(price -> price.compareTo(ORIGINAL_CORRECTION_PRICE) == 0);
    }

    @Test
    void later_business_date_change_splits_the_timeline_in_both_stores()
    {
        List<Map<String, Object>> h2 = rowsFor(h2Products, "productId", Ids.PRODUCT_PRICE_DEMO);
        List<Map<String, Object>> ddbRows = rowsFor(ddbProducts, "productId", Ids.PRODUCT_PRICE_DEMO);

        assertThat(h2)
                .as("dating a change later must split, not destroy:%n%s", describeRows(h2))
                .hasSize(2);
        assertThat(pricesOf(h2))
                .as("June 10.00 and August 12.50 must both survive as business-time segments")
                .containsExactlyInAnyOrder(JUNE_PRICE, AUGUST_PRICE);

        assertIdentical("PRODUCT price-window " + Ids.PRODUCT_PRICE_DEMO, h2, ddbRows);

        assertThat(ddbRows).hasSize(2);
        assertThat(pricesOf(ddbRows)).containsExactlyInAnyOrder(JUNE_PRICE, AUGUST_PRICE);
    }

    @Test
    void audit_only_amendment_retains_every_processing_version_in_both_stores()
    {
        List<Map<String, Object>> h2 = rowsFor(h2Orders, "orderId", Ids.ORDER_AUDIT_DEMO);
        List<Map<String, Object>> ddbRows = rowsFor(ddbOrders, "orderId", Ids.ORDER_AUDIT_DEMO);

        assertThat(h2)
                .as("audit-only updates must keep every processing version:%n%s", describeRows(h2))
                .hasSize(3);
        assertThat(statusesOf(h2)).containsExactlyInAnyOrder("OPEN", "PAID", "FULFILLED");

        assertIdentical("SALES_ORDER audit " + Ids.ORDER_AUDIT_DEMO, h2, ddbRows);

        assertThat(ddbRows).hasSize(3);
        assertThat(statusesOf(ddbRows)).containsExactlyInAnyOrder("OPEN", "PAID", "FULFILLED");
    }

    /**
     * The DynamoDB mechanic behind finding 5: a business-only sort key is
     * {@code v1#B#<businessDateFrom>}. Two puts of the same segment share that key, so the
     * second put overwrites. If the adapter fabricated a processing axis here, both rows
     * would survive and this test would fail — that is the "helpfully preserve" bug.
     */
    @Test
    void same_business_from_put_overwrites_rather_than_preserving_an_extra_row()
    {
        Map<String, Object> corrected = onlyRow(
                rowsFor(h2Products, "productId", Ids.PRODUCT_CORRECTION_DEMO));
        Map<String, Object> prior = new LinkedHashMap<String, Object>(corrected);
        prior.put("unitPrice", ORIGINAL_CORRECTION_PRICE);

        products.purgeAll(rowsFor(products.scanAll(), "productId", Ids.PRODUCT_CORRECTION_DEMO));
        products.push(java.util.Collections.singletonList(prior));
        products.push(java.util.Collections.singletonList(corrected));

        List<Map<String, Object>> after = rowsFor(products.scanAll(), "productId", Ids.PRODUCT_CORRECTION_DEMO);
        assertThat(after)
                .as("same-segment put must destroy the prior DynamoDB item, not keep both:%n%s",
                        describeRows(after))
                .hasSize(1);
        assertThat(pricesOf(after)).containsExactly(CORRECTED_PRICE);
        assertThat(pricesOf(after)).noneMatch(price -> price.compareTo(ORIGINAL_CORRECTION_PRICE) == 0);
    }

    private static Map<String, Object> onlyRow(List<Map<String, Object>> rows)
    {
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private static List<Map<String, Object>> rowsFor(List<Map<String, Object>> rows, String idAttr, long id)
    {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < rows.size(); i++)
        {
            Map<String, Object> row = rows.get(i);
            Object value = row.get(idAttr);
            if (value instanceof Number && ((Number) value).longValue() == id)
            {
                out.add(row);
            }
        }
        return out;
    }

    private static List<BigDecimal> pricesOf(List<Map<String, Object>> rows)
    {
        List<BigDecimal> prices = new ArrayList<BigDecimal>();
        for (int i = 0; i < rows.size(); i++)
        {
            Object value = rows.get(i).get("unitPrice");
            if (!(value instanceof BigDecimal))
            {
                throw new IllegalStateException("expected BigDecimal unitPrice, got " + value);
            }
            prices.add((BigDecimal) value);
        }
        return prices;
    }

    private static List<String> statusesOf(List<Map<String, Object>> rows)
    {
        List<String> statuses = new ArrayList<String>();
        for (int i = 0; i < rows.size(); i++)
        {
            statuses.add((String) rows.get(i).get("status"));
        }
        return statuses;
    }

    private static void assertIdentical(String table,
                                        List<Map<String, Object>> reference,
                                        List<Map<String, Object>> adapter)
    {
        assertThat(reference)
                .as("%s: the H2 reference must produce rows, otherwise the comparison is vacuous", table)
                .isNotEmpty();
        RowSetDiff diff = TemporalRowSetDiffer.compare(reference, adapter);
        assertThat(diff.isIdentical())
                .as("H2 and DynamoDB disagree for %s:%n%s%nH2:%n%s%nDDB:%n%s",
                        table, diff.describe(), describeRows(reference), describeRows(adapter))
                .isTrue();
    }

    private static String describeRows(List<Map<String, Object>> rows)
    {
        StringBuilder sb = new StringBuilder();
        sb.append(rows.size()).append(" row(s)\n");
        for (int i = 0; i < rows.size(); i++)
        {
            sb.append("  [").append(i).append("] ").append(rows.get(i)).append('\n');
        }
        return sb.toString();
    }

    private static ProductList allProductVersions()
    {
        ProductList list = ProductFinder.findMany(ProductFinder.businessDate().equalsEdgePoint());
        list.setBypassCache(true);
        return list;
    }

    private static SalesOrderList allOrderVersions()
    {
        SalesOrderList list = SalesOrderFinder.findMany(
                SalesOrderFinder.processingDate().equalsEdgePoint());
        list.setBypassCache(true);
        return list;
    }

    private static List<Map<String, Object>> extractDated(RelatedFinder finder, MithraList list)
    {
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < list.size(); i++)
        {
            MithraDatedTransactionalObject item = (MithraDatedTransactionalObject) list.get(i);
            rows.add(MithraDataAccessor.extract(finder, item.zGetCurrentData()));
        }
        return rows;
    }

    static String loadDemoXml(String fileName)
    {
        String path = "/reladomo/" + fileName;
        InputStream in = PetstoreDynamoDifferentialTest.class.getResourceAsStream(path);
        if (in == null)
        {
            throw new IllegalStateException("missing classpath resource " + path
                    + " — parse the demo's own XML, do not substitute a hand-written mapping");
        }
        StringBuilder xml = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                xml.append(line).append('\n');
            }
        }
        catch (Exception e)
        {
            throw new IllegalStateException("could not read " + path, e);
        }
        return xml.toString();
    }

    /** One mapped DynamoDB table plus the codec/writer used to push H2 snapshots through the adapter. */
    static final class Store
    {
        private final LocalDynamoDb ddb;
        private final EntityMapping mapping;
        private final ItemCodec codec;
        private final DynamoDbWriter writer;

        Store(LocalDynamoDb ddb, EntityMapping mapping)
        {
            this.ddb = ddb;
            this.mapping = mapping;
            this.codec = new ItemCodec(mapping);
            this.writer = new DynamoDbWriter(ddb.client(), mapping, codec, new DefaultKeyStrategy());
        }

        void push(List<Map<String, Object>> rows)
        {
            for (int i = 0; i < rows.size(); i++)
            {
                // Snapshot replay, not an ORM insert: the same H2 version may already occupy
                // this pk+sk from an earlier assertion or a bound-portal write.
                writer.upsert(rows.get(i));
            }
        }

        void purgeAll(List<Map<String, Object>> rows)
        {
            for (int i = 0; i < rows.size(); i++)
            {
                writer.purge(rows.get(i));
            }
        }

        List<Map<String, Object>> scanAll()
        {
            List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
            Map<String, AttributeValue> start = null;
            do
            {
                final Map<String, AttributeValue> exclusiveStart = start;
                ScanResponse response = ddb.client().scan(b ->
                {
                    b.tableName(mapping.tableName()).consistentRead(true);
                    if (exclusiveStart != null && !exclusiveStart.isEmpty())
                    {
                        b.exclusiveStartKey(exclusiveStart);
                    }
                });
                List<Map<String, AttributeValue>> items = response.items();
                for (int i = 0; i < items.size(); i++)
                {
                    rows.add(codec.decode(items.get(i)));
                }
                start = response.lastEvaluatedKey();
            }
            while (start != null && !start.isEmpty());
            return rows;
        }
    }
}
