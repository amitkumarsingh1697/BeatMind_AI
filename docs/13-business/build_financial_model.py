"""Build financial-model.xlsx: inputs, price bands, unit economics, 12-month scenarios, budgets.

Every input cell carries an evidence tag. Change inputs in the workbook; all outputs are formulas.
Run: python3 build_financial_model.py
"""
from pathlib import Path

from openpyxl import Workbook
from openpyxl.styles import Font, PatternFill
from openpyxl.utils import get_column_letter

HERE = Path(__file__).parent
HEAD = PatternFill("solid", fgColor="1F3A5F")
INPUT = PatternFill("solid", fgColor="FFF2CC")
WHITE = Font(bold=True, color="FFFFFF")
BOLD = Font(bold=True)


def header(ws, row, values):
    for j, v in enumerate(values, start=1):
        c = ws.cell(row, j, v)
        c.fill, c.font = HEAD, WHITE


def widths(ws, w):
    for i, x in enumerate(w, start=1):
        ws.column_dimensions[get_column_letter(i)].width = x


def build_inputs(wb):
    ws = wb.create_sheet("Inputs")
    ws["A1"] = "Inputs (yellow cells are editable). Tags: [A] assumption, [H] hypothesis, [E] estimate, [C] calculated, [ES] externally sourced, [SS] source-stated."
    header(ws, 2, ["Input", "Value", "Unit", "Tag", "Basis"])
    rows = [
        ("USD to INR exchange rate", 88, "INR per USD", "[A]", "Update monthly from RBI reference rate"),
        ("GST on Indian subscriptions", 0.18, "rate", "[A]", "Suno India prices include 18% GST (SRC-111); confirm with tax adviser (COMP-IN-07)"),
        ("Razorpay fee", 0.02, "share of charge", "[A]", "Confirm with Razorpay pricing (C-04)"),
        ("Merchant-of-record fee (percentage)", 0.05, "share of charge", "[A]", "Confirm with provider (ADR-009)"),
        ("Merchant-of-record fee (fixed)", 0.50, "USD per charge", "[A]", "Confirm with provider"),
        ("GPU cost per take, self-hosted", 0.004, "USD", "[C]", "TDD 9.1: 0.002 to 0.006 at 25% utilisation"),
        ("Lyria fallback cost per take", 0.08, "USD", "[ES]", "SRC-115"),
        ("Share of paid takes on fallback", 0.0, "share", "[H]", "Recommended 0% in India at launch; raise only if Hindi fails G1"),
        ("Storage and delivery per kept song per month", 0.0005, "USD", "[E]", "R2 free tier first (A-029)"),
        ("Free user takes per active month", 20, "takes", "[H]", "Daily caps limit this"),
        ("Paid usage as share of allowance", 0.35, "share", "[H]", "To measure in beta"),
        ("Monthly free-user churn", 0.40, "share", "[H]", ""),
        ("Monthly paid churn", 0.08, "share", "[H]", ""),
        ("Founders' monthly cash budget before funding", 5000, "INR", "[SS]", "DEC-023: ₹5,000 to ₹10,000; floor used"),
    ]
    for i, (name, val, unit, tag, basis) in enumerate(rows, start=3):
        ws.cell(i, 1, name)
        c = ws.cell(i, 2, val)
        c.fill = INPUT
        ws.cell(i, 3, unit)
        ws.cell(i, 4, tag)
        ws.cell(i, 5, basis)
    widths(ws, [44, 12, 18, 7, 70])
    return ws


# Input cell addresses (row numbers fixed by build_inputs order)
FX, GST, RZP, MOR_P, MOR_F, GPU, LYRIA, FALLBACK, STORE, FREE_TAKES, USAGE, FREE_CHURN, PAID_CHURN, BUDGET = (
    f"Inputs!$B${r}" for r in range(3, 17)
)


