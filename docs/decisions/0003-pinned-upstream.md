# 0003 · Depend on upstream by source, pinned to one commit

**Status**: Accepted

## Context

aws-emulator's engine, router and service crates are not published to crates.io. Its service crates are generated
from Smithy models and change as upstream regenerates them.

## Decision

The workspace `Cargo.toml` takes every upstream crate as a git dependency at one `rev`. Cargo finds the unpublished
crates in the repository by name, and their `workspace = true` keys resolve against upstream's own workspace.
`Cargo.lock` is committed, and CI builds with `--locked`.

A local change to upstream goes through `[patch]`, not a fork.

## Consequences

- Behaviour is reproducible: the same commit gives the same generated code, engine and responses.
- Moving to a newer upstream is one deliberate change to one `rev`, reviewed with its test results.
- The first build compiles upstream from source, which takes minutes. CI caches it.
