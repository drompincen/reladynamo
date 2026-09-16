---
name: enterprise-form-architect
description: Design, build, review and debug long corporate data-entry forms in React — section architecture, render isolation at 100+ fields, validation timing, draft autosave and resume, server error mapping, and the accessibility a compliance review will ask for. Use whenever work touches a multi-section form, an intake or application form, a form that is slow to type in, or a form losing user data.
user-invocable: true
---

# Enterprise form architect

A corporate long form is not a big version of a contact form. It is a data-entry instrument used
repeatedly by people whose job it is, under a deadline, on data they are copying from somewhere
else. Three things decide whether it works: whether typing stays fast at scale, whether an
interruption costs the user their work, and whether a rejected submit tells them exactly what to
fix. Visual polish decides none of them.

**Work in this order.** Decide the shape, then the state architecture, then render isolation, then
validation timing, then persistence and submit. Reversing that order produces a form that is
beautiful, re-renders 140 fields on every keystroke, and loses an hour of work when the session
expires.

This skill assumes React with React Hook Form (RHF v7). Where a rule is library-independent it is
marked **[pattern]**.

## Decide the shape first

| Situation | Shape |
|---|---|
| 8–15 fields, one coherent topic | Single page, no sections |
| 15–80 fields, natural groupings, user knows the domain | **One page, sectioned, with a sticky section nav** |
| Later fields genuinely depend on earlier answers | Wizard — steps that gate |
| User rarely completes in one sitting | Sectioned page + draft autosave, not a wizard |
| Occasional user, high abandonment cost (public application) | Wizard with progress and save-and-resume |

**The default for corporate internal forms is a sectioned single page, not a wizard.** A wizard
hides the total scope, blocks the out-of-order entry that copy-from-source work demands, and makes
review before submit an extra journey. Use one only when a real dependency means later fields
cannot be rendered until earlier ones are answered. A repeat user filling the same form for the
fortieth time wants to tab through it, not click Continue nine times.

## Form state architecture

### Rules

1. **Give `useForm` a complete `defaultValues` object covering every field, because a field
   initialised as `undefined` mounts uncontrolled and React warns — then loses its value — the
   moment a value arrives.**
   Derive it from the same schema the resolver uses so a new field cannot be added to one and not
   the other.
   Use `null` or `''` for "no value yet", never `undefined`.

2. **Feed server data through the `values` prop, not a `reset()` inside `useEffect`, because
   `values` re-synchronises reactively and does not race the first render.**
   Pair it with `resetOptions: { keepDirtyValues: true }` so a background refetch cannot overwrite
   fields the user has already edited.
   Without `keepDirtyValues`, any poll or window-refocus refetch silently discards typing in
   progress — the single most damaging bug in this class of form.

3. **Use one form instance for the whole document, not one per section, because cross-section
   validation, a single dirty state, and one submit payload all need one source of truth.**
   Sections are components that read from shared `control`, not independent forms.
   Split into separate `useForm` instances only when sections submit independently to different
   endpoints.

4. **Keep `shouldUnregister` at its default `false` and strip conditional fields at the submit
   boundary instead, because unregistering on unmount destroys the value when a user collapses a
   section or a step scrolls out of a virtualised list.**
   When the API must not receive fields for a hidden branch, drop them in the resolver's transform
   or in `onSubmit` — a deliberate, testable step.
   Setting `shouldUnregister: true` to achieve that couples payload shape to mount state, which
   changes whenever the layout does.

5. **Read values with `getValues()` when you need a snapshot and nothing should re-render, because
   `watch()` and `useWatch` subscribe and `getValues()` does not.**
   Autosave, analytics, and "is this section complete" checks that run on a timer want
   `getValues()`.
   Anything that must update the UI wants `useWatch`.

### Worked example: server-backed form that survives a refetch

