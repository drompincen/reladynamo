package io.reladynamo.ddb.differential.findermatrix;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** One DIFF_FINDER_VALUE row. Every mapped attribute is specified; null means SQL/DynamoDB NULL. */
final class ValueRow {

    final int scopeId;
    final int rowId;
    final int bucketId;
    final Integer intValue;
    final Long longValue;
    final Double doubleValue;
    final Float floatValue;
    final BigDecimal decimalValue;
    final String textValue;
    final Boolean booleanValue;
    final Timestamp timestampValue;
    final byte[] bytesValue;

    ValueRow(int scopeId, int rowId, int bucketId,
             Integer intValue, Long longValue, Double doubleValue, Float floatValue,
             BigDecimal decimalValue, String textValue, Boolean booleanValue,
             Timestamp timestampValue, byte[] bytesValue) {
        this.scopeId = scopeId;
        this.rowId = rowId;
        this.bucketId = bucketId;
        this.intValue = intValue;
        this.longValue = longValue;
        this.doubleValue = doubleValue;
        this.floatValue = floatValue;
        this.decimalValue = decimalValue;
        this.textValue = textValue;
        this.booleanValue = booleanValue;
        this.timestampValue = timestampValue == null ? null : (Timestamp) timestampValue.clone();
        this.bytesValue = bytesValue == null ? null : Arrays.copyOf(bytesValue, bytesValue.length);
    }

    ValueRow copyReplacing(int newScope, int newRow, int newBucket) {
        return new ValueRow(newScope, newRow, newBucket, intValue, longValue, doubleValue, floatValue,
                decimalValue, textValue, booleanValue, timestampValue, bytesValue);
    }

    ValueRow withInt(Integer v) {
        return new ValueRow(scopeId, rowId, bucketId, v, longValue, doubleValue, floatValue,
                decimalValue, textValue, booleanValue, timestampValue, bytesValue);
    }

    ValueRow withDouble(Double v) {
        return new ValueRow(scopeId, rowId, bucketId, intValue, longValue, v, floatValue,
                decimalValue, textValue, booleanValue, timestampValue, bytesValue);
    }

    ValueRow withFloat(Float v) {
        return new ValueRow(scopeId, rowId, bucketId, intValue, longValue, doubleValue, v,
                decimalValue, textValue, booleanValue, timestampValue, bytesValue);
    }

    ValueRow withDecimal(BigDecimal v) {
        return new ValueRow(scopeId, rowId, bucketId, intValue, longValue, doubleValue, floatValue,
                v, textValue, booleanValue, timestampValue, bytesValue);
    }

    ValueRow withText(String v) {
        return new ValueRow(scopeId, rowId, bucketId, intValue, longValue, doubleValue, floatValue,
                decimalValue, v, booleanValue, timestampValue, bytesValue);
    }

    ValueRow withBytes(byte[] v) {
        return new ValueRow(scopeId, rowId, bucketId, intValue, longValue, doubleValue, floatValue,
                decimalValue, textValue, booleanValue, timestampValue, v);
    }

    Map<String, Object> toMap() {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("scopeId", Integer.valueOf(scopeId));
        row.put("rowId", Integer.valueOf(rowId));
        row.put("bucketId", Integer.valueOf(bucketId));
        row.put("intValue", intValue);
        row.put("longValue", longValue);
        row.put("doubleValue", doubleValue);
        row.put("floatValue", floatValue);
        row.put("decimalValue", decimalValue);
        row.put("textValue", textValue);
        row.put("booleanValue", booleanValue);
        row.put("timestampValue", timestampValue == null ? null : (Timestamp) timestampValue.clone());
        row.put("bytesValue", bytesValue == null ? null : Arrays.copyOf(bytesValue, bytesValue.length));
        return row;
    }
}
