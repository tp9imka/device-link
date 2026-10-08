#!/usr/bin/env python3
"""Protect the boundaries with narrow, executable checks; no coverage quotas."""
from pathlib import Path
import re
root = Path(__file__).resolve().parents[1]
errors = []
for p in root.glob('**/src/main/**/*.kt'):
    if 'build' in p.parts:
        continue
    relative = str(p.relative_to(root))
    text = p.read_text()
    imports = re.findall(r'^import (.+)$', text, re.M)
    if relative.startswith('core/model/'):
        for item in imports:
            if item.startswith(('android.', 'androidx.', 'dev.devicelink.transfer', 'dev.devicelink.feature')):
                errors.append(f'{relative}: platform dependency in model: {item}')
    if relative.startswith('feature/') and any(item.startswith('dev.devicelink.transfer') for item in imports):
        errors.append(f'{relative}: UI imports transport implementation')
    if relative.startswith('core/designsystem/') and any(item.startswith(('dev.devicelink.transfer', 'dev.devicelink.feature')) for item in imports):
        errors.append(f'{relative}: design system imports a feature/transport')
    if 'GlobalScope' in text:
        errors.append(f'{relative}: unowned GlobalScope')
    if relative.startswith('feature/') and re.search(r'Color\(0x|\b\d+(?:\.\d+)?\.(?:dp|sp)\b', text):
        errors.append(f'{relative}: raw visual token outside design system')
if errors:
    raise SystemExit('\n'.join(errors))
print('Architecture boundaries passed')
