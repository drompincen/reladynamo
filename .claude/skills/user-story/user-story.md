---
name: user-story
description: Write implementation-ready user stories with role-goal-benefit context, testable Gherkin acceptance criteria, and an INVEST quality check.
user-invocable: true
---

# User Story

Turn a product need into a story a developer can implement and a tester can verify without a clarification meeting. Use this skill for a bounded user-facing behavior, workflow change, or product increment.

## Responsibilities

1. **Establish the outcome.** Identify the user or actor, the capability they need, and the practical benefit they expect.
2. **Inspect available context.** Read the request, relevant product rules, designs, interfaces, and existing behavior. Do not invent policy or system behavior when evidence is available.
3. **Resolve consequential gaps.** Ask only about missing facts that would change scope, behavior, or acceptance. Record minor assumptions explicitly instead of hiding them.
4. **Choose one coherent slice.** Keep one user outcome per story. Split unrelated actors, workflows, or independently releasable outcomes into separate stories.
5. **Write the story statement.** Use role-goal-benefit form: “As a …, I want …, so that …”. Name a real actor and an observable goal.
6. **Define boundaries.** State what is included and excluded when the title and story statement do not make the boundary obvious.
7. **Extract business rules.** Convert limits, permissions, calculations, state changes, and error handling into concise rules before writing scenarios.
8. **Write acceptance criteria.** Express each distinct behavior as a Gherkin scenario with Given, When, and Then. Add And only when it clarifies another condition or result.
9. **Cover the meaningful paths.** Include the primary success path, relevant alternate paths, boundary conditions, and likely failures. Do not multiply scenarios for combinations that behave identically.
10. **Make results observable.** State what the user, API consumer, or tester can see or query. Avoid criteria about internal implementation unless the implementation itself is a constraint.
11. **Check INVEST.** Assess whether the story is independent, negotiable, valuable, estimable, small, and testable. Revise or split it when a quality fails.
12. **Run a handoff check.** Confirm that a developer can identify the behavior to build and a tester can derive checks without guessing missing rules.

## Working Method

Start from the behavior, not the interface control. “Choose a delivery date” describes a capability; “add a date picker” prescribes one possible design.

Use the role to distinguish needs that genuinely differ. Prefer “warehouse dispatcher” over “user” when permissions, terminology, or goals depend on that role.

Make the benefit explain why the capability matters. If removing the benefit changes nothing, rewrite it. A circular benefit such as “so that I can use the feature” adds no product context.

Split a story when any of these are true:

- Different roles receive different value.
- One path can ship and be useful without another.
- Separate business rules create distinct test surfaces.
- The acceptance criteria cannot be reviewed in one short sitting.
- The team cannot estimate it without first decomposing it.

Keep closely related validation and error behavior with the successful behavior. A story that permits submission should normally say what happens when required input is invalid.

## Output Format

Produce the following artifact. Omit a section only when it truly adds no information; never omit acceptance criteria or the INVEST check.

```markdown
# [Short outcome-oriented title]

## Story

As a [specific role or actor],
I want [capability or outcome],
so that [concrete benefit].

## Context

[Two or three sentences describing the current situation, trigger, or relevant product constraint.]

## Scope

### Included

- [Behavior included in this increment]
- [Relevant state, channel, or user path]

### Excluded

- [Adjacent behavior intentionally deferred]
- [Implementation or workflow outside this story]

## Business Rules

1. [Unambiguous rule, limit, permission, or calculation]
2. [Unambiguous rule, limit, permission, or calculation]

## Acceptance Criteria

### Scenario: [Primary successful outcome]

Given [observable starting state]
And [necessary precondition]
When [actor performs one event]
Then [observable result]
And [additional observable result]

### Scenario: [Relevant alternate or boundary outcome]

Given [observable starting state]
When [actor performs one event]
Then [observable result]

### Scenario: [Relevant failure outcome]

Given [observable starting state]
When [actor attempts one event]
Then [clear rejection or error result]
And [state that remains unchanged]

## Assumptions and Open Questions

- Assumption: [Fact used to make the story actionable]
- Open question: [Decision still needed, owner if known, and effect on the story]

## INVEST Check

| Quality | Pass? | Evidence or action |
|---|---|---|
| Independent | Yes/No | [Dependency removed, declared, or reason to split] |
| Negotiable | Yes/No | [Outcome preserved without prescribing unnecessary design] |
| Valuable | Yes/No | [Specific user or business benefit] |
| Estimable | Yes/No | [Rules and boundaries clear enough to estimate] |
| Small | Yes/No | [One coherent increment, or proposed split] |
| Testable | Yes/No | [Observable criteria with deterministic results] |

## Dependencies

- [Required system, decision, story, data, or “None”]
```

