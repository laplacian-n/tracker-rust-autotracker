"""Printable checkerboards for Coordinate System > Calibrate from Pattern.

    python docs/calibration/make_patterns.py

Writes A4 landscape PDFs (vector, no dependencies) next to this file. Each has a
100 mm check line: print at "Actual size" / 100 %, then measure it.
"""
from pathlib import Path

MM = 72 / 25.4  # PDF points per mm
A4 = (297.0, 210.0)  # landscape, mm

PATTERNS = [
    # file, squares across, squares down, square mm
    ("checkerboard_A4_20mm.pdf", 10, 7, 20.0),
    ("checkerboard_A4_30mm.pdf", 8, 5, 30.0),
    ("checkerboard_A4_15mm.pdf", 14, 9, 15.0),
]


def pdf(path, width_mm, height_mm, content):
    objs = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 {width_mm * MM:.2f} {height_mm * MM:.2f}] "
        f"/Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>".encode(),
        b"<< /Length %d >>\nstream\n" % len(content) + content + b"\nendstream",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    ]
    out = bytearray(b"%PDF-1.4\n")
    offsets = []
    for i, o in enumerate(objs, 1):
        offsets.append(len(out))
        out += b"%d 0 obj\n" % i + o + b"\nendobj\n"
    xref = len(out)
    out += b"xref\n0 %d\n0000000000 65535 f \n" % (len(objs) + 1)
    for off in offsets:
        out += b"%010d 00000 n \n" % off
    out += b"trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (len(objs) + 1, xref)
    path.write_bytes(bytes(out))


def text(x, y, size, s):
    s = s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
    return f"BT /F1 {size} Tf {x * MM:.2f} {y * MM:.2f} Td ({s}) Tj ET\n"


def board(nx, ny, sq):
    w, h = A4
    bw, bh = nx * sq, ny * sq
    x0 = (w - bw) / 2
    y0 = (h - bh) / 2 + 6  # room for the notes below
    c = ["0 g\n"]
    for j in range(ny):
        for i in range(nx):
            if (i + j) % 2 == 0:
                c.append(f"{(x0 + i * sq) * MM:.3f} {(y0 + j * sq) * MM:.3f} {sq * MM:.3f} {sq * MM:.3f} re f\n")
    # 100 mm check line with end ticks
    ly = y0 - 9
    lx = (w - 100) / 2
    c.append(f"0.6 w {lx * MM:.3f} {ly * MM:.3f} m {(lx + 100) * MM:.3f} {ly * MM:.3f} l S\n")
    for t in (lx, lx + 100):
        c.append(f"{t * MM:.3f} {(ly - 2) * MM:.3f} m {t * MM:.3f} {(ly + 2) * MM:.3f} l S\n")
    c.append(text(lx + 100 + 3, ly - 1.2, 9, "100 mm"))
    c.append(text(x0, ly - 8, 9,
                  f"Squares {sq:g} mm ({nx - 1} x {ny - 1} inner corners). Print at Actual size / 100 %, "
                  f"check the line is 100 mm, keep the white margin."))
    c.append(text(x0, ly - 13, 9,
                  "Tracker-Rust: Coordinate System > Calibrate from Pattern. Lay it flat in the plane of the motion."))
    return "".join(c).encode()


if __name__ == "__main__":
    here = Path(__file__).parent
    for name, nx, ny, sq in PATTERNS:
        pdf(here / name, *A4, board(nx, ny, sq))
        print(name)
