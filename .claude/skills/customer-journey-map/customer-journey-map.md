---
name: customer-journey-map
description: Map an end-to-end customer journey to locate concentrated pain, emotional shifts, broken touchpoints, and moments where the experience is won or lost.
user-invocable: true
---

# Customer Journey Map

Build an evidence-aware view of a customer's experience across stages, from the customer's goal to the outcome and aftermath. Use it when a team needs to see where friction concentrates and which moments deserve attention first.

## Responsibilities

1. **Set the journey boundary.**
   - Name one customer or tightly defined segment.
   - State the situation that starts the journey.
   - State the customer outcome that ends it.
   - Choose a time horizon that includes the aftermath, not only the transaction.
   - Exclude adjacent journeys explicitly.

2. **Separate evidence from inference.**
   - Gather available interviews, support cases, analytics, observations, surveys, and process records.
   - Label each claim as `Observed`, `Reported`, `Measured`, or `Assumed`.
   - Cite an evidence reference or note the source beside consequential claims.
   - Keep unknowns visible; do not fill gaps with confident prose.

3. **Define stages in customer language.**
   - Use five to nine stages that describe changes in the customer's situation.
   - Start before the customer reaches the product or service.
   - End after the immediate result, including recovery, follow-up, or repeat use.
   - Prefer `Realise a need` and `Compare options` over internal phases such as `Acquisition`.

4. **Trace actions and touchpoints.**
   - Record what the customer tries to do in each stage.
   - List concrete interactions: search result, sales call, form, email, delivery, invoice, support chat.
   - Include handoffs between channels and teams.
   - Note workarounds, repeated work, waiting, abandonment, and requests for help.

5. **Capture thoughts and questions.**
   - Write brief first-person thoughts only when supported or clearly marked as assumed.
   - Identify what the customer needs to know, believe, or decide at each stage.
   - Record expectations before an interaction and whether reality meets them.
   - Avoid turning marketing copy into an invented customer quote.

6. **Plot the emotional curve.**
   - Assign each stage a score from `-2` to `+2`.
   - Use `-2 severe frustration`, `-1 friction`, `0 neutral`, `+1 confidence`, and `+2 delight`.
   - Explain every high and low with a cause.
   - Record the transition between stages when emotion changes sharply.

7. **Mark moments of truth.**
   - Flag an interaction when it changes trust, commitment, success, or willingness to continue.
   - State what the customer expects at that moment.
   - State what winning and losing look like in observable terms.
   - Distinguish a decisive moment from ordinary inconvenience.

8. **Locate concentrated pain.**
   - Write each pain point as a cause-and-effect statement.
   - Score severity, frequency, and journey consequence from 1 to 3.
   - Multiply the three scores for a directional priority, not false precision.
   - Increase confidence only when evidence is recent, relevant, and repeated.
   - Group related symptoms under a shared cause where evidence supports it.

9. **Identify opportunities without designing prematurely.**
   - Frame opportunities as outcomes, such as `reduce uncertainty before payment`.
   - Connect every opportunity to a stage, pain point, and evidence item.
   - Name the owner or collaborating teams when known.
   - Add a measure that would show the experience improved.
   - Keep proposed features in a separate follow-up list.

10. **Conclude with decisions and research gaps.**
    - Rank the three most consequential experience problems.
    - Explain why each matters to the customer and the organisation.
    - List unresolved assumptions that could change the ranking.
    - Recommend the next validation or intervention, not a generic workshop.

## Output Format

Produce one markdown artifact using this skeleton. Replace every bracketed prompt; remove rows that do not apply.

