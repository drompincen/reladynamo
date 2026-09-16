---
name: user-story-mapping
description: Build a journey-centred story map and slice a thin, coherent first release when a team needs shared scope and release decisions.
user-invocable: true
---

# User Story Mapping

Build a story map that makes the user's end-to-end journey visible, organises detail beneath it, and identifies the smallest release that delivers a coherent outcome. Use it when a team must align on scope, expose gaps, or decide what belongs in a first release.

## Responsibilities

1. **Define the mapping frame.** State the primary user, the situation that starts the journey, the outcome they seek, and the point at which the journey is complete. Record important business or delivery constraints separately; do not turn constraints into user steps.

2. **Choose one journey.** Keep one map centred on one user and one outcome. If the request mixes distinct users or unrelated outcomes, split it into separate maps and note where they connect.

3. **Write the backbone.** Describe 5–12 high-level user activities in the order the user would naturally perform them. Use short verb phrases such as `Find a suitable class`, `Reserve a place`, and `Attend the class`.

4. **Add user steps.** Under each activity, write the observable steps a user takes. Order steps from left to right within the journey. Treat alternate routes as branches, not as extra backbone activities.

5. **Add detail tasks and stories.** Place concrete behaviours beneath the relevant step. Write each item so the team can discuss, estimate, and verify it. Prefer `See remaining places for a class` over `Capacity management`.

6. **Mark uncertainty.** Label assumptions, unanswered questions, risky dependencies, policy decisions, and research needs. Do not silently invent product rules or technical constraints.

7. **Check the journey for gaps.** Walk through the map as the primary user. Cover entry, success, recovery from likely failures, and completion. Add missing steps before prioritising detail.

8. **Define the first-release outcome.** Write one sentence naming the user, the outcome they can achieve, and the evidence that the release works. Example: `A first-time member can find, book, and receive confirmation for one available class without staff help.`

9. **Slice horizontally.** Select the thinnest connected path across the whole journey that achieves the first-release outcome. Include only the minimum behaviour needed at every necessary step; defer breadth, automation, variants, and polish unless they are essential to coherence or safety.

10. **Test the slice.** Trace a realistic scenario through every selected item. Reject a slice that starts but cannot finish, depends on a deferred capability, or delivers only internal infrastructure with no usable outcome.

11. **Add later slices by outcome.** Group deferred items into releases that each create a meaningful improvement. Name releases by the user or business result, not by a feature bundle or implementation phase.

12. **Report decisions.** Summarise what is in the first release, what is explicitly out, which assumptions could change the slice, and what the team must decide next.

## Mapping rules

- Read the map left to right as time or narrative sequence.
- Read it top to bottom as increasing detail, alternatives, or lower priority.
- Keep activities broader than steps and steps broader than detail stories.
- Describe user behaviour, not screens, services, database tables, or team components.
- Preserve necessary non-happy-path behaviour in the first slice when omission would prevent completion, mislead the user, lose data, or create unacceptable risk.
- Treat accessibility, security, privacy, legal duties, and data integrity as constraints on every applicable slice, not optional late releases.
- Keep evidence and confidence visible. Mark inferred content with `Assumption` and unresolved content with `Question`.
- Use stable item IDs so discussion, release membership, and follow-up work can refer to the same item.

## Output format

Produce one markdown artifact using this skeleton. Replace every bracketed prompt and delete unused rows.

```markdown
# Story Map: [journey name]

## Frame

| Field | Definition |
|---|---|
| Primary user | [specific user or role] |
| Trigger | [event or need that starts the journey] |
| Desired outcome | [observable result for the user] |
| Journey complete when | [observable completion condition] |
| Constraints | [deadline, policy, platform, safety, or "None known"] |
| Evidence consulted | [research, analytics, stakeholder input, or "Not provided"] |

## Backbone and steps

| Order | Activity | User steps beneath the activity |
|---:|---|---|
| 1 | [verb phrase] | [1.1 step] → [1.2 step] → [1.3 step] |
| 2 | [verb phrase] | [2.1 step] → [2.2 step] |
| 3 | [verb phrase] | [3.1 step] → [3.2 step] → [3.3 step] |

## Detail stories

| ID | Activity / step | User behaviour or need | Notes and acceptance signal | Release |
|---|---|---|---|---|
| S-01 | [1 / 1.1] | [specific behaviour] | [observable signal] | R1 |
| S-02 | [1 / 1.1] | [alternative or detail] | [observable signal] | Later |
| S-03 | [1 / 1.2] | [specific behaviour] | [observable signal] | R1 |
| S-04 | [2 / 2.1] | [specific behaviour] | [observable signal] | R1 |

## Release slices

### R1 — [outcome, not feature name]

**Outcome:** [user] can [complete outcome] under [bounded conditions].

**Success signal:** [observable measure or acceptance scenario].

**Included path:** S-01 → S-03 → S-04 → [final completion story]

**Deliberate limits:**

- [one supported user, channel, case, or manual operation]
- [one deferred variant or convenience]

**Coherence check:**

- Entry: [how the user begins]
- Progress: [how the user completes essential steps]
- Recovery: [minimum handling for likely failure]
- Completion: [what confirms the outcome]

### R2 — [next meaningful outcome]

**Outcome:** [what becomes easier, broader, faster, or safer].

**Adds:** [story IDs and why they belong together]

## Open decisions

| Type | Statement | Owner / evidence needed | Effect on slice |
|---|---|---|---|
| Assumption | [untested belief] | [person, research, or data] | [what changes if false] |
| Question | [unresolved choice] | [decision owner] | [scope or sequence impact] |
| Dependency | [external need] | [owner or system] | [blocked stories] |
| Risk | [credible failure] | [mitigation or test] | [release impact] |

## Scope decision

**R1 includes:** [short statement of the complete thin path].

**R1 excludes:** [important deferred behaviours and variants].

**Next action:** [single decision, test, or refinement needed next].
```

