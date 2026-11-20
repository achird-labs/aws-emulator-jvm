"""One emu_dispatch, by memory: who allocates each buffer, who reads it, and who frees it, in order."""

from hud import AMBER, BLUE, CYAN, EDGE, GREEN, PINK, TEXT, Scene

ABI_X = 520
LEFT, RIGHT = 40, 560
STEPS = [  # (time, caption)
    (0.4, "1 · EmuCore allocates method, URI, header lines and body in a confined arena"),
    (2.6, "2 · emu_dispatch: emu-core reads them in place and copies the body into the request"),
    (4.6, "3 · the router answers; emu-core leaks the header lines and body as boxed slices"),
    (6.4, "4 · emu-core writes status and both pointers into EmuResponse, inside the arena"),
    (8.4, "5 · EmuCore copies both buffers onto the JVM heap as an EmuReply"),
    (10.2, "6 · emu_response_free: emu-core takes its buffers back and nulls the pointers"),
    (12.0, "7 · the arena closes; only the EmuReply is left, owned by the garbage collector"),
]
END = 14.6


def block(sc, x, y, w, label, color, on, off, h=30):
    style = sc.shown([(on, off)], fade=0.25)
    return (
        f'<g style="{style}"><rect x="{x}" y="{y}" width="{w}" height="{h}" rx="5" fill="{color}" fill-opacity="0.16" '
        f'stroke="{color}" stroke-width="1.4" filter="url(#glow)"/>'
        f'<text x="{x + w / 2}" y="{y + h / 2 + 4}" class="chip" text-anchor="middle" fill="{color}">{label}</text></g>'
    )


def build() -> Scene:
    T = 16.0
    sc = Scene(
        950, 520, T,
        "emu_dispatch · who owns which bytes",
        "JVM arena · Rust heap · JVM heap",
        "every buffer has one owner at a time: the JVM frees what it allocated, and emu-core frees what it leaked",
        "One emu_dispatch call by memory: the JVM allocates the request in a confined arena, emu-core leaks the "
        "response buffers and writes their addresses into EmuResponse, the JVM copies them to its heap, then "
        "emu_response_free and the arena's close release each side's memory.",
    )
    sc.add(sc.text(LEFT, 88, "JVM · CONFINED ARENA", "tiny", CYAN), sc.text(RIGHT, 88, "emu-core · RUST HEAP", "tiny", AMBER),
           sc.text(LEFT, 372, "JVM · GC HEAP", "tiny", GREEN))
    sc.add(f'<line x1="{ABI_X}" y1="76" x2="{ABI_X}" y2="340" stroke="{EDGE}" stroke-width="1.5" stroke-dasharray="5 5"/>',
           sc.text(ABI_X, 356, "C ABI", "tag", BLUE, "middle"))

    # 1: request bytes in the arena
    req = [("method", 64), ("uri", 64), ("header lines", 120), ("body", 120)]
    x = LEFT
    for label, w in req:
        sc.add(block(sc, x, 108, w, label, CYAN, 0.4, 12.2))
        x += w + 10
    # 2: the call crosses; emu-core reads in place and owns a copy of the body only for the call
    sc.add(f'<path d="M{LEFT + 200} 150 C{LEFT + 260} 190 {ABI_X - 60} 190 {RIGHT + 30} 150" class="arrow" '
           f'stroke="{BLUE}" style="{sc.shown([(2.6, 4.6)])}"/>',
           sc.text(ABI_X, 210, "emu_dispatch(…, out)", "hud", BLUE, "middle", sc.shown([(2.6, 6.6)])))
    sc.add(block(sc, RIGHT, 108, 170, "http::Request&lt;Bytes&gt;", AMBER, 2.8, 4.8))
    sc.add(sc.pulse(RIGHT + 300, 123, PINK, 4.4, r=14), sc.text(RIGHT + 300, 158, "router", "tiny", anchor="middle"))
    # 3: response buffers leaked for the host
    sc.add(block(sc, RIGHT, 240, 150, "headers · Box&lt;[u8]&gt;", AMBER, 4.6, 10.4),
           block(sc, RIGHT + 165, 240, 150, "body · Box&lt;[u8]&gt;", AMBER, 4.6, 10.4))
    # 4: EmuResponse, laid out field by field in the arena, pointing across
    fields = [("status", 0, 46), ("pad", 2, 30), ("headers*", 8, 84), ("headers_len", 16, 84), ("body*", 24, 84),
              ("body_len", 32, 84)]
    x = LEFT
    cells = {}
    for name, offset, w in fields:
        color = TEXT if name != "pad" else EDGE
        sc.add(block(sc, x, 260, w, name, color if name != "pad" else EDGE, 6.4, 12.2, h=28),
               sc.text(x + 2, 304, str(offset), "tiny", style=sc.shown([(6.4, 12.2)])))
        cells[name] = x + w / 2
        x += w + 4
    sc.add(sc.text(LEFT, 326, "EmuResponse · 40 bytes · offsets in bytes", "tiny", TEXT, style=sc.shown([(6.4, 12.2)])))
    for name, target in [("headers*", RIGHT + 75), ("body*", RIGHT + 240)]:
        sc.add(f'<path d="M{cells[name]} 260 C{cells[name]} 220 {target} 200 {target} 240" class="arrow" '
               f'stroke="{AMBER}" stroke-dasharray="3 4" style="{sc.shown([(6.6, 10.4)])}"/>')
    # 5: copies on the JVM heap
    sc.add(block(sc, LEFT, 384, 110, "status", GREEN, 8.4, END),
           block(sc, LEFT + 120, 384, 170, "headers: Map", GREEN, 8.4, END),
           block(sc, LEFT + 300, 384, 150, "body: Array[Byte]", GREEN, 8.4, END),
           sc.text(LEFT + 470, 403, "EmuReply", "tag", GREEN, style=sc.shown([(8.4, END)])))
    # 6, 7: frees
    sc.add(sc.text(RIGHT, 300, "emu_response_free", "tag", PINK, style=sc.shown([(10.2, 11.6)])),
           sc.text(LEFT + 470, 125, "arena.close()", "tag", PINK, style=sc.shown([(12.0, 13.4)])))

    for i, (on, caption) in enumerate(STEPS):
        off = STEPS[i + 1][0] if i + 1 < len(STEPS) else END
        sc.add(sc.text(LEFT, 446, caption, "log", TEXT, style=sc.shown([(on, off)], fade=0.15)))
    return sc
