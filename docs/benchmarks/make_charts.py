"""Speed charts for README: stock Tracker vs this build.

Reads sweep.csv (from java/bench/.../SweepBench) and writes SVG charts plus an
index.html with a table view. Standard library only.

    python make_charts.py sweep.csv
"""
import csv
import math
import sys
from html import escape
from pathlib import Path

OUT = Path(__file__).parent

# validated two-slot palette (dataviz reference palette, slots 1-2), light / dark
CSS = """
<style>
.s { --surface:#fcfcfb; --ink:#0b0b0b; --ink2:#52514e; --grid:#e6e5e0; --new:#2a78d6; --old:#eb6834; }
@media (prefers-color-scheme: dark) {
  .s { --surface:#1a1a19; --ink:#f0efec; --ink2:#c3c2b7; --grid:#33332f; --new:#3987e5; --old:#d95926; }
}
.s text { font-family: 'Segoe UI', system-ui, -apple-system, sans-serif; fill: var(--ink2); font-size: 12px; }
.s .t { fill: var(--ink); font-size: 16px; font-weight: 600; }
.s .lab { fill: var(--ink); font-size: 12px; }
.s .big { fill: var(--ink); font-size: 13px; font-weight: 600; }
.s .g { stroke: var(--grid); stroke-width: 1; }
.s .ln { fill: none; stroke-width: 2; stroke-linejoin: round; stroke-linecap: round; }
.s .new { stroke: var(--new); } .s .old { stroke: var(--old); }
.s .dn { fill: var(--new); stroke: var(--surface); stroke-width: 2; }
.s .do { fill: var(--old); stroke: var(--surface); stroke-width: 2; }
.s .bn { fill: var(--new); } .s .bo { fill: var(--old); }
</style>
"""


def fmt_ms(v):
    if v >= 1000:
        return f"{v / 1000:.2f} s"
    if v >= 10:
        return f"{v:.0f} ms"
    if v >= 1:
        return f"{v:.1f} ms"
    return f"{v:.2f} ms"


def line_chart(name, title, subtitle, xlabel, xs, old, new):
    W, H = 760, 430
    L, R, T, B = 74, 200, 92, 52
    pw, ph = W - L - R, H - T - B
    lo = 10 ** math.floor(math.log10(min(new + old)))
    hi = 10 ** math.ceil(math.log10(max(new + old)))
    # log x: the sweep values span 20x, a linear axis crowds the small ones together
    x0, x1 = math.log10(min(xs)), math.log10(max(xs))
    X = lambda x: L + (math.log10(x) - x0) / (x1 - x0) * pw
    Y = lambda v: T + ph - (math.log10(v) - math.log10(lo)) / (math.log10(hi) - math.log10(lo)) * ph
    o = [f'<svg xmlns="http://www.w3.org/2000/svg" class="s" viewBox="0 0 {W} {H}" width="{W}" height="{H}" style="max-width:100%;height:auto" role="img" aria-label="{escape(title)}">',
         CSS, f'<rect width="{W}" height="{H}" fill="var(--surface)"/>',
         f'<text class="t" x="{L}" y="28">{escape(title)}</text>',
         f'<text x="{L}" y="48">{escape(subtitle)}</text>']
    # legend row
    o.append(f'<rect x="{L}" y="62" width="14" height="4" rx="2" class="bo"/><text x="{L + 20}" y="68">Original Tracker 6.3.5 (Java)</text>')
    o.append(f'<rect x="{L + 230}" y="62" width="14" height="4" rx="2" class="bn"/><text x="{L + 250}" y="68">This build (Rust, Fast ticked)</text>')
    # log gridlines
    d = lo
    while d <= hi * 1.0001:
        y = Y(d)
        o.append(f'<line class="g" x1="{L}" x2="{L + pw}" y1="{y:.1f}" y2="{y:.1f}"/>')
        tick = f"{d / 1000:g} s" if d >= 1000 else f"{d:g} ms"
        o.append(f'<text x="{L - 8}" y="{y + 4:.1f}" text-anchor="end">{tick}</text>')
        d *= 10
    for x in xs:
        o.append(f'<text x="{X(x):.1f}" y="{T + ph + 18}" text-anchor="middle">{x}</text>')
    o.append(f'<text x="{L + pw / 2}" y="{H - 10}" text-anchor="middle">{escape(xlabel)}</text>')
    o.append(f'<text x="14" y="{T + ph / 2}" transform="rotate(-90 14 {T + ph / 2})" text-anchor="middle">time per frame match (log scale)</text>')
    for cls, dot, vals, lab in (("old", "do", old, "Original"), ("new", "dn", new, "This build")):
        pts = " ".join(f"{X(x):.1f},{Y(v):.1f}" for x, v in zip(xs, vals))
        o.append(f'<polyline class="ln {cls}" points="{pts}"/>')
        for x, v, ov, nv in zip(xs, vals, old, new):
            o.append(f'<circle class="{dot}" cx="{X(x):.1f}" cy="{Y(v):.1f}" r="4"><title>{lab}: {xlabel} {x} → {fmt_ms(v)}'
                     f' (×{ov / nv:.0f} faster with this build)</title></circle>')
        o.append(f'<text class="lab" x="{X(xs[-1]) + 10:.1f}" y="{Y(vals[-1]) + 4:.1f}">{lab} {fmt_ms(vals[-1])}</text>')
    sp = old[-1] / new[-1]
    ym = (Y(old[-1]) + Y(new[-1])) / 2
    o.append(f'<text class="big" x="{X(xs[-1]) + 10:.1f}" y="{ym + 4:.1f}">×{sp:,.0f} faster</text>')
    o.append('</svg>')
    (OUT / f"{name}.svg").write_text("\n".join(o), encoding="utf-8")
    return sp


