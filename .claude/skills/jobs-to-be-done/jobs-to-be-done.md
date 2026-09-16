---
name: jobs-to-be-done
description: Uncover what customers hire or fire a product to accomplish; use for discovery, interviews, positioning, prioritisation, and churn analysis.
user-invocable: true
---

# Jobs-to-be-Done

Identify the progress a customer seeks in a specific situation, then turn that evidence into product decisions. Use this when requests, feature ideas, adoption, churn, or competitor switches reveal the solution but not the underlying job.

## Responsibilities

1. **Set the decision** — state what the analysis must inform: discovery, prioritisation, positioning, onboarding, retention, or another named choice.
2. **Choose one actor and episode** — focus on a person who made, attempted, delayed, or rejected a decision in a bounded situation.
3. **Gather behavioural evidence** — prefer interview notes, support conversations, sales calls, churn reasons, usage sequences, and observed workarounds over opinions about an imagined future.
4. **Reconstruct the timeline** — identify the first concern, passive search, active search, comparison, commitment, first use, continued use, and possible abandonment.
5. **Separate evidence from inference** — label direct observations, customer statements, interpretations, and open questions distinctly.
6. **Describe the functional job** — name the practical change the actor needs, without naming a feature, product, or implementation.
7. **Describe the emotional job** — name how the actor wants to feel or avoid feeling while making that progress.
8. **Describe the social job** — name how the actor wants relevant people to view, trust, include, or respond to them.
9. **Record pains** — capture obstacles, risks, costs, delays, uncertainty, and unwanted trade-offs in the current approach.
10. **Record gains** — capture observable outcomes that would make the new approach meaningfully better, including thresholds where known.
11. **Map switching forces** — identify what makes the current approach intolerable, what attracts the actor to an alternative, what preserves the old behaviour, and what makes the new choice feel risky.
12. **Identify the trigger** — find the event or accumulated change that turns background dissatisfaction into action.
13. **Name the alternatives** — include competitors, manual work, internal help, delay, and doing nothing; the product competes with all credible ways to make progress.
14. **Explain hiring and firing** — state why the actor chose the product, what outcome keeps it employed, and what failure would cause replacement or abandonment.
15. **Segment by circumstance** — group cases by situation, desired progress, constraints, and switching pattern rather than demographics alone.
16. **Translate the job into decisions** — propose changes to product, messaging, service, or research, each tied to evidence and a named force.
17. **Expose uncertainty** — rank unresolved assumptions and specify the next interview, observation, or test that can resolve each one.

## Evidence Rules

- Ask about the last real occasion, not what the customer generally prefers.
- Follow sequence questions: “What happened before that?”, “What did you try next?”, and “Who else was involved?”
- Ask for concrete artefacts such as comparison notes, spreadsheets, messages, approvals, or abandoned setups.
- Treat vivid recollection as evidence of an event, not proof that the explanation is complete.
- Record exact language sparingly when it clarifies motivation; otherwise paraphrase without polishing it into marketing copy.
- Do not combine contradictory cases into one smooth narrative. Preserve meaningful differences.
- Mark a claim `Observed`, `Stated`, `Inferred`, or `Unknown`.
- Assign confidence from evidence quality, not confidence of tone.

## Interview Prompts

Use these prompts to reconstruct a decision rather than solicit features.

1. “Tell me about the most recent time this became a problem.”
2. “What changed that made you deal with it then?”
3. “What were you using or doing before?”
4. “What was good enough about that approach for you to keep it?”
5. “When did you first look for another way?”
6. “Which alternatives did you seriously consider, including doing nothing?”
7. “What attracted you to each option?”
8. “What worried you about changing?”
9. “Who influenced, approved, blocked, or judged the choice?”
10. “What did you expect to be different after choosing?”
11. “What happened during the first use?”
12. “What would make you return to the old approach or choose something else?”

Do not ask all prompts mechanically. Follow the episode until the timeline, trade-offs, and forces are specific.

## Job Statement Rules

Write the primary job in this form:

```text
In [specific circumstance], I need to [make practical progress],
so that [meaningful outcome in the actor's world].
```

Add emotional and social dimensions separately:

```text
Emotional: I want to feel [state] and avoid feeling [state].
Social: I want [relevant people] to see or treat me as [desired perception].
```

A strong statement is solution-neutral and circumstance-bound.

Weak:

```text
When planning work, I need an AI dashboard so I can be productive.
```

Stronger:

```text
When several teams change commitments during the week, I need to see which delivery promises are now at risk,
so that I can renegotiate them before stakeholders are surprised.

Emotional: I want to feel in control rather than exposed.
Social: I want team leads to regard me as candid and dependable.
```

## Switching-Force Model

Analyse four forces and the trigger:

| Force | Diagnostic question | Evidence to capture |
|---|---|---|
| Pressure from the current approach | What became costly, unreliable, slow, or embarrassing? | Failure, delay, incident, repeated workaround |
| Attraction of the alternative | What promised a better path or outcome? | Demonstration, referral, capability, service promise |
| Attachment to the current approach | What remains familiar, trusted, cheap, or politically safe? | Routine, sunk setup, relationships, existing data |
| Anxiety about the alternative | What could go wrong during or after the change? | Learning cost, migration risk, credibility risk, reversibility |
| Trigger | Why did evaluation or action begin at that moment? | Deadline, growth, new role, failure, policy, life event |

Do not treat adoption as attraction alone. A switch occurs when the case for movement becomes stronger than attachment and anxiety in that circumstance.

