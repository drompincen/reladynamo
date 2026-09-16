---
name: tdd
description: Enforces genuine test-driven development for Java engineering work with JUnit 5 and AssertJ; invoke this skill whenever an agent adds or changes production behaviour, fixes a defect, refactors implementation, replaces a backend, characterises legacy code, or reviews whether a change was actually developed test-first.
user-invocable: true
---

# Test-driven development

TDD is evidence about causality, not the presence of test files. The evidence is a test that failed
for the expected behavioural reason, the smallest implementation that made it pass, and a refactor
performed while the same test remained green.

**Work in this order.** RED, then GREEN, then REFACTOR. Do not report TDD without the failing-test
output: a test never seen to fail may be irrelevant, vacuous, or exercising behaviour that already existed.

## Strict loop

### Rules

1. **Write one failing test before production code because RED proves the test can detect the missing behaviour.**
   Name the behaviour before selecting classes or methods.
   Change test code only during RED; do not pre-stage the implementation.
   Run the narrowest command that executes the new test.
   Stop if the test passes immediately; repair the test or choose genuinely missing behaviour.

2. **Run RED and quote the decisive failure because an unobserved failure proves nothing about the test's sensitivity.**
   Maven: `./mvnw -Dtest=PriceCalculatorTest#should_apply_discount_when_customer_is_vip test`.
   Gradle: `./gradlew test --tests '*PriceCalculatorTest.should_apply_discount_when_customer_is_vip'`.
   Record the assertion or exception line, not merely `BUILD FAILED`.
   Expected evidence: `expected: 800 but was: 1000`; reject compilation errors and unrelated failures.

3. **Make RED fail for the intended reason because a broken fixture or missing symbol does not validate the contract.**
   A new type may require a minimal compiling shell before the behavioural RED run.
   Treat `NullPointerException`, fixture load errors, and Mockito strictness errors as invalid RED unless they are the contract.
   Re-run after fixing test plumbing until the assertion exposes the absent behaviour.
   Quote the final behavioural failure in the work log or handoff.

4. **Write the minimum implementation after valid RED because GREEN tests the causal link between one change and one behaviour.**
   Implement only what the current test requires; defer speculative branches.
   Re-run the exact RED command before running the broader suite.
   Require the new test to pass without deleting, disabling, or weakening assertions.
   Then run the affected module or repository suite before refactoring.

5. **Refactor only with the suite green because structural change without a passing baseline obscures regressions.**
   Remove duplication, improve names, and simplify boundaries without changing the contract.
   Run focused tests after each small edit and the affected suite at completion.
   If a test fails, revert or fix the refactor; do not redefine expected behaviour mid-step.
   Start the next behaviour with a new RED cycle.

### Minimal RED-to-GREEN example

The first run must report `expected: 800 but was: 1000` before `PriceCalculator` gains the VIP branch.

```java
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

final class PriceCalculatorTest {
    @Test
    void should_apply_discount_when_customer_is_vip() {
        // Arrange
        PriceCalculator calculator = new PriceCalculator();

        // Act
        int totalCents = calculator.totalCents(1_000, true);

        // Assert
        assertThat(totalCents).isEqualTo(800);
    }
}

final class PriceCalculator {
    int totalCents(int subtotalCents, boolean vip) {
        return vip ? subtotalCents * 80 / 100 : subtotalCents;
    }
}
```

## Test design

### Rules

6. **Name tests `should_X_when_Y` because the name must state the observable outcome and triggering condition.**
   Use `should_reject_transfer_when_balance_is_insufficient`, not `testTransfer`.
   Omit `when_Y` only when the condition is the sole valid invocation.
   Do not encode private method names, loop branches, or implementation algorithms.
   Make a failing build readable without opening the test body.

7. **Separate Arrange, Act, and Assert because mixed phases hide what stimulus produced the observation.**
   Arrange only inputs, collaborators, and initial state.
   Act once through the public API; capture the result or thrown exception.
   Assert contract-visible output, state, event, or boundary call.
   Extract builders when setup overwhelms the behaviour, but keep decisive values in the test.

8. **Test one behaviour per test because multiple outcomes make failures ambiguous and GREEN changes oversized.**
   One behaviour may need several assertions on one returned value or state transition.
   Split tests when assertions could fail for independent reasons.
   Prefer AssertJ `satisfies` or `extracting` for one cohesive result.
   Never conceal several scenarios in a loop whose first failure masks the rest.

