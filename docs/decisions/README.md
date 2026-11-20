# Decision records

Each record states one decision: the problem, what was chosen, what was not, and what follows from it. A record is
not edited once accepted; a later record supersedes it.

| # | Decision | Status |
|---|---|---|
| [0001](0001-c-abi.md) | Reach the emulator through a byte-level C ABI | Accepted |
| [0002](0002-ffm.md) | Bind with the foreign function API, not JNI or JNA | Accepted |
| [0003](0003-pinned-upstream.md) | Depend on upstream by source, pinned to one commit | Accepted |
| [0004](0004-async-threads.md) | Run async native calls on the client's own platform threads | Accepted |
| [0005](0005-close-semantics.md) | `close` waits out running calls, then refuses new ones | Accepted |
| [0006](0006-bundled-natives.md) | Bundle every platform's library in the jar | Accepted |
