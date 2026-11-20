"""The hero: three SDK calls, each a single in-process trip through the binding, across the C ABI to the router."""

from hud import AMBER, BLUE, CYAN, EDGE, GREEN, PINK, TEXT, Scene

RAIL_Y = 205
SDK, HTTP, CORE, ROUTER = (36, 150), (212, 150), (388, 150), (592, 146)
ABI_X = 566
SERVICES = {"DynamoDB": (790, 120), "S3": (790, 205), "KMS": (790, 290)}
SVC_X, SVC_W = 788, 134
NODE_H = 58
OUT_Y, BACK_Y = RAIL_Y - 50, RAIL_Y + 50  # requests travel above the boxes, replies below
STEP = 0.95  # seconds per hop, long enough to read the caption


def centre(node) -> float:
    x, w = node
    return x + w / 2


def build() -> Scene:
    T = 19.5
    sc = Scene(
        950, 470, T,
        "aws-emulator-jvm",
        "one process · no socket · no TLS · no container",
        "an SDK call is one emu_dispatch: method, URI, header lines and body cross as bytes; the reply comes back in it",
        "An AWS SDK client built with EmuHttpClient sends each request through EmuCore, across the emu-core C ABI, to "
        "the aws-emulator router, which hands it to DynamoDB, S3 or KMS and returns the response in the same call.",
    )
    sc.add(sc.text(SDK[0], 88, "JVM", "tiny"), sc.text(ABI_X + 14, 88, "emu-core · Rust", "tiny"))
    # The process boundary is the C ABI, not a socket
    sc.add(f'<line x1="{ABI_X}" y1="76" x2="{ABI_X}" y2="356" stroke="{EDGE}" stroke-width="1.5" '
           f'stroke-dasharray="5 5"/>',
           sc.text(ABI_X, 372, "C ABI", "tag", BLUE, "middle"))

    sc.add(sc.rail(SDK[0] + SDK[1], RAIL_Y, ROUTER[0], BLUE))
    rx = ROUTER[0] + ROUTER[1]
    for name, (_, y) in SERVICES.items():
        sc.add(f'<path d="M{rx} {RAIL_Y} C{rx + 40} {RAIL_Y} {SVC_X - 40} {y} {SVC_X} {y}" class="rail" '
               f'fill="none"/>')
    nodes = [(SDK, "SDK client", "DynamoDbClient …"), (HTTP, "EmuHttpClient", "SdkHttpClient SPI"),
             (CORE, "EmuCore", "FFM downcall"), (ROUTER, "router", "MultiServiceRouter")]
    for (x, w), title, sub in nodes:
        sc.add(sc.node(x, RAIL_Y - NODE_H / 2, w, NODE_H, title, sub))
    for name, (x, y) in SERVICES.items():
        sc.add(sc.node(SVC_X, y - 24, SVC_W, 48, name))
    sc.add(sc.text(SVC_X + SVC_W, 346, "+ IAM, STS · in-memory engine", "tiny", anchor="end"))

    calls = [
        ("PutItem", CYAN, "DynamoDB", 0.3),
        ("PutObject", AMBER, "S3", 6.7),
        ("Encrypt", PINK, "KMS", 13.1),
    ]
    stops = [centre(SDK), centre(HTTP), centre(CORE), ABI_X, centre(ROUTER)]
    captions = [
        "the SDK signs and builds an HTTP request, as it would for AWS",
        "the binding reads the body and frames each header as one name: value line",
        "a confined arena holds the bytes; emu_dispatch hands them over",
        "the router picks the service from the request, as the standalone server does",
    ]
    for label, color, service, t0 in calls:
        sx, sy = SERVICES[service]
        w = 14 + len(label) * 7
        out = [(t0, stops[0], OUT_Y, 0), (t0 + 0.2, stops[0], OUT_Y)]
        for i, x in enumerate(stops[1:], start=1):
            out.append((t0 + 0.2 + i * STEP, x, OUT_Y))
        out += [(t0 + 4.4, SVC_X + 20, sy), (t0 + 4.6, SVC_X + 20, sy, 0)]
        sc.add(sc.chip(label, color, sc.path(out), w=w))
        sc.add(sc.pulse(SVC_X + SVC_W / 2, sy, color, t0 + 4.45, r=16))
        back = [(t0 + 4.7, SVC_X + 20, sy, 0), (t0 + 4.85, SVC_X + 20, sy), (t0 + 5.3, stops[-1], BACK_Y),
                (t0 + 6.0, stops[0], BACK_Y), (t0 + 6.2, stops[0], BACK_Y, 0)]
        sc.add(sc.chip("200", GREEN, sc.path(back), w=34))
        for i, caption in enumerate(captions):
            on = t0 + 0.2 + i * STEP
            sc.add(sc.text(SDK[0], 410, caption, "hud", TEXT, style=sc.shown([(on, on + STEP)], fade=0.1)))
        sc.add(sc.text(SDK[0], 410, f"{service} answers; the same call returns status, header lines and body",
                       "hud", GREEN, style=sc.shown([(t0 + 4.4, t0 + 6.2)], fade=0.1)))
    return sc
