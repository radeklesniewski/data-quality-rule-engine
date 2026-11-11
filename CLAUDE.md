@AGENTS.md

## Claude Code specifics

- `AGENTS.md` (imported above) is the shared guide for all assistants. Edit it there, not here, unless the note is Claude-only.
- Before you implement, check `docs/PLAN.md` for the next step, and do one milestone step at a time.
- After changing code, run `./gradlew build` (or the narrowest relevant `:module:test`) before you report it done.
  Report failures with their output.
- When you finish a step, propose a Conventional Commit message. Don't commit unless asked.
- Prefer editing existing types over adding new abstractions. Every new public type needs a reason in DESIGN.md or an ADR.
