You are auditing files in a repository. Work only from what you read on disk.

## Target
{{TARGET}}

## What to check
{{CHECKS}}

## How to work
1. Read every target file completely. Do not guess or infer from filenames.
2. For each file, record concrete findings with the exact line or field at fault.
3. If a file is clean, say so explicitly — absence of findings is a finding.

## Output
Write your findings to `{{OUTFILE}}` in your working directory, formatted as:

```
# Audit: {{TITLE}}
## <file path>
- <finding> (field/line: <where>)
```

End your final message with a single line beginning `RESULT:` summarising the count
of files audited and total findings.
