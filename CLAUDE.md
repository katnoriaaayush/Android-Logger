# CLAUDE.md

## 1. Think Before Coding
- State assumptions explicitly; if uncertain, ask.
- If multiple interpretations exist, present them — don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop, name it, and ask.

## 2. Simplicity First
- Minimum code that solves the problem. Nothing speculative.
- No features beyond what was asked; no abstractions for single-use code.
- No "flexibility" that wasn't requested; no error handling for impossible cases.
- If 200 lines could be 50, rewrite it.

## 3. Surgical Changes
- Touch only what you must. Match existing style.
- Don't refactor or "improve" adjacent code that isn't broken.
- Remove only the imports/vars your own changes orphaned; flag pre-existing
  dead code instead of deleting it.
- Every changed line should trace directly to the request.

## 4. Goal-Driven Execution
- Turn tasks into verifiable goals (e.g. "fix the bug" → "write a failing
  test that reproduces it, then make it pass").
- For multi-step work, state a brief plan with a verify check per step.
