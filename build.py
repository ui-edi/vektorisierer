"""Baut index.html aus src/vektorisierer.html und vendor/imagetracer_v1.2.6.js.

Aufruf im Repo-Ordner:  python3 build.py
"""
from pathlib import Path

root = Path(__file__).parent
src = (root / 'src' / 'vektorisierer.html').read_text(encoding='utf8')
engine = (root / 'vendor' / 'imagetracer_v1.2.6.js').read_text(encoding='utf8')
assert '/*__IMAGETRACER__*/' in src, 'Platzhalter für den Motor fehlt in src/vektorisierer.html'
assert '</script' not in engine
page = src.replace('/*__IMAGETRACER__*/', engine)

# <title> gehört in den <head>
import re
m = re.match(r'\s*(<title>.*?</title>)\s*', page)
title = m.group(1) if m else '<title>Vektorisierer</title>'
if m:
    page = page[m.end():]

head = ('<!doctype html>\n<html lang="de">\n<head>\n<meta charset="utf-8">\n'
        '<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">\n'
        + title + '\n<meta name="color-scheme" content="light dark">\n'
        '<meta name="description" content="Bild rein, SVG raus: Bilder im Browser in Vektorgrafiken umwandeln, '
        'mit Hintergrund-Entferner und Pixelart-Modus.">\n'
        '<style>:root{padding-top:env(safe-area-inset-top,0px);padding-bottom:env(safe-area-inset-bottom,0px)}'
        'body{margin:0;font:14px/1.5 system-ui,sans-serif}img{max-width:100%}[hidden]{display:none!important}</style>\n'
        '</head>\n<body>\n')
(root / 'index.html').write_text(head + page + '\n</body>\n</html>\n', encoding='utf8')
print('index.html gebaut,', len(page), 'Zeichen')
