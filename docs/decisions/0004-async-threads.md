# 0004 · Run async native calls on the client's own platform threads

**Status**: Accepted

## Context

`emu_dispatch` blocks its calling thread until the router answers. `SdkAsyncHttpClient.execute` must return a future
without blocking.

## Decision

`EmuAsyncHttpClient` owns a cached pool of daemon platform threads named `emu-http`, and every dispatch runs there.
Closing the client shuts the pool down.

## Alternatives

**The caller's thread**
:   Blocks code that asked for a future, often an event loop or a ZIO or Cats Effect worker.

**The common `ForkJoinPool`**
:   Shared with the rest of the JVM. Blocking it starves unrelated work.

**Virtual threads**
:   A thread blocked in a native call pins its carrier, so a burst of calls exhausts the carriers.

## Consequences

- Concurrency is bounded only by the emulator. The cached pool grows to the number of calls in flight and shrinks
  when idle.
- Daemon threads never keep a JVM alive after its tests finish, even if a client is not closed.
