---
name: epic-breakdown-advisor
description: Break an epic into independently shippable vertical stories, ordered to expose risk and generate useful learning early.
user-invocable: true
---

# Epic Breakdown Advisor

Turn a large product outcome into small stories that each produce observable user or business value. Use this when an epic is too large to deliver safely or has been divided by technical component instead of usable behavior.

## Responsibilities

1. **State the outcome** — rewrite the epic as a measurable change for a named actor, not as a list of features or components.
2. **Set the boundaries** — record what is in scope, what is explicitly out of scope, and any fixed constraints such as policy, deadline, platform, or data availability.
3. **Map the end-to-end behavior** — describe the shortest path from the actor's trigger to an observable result, including exceptional outcomes that materially affect value.
4. **Identify slice dimensions** — look for differences in actors, scenarios, data, rules, channels, scale, and operational treatment.
5. **Draft vertical slices** — make every story cross the necessary interface, logic, and data boundaries to deliver one usable result.
6. **Test independence** — confirm each story can be released, observed, and evaluated without waiting for another story in the proposed set.
7. **Thin oversized stories** — narrow rules, inputs, actors, or paths while preserving a complete end-to-end result.
8. **Sequence for evidence** — order stories to test the riskiest assumption or most consequential unknown early, then expand capability.
9. **Define acceptance evidence** — specify observable examples and signals that show whether each slice works and matters.
10. **Expose enabling work** — attach technical preparation to the first slice that needs it; create a separate enabler only when it produces a verifiable capability and cannot safely fit inside a story.
11. **Check coverage** — map every essential epic behavior to at least one story and identify deliberate omissions.
12. **Return the artifact** — use the output format below and include unresolved decisions rather than silently inventing product policy.

## Working method

### 1. Frame the epic

Write one sentence in this form:

> When **[actor]** encounters **[trigger]**, they can **[complete behavior]**, resulting in **[observable outcome]**.

Replace “build notifications” with a result such as:

> When an account owner is nearing a usage limit, they receive enough warning to act before service is interrupted.

Reject outcome statements that name only a system, screen, service, or implementation task.

### 2. Trace the value path

List the minimum steps required for the actor to reach the outcome:

1. Trigger occurs.
2. System recognizes the relevant condition.
3. Actor receives or requests information.
4. Actor takes an action.
5. System confirms the result.
6. Team observes whether the outcome occurred.

Use the actual path for the epic. Do not turn these steps into separate stories by default. They are a checklist of concerns that a vertical story may cross.

### 3. Generate candidate slices

Split along product behavior before splitting along architecture. Try these dimensions in order:

1. **Happy path before exceptions** — support one successful route, then add recovery and edge cases.
2. **One actor before more actors** — serve the primary actor, then add roles with distinct needs.
3. **One scenario before more scenarios** — choose a real, frequent situation, then widen coverage.
4. **Simple rules before complex rules** — use one clear policy, then add thresholds, overrides, and combinations.
5. **One data shape before more shapes** — accept the common input, then unusual or incomplete inputs.
6. **Manual operation before automation** — permit a safe human step when it validates demand without invalidating the user outcome.
7. **One channel before more channels** — deliver through a useful channel, then add alternatives.
8. **Small scale before full scale** — release to a bounded cohort when the result remains genuine for that cohort.
9. **Read before change** — show trustworthy information before enabling consequential edits, when viewing alone has value.
10. **Basic feedback before optimization** — measure the first behavior before adding sophistication.

Do not force every dimension. Choose the one that makes the next story smaller while keeping it releasable.

### 4. Apply the vertical-slice test

For every candidate story, answer yes or no:

- Does a named actor receive a meaningful result?
- Can the story be released without the remaining stories?
- Can acceptance be demonstrated through behavior rather than component completion?
- Does it include the minimum interface, logic, data, and operational support needed for that result?
- Can the team observe use, success, failure, or learning after release?
- Would omitting later stories leave this story coherent and supportable?

Revise any story with a “no.” If the only acceptance statement is “API exists,” “table created,” or “UI complete,” it is probably a horizontal slice.

### 5. Sequence deliberately

Score each candidate qualitatively:

| Factor | Low | Medium | High |
|---|---|---|---|
| Outcome value | Minor convenience | Useful improvement | Essential result |
| Assumption risk | Familiar and evidenced | Some uncertainty | Could invalidate the approach |
| Learning value | Confirms little | Answers a useful question | Changes a major decision |
| Delivery exposure | Contained and reversible | Several dependencies | Novel, coupled, or hard to reverse |

Prefer an early slice with high learning value and a safe, genuine outcome. Do not automatically put the highest-value, largest slice first. State why each position in the sequence is justified.

## Output format

Produce the following artifact. Keep story identifiers stable when revising it.