9. **Test contracts and boundaries because callers depend on observable behaviour rather than internal shape.**
   Test public use cases, domain invariants, serialization, persistence adapters, protocol mapping, and error semantics.
   Test getters only when transformation, authorization, defensive copying, or another contract exists.
   Do not test private methods directly; exercise them through the public behaviour they support.
   Do not assert field layout, helper invocation order, or algorithm choice unless the contract requires it.

10. **Reject implementation-first tests because a test written after code tends to rationalise the code's current mistakes.**
    Inspect history or the working diff: test change must precede the production change in the loop.
    A newly written test that passes immediately is not RED evidence.
    Reset the production hunk locally when safe, run the test against the pre-change behaviour, and capture its failure.
    If rollback is unsafe, write a distinct test that exposes still-missing behaviour; do not relabel the work TDD.

11. **Preserve failing assertions because loosening the contract to obtain GREEN destroys the specification.**
    Do not replace exact values with `isNotNull`, widen tolerances without domain evidence, or remove edge cases.
    Do not add `@Disabled`, catches, retries, or assumptions that bypass the failure.
    Change an expectation only when the requirement changed, and record that requirement separately.
    Require the implementation—not the oracle—to explain the transition from RED to GREEN.

## Test doubles and boundaries

### Rules

12. **Choose the least behavioural test double because each simulated interaction couples the test to implementation structure.**
    Stub: supplies canned input; mock: verifies expected interaction; fake: working lightweight implementation; spy: records calls while delegating.
    Prefer a fake when state and semantics fit in memory and can be shared across contract tests.
    Use a stub for deterministic responses that carry no behavioural assertion.
    Use a spy only when observing an unavoidable side effect; never spy on the class under test.

13. **Mock only process boundaries you own because internal mocks assert wiring instead of user-visible behaviour.**
    Mock an owned gateway interface around email, payments, queues, clocks, or remote APIs.
    Do not mock value objects, collections, repositories backed by a practical fake, or another method on the subject.
    Assert the returned value or state first; verify a boundary call only when the call itself is the contract.
    Wrap third-party clients behind an owned port so SDK churn does not rewrite domain tests.

```java
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class RegistrationServiceTest {
    @Test
    void should_send_welcome_when_registration_succeeds() {
        // Arrange
        FakeWelcomeMessages messages = new FakeWelcomeMessages();
        RegistrationService service = new RegistrationService(messages);

        // Act
        service.register("ada@example.test");

        // Assert
        assertThat(messages.recipients()).containsExactly("ada@example.test");
    }
}

interface WelcomeMessages {
    void sendTo(String email);
}

final class FakeWelcomeMessages implements WelcomeMessages {
    private final List<String> recipients = new ArrayList<>();

    public void sendTo(String email) {
        recipients.add(email);
    }

    List<String> recipients() {
        return List.copyOf(recipients);
    }
}

final class RegistrationService {
    private final WelcomeMessages messages;

    RegistrationService(WelcomeMessages messages) {
        this.messages = messages;
    }

    void register(String email) {
        messages.sendTo(email);
    }
}
```

## Existing behaviour and replacement implementations

### Rules

14. **Characterise legacy behaviour before refactoring because undocumented quirks may already be relied upon by callers.**
    Write tests against observed outputs, errors, ordering, and boundary effects before changing structure.
    Label surprising pinned behaviour so it is not mistaken for an endorsed requirement.
    Run and quote RED by deliberately perturbing the assertion or by proving the test fails against a known broken variant.
    Restore the characterisation suite to green before the refactor begins.

15. **Run the same contract suite against every implementation because duplicated tests can drift and conceal semantic differences.**
    Put all behavioural tests in one abstract JUnit 5 contract class.
    Supply the backend through one abstract factory method.
    Add one concrete subclass per backend; inherited `@Test` methods execute unchanged.
    Require old and replacement backends to pass together before cutover.

```java
import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

interface KeyValueStore {
    void put(String key, String value);
    String get(String key);
}

abstract class KeyValueStoreContractTest {
    protected abstract KeyValueStore createStore();

    @Test
    void should_return_value_when_key_was_written() {
        // Arrange
        KeyValueStore store = createStore();

        // Act
        store.put("region", "west");

        // Assert
        assertThat(store.get("region")).isEqualTo("west");
    }

    @Test
    void should_return_null_when_key_is_absent() {
        assertThat(createStore().get("missing")).isNull();
    }
}

final class HashMapStoreContractTest extends KeyValueStoreContractTest {
    protected KeyValueStore createStore() {
        return new HashMapStore();
    }
}

final class LegacyStoreContractTest extends KeyValueStoreContractTest {
    protected KeyValueStore createStore() {
        return new LegacyStore();
    }
}

final class HashMapStore implements KeyValueStore {
    private final Map<String, String> values = new HashMap<>();
    public void put(String key, String value) { values.put(key, value); }
    public String get(String key) { return values.get(key); }
}

final class LegacyStore implements KeyValueStore {
    private final Map<String, String> values = new HashMap<>();
    public void put(String key, String value) { values.put(key, value); }
    public String get(String key) { return values.get(key); }
}
```

