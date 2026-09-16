---
name: prioritization-advisor
description: Select and apply RICE, ICE, value-effort, weighted scoring, or cost of delay when prioritizing product work without false precision.
user-invocable: true
---

# Prioritization Advisor

Choose a method that fits the decision, expose the assumptions behind the ranking, and produce a recommendation that survives discussion. Use this for comparing product opportunities, features, experiments, fixes, or initiatives when the team needs an explicit ordering.

## Responsibilities

1. **Define the decision.** State the items being compared, the decision owner, the time horizon, and the scarce resource being allocated. Do not score items that compete for different budgets or teams unless the decision truly spans them.
2. **Set eligibility gates.** Remove or separately label mandatory work, contractual commitments, security fixes, regulatory obligations, and hard dependencies. A formula must not make non-optional work appear optional.
3. **Check comparability.** Confirm that every item is at a similar level of scope. Split a year-long platform program or combine tiny tasks before comparing either with a six-week feature.
4. **Assess the evidence.** Record what is measured, estimated, assumed, or unknown. Ask for missing inputs only when they could change the decision; otherwise proceed with explicit assumptions.
5. **Choose the lightest suitable method.** Use the selection rules below. Explain the choice in one or two sentences and name the method's main limitation for this decision.
6. **Define scales before scoring.** Give each factor anchors with observable meanings. Keep scales short. Prefer ranges and confidence labels over invented decimals.
7. **Score consistently.** Use the same horizon, unit, population, and interpretation for every item. Preserve the raw inputs so another person can reproduce the result.
8. **Test sensitivity.** Vary uncertain inputs, weights, or estimates. Identify rankings that remain stable and rankings that reverse under plausible assumptions.
9. **Apply judgment openly.** Recommend an order, then list any deliberate override and its reason. Never alter inputs merely to force the desired order.
10. **Define the next action.** Name what to start, defer, validate, or re-estimate, plus the evidence that would trigger reconsideration.

## Choose the method

### Value versus effort

Use for a fast first pass when the item set is small, evidence is limited, and the goal is conversation or triage rather than a defensible numerical rank.

Plot or classify each item using explicitly anchored value and effort. Treat quadrant boundaries as discussion aids, not natural laws.

Good fit: a team sorting twelve candidate improvements during quarterly planning.

Wrong tool: items differ materially in urgency, confidence, reach, or strategic constraints. A two-axis view hides those differences.

### ICE

Use for rapid comparison of experiments or growth ideas when impact, confidence, and ease can be judged on common anchored scales.

Calculate:

`ICE = impact × confidence × ease`

Use integers such as 1–5 for impact and ease. Express confidence as a multiplier such as 0.5, 0.8, or 1.0, with evidence anchors. Define whether ease means low effort, short lead time, or low complexity; do not mix them.

Good fit: choosing which onboarding experiments to test next week.

Wrong tool: reach varies widely, delay has substantial economic consequences, or effort estimates are available in real units. ICE can reward easy but unimportant work.

### RICE

Use when reach varies meaningfully across options and the team can estimate a common population and period.

Calculate:

`RICE = reach × impact × confidence / effort`

Define reach as a count in one fixed period, such as affected active accounts per quarter. Anchor impact to a specific outcome. Use effort in one unit, commonly person-weeks or person-months, and include all material functions.

Good fit: comparing customer-facing features that affect different portions of the user base.

Wrong tool: platform, compliance, reliability, or strategic work whose value is not proportional to directly reached users. It is also weak when reach numbers are speculative.

### Weighted scoring

Use when the decision has several explicit objectives or constraints and stakeholders need to agree on their relative importance.

Calculate:

`weighted score = Σ(factor score × factor weight)`

Make weights total 100%. Give every factor a 1–5 rubric with distinct anchors. Avoid duplicate factors such as “customer value” and “user impact” unless they measure separate things.

Good fit: portfolio planning across retention, revenue, strategic alignment, risk reduction, and delivery cost.

Wrong tool: stakeholders cannot agree on objectives, factors overlap, or weights are being used to disguise a predetermined answer. Resolve the decision criteria first.

### Cost of delay

Use when timing changes value and the main question is sequence: what should happen first, and what is lost by waiting?

Estimate value lost per unit of delay in a common period. When duration matters, compare using:

`CD3 = cost of delay per period / estimated duration`

Include time-critical value, ongoing economic impact, risk reduction, or opportunity enablement only where evidence supports them. Show a range when the loss curve is uncertain.

Good fit: sequencing launches around a market window or choosing between fixes with accumulating losses.

Wrong tool: delay cost is essentially flat, estimates cannot share a monetary or proxy unit, or dependencies dictate the order. Do not convert every benefit into fictional revenue.

## Scale rules

- Define the outcome before the score: “reduces failed checkout attempts,” not “high customer value.”
- Tie low, middle, and high anchors to observable evidence. Example: impact 1 = no measured behavior change expected; 3 = likely movement in a secondary metric; 5 = likely material movement in the primary metric.
- Map confidence to evidence. Example: 0.5 = opinion or analogy; 0.8 = relevant qualitative evidence or directional data; 1.0 = direct experiment or repeated production evidence.
- Use one effort boundary. State whether estimates include discovery, design, engineering, rollout, migration, support, and coordination.
- Use coarse values when knowledge is coarse. Do not report `7.43` when the inputs are guesses on five-point scales.
- Mark unknowns as unknown. Do not silently replace missing information with a midpoint.