## Worked Example

Use examples to expose exact behavior, not to decorate the story.

```markdown
# Reschedule an upcoming delivery

## Story

As a customer expecting a delivery,
I want to choose a later available delivery date,
so that I can receive the order when I will be present.

## Context

Customers can view an upcoming delivery but currently must contact support to change its date. This story covers self-service rescheduling before dispatch.

## Scope

### Included

- Moving an undispatched order to a later date offered for the address
- Showing the confirmed date after the change

### Excluded

- Changing the delivery address
- Rescheduling an order after dispatch

## Business Rules

1. Only dates returned as available for the delivery address can be selected.
2. A dispatched order cannot be rescheduled.
3. A successful change replaces the previous delivery date.

## Acceptance Criteria

### Scenario: Move an undispatched order to an available date

Given the customer has an undispatched order due on 14 May
And 16 May is available for the delivery address
When the customer reschedules the order to 16 May
Then the order's delivery date is 16 May
And the customer sees confirmation of the new date

### Scenario: Reject a date that is no longer available

Given the customer is viewing 16 May as an available date
And 16 May becomes unavailable before confirmation
When the customer confirms 16 May
Then the customer sees that the date is no longer available
And the order keeps its previous delivery date

### Scenario: Prevent rescheduling after dispatch

Given the customer's order has been dispatched
When the customer attempts to reschedule it
Then rescheduling is unavailable
And the order keeps its current delivery date

## Assumptions and Open Questions

- Assumption: Availability is supplied by the existing delivery scheduling service.
- Open question: None.

## INVEST Check

| Quality | Pass? | Evidence or action |
|---|---|---|
| Independent | Yes | Uses existing availability; no address change is required. |
| Negotiable | Yes | Specifies behavior without choosing a control or page layout. |
| Valuable | Yes | Avoids missed deliveries and a support contact. |
| Estimable | Yes | Eligibility, success, and rejection behavior are bounded. |
| Small | Yes | Covers one pre-dispatch date-change workflow. |
| Testable | Yes | Each scenario has a visible result and retained or changed state. |

## Dependencies

- Existing delivery availability service
```

## Quality Bar

A good story passes all of these checks:

- The title names an outcome rather than a project, component, or vague feature.
- The role is specific enough to explain relevant needs or permissions.
- The goal states user-visible behavior without dictating an unnecessary implementation.
- The benefit names a consequence that matters to the actor or business.
- Each scenario describes one event and its observable consequences.
- Preconditions are sufficient to reproduce the scenario but do not contain the action under test.
- Expected results are precise about changed state, unchanged state, messages, permissions, or calculated values.
- Business rules and scenarios agree; no rule is left untested when it affects acceptance.
- Scope exclusions prevent plausible misunderstandings about adjacent work.
- Every “No” in the INVEST check produces a revision, split, dependency, or explicit follow-up.

Avoid these failure modes:

- Do not use a generic actor such as “user” when the behavior belongs to a customer, administrator, reviewer, or system client.
- Do not write a solution disguised as a goal, such as “I want a blue modal with two buttons,” unless those interface details are mandated constraints.
- Do not use a benefit that repeats the goal, such as “so that I can reschedule my delivery.”
- Do not write criteria as subjective claims such as “Then the page is easy to use” or “Then the response is fast.” Supply a measurable threshold or observable outcome.
- Do not combine several actions in one When step. Split “When I edit, save, approve, and publish” into scenarios that isolate behavior.
- Do not hide decisions behind words such as “valid,” “appropriate,” “soon,” or “correct.” Define the applicable rule, value, or time limit.
- Do not describe only the happy path when invalid input, authorization, concurrency, or state transitions can change the outcome.
- Do not enumerate technical tasks as acceptance criteria. Database migrations and component changes belong in implementation notes or tasks unless externally observable.
- Do not copy the story statement into a scenario and call it acceptance. Add concrete starting states, actions, and results.
- Do not declare a large story “small” without evidence. Propose slices when it spans multiple independently useful outcomes.

Before delivering, read the artifact once as a developer and once as a tester. As the developer, identify the states, rules, and boundaries to implement. As the tester, identify the setup, action, and assertion for every scenario. Revise any sentence that requires private context or an unstated decision.

## When Not to Use This

Do not use this skill for a technical task with no direct user outcome, such as upgrading a dependency or rotating credentials; use a technical task with explicit completion checks. Do not use it for broad discovery, a product vision, a roadmap item, or an initiative that still needs decomposition. Do not force incident reports, research questions, or open-ended design exploration into story form.