def bar_chart(name, title, subtitle, rows):
    W, H = 760, 196
    L, R, T = 210, 120, 80
    pw = W - L - R
    mx = max(v for _, v, _ in rows)
    o = [f'<svg xmlns="http://www.w3.org/2000/svg" class="s" viewBox="0 0 {W} {H}" width="{W}" height="{H}" style="max-width:100%;height:auto" role="img" aria-label="{escape(title)}">',
         CSS, f'<rect width="{W}" height="{H}" fill="var(--surface)"/>',
         f'<text class="t" x="20" y="28">{escape(title)}</text>', f'<text x="20" y="48">{escape(subtitle)}</text>']
    for i, (lab, v, cls) in enumerate(rows):
        y = T + i * 56
        w = max(2, v / mx * pw)
        o.append(f'<text class="lab" x="{L - 12}" y="{y + 16}" text-anchor="end">{escape(lab)}</text>')
        o.append(f'<rect class="{cls}" x="{L}" y="{y + 2}" width="{w:.1f}" height="20" rx="4"><title>{escape(lab)}: {v:,.1f} s</title></rect>')
        txt = f"{v / 60:.1f} min" if v >= 120 else f"{v:.1f} s"
        o.append(f'<text class="big" x="{L + w + 8:.1f}" y="{y + 17}">{txt}</text>')
    o.append(f'<line class="g" x1="{L}" x2="{L}" y1="{T - 6}" y2="{T + len(rows) * 56 - 22}"/>')
    o.append('</svg>')
    (OUT / f"{name}.svg").write_text("\n".join(o), encoding="utf-8")


# per-stage cost per tracked frame, measured during this work (see README for sources):
# (stage, original technique, original ms, new technique, new ms on the event thread)
STAGES = [
    ("Find the match", "Java loop over every position", 896.0,
     "Rust AVX2 / FFT, all cores, run ahead", 2.0),
    ("Sub-pixel position", "3-point parabola", 0.3,
     "Gauss-Newton on bicubic image (AVX2)", 0.8),
    ("Decode video frame", "FFmpeg, 1 thread, on the UI thread", 7.7,
     "8-thread decoder running ahead", 0.04),
    ("Copy frame for display", "per-pixel BGR to RGB copy", 2.0,
     "decoded image reused, no copy", 0.0),
    ("Redraw screen + panels", "every frame", 5.9,
     "about 25 times a second", 1.1),
    ("Wait for video speed", "up to 42 ms: sleeps to 24 fps if faster (Fast off)", 41.7,
     "no wait", 0.0),
]


def stage_chart(name):
    rows = STAGES
    W = 900
    L, R, T, RH = 190, 30, 92, 64
    H = T + RH * len(rows) + 64
    pw = W - L - R
    lo, hi = 0.01, 1000.0
    X = lambda v: L + (math.log10(max(v, lo)) - math.log10(lo)) / (math.log10(hi) - math.log10(lo)) * pw
    o = [f'<svg xmlns="http://www.w3.org/2000/svg" class="s" viewBox="0 0 {W} {H}" width="{W}" height="{H}" style="max-width:100%;height:auto" role="img" aria-label="Where the time goes per frame">',
         CSS, f'<rect width="{W}" height="{H}" fill="var(--surface)"/>',
         '<text class="t" x="20" y="28">Where the time goes, per tracked frame</text>',
         '<text x="20" y="48">IMG_4122.mp4 1080×1920 · template ~155 px · search ~180 px · time on the UI thread (log scale)</text>',
         f'<rect x="20" y="62" width="14" height="4" rx="2" class="bo"/><text x="40" y="68">Original Tracker 6.3.5</text>',
         f'<rect x="230" y="62" width="14" height="4" rx="2" class="bn"/><text x="250" y="68">This build (Fast ticked)</text>']
    for t in (0.01, 0.1, 1, 10, 100, 1000):
        x = X(t)
        o.append(f'<line class="g" x1="{x:.1f}" x2="{x:.1f}" y1="{T - 6}" y2="{T + RH * len(rows) - 4}"/>')
        lab = f"{t / 1000:g} s" if t >= 1000 else f"{t:g} ms"
        o.append(f'<text x="{x:.1f}" y="{T + RH * len(rows) + 12}" text-anchor="middle">{lab}</text>')
    for i, (stage, ot, ov, nt, nv) in enumerate(rows):
        y = T + i * RH
        o.append(f'<text class="lab" x="{L - 12}" y="{y + 22}" text-anchor="end">{escape(stage)}</text>')
        for j, (cls, tech, v) in enumerate((("bo", ot, ov), ("bn", nt, nv))):
            yy = y + 4 + j * 26
            val = "0 (skipped)" if v == 0 else fmt_ms(v)
            if v > 0:
                w = X(v) - L
                o.append(f'<rect class="{cls}" x="{L}" y="{yy}" width="{max(w, 2):.1f}" height="16" rx="4"><title>{escape(tech)}: {val}</title></rect>')
                tx = L + max(w, 2) + 8
            else:
                tx = L + 2
            label = f"{val} · {tech}"
            # keep long labels inside the chart: put them inside the bar when the bar is long
            if v > 0 and tx + 7 * len(label) > W - R:
                o.append(f'<text x="{L + 8}" y="{yy + 12.5}" style="fill:#fff">{escape(label)}</text>')
            else:
                o.append(f'<text x="{tx:.1f}" y="{yy + 12.5}">{escape(label)}</text>')
    o.append(f'<text class="big" x="20" y="{H - 14}">Whole frame: original ≈ 931 ms (1 fps) → this build ≈ 4.7–5.4 ms (185–213 fps)</text>')
    o.append('</svg>')
    (OUT / f"{name}.svg").write_text(chr(10).join(o), encoding="utf-8")


