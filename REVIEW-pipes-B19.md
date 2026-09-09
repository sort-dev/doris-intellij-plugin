# B19 Connector/J URL validation

Date: 2026-09-09

Status: **fixed; verification passed**.

The validator no longer relies on `java.net.URI.host` for Connector/J URLs. A small
depth-aware scanner handles top-level host lists without splitting commas inside host
properties or query values. Supported forms include:

- ordinary single-host and failover/multi-host URLs;
- `loadbalance` and `replication` schemes;
- per-list and per-host credentials;
- bracketed IPv6 endpoints;
- `(host=...,port=...)` and `address=(host=...)(port=...)` forms;
- URL-encoded and global port properties.

Malformed delimiters, empty host entries, missing host properties, invalid ports,
unbracketed IPv6-with-port, malformed percent escapes and trailing `address=` junk are
rejected. Empty ports retain Connector/J default behavior and receive the existing
missing-port warning. Port 3306 and missing effective ports are reported once per URL,
even for mixed endpoint lists. Non-MySQL schemes retain the previous URI fallback and
prefix warning.

## Verification

- Combined full DB-261.24374.56 suite: 414/414 passed in each companion mode.
- Combined focused B15/B19 tests on DB-262.10315.132: 7/7 passed in each companion mode.
- `verifyEmbeddedPipes` passed.
- Plugin Verifier reports DB-261.24374.56 and IU-262.8665.81 Compatible.

The verifier retained the existing API notices. No network connection or JDBC request
was performed.