## Output Format

Produce the following artefact. Preserve `Unknown` fields rather than inventing a complete story.

```markdown
# Jobs-to-be-Done Analysis: [actor and episode]

## Decision to inform
- Decision: [specific product or research decision]
- Actor: [person making or strongly shaping the choice]
- Episode: [real event and approximate date]
- Evidence: [sources reviewed]
- Confidence: high | medium | low — [reason]

## Situation and timeline
| Stage | What happened | Evidence status | Source |
|---|---|---|---|
| First concern | [event or realisation] | Observed/Stated/Inferred/Unknown | [source] |
| Passive search | [attention before active shopping] | [...] | [...] |
| Active search | [options sought] | [...] | [...] |
| Comparison | [criteria and trade-offs] | [...] | [...] |
| Commitment | [choice, approval, purchase, or refusal] | [...] | [...] |
| First use | [initial outcome and friction] | [...] | [...] |
| Continued use or firing | [why retained, reduced, or abandoned] | [...] | [...] |

## Job
In [circumstance], I need to [functional progress], so that [outcome].

- Functional: [practical progress]
- Emotional: [desired and avoided feelings]
- Social: [desired response from named people]
- Success signals: [observable outcomes or thresholds]
- Constraints: [time, money, policy, skill, compatibility, authority]

## Pains and gains
| Type | Evidence-backed detail | Severity or value | Current response |
|---|---|---|---|
| Pain | [obstacle, cost, risk, or trade-off] | high/medium/low | [workaround] |
| Gain | [valuable outcome] | high/medium/low | [how measured] |

## Switching forces
| Force | Finding | Evidence | Strength |
|---|---|---|---|
| Pressure from current approach | [...] | [...] | strong/medium/weak |
| Attraction of alternative | [...] | [...] | strong/medium/weak |
| Attachment to current approach | [...] | [...] | strong/medium/weak |
| Anxiety about alternative | [...] | [...] | strong/medium/weak |
| Trigger | [...] | [...] | decisive/contributing/unclear |

## Alternatives
| Alternative | Why considered | Why accepted or rejected |
|---|---|---|
| [competitor, manual method, help, delay, or no action] | [...] | [...] |

## Hiring and firing criteria
- Hired because: [progress and trade-off the actor selected]
- Kept if: [outcome that must continue]
- Fired if: [failure, changed circumstance, or superior alternative]
- Re-hire condition: [what could restore consideration]

## Implications
| Proposed action | Job or force addressed | Evidence | Expected change | Validation |
|---|---|---|---|---|
| [product, message, service, or research action] | [specific link] | [source] | [behaviour or outcome] | [test and measure] |

## Unknowns and next evidence
| Unknown or assumption | Why it matters | Next evidence | Priority |
|---|---|---|---|
| [...] | [...] | [interview, observation, log, experiment] | high/medium/low |

## One-sentence decision
[Because actors in circumstance X seek progress Y but are held back by force Z, do A and validate it with B.]
```

## Converting Findings into Action

Tie each recommendation to a behavioural mechanism.

- Reduce pressure only when improving the current experience prevents firing.
- Increase attraction by making the desired outcome easier to recognise and achieve.
- Respect attachment by preserving familiar workflows, data, relationships, or control where they matter.
- Reduce anxiety with trials, migration support, previews, guarantees, reversibility, and clear expectations.
- Address the trigger where customers notice it: the relevant workflow, channel, role transition, or deadline.
- Prioritise an action when evidence is repeated, the force is strong, and the expected behaviour is measurable.
- Recommend more research when the apparent job rests mainly on inference or a single unverified case.

## Quality Bar

A good output lets a product team explain a real choice, distinguish competing forces, and make a testable decision.

Check that:

- The job names progress in a concrete circumstance without embedding the proposed product.
- Functional, emotional, and social jobs are distinct and supported by the episode.
- The timeline shows what changed, not merely what the customer says they value.
- Alternatives include inertia, workarounds, and no action where credible.
- Pains and gains describe consequences in the customer's world, not a reverse list of product features.
- Hiring and firing criteria state observable conditions.
- Recommendations point back to evidence and predict a measurable behaviour.
- Unknowns remain visible and have a practical route to resolution.

Avoid these failure modes:

1. **Feature translation** — rewriting “add exports” as “the job is exporting.” Ask what progress the export enables and in what situation.
2. **Generic aspiration** — using “save time,” “be productive,” or “feel confident” without an episode, consequence, or success threshold.
3. **Persona substitution** — assuming age, title, or industry explains the job without showing how circumstance changes behaviour.
4. **Attraction-only analysis** — listing reasons to buy while ignoring habit, switching cost, anxiety, and the trigger.
5. **Opinion as evidence** — treating hypothetical intent, stakeholder belief, or a loud request as observed behaviour.
6. **Single-job flattening** — forcing contradictory switching stories into one statement instead of segmenting by circumstance.
7. **Product-centred success** — measuring clicks or adoption without connecting them to the customer's desired outcome.
8. **Unfalsifiable recommendations** — proposing “improve onboarding” without naming the anxiety, expected behaviour, and validation measure.

## When NOT to Use This

Do not use this skill for a known defect with a clear reproduction, a mandatory compliance change, a purely technical migration, or routine delivery planning. Do not use it when the decision requires usability testing of an existing interface rather than understanding why the customer seeks progress. If no behavioural evidence exists, use the template to plan discovery, not to manufacture a definitive job.
