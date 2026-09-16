package io.reladynamo.ddb.codec;

import io.reladynamo.core.config.AttributeMapping;
import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.config.TemporalMapping;
import io.reladynamo.core.key.DefaultKeyStrategy;
import io.reladynamo.core.mapping.MithraObjectXmlParser;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The strongest available test of the word "generic": every object model in the three demo projects,
 * keyed and round-tripped through the codec.
 *
 * <p>These ~80 XMLs were written for three unrelated domains — a CRM, a pet store and a car
 * classifier — by a different author, to demonstrate Reladomo rather than to suit this adapter. If
 * the adapter only works on fixtures written alongside it, "generic" is a claim rather than a
 * property. This is the test that tells the difference.
 */
class DemoCorpusCodecTest {

    @Test
    void every_demo_entity_can_be_keyed_and_round_tripped() throws IOException {
        List<Path> files = demoObjectFiles();
        assertThat(files).as("expected the three demo projects' object models").hasSizeGreaterThan(60);

        DefaultKeyStrategy keys = new DefaultKeyStrategy();
        List<String> failures = new ArrayList<>();
        int checked = 0;

        for (Path file : files) {
            String xml = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            if (!xml.contains("<MithraObject")) {
                continue;
            }
            EntityMapping mapping;
            try {
                mapping = new MithraObjectXmlParser().parse(xml);
            } catch (RuntimeException e) {
                failures.add(file.getFileName() + " — parse: " + e.getMessage());
                continue;
            }
            try {
                Map<String, Object> row = syntheticRow(mapping);
                ItemCodec codec = new ItemCodec(mapping);
                Map<String, AttributeValue> item = codec.encode(row);
                Map<String, Object> back = codec.decode(item);

                for (AttributeMapping a : mapping.attributes()) {
                    Object before = row.get(a.javaName());
                    Object after = back.get(a.javaName());
                    if (before != null && !before.equals(after)) {
                        failures.add(mapping.className() + "." + a.javaName()
                                + " — round trip: " + before + " -> " + after);
                    }
                }

                Map<String, Object> pk = new LinkedHashMap<>();
                for (AttributeMapping a : mapping.primaryKeyAttributes()) {
                    pk.put(a.javaName(), row.get(a.javaName()));
                }
                String partition = keys.partitionKey(mapping, pk);
                assertThat(partition).startsWith("v1#");

                TemporalMapping t = mapping.temporal();
                String sort = keys.sortKey(mapping,
                        t.hasProcessingDate() ? (Timestamp) row.get("processingDateFrom") : null,
                        t.hasBusinessDate() ? (Timestamp) row.get("businessDateFrom") : null);
                assertThat(sort).as("%s must produce a sort key", mapping.className()).isNotNull();
                checked++;
            } catch (RuntimeException e) {
                failures.add(mapping.className() + " — " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }

        assertThat(checked).as("entities actually exercised").isGreaterThan(60);
        assertThat(failures)
                .as("every demo entity must key and round-trip; %s of %s failed", failures.size(), checked)
                .isEmpty();
    }

    /** A value of the right type for every attribute, so the codec sees each mapped Java type. */
    private static Map<String, Object> syntheticRow(EntityMapping mapping) {
        Map<String, Object> row = new LinkedHashMap<>();
        int seed = 1;
        for (AttributeMapping a : mapping.attributes()) {
            row.put(a.javaName(), valueFor(a.javaType(), seed++));
        }
        return row;
    }

    private static Object valueFor(String javaType, int seed) {
        String t = javaType.toLowerCase(java.util.Locale.ROOT);
        if (t.equals("int") || t.equals("integer")) {
            return Integer.valueOf(seed);
        }
        if (t.equals("long")) {
            return Long.valueOf(seed);
        }
        if (t.equals("double")) {
            return Double.valueOf(seed + 0.5);
        }
        if (t.equals("float")) {
            return Float.valueOf(seed + 0.25f);
        }
        if (t.equals("short")) {
            return Short.valueOf((short) seed);
        }
        if (t.equals("byte")) {
            return Byte.valueOf((byte) seed);
        }
        if (t.equals("char") || t.equals("character")) {
            return Character.valueOf('x');
        }
        if (t.equals("boolean")) {
            return Boolean.valueOf(seed % 2 == 0);
        }
        if (t.equals("bigdecimal")) {
            return new BigDecimal(seed + ".10");
        }
        if (t.equals("timestamp")) {
            return utc(2026, 6, 1);
        }
        if (t.equals("date")) {
            return new java.sql.Date(utc(2026, 6, 1).getTime());
        }
        if (t.equals("time")) {
            return com.gs.fw.common.mithra.util.Time.withMillis(12, 30, 0, 0);
        }
        if (t.equals("byte[]")) {
            return new byte[]{(byte) seed};
        }
        return "v" + seed;
    }

    private static List<Path> demoObjectFiles() throws IOException {
        List<Path> out = new ArrayList<>();
        Path demos = Paths.get("..", "demos");
        if (!Files.isDirectory(demos)) {
            return out;
        }
        try (Stream<Path> walk = Files.walk(demos)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().replace('\\', '/').contains("/resources/reladomo/"))
                    .filter(p -> p.getFileName().toString().endsWith(".xml"))
                    .filter(p -> !p.getFileName().toString().contains("ClassList"))
                    .filter(p -> !p.getFileName().toString().contains("Runtime"))
                    .forEach(out::add);
        }
        return out;
    }

    private static Timestamp utc(int y, int mo, int d) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(y, mo - 1, d, 0, 0, 0);
        return new Timestamp(c.getTimeInMillis());
    }
}
