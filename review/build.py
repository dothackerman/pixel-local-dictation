#!/usr/bin/env python3
"""Regenerate the self-contained documentation comparison; no private inputs."""
import json
from pathlib import Path
root=Path(__file__).resolve().parents[1]
data=json.loads((root/'review/doc-map.json').read_text())
data['documents']={str(p.relative_to(root/'docs')):dict(text=p.read_text(),lines=len(p.read_text().splitlines()),words=len(p.read_text().split())) for p in sorted((root/'docs').rglob('*.md'))}
for source in data['sources']:
 for target in source['targets']:
  assert target.split('#')[0] in data['documents'],target
payload=json.dumps(data,ensure_ascii=False).replace('<','\\u003c')
(root/'review/documentation.html').write_text((root/'review/template.html').read_text().replace('DATA_PLACEHOLDER',payload))
print('Generated review/documentation.html')
