---
name: df-research
description: Deep research on the grok fleet — decompose, multi-perspective sweep, independence and contradiction audit, adversarial critics, cite-check gate
user-invocable: true
---

# df-research

Run a sourced, adversarially-critiqued, citation-verified research report on **grok sub-agents**,
for almost no Claude tokens.

```bash
bash scripts/df-research.sh doctor
bash scripts/df-research.sh run "<question>" [--depth quick|deep]
```

`quick` = 4 perspectives + 3 critics. `deep` = 6 + 4. Output lands in `research/<slug>/`.

## What it does

| Phase | Units | Purpose |
|---|---|---|
| decompose | 1 | canonical question, sub-questions, coverage matrix, **search perspectives** |
| sweep | 4–6 **parallel** | one perspective each; every claim needs a verbatim quote; one unit uses **X/Twitter search** |
| audit | 1 | independence clustering (syndication ≠ consensus) + ranked contradiction graph |
| draft | 1 | cited report; disagreements presented, not averaged away |
| critics | 3–4 **parallel** | adversarial; **objections only**, never rewrites |
| patch | 1 | surgical edits only; may not restructure |
| cite-check | 1 | does each source *actually support* its sentence — a hard gate |

## Reading the result

Claude should read **`reports/df-research-audit.json`** and the report's Answer section — not the
phase bodies. Everything else stays on disk for drill-down.

The audit is the quality bar, and it is machine-checked:

- ≥20 distinct source URLs actually fetched (configurable via `DF_MIN_SOURCES`)
- every `[S<n>]` citation resolves to a real corpus source
- ≥1 contradiction cluster found
- zero uncited claims in Findings
- social sources labelled as such
- **cite-check: any `unsupported`, `missing`, or fabricated quote is a hard block**

If the audit fails, the report is not shippable. Say so plainly rather than presenting it anyway.

## Honest limits

- **X/social sources are signal, not evidence.** They are tagged `social`, counted separately, and
  must be labelled in the report. Never let them stand in for peer-reviewed work.
- Quality depends on grok's `web_search` / `web_fetch` reach. If the audit shows thin sourcing, the
  fallback is grok for breadth and Claude for the cite-check gate only.
- The method is adapted from [hyperresearch](https://github.com/jordan-gibbs/hyperresearch) (MIT) —
  independence audit, contradiction graph, adversarial critics, cite-check. The prompts here are our
  own and are **not** their tuned versions.

## Cost

Runs on grok, so Claude pays only for dispatch and reading verdicts — target **≤6 Claude turns** for a
full run. Grok spend is tracked per phase by the fleet.

Long runs survive Claude hitting its usage limit: phases are fleet units, so `grok-fleet.sh resume`
picks up where it stopped. See `.claude/docs/df-research.md`.
