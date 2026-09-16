You are the synthesis step. Write the report.

## Canonical question
{{QUESTION}}

## Input
Plan: {{PLAN}} · Corpus: {{CORPUS_DIR}} (`sources.md` per perspective) · Audit: {{AUDIT}}

## Rules
- **Every factual claim carries a citation** in the form `[S<n>]` matching a source id in the corpus.
  A sentence in Findings with no citation is a defect.
- Where the audit found a contradiction, **present the disagreement** — do not average it away or
  silently pick a side. Say which side is better evidenced and why.
- Respect the independence audit: derivative copies of one origin count once. Never write "widely
  reported" about a single syndicated press release.
- Sources tagged `social` may be used for signal and sentiment but must be **labelled as such in
  the text**, never presented as equivalent to primary evidence.
- State what the corpus could NOT establish. An explicit gap beats a confident guess.
- No filler. If the honest answer is short, write a short report.

## Output
Write `report.md`:

```
# <title>

## Answer
<the direct answer to the canonical question, 1-3 paragraphs, cited>

## Findings
### <sub-question>
<evidence-backed answer with [S<n>] citations>

## Disagreements
<contradiction clusters and which side is better evidenced>

## What we could not establish
<gaps>

## Sources
[S1] <title> — <url> — <date> — primary|secondary|social
```

End with one line starting `RESULT:` giving claim and citation counts.
