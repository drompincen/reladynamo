package io.reladynamo.ddb.differential.findermatrix;

import com.gs.fw.common.mithra.attribute.BigDecimalAttribute;
import com.gs.fw.common.mithra.attribute.BooleanAttribute;
import com.gs.fw.common.mithra.attribute.ByteArrayAttribute;
import com.gs.fw.common.mithra.attribute.DoubleAttribute;
import com.gs.fw.common.mithra.attribute.FloatAttribute;
import com.gs.fw.common.mithra.attribute.IntegerAttribute;
import com.gs.fw.common.mithra.attribute.LongAttribute;
import com.gs.fw.common.mithra.attribute.StringAttribute;
import com.gs.fw.common.mithra.attribute.TimestampAttribute;
import io.reladynamo.ddb.differential.DifferentialSupport;
import io.reladynamo.ddb.differential.domain.DiffFinderValue;
import io.reladynamo.ddb.differential.domain.DiffFinderValueFinder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Generated APIs the matrix assumes. javap of these classes is recorded in BUILD-LOG;
 * this test fails the build if the generator omitted an accessor or changed a type.
 */
class DiffFinderValueGeneratedApiTest {

    @BeforeAll
    static void boot() {
        DifferentialSupport.boot();
    }

    @Test
    void should_expose_typed_accessors_the_matrix_names() {
        assertThat(DiffFinderValueFinder.scopeId()).isInstanceOf(IntegerAttribute.class);
        assertThat(DiffFinderValueFinder.rowId()).isInstanceOf(IntegerAttribute.class);
        assertThat(DiffFinderValueFinder.bucketId()).isInstanceOf(IntegerAttribute.class);
        assertThat(DiffFinderValueFinder.intValue()).isInstanceOf(IntegerAttribute.class);
        assertThat(DiffFinderValueFinder.longValue()).isInstanceOf(LongAttribute.class);
        assertThat(DiffFinderValueFinder.doubleValue()).isInstanceOf(DoubleAttribute.class);
        assertThat(DiffFinderValueFinder.floatValue()).isInstanceOf(FloatAttribute.class);
        assertThat(DiffFinderValueFinder.decimalValue()).isInstanceOf(BigDecimalAttribute.class);
        assertThat(DiffFinderValueFinder.textValue()).isInstanceOf(StringAttribute.class);
        assertThat(DiffFinderValueFinder.booleanValue()).isInstanceOf(BooleanAttribute.class);
        assertThat(DiffFinderValueFinder.timestampValue()).isInstanceOf(TimestampAttribute.class);
        assertThat(DiffFinderValueFinder.bytesValue()).isInstanceOf(ByteArrayAttribute.class);
    }

    @Test
    void should_be_non_dated_with_composite_pk_scope_then_row() {
        assertThat(DiffFinderValueFinder.getAsOfAttributes()).isNull();
        assertThat(DiffFinderValueFinder.getPrimaryKeyAttributes())
                .containsExactly(DiffFinderValueFinder.scopeId(), DiffFinderValueFinder.rowId());
        assertThat(DiffFinderValueFinder.getSourceAttributeType()).isNull();
    }

    @Test
    void should_expose_find_one_find_many_and_nullable_primitive_null_flags() {
        assertThat(DiffFinderValueFinder.findOne(DiffFinderValueFinder.scopeId().eq(-1))).isNull();
        assertThat(DiffFinderValueFinder.findMany(DiffFinderValueFinder.scopeId().eq(-1))).isNotNull();
        DiffFinderValue row = new DiffFinderValue();
        row.setIntValueNull();
        assertThat(row.isIntValueNull()).isTrue();
        row.setBooleanValueNull();
        assertThat(row.isBooleanValueNull()).isTrue();
    }
}
