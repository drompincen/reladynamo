---
name: prd-development
description: Create or revise a product requirements document when a team needs agreement on the problem, outcomes, scope, exclusions, constraints, and rollout.
user-invocable: true
---

# PRD Development

Produce a decision-ready product requirements document that settles what will be built, why it matters, how success will be judged, and what is deliberately excluded. Use it before design or delivery when product boundaries still need explicit agreement.

## Responsibilities

1. **Collect the evidence.** Read the request, research, customer feedback, business context, prior decisions, technical constraints, and delivery commitments provided by the user. Separate known facts from assumptions.
2. **Name the decision owner and readers.** Identify who approves the PRD and which product, design, engineering, data, legal, support, or go-to-market partners must be able to act from it. Mark missing owners as open questions.
3. **Write the problem before the solution.** Describe the current user situation, the obstacle, its consequence, and the evidence that it matters. Do not disguise a proposed feature as the problem.
4. **Define target users.** Select the users whose needs determine the first release. State their context, relevant behavior, and need. Distinguish primary users from affected or secondary users.
5. **Set measurable outcomes.** Choose a small set of success metrics with baselines, targets, measurement windows, and data sources. Add guardrail metrics for harms the team must not introduce.
6. **Set the product boundary.** List capabilities included in the release at an outcome level. For each item, add acceptance evidence that a reviewer can observe or measure.
7. **Record explicit non-goals.** Name plausible adjacent work that will not be delivered. Give a short reason when the exclusion prevents predictable misunderstanding.
8. **Capture constraints and dependencies.** Record fixed dates, policy obligations, platform limits, budget or staffing bounds, accessibility needs, data rules, and external teams or systems on the critical path.
9. **Resolve contradictions.** Check that the scope serves the stated users and outcomes, metrics can be measured, constraints permit the scope, and non-goals do not negate required capabilities. Surface conflicts instead of silently choosing.
10. **Turn uncertainty into decisions.** List only questions that can change scope, design, measurement, timing, or risk. Assign an owner and decision date to each one. State the default assumption that applies until resolved.
11. **Plan release and learning.** Define rollout stages, eligibility, instrumentation, validation at each stage, stop conditions, support readiness, and the path to full availability or rollback.
12. **Finish with a decision summary.** State the proposed release boundary, unresolved blockers, and approvals needed. Do not label the PRD final while a release-defining question remains unanswered.

## Working Rules

- Preserve uncertainty explicitly. Use `Known`, `Assumption`, or `Unknown` where confidence affects a decision.
- Prefer observable user behavior to internal activity. Write “80% of invited admins complete setup” rather than “launch onboarding improvements.”
- Keep requirements solution-neutral unless a constraint fixes the implementation. State the behavior the product must enable, not the screen or component to build.
- Use `TBD — owner, due date` only when the value cannot yet be established. Never leave an unowned `TBD`.
- Treat dates, targets, legal claims, and customer counts as unverified unless the supplied evidence supports them.
- Ask focused questions when missing information changes the product boundary. Proceed with labelled assumptions when the user prefers a draft.
- Maintain one source of truth. When revising a PRD, replace superseded decisions and note the change in the decision log rather than leaving conflicting text.

## Output Format

Produce the PRD with this copyable structure. Remove instructional brackets in the completed document.

