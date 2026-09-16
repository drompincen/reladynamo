---
name: roadmap-planning
description: Build outcome-based now/next/later roadmaps when priorities may shift and feature-date promises would mislead.
user-invocable: true
---

# Roadmap Planning

Build a roadmap around problems, outcomes, and evidence rather than a fixed feature queue. Use it when a team needs direction across changing priorities without pretending that distant scope or dates are certain.

## Responsibilities

1. **Establish the decision context.** Record the planning period, product scope, business goals, target users, known commitments, capacity constraints, and decision owner. Ask only for missing facts that would materially change the roadmap.

2. **Separate commitments from options.** Identify contractual, regulatory, safety, or announced obligations. Label each one with its source and deadline. Do not disguise an obligation as a discretionary outcome.

3. **Gather evidence.** Summarise user research, product data, commercial signals, operational pain, and technical constraints. Mark claims as observed, inferred, or unknown. Do not invent metrics or user needs.

4. **Define themes.** Group related problems into three to five durable themes. Name each theme as a user or business concern, such as “Faster first value,” not as a project, feature, or department.

5. **Write outcomes.** For each theme, state the change to create, the population affected, the baseline if known, the target signal, and the measurement window. Use directional evidence when a numeric target would be fabricated.

6. **Generate candidate bets.** List possible ways to pursue each outcome. Keep bets separate from outcomes so a weak idea can be replaced without rewriting the roadmap.

7. **Map dependencies.** Distinguish hard dependencies from convenient ordering. Name the prerequisite, owner, and consequence of delay. Include enabling work only when it unlocks an outcome or reduces a named risk.

8. **Assess uncertainty and risk.** Rate evidence confidence, delivery uncertainty, reversibility, and downside. Identify the riskiest assumption behind each bet and the cheapest way to test it.

9. **Sequence the work.** Place items into Now, Next, or Later using urgency, dependency, learning value, risk, and capacity. Put work in Now only when the team can actively pursue it. Use Next for the best current options and Later for plausible directions, not overflow.

10. **Set horizon rules.** Give each horizon an explicit meaning. For example: Now is active and capacity-backed; Next is likely but awaiting evidence or capacity; Later is exploratory and may change substantially.

11. **Attach review triggers.** Define when the roadmap will be reconsidered: a regular review date, a target reached, an experiment failed, a dependency moved, or a material strategy change.

12. **Expose trade-offs.** State what is not being pursued and why. Surface conflicts between outcomes rather than implying that every request fits.

13. **Publish the artifact.** Produce the roadmap, evidence register, dependency notes, decision log, and communication summary using the format below. Preserve uncertainty labels in executive summaries.

## Decision Rules

Apply these rules when assigning a horizon:

- Put an item in **Now** only if it supports a current outcome, has an accountable owner, fits available capacity, and has a clear first learning or delivery checkpoint.
- Put an item in **Next** when it is a credible priority but depends on evidence, a prerequisite, or released capacity.
- Put an item in **Later** when the problem matters but the solution, timing, or strategic fit remains uncertain.
- Remove an item when its expected contribution is weak, evidence contradicts it, or a stronger option displaces it.
- Split a large bet when its first useful learning can occur before full delivery.
- Sequence discovery before irreversible delivery when a critical assumption is untested.
- Sequence enabling work before dependent bets, but show the outcome it enables.
- Prefer reversible tests when two options have similar expected value and different uncertainty.
- Do not infer priority from request volume alone; consider user impact, strategy, evidence quality, and opportunity cost.
- Do not assign feature-level dates beyond the range supported by evidence and capacity.

## Outcome Test

Before accepting an outcome, check that it describes a changed condition rather than shipped output.

Weak:

> Launch saved searches in Q3.

Stronger:

> Increase the share of returning buyers who find a relevant listing within five minutes, measured four weeks after release.

Weak:

> Improve onboarding.

Stronger:

> Reduce the time for a new workspace administrator to invite a teammate and complete the first shared workflow; baseline to be measured during discovery.

An outcome must remain meaningful if the proposed feature changes.

## Roadmap Output Format

Produce one markdown artifact with this structure:

