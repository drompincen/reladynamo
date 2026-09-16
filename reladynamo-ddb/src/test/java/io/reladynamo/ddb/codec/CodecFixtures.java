package io.reladynamo.ddb.codec;

import io.reladynamo.core.config.AttributeMapping;
import io.reladynamo.core.config.EntityMapping;
import io.reladynamo.core.config.TemporalMapping;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class CodecFixtures {

    static final Timestamp INFINITY = Timestamp.from(Instant.parse("9999-12-01T23:59:00Z"));

    private CodecFixtures() {
    }

    static AttributeMapping pk(String javaName, String javaType) {
        return new AttributeMapping(javaName, javaName, javaType, true, false);
    }

    static AttributeMapping attr(String javaName, String javaType, boolean nullable) {
        return new AttributeMapping(javaName, javaName, javaType, false, nullable);
    }

    static AttributeMapping attr(String javaName, String itemName, String javaType,
                                 boolean primaryKey, boolean nullable) {
        return new AttributeMapping(javaName, itemName, javaType, primaryKey, nullable);
    }

    static EntityMapping entity(AttributeMapping payload) {
        List<AttributeMapping> attributes = new ArrayList<>();
        attributes.add(pk("id", "long"));
        attributes.add(payload);
        return entity("Position", attributes);
    }

    static EntityMapping entity(String className, List<AttributeMapping> attributes) {
        return new EntityMapping(
                className,
                className,
                TemporalMapping.of(TemporalMapping.Flavour.BITEMPORAL, INFINITY),
                attributes);
    }

    static Map<String, Object> values(String payloadName, Object payloadValue) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", 1L);
        values.put(payloadName, payloadValue);
        return values;
    }
}