## Property-based tests

### Rules

16. **Use properties when the input space matters more than named examples because generators explore combinations humans systematically omit.**
    Prefer properties for parsers, codecs, algebraic laws, ordering, normalization, and boundary-heavy numeric logic.
    Keep examples for named business cases whose meaning is clearer than a universal invariant.
    State the invariant before choosing generators; random examples without an oracle add noise.
    Preserve jqwik's reported seed and shrunk counterexample when diagnosing RED.

```java
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;

final class ReversalProperties {
    @Property(tries = 1_000)
    void should_restore_list_when_reversed_twice(@ForAll List<Integer> original) {
        List<Integer> reversed = new java.util.ArrayList<>(original);
        Collections.reverse(reversed);
        Collections.reverse(reversed);
        assertThat(reversed).isEqualTo(original);
    }
}
```

## Coverage, mutation and flakiness

### Rules

17. **Treat line coverage as a weak navigation signal because execution does not prove that assertions detect wrong results.**
    Use coverage to find untouched contracts and error paths, not to certify test quality.
    Reject targets that reward assertion-free execution or trivial getters.
    Review branch coverage where decisions encode business rules, but do not optimize the percentage alone.
    A covered line surviving semantic corruption remains unprotected.

18. **Run PIT mutation testing on changed logic because killed mutants demonstrate that tests detect plausible implementation faults.**
    Maven: `./mvnw org.pitest:pitest-maven:mutationCoverage`.
    Gradle with the PIT plugin: `./gradlew pitest`.
    Investigate surviving mutants in changed domain logic before chasing a global score.
    Exclude generated code only with an explicit rationale; never exclude hard-to-test production code by default.
    Treat equivalent mutants as review items, not automatic evidence of a missing test.

19. **Treat every flaky test as failing because nondeterminism makes both RED and GREEN untrustworthy.**
    Common causes: wall clock, scheduler races, shared mutable state, unordered collections, random ports, external services, locale, and timezone.
    Inject `Clock`, await observable conditions with a deadline, isolate storage, and seed generators.
    Do not add blind sleeps, retries, or relaxed assertions to suppress the symptom.
    Quarantine only with an owner, issue, expiry, and preserved CI visibility.

## Common pitfalls

| Failure mode | Symptom | Required correction |
|---|---|---|
| Implementation precedes test | New test is green on its first run | Restore pre-change behaviour and capture valid RED |
| Test asserts a mock graph | Rename or extraction breaks tests with unchanged output | Assert public result or state; mock only the owned process boundary |
| Assertion is loosened for GREEN | Regression becomes accepted as a broader expectation | Restore the contract and fix production code |
| Private method is tested directly | Harmless refactor requires test rewrites | Drive the private logic through a public use case |
| Fixture fails before assertion | RED shows setup exception or missing resource | Repair plumbing until the intended assertion fails |
| One test covers many scenarios | First assertion hides later failures | Split by behaviour and condition |
| Shared mutable fake leaks state | Tests pass alone and fail as a suite | Construct fresh state per test |
| Real clock or timezone leaks in | Failures cluster at midnight, DST, or CI locale | Inject fixed `Clock`, locale, and zone |
| Async code uses sleep | Fast machines pass; loaded CI fails | Await a state predicate with a bounded deadline |
| Random data lacks a recorded seed | Failure cannot be reproduced locally | Print and replay the seed; prefer shrinking frameworks |
| Characterisation suite is copied | Old and new backends silently diverge | Inherit one contract suite from both backends |
| High line coverage masks weak oracles | PIT survivors remain in covered code | Add assertions that kill meaningful mutants |
| Getter tests dominate metrics | Coverage rises without risk reduction | Delete trivial tests and cover contracts and boundaries |
| Test order is significant | Reordering or parallel execution fails | Remove shared state and reset boundary resources |

## Completion gate

20. **Report the full RED-GREEN-REFACTOR evidence because a green final suite alone cannot establish test-first development.**
    Include the test name and exact focused command.
    Quote the decisive RED assertion, then the GREEN result from the same command.
    Report the broader suite and PIT result when changed logic warrants mutation testing.
    State any skipped refactor, quarantine, surviving mutant, or untested external boundary explicitly.
    Do not claim TDD when RED evidence is unavailable; say tests were added after implementation.
