"""Build beatmind-registers.xlsx from the CSV registers in this folder.

Run: python3 build_workbook.py
"""
import csv
from pathlib import Path

from openpyxl import Workbook
from openpyxl.styles import Font, PatternFill, Alignment
from openpyxl.utils import get_column_letter

HERE = Path(__file__).parent
SHEETS = [
    ("Assumptions", "assumptions.csv"),
    ("Risks", "risks.csv"),
    ("Sources", "sources.csv"),
    ("Launch markets", "launch-market-scores.csv"),
    ("Review findings", "review-findings.csv"),
    ("Compliance", "../05-compliance/compliance-register.csv"),
    ("RTM", "../07-quality/rtm.csv"),
    ("Entitlements", "../02-product/entitlement-matrix.csv"),
]
WEIGHTS = [0.20, 0.15, 0.20, 0.10, 0.10, 0.10, 0.15]
HEADER_FILL = PatternFill("solid", fgColor="1F3A5F")
HEADER_FONT = Font(bold=True, color="FFFFFF")


def add_sheet(wb, title, path):
    ws = wb.create_sheet(title)
    with open(path, newline="", encoding="utf-8") as f:
        rows = list(csv.reader(f))
    for row in rows:
        ws.append([int(v) if v.isdigit() else v for v in row])
    for cell in ws[1]:
        cell.fill, cell.font = HEADER_FILL, HEADER_FONT
    for i, col in enumerate(ws.columns, start=1):
        width = min(60, max(len(str(c.value or "")) for c in col) + 2)
        ws.column_dimensions[get_column_letter(i)].width = width
        for c in col:
            c.alignment = Alignment(wrap_text=True, vertical="top")
    ws.freeze_panes = "A2"
    return ws, len(rows)


def add_market_formulas(ws, n_rows):
    # Weights row below the data, and a weighted-score column that recalculates.
    weight_row = n_rows + 2
    ws.cell(weight_row, 1, "weight")
    for j, w in enumerate(WEIGHTS, start=2):
        ws.cell(weight_row, j, w)
    score_col = len(WEIGHTS) + 2
    ws.cell(1, score_col, "weighted_score").font = HEADER_FONT
    ws.cell(1, score_col).fill = HEADER_FILL
    last = get_column_letter(len(WEIGHTS) + 1)
    for r in range(2, n_rows + 1):
        ws.cell(r, score_col, f"=SUMPRODUCT(B{r}:{last}{r},$B${weight_row}:${last}${weight_row})")


def main():
    wb = Workbook()
    wb.remove(wb.active)
    for title, name in SHEETS:
        ws, n = add_sheet(wb, title, HERE / name)
        if title == "Launch markets":
            add_market_formulas(ws, n)
    wb.save(HERE / "beatmind-registers.xlsx")


if __name__ == "__main__":
    main()
