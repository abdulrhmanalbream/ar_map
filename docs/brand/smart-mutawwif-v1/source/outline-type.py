"""Build native Arabic SVG glyph paths from the bundled fonts; no raster image is read or edited."""
from pathlib import Path
from xml.sax.saxutils import escape
import uharfbuzz as hb
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen

ROOT = Path(__file__).resolve().parent.parent
def outline(text, filename, size, baseline, group_id):
    file = ROOT / 'fonts' / filename
    fontdata = file.read_bytes()
    face = hb.Face(fontdata)
    font = hb.Font(face)
    font.scale = (face.upem, face.upem)
    hb.ot_font_set_funcs(font)
    buf = hb.Buffer()
    buf.add_str(text)
    buf.direction, buf.script, buf.language = 'rtl', 'arab', 'ar'
    hb.shape(font, buf)
    tt = TTFont(file)
    glyphs, order = tt.getGlyphSet(), tt.getGlyphOrder()
    scale = size / face.upem
    total = sum(p.x_advance for p in buf.glyph_positions) * scale
    x = (1200-total)/2
    pieces = [f'<g id="{group_id}" aria-label="{escape(text)}" fill="#173E35">']
    for info, pos in zip(buf.glyph_infos, buf.glyph_positions):
        if info.codepoint == 0:
            raise ValueError(f'Missing glyph in {text}')
        pen = SVGPathPen(glyphs)
        glyphs[order[info.codepoint]].draw(pen)
        d = pen.getCommands()
        if d:
            pieces.append(f'<path transform="translate({x+pos.x_offset*scale:.5f} {baseline-pos.y_offset*scale:.5f}) scale({scale:.5f} {-scale:.5f})" d="{d}"/>')
        x += pos.x_advance*scale
    pieces.append('</g>')
    return '\n'.join(pieces)

content = outline('المطوف الذكي','IBMPlexSansArabic-SemiBold.ttf',124,625,'product-name')
content += '\n' + outline('رفيقٌ وفيّ، وسندٌ قويّ.','IBMPlexSansArabic-Regular.ttf',65,755,'approved-slogan')
(ROOT/'source'/'wordmark-paths.svg').write_text('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1200 900">'+content+'</svg>',encoding='utf-8')
print('Arabic font outlines generated: two lines, no missing glyphs.')
