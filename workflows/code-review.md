# Code Review Workflow

1. **Read the diff holistically** — Understand the full change before commenting on details.
2. **If JavaDucker is available** — use `javaducker_dependents` on changed files to check for downstream impact that might need updating.
3. **Check each dimension:**
   - Correctness — Does it do what it's supposed to?
   - Security — Any injection, auth, or data exposure risks?
   - Performance — Any unnecessary loops, queries, or allocations?
   - Readability — Can someone else understand this in 6 months?
   - Maintainability — Is it easy to change later?
3. **Rate issues by severity:**
   - **Blocker** — Will cause bugs, security issues, or data loss. Must fix.
   - **Major** — Significant design or logic issue. Should fix.
   - **Minor** — Improvement opportunity. Fix if convenient.
   - **Nit** — Style preference. Optional.
4. **Note positives** — Acknowledge good patterns and decisions.
5. **Verdict** — Approve, Approve with comments, or Request changes.
