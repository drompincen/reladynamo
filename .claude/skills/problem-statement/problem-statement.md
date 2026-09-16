---
name: problem-statement
description: Turn vague complaints and feature requests into testable, user-centred problem statements before a team chooses a solution.
user-invocable: true
---

# Problem Statement

Turn a complaint, idea, or requested feature into a specific account of who is struggling, what they need to accomplish, what obstructs them, why it matters, and what evidence would show improvement. Use this before solution design, prioritisation, or discovery planning.

## Responsibilities

1. **Preserve the input** — quote or closely paraphrase the original request so the team can see what was interpreted.
2. **Separate evidence from assumptions** — label each important claim as observed, reported, inferred, or unknown. Never turn an inference into a fact through confident wording.
3. **Identify the affected user** — name the narrowest defensible user group and the situation in which the problem occurs. Prefer “first-time account administrators importing more than 500 records” to “users.”
4. **Describe the intended progress** — state what the user is trying to accomplish in their terms, independent of any proposed feature.
5. **Describe the obstacle** — explain what prevents, delays, confuses, or discourages that progress. Include the point in the journey where it occurs.
6. **Establish the current workaround** — record what users do now, including abandoning the task, asking another person, or accepting a poor result.
7. **Express the cost of the status quo** — state the consequence for the user and, when supported, for the organisation. Use quantities only when evidence provides them.
8. **Define observable success** — describe a change in user behaviour, outcome, time, error rate, or confidence. Do not define success as shipping a feature.
9. **Keep solutions out of the core statement** — move requested features and implementation ideas into a separate constraints and candidates section.
10. **Expose uncertainty** — list unanswered questions whose answers could materially change the audience, severity, scope, or chosen response.
11. **Set boundaries** — state adjacent users, situations, or problems that this framing does not cover.
12. **Produce the artifact below** — make the final statement concise enough to challenge, while retaining the evidence and decisions that support it.

## Working Method

### 1. Start from the request

Capture the request without correcting it.

Example input:

> Add a CSV preview screen. Imports keep failing and customers are angry.

Extract separate claims:

| Claim | Classification | What is missing |
|---|---|---|
| Customers import CSV files | Reported | Which customers and how often |
| Imports fail | Reported | Failure rate, causes, and affected file types |
| Customers are angry | Reported | Support evidence or direct user language |
| A preview screen will help | Assumption | Evidence that preview prevents the failures |

If the input contains no evidence, continue with a provisional framing. Mark it provisional rather than inventing research.

### 2. Resolve the five parts

Answer these questions in order:

1. **Who:** Which user or actor experiences the problem? In what context?
2. **Intent:** What outcome are they trying to achieve?
3. **Obstacle:** What specifically gets in the way, and at what moment?
4. **Cost:** What happens if nothing changes?
5. **Evidence of success:** What observable difference would indicate that the problem is reduced?

Use the user’s job and context to distinguish superficially similar problems. A finance analyst importing a monthly ledger may have a different problem from a shop owner uploading a first product catalogue.

### 3. Test the causal chain

Read the draft as a chain:

`user and context → intended progress → obstacle → consequence → observable improvement`

Challenge every link:

- Does the obstacle actually prevent the intended progress?
- Does the stated consequence follow from that obstacle?
- Would the success evidence change if the obstacle were reduced?
- Could a different underlying cause produce the same complaint?

If a link is unsupported, label it as an assumption or replace it with an open question.

### 4. Handle feature requests

Translate a requested feature into the hoped-for effect.

For “Add a CSV preview screen,” ask or infer provisionally:

- What would previewing let the user notice or decide?
- What goes wrong without it?
- Who encounters that failure?
- How would behaviour differ if the problem were solved?

Retain the preview screen as a candidate solution. Do not let it define the problem.

### 5. Choose success signals

Select one primary signal close to the user outcome and up to three supporting signals.

Good signals include:

- the proportion of target users completing the task;
- median time from starting to completing the task;
- frequency of a specific recoverable error;
- proportion of users needing staff assistance;
- repeated use after a successful first attempt.

Pair each signal with a baseline and target only when those numbers exist or the user asks for targets. Otherwise write “baseline needed” and describe how it could be measured.

Guard against displacement. A lower support-ticket count is not success if users simply abandon the task without contacting support.

## Output Format

Produce this artifact and omit fields only when they genuinely do not apply:

