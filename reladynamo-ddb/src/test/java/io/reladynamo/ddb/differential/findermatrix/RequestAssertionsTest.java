package io.reladynamo.ddb.differential.findermatrix;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The runner itself must reject zero requests and an unexpected refusal. These assertions
 * are the cache-defeat proof; they do not need DynamoDB.
 */
class RequestAssertionsTest {

    @Test
    void should_reject_zero_ddb_reads_as_cache_hit() {
        assertThatThrownBy(() -> RequestAssertions.requirePositiveDataReads(0, "MATCH probe"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("data-read request delta was 0")
                .hasMessageContaining("without reaching DynamoDB");
    }

    @Test
    void should_reject_zero_reader_entries() {
        assertThatThrownBy(() -> RequestAssertions.requireReaderEntered(0, "MATCH probe"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("find/count entry delta was 0");
    }

    @Test
    void should_reject_scan_access_path() {
        assertThatThrownBy(() -> RequestAssertions.requireZeroScan(1, "MATCH probe"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Scan delta was 1");
    }

    @Test
    void should_reject_sql_fallback_during_ddb() {
        assertThatThrownBy(() -> RequestAssertions.requireNoSqlDuringDdb(1, "MATCH probe"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("SQL SELECT delta was 1");
    }
}
