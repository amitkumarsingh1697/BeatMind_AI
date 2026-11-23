"""Builds BeatMind_AI_Presenter_Script.docx from src/script_content.js and the slide renders.

  python3 src/build_script_docx.py out/script.json out/r out/BeatMind_AI_Presenter_Script.docx
(script.json is written by: node -e "console.log(JSON.stringify(require('./src/script_content')))")
"""
import json, os, sys
from docx import Document
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH, WD_BREAK
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Cm, Pt, RGBColor

data = json.load(open(sys.argv[1]))
render_dir, out = sys.argv[2], sys.argv[3]

INK, MUTED, MARIGOLD, VIOLET = RGBColor(0x1A, 0x14, 0x2E), RGBColor(0x5E, 0x57, 0x78), RGBColor(0xB8, 0x6E, 0x00), RGBColor(0x5B, 0x3D, 0xF5)

doc = Document()
sec = doc.sections[0]
sec.page_width, sec.page_height = Cm(21), Cm(29.7)
sec.left_margin = sec.right_margin = Cm(2.0)
sec.top_margin, sec.bottom_margin = Cm(1.8), Cm(1.8)

st = doc.styles['Normal']
st.font.name = 'Calibri'
st.font.size = Pt(10.5)
st.font.color.rgb = INK
st.paragraph_format.space_after = Pt(4)
for name, size, color in (('Heading 1', 20, INK), ('Heading 2', 14, INK), ('Heading 3', 11, VIOLET)):
    h = doc.styles[name]
    h.font.name, h.font.size, h.font.bold, h.font.color.rgb = 'Calibri', Pt(size), True, color

def shade(cell, hex_):
    tcPr = cell._tc.get_or_add_tcPr()
    shd = OxmlElement('w:shd')
    shd.set(qn('w:val'), 'clear'); shd.set(qn('w:color'), 'auto'); shd.set(qn('w:fill'), hex_)
    tcPr.append(shd)

def set_widths(t, widths):
    t.autofit = False
    tblPr = t._tbl.tblPr
    lay = OxmlElement('w:tblLayout'); lay.set(qn('w:type'), 'fixed'); tblPr.append(lay)
    grid = t._tbl.tblGrid
    for gc, w in zip(grid.findall(qn('w:gridCol')), widths):
        gc.set(qn('w:w'), str(int(w.twips)))
    for row in t.rows:
        for c, w in zip(row.cells, widths):
            c.width = w

def para(text, size=10.5, bold=False, color=INK, italic=False, after=4, style=None):
    p = doc.add_paragraph(style=style)
    r = p.add_run(text)
    r.font.size, r.bold, r.italic, r.font.color.rgb = Pt(size), bold, italic, color
    p.paragraph_format.space_after = Pt(after)
    return p

# footer page numbers
fp = sec.footer.paragraphs[0]
fp.alignment = WD_ALIGN_PARAGRAPH.RIGHT
r = fp.add_run('BeatMind AI · Presenter script · page ')
r.font.size, r.font.color.rgb = Pt(8), MUTED
for t, txt in (('begin', None), (None, 'PAGE'), ('end', None)):
    run = fp.add_run()
    run.font.size = Pt(8)
    if t:
        el = OxmlElement('w:fldChar'); el.set(qn('w:fldCharType'), t); run._r.append(el)
    else:
        el = OxmlElement('w:instrText'); el.set(qn('xml:space'), 'preserve'); el.text = txt; run._r.append(el)

# ── cover ──
para('BEATMIND AI', 11, True, MARIGOLD, after=2)
doc.add_heading('Presenter script', 0).runs[0].font.color.rgb = INK
para('Client pitch deck · 23 slides · about 35 minutes plus questions', 12, color=MUTED, after=14)
para('How to use this script', 13, True)
for t in [
    'Each slide has an objective, a key message, presenter notes, talking points, a bridge to the next slide and a time budget. The same notes are in the PowerPoint speaker notes.',
    'Do not read the slides. Say the key message in your own words, then use the talking points to add what the slide does not say.',
    'Every slide builds automatically after its transition (about 1 to 2.5 seconds). Wait for the build to finish before you speak to the detail; one click moves to the next slide.',
    'Facts carry their source on the slide. If asked for more, the sources are in the BeatMind market research (Oct 2026) and the repository registers.',
    'Before sending or presenting externally, fill in the placeholders: partner name, presenter, team details (slide 19) and contact line (slide 23).',
]:
    p = doc.add_paragraph(t, style='List Bullet'); p.paragraph_format.space_after = Pt(3)

para('Running order', 13, True, after=6)
tbl = doc.add_table(rows=1, cols=4)
tbl.style = 'Table Grid'
tbl.alignment = WD_TABLE_ALIGNMENT.CENTER
for i, h in enumerate(['#', 'Slide title', 'Act', 'Time']):
    c = tbl.rows[0].cells[i]; c.text = ''
    rr = c.paragraphs[0].add_run(h); rr.bold = True; rr.font.size = Pt(9.5); rr.font.color.rgb = RGBColor(255, 255, 255)
    shade(c, '1A142E')
for s in data:
    row = tbl.add_row().cells
    for i, v in enumerate([str(s['n']), s['title'], s['act'].split('·')[-1].strip(), s['duration']]):
        row[i].text = ''
        rr = row[i].paragraphs[0].add_run(v); rr.font.size = Pt(9)
        row[i].paragraphs[0].paragraph_format.space_after = Pt(0)
set_widths(tbl, [Cm(1.0), Cm(10.2), Cm(4.3), Cm(1.5)])

# ── per slide ──
for s in data:
    doc.add_paragraph().add_run().add_break(WD_BREAK.PAGE)
    para(f"SLIDE {s['n']}  ·  {s['act'].upper()}", 9, True, MARIGOLD, after=2)
    doc.add_heading(s['title'], 1)
    img = os.path.join(render_dir, 's-%02d.jpg' % s['n'])
    if os.path.exists(img):
        doc.add_picture(img, width=Cm(13.5))
        doc.paragraphs[-1].alignment = WD_ALIGN_PARAGRAPH.CENTER
    meta = doc.add_table(rows=3, cols=2)
    meta.style = 'Table Grid'
    for i, (k, v) in enumerate([('Objective', s['objective']), ('Key message', s['key']), ('Recommended duration', s['duration'])]):
        a, b = meta.rows[i].cells
        a.text = ''; b.text = ''
        ra = a.paragraphs[0].add_run(k); ra.bold = True; ra.font.size = Pt(9.5)
        rb = b.paragraphs[0].add_run(v); rb.font.size = Pt(9.5)
        shade(a, 'F1EEFB')
    set_widths(meta, [Cm(4.0), Cm(13.0)])
    doc.add_heading('Presenter notes', 3)
    para(s['notes'])
    doc.add_heading('Talking points', 3)
    for t in s['talking']:
        p = doc.add_paragraph(t, style='List Bullet'); p.paragraph_format.space_after = Pt(2)
    doc.add_heading('Transition to the next slide', 3)
    para('“' + s['next'] + '”', italic=True, color=MUTED)
    doc.add_heading('On-screen motion', 3)
    para(f"{s['animation']} Transition: {s['transition']}", 9.5, color=MUTED)

doc.core_properties.title = 'BeatMind AI · Presenter Script'
doc.core_properties.author = 'BeatMind AI'
doc.save(out)
print('wrote', out)
