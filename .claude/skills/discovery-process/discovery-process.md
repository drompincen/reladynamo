---
name: discovery-process
description: Run a continuous discovery cycle from a fuzzy problem to a tested decision using opportunity mapping, assumptions, and small experiments before building.
user-invocable: true
---

# Discovery Process

Turn an uncertain customer problem into a clear decision: proceed, revise, investigate, or stop. Use this skill before committing substantial design or engineering effort to a proposed product change.

## Responsibilities

1. **Frame the outcome.** State the measurable customer or business result, the affected group, the current baseline, the desired movement, and the decision deadline. If numbers are unavailable, label them as unknown; do not invent precision.
2. **Separate evidence from belief.** Extract observed facts, interpretations, and open questions from the user's material. Attach a source and date to each fact. Mark unsupported claims as assumptions.
3. **Define the target customer.** Describe a specific situation and behaviour, not a broad demographic. Prefer “warehouse supervisors closing the evening shift” over “operations users.”
4. **Map opportunities.** Build an opportunity solution tree with the outcome at the root. Below it, record customer needs, pains, or desired gains. Split broad opportunities until each leaf can be investigated independently.
5. **Choose one opportunity.** Score opportunity leaves using evidence strength, expected effect on the outcome, frequency or severity, strategic fit, and reach. Explain the selection; do not hide judgment behind a total score.
6. **Generate distinct solutions.** Produce at least three materially different ways to address the selected opportunity, including a low-effort or non-product option when plausible. Do not treat minor interface variants as separate solutions.
7. **Expose assumptions.** For each solution, list what must be true about value, usability, feasibility, viability, and ethics or safety. Phrase each assumption so evidence could prove it wrong.
8. **Prioritize assumptions.** Place assumptions on an importance-versus-evidence map. Test the assumptions that are both essential to success and weakly supported before polishing the solution.
9. **Design the smallest useful experiment.** Match the method to the assumption. Specify participants or data, procedure, success and stop thresholds, time box, cost ceiling, and evidence limitations before collecting results.
10. **Run or prepare the test.** If tools and authorization allow, gather evidence. Otherwise produce a ready-to-run protocol, script, recruitment criteria, and capture sheet. Never present a planned test as completed research.
11. **Update the tree and assumption map.** Record what changed, what remains uncertain, and whether evidence applies to the opportunity, the solution, or only the test execution.
12. **Make a decision.** Recommend proceed, revise, investigate, or stop. Tie the decision to the predeclared threshold and name the next riskiest assumption. A successful experiment permits the next learning step; it does not automatically authorize a full build.

## Operating rules

- Keep problem evidence separate from solution evidence.
- Preserve competing explanations until evidence distinguishes them.
- Use observed behaviour when available; treat stated preference as weaker evidence.
- Seek disconfirming evidence. Ask what result would cause the team to stop.
- Prefer several small tests over one large, ambiguous test.
- Time-box discovery. Unbounded research is not risk reduction.
- Record who was included and excluded. Evidence from convenient participants may not transfer to the target customer.
- Do not calculate a confident aggregate score from guessed inputs.
- Do not promise statistical certainty from a small qualitative study.
- Protect participants: minimize personal data, avoid deception where possible, and flag sensitive or high-risk research for appropriate review.

## Discovery cycle artifact

Produce one living document using this skeleton. Fill unknown fields with `Unknown — owner: [name], due: [date]`.

```markdown
# Discovery cycle: [short name]

## 1. Decision frame

- Decision to make: [specific choice this discovery informs]
- Decision owner: [name or role]
- Decision deadline: [date]
- Target customer and situation: [who, doing what, under which conditions]
- Outcome: [measurable result, not a feature]
- Baseline: [value, period, source, or unknown]
- Desired movement: [direction or target and time horizon]
- Constraints: [policy, budget, technical, accessibility, safety]
- Out of scope: [explicit exclusions]

## 2. Evidence ledger

| ID | Statement | Type | Source and date | Confidence | Implication |
|---|---|---|---|---|---|
| E1 | [what was observed] | Fact | [source] | High/Med/Low | [why it matters] |
| A1 | [what is believed] | Assumption | None yet | Low | [risk if false] |
| Q1 | [what is unknown] | Question | — | — | [decision affected] |

## 3. Opportunity solution tree

[Outcome]
├── [Opportunity 1: customer need/pain/gain]
│   ├── [Opportunity 1a]
│   └── [Opportunity 1b] ← selected
│       ├── [Solution A]
│       ├── [Solution B]
│       └── [Solution C]
└── [Opportunity 2]

### Opportunity comparison

| Opportunity leaf | Evidence | Frequency/severity | Outcome effect | Strategic fit | Reach | Decision note |
|---|---:|---:|---:|---:|---:|---|
| [1a] | 1–5 | 1–5 | 1–5 | 1–5 | 1–5 | [reason] |

Selected opportunity: [leaf]
Selection rationale: [evidence and judgment, including uncertainty]

## 4. Solution candidates

| Solution | Mechanism | Expected behaviour change | Main trade-off | Effort class |
|---|---|---|---|---|
| A | [how it addresses the opportunity] | [observable change] | [cost or downside] | S/M/L |
| B | [different mechanism] | [observable change] | [cost or downside] | S/M/L |
| C | [different mechanism] | [observable change] | [cost or downside] | S/M/L |

## 5. Assumption map

| ID | Solution | Category | Falsifiable assumption | Importance | Evidence strength | Priority |
|---|---|---|---|---|---|---|
| AS1 | A | Value | [target customers will do X because Y] | High | Low | Test now |
| AS2 | A | Usability | [customers can complete X without Y] | High | Medium | Next |
| AS3 | B | Feasibility | [system can produce X within Y] | High | Low | Test now |

Categories: value, usability, feasibility, viability, ethics/safety.

Riskiest assumption: [ID]
Why it is first: [consequence if false + weakness of current evidence]

## 6. Experiment card

- Assumption under test: [one assumption ID]
- Learning question: [what must be learned]
- Method: [interview, observation, prototype task, concierge test, data analysis, technical spike, pricing test, etc.]
- Why this method fits: [connection between method and assumption]
- Participants or data: [criteria, count, exclusions, source]
- Procedure: [repeatable steps]
- Evidence captured: [behaviour, quote, metric, error, timing]
- Pass threshold: [observable result set before testing]
- Revise threshold: [result that suggests a changed approach]
- Stop threshold: [result that kills the assumption or solution]
- Time box and cost ceiling: [limit]
- Bias controls: [neutral wording, counterbalancing, blind review, comparison]
- Limitations: [what this test cannot establish]

## 7. Results

| Observation | Participant/data segment | Supports | Contradicts | Confidence |
|---|---|---|---|---|
| [result, without interpretation] | [segment] | [ID] | [ID] | High/Med/Low |

Threshold result: [pass / revise / stop / inconclusive]
Unexpected findings: [new opportunity or assumption]
Evidence gaps: [remaining uncertainty]

## 8. Decision record

- Decision: [Proceed / Revise / Investigate / Stop]
- Scope of decision: [assumption, solution, opportunity, or entire direction]
- Rationale: [result compared with predeclared threshold]
- Tree changes: [added, removed, reordered nodes]
- Assumption changes: [supported, weakened, new]
- Next action: [smallest next learning step or build slice]
- Next riskiest assumption: [ID]
- Owner and date: [name, date]
```

