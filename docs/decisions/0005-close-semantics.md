# 0005 · `close` waits out running calls, then refuses new ones

**Status**: Accepted

## Context

`emu_free` while another thread is inside `emu_dispatch` frees memory that call is using. In native code that is a
crash or silent corruption, not an exception. Test frameworks close shared resources while stray calls may still be
running, for instance after a test times out.

## Decision

`EmuCore` guards the native handle with a `ReentrantReadWriteLock` and a `closed` flag:

- `dispatch` holds the read lock for the whole native call, and checks `closed` first.
- `close` takes the write lock, and frees the emulator only if it is not already closed.
- A call that finds `closed` throws `IllegalStateException`.

## Consequences

- Calls run concurrently; the read lock costs little next to a request.
- `close` blocks until running calls return, so a call that never returned would hold it up indefinitely. That is
  the price of never freeing memory a call is using.
- A call that arrives while `close` waits queues behind it, then fails. No call can see a freed emulator.
- `close` is idempotent.
