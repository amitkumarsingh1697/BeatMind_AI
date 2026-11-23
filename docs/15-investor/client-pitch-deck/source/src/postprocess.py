"""Adds the motion layer to the pptxgenjs output and tidies the package.

  python3 src/postprocess.py out/_raw.pptx out/anim_plan.json out/BeatMind_AI_Client_Pitch_Deck.pptx

1. Media: fixes file extensions that do not match the bytes, then removes duplicate
   media (pptxgenjs writes one copy per use) and repoints relationships.
2. Brand gradient: shapes whose name ends in "__GRAD" get the marigold > rani > violet gradient.
3. Transitions: Morph (PowerPoint 2019 / Microsoft 365) with a Fade fallback for older
   players, or Fade through black on the cover.
4. Entrance animations: native PowerPoint effects (Fade, Float In, Zoom, Wipe) that start
   automatically after the transition, staggered per anim_plan.json.
5. Cover video: starts automatically and loops.
"""
import hashlib, json, os, re, shutil, sys, tempfile, zipfile
from lxml import etree

NS = {
    'p': 'http://schemas.openxmlformats.org/presentationml/2006/main',
    'a': 'http://schemas.openxmlformats.org/drawingml/2006/main',
    'r': 'http://schemas.openxmlformats.org/officeDocument/2006/relationships',
}
P = '{%s}' % NS['p']
A = '{%s}' % NS['a']

src, plan_path, dst = sys.argv[1:4]
plan = json.load(open(plan_path))
work = tempfile.mkdtemp()
with zipfile.ZipFile(src) as z:
    z.extractall(work)

# ── 1. media hygiene ─────────────────────────────────────────────
media_dir = os.path.join(work, 'ppt', 'media')
rels_files = []
for root, _, files in os.walk(work):
    rels_files += [os.path.join(root, f) for f in files if f.endswith('.rels')]

def sniff(path):
    b = open(path, 'rb').read(12)
    if b.startswith(b'\x89PNG'): return 'png'
    if b.startswith(b'\xff\xd8'): return 'jpeg'
    if b[4:8] == b'ftyp': return 'mp4'
    return None

renames = {}
for f in sorted(os.listdir(media_dir)):
    kind = sniff(os.path.join(media_dir, f))
    base, ext = os.path.splitext(f)
    want = {'png': '.png', 'jpeg': '.jpg', 'mp4': '.mp4'}.get(kind)
    if want and ext.lower() not in ((want,) if want != '.jpg' else ('.jpg', '.jpeg')):
        os.rename(os.path.join(media_dir, f), os.path.join(media_dir, base + want))
        renames[f] = base + want

hashes, dupes = {}, {}
for f in sorted(os.listdir(media_dir)):
    h = hashlib.sha1(open(os.path.join(media_dir, f), 'rb').read()).hexdigest() + os.path.splitext(f)[1]
    if h in hashes:
        dupes[f] = hashes[h]
    else:
        hashes[h] = f
for f in dupes:
    os.remove(os.path.join(media_dir, f))
mapping = {**renames}
for k, v in list(mapping.items()):
    if v in dupes:
        mapping[k] = dupes[v]
mapping.update(dupes)
for rf in rels_files:
    s = open(rf, encoding='utf-8').read()
    s2 = re.sub(r'Target="\.\./media/([^"]+)"', lambda m: 'Target="../media/%s"' % mapping.get(m.group(1), m.group(1)), s)
    if s2 != s:
        open(rf, 'w', encoding='utf-8').write(s2)
ct_path = os.path.join(work, '[Content_Types].xml')
ct = open(ct_path, encoding='utf-8').read()
for ext, mime in (('jpg', 'image/jpeg'), ('png', 'image/png'), ('mp4', 'video/mp4')):
    if 'Extension="%s"' % ext not in ct:
        ct = ct.replace('<Default Extension="xml"', '<Default Extension="%s" ContentType="%s"/><Default Extension="xml"' % (ext, mime), 1)
for old in list(dupes) + list(renames):
    ct = re.sub(r'<Override PartName="/ppt/media/%s"[^>]*/>' % re.escape(old), '', ct)
open(ct_path, 'w', encoding='utf-8').write(ct)
print('media: %d renamed, %d duplicates removed, %d files kept' % (len(renames), len(dupes), len(os.listdir(media_dir))))

# ── helpers for timing XML ───────────────────────────────────────
GRAD = ('<a:gradFill xmlns:a="%s" rotWithShape="1"><a:gsLst>'
        '<a:gs pos="0"><a:srgbClr val="FFB020"/></a:gs><a:gs pos="55000"><a:srgbClr val="FF5C93"/></a:gs>'
        '<a:gs pos="100000"><a:srgbClr val="8B5CF6"/></a:gs></a:gsLst><a:lin ang="0" scaled="0"/></a:gradFill>') % NS['a']