```tsx
const { data: application } = useQuery({
  queryKey: ['application', id],
  queryFn: fetchApplication,
});

const form = useForm<ApplicationForm>({
  resolver: zodResolver(applicationSchema),
  defaultValues: emptyApplication,          // every key present, never undefined
  values: application,                      // reactive re-sync when the server answers
  resetOptions: { keepDirtyValues: true },  // never clobber in-flight edits
  mode: 'onBlur',
  criteriaMode: 'firstError',
});
```

`defaultValues` covers the first paint, `values` covers arrival and every later refetch, and
`keepDirtyValues` covers the collision between them. All three are needed; two out of three is the
bug.

## Render isolation at 100+ fields

This is where long forms die. RHF is fast because inputs are uncontrolled by default — a form that
subscribes carelessly throws that away and re-renders 140 fields per keystroke.

### Rules

1. **Never call `watch()` in the body of the component that renders the form, because it subscribes
   the entire form tree to every change.**
   `const values = watch()` at the top of a 12-section form is the single worst line you can write
   in this file.
   Replace it with `useWatch({ control, name })` inside the leaf that actually needs the value.

2. **Subscribe by name, and as deep in the tree as possible, because a subscription re-renders its
   own component and everything below it.**
   `useWatch({ control, name: 'employment.status' })` inside `<EmploymentSection>` re-renders one
   section; the same call in the page root re-renders everything.

3. **Read `formState` through `useFormState({ control, name })` in leaf components rather than
   destructuring the parent's `formState`, because the parent's copy re-renders the parent.**
   Scope it with `name` so a field's error does not re-render sibling sections.

4. **Destructure the `formState` properties you use during render, because `formState` is a Proxy
   that only begins tracking a property once it has been read.**
   `const { errors, isDirty } = form.formState;` subscribes to both.
   Reading `form.formState.isDirty` for the first time inside a callback or an effect subscribes to
   nothing and the value goes stale.

5. **Depend on the specific `formState` slice in `useEffect`, not on the `formState` object,
   because the object identity changes on every state update and the effect fires constantly.**
   `useEffect(..., [isDirty])`, never `useEffect(..., [formState])`.

6. **Do not expect `React.memo` to stop re-renders caused by `useFormContext()`, because context
   propagation bypasses memoisation entirely.**
   A memoised section that calls `useFormContext()` re-renders whenever the provider's value
   changes.
   Pass `control` down as a prop and subscribe with `useWatch`/`useFormState`, or keep
   `useFormContext()` to components that genuinely need imperative methods.

7. **Wrap controlled third-party inputs in `useController` at the leaf, because `Controller` at a
   high level re-renders its whole render-prop subtree.**
   Date pickers, comboboxes, currency inputs, and rich text editors all need this; plain
   `<input>`s should stay uncontrolled via `register`.

### Worked example: an isolated section

```tsx
function EmploymentSection({ control }: { control: Control<ApplicationForm> }) {
  // Only this section re-renders when employment status changes.
  const status = useWatch({ control, name: 'employment.status' });
  const { errors } = useFormState({ control, name: 'employment' });

  return (
    <fieldset>
      <legend>Employment</legend>
      <StatusSelect control={control} name="employment.status" />
      {status === 'employed' && (
        <>
          <TextField control={control} name="employment.employerName" />
          <TextField control={control} name="employment.startDate" />
        </>
      )}
      {status === 'self_employed' && (
        <TextField control={control} name="employment.tradingName" />
      )}
    </fieldset>
  );
}
```

The page root renders `<EmploymentSection control={control} />` and subscribes to nothing. Typing
in the address section does not touch this component.

### Measuring it

Do not argue about this from first principles — measure. Open React DevTools Profiler, enable
"Highlight updates when components render", and type one character in a field in the middle of the
form. If more than the field and its immediate section flash, a subscription is too high in the
tree. Find it by searching for `watch(`, bare `formState` destructuring in the page root, and
`useFormContext()` in section components, in that order.

## Validation timing

### Rules