```markdown
# Journey: [customer goal, not product name]

## Scope

| Field | Definition |
|---|---|
| Customer | [specific person or segment and relevant context] |
| Trigger | [event that starts the journey] |
| End state | [customer outcome and immediate aftermath] |
| Time horizon | [minutes, days, months, or lifecycle interval] |
| Included | [channels, regions, products, or scenarios] |
| Excluded | [adjacent journeys or edge cases] |
| Evidence period | [dates covered by the evidence] |

## Evidence key

- E1 — [source, date, sample or record count, relevant segment]
- E2 — [source, date, sample or record count, relevant segment]
- A1 — Assumption: [claim that still needs validation]

Evidence labels: `O` observed, `R` reported, `M` measured, `A` assumed.

## Journey map

| Lane | 1. [Stage] | 2. [Stage] | 3. [Stage] | 4. [Stage] | 5. [Stage] |
|---|---|---|---|---|---|
| Customer goal | [desired progress] | [desired progress] | [desired progress] | [desired progress] | [desired progress] |
| Actions | [specific verbs] | [specific verbs] | [specific verbs] | [specific verbs] | [specific verbs] |
| Touchpoints | [channel or interaction] | [channel or interaction] | [channel or interaction] | [channel or interaction] | [channel or interaction] |
| Questions and thoughts | [short first-person thought] | [...] | [...] | [...] | [...] |
| Expectations | [what should happen] | [...] | [...] | [...] | [...] |
| Emotion | [score: cause] | [score: cause] | [score: cause] | [score: cause] | [score: cause] |
| Pain and workaround | [friction; response] | [...] | [...] | [...] | [...] |
| Evidence | [O:E1, M:E2, or A:A1] | [...] | [...] | [...] | [...] |
| Moment of truth | [none or MoT-1] | [...] | [...] | [...] | [...] |
| Opportunity outcome | [desired change] | [...] | [...] | [...] | [...] |

## Emotional curve

`[Stage 1 score] → [Stage 2 score] → [Stage 3 score] → [Stage 4 score] → [Stage 5 score]`

- Highest point: [stage, score, and cause]
- Lowest point: [stage, score, and cause]
- Sharpest change: [transition, score change, and trigger]
- Recovery pattern: [whether the experience recovers and why]

## Moments of truth

| ID | Stage and interaction | Customer expectation | Win signal | Loss signal | Evidence |
|---|---|---|---|---|---|
| MoT-1 | [where and what happens] | [expected outcome] | [observable behaviour or result] | [observable behaviour or result] | [E# or A#] |

## Pain concentration

Score each factor from 1 (low) to 3 (high). `Priority = severity × frequency × consequence`.

| Rank | Pain statement | Stage(s) | Severity | Frequency | Consequence | Priority | Confidence | Evidence |
|---:|---|---|---:|---:|---:|---:|---|---|
| 1 | Because [cause], the customer [struggle], which leads to [effect]. | [stage] | [1–3] | [1–3] | [1–3] | [1–27] | [low/medium/high] | [E# or A#] |

## Opportunity backlog

| Opportunity outcome | Pain addressed | Customer measure | Business measure | Owner | Next step |
|---|---|---|---|---|---|
| [reduce/increase customer outcome] | [rank or statement] | [behavioural or perception metric] | [operational or commercial metric] | [team] | [research, experiment, or process change] |

## Decisions

1. Address [pain] first because [customer consequence, business consequence, and evidence].
2. Validate [assumption] through [specific method and sample] before [decision it affects].
3. Monitor [metric] at [stage] to detect [desired or harmful change].

## Open questions

- [Question] — owner: [person or team] — answer by: [date or decision point]

## Proposed solutions, kept separate

- [Optional concept linked to an opportunity; do not present it as validated]
```

## Worked Example

Use this level of specificity for a pain entry:

```markdown
| 1 | Because the delivery estimate disappears after payment, first-time buyers repeatedly reopen tracking and contact support, delaying confidence that the order is progressing. | Wait for delivery | 2 | 3 | 2 | 12 | Medium | M:E2, R:E4 |
```

The cause is the missing estimate, the behaviour is repeated checking and support contact, and the consequence is delayed confidence. `Checkout is confusing` would be too vague to guide action.

Use this level of specificity for a moment of truth:

```markdown
| MoT-2 | Payment confirmation page | Proof that payment succeeded and the order exists | Customer saves the order number and leaves the page | Customer retries payment or contacts support | O:E3, M:E5 |
```

## Quality Bar

A good map lets a reader trace every priority from evidence through customer impact to a measurable opportunity. It shows the whole experience, exposes uncertainty, and makes the painful transitions as visible as the painful stages.

Check that the output:

- Uses one defined customer and one bounded journey.
- Includes before, during, and after the core transaction.
- Names actions and touchpoints precisely enough to observe them.
- Gives emotional scores a stated cause and supporting evidence.
- Marks only genuinely decisive interactions as moments of truth.
- Ranks pain using consistent factors while showing confidence separately.
- Links each opportunity to a pain point and a success measure.
- Preserves disagreement or segment differences instead of averaging them away.

Avoid these failure modes:

- Do not map the organisation's funnel and relabel it as the customer's journey.
- Do not invent thoughts, quotations, emotions, or precision when evidence is absent.
- Do not treat every touchpoint as a moment of truth; reserve the label for consequential shifts.
- Do not stop at the purchase or task completion when follow-up determines trust.
- Do not mix different personas, channels, or happy and failure paths into one incoherent row.
- Do not list generic pains such as `slow`, `confusing`, or `bad support` without cause and consequence.
- Do not let an attractive feature idea replace diagnosis of the experience problem.
- Do not hide sparse evidence behind polished formatting.
- Do not rank pain solely by emotional intensity; include frequency and journey consequence.
- Do not confuse a service process step with something the customer actually experiences.

## When Not to Use This

Do not use this skill to design a future service from scratch when no current journey exists; use a service blueprint or scenario instead. Do not use it for a single screen usability review, a narrow workflow diagram, market segmentation, or internal process optimisation without a customer-experience question. If the problem is already isolated and the team needs root-cause analysis or solution testing, use the corresponding diagnostic or experiment method.
