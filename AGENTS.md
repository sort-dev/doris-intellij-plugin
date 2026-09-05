# Agent Guide

## Verified fix workflow

When a fix is complete and fully verified, commit its intended changes before
reporting completion. Include the commit SHA and the verification performed in
the report. Stage only changes belonging to the task, not unrelated work.

Do not push without an explicit instruction from the user. If verification is
blocked or incomplete, report the limitation rather than calling the fix fully
verified.

## Commit identity (Buzz agents)

When you (a Buzz agent) commit on someone's behalf, attribute the commit to the
**human who issued the request**, not to the machine's global git identity.

1. The requester is the signed author pubkey of the triggering Buzz event.
2. Look that pubkey up in [`.buzz/authors.toml`](.buzz/authors.toml) to get the
   git `name` / `email`.
3. Commit as that identity, crediting the model as a co-author:

   ```bash
   GIT_AUTHOR_NAME="<name>" GIT_AUTHOR_EMAIL="<email>" \
   git commit -m "<subject>

   Co-Authored-By: Claude <model> <noreply@anthropic.com>"
   ```

4. **Pubkey not in `.buzz/authors.toml`?** Stop and ask — never silently fall
   back to the machine's global identity. Add the person (reviewed) first.

This matters for a **shared** Buzz agent that acts for several people. A person's
own standalone agent that only ever commits as its owner can ignore it — that
agent's global git identity is already correct.