1. **Use `mode: 'onBlur'` or `'onTouched'` for long forms, not `'onChange'` and not the default
   `'onSubmit'`, because a user copying a reference number should not see an error at the second
   character, and a user who reaches the bottom of a 90-field form should not learn about field
   three for the first time.**
   `'onTouched'` — validate on first blur, then on every change — is the best default for data
   entry.
   `'onChange'` is justified only for fields with live feedback: password strength, a remaining
   character count, an availability check.

2. **Never disable the submit button on a long form, because a disabled button with no explanation
   gives the user nothing to act on and no way to find what is missing. [pattern]**
   Let submit run, fail validation, and render an error summary that names and links to each
   problem.
   Disabled submit is defensible only on short forms where every requirement is visible on screen
   at once.

3. **Do not rely on `formState.isValid` unless the mode is `onChange`, `onBlur`, `onTouched` or
   `all`, because in the default `onSubmit` mode nothing has validated yet and the flag is not
   meaningful.**
   If you need a live "ready to submit" indicator, you have chosen a validation mode already.

4. **Put cross-field and cross-section rules in the schema, not in component effects, because a
   rule spread across three `useEffect`s cannot be tested and will disagree with the server.**
   In Zod, `superRefine` with an explicit `path` attaches the error to the field the user must
   change.
   Rules like "end date must follow start date", "at least one contact method", and "total
   allocation must equal 100%" all belong here.

5. **Validate a section imperatively with `trigger(['a', 'b'])` when a wizard step must gate,
   because `handleSubmit` validates the whole form and will report errors from steps the user has
   not reached.**
   Keep the field list for each step next to the step definition, not inline in a handler.

6. **Normalise at the boundary, not in the validator, because a validator that also transforms
   makes the stored value depend on validation order.**
   Trim strings, strip currency separators, and convert local dates to ISO in the input component
   or a `transform`, then validate the normalised value.
   When the resolver transforms the output type, pass the third `useForm` generic so the submit
   handler is typed on the transformed shape, not the input shape.

### Worked example: a cross-section rule that points at the right field

```ts
const applicationSchema = z
  .object({
    employment: z.object({
      status: z.enum(['employed', 'self_employed', 'none']),
      employerName: z.string().trim().optional(),
      startDate: z.string().optional(),
      endDate: z.string().optional(),
    }),
    allocations: z.array(
      z.object({ costCentre: z.string().min(1), percent: z.number().min(0).max(100) })
    ),
  })
  .superRefine((v, ctx) => {
    if (v.employment.status === 'employed' && !v.employment.employerName) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ['employment', 'employerName'],   // the field the user must fix
        message: 'Employer name is required for employed applicants.',
      });
    }
    const total = v.allocations.reduce((sum, a) => sum + a.percent, 0);
    if (v.allocations.length > 0 && total !== 100) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ['allocations'],
        message: `Allocations must total 100%. Currently ${total}%.`,
      });
    }
  });
```

The `path` is the whole point. An issue raised without one lands on the form root, where the user
cannot see which field caused it.

## Draft persistence and resume

A corporate form is interrupted. Treat the draft as a product requirement, not a nicety.

### Rules

1. **Autosave on a debounce from `getValues()`, not on every change from a subscription, because
   the save path must not be able to trigger a render.**
   500–2000 ms of idle is the usual window; save on blur and on tab-hide as well.

2. **Save the raw form values, not the validated payload, because a draft is by definition
   incomplete and will not pass the schema.**
   Version the stored draft with a schema version so a deployed field change can be migrated or
   discarded deliberately rather than crashing on load.

3. **Show the save state in words with a timestamp, because "Saved" without a time does not tell
   an interrupted user whether their last ten minutes survived. [pattern]**
   "All changes saved 14:32" beats a checkmark; a persistent, actionable error beats a silent
   failure.

