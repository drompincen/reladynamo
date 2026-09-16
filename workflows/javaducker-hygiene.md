# JavaDucker Hygiene Workflow

Claude is the brain. JavaDucker is the memory. This workflow is how Claude curates that memory — deciding what's current, what's stale, what supersedes what, and what threads matter.

## When to run

- Session-end hook detects un-enriched artifacts → run Phase 2
- `javaducker_index_health` reports "degraded" → run Phase 1
- After major architectural changes → run Phase 3
- Periodically (weekly or after big feature work) → run all phases

## Phase 1: Freshness — Keep the Index Current

```
1. javaducker_index_health → read the recommendation
2. If degraded:
   - javaducker_stale with git_diff_ref: "HEAD" → list stale files
   - Re-index each with javaducker_index_file (parallel)
   - javaducker_wait_for_indexed on each
```

This phase is mechanical — no judgment needed.

## Phase 2: Enrichment — Claude Reads and Classifies

This is where Claude's judgment matters. JavaDucker doesn't know what a file *means* — Claude does.

For each un-enriched artifact from `javaducker_enrich_queue`:

1. **Read the content** — `javaducker_get_file_text` to understand what the file is about
2. **Classify it** — `javaducker_classify` with the doc_type Claude determines:
   - `CODE` — source code (most files)
   - `ADR` — architecture decision record
   - `DESIGN_DOC` — design document or RFC
   - `PLAN` — execution plan
   - `MEETING_NOTES` — meeting notes or standup summaries
   - `THREAD` — conversation thread or session transcript
   - `REFERENCE` — reference material, guides, standards
   - `TICKET` — issue or ticket description
   - `SCRATCH` — throwaway notes, experiments
3. **Extract the important threads** — `javaducker_extract_points`. Claude reads the content and identifies:
   - `DECISION` — choices made and why (the most important type — these form the decision history)
   - `RISK` — identified risks, concerns, potential issues
   - `ACTION` — things that need to be done
   - `INSIGHT` — non-obvious learnings, "we discovered that..."
   - `CONSTRAINT` — hard constraints that limit future choices
   - `QUESTION` — open questions that haven't been resolved
   - `STATUS` — current state of something
4. **Tag it** — `javaducker_tag` with semantic tags Claude chooses based on content (domain area, technology, component, feature)
5. **Mark done** — `javaducker_mark_enriched`

**Key principle**: Claude must *read and understand* the content to enrich it properly. Don't guess from filenames. A file called `notes.md` might contain critical architecture decisions. A file called `ArchitectureDecision.md` might be an abandoned draft.

## Phase 3: Compaction — Claude Decides What's Obsolete

This is the most judgment-heavy phase. JavaDucker doesn't know which decisions invalidate others. Claude does.

### Step 1: Identify candidates

```
javaducker_stale_content → list stale/superseded artifacts
javaducker_find_points with DECISION type → all recorded decisions
javaducker_concept_health → concepts with fading/cold status
```

### Step 2: For each stale artifact, Claude decides

Read the artifact's summary, tags, and points. Then ask:

- **Is there a newer version?** `javaducker_latest` on the topic. If yes → supersede.
- **Does a recent decision invalidate this?** Compare against `javaducker_recent_decisions`. If a newer decision contradicts this artifact's decisions → supersede and note why.
- **Is it just old but still valid?** Some things don't change (core design principles, external API contracts). If still accurate → `javaducker_set_freshness` → `current`. Don't prune valid content just because it's old.
- **Is it partially valid?** Some points are still relevant, others are stale. Extract the still-valid points into the synthesis summary.

### Step 3: Synthesize what's truly obsolete

For artifacts Claude has decided to supersede:

```
1. javaducker_set_freshness → "superseded", superseded_by: <new artifact_id>
2. javaducker_synthesize:
   - summary_text: what this artifact contained and why it's superseded
   - key_points: any points that are STILL relevant (carry forward)
   - outcome: what replaced it and why
   - tags: preserve existing tags
3. javaducker_link_concepts → connect concepts from old → new artifact
```

**What gets pruned**: full text and embeddings (heavy, noisy in search results)
**What stays**: summary, tags, key points, concept links (lightweight, searchable as reference)

### Step 4: Decision chain maintenance

The most important thread to maintain is the **decision chain** — the sequence of decisions that led to the current state.

```
1. javaducker_find_points with DECISION type → all decisions
2. Group by domain/topic
3. For each topic, read the decisions chronologically
4. Identify: which decisions are still active? which were superseded?
5. If a decision was superseded but not marked:
   - javaducker_set_freshness → "superseded" on the old artifact
   - javaducker_synthesize with outcome noting what replaced it
6. If a decision chain has gaps (decision A → ??? → decision C):
   - Extract the implicit decision B from context and record it
```

This ensures that `javaducker_recent_decisions` always returns the *current* decisions, not a mix of old and new.

## Phase 4: Concept Health — Identify Important vs. Dead Threads

```
1. javaducker_concept_health → list all concepts with trend (active/fading/cold)
2. For each fading/cold concept, Claude decides:
   - Was it a real concept (e.g., "authentication", "payment processing")?
     → Check if it's still in the codebase (grep/search)
     → If still relevant: the concept needs fresh content, flag for investigation
     → If truly gone: no action, the synthesis records serve as history
   - Was it noise (e.g., "temporary", "workaround")?
     → No action needed
3. javaducker_concepts → verify the concept map reflects reality
```

## What stays, what goes

| State | Full text | Embeddings | Summary | Tags | Points | Searchable? |
|-------|-----------|------------|---------|------|--------|------------|
| **INDEXED** | yes | yes | yes | no | no | by text + semantic |
| **ENRICHED** | yes | yes | yes | yes | yes | full: text + semantic + tags + points + type |
| **SUPERSEDED** | **pruned** | **pruned** | yes | yes | yes | reference: summary + tags + points only |

## The judgment principle

JavaDucker stores. Claude curates. Every `set_freshness`, `synthesize`, `extract_points`, and `classify` call is a **judgment call** that Claude makes after reading and understanding the content. Never run enrichment or compaction mechanically — always read first, decide second, write third.
