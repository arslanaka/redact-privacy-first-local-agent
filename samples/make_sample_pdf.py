"""Generates sample-medical-referral.pdf with a real text layer, using only the Python standard library.

All content is fictional. Run: python3 make_sample_pdf.py
"""
from pathlib import Path

LINES = [
    "Riverside Health Clinic - Referral Letter",
    "",
    "Date: 18 August 2025",
    "Patient: Emma Richardson",
    "Date of birth: 03/11/1979",
    "Patient email: emma.richardson@example.com",
    "Phone: +44 20 7946 0321",
    "Address: 14 Alder Close, Bristol BS8 1TH",
    "NHS number: 485 777 3456",
    "",
    "Dear Dr Samuel Ortiz,",
    "",
    "I am referring the above patient for a follow-up consultation regarding",
    "recurring migraines. Please contact her directly to arrange an appointment.",
    "Payment for private treatment may be taken from card 5555 5555 5555 4444.",
    "",
    "Kind regards,",
    "Dr Helen Marsh, Riverside Health Clinic",
]


def escape(s: str) -> str:
    return s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")


def build_pdf(lines: list[str]) -> bytes:
    stream_lines = ["BT", "/F1 11 Tf", "14 TL", "56 780 Td"]
    for line in lines:
        stream_lines.append(f"({escape(line)}) Tj T*")
    stream_lines.append("ET")
    stream = "\n".join(stream_lines).encode("latin-1")
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R "
        b"/Resources << /Font << /F1 5 0 R >> >> >>",
        b"<< /Length %d >>\nstream\n" % len(stream) + stream + b"\nendstream",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
    ]
    out = bytearray(b"%PDF-1.4\n")
    offsets = []
    for i, body in enumerate(objects, start=1):
        offsets.append(len(out))
        out += b"%d 0 obj\n" % i + body + b"\nendobj\n"
    xref = len(out)
    out += b"xref\n0 %d\n0000000000 65535 f \n" % (len(objects) + 1)
    for off in offsets:
        out += b"%010d 00000 n \n" % off
    out += b"trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (len(objects) + 1, xref)
    return bytes(out)


if __name__ == "__main__":
    target = Path(__file__).with_name("sample-medical-referral.pdf")
    target.write_bytes(build_pdf(LINES))
    print(f"wrote {target}")