```markdown
# Roadmap: [Product or portfolio]

**Planning window:** [Start date–end date or rolling period]
**Scope:** [Products, users, or teams included]
**Decision owner:** [Role or name]
**Last reviewed:** [YYYY-MM-DD]
**Next review:** [YYYY-MM-DD or trigger]

## Direction

**Goal:** [Business or mission goal this roadmap serves]
**Target users:** [Specific users or customers]
**Constraints:** [Capacity, regulation, contract, architecture, budget]
**Horizon policy:**
- Now: [Meaning, such as active and capacity-backed]
- Next: [Meaning, such as likely pending evidence or capacity]
- Later: [Meaning, such as exploratory direction]

## Themes and outcomes

| Theme | Problem evidence | Intended outcome | Success signal | Baseline | Target or direction | Window |
|---|---|---|---|---|---|---|
| Faster first value | 38% of new admins stop before inviting a teammate | More new admins complete a shared workflow | Activation rate | 22% | Increase; target after baseline validation | 30 days |
| [Concern, not project] | [Observed evidence] | [Changed user or business condition] | [Metric or observable signal] | [Value or unknown] | [Value or direction] | [Measurement period] |

## Now

| Outcome | Current bet | Why now | First checkpoint | Owner | Confidence | Dependencies | Change trigger |
|---|---|---|---|---|---|---|---|
| [Outcome] | [Approach, experiment, or enabling work] | [Urgency, learning, or dependency] | [Evidence or deliverable] | [Owner] | [High/medium/low + reason] | [Hard prerequisites] | [Condition that causes review] |

## Next

| Outcome | Candidate bet | Entry condition | Key assumption | Risk-reduction step | Dependencies | Confidence |
|---|---|---|---|---|---|---|
| [Outcome] | [Current best option] | [Evidence, capacity, or prerequisite needed] | [Unproven belief] | [Cheapest useful test] | [Prerequisites] | [High/medium/low + reason] |

## Later

| Theme or outcome | Opportunity | Why retain it | Major unknowns | Earliest review trigger |
|---|---|---|---|---|
| [Theme/outcome] | [Possible direction, not promise] | [Strategic reason] | [Unknown users, value, solution, or timing] | [Signal that merits reconsideration] |

## Fixed commitments

| Commitment | Source | Deadline | Minimum compliant result | Roadmap impact | Owner |
|---|---|---|---|---|---|
| [Contract, regulation, safety, or public commitment] | [Evidence] | [Date] | [Smallest valid scope] | [Capacity or sequence effect] | [Owner] |

## Dependencies and sequence

| Prerequisite | Unlocks | Type | Owner | Needed by | If delayed |
|---|---|---|---|---|---|
| [Dependency] | [Outcome or bet] | [Hard/soft] | [Owner] | [Checkpoint] | [Resequence, reduce scope, or stop] |

## Evidence and assumptions

| Claim | Status | Source | Confidence | Validation action | Review date |
|---|---|---|---|---|---|
| [Claim] | [Observed/inferred/unknown] | [Research, data, or none] | [High/medium/low] | [Test or measurement] | [Date] |

## Trade-offs

- Not pursuing: [Request or opportunity] — [Reason and reconsideration trigger].
- Reduced scope: [Area] — [Capacity or risk trade-off].
- Outcome tension: [Outcome A] versus [Outcome B] — [How the decision was made].

## Review triggers

- Scheduled: [Cadence and owner].
- Evidence: [Metric threshold or experiment result].
- Dependency: [External event or prerequisite change].
- Strategy: [Decision that requires re-planning].

## Decision log

| Date | Decision | Evidence considered | Alternatives rejected | Revisit when | Owner |
|---|---|---|---|---|---|
| [YYYY-MM-DD] | [Decision] | [Evidence] | [Options] | [Trigger] | [Owner] |

## Communication summary

[Two to four sentences explaining the outcomes being pursued now, what may come next,
what remains exploratory, and which conditions could change the sequence.]
```

## Communicating Uncertainty

Use language that matches the evidence:

- **Committed:** “We will meet the accessibility deadline by 30 September; the minimum scope is defined.”
- **Capacity-backed:** “The team is working on reducing setup abandonment now; the current bet is guided setup.”
- **Conditional:** “Account delegation is next if the security review passes and activation remains the leading constraint.”
- **Exploratory:** “Marketplace integrations are a later opportunity; partner demand and operating cost remain unknown.”

Never convert Now, Next, and Later into hidden date buckets unless the organisation has explicitly defined them that way. If stakeholders require dates, provide ranges, assumptions, and confidence, and distinguish an external commitment from an internal forecast.

## Quality Bar

A good roadmap passes these checks:

1. Every Now item links to a stated outcome or fixed commitment.
2. Every outcome describes a measurable or observable change, not a delivery milestone.
3. Every horizon has a declared meaning and a review condition.
4. Current bets can change without destroying the roadmap’s strategic logic.
5. Hard dependencies identify an owner and the consequence of delay.
6. Unknown baselines and weak evidence are visible rather than filled with invented precision.
7. Capacity limits force explicit trade-offs; Now is not a catalogue of all desired work.
8. A reader can distinguish commitments, forecasts, options, and experiments.
9. The decision log explains why sequence changed between reviews.
10. The communication summary preserves the caveats shown in the detailed roadmap.

Avoid these failure modes:

- Do not present a feature list grouped into Now, Next, and Later with no outcomes.
- Do not attach exact distant dates to work whose scope, dependencies, or evidence remain unsettled.
- Do not use themes such as “Mobile app,” “Platform,” or “AI features”; name the problem or benefit instead.
- Do not treat Later as a promise, backlog dump, or parking place that only grows.
- Do not put every stakeholder request in Now to avoid a prioritisation decision.
- Do not hide mandatory work, maintenance, or enabling work; show why it consumes capacity.
- Do not claim a hard dependency when parallel work or a temporary workaround is possible.
- Do not use confidence labels without a reason tied to evidence, complexity, or dependency.
- Do not keep an item because it appeared on the previous roadmap; require current justification.
- Do not report shipped output as success when the intended user or business condition did not change.

## When Not to Use This

Do not use this skill for a sprint plan, release checklist, incident response, or a single project with fixed scope and a near-term contractual schedule. Use execution planning for those tasks. Do not use it to replace product strategy; if the target users, goals, and constraints are undecided, establish those first.