4. **Guard navigation while `isDirty` and unsaved, and clear `isDirty` by calling `reset()` with
   the submitted values after a successful submit, because a form that stays dirty forever will
   warn the user on every exit until they stop reading the warning. [pattern]**

5. **Never autosave a draft containing credentials, full payment card numbers, or unmasked
   national identifiers to `localStorage`, because browser storage is unencrypted, readable by any
   script on the origin, and survives logout.**
   Drafts of regulated data belong on the server against the authenticated session, or nowhere.

### Worked example: an autosave hook

```tsx
function useFormDraft(form: UseFormReturn<ApplicationForm>, draftId: string) {
  const [savedAt, setSavedAt] = useState<Date | null>(null);
  const { isDirty } = form.formState;

  useEffect(() => {
    if (!isDirty) return;
    const timer = setTimeout(async () => {
      // getValues() is a snapshot read — it does not subscribe and cannot cause a render.
      await saveDraft(draftId, { version: DRAFT_SCHEMA_VERSION, values: form.getValues() });
      setSavedAt(new Date());
    }, 1200);
    return () => clearTimeout(timer);
    // Depend on the slice, never on the formState object.
  }, [isDirty, draftId, form]);

  return savedAt;
}
```

## Submit and server errors

### Rules

1. **Map server field errors back onto their fields with `setError(path, { type: 'server' })`,
   because a server rejection rendered only as a banner forces the user to hunt through 90 fields
   for the one the API named.**
   Agree the error path format with the API — a JSON Pointer or a dotted path per field — and write
   one mapping function with a test.
   Errors with no field equivalent go to `setError('root.serverError', ...)` and render at the top.

2. **Drive the submit button from `formState.isSubmitting` and make the handler idempotent,
   because a long form invites double submission on a slow network.**
   Prefer an idempotency key over relying on the disabled state alone.

3. **Handle the invalid branch with `handleSubmit(onValid, onInvalid)`, because that callback is
   where focus management and the error summary belong.**
   Ignoring it produces a form that appears to do nothing when submit is pressed and the first
   error is off screen.

4. **Give a long form a review step or a review panel before commit, because a user who has entered
   80 fields over three sittings cannot verify them from the input controls alone. [pattern]**
   Show entered values grouped by section, each with an edit link back to the field.

5. **Do not clear the form on failure, ever.** Re-render it with the values intact and the errors
   attached.

### Worked example: server errors and focus

```tsx
const onInvalid = (errors: FieldErrors<ApplicationForm>) => {
  const first = Object.keys(flattenFieldErrors(errors))[0];
  if (first) form.setFocus(first as FieldPath<ApplicationForm>);
  summaryRef.current?.focus();   // announce the summary to screen readers
};

const onValid = async (values: ApplicationForm) => {
  try {
    await submitApplication(values, { idempotencyKey });
    form.reset(values);          // clears isDirty so the exit guard stops firing
  } catch (e) {
    if (isFieldValidationError(e)) {
      for (const { path, message } of e.fieldErrors) {
        form.setError(path, { type: 'server', message });
      }
      onInvalid(form.formState.errors);
    } else {
      form.setError('root.serverError', { message: 'Submission failed. Your data is saved.' });
    }
  }
};

<form onSubmit={form.handleSubmit(onValid, onInvalid)} noValidate>
```

## Repeating rows

Long corporate forms almost always contain a table of line items — allocations, dependants, prior
addresses, assets.

### Rules

1. **Use `field.id` from `useFieldArray` as the React `key`, never the array index, because RHF
   generates `id` precisely so that removing a row does not shift every later row's identity and
   scramble their state.**

2. **Mutate with the specific operation — `append`, `remove`, `insert`, `update` — rather than
   replacing the array, because a wholesale `setValue` on the array remounts every row and drops
   focus.**

3. **Declare one `useFieldArray` per `name`, because two hooks on the same array keep separate
   internal state and will disagree after a removal.**