def build_plans(wb):
    ws = wb.create_sheet("Plans")
    ws["A1"] = "Base prices (US band) and allowances [H]. Local prices are rounded price points; India shown in INR including GST."
    header(ws, 2, ["Plan", "Base US price (USD/month)", "Takes allowance per month", "Tag"])
    for i, (p, price, takes) in enumerate([("Creator", 8, 300), ("Pro", 18, 800)], start=3):
        ws.cell(i, 1, p)
        ws.cell(i, 2, price).fill = INPUT
        ws.cell(i, 3, takes).fill = INPUT
        ws.cell(i, 4, "[H]")

    header(ws, 6, ["Band", "Factor", "Example countries", "Creator (USD equiv.)", "Pro (USD equiv.)"])
    bands = [
        ("B1", 1.0, "US, UK, EU, Australia, Japan"),
        ("B2", 0.6, "Mexico, South Korea, Gulf"),
        ("B3", 0.35, "Brazil, Indonesia"),
        ("B4", 0.2, "India, Philippines, Nepal, Turkey"),
        ("B5", 0.12, "Pakistan, Bangladesh, Egypt, Nigeria"),
    ]
    for i, (b, f, ex) in enumerate(bands, start=7):
        ws.cell(i, 1, b)
        ws.cell(i, 2, f).fill = INPUT
        ws.cell(i, 3, ex)
        ws.cell(i, 4, f"=$B$3*B{i}")
        ws.cell(i, 5, f"=$B$4*B{i}")

    header(ws, 13, ["India price points (INR incl. GST)", "Creator", "Pro", "Basis"])
    ws.cell(14, 1, "Formula price (band B4 x base x FX)")
    ws.cell(14, 2, f"=D10*{FX}")
    ws.cell(14, 3, f"=E10*{FX}")
    ws.cell(14, 4, "[C]")
    ws.cell(15, 1, "Chosen price point")
    ws.cell(15, 2, 149).fill = INPUT
    ws.cell(15, 3, 349).fill = INPUT
    ws.cell(15, 4, "[H] Rounded; Spotify India ₹199 (SRC-116); Suno Pro India ₹999 (SRC-111)")
    widths(ws, [36, 24, 26, 26, 60])
    return ws


def build_unit_economics(wb):
    ws = wb.create_sheet("Unit economics")
    ws["A1"] = "Contribution per paying user per month (USD). India uses Razorpay and GST-inclusive prices; band B1 uses a merchant of record with tax added on top."
    header(ws, 2, ["Line", "India Creator", "India Pro", "B1 Creator", "B1 Pro"])
    lines = [
        ("List price (local)", "=Plans!B15", "=Plans!C15", "=Plans!D7", "=Plans!E7"),
        ("Net of GST (USD)", f"=B3/(1+{GST})/{FX}", f"=C3/(1+{GST})/{FX}", "=D3", "=E3"),
        ("Payment fees (USD)", f"=B4*{RZP}", f"=C4*{RZP}", f"=D4*{MOR_P}+{MOR_F}", f"=E4*{MOR_P}+{MOR_F}"),
        ("Net revenue (USD)", "=B4-B5", "=C4-C5", "=D4-D5", "=E4-E5"),
        ("Takes used", f"=Plans!$C$3*{USAGE}", f"=Plans!$C$4*{USAGE}", f"=Plans!$C$3*{USAGE}", f"=Plans!$C$4*{USAGE}"),
        ("Cost per take (USD)", f"=(1-{FALLBACK})*{GPU}+{FALLBACK}*{LYRIA}", "=B8", "=B8", "=B8"),
        ("Compute cost (USD)", "=B7*B8", "=C7*C8", "=D7*D8", "=E7*E8"),
        ("Storage and delivery (USD)", f"=B7*0.2*{STORE}*12", f"=C7*0.2*{STORE}*12", f"=D7*0.2*{STORE}*12", f"=E7*0.2*{STORE}*12"),
        ("Contribution (USD)", "=B6-B9-B10", "=C6-C9-C10", "=D6-D9-D10", "=E6-E9-E10"),
        ("Contribution margin", "=B11/B6", "=C11/C6", "=D11/D6", "=E11/E6"),
        ("Break-even fallback share", f"=(B6-B10-B7*{GPU})/(B7*({LYRIA}-{GPU}))", f"=(C6-C10-C7*{GPU})/(C7*({LYRIA}-{GPU}))",
         f"=(D6-D10-D7*{GPU})/(D7*({LYRIA}-{GPU}))", f"=(E6-E10-E7*{GPU})/(E7*({LYRIA}-{GPU}))"),
        ("Free user cost per active month (USD)", f"={FREE_TAKES}*{GPU}", "", "", ""),
    ]
    for i, row in enumerate(lines, start=3):
        for j, v in enumerate(row, start=1):
            ws.cell(i, j, v)
    ws.cell(15, 1, "Storage line assumes 20% of takes are kept and stored for 12 months [H].")
    widths(ws, [40, 16, 16, 16, 16])
    return ws


