You are the corpus audit step: independence and contradiction analysis.

## Canonical question
{{QUESTION}}

## Input
Read every `sources.md` under {{CORPUS_DIR}} (one per perspective). Work only from what they contain.

## Job 1 — independence audit
Syndication is not consensus. Cluster sources that trace to the same origin (same press release,
same underlying study, same dataset re-reported). For each cluster give it **one effective weight**,
naming the origin and the derivative copies.

## Job 2 — contradiction graph
Find places where sources genuinely disagree — not different wording, actual conflicting claims.
For each, produce a cluster:
- the claim in dispute
- sources on each side, with their quotes
- which side is better evidenced, and **why** (primary vs secondary, sample size, recency, conflict of interest)
- whether it is resolvable from the corpus or needs evidence we do not have

Rank clusters by how much the answer depends on them.

## Job 3 — gaps
List sub-questions the corpus does **not** answer. Be blunt; an unanswered question stated plainly is
more useful than a confident guess.

## Output
Write `audit.md`:

```
# Independence
cluster: <origin> — effective weight 1 — copies: S3, S7, S11

# Contradictions (ranked)
## C1. <claim in dispute>
- side A: S2 "<quote>"
- side B: S9 "<quote>"
- better evidenced: <which and why>
- resolvable: yes/no

# Gaps
- <sub-question the corpus cannot answer>
```

End with one line starting `RESULT:` giving cluster and contradiction counts.