```markdown
# Epic breakdown: [epic name]

## Outcome
When [actor] encounters [trigger], they can [behavior], resulting in [observable outcome].

**Success signal:** [metric, observation, or decision enabled]
**In scope:** [behaviors, actors, or channels]
**Out of scope:** [explicit exclusions]
**Constraints:** [policy, deadline, platform, safety, or data constraints]

## Assumptions to test

| ID | Assumption | Consequence if wrong | Evidence needed | Earliest story |
|---|---|---|---|---|
| A1 | [belief] | [impact] | [observable evidence] | S1 |

## End-to-end value path

1. [Actor trigger]
2. [System or human response]
3. [Actor action]
4. [Observable result]

## Proposed stories

### S1 — [behavioral title]

**Actor and value:** [who receives what useful result]
**Slice boundary:** [included scenario/rule/data/channel/cohort]
**Not yet:** [deliberately deferred behavior]
**End-to-end path:** [trigger → interaction → result]
**Risk or learning target:** [assumption tested or risk retired]
**Release approach:** [cohort, flag, manual support, or general release]

**Acceptance examples:**

- Given [starting condition], when [action], then [observable result].
- Given [important failure condition], when [action], then [safe observable response].

**Evidence after release:** [event, metric, interview signal, support observation, or operational check]
**Dependencies:** [external dependency only; write "none" if independently releasable]

### S2 — [behavioral title]

[Repeat the S1 fields.]

## Sequence rationale

| Order | Story | Value now | Risk or learning addressed | Why before the next story |
|---|---|---|---|---|
| 1 | S1 | [result] | [unknown reduced] | [reason] |
| 2 | S2 | [result] | [unknown reduced] | [reason] |

## Coverage check

| Epic behavior | Covered by | Status |
|---|---|---|
| [behavior] | S1 | covered |
| [behavior] | — | deferred: [reason] |

## Enabling work

| Work | Needed by | Verification | Why it cannot remain inside the story |
|---|---|---|---|
| [enabler or "none"] | [story] | [demonstrable capability] | [reason] |

## Open decisions

- [Decision owner]: [question that changes scope, policy, or sequence]
```

## Worked example

Epic: “Add usage-limit notifications.” Avoid creating separate stories for a notification table, threshold service, settings screen, email template, and analytics event.

Prefer slices such as:

| Order | Story | Complete result | Learning purpose |
|---|---|---|---|
| 1 | Warn account owners by email at 90% usage | A bounded cohort receives one actionable warning and can reach usage details | Test whether warnings arrive in time and prompt action |
| 2 | Let owners choose 75%, 90%, or 100% thresholds | Owners tune warning timing without support help | Learn which thresholds are useful |
| 3 | Add in-product warnings for active owners | Owners who miss email see the same actionable state while using the product | Compare channel reach and response |
| 4 | Handle rapid threshold crossings and repeated warnings | Owners receive accurate warnings without noise during usage spikes | Retire correctness and trust risk |

Story 1 still needs a trigger, persisted state, email content, a destination, and an observable delivery result. Those are implementation tasks within the story, not separate product stories.

## Quality bar

A good breakdown passes all of these checks:

- Every story names an actor, trigger, behavior, and observable result.
- Every story can be released without pretending that unfinished layers deliver value.
- The first story is narrow but real; it is not a prototype presented as production behavior.
- Acceptance examples cover one successful path and the most important safe failure response.
- Deferred scope is explicit, so “small” does not mean ambiguous.
- Sequence rationale connects order to value, risk, or learning rather than development convenience alone.
- Evidence can change a later decision; metrics are not included merely because they are easy to count.
- Enabling work is minimal, attached to a consumer story where possible, and independently verifiable otherwise.
- Coverage shows where every essential epic behavior went.

Avoid these failure modes:

- **Horizontal layers:** Do not create “build UI,” “create API,” “design schema,” and “add tests” as separate stories.
- **Dependent fragments:** Do not call work shippable when users see no coherent result until several stories are complete.
- **Technical titles hiding technical scope:** Do not rename “implement rules engine” as “user gets rules engine” without changing the boundary.
- **Mini-waterfalls:** Do not sequence analysis, design, backend, frontend, and validation as if each were a value increment.
- **Oversized happy paths:** Do not place every actor, rule, channel, and data case into the first story.
- **Valueless thinness:** Do not produce a slice so narrow that no real actor would use or benefit from it.
- **Risk-last sequencing:** Do not defer the assumption most likely to invalidate the approach until after expensive expansion.
- **Invisible success:** Do not accept a story that cannot be demonstrated or observed after release.
- **Disguised projects:** Do not use one story with many phases, teams, or independent outcomes to avoid making actual trade-offs.
- **Unowned policy gaps:** Do not invent thresholds, permissions, compliance behavior, or commercial rules; record an open decision.

## When not to use this

Do not use this skill for a single small defect, a routine maintenance task with no user-facing outcome, or work already small enough to ship and verify independently. Do not use it to estimate delivery dates, design system architecture, or replace product discovery when the intended outcome and actor are still unknown.