def build_scenarios(wb):
    ws = wb.create_sheet("Scenarios")
    ws["A1"] = "12-month scenarios from beta start. These are hypotheses for planning, not forecasts or traction. Edit row 4 to 7."
    header(ws, 3, ["Scenario input", "Low", "Base", "High", "Tag"])
    inputs = [
        ("New free sign-ups in month 1", 500, 2000, 6000, "[H]"),
        ("Monthly growth in new sign-ups", 0.05, 0.15, 0.25, "[H]"),
        ("Free-to-paid conversion per month of active free users", 0.01, 0.025, 0.05, "[H]"),
        ("Share of paid users on Pro (rest Creator)", 0.2, 0.3, 0.4, "[H]"),
    ]
    for i, row in enumerate(inputs, start=4):
        for j, v in enumerate(row, start=1):
            c = ws.cell(i, j, v)
            if 2 <= j <= 4:
                c.fill = INPUT

    # India-only economics for simplicity; monthly table per scenario
    start = 10
    for k, (name, col) in enumerate([("Low", "B"), ("Base", "C"), ("High", "D")]):
        top = start + k * 17
        ws.cell(top, 1, f"{name} scenario (India price points)").font = BOLD
        header(ws, top + 1, ["Metric"] + [f"M{m}" for m in range(1, 13)])
        labels = ["New free sign-ups", "Active free users", "New paid users", "Active paid users",
                  "Net revenue (USD)", "Paid compute and storage (USD)", "Free-tier compute (USD)",
                  "Contribution after free-tier cost (USD)", "Cumulative contribution (USD)"]
        for r, lab in enumerate(labels, start=top + 2):
            ws.cell(r, 1, lab)
        for m in range(1, 13):
            c = get_column_letter(m + 1)
            p = get_column_letter(m)
            r0 = top + 2
            new_free = f"=${col}$4*(1+${col}$5)^{m - 1}"
            active_free = f"={c}{r0}" if m == 1 else f"={p}{r0 + 1}*(1-{FREE_CHURN})+{c}{r0}"
            new_paid = f"={c}{r0 + 1}*${col}$6"
            active_paid = f"={c}{r0 + 2}" if m == 1 else f"={p}{r0 + 3}*(1-{PAID_CHURN})+{c}{r0 + 2}"
            mix_rev = f"={c}{r0 + 3}*((1-${col}$7)*'Unit economics'!$B$6+${col}$7*'Unit economics'!$C$6)"
            mix_cost = (f"={c}{r0 + 3}*((1-${col}$7)*('Unit economics'!$B$9+'Unit economics'!$B$10)"
                        f"+${col}$7*('Unit economics'!$C$9+'Unit economics'!$C$10))")
            free_cost = f"={c}{r0 + 1}*'Unit economics'!$B$14"
            contrib = f"={c}{r0 + 4}-{c}{r0 + 5}-{c}{r0 + 6}"
            cum = f"={c}{r0 + 7}" if m == 1 else f"={p}{r0 + 8}+{c}{r0 + 7}"
            for off, f in enumerate([new_free, active_free, new_paid, active_paid, mix_rev, mix_cost, free_cost, contrib, cum]):
                ws.cell(r0 + off, m + 1, f)
    widths(ws, [52] + [12] * 12)
    return ws


def build_budgets(wb):
    ws = wb.create_sheet("Budgets")
    ws["A1"] = "Recommended allocation of money. Shares are recommendations [E]; amounts follow the founders' decision (Q-F02) and any future round. No round size is assumed."
    header(ws, 3, ["Pre-funding monthly budget (DEC-023)", "₹5,000", "₹10,000", "", "Notes"])
    pre = [
        ("GPU compute for beta (rented, scale to zero)", 0.60, 0.60, None, "Largest variable cost; credits first"),
        ("Domain, email, tools beyond free tiers", 0.10, 0.10, None, ""),
        ("Listening-test honoraria and interview incentives", 0.10, 0.15, None, "Evidence for investors"),
        ("Company compliance (accountant, filings)", 0.20, 0.15, None, "Needs quotes"),
        ("Counsel review of terms", 0.0, 0.0, None, "Pay one-off from savings before paid launch"),
    ]
    for i, row in enumerate(pre, start=4):
        for j, v in enumerate(row, start=1):
            ws.cell(i, j, v)
    ws.cell(9, 1, "Total share").font = BOLD
    for col in "BC":
        ws[f"{col}9"] = f"=SUM({col}4:{col}8)"

    header(ws, 11, ["Use of funds after a seed round (share)", "Share", "What it buys", "", ""])
    post = [
        ("Product and engineering (hires, contractors)", 0.40, "Audio-ML engineer, second backend engineer, designer"),
        ("Compute (GPU inference, evaluation)", 0.20, "Lower latency, higher free caps, paid-cloud migration"),
        ("Licensed data and rights", 0.15, "Regional catalogue and commissioned sessions to retire RISK-001"),
        ("Go-to-market", 0.15, "Creator partnerships, content, paid tests in India"),
        ("Legal, compliance, finance and contingency", 0.10, "Counsel, audits, insurance, buffer"),
    ]
    for i, row in enumerate(post, start=12):
        for j, v in enumerate(row, start=1):
            ws.cell(i, j, v)
    ws.cell(17, 1, "Total share").font = BOLD
    ws["B17"] = "=SUM(B12:B16)"
    widths(ws, [52, 12, 60, 12, 40])
    return ws


def main():
    wb = Workbook()
    wb.remove(wb.active)
    build_inputs(wb)
    build_plans(wb)
    build_unit_economics(wb)
    build_scenarios(wb)
    build_budgets(wb)
    wb.save(HERE / "financial-model.xlsx")


if __name__ == "__main__":
    main()
