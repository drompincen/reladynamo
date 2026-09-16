package io.reladynamo.ddb.persist;

import io.reladynamo.core.config.AttributeMapping;
import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.config.TemporalMapping;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Calendar;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The persister's contract at the seam, independent of any store.
 *
 * <p>The behaviour worth pinning is the refusal: an unimplemented read must fail by name, not return
 * an empty result. An empty result is indistinguishable from a legitimately empty table, so it would
 * be diagnosed as a data problem rather than a missing feature — potentially long after someone
 * concluded the adapter "works".
 */
class DynamoDbPersisterTest {

    private static EntityMapping mapping() {
        return new EntityMapping("com.acme.PlanRule", "plan_rule",
                TemporalMapping.of(TemporalMapping.Flavour.BITEMPORAL, infinity()),
                Arrays.asList(
                        new AttributeMapping("ruleId", "ruleId", "int", true, false),
                        new AttributeMapping("businessDateFrom", "FROM_Z", "Timestamp", false, false),
                        new AttributeMapping("businessDateTo", "THRU_Z", "Timestamp", false, false),
                        new AttributeMapping("processingDateFrom", "IN_Z", "Timestamp", false, false),
                        new AttributeMapping("processingDateTo", "OUT_Z", "Timestamp", false, false)));
    }

    @Test
    void unimplemented_reads_fail_by_name_rather_than_returning_an_empty_result() {
        DynamoDbPersister p = new DynamoDbPersister(
                stubFinder(), mapping(), null_writer());

        assertThatThrownBy(() -> p.count(null))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("count")
                .hasMessageContaining("com.acme.PlanRule");

        assertThatThrownBy(() -> p.refresh(null, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("refresh");

        assertThatThrownBy(() -> p.getForDateRange(null, null, null))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("getForDateRange");
    }

    @Test
    void rejects_construction_without_its_collaborators() {
        assertThatThrownBy(() -> new DynamoDbPersister(null, mapping(), null_writer()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tx_participation_mode_is_accepted_rather_than_refused() {
        // DynamoDB has no interactive transaction to enlist in. Throwing here would break callers
        // that set a mode they do not actually depend on; the real limitation is documented instead.
        DynamoDbPersister p = new DynamoDbPersister(
                stubFinder(), mapping(), null_writer());
        p.setTxParticipationMode(null, null);
    }

    /**
     * These paths refuse before touching the finder, so a proxy is enough — and avoids dragging a
     * reladomogen fixture into this module just to satisfy a constructor null-check.
     */
    private static com.gs.fw.common.mithra.finder.RelatedFinder stubFinder() {
        return (com.gs.fw.common.mithra.finder.RelatedFinder) java.lang.reflect.Proxy.newProxyInstance(
                com.gs.fw.common.mithra.finder.RelatedFinder.class.getClassLoader(),
                new Class[]{com.gs.fw.common.mithra.finder.RelatedFinder.class},
                new java.lang.reflect.InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                        Class<?> rt = m.getReturnType();
                        if (rt == boolean.class) {
                            return Boolean.FALSE;
                        }
                        if (rt == int.class) {
                            return Integer.valueOf(0);
                        }
                        return null;
                    }
                });
    }

    private static DynamoDbWriter null_writer() {
        // Construction-only test: no call reaches the writer on these paths.
        return new DynamoDbWriter(software.amazon.awssdk.services.dynamodb.DynamoDbClient.builder()
                .region(software.amazon.awssdk.regions.Region.US_EAST_1)
                .endpointOverride(java.net.URI.create("http://127.0.0.1:1"))
                .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                        software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create("x", "y")))
                .build(),
                mapping(),
                new io.reladynamo.ddb.codec.ItemCodec(mapping()),
                new io.reladynamo.core.key.DefaultKeyStrategy());
    }

    private static Timestamp infinity() {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(9999, Calendar.DECEMBER, 1, 23, 59, 0);
        return new Timestamp(c.getTimeInMillis());
    }
}
