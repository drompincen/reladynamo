package io.reladynamo.ddb.codec;

/**
 * The item's {@code _rd_v} is outside the codec's supported range. Decode refuses rather
 * than guessing a transformation for an unknown future (or retired) version.
 */
public final class UnsupportedSchemaVersionException extends CodecException {

    public static final String CODE = "RD_UNSUPPORTED_SCHEMA_VERSION";

    private final int foundVersion;
    private final int minSupported;
    private final int maxSupported;

    public UnsupportedSchemaVersionException(int foundVersion, int minSupported, int maxSupported) {
        super(CODE + ": _rd_v=" + foundVersion
                + " is outside supported range [" + minSupported + "," + maxSupported + "]");
        this.foundVersion = foundVersion;
        this.minSupported = minSupported;
        this.maxSupported = maxSupported;
    }

    public String code() {
        return CODE;
    }

    public int foundVersion() {
        return foundVersion;
    }

    public int minSupported() {
        return minSupported;
    }

    public int maxSupported() {
        return maxSupported;
    }
}
