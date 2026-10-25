#!/usr/bin/env python3
"""Verify that compiled JVM classes only reference java.*/javax.* APIs available at a given Android API level.

Usage: check_android_api.py <api-versions.xml> <minSdk> <classes-dir-or-jar>...
Uses `javap` (JDK) to list member references and resolves them through the class hierarchy recorded in the
SDK's api-versions.xml. Exits 1 when any reference is newer than minSdk or missing from Android entirely.
"""
import re, subprocess, sys, os, xml.etree.ElementTree as ET
from collections import defaultdict

api_xml, min_sdk, roots = sys.argv[1], int(sys.argv[2]), sys.argv[3:]
classes = {}
for c in ET.parse(api_xml).getroot().iter('class'):
    since = int(c.get('since', '1'))
    members = {}
    for m in list(c.iter('method')) + list(c.iter('field')):
        members[m.get('name')] = int(m.get('since', since))
    supers = [e.get('name') for e in c.iter('extends')] + [i.get('name') for i in c.iter('implements')]
    classes[c.get('name')] = (since, members, supers, c.get('removed'))

def lookup(owner, key, seen=None):
    seen = seen or set()
    if owner in seen or owner not in classes: return None
    seen.add(owner)
    since, members, supers, _ = classes[owner]
    if key in members: return members[key]
    for s in supers:
        r = lookup(s, key, seen)
        if r is not None: return r
    return None

ref = re.compile(r'// (Method|InterfaceMethod|Field) ([\w/$]+)\.("?<?[\w$]+>?"?):(\S+)')
files = []
for r in roots:
    if r.endswith('.jar'): files.append(r)
    else:
        for d, _, fs in os.walk(r): files += [os.path.join(d, f) for f in fs if f.endswith('.class')]
problems = defaultdict(set)
for i in range(0, len(files), 200):
    batch = files[i:i+200]
    cmd = ['javap', '-c', '-p'] + batch
    out = subprocess.run(cmd, capture_output=True, text=True).stdout
    current = '?'
    for line in out.splitlines():
        if line.startswith(('class ', 'public ', 'final ', 'abstract ', 'interface ')) and '{' in line: current = line.split('{')[0].strip()[-80:]
        m = ref.search(line)
        if not m: continue
        kind, owner, name, desc = m.groups(); name = name.strip('"')
        if not owner.startswith(('java/', 'javax/')): continue
        key = name if kind == 'Field' else name + desc
        if owner not in classes:
            problems[f'{owner} (class missing on Android)'].add(current); continue
        csince = classes[owner][0]
        msince = lookup(owner, key)
        if msince is None:
            problems[f'{owner}.{key} (member missing on Android)'].add(current)
        elif max(msince, csince) > min_sdk:
            problems[f'{owner}.{key} (API {max(msince, csince)})'].add(current)
for k in sorted(problems):
    print(k); [print('    used in', u) for u in sorted(problems[k])[:4]]
print(f'checked {len(files)} class files: {len(problems)} incompatible references (minSdk {min_sdk})')
sys.exit(1 if problems else 0)