```markdown
# Problem framing: [short neutral title]

## Source request
> [Original complaint, observation, or feature request]

## Problem statement
[Specific user group] who are [relevant context] are trying to [intended progress],
but [obstacle at a specific point] prevents or hinders them.
As a result, [user consequence]; this also causes [organisational consequence, if evidenced].
We will know the problem has been reduced when [observable user outcome or behaviour changes].

## Evidence and confidence
| Claim | Evidence | Status | Confidence |
|---|---|---|---|
| [Who is affected] | [Source or “none yet”] | observed / reported / inferred / unknown | high / medium / low |
| [Obstacle] | [Source or “none yet”] | observed / reported / inferred / unknown | high / medium / low |
| [Cost] | [Source or “none yet”] | observed / reported / inferred / unknown | high / medium / low |

## Current behaviour
- Current path: [What the user does now]
- Workaround: [How the user compensates]
- Failure or abandonment point: [Where progress stops or degrades]

## Success signals
| Signal | Current baseline | Desired direction or target | Measurement method |
|---|---:|---:|---|
| [Primary user-outcome signal] | [value or “needed”] | [direction or target] | [event, study, survey, or operational data] |
| [Guardrail signal] | [value or “needed”] | [must not worsen] | [method] |

## Scope
- In scope: [Users, circumstances, and journey stage covered]
- Out of scope: [Nearby problems this statement does not claim to solve]

## Assumptions to test
1. [Assumption that could invalidate the framing]
2. [Assumption that affects severity or reach]

## Open questions
1. [Question answerable through research or data]
2. [Question needed before choosing a response]

## Candidate solutions mentioned
- [Preserve proposed solutions here without endorsing them]

## Next evidence step
[Smallest research or measurement action that most reduces uncertainty]
```

## Worked Example

Given “Add a CSV preview screen. Imports keep failing and customers are angry,” produce a provisional statement such as:

> Account administrators importing a catalogue for the first time are trying to publish accurate product data without manual re-entry, but they learn about formatting errors only after submitting the file. They must diagnose unfamiliar row-level errors and repeat the upload, delaying catalogue setup and increasing requests for staff help. We will know the problem is reduced when more first-time administrators complete a valid import without assistance and require fewer submission attempts.

Label “first-time,” “learn only after submitting,” and any claimed support impact according to the available evidence. Keep “CSV preview screen” under candidate solutions until evidence shows that late error discovery is the material obstacle.

A useful next evidence step might be: review failed-import events and five recent support conversations, then interview three affected administrators while they retry an import. Choose the smallest step that can disprove the framing, not a broad research programme by default.

## Quality Bar

A good output is specific enough that teammates can dispute individual claims and investigate them. It identifies a user and situation, describes a solution-independent obstacle, connects that obstacle to a meaningful consequence, and names observable evidence of improvement.

Before finishing, check:

- Can a reader identify who is included and excluded?
- Could more than one plausible solution address the statement?
- Is each factual claim supported or visibly labelled uncertain?
- Does success describe a changed user outcome rather than completed delivery?
- Is the next evidence step proportionate to the largest uncertainty?
- Would a researcher or analyst know what to examine next?

Avoid these failure modes:

- **Do not restate the feature as the problem.** “Users need a CSV preview” presupposes the answer.
- **Do not use a universal audience.** “Users find imports difficult” hides differences in role, experience, volume, and context.
- **Do not smuggle in unsupported causes.** A complaint about failures does not prove that validation, documentation, or interface design is responsible.
- **Do not confuse business impact with user impact.** “Support costs are high” may matter, but it does not explain what users cannot accomplish.
- **Do not invent precision.** Unsupported percentages, revenue effects, or time savings make the artifact less trustworthy.
- **Do not use output metrics as success.** Screens shipped, tooltips added, and preview views opened do not establish that users made progress.
- **Do not bundle distinct problems.** If two user groups face different obstacles or consequences, write separate statements.
- **Do not hide uncertainty in polished prose.** A provisional statement must remain visibly provisional.
- **Do not make the artifact exhaustive.** Include only evidence and questions that affect the framing or the next decision.

## When Not to Use This

Do not use this skill when the problem is already well evidenced and the user needs solution design, requirements, delivery planning, or prioritisation instead. Do not use it for a confirmed defect whose expected and actual behaviour are already clear; write a reproducible bug report. Do not use it to manufacture user justification for a fixed executive decision. If the task is purely technical maintenance with no meaningful user outcome, use an engineering problem description instead.