## Worked example

Use this small example to calibrate hierarchy and slicing. It is illustrative, not a default domain model.

```markdown
# Story Map: Book a fitness class

## Frame

| Field | Definition |
|---|---|
| Primary user | Existing gym member |
| Trigger | Wants to attend a class this week |
| Desired outcome | Has a confirmed place in a suitable class |
| Journey complete when | Confirmation identifies the class, time, and location |
| Constraints | Booking must not exceed class capacity |
| Evidence consulted | Product brief only; member behaviour is an assumption |

## Backbone and steps

| Order | Activity | User steps beneath the activity |
|---:|---|---|
| 1 | Find a class | Choose day → Review suitable classes |
| 2 | Reserve a place | Check availability → Confirm booking |
| 3 | Prepare to attend | Receive confirmation → Review location |

## Detail stories

| ID | Activity / step | User behaviour or need | Notes and acceptance signal | Release |
|---|---|---|---|---|
| S-01 | 1 / Choose day | See classes for one selected day | Results match the selected date | R1 |
| S-02 | 1 / Review suitable classes | Filter by instructor | Results update to selected instructor | Later |
| S-03 | 2 / Check availability | See whether a place remains | Full classes cannot be booked | R1 |
| S-04 | 2 / Confirm booking | Reserve one place | Capacity decreases once, with no duplicate booking | R1 |
| S-05 | 3 / Receive confirmation | See booking confirmation | Class, time, and location are shown | R1 |
| S-06 | 3 / Review location | Open directions | Directions open for the class venue | Later |

## Release slices

### R1 — Confirm one available class booking

**Outcome:** An existing member can choose a day, reserve one available class, and see confirmation.

**Included path:** S-01 → S-03 → S-04 → S-05

**Deliberate limits:** Instructor filtering and directions are deferred; full classes show unavailable rather than offering a waitlist.
```

## Quality bar

A good story map passes all of these checks:

1. **Narrative check:** A reader can retell the journey from the backbone without reading the detail rows.
2. **Hierarchy check:** Each step fits under its activity, and each detail story describes behaviour under its step.
3. **Coverage check:** The map reaches an observable user outcome and includes essential recovery or constraint handling.
4. **Slice check:** R1 crosses the necessary journey from entry to completion and can be demonstrated as one scenario.
5. **Thinness check:** Every R1 item is essential to the stated outcome or a mandatory constraint; convenience and breadth are deferred.
6. **Traceability check:** Every included item has an ID, a release, and an observable acceptance signal.
7. **Honesty check:** Assumptions and unanswered questions are visible rather than presented as facts.

Avoid these failure modes:

- Do not make the backbone a list of screens, system components, epics, or departments.
- Do not create a first release that covers only the beginning of the journey, such as search without completion.
- Do not label every item `R1`; that is backlog accumulation, not slicing.
- Do not make infrastructure, design, API, and testing separate horizontal releases. Include the minimum of each needed to deliver the selected user outcome.
- Do not hide exceptional paths that make the happy path unsafe or unusable, such as payment failure or capacity conflicts.
- Do not mix multiple primary users into one narrative and pretend their steps occur in a single sequence.
- Do not invent prioritisation evidence. State assumptions and request the missing evidence.
- Do not turn every detail into a long specification. Keep the map scannable and link or refer to deeper acceptance criteria when needed.

## When not to use this

Do not use a story map for a single isolated defect, a known one-step change, a technical migration with no user journey, or a roadmap organised primarily by dates and investments. Use a bug report, task breakdown, technical plan, or outcome roadmap instead. Do not force a map when the primary user and desired outcome are still unknown; first perform discovery sufficient to define them.
