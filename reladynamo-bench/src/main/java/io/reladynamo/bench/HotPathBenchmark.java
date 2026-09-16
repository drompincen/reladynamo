package io.reladynamo.bench;

import io.reladynamo.core.config.AttributeMapping;
import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.config.TemporalMapping;
import io.reladynamo.core.key.DefaultKeyStrategy;
import io.reladynamo.core.temporal.TemporalEncoder;
import io.reladynamo.ddb.codec.ItemCodec;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * The per-row work every read and write pays: temporal encoding, key derivation, and the item codec.
 *
 * <p>These run on every row of every query, so a bad constant here is not a micro-optimisation
 * question — it multiplies across the whole workload. The interesting number is not "is it fast" but
 * "is it negligible next to a DynamoDB round trip", which is single-digit milliseconds at best.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class HotPathBenchmark {

    private EntityMapping mapping;
    private ItemCodec codec;
    private DefaultKeyStrategy keys;
    private Map<String, Object> row;
    private Map<String, AttributeValue> item;
    private Map<String, Object> pk;
    private Timestamp businessFrom;
    private Timestamp processingFrom;
    private String encoded;

    @Setup
    public void setUp() {
        mapping = new EntityMapping("com.acme.Balance", "bench_balance",
                TemporalMapping.of(TemporalMapping.Flavour.BITEMPORAL, ts(9999, 12, 1)),
                Arrays.asList(
                        new AttributeMapping("balanceId", "balanceId", "int", true, false),
                        new AttributeMapping("quantity", "quantity", "double", false, false),
                        new AttributeMapping("amount", "amount", "BigDecimal", false, false),
                        new AttributeMapping("label", "label", "String", false, false),
                        new AttributeMapping("businessDateFrom", "FROM_Z", "Timestamp", false, false),
                        new AttributeMapping("businessDateTo", "THRU_Z", "Timestamp", false, false),
                        new AttributeMapping("processingDateFrom", "IN_Z", "Timestamp", false, false),
                        new AttributeMapping("processingDateTo", "OUT_Z", "Timestamp", false, false)));
        codec = new ItemCodec(mapping);
        keys = new DefaultKeyStrategy();
        businessFrom = ts(2026, 1, 1);
        processingFrom = ts(2026, 6, 1);

        row = new LinkedHashMap<>();
        row.put("balanceId", Integer.valueOf(42));
        row.put("quantity", Double.valueOf(1234.5));
        row.put("amount", new BigDecimal("1234.50"));
        row.put("label", "a typical label value");
        row.put("businessDateFrom", businessFrom);
        row.put("businessDateTo", ts(9999, 12, 1));
        row.put("processingDateFrom", processingFrom);
        row.put("processingDateTo", ts(9999, 12, 1));

        item = codec.encode(row);
        pk = new LinkedHashMap<>();
        pk.put("balanceId", Integer.valueOf(42));
        encoded = TemporalEncoder.encode(businessFrom);
    }

    @Benchmark
    public String temporalEncode() {
        return TemporalEncoder.encode(businessFrom);
    }

    @Benchmark
    public Timestamp temporalDecode() {
        return TemporalEncoder.decode(encoded);
    }

    @Benchmark
    public String partitionKey() {
        return keys.partitionKey(mapping, pk);
    }

    @Benchmark
    public String sortKey() {
        return keys.sortKey(mapping, processingFrom, businessFrom);
    }

    @Benchmark
    public Map<String, AttributeValue> codecEncode() {
        return codec.encode(row);
    }

    @Benchmark
    public Map<String, Object> codecDecode() {
        return codec.decode(item);
    }

    private static Timestamp ts(int y, int mo, int d) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(y, mo - 1, d, 0, 0, 0);
        return new Timestamp(c.getTimeInMillis());
    }
}