class Ids:
    def __init__(self): self.n = 2
    def __call__(self):
        self.n += 1
        return self.n

def tgt(spid):
    return '<p:tgtEl><p:spTgt spid="%s"/></p:tgtEl>' % spid

def vis(ids, spid):
    return ('<p:set><p:cBhvr><p:cTn id="%d" dur="1" fill="hold"><p:stCondLst><p:cond delay="0"/></p:stCondLst></p:cTn>%s'
            '<p:attrNameLst><p:attrName>style.visibility</p:attrName></p:attrNameLst></p:cBhvr><p:to><p:strVal val="visible"/></p:to></p:set>') % (ids(), tgt(spid))

def prop(ids, spid, attr, frm, to, dur):
    return ('<p:anim calcmode="lin" valueType="num"><p:cBhvr><p:cTn id="%d" dur="%d" decel="100000" fill="hold"/>%s'
            '<p:attrNameLst><p:attrName>%s</p:attrName></p:attrNameLst></p:cBhvr><p:tavLst>'
            '<p:tav tm="0"><p:val><p:strVal val="%s"/></p:val></p:tav><p:tav tm="100000"><p:val><p:strVal val="%s"/></p:val></p:tav>'
            '</p:tavLst></p:anim>') % (ids(), dur, tgt(spid), attr, frm, to)

def effect(ids, spid, kind, delay, dur):
    preset = {'fade': (10, 0), 'float': (42, 0), 'zoom': (53, 16), 'media': (53, 16), 'wipe': (22, 8)}[kind]
    cid = ids()
    inner = vis(ids, spid)
    if kind == 'fade':
        inner += '<p:animEffect transition="in" filter="fade"><p:cBhvr><p:cTn id="%d" dur="%d"/>%s</p:cBhvr></p:animEffect>' % (ids(), dur, tgt(spid))
    elif kind == 'float':
        inner += '<p:animEffect transition="in" filter="fade"><p:cBhvr><p:cTn id="%d" dur="%d"/>%s</p:cBhvr></p:animEffect>' % (ids(), dur, tgt(spid))
        inner += prop(ids, spid, 'ppt_x', '#ppt_x', '#ppt_x', dur)
        inner += prop(ids, spid, 'ppt_y', '#ppt_y+.06', '#ppt_y', dur)
    elif kind in ('zoom', 'media'):
        inner += prop(ids, spid, 'ppt_w', '#ppt_w*0.82', '#ppt_w', dur)
        inner += prop(ids, spid, 'ppt_h', '#ppt_h*0.82', '#ppt_h', dur)
        inner += '<p:animEffect transition="in" filter="fade"><p:cBhvr><p:cTn id="%d" dur="%d"/>%s</p:cBhvr></p:animEffect>' % (ids(), dur, tgt(spid))
    elif kind == 'wipe':
        inner += '<p:animEffect transition="in" filter="wipe(left)"><p:cBhvr><p:cTn id="%d" dur="%d"/>%s</p:cBhvr></p:animEffect>' % (ids(), dur, tgt(spid))
    return ('<p:par><p:cTn id="%d" presetID="%d" presetClass="entr" presetSubtype="%d" fill="hold" grpId="0" nodeType="withEffect">'
            '<p:stCondLst><p:cond delay="%d"/></p:stCondLst><p:childTnLst>%s</p:childTnLst></p:cTn></p:par>') % (cid, preset[0], preset[1], delay, inner)

def media_call(ids, spid):
    return ('<p:par><p:cTn id="%d" presetID="1" presetClass="mediacall" presetSubtype="0" fill="hold" nodeType="withEffect">'
            '<p:stCondLst><p:cond delay="0"/></p:stCondLst><p:childTnLst><p:cmd type="call" cmd="playFrom(0.0)">'
            '<p:cBhvr><p:cTn id="%d" dur="8000" fill="hold"/>%s</p:cBhvr></p:cmd></p:childTnLst></p:cTn></p:par>') % (ids(), ids(), tgt(spid))

TRANS = {
    'morph': ('<mc:AlternateContent xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006">'
              '<mc:Choice xmlns:p159="http://schemas.microsoft.com/office/powerpoint/2015/09/main" xmlns:p14="http://schemas.microsoft.com/office/powerpoint/2010/main" Requires="p159">'
              '<p:transition xmlns:p="%s" spd="slow" p14:dur="1200"><p159:morph option="byObject"/></p:transition></mc:Choice>'
              '<mc:Fallback><p:transition xmlns:p="%s" spd="slow"><p:fade/></p:transition></mc:Fallback></mc:AlternateContent>') % (NS['p'], NS['p']),
    'fadeBlack': '<p:transition xmlns:p="%s" spd="slow"><p:fade thruBlk="1"/></p:transition>' % NS['p'],
}