4. **Virtualise only above roughly 100 rows, and never with `shouldUnregister: true`, because
   unmounting a scrolled-away row would destroy its value.**

5. **Validate the array as a whole where the rule is about the whole**, such as a total or a
   uniqueness constraint, and attach the issue to the array path so the summary can link to it.

## Accessibility that survives a compliance review

Long forms are where accessibility audits find the most, and the fixes are cheap if designed in.

1. **Every input has a real `<label for>`.** A placeholder is not a label; it disappears on the
   first keystroke, exactly when a copying user looks back to check what the field was.
2. **Group related fields in `<fieldset>` with a `<legend>`** — this is what makes a screen reader
   announce which section a field belongs to, and it is the whole basis of section navigation.
3. **Wire `aria-describedby` to both the helper text and the error node**, and set
   `aria-invalid="true"` when the field has an error.
4. **Render an error summary at the top of the form on failed submit**, as a focusable region
   listing every error as a link to its field. This is the single highest-value accessibility
   feature in a long form and it helps sighted users equally.
5. **Move focus deliberately after a failed submit** — to the summary, or to the first invalid
   field. Never leave focus on the submit button with the error off screen.
6. **Announce autosave state in an `aria-live="polite"` region**, not only as a visual badge.
7. **Do not trap tab order in a wizard step** and do not use `tabindex` above 0 anywhere.

## Traps to call out in review

- `const values = watch()` in the page root — re-renders every field on every keystroke.
- `useEffect(..., [formState])` — fires on every state change; use the slice.
- `formState.isDirty` read for the first time inside a callback — never subscribed, always stale.
- `values` prop without `resetOptions: { keepDirtyValues: true }` — a refetch eats live edits.
- `shouldUnregister: true` used to shape the payload — couples the API contract to mount state.
- Submit disabled until `isValid` on a 90-field form — no path to discovering what is missing.
- `isValid` consulted in the default `onSubmit` mode — it has not validated anything.
- Array index used as the `useFieldArray` key — row state scrambles on delete.
- A server 422 rendered as a banner only — the user cannot find the field.
- A memoised section that calls `useFormContext()` — memo does not stop context.
- Draft with unmasked personal or payment data in `localStorage` — unencrypted and survives logout.
- Cross-field rules in `useEffect` — untestable and will drift from the server's rules.
- Zod issue raised without a `path` — the error lands on the form root, invisible.

## Review checklist

- [ ] `defaultValues` covers every field; no `undefined` initial values
- [ ] Server data arrives via `values` with `keepDirtyValues`
- [ ] Typing in one section re-renders only that section (verified in the Profiler)
- [ ] No `watch()` in the page root; leaf components use `useWatch` / `useFormState`
- [ ] Validation mode is `onBlur` or `onTouched`; submit is not disabled
- [ ] Cross-field rules live in the schema with an explicit `path`
- [ ] Draft autosaves on a debounce from `getValues()`, with a visible timestamp
- [ ] No regulated data in browser storage
- [ ] Server field errors map onto fields; unmapped errors render at the top
- [ ] `handleSubmit` has an `onInvalid` branch that manages focus
- [ ] Error summary exists, is focusable, and links to each field
- [ ] Every input has a label; sections are `fieldset`/`legend`
- [ ] `reset()` after successful submit clears the navigation guard
- [ ] Field arrays key on `field.id`

## When not to use this

- **Short forms.** Under about 10 fields, an uncontrolled `<form>` with `FormData` and server-side
  validation is less code and fewer dependencies than any form library.
- **React 19 Server Actions / `useActionState`.** Server-driven form handling is a different model;
  this skill is client-side controlled forms.
- **Deeply nested, fully type-safe schemas.** If the shape is heavily nested and type inference
  matters more than bundle size, evaluate TanStack Form before assuming RHF.
- **Form-builder platforms.** If the form is configured rather than coded, the constraints are the
  platform's; only the shape, validation-timing and accessibility rules above transfer.