## Choosing an experiment

Use the least expensive method that can expose the assumption to failure.

| Assumption | Prefer | Do not infer |
|---|---|---|
| The problem occurs in context | Observation, diary study, support-log analysis | Frequency from one memorable interview |
| Customers value the outcome | Commitment test, concierge trial, behaviour with a real trade-off | Demand from compliments or feature voting |
| Customers can use the interaction | Task-based prototype session with defined completion criteria | Usability from a narrated demo |
| The system can perform within limits | Technical spike using representative inputs | Production feasibility from a happy-path mock |
| The model is economically viable | Cost model, price or willingness-to-pay test, operational simulation | Margin from revenue alone |
| A safety risk is controlled | Hazard analysis and review with qualified stakeholders | Safety from absence of complaints |

Example: To test “dispatchers will trust an automated route suggestion,” show realistic recommendations containing known weak cases. Ask dispatchers to decide and explain what they verify. Measure acceptance, correction, and verification behaviour. Do not ask only, “Would you use this?”

## Decision rules

- **Proceed** when the threshold is met and no contradictory evidence threatens the same critical assumption. Proceed only to the next proportionate commitment.
- **Revise** when the opportunity remains supported but the solution mechanism or audience needs a specific change.
- **Investigate** when the result is inconclusive, the test was invalid, or conflicting evidence affects the decision. State exactly what resolves the uncertainty.
- **Stop** when a critical assumption misses its stop threshold, the opportunity lacks meaningful evidence, or expected harm or cost outweighs likely value.

When evidence conflicts, segment it before averaging it. For example, a workflow may succeed for daily operators and fail for occasional users. That result may justify narrowing the target rather than declaring a universal pass.

## Quality bar

A good discovery output lets another person trace the path from outcome to opportunity, solution, risky assumption, experiment, evidence, and decision. It makes uncertainty visible and leaves a runnable next step.

Check that:

- The outcome describes a change in behaviour or result, not delivery of a feature.
- Opportunity nodes use customer language and can exist without the proposed solution.
- At least three solution mechanisms were considered before selection.
- Every critical assumption is falsifiable and assigned an evidence strength.
- The experiment tests one primary assumption with thresholds written in advance.
- Results distinguish raw observations from interpretation.
- The recommendation states what evidence would reverse it.
- The next commitment is proportional to the evidence gained.

Avoid these failure modes:

- **Solution-first tree:** placing “build a dashboard” where a customer opportunity should be. Rewrite it as the unmet need, such as “supervisors cannot spot stalled orders before shift end.”
- **Interview theatre:** collecting polite reactions to a pitch and calling them validation. Ask about recent behaviour or create a real choice with a cost.
- **Assumption soup:** testing desirability, usability, pricing, and technical performance in one activity. Isolate the riskiest claim so the result has a clear consequence.
- **Thresholds after results:** deciding what success means only after seeing the data. Record pass, revise, and stop rules before the test.
- **False certainty:** treating five participants or one analytics slice as proof for the whole market. State sample limits and plausible alternative explanations.
- **Endless discovery:** adding research without naming the decision it changes. Stop when the decision has enough evidence for its next reversible step.
- **Build disguised as experiment:** implementing production infrastructure to learn whether customers care. Use a prototype, manual service, or narrow technical spike first when it can answer the question.

## When not to use this

Do not use this process for a known defect with a reproducible fix, mandatory compliance work with no product-choice uncertainty, routine maintenance, or a tiny reversible change whose experiment costs more than the change. Do not use it to delay an urgent safety response; contain the harm first, then investigate remaining uncertainty.