```markdown
---
title: [Product or initiative]
status: draft | in-review | approved
owner: [Decision owner]
last_updated: [YYYY-MM-DD]
target_release: [Date, quarter, or “not committed”]
---

# PRD: [Product or initiative]

## Decision Summary

**Proposal:** [One sentence describing the release boundary and intended outcome.]

**Why now:** [Evidence-backed reason this deserves attention now.]

**Approval needed:** [Named decision, approver, and date.]

## Problem

[Describe the current situation, user obstacle, and consequence. Keep the proposed
solution out of this section.]

### Evidence

| Evidence | Source | Date | Confidence | What it establishes |
|---|---|---|---|---|
| [Example: 31% abandon setup at permissions step] | [Analytics report] | [YYYY-MM-DD] | High | [The step is a material barrier] |

### Assumptions

| Assumption | Why it matters | How to test | Owner | Test by |
|---|---|---|---|---|
| [Users abandon because permission language is unclear] | [Changes solution choice] | [Five moderated sessions] | [Name] | [YYYY-MM-DD] |

## Target Users

### Primary user

- **Who:** [Specific role or segment]
- **Context:** [When and where the problem occurs]
- **Need:** [Job or outcome they need]
- **Current workaround:** [What they do today]

### Secondary or affected users

| User | How affected | Priority in this release |
|---|---|---|
| [Support agent] | [Receives setup questions] | [Consulted, not optimized for] |

## Outcomes and Success Metrics

| Outcome | Metric | Baseline | Target | Window | Data source | Owner |
|---|---|---:|---:|---|---|---|
| [Admins finish setup] | [Setup completion rate] | [54%] | [75%] | [Within 7 days of invite] | [Event funnel] | [Name] |

### Guardrails

| Risk to avoid | Metric | Current level | Limit | Response if breached |
|---|---|---:|---:|---|
| [More support burden] | [Tickets per 100 setups] | [8] | [No more than 10] | [Pause expansion and review tickets] |

## Scope

### In scope

| ID | Required capability | User value | Acceptance evidence | Priority |
|---|---|---|---|---|
| S1 | [Admin can understand each requested permission before granting it] | [Makes a confident choice] | [Usability test and event criteria] | Must |

### Non-goals

| Excluded item | Reason for exclusion | Revisit trigger |
|---|---|---|
| [Redesign the full admin console] | [Not required to test setup completion] | [Setup target met but later console abandonment persists] |

## User Journey

1. **Entry:** [How the target user reaches the experience.]
2. **Core action:** [What the user must be able to accomplish.]
3. **Success state:** [What the user sees and what the system records.]
4. **Failure and recovery:** [Expected errors, escape paths, and support route.]

## Constraints

| Constraint | Fixed or flexible | Consequence | Owner or source |
|---|---|---|---|
| [Consent record retained for 7 years] | Fixed | [Requires auditable storage] | [Policy link or legal owner] |

## Dependencies

| Dependency | Needed outcome | Owner | Required by | Status | Fallback |
|---|---|---|---|---|---|
| [Identity platform] | [Expose permission metadata] | [Team/name] | [YYYY-MM-DD] | [Unconfirmed] | [Use existing labels and reduce pilot scope] |

## Risks and Mitigations

| Risk | Likelihood | Impact | Early signal | Mitigation | Owner |
|---|---|---|---|---|---|
| [Users approve without reading] | Medium | High | [Very short dwell time] | [Test progressive disclosure] | [Name] |

## Open Questions

| Question | Decision affected | Default assumption | Owner | Decide by | Status |
|---|---|---|---|---|---|
| [Can existing audit storage meet retention policy?] | [Architecture and timing] | [No; pilot stores records separately] | [Name] | [YYYY-MM-DD] | Open |

## Rollout

| Stage | Eligible users | Entry criteria | Validation | Stop or rollback condition | Exit criteria |
|---|---|---|---|---|---|
| Internal | [Employees] | [Events verified in test] | [Task completion and error review] | [Critical data or permission defect] | [No critical defects for 5 business days] |
| Pilot | [50 invited customers] | [Support briefed; consent approved] | [Success and guardrail metrics] | [Guardrail exceeds limit] | [Target trend sustained for 2 weeks] |
| General | [All eligible users] | [Pilot exit criteria met] | [Weekly dashboard] | [Named incident threshold] | [Post-launch review complete] |

### Operational Readiness

- **Instrumentation:** [Events, properties, dashboard owner, validation date]
- **Support:** [Documentation, training, escalation owner]
- **Communications:** [Audience, message, channel, timing]
- **Rollback:** [Who decides, what is reversible, estimated time]

## Decision Log

| Date | Decision | Rationale | Decider | Supersedes |
|---|---|---|---|---|
| [YYYY-MM-DD] | [Pilot limited to invited admins] | [Contains policy and support risk] | [Name] | [None] |
```

## Review Sequence

Review the draft in this order:

1. Confirm the problem and evidence with the product owner and relevant user-facing partners.
2. Confirm metrics, event definitions, baselines, and reporting ownership with data partners.
3. Confirm scope, acceptance evidence, constraints, dependencies, and fallback paths with design and engineering.
4. Confirm legal, privacy, security, accessibility, support, and go-to-market obligations where applicable.
5. Resolve release-defining open questions or mark the document `in-review` with named blockers.
6. Obtain approval from the decision owner and record it in the decision log.

## Quality Bar

A good PRD lets two independent delivery teams describe the same release boundary, explain the user outcome, and identify the evidence required for launch. It distinguishes facts from assumptions and makes exclusions as visible as included work.

Before delivery, verify all of the following:

- The problem can be stated without naming the proposed feature.
- Each primary user is specific enough to recruit for research or identify in product data.
- Every success metric has a baseline or a plan to establish one, a target, a time window, a source, and an owner.
- Each in-scope capability traces to a target-user need and a success outcome.
- Acceptance evidence describes an observable result, not “works as expected.”
- Non-goals include the adjacent requests most likely to be assumed in scope.
- Each fixed constraint names its consequence for the product or delivery plan.
- Each critical dependency has an owner, date, status, and fallback.
- Each open question names the decision affected, owner, due date, and temporary default.
- Rollout stages include entry, validation, stop, and exit criteria.
- The decision summary agrees with the detailed scope and rollout.

Avoid these failure modes:

- **Feature-shaped problem:** “We need an AI assistant” specifies a solution. Replace it with the affected user, blocked task, consequence, and evidence.
- **Unbounded audience:** “All customers” conceals competing needs. Select the segment that controls the first release and state who is secondary.
- **Activity metric:** “Ship by Q3” or “increase engagement” does not prove user value. Define a behavior, baseline, target, and measurement window.
- **Scope by implication:** A list of mockups does not settle product behavior. Write required capabilities and observable acceptance evidence.
- **Invisible exclusions:** Omitting adjacent work invites different assumptions. Name plausible exclusions such as migration, mobile support, localization, or admin controls when relevant.
- **Decorative open questions:** Questions without owners or dates never become decisions. Assign them or resolve them before approval.
- **Launch-only rollout:** “Release to everyone” ignores learning and recovery. Add staged eligibility, guardrails, stop conditions, and rollback ownership.
- **False certainty:** Invented baselines, customer claims, or technical limits weaken the document. Label unknowns and specify how they will be resolved.

## When Not to Use This

Do not use this skill for a one-line bug fix with settled behavior, a technical design document that explains implementation, a project plan that assigns delivery tasks, or exploratory research before the team is ready to define a product boundary. Use a brief decision note instead when only one narrow trade-off needs approval.
