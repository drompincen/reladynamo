package io.reladynamo.ddb.differential.findermatrix;

import com.gs.fw.common.mithra.finder.orderby.OrderBy;
import io.reladynamo.core.plan.OrderByTranslator;
import io.reladynamo.core.plan.UnsignedByteArrayOrderBy;
import io.reladynamo.ddb.differential.DifferentialSupport;
import io.reladynamo.ddb.differential.domain.DiffFinderValue;
import io.reladynamo.ddb.differential.domain.DiffFinderValueFinder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Finding 30: Reladomo 18.1.0 {@code ByteArrayOrderBy} crashes on empty {@code byte[]}
 * (fixture V row 2) and compares signed bytes. DynamoDB binary order is unsigned
 * lexicographic, shorter first. The replacement must be what MATCH_H2 uses so H2
 * can serve as the oracle without weakening the six-row content-order assertion.
 */
class ByteArrayOrderByConformanceTest {

    @BeforeAll
    static void boot() {
        DifferentialSupport.boot();
    }

    @Test
    void should_throw_index_zero_of_empty_when_reladomo_orders_empty_against_non_empty() {
        OrderBy reladomo = DiffFinderValueFinder.bytesValue().ascendingOrderBy();
        DiffFinderValue empty = withBytes(new byte[0]);
        DiffFinderValue nonempty = withBytes(new byte[] {0x00, (byte) 0xFF});

        assertThatThrownBy(() -> reladomo.compare(empty, nonempty))
                .isInstanceOf(ArrayIndexOutOfBoundsException.class)
                .hasMessageContaining("Index 0 out of bounds for length 0");
    }

    @Test
    void should_order_empty_first_then_unsigned_content_when_byte_array_order_by_is_rewritten() {
        OrderBy rewritten = OrderByTranslator.withUnsignedByteArrayOrder(
                DiffFinderValueFinder.bytesValue().ascendingOrderBy()
                        .and(DiffFinderValueFinder.rowId().ascendingOrderBy()));

        assertThat(rewritten).isNotNull();
        List<DiffFinderValue> rows = new ArrayList<DiffFinderValue>();
        rows.add(withBytesAndRow(4, new byte[] {(byte) 0x80}));
        rows.add(withBytesAndRow(2, new byte[0]));
        rows.add(withBytesAndRow(7, new byte[] {(byte) 0xFF}));
        rows.add(withBytesAndRow(1, new byte[] {0x00, (byte) 0xFF}));
        rows.add(withBytesAndRow(6, new byte[] {0x01, 0x02}));
        rows.add(withBytesAndRow(3, new byte[] {0x01, 0x02}));

        Collections.sort(rows, rewritten);

        assertThat(rowIds(rows)).containsExactly(
                Integer.valueOf(2), Integer.valueOf(1), Integer.valueOf(3),
                Integer.valueOf(6), Integer.valueOf(4), Integer.valueOf(7));
    }

    @Test
    void should_keep_unsigned_order_when_0x80_would_sort_first_as_a_signed_byte() {
        OrderBy rewritten = OrderByTranslator.withUnsignedByteArrayOrder(
                DiffFinderValueFinder.bytesValue().ascendingOrderBy());
        assertThat(rewritten).isInstanceOf(UnsignedByteArrayOrderBy.class);

        DiffFinderValue highBit = withBytes(new byte[] {(byte) 0x80});
        DiffFinderValue low = withBytes(new byte[] {0x01});
        assertThat(rewritten.compare(low, highBit))
                .as("unsigned 0x01 must sort before 0x80; Reladomo signed subtract would reverse them")
                .isLessThan(0);
    }

    private static DiffFinderValue withBytes(byte[] bytes) {
        return withBytesAndRow(0, bytes);
    }

    private static DiffFinderValue withBytesAndRow(int rowId, byte[] bytes) {
        DiffFinderValue row = new DiffFinderValue();
        row.setScopeId(1);
        row.setRowId(rowId);
        row.setBytesValue(bytes);
        return row;
    }

    private static List<Integer> rowIds(List<DiffFinderValue> rows) {
        List<Integer> ids = new ArrayList<Integer>();
        for (int i = 0; i < rows.size(); i++) {
            ids.add(Integer.valueOf(rows.get(i).getRowId()));
        }
        return ids;
    }
}
