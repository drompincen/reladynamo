package io.reladynamo.ddb.codec;

/**
 * Reladomo scalar types the codec knows how to put on the wire. Parsed from
 * {@code AttributeMapping.javaType()} — both the short names Reladomo XML uses and the
 * fully-qualified Java names.
 */
enum AttributeKind {
    BOOLEAN,
    BYTE,
    SHORT,
    INT,
    LONG,
    FLOAT,
    DOUBLE,
    CHAR,
    STRING,
    SQL_DATE,
    SQL_TIMESTAMP,
    SQL_TIME,
    RELADOMO_TIME,
    BIG_DECIMAL,
    BYTE_ARRAY;

    static AttributeKind parse(String javaType) {
        if (javaType == null || javaType.isEmpty()) {
            throw new CodecException("javaType is required");
        }
        switch (javaType) {
            case "boolean":
            case "Boolean":
            case "java.lang.Boolean":
                return BOOLEAN;
            case "byte":
            case "Byte":
            case "java.lang.Byte":
                return BYTE;
            case "short":
            case "Short":
            case "java.lang.Short":
                return SHORT;
            case "int":
            case "Integer":
            case "java.lang.Integer":
                return INT;
            case "long":
            case "Long":
            case "java.lang.Long":
                return LONG;
            case "float":
            case "Float":
            case "java.lang.Float":
                return FLOAT;
            case "double":
            case "Double":
            case "java.lang.Double":
                return DOUBLE;
            case "char":
            case "Character":
            case "java.lang.Character":
                return CHAR;
            case "String":
            case "java.lang.String":
                return STRING;
            case "Date":
            case "java.sql.Date":
                return SQL_DATE;
            case "Timestamp":
            case "java.sql.Timestamp":
                return SQL_TIMESTAMP;
            case "java.sql.Time":
                return SQL_TIME;
            case "Time":
            case "com.gs.fw.common.mithra.util.Time":
                return RELADOMO_TIME;
            case "BigDecimal":
            case "java.math.BigDecimal":
                return BIG_DECIMAL;
            case "byte[]":
            case "[B":
                return BYTE_ARRAY;
            default:
                throw new CodecException("unsupported Reladomo javaType: " + javaType);
        }
    }
}
