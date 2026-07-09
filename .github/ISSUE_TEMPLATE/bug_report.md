---
name: Bug report
about: Something the plugin gets wrong — a false error, broken completion, a bad boundary, a cancel that doesn't
title: ''
labels: bug
assignees: ''
---

**What happened**
A clear description of the misbehavior.

**The SQL** (please genericize — no company table/column names)
```sql
-- a minimal statement that reproduces it, with identifiers replaced (e.g. acme_events)
```

**Expected vs actual**
- Expected:
- Actual:

**Screenshots**
If it's a highlighting / completion / resolution issue, a screenshot of the editor helps a lot.

**Environment**
- IDE + version (e.g. DataGrip 2026.1.4, IntelliJ IDEA 2026.2 EAP):
- Plugin version:
- Doris server version (`SELECT VERSION()` / `SHOW FRONTENDS`):
- Deployment (single FE / multi-FE behind a load balancer / k8s):

**Relevant flags** (if you changed any)
- [ ] `-Ddoris.catalogs.experimental=false`
- [ ] `-Ddoris.replay.poc=false`
- [ ] `-Ddoris.cancel.experimental=false`

**Logs**
For catalog/introspection or cancel issues, `Help → Show Log in Finder/Explorer`, then grep for
`DorisCatalogs:` or `DorisCancel:` and paste the relevant lines.
