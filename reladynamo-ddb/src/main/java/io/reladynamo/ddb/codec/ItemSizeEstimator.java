package io.reladynamo.ddb.codec;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Pre-write item-size estimate against DynamoDB's 400 KB hard limit.
 *
 * <p>Numbers are over-estimated ({@code digitCount + 1} rather than {@code digits/2 + 1}) so a
 * false reject is preferred to a {@code ValidationException} mid-transaction.
 */
final class ItemSizeEstimator {

    int estimate(Map<String, AttributeValue> item) {
        int total = 0;
        for (Map.Entry<String, AttributeValue> entry : item.entrySet()) {
            total += utf8Len(entry.getKey());
            total += valueBytes(entry.getValue());
        }
        return total;
    }

    private static int valueBytes(AttributeValue av) {
        AttributeValue.Type type = av.type();
        if (type == null) {
            return 1;
        }
        switch (type) {
            case S:
                return utf8Len(av.s());
            case N:
                return numberBytes(av.n());
            case B:
                return av.b() == null ? 0 : av.b().asByteArray().length;
            case BOOL:
            case NUL:
                return 1;
            case M:
                return mapBytes(av);
            case L:
                return listBytes(av);
            case SS:
                return stringSetBytes(av);
            case NS:
                return numberSetBytes(av);
            case BS:
                return binarySetBytes(av);
            default:
                return 1;
        }
    }

    private static int mapBytes(AttributeValue av) {
        int size = 3;
        if (av.hasM()) {
            for (Map.Entry<String, AttributeValue> entry : av.m().entrySet()) {
                size += utf8Len(entry.getKey());
                size += valueBytes(entry.getValue());
            }
        }
        return size;
    }

    private static int listBytes(AttributeValue av) {
        int size = 3;
        if (av.hasL()) {
            for (AttributeValue nested : av.l()) {
                size += valueBytes(nested);
            }
        }
        return size;
    }

    private static int stringSetBytes(AttributeValue av) {
        int size = 3;
        if (av.hasSs()) {
            for (String s : av.ss()) {
                size += utf8Len(s);
            }
        }
        return size;
    }

    private static int numberSetBytes(AttributeValue av) {
        int size = 3;
        if (av.hasNs()) {
            for (String n : av.ns()) {
                size += numberBytes(n);
            }
        }
        return size;
    }

    private static int binarySetBytes(AttributeValue av) {
        int size = 3;
        if (av.hasBs()) {
            for (software.amazon.awssdk.core.SdkBytes b : av.bs()) {
                size += b.asByteArray().length;
            }
        }
        return size;
    }

    /**
     * AWS documents number size as roughly {@code (significantDigits / 2) + 1}. Counting every
     * digit plus one over-estimates, which is the safe direction.
     */
    private static int numberBytes(String n) {
        if (n == null) {
            return 1;
        }
        int digits = 0;
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c >= '0' && c <= '9') {
                digits++;
            }
        }
        return digits + 1;
    }

    private static int utf8Len(String s) {
        if (s == null) {
            return 0;
        }
        return s.getBytes(StandardCharsets.UTF_8).length;
    }
}