## Required output format

Produce this artifact and omit sections only when they genuinely do not apply:

```markdown
# Prioritization: [decision]

## Decision frame
- Decision owner: [name or role]
- Decision date: [date]
- Horizon: [for example, next quarter]
- Scarce resource: [team capacity, budget, launch window]
- Items compared: [scope]
- Excluded or mandatory items: [item — reason]
- Hard dependencies: [item A before item B]

## Method choice
- Method: [value-effort | ICE | RICE | weighted scoring | cost of delay/CD3]
- Why it fits: [decision property that makes it suitable]
- Why the nearest alternative was rejected: [specific mismatch]
- Main limitation here: [what the method may miss]

## Scales and assumptions
| Input | Definition and unit | Anchors or range | Evidence standard |
|---|---|---|---|
| [factor] | [meaning and period] | [1 / 3 / 5, or low–high] | [what earns each confidence level] |

## Scores
| Rank | Item | Raw inputs | Score or quadrant | Confidence | Key evidence | Main uncertainty |
|---:|---|---|---:|---|---|---|
| 1 | [item] | [show calculation inputs] | [result] | [low/medium/high] | [source or observation] | [uncertain input] |

## Sensitivity check
| Assumption changed | Plausible change | Ranking effect | Interpretation |
|---|---|---|---|
| [input or weight] | [from X to Y] | [none / swaps A and B] | [stable or fragile] |

## Recommendation
1. **Start:** [item] — [reason grounded in evidence].
2. **Next:** [item] — [reason and dependency, if any].
3. **Validate:** [item] — [cheapest evidence needed before commitment].
4. **Defer:** [item] — [reason and reconsideration trigger].

## Judgment and overrides
- Formula order: [ordered list]
- Recommended order: [ordered list]
- Override: [difference, owner, and explicit reason; or none]

## Revisit triggers
- Re-score when [reach, effort, deadline, evidence, or strategy changes].
- Collect [specific evidence] by [date or decision point].
```

## Worked example

For three checkout improvements, choose RICE because affected users differ and the team has quarterly reach data. Define impact as expected reduction in failed checkouts, confidence from test quality, and effort in person-weeks.

| Item | Reach/quarter | Impact | Confidence | Effort | RICE |
|---|---:|---:|---:|---:|---:|
| Better decline message | 8,000 | 1 | 0.8 | 2 | 3,200 |
| New wallet option | 2,000 | 2 | 0.5 | 5 | 400 |
| Retry failed payments | 5,000 | 2 | 0.8 | 4 | 2,000 |

Recommend the decline message first if the inputs survive review. Do not conclude that it is exactly eight times “better” than the wallet option. If wallet reach could plausibly be 10,000, mark its placement as fragile and validate adoption before committing.

## Handling special cases

### Mandatory work

Place mandatory work in a separate capacity lane. State the minimum compliant or safe scope, deadline, and consequence of omission. Prioritize only among implementation options when useful.

### Dependencies

Score the end-to-end outcome when a prerequisite has little standalone value. Alternatively, show the prerequisite as a constraint beneath the enabled item. Do not rank the dependency below the work it must precede and then ignore the contradiction.

### Mixed horizons

Normalize all inputs to one horizon or create separate near-term and long-term views. Do not compare annual reach for one item with monthly reach for another.

### Insufficient evidence

Recommend an evidence-gathering action rather than a delivery commitment. Example: interview five affected customers or run a painted-door test before assigning a high-confidence impact score.

### Tied or fragile rankings

Treat close scores as a decision band. Use strategic fit, sequencing, option value, or learning value as a stated tie-breaker. Never add decimal places to manufacture separation.

## Quality bar

A good output lets a skeptical reader reconstruct the ranking, challenge one assumption without rebuilding the model, and see whether the recommendation changes.

Check that:

- The method follows from the decision shape, not team habit.
- Every factor has a definition, unit, period, and evidence anchor.
- Raw inputs and calculations are visible beside final scores.
- Mandatory work and dependencies are handled before ranking.
- At least one plausible sensitivity test targets the weakest assumption.
- The recommendation distinguishes stable choices from fragile ones.
- Any override is explicit and attributable to a decision owner.
- Deferred items have a reason and a reconsideration trigger.

Avoid these failure modes:

- Do not compare unlike scopes, populations, periods, or effort units in one table.
- Do not use RICE reach as a proxy for importance when low-reach work protects the whole system.
- Do not let ICE “ease” reward trivial work without checking absolute value.
- Do not create weighted-scoring factors that count the same benefit twice.
- Do not invent revenue to make cost of delay look quantitative.
- Do not present estimated scores as measured facts or rank near-ties with false precision.
- Do not hide strategic judgment inside weights, confidence, or effort estimates.
- Do not allow a score to overrule safety, legal, contractual, or dependency constraints.

## When not to use this

Do not use a prioritization framework when there is only one viable option, an incident requires immediate response, a legal or safety obligation dictates action, or the next step is already forced by a hard dependency. Do not score ideas when the real problem is unresolved strategy; define the objective and decision rights first.
