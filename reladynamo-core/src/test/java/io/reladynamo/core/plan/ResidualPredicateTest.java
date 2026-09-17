package io.reladynamo.core.plan;

import com.gs.fw.common.mithra.finder.Operation;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResidualPredicateTest {

    @Test
    void should_treat_null_matches_as_not_a_pass() {
        assertThat(ResidualPredicate.isPass(null)).isFalse();
        assertThat(ResidualPredicate.isPass(Boolean.FALSE)).isFalse();
        assertThat(ResidualPredicate.isPass(Boolean.TRUE)).isTrue();
    }

    @Test
    void should_fail_loudly_when_residual_matches_returns_null() {
        Operation op = nullMatchesOperation();
        ResidualPredicate residual = new ResidualPredicate(op);

        assertThatThrownBy(() -> residual.matchesOrThrow(new Object()))
                .isInstanceOf(ReladynamoResidualEvaluationException.class)
                .hasMessageContaining("matches() returned null")
                .hasMessageContaining("planner mis-classification");
    }

    @Test
    void empty_residual_passes() {
        assertThat(ResidualPredicate.empty().matchesOrThrow(new Object())).isTrue();
        assertThat(ResidualPredicate.empty().isEmpty()).isTrue();
    }

    static Operation nullMatchesOperation() {
        return (Operation) Proxy.newProxyInstance(
                Operation.class.getClassLoader(),
                new Class[]{Operation.class},
                (proxy, method, args) -> {
                    if ("matches".equals(method.getName())) {
                        return null;
                    }
                    if ("toString".equals(method.getName()) || "zToString".equals(method.getName())) {
                        return "NullMatchesOp";
                    }
                    if ("hashCode".equals(method.getName())) {
                        return Integer.valueOf(7);
                    }
                    if ("equals".equals(method.getName())) {
                        return Boolean.valueOf(proxy == args[0]);
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) {
                        return Boolean.FALSE;
                    }
                    if (rt == int.class) {
                        return Integer.valueOf(0);
                    }
                    return null;
                });
    }
}
