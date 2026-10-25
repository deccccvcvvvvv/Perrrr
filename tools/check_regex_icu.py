#!/usr/bin/env python3
"""Flag regular expressions that desktop Java accepts but Android's ICU regex engine rejects.

Android's java.util.regex is backed by ICU, which is stricter than the JDK:
  * a literal '}' must be escaped ("\\}") unless it closes a {n}, {n,}, {n,m} quantifier;
  * a literal '{' must be escaped unless it starts such a quantifier.
The JDK silently treats both as literals, so such patterns pass every host test and then crash on a device
(ExceptionInInitializerError when the pattern is in an object/companion initialiser).

Usage: check_regex_icu.py <source roots...>   (scans *.kt, exits 1 on findings)
"""
import os, re, sys

# Regex("..."), Regex("""..."""), "...".toRegex(), Pattern.compile("...")
CALL = re.compile(r'(?:Regex\(\s*|Pattern\.compile\(\s*)("""[\s\S]*?"""|"(?:\\.|[^"\\])*")|("""[\s\S]*?"""|"(?:\\.|[^"\\])*")\s*\.toRegex\(')
QUANT = re.compile(r'\{\d+(,\d*)?\}')

def kotlin_literal_to_pattern(lit):
    if lit.startswith('"""'):
        return lit[3:-3]                       # raw string: no escape processing
    body, out, i = lit[1:-1], [], 0
    while i < len(body):
        c = body[i]
        if c == '\\' and i + 1 < len(body):
            n = body[i + 1]
            out.append({'n': '\n', 't': '\t', 'r': '\r', '\\': '\\', '"': '"', '$': '$', "'": "'"}.get(n, '\\' + n)); i += 2
        else:
            out.append(c); i += 1
    return ''.join(out)

def problems(p):
    found, i, in_class = [], 0, False
    while i < len(p):
        c = p[i]
        if c == '\\': i += 2; continue
        if in_class:
            if c == ']': in_class = False
        elif c == '[':
            in_class = True
            if p[i + 1:i + 2] == ']': i += 1      # "[]...]" literal bracket
        elif c == '{':
            m = QUANT.match(p, i)
            if m: i = m.end(); continue
            found.append(f"unescaped '{{' at {i}")
        elif c == '}':
            found.append(f"unescaped '}}' at {i}")
        i += 1
    return found

bad = 0
for root in sys.argv[1:]:
    for d, _, files in os.walk(root):
        for f in files:
            if not f.endswith('.kt'): continue
            path = os.path.join(d, f); src = open(path, encoding='utf-8').read()
            for m in CALL.finditer(src):
                lit = m.group(1) or m.group(2)
                if '${' in lit.replace('\\$', ''):       # string template: cannot be checked statically
                    continue
                for msg in problems(kotlin_literal_to_pattern(lit)):
                    line = src.count('\n', 0, m.start()) + 1
                    print(f"{path}:{line}: {msg} in {lit}"); bad += 1
print(f"regex ICU check: {bad} problem(s)")
sys.exit(1 if bad else 0)