# ── 2-5. per slide ───────────────────────────────────────────────
total_fx = 0
for entry in plan:
    path = os.path.join(work, 'ppt', 'slides', 'slide%d.xml' % entry['slide'])
    tree = etree.parse(path)
    root = tree.getroot()
    ids_by_name, kind_by_id = {}, {}
    for el in root.iter(P + 'cNvPr'):
        ids_by_name[el.get('name')] = el.get('id')
        kind_by_id[el.get('id')] = etree.QName(el.getparent().getparent()).localname  # sp / pic / graphicFrame / cxnSp
    # gradient fills
    for el in root.iter(P + 'cNvPr'):
        if el.get('name', '').endswith('__GRAD'):
            spPr = el.getparent().getparent().find(P + 'spPr')
            sf = spPr.find(A + 'solidFill')
            if sf is not None:
                sf.addprevious(etree.fromstring(GRAD))
                spPr.remove(sf)
    # transition
    cmo = root.find(P + 'clrMapOvr')
    cmo.addnext(etree.fromstring(TRANS[entry['transition']]))
    trans_el = cmo.getnext()
    # timing
    ids = Ids()
    fx = []
    bld = []
    if entry.get('video'):
        fx.append(media_call(ids, ids_by_name[entry['video']]))
    for a in entry['anims']:
        spid = ids_by_name.get(a['name'])
        if spid is None:
            print('  ! slide %d: no shape named %s' % (entry['slide'], a['name']))
            continue
        fx.append(effect(ids, spid, a['effect'], a['delay'], a['dur']))
        k = kind_by_id[spid]
        if k == 'sp' and spid not in bld:
            bld.append(spid)
    total_fx += len(fx)
    if not fx:
        continue
    video_node = ''
    if entry.get('video'):
        video_node = ('<p:video><p:cMediaNode vol="0" mute="1"><p:cTn id="%d" repeatCount="indefinite" fill="hold" display="0">'
                      '<p:stCondLst><p:cond delay="indefinite"/></p:stCondLst></p:cTn>%s</p:cMediaNode></p:video>') % (ids(), tgt(ids_by_name[entry['video']]))
    timing = ('<p:timing xmlns:p="%s"><p:tnLst><p:par><p:cTn id="1" dur="indefinite" restart="never" nodeType="tmRoot"><p:childTnLst>'
              '<p:seq concurrent="1" nextAc="seek"><p:cTn id="2" dur="indefinite" nodeType="mainSeq"><p:childTnLst>'
              '<p:par><p:cTn id="%d" fill="hold"><p:stCondLst><p:cond delay="indefinite"/><p:cond evt="onBegin" delay="0"><p:tn val="2"/></p:cond></p:stCondLst><p:childTnLst>'
              '<p:par><p:cTn id="%d" fill="hold"><p:stCondLst><p:cond delay="0"/></p:stCondLst><p:childTnLst>%s</p:childTnLst></p:cTn></p:par>'
              '</p:childTnLst></p:cTn></p:par>'
              '</p:childTnLst></p:cTn><p:prevCondLst><p:cond evt="onPrev" delay="0"><p:tgtEl><p:sldTgt/></p:tgtEl></p:cond></p:prevCondLst>'
              '<p:nextCondLst><p:cond evt="onNext" delay="0"><p:tgtEl><p:sldTgt/></p:tgtEl></p:cond></p:nextCondLst></p:seq>%s'
              '</p:childTnLst></p:cTn></p:par></p:tnLst>%s</p:timing>') % (
        NS['p'], ids(), ids(), ''.join(fx), video_node,
        ('<p:bldLst>%s</p:bldLst>' % ''.join('<p:bldP spid="%s" grpId="0" animBg="1"/>' % i for i in bld)) if bld else '')
    trans_el.addnext(etree.fromstring(timing))
    tree.write(path, xml_declaration=True, encoding='UTF-8', standalone=True)
print('animations: %d effects across %d slides' % (total_fx, len(plan)))

# ── repack (no directory entries) ────────────────────────────────
if os.path.exists(dst):
    os.remove(dst)
with zipfile.ZipFile(dst, 'w', zipfile.ZIP_DEFLATED) as z:
    ct = os.path.join(work, '[Content_Types].xml')
    z.write(ct, '[Content_Types].xml')
    for root_dir, _, files in os.walk(work):
        for f in sorted(files):
            full = os.path.join(root_dir, f)
            arc = os.path.relpath(full, work)
            if arc == '[Content_Types].xml':
                continue
            comp = zipfile.ZIP_STORED if f.endswith(('.mp4', '.jpg', '.png')) else zipfile.ZIP_DEFLATED
            z.write(full, arc, compress_type=comp)
shutil.rmtree(work)
print('wrote', dst, '%.1f MB' % (os.path.getsize(dst) / 1e6))
