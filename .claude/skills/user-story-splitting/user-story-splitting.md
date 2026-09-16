---
name: user-story-splitting
description: Split an oversized user story into small, valuable end-to-end slices when it cannot be delivered, reviewed, or learned from safely as one unit.
user-invocable: true
---

# User Story Splitting

Turn a story that is too large into independently useful slices that cross the necessary layers from user action to observable result. Use this when one story hides several behaviours, rules, cases, or technical uncertainties.

## Responsibilities

1. **Restate the outcome.** Name the user or actor, the action they need, and the result they value. Preserve this outcome while splitting.
2. **Set the size constraint.** Record why the story is too large: delivery window, review risk, unknown effort, or too many acceptance cases. Ask for the team's target size if it is not given.
3. **Map the behaviour.** List the shortest sequence from the user's starting point to the visible result. Include decisions, business rules, input varieties, failure cases, and important quality expectations.
4. **Find the thinnest useful path.** Select the smallest real scenario that one user can complete end to end. Prefer a narrow working behaviour over a broad partial layer.
5. **Apply one splitting pattern at a time.** Try workflow steps, business-rule variations, happy path versus edge cases, data variation, effort spikes, and deferred performance. Use the pattern that creates the clearest independent outcomes.
6. **Write each slice as behaviour.** State who can do what and why it matters. Give each slice acceptance examples that can be demonstrated without relying on unfinished slices.
7. **Order by learning and value.** Put a usable core path first. Move uncertain or high-risk work early enough to change the plan. Defer rare cases and optimisations only when doing so is safe.
8. **Check independence.** Confirm each slice can be built, tested, reviewed, and released on its own. Document any unavoidable dependency instead of hiding it.
9. **Check coverage.** Trace every original workflow step, rule, edge case, data type, uncertainty, and performance need to a slice or an explicit deferral.
10. **Present the split.** Produce the story-splitting record below, including rationale, sequence, acceptance examples, dependencies, and deferred scope.

## Guardrail: Slice Vertically

A slice must connect a user trigger to a meaningful result through every layer needed for that result.

Do not split into technical components such as:

- build the database table;
- add the service endpoint;
- create the screen;
- connect the screen to the endpoint.

Those tasks may sit inside one slice, but none is independently valuable to a user.

Prefer:

- a customer can submit one supported request and see its confirmed status.

That slice may use a simple screen, one endpoint, and minimal storage. Later slices expand the behaviour.

## Splitting Patterns

### 1. Workflow Steps

Split a long journey at points where an intermediate result has value on its own.

Use this pattern when the story contains a sequence such as create, review, approve, publish, and notify.

Procedure:

1. Write the workflow as actor-visible steps.
2. Mark which intermediate states are useful, safe, and understandable.
3. Combine steps that have no meaningful result in isolation.
4. Create slices that finish at the remaining useful states.

Example:

Large story: A hiring manager creates a vacancy, gets approval, publishes it, and notifies recruiters.

- Slice 1: A hiring manager can save a vacancy draft and return to it.
- Slice 2: A manager can submit a draft and an approver can accept it.
- Slice 3: A manager can publish an approved vacancy.
- Slice 4: Recruiters are notified when a vacancy is published.

Avoid calling a screen or service-layer operation a workflow step. The step must describe progress that an actor recognises.

### 2. Business-Rule Variations

Split by rules that change eligibility, calculation, routing, permission, or outcome.

Use this pattern when one story contains many “if,” “unless,” threshold, role, region, or policy clauses.

Procedure:

1. List each rule and the condition that activates it.
2. Choose the simplest common rule as the first complete slice.
3. Add one materially different rule or coherent rule set per later slice.
4. State which rule has precedence when conditions overlap.

Example:

Large story: Approve purchase requests according to amount and department.

- Slice 1: Department leads can approve requests up to $500.
- Slice 2: Requests above $500 route to finance approval.
- Slice 3: Capital purchases route to the asset owner regardless of amount.

Do not split arbitrary ranges that behave identically. A boundary earns a slice when it changes behaviour or reduces delivery risk.

### 3. Happy Path and Edge Cases

Deliver the normal successful route first, then add exceptions and recovery behaviour.

Use this pattern when rare failures, invalid states, or recovery options dominate the estimate.

Procedure:

1. Define the happy path with explicit valid preconditions.
2. Confirm it is safe to release without each edge case.
3. Group edge cases by the behaviour required, not by a generic “errors” label.
4. Add prevention, recovery, or explanation in separate valuable slices.

Example:

- Slice 1: A signed-in customer with a valid card can pay an available invoice.
- Slice 2: A customer sees a clear outcome and can retry when the card is declined.
- Slice 3: A customer cannot pay an invoice that was already settled elsewhere.
- Slice 4: A timed-out payment is reconciled before another charge is attempted.

Never defer an edge case that could cause material loss, unsafe behaviour, corruption, or a compliance breach. Treat it as part of the first releasable slice.

### 4. Data Variation

Split by input or output forms that require genuinely different behaviour.

Use this pattern for file types, channels, record shapes, locales, sources, or destinations.

Procedure:

1. Inventory the meaningful data forms.
2. Select one common, representative form for the first end-to-end slice.
3. Add forms with distinct validation, transformation, or presentation separately.
4. Combine forms that travel through the same behaviour.

