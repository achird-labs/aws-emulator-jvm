# The C ABI

`emu-core` exports four functions and one struct. Any language that can call C can drive the emulator through them;
the JVM binding is one such host. The Rust tests in `crates/emu-core/tests/c_abi.rs` drive it the same way.

```c
typedef struct Emulator Emulator;          /* opaque */

typedef struct {
    uint16_t status;
    uint8_t *headers;   size_t headers_len; /* "name: value\r\n" lines */
    uint8_t *body;      size_t body_len;
} EmuResponse;

Emulator *emu_new(void);
void      emu_free(Emulator *emulator);
int32_t   emu_dispatch(const Emulator *emulator,
                       const char *method, const char *uri,
                       const uint8_t *headers, size_t headers_len,
                       const uint8_t *body, size_t body_len,
                       EmuResponse *out);
void      emu_response_free(EmuResponse *response);
```

## The functions

`emu_new`
:   Starts a new emulator over a fresh in-memory engine, or returns null if it could not start. Two emulators never
    share state.

`emu_dispatch`
:   Handles one request. `method` and `uri` are NUL-terminated; the URI is a path with an optional query. `headers` is
    `name: value\r\n` lines. A length of 0 allows a null pointer. Returns 0 and fills `out`, or -1 if the request
    could not be read, in which case `out` is untouched. Safe to call from several threads at once.

`emu_response_free`
:   Frees the two buffers of a response `emu_dispatch` filled, and nulls its pointers. Call it exactly once per
    successful dispatch.

`emu_free`
:   Frees an emulator. Null is ignored. No call on it may be running, and it may not be used again.

## Who owns which bytes

Each buffer has one owner at a time, and the owner frees it. The host owns what it passes in, for the duration of the
call. `emu-core` owns the response buffers it hands out, until `emu_response_free`.

![One emu_dispatch call by memory: the JVM allocates the request in a confined arena, emu-core leaks the response buffers and writes their addresses into EmuResponse, the JVM copies them to its heap, then emu_response_free and the arena's close release each side's memory.](../assets/abi-memory.svg)

`EmuCore` keeps every native allocation for one call in a confined arena, so they are freed together when the call
returns, even if it throws. The `EmuResponse` struct itself lives in that arena; only the two buffers it points to are
`emu-core`'s.

## The response layout

On a 64-bit platform `EmuResponse` is 40 bytes. `EmuCore` declares the same layout with `MemoryLayout.structLayout`
and reads the fields at fixed offsets:

| Offset | Field | Type |
|---:|---|---|
| 0 | `status` | `uint16_t` |
| 2 | padding | 6 bytes |
| 8 | `headers` | pointer |
| 16 | `headers_len` | `size_t` |
| 24 | `body` | pointer |
| 32 | `body_len` | `size_t` |

## Framing headers

Headers cross as text, one `name: value\r\n` line each, so a header that would break a line is refused before it
crosses: `EmuCore` throws `IllegalArgumentException` for a name containing `\r`, `\n` or `:`, or a value containing
`\r` or `\n`. Otherwise a value such as `"a\r\nx-injected: b"` would arrive as two headers.

On the way back, header names are lower-cased and a repeated header keeps every value:
`EmuReply.headers: Map[String, List[String]]`.

## Failure

Nothing unwinds across the ABI. `emu_new` and `emu_dispatch` catch panics: a panic while starting returns null, a
panic while reading the request returns -1, and a panic inside a service becomes a `500` response whose body says so.
On the JVM side, null becomes `IllegalStateException("the emulator could not start")` and -1 becomes
`IllegalArgumentException("the emulator could not read the request: …")`.
