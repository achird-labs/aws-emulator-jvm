"""EmuCore.close against running calls: dispatch holds a read lock, close takes the write lock, later calls fail."""

from hud import AMBER, CYAN, DIM, EDGE, GREEN, PINK, RED, TEXT, Scene

X0, X1 = 190, 900          # the time axis
SWEEP = 9.0                # seconds the cursor takes to cross it
ROW0, ROW_GAP = 112, 52


def at(t: float) -> float:
    return X0 + (X1 - X0) * t / SWEEP


def row(i: int) -> float:
    return ROW0 + i * ROW_GAP


def build() -> Scene:
    T = 12.0
    sc = Scene(
        950, 470, T,
        "EmuCore · dispatch and close",
        "ReentrantReadWriteLock · one emulator, many threads",
        "dispatch holds the read lock; close takes the write lock, waits out running calls, frees once; later calls fail",
        "Threads A and B dispatch at the same time under the read lock. close() asks for the write lock at once, waits "
        "until both calls finish, and frees the emulator. Thread C, which arrived while close was waiting, and thread D, "
        "which arrived after, both get IllegalStateException instead of touching freed memory.",
    )
    labels = ["thread A", "thread B", "close()", "thread C", "thread D"]
    for i, label in enumerate(labels):
        y = row(i)
        sc.add(sc.text(40, y + 4, label, "key" if label == "close()" else "hud", TEXT if label == "close()" else None),
               f'<line x1="{X0}" y1="{y}" x2="{X1}" y2="{y}" stroke="{EDGE}" stroke-dasharray="2 6"/>')
    H = 22

    def bar(i, start, end, color, dashed=False):
        sc.add(sc.grow(at(start), row(i) - H / 2, at(end) - at(start), H, color, start, end, dashed=dashed))

    def badge(i, t, s, color):
        sc.add(sc.badge(at(t) + 6, row(i) + 1, s, color, sc.shown([(t, SWEEP + 2.4)], fade=0.2)))

    bar(0, 0.4, 3.6, CYAN)
    badge(0, 3.6, "200", GREEN)
    bar(1, 1.0, 5.0, CYAN)
    badge(1, 5.0, "200", GREEN)
    # close asks at 3.0, waits for B, then frees
    bar(2, 3.0, 5.0, AMBER, dashed=True)
    bar(2, 5.0, 5.6, PINK)
    badge(2, 5.6, "emu_free", PINK)
    # C queues behind the waiting writer, then finds the emulator closed
    bar(3, 4.0, 5.6, DIM, dashed=True)
    badge(3, 5.6, "IllegalStateException", RED)
    badge(4, 7.0, "IllegalStateException", RED)
    sc.add(sc.pulse(at(7.0), row(4), RED, 7.0, r=8))

    sc.add(sc.text(at(1.9), row(0) - 18, "read lock", "tiny", CYAN, "middle", sc.shown([(0.6, SWEEP + 2.4)])),
           sc.text(at(4.0), row(2) - 18, "waits for the write lock", "tiny", AMBER, "middle",
                   sc.shown([(3.2, SWEEP + 2.4)])),
           sc.text(at(4.8), row(3) - 18, "queued behind close", "tiny", None, "middle", sc.shown([(4.2, SWEEP + 2.4)])))

    cursor = sc.path([(0, X0, 0), (SWEEP, X1, 0), (SWEEP + 2.4, X1, 0), (SWEEP + 2.6, X1, 0, 0), (T, X0, 0, 0)],
                     ease="linear")
    sc.add(f'<g style="{cursor}"><line x1="0" y1="84" x2="0" y2="{row(4) + 24}" stroke="{TEXT}" stroke-opacity=".35"/>'
           f'<text x="0" y="{row(4) + 40}" class="tiny" text-anchor="middle">now</text></g>')
    return sc