Example:

- Slice 1: Import customers from a UTF-8 CSV with required columns.
- Slice 2: Report row-level validation errors and import the valid CSV rows.
- Slice 3: Import customers from the partner's spreadsheet layout.
- Slice 4: Import customers from the partner API.

Do not use “all text fields first, numeric fields later.” Split by a usable data scenario, not by primitive data type.

### 5. Effort Spikes

Separate a time-boxed investigation when uncertainty prevents a credible delivery slice.

Use this pattern only when the team cannot yet decide how to implement, estimate, or safely release a behaviour.

Procedure:

1. Write the decision the spike must enable.
2. State the unknowns, evidence to collect, and time box.
3. Define the output as a decision, measured result, or discarded prototype.
4. Follow the spike with revised delivery slices; do not treat the spike as user value.

Example:

Spike: Within one day, test whether the identity provider supports account linking without duplicate identities. Produce a recommendation, constraints, and revised acceptance examples.

Delivery slice: A returning customer can link one existing account after signing in through the provider.

Avoid spikes named “research integration” or “investigate options.” They lack a decision and a stopping condition.

### 6. Defer Performance

Implement correct behaviour for a stated initial load, then improve capacity or speed in measured increments.

Use this pattern when correctness can safely precede optimisation and the initial limit still supports real use.

Procedure:

1. Define the minimum acceptable load and response target for the first slice.
2. Verify that the limited version remains usable and operationally safe.
3. Record the expected growth trigger or observed bottleneck.
4. Create later slices with measurable performance outcomes.

Example:

- Slice 1: A support lead can export up to 1,000 cases; 95% of exports finish within 30 seconds.
- Slice 2: Exports up to 100,000 cases run in the background and notify the requester when ready.
- Slice 3: Ten concurrent large exports complete without slowing case updates beyond the agreed limit.

Do not defer security, integrity, accessibility, or a response time required for the first users to complete the task.

## Output Format

Produce this artifact and replace every bracketed prompt:

```markdown
# Story split: [short outcome]

## Original story
As a [actor], I want [capability], so that [valuable result].

**Why it is too large:** [specific evidence or constraint]
**Target slice size:** [team limit or stated assumption]

## Behaviour map
| Area | Observed behaviours, rules, or unknowns |
|---|---|
| Workflow | [actor-visible sequence] |
| Business rules | [conditions and changed outcomes] |
| Edge cases | [failure, prevention, and recovery cases] |
| Data variations | [meaningfully different forms] |
| Uncertainty | [decision-blocking unknowns] |
| Performance | [initial need, later target, trigger] |

## Proposed slices
| Order | Slice | Pattern | User-visible value | Acceptance examples | Dependency |
|---:|---|---|---|---|---|
| 1 | As a [actor], I can [narrow capability], so that [result]. | [pattern] | [demonstrable value] | Given [context], when [action], then [result]. | None |
| 2 | As a [actor], I can [next capability], so that [result]. | [pattern] | [demonstrable value] | Given [context], when [action], then [result]. | [None or slice] |

## Spike, if required
**Decision:** [decision this investigation enables]
**Time box:** [duration]
**Evidence:** [test, measurement, or prototype]
**Exit:** [decision and changes to delivery slices]

## Coverage and deferrals
| Original concern | Covered by | Reason for order or deferral |
|---|---|---|
| [step, rule, case, data form, or quality need] | [slice number or deferred] | [value, risk, learning, or trigger] |

## Release notes
- First independently releasable slice: [number and reason]
- Safety constraints included from the start: [constraints]
- Explicit assumptions: [assumptions to confirm]
```

## Quality Bar

A good split meets all of these checks:

- Every delivery slice starts with an actor trigger and ends with an observable, useful result.
- The first slice is narrower than the original story and can be demonstrated with realistic data.
- Acceptance examples define boundaries precisely enough to test.
- The order reflects value, risk, or learning rather than architectural convenience.
- Dependencies are few, necessary, and explicit.
- The coverage table accounts for all original scope, including deliberate deferrals.
- A spike answers a named decision within a time box and is not presented as delivered user value.
- Deferred performance has a safe initial envelope and a measurable later target.

Avoid these failure modes:

- Do not produce horizontal layers such as “database,” “API,” and “UI” as separate stories.
- Do not create slices that merely rename tasks and still require all other slices before anyone benefits.
- Do not label a vague phase “MVP” without stating the exact scenario, rules, and limits it supports.
- Do not defer rare cases that threaten money, privacy, safety, compliance, or data integrity.
- Do not invent business priorities. Mark assumptions and ask the product owner to confirm them.
- Do not split every rule or input mechanically; combine variations when their behaviour and risk are the same.
- Do not make the first slice a throwaway implementation unless the user explicitly accepts that cost.
- Do not hide unresolved uncertainty inside an estimate; use a decision-focused spike.

## When Not to Use This

Do not use story splitting when the work is already small enough to complete and verify within the team's normal delivery window.

Do not use it to break a purely technical maintenance task into fictional user stories. Decompose that work as technical tasks with testable outcomes instead.

Do not use it when the request is to prioritise a backlog, discover the user problem, or design a release roadmap. Resolve those questions first; then split the selected outcome.