def main(path):
    rows = list(csv.DictReader(open(path, encoding="utf-8")))

    def series(sweep, key, fixed_key, fixed):
        pts = {}
        for r in rows:
            if r["sweep"] == sweep and int(r[fixed_key]) == fixed:
                pts.setdefault(int(r[key]), {})[r["mode"]] = float(r["ms_median"])
        xs = sorted(pts)
        return xs, [pts[x]["stock"] for x in xs], [pts[x]["new"] for x in xs]

    charts = []
    for t in (161, 41):
        xs, old, new = series("search", "search", "template", t)
        sp = line_chart(f"search_t{t}", f"Search area vs speed (template {t}×{t} px)",
                        "One frame match on a 1080×1920 frame of IMG_4122.mp4 · median · i5-13450HX",
                        "search area side (px, log scale)", xs, old, new)
        charts.append((f"search_t{t}", xs, old, new, "search side", sp))
    xs, old, new = series("template", "template", "search", 181)
    sp = line_chart("template_s181", "Template size vs speed (search area 181×181 px)",
                    "One frame match on a 1080×1920 frame of IMG_4122.mp4 · median · i5-13450HX",
                    "template side (px, log scale)", xs, old, new)
    charts.append(("template_s181", xs, old, new, "template side", sp))
    stage_chart("stages")
    bar_chart("whole_clip", "Autotracking the whole clip (721 frames)",
              "IMG_4122.mp4 · template 154×152 · search ~180×180 · Evolve 20% · original: 931 ms/frame measured on 40 frames, × 721",
              [("Original Tracker 6.3.5", 721 * 0.93113, "bo"), ("This build (Fast ticked)", 3.83, "bn")])

    html = ["<!doctype html><meta charset='utf-8'><title>Tracker-Rust speed</title>",
            "<style>body{font-family:'Segoe UI',system-ui,sans-serif;max-width:800px;margin:24px auto;padding:0 12px;}"
            "table{border-collapse:collapse;margin:8px 0 28px}td,th{padding:3px 10px;text-align:right;border-bottom:1px solid #ddd}"
            "@media (prefers-color-scheme: dark){body{background:#1a1a19;color:#f0efec}td,th{border-color:#33332f}}</style>",
            "<h1>Tracker-Rust: speed vs original Tracker</h1>",
            "<p>Hover a point for its value. Whole clip: original measured on 40 frames (931 ms/frame) "
            "and scaled to 721; this build measured over the whole clip.</p>",
            (OUT / "whole_clip.svg").read_text(encoding="utf-8"),
            (OUT / "stages.svg").read_text(encoding="utf-8")]
    for name, xs, old, new, xl, _ in charts:
        html.append((OUT / f"{name}.svg").read_text(encoding="utf-8"))
        html.append(f"<table><tr><th>{xl} (px)</th><th>original</th><th>this build</th><th>faster</th></tr>")
        for x, a, b in zip(xs, old, new):
            html.append(f"<tr><td>{x}</td><td>{fmt_ms(a)}</td><td>{fmt_ms(b)}</td><td>×{a / b:,.0f}</td></tr>")
        html.append("</table>")
    (OUT / "index.html").write_text("\n".join(html), encoding="utf-8")
    for name, xs, old, new, xl, sp in charts:
        print(name, " ".join(f"{x}:{a / b:.0f}x" for x, a, b in zip(xs, old, new)))


if __name__ == "__main__":
    main(sys.argv[1])
