#!/usr/bin/env python3
"""Deterministic code map: static facts, policy interpretation, observed traces (ADR-028).

python3 tools/codemap.py [--root DIR] [--policy tools/codemap_policy.json]
    [--trace FILE]... [--out explainers/codemap/graph.json] [--check]

Scans app/src/**/*.kt, tools/*.py, tools/phone and tools/gate/*.py and writes graph.json
(schema codemap/1). No LLM, no network, no timestamps: the same tree gives the same bytes.
Kotlin is read by a small tokenizer plus declaration scanner, Python by `ast`.
Calls resolve by simple name: same type, then same file, then same package (Python: same
directory), then the whole corpus when unique; the rest count as metrics.unresolved_calls.
Unknown capitalised names (constructors), Python builtins and calls on imported external
modules are not counted. metrics.tests_referencing counts test files whose text contains the
function's simple name as a whole word: a reference count, not coverage. `tests` edges are
emitted only when that name belongs to one function of the language.
--check exits 1 when the policy reports violations.
"""
import argparse
import ast
import builtins
import fnmatch
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SCHEMA = 'codemap/1'
REPO_URL = 'https://github.com/dothackerman/pixel-local-dictation'
SKIP_DIRS = {'build', 'corpus', 'models', '.gradle', '__pycache__'}
UNASSIGNED = {'id': 'cmp:unassigned', 'kind': 'component', 'name': 'Unassigned', 'parent': 'sys',
              'layer': None, 'description': 'Files matching no policy component.'}
WORD = re.compile(r'[^\W\d]\w*')

# ---------------------------------------------------------------- Kotlin tokenizer

IDENT = re.compile(r'[^\W\d]\w*')
NUMBER = re.compile(r'0[xXbB][0-9a-fA-F_]+[uUlL]*|\d[\d_]*(?:\.\d[\d_]*)?(?:[eE][+-]?\d+)?[fFdDuUlL]*')
MULTI = ('->', '&&', '||', '?:', '?.', '::', '===', '!==', '==', '!=', '<=', '>=', '..',
         '!!', '++', '--', '+=', '-=')
OPEN, CLOSE = '([{', ')]}'


def kotlin_tokens(src):
    """Return tokens (kind, text, line, start, end); kinds id, op, str, chr, num.

    String bodies are dropped but `${...}` template expressions are tokenized in place.
    """
    toks, stack = [], [0]   # int: code brace depth; 'str'/'raw': inside a string
    i, n, line = 0, len(src), 1
    while i < n:
        mode, c = stack[-1], src[i]
        if mode in ('str', 'raw'):
            if c == '\\' and mode == 'str':
                i += 2
            elif mode == 'str' and c == '"':
                stack.pop()
                i += 1
            elif mode == 'raw' and src.startswith('"""', i):
                i += 3
                while i < n and src[i] == '"':
                    i += 1
                stack.pop()
            elif src.startswith('${', i):
                stack.append(0)
                i += 2
            else:
                line += c == '\n'
                i += 1
            continue
        if c.isspace():
            line += c == '\n'
            i += 1
        elif src.startswith('//', i):
            j = src.find('\n', i)
            i = n if j < 0 else j
        elif src.startswith('/*', i):
            depth, i = 1, i + 2
            while i < n and depth:
                if src.startswith('/*', i):
                    depth, i = depth + 1, i + 2
                elif src.startswith('*/', i):
                    depth, i = depth - 1, i + 2
                else:
                    line += src[i] == '\n'
                    i += 1
        elif src.startswith('"""', i):
            toks.append(('str', '"""', line, i, i + 3))
            stack.append('raw')
            i += 3
        elif c == '"':
            toks.append(('str', '"', line, i, i + 1))
            stack.append('str')
            i += 1
        elif c == "'":
            j = i + 1
            while j < n and src[j] not in "'\n":
                j += 2 if src[j] == '\\' else 1
            toks.append(('chr', src[i:j + 1], line, i, j + 1))
            i = j + 1
        elif c == '`':
            j = src.find('`', i + 1)
            j = n if j < 0 else j
            toks.append(('id', src[i + 1:j], line, i, j + 1))
            i = j + 1
        elif c.isalpha() or c == '_':
            m = IDENT.match(src, i)
            toks.append(('id', m.group(), line, i, m.end()))
            i = m.end()
        elif c.isdigit():
            m = NUMBER.match(src, i)
            toks.append(('num', m.group(), line, i, m.end()))
            i = m.end()
        elif c == '}' and len(stack) > 1 and stack[-1] == 0:
            stack.pop()   # end of ${...}
            i += 1
        else:
            if c == '{':
                stack[-1] += 1
            elif c == '}':
                stack[-1] = max(0, stack[-1] - 1)
            op = next((m for m in MULTI if src.startswith(m, i)), c)
            toks.append(('op', op, line, i, i + len(op)))
            i += len(op)
    return toks


def bracket_info(toks):
    """Matching bracket index map and, per token, the index of its innermost enclosing `{`."""
    match, encl, stack, braces = {}, [], [], []
    for j, t in enumerate(toks):
        text = t[1] if t[0] == 'op' else ''
        encl.append(braces[-1] if braces else -1)
        if text and text in OPEN:
            stack.append(j)
            if text == '{':
                braces.append(j)
        elif text and text in CLOSE:
            want = OPEN[CLOSE.index(text)]
            while stack and toks[stack[-1]][1] != want:
                stack.pop()
            if stack:
                o = stack.pop()
                match[o], match[j] = j, o
                if want == '{':
                    while braces and braces[-1] != o:
                        braces.pop()
                    if braces:
                        braces.pop()
    return match, encl


# ---------------------------------------------------------------- Kotlin declarations

TYPE_MODS = {'enum', 'data', 'sealed', 'annotation', 'value', 'inline', 'inner', 'abstract',
             'open', 'private', 'internal', 'protected', 'public', 'fun', 'companion',
             'expect', 'actual', 'final', 'external'}
FUN_MODS = {'private', 'internal', 'protected', 'public', 'override', 'open', 'abstract', 'final',
            'suspend', 'inline', 'operator', 'infix', 'tailrec', 'external', 'actual', 'expect'}
CALL_KEYWORDS = {'if', 'while', 'for', 'when', 'catch', 'return', 'throw', 'super', 'this',
                 'constructor', 'fun', 'init', 'class', 'object'}
BRANCH_WORDS = {'if', 'while', 'for', 'catch'}
CONT_END = {'.', '?.', ',', '(', '[', '=', '+', '-', '*', '/', '%', '&&', '||', '?:', '->', ':',
            '!', '==', '!=', '===', '!==', '<=', '>=', '..', 'in', 'is', 'as', 'to',
            'else', 'return', 'throw', 'if', 'when', 'try', 'until', 'step', 'and', 'or'}
CONT_START = {'.', '?.', '?:', '&&', '||', 'else', 'catch', 'finally', '+', '*', '/', '%',
              '..', '->', ':', ',', 'until', 'step'}


class _K:
    """Kotlin file scan state (tokens, brackets, collected nodes)."""

    def __init__(self, src, path, fid):
        self.src, self.path, self.fid = src, path, fid
        self.toks = kotlin_tokens(src)
        self.match, self.encl = bracket_info(self.toks)
        self.when_braces = set()
        self.package, self.imports, self.types, self.fns, self.ids = '', [], [], [], set()

    def text(self, k):
        return self.toks[k][1] if 0 <= k < len(self.toks) else ''

    def kind(self, k):
        return self.toks[k][0] if 0 <= k < len(self.toks) else ''

    def line(self, k):
        return self.toks[min(max(k, 0), len(self.toks) - 1)][2]

    def unique(self, base, line):
        return base if base not in self.ids else f'{base}:{line}'

    def skip_angle(self, k, hi):
        depth = 0
        while k < hi:
            t = self.text(k)
            if t == '<':
                depth += 1
            elif t == '>':
                depth -= 1
                if depth == 0:
                    return k + 1
            elif t == '(':
                k = self.match.get(k, k)
            elif t in ('{', '=', ';', '}'):
                return k
            k += 1
        return k

    def scan(self, j, hi, ctx):
        while j < hi:
            kind, text = self.toks[j][0], self.toks[j][1]
            prev = self.text(j - 1)
            fn = ctx['fn']
            if kind == 'id':
                if text == 'package' and ctx['top']:
                    self.package, j = self.dotted(j + 1, hi)
                    continue
                if text == 'import' and ctx['top']:
                    name, j = self.dotted(j + 1, hi)
                    if name:
                        self.imports.append(name)
                    continue
                if text in ('class', 'interface') and prev != '::' or text == 'object' and (
                        prev == 'companion' or self.kind(j + 1) == 'id'):
                    j = self.type_decl(j, hi, ctx)
                    continue
                if text == 'fun' and self.text(j + 1) != 'interface':
                    j = self.fun_decl(j, hi, ctx)
                    continue
                if text == 'when':
                    k = j + 1
                    if self.text(k) == '(':
                        k = self.match.get(k, k) + 1
                    if self.text(k) == '{':
                        self.when_braces.add(k)
                if fn is not None:
                    if text in BRANCH_WORDS:
                        fn['_cx'] += 1
                    elif (self.text(j + 1) == '(' and text not in CALL_KEYWORDS
                          and prev != '@'):
                        fn['_calls'].append((text, self.toks[j][2], text[:1].isupper(),
                                             self.receiver(j)))
            elif kind == 'op' and fn is not None:
                if text in ('&&', '||', '?:'):
                    fn['_cx'] += 1
                elif text == '->' and self.encl[j] in self.when_braces and prev != 'else':
                    fn['_cx'] += 1
            j += 1
        return j

    def receiver(self, j):
        """None for `x()`, the identifier for `recv.x()` (`this` kept), '?' for any other receiver."""
        if self.text(j - 1) not in ('.', '?.'):
            return None
        r = j - 2
        if self.kind(r) == 'id' and self.text(r - 1) not in ('.', '?.'):
            return self.text(r)
        return '?'

    def binds(self, idxs):
        """Declared types of identifiers: `x: Type`, `x = Type(`, `x by lazy { Type(`."""
        out = {}
        for j in idxs:
            if self.kind(j) != 'id' or self.text(j - 1) in ('.', '?.', 'class', 'object', 'interface'):
                continue
            nxt, t = self.text(j + 1), None
            if nxt == ':' and self.kind(j + 2) == 'id':
                t = j + 2
                while self.text(t + 1) == '.' and self.kind(t + 2) == 'id':
                    t += 2
            elif nxt == '=' and self.kind(j + 2) == 'id' and self.text(j + 3) == '(':
                t = j + 2
            elif (nxt, self.text(j + 2), self.text(j + 3)) == ('by', 'lazy', '{') \
                    and self.kind(j + 4) == 'id' and self.text(j + 5) == '(':
                t = j + 4
            if t is not None and self.text(t)[:1].isupper():
                out.setdefault(self.text(j), self.text(t))
        return out

    def dotted(self, k, hi):
        parts, line = [], self.line(k)
        while k < hi and self.line(k) == line and (self.kind(k) == 'id' or self.text(k) in ('.', '*')):
            if self.text(k) == 'as' and parts:
                k += 2
                break
            parts.append(self.text(k))
            k += 1
        return ''.join(parts), k

    def type_decl(self, j, hi, ctx):
        kw, mods, b = self.text(j), set(), j - 1
        while b >= 0 and self.kind(b) == 'id' and self.text(b) in TYPE_MODS:
            mods.add(self.text(b))
            b -= 1
        if kw == 'interface':
            type_kind = 'interface'
        elif kw == 'object':
            type_kind = 'companion object' if 'companion' in mods else 'object'
        else:
            type_kind = next((v for m, v in (('enum', 'enum'), ('data', 'data class'),
                                             ('sealed', 'sealed class'),
                                             ('annotation', 'annotation class')) if m in mods),
                             'class')
        k = j + 1
        if self.kind(k) == 'id' and self.text(k) not in ('private', 'internal', 'protected',
                                                          'public', 'constructor'):
            name, k = self.text(k), k + 1
        elif type_kind == 'companion object':
            name = 'Companion'
        else:
            return j + 1
        if self.text(k) == '<':
            k = self.skip_angle(k, hi)
        while k < hi and (self.text(k) in ('private', 'internal', 'protected', 'public',
                                            'constructor') or self.text(k) == '@'):
            k += 2 if self.text(k) == '@' else 1
            if self.text(k) == '(' and self.text(k - 1) != 'constructor':
                k = self.match.get(k, k) + 1
        if self.text(k) == '(':
            k = self.match.get(k, k) + 1
        supers = []
        if self.text(k) == ':':
            k += 1
            while k < hi:
                while self.text(k) == '@':
                    k += 2
                if self.kind(k) != 'id':
                    break
                last = self.text(k)
                k += 1
                while self.text(k) == '.' and self.kind(k + 1) == 'id':
                    last, k = self.text(k + 1), k + 2
                supers.append(last)
                if self.text(k) == '<':
                    k = self.skip_angle(k, hi)
                if self.text(k) == '(':
                    k = self.match.get(k, k) + 1
                if self.text(k) == 'by':
                    k += 1
                    while k < hi and (self.kind(k) == 'id' or self.text(k) == '.'):
                        k += 1
                    if self.text(k) == '(':
                        k = self.match.get(k, k) + 1
                if self.text(k) != ',':
                    break
                k += 1
        qual = ctx['prefix'] + name
        tid = self.unique(f'type:{self.path}#{qual}', self.line(j))
        self.ids.add(tid)
        node = {'id': tid, 'kind': 'type', 'name': name, 'type_kind': type_kind, 'lang': 'kotlin',
                'path': self.path, 'line': self.line(j), 'parent': ctx['parent'],
                'supertypes': supers, '_pkg': self.package, '_file': self.fid,
                '_top': ctx['top']}
        self.types.append(node)
        if self.text(k) == '{' and k < hi:
            close = self.match.get(k, hi - 1)
            inner = {'top': False, 'fn': None, 'prefix': qual + '.', 'parent': tid,
                     'types': [tid] + ctx['types'], 'scope': ctx.get('scope', [])}
            self.scan(k + 1, close, inner)
            node['end_line'], nxt = self.line(close), close + 1
            node['_range'] = (j, k, close)
        else:
            node['end_line'], nxt = self.line(k - 1), k
            node['_range'] = (j, k, None)
        node['metrics'] = {'loc': node['end_line'] - node['line'] + 1}
        return max(nxt, j + 1)

    def fun_decl(self, j, hi, ctx):
        k = j + 1
        if self.text(k) == '<':
            k = self.skip_angle(k, hi)
        head = []
        while k < hi and len(head) < 40 and self.text(k) not in ('(', '{', '=', '}', ';'):
            if self.text(k) == '<':
                k = self.skip_angle(k, hi)
                continue
            head.append(k)
            k += 1
        ids = [h for h in head if self.kind(h) == 'id']
        if self.text(k) != '(' or not ids or k not in self.match:
            return j + 1
        ni = ids[-1]
        name = self.text(ni)
        dots = [h for h in head if h < ni and self.text(h) == '.']
        receiver = None
        if dots:
            receiver = ' '.join(self.src[self.toks[head[0]][3]:self.toks[dots[-1]][3]].split())
        last = self.match[k]
        k = last + 1
        if self.text(k) == ':':
            last, k = k, k + 1
            while k < hi:
                t = self.text(k)
                if t in ('{', '=', ';', '}'):
                    break
                if self.line(k) > self.line(last) and self.text(last) not in CONT_END \
                        and t not in ('->', '.', '?'):
                    break
                if t == '(':
                    k = self.match.get(k, k)
                elif t == '<':
                    k = self.skip_angle(k, hi) - 1
                last, k = k, k + 1
        qual = ctx['prefix'] + name
        line = self.line(j)
        fid = f'fn:{self.path}#{qual}:{line}'
        start = self.toks[j][3]
        node = {'id': fid, 'kind': 'function', 'name': name, 'qualname': qual, 'lang': 'kotlin',
                'path': self.path, 'line': line, 'parent': ctx['parent'],
                '_calls': [], '_cx': 1, '_types': ctx['types'], '_file': self.fid,
                '_pkg': self.package, '_top': ctx['top'], '_scope': ctx.get('scope', []),
                '_local': ctx['fn'] is not None}
        if receiver:
            node['receiver'] = receiver
        b = j - 1
        while b >= 0 and self.kind(b) == 'id' and self.text(b) in FUN_MODS:
            node['_private'] = node.get('_private') or self.text(b) == 'private'
            b -= 1
        self.fns.append(node)
        inner = {'top': False, 'fn': node, 'prefix': qual + '.', 'parent': fid,
                 'types': ctx['types'], 'scope': ctx.get('scope', []) + [fid]}
        if self.text(k) == '{' and k < hi:
            close = self.match.get(k, hi - 1)
            sig_end = self.toks[k][3]
            self.scan(k + 1, close, inner)
            end, nxt = close, close + 1
        elif self.text(k) == '=' and k < hi:
            sig_end = self.toks[k][3]
            end = self.expr_end(k + 1, hi)
            self.scan(k + 1, end + 1, inner)
            nxt = end + 1
        else:
            sig_end, end, nxt = self.toks[last][4], last, last + 1
        node['signature'] = ' '.join(self.src[start:sig_end].split())
        node['end_line'] = self.line(end)
        node['_range'] = (j, end)
        return max(nxt, j + 1)

    def expr_end(self, k, hi):
        last, i = k, k
        while i < hi:
            t = self.text(i)
            if i > k and self.line(i) > self.line(last) and self.text(last) not in CONT_END \
                    and t not in CONT_START:
                break
            if t == ';' or (self.kind(i) == 'op' and t in CLOSE):
                break
            if self.kind(i) == 'op' and t in OPEN:
                i = min(self.match.get(i, hi - 1), hi - 1)
            last, i = i, i + 1
        return last


def extract_kotlin(src, path, fid):
    k = _K(src, path, fid)
    k.scan(0, len(k.toks), {'top': True, 'fn': None, 'prefix': '', 'parent': fid, 'types': []})
    for f in k.fns:
        a, b = f.pop('_range')
        f['_binds'] = k.binds(range(a, b + 1))
    for t in k.types:
        a, h, close = t.pop('_range')
        body = [] if close is None else [i for i in range(h, close) if k.encl[i] == h]
        t['_binds'] = k.binds(list(range(a, h)) + body)
    top = k.binds([i for i in range(len(k.toks)) if k.encl[i] == -1])
    return k.package, k.imports, k.types, k.fns, top


# ---------------------------------------------------------------- Python

BUILTIN_NAMES = set(dir(builtins))


def _py_complexity(node):
    n = 0
    if isinstance(node, (ast.If, ast.For, ast.AsyncFor, ast.While, ast.ExceptHandler, ast.IfExp)):
        n += 1
    elif isinstance(node, ast.BoolOp):
        n += len(node.values) - 1
    elif isinstance(node, ast.comprehension):
        n += len(node.ifs)
    elif isinstance(node, ast.match_case):
        n += 1
    return n


def _py_receiver(value):
    if isinstance(value, ast.Name):
        return 'this' if value.id == 'self' else value.id
    if isinstance(value, ast.Attribute) and isinstance(value.value, ast.Name) and value.value.id == 'self':
        return 'self.' + value.attr
    return '?'


def _py_type(ann):
    if isinstance(ann, ast.Constant) and isinstance(ann.value, str):
        return ann.value.split('.')[-1].split('[')[0].strip()
    if isinstance(ann, ast.BinOp):   # X | None
        return _py_type(ann.left) or _py_type(ann.right)
    if isinstance(ann, ast.Name):
        return None if ann.id == 'None' else ann.id
    return ann.attr if isinstance(ann, ast.Attribute) else None


def _py_binds(node, members=False):
    """Declared types: `x: Type`, `x = Type(...)`; for a class also `self.x = Type(`/`self.x: Type`."""
    out, nested = {}, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef, ast.Lambda)

    def own(n):   # descendants of n outside nested scopes
        for c in ast.iter_child_nodes(n):
            if not isinstance(c, nested):
                yield c
                yield from own(c)

    def assigned(c):
        if isinstance(c, ast.AnnAssign):
            return c.target, _py_type(c.annotation)
        if isinstance(c, ast.Assign) and isinstance(c.value, ast.Call) and len(c.targets) == 1:
            t = _py_type(c.value.func)
            return c.targets[0], t if t and t[:1].isupper() else None
        return None, None

    def collect(stmts, self_attrs):
        for c in stmts:
            target, t = assigned(c)
            if not t:
                continue
            if isinstance(target, ast.Name) and not self_attrs:
                out.setdefault(('self.' if members else '') + target.id, t)
            elif self_attrs and isinstance(target, ast.Attribute) \
                    and isinstance(target.value, ast.Name) and target.value.id == 'self':
                out.setdefault('self.' + target.attr, t)

    if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
        for a in node.args.posonlyargs + node.args.args + node.args.kwonlyargs:
            if a.annotation is not None and _py_type(a.annotation):
                out.setdefault(a.arg, _py_type(a.annotation))
    if members:
        collect(node.body, False)
        for m in node.body:
            if isinstance(m, (ast.FunctionDef, ast.AsyncFunctionDef)):
                collect(own(m), True)
    else:
        collect(own(node), False)
    return out


def extract_python(src, path, fid, external):
    """Return (imports, types, fns); imports are (module, alias-bound name) pairs."""
    try:
        tree = ast.parse(src, filename=path)
    except SyntaxError:
        return [], [], [], {}
    imports, types, fns = [], [], []

    def module_imports(body):
        for st in body:
            if isinstance(st, ast.Import):
                imports.extend((a.name, (a.asname or a.name).split('.')[0], 0) for a in st.names)
            elif isinstance(st, ast.ImportFrom):
                mod = st.module or ''
                imports.extend((f'{mod}.{a.name}' if mod else a.name, a.asname or a.name,
                                st.level) for a in st.names)
            elif isinstance(st, (ast.If, ast.Try, ast.With)):
                for field in ('body', 'orelse', 'finalbody'):
                    module_imports(getattr(st, field, []))
                for h in getattr(st, 'handlers', []):
                    module_imports(h.body)
    module_imports(tree.body)
    ext_names = {bound for mod, bound, level in imports if not level and external(mod)}

    def body_facts(fn, node):
        for child in ast.iter_child_nodes(node):
            if isinstance(child, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
                visit(child, fn['_ctx'])
                continue
            fn['_cx'] += _py_complexity(child)
            if isinstance(child, ast.Call):
                f = child.func
                if isinstance(f, ast.Name):
                    fn['_calls'].append((f.id, child.lineno,
                                         f.id[:1].isupper() or f.id in BUILTIN_NAMES, None))
                elif isinstance(f, ast.Attribute):
                    ext = isinstance(f.value, ast.Name) and f.value.id in ext_names
                    fn['_calls'].append((f.attr, child.lineno, ext or f.attr[:1].isupper(),
                                         _py_receiver(f.value)))
            body_facts(fn, child)

    def visit(node, ctx):
        qual = ctx['prefix'] + node.name
        if isinstance(node, ast.ClassDef):
            tid = f'type:{path}#{qual}'
            supers = [b.id if isinstance(b, ast.Name) else b.attr for b in node.bases
                      if isinstance(b, (ast.Name, ast.Attribute))]
            types.append({'id': tid, 'kind': 'type', 'name': node.name, 'type_kind': 'class',
                          'lang': 'python', 'path': path, 'line': node.lineno,
                          'end_line': node.end_lineno, 'parent': ctx['parent'],
                          'supertypes': supers, 'metrics': {'loc': node.end_lineno - node.lineno + 1},
                          '_file': fid, '_pkg': ctx['pkg'], '_top': ctx['top'],
                          '_binds': _py_binds(node, members=True)})
            inner = dict(ctx, prefix=qual + '.', parent=tid, types=[tid] + ctx['types'], top=False)
            for st in node.body:
                if isinstance(st, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
                    visit(st, inner)
            return
        prefix = 'async def' if isinstance(node, ast.AsyncFunctionDef) else 'def'
        sig = f'{prefix} {node.name}({ast.unparse(node.args)})'
        if node.returns is not None:
            sig += f' -> {ast.unparse(node.returns)}'
        nid = f'fn:{path}#{qual}:{node.lineno}'
        fn = {'id': nid, 'kind': 'function', 'name': node.name, 'qualname': qual,
              'lang': 'python', 'path': path, 'line': node.lineno, 'end_line': node.end_lineno,
              'parent': ctx['parent'], 'signature': sig, '_calls': [], '_cx': 1,
              '_types': ctx['types'], '_file': fid, '_pkg': ctx['pkg'], '_top': ctx['top'],
              '_scope': ctx['scope'], '_local': bool(ctx['scope']) and ctx['parent'] == ctx['scope'][-1],
              '_binds': _py_binds(node)}
        fns.append(fn)
        fn['_ctx'] = dict(ctx, prefix=qual + '.', parent=nid, top=False, scope=ctx['scope'] + [nid])
        for st in node.body:
            if isinstance(st, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
                visit(st, fn['_ctx'])
            else:
                fn['_cx'] += _py_complexity(st)
                body_facts(fn, st)
        del fn['_ctx']

    pkg = str(Path(path).parent).replace(os.sep, '/')
    ctx = {'prefix': '', 'parent': fid, 'types': [], 'pkg': pkg, 'top': True, 'scope': []}
    for st in tree.body:
        if isinstance(st, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            visit(st, ctx)
    return imports, types, fns, _py_binds(tree)


# ---------------------------------------------------------------- scan + resolve

def source_files(root):
    found = set(root.glob('app/src/**/*.kt')) | set(root.glob('tools/*.py')) \
        | set(root.glob('tools/gate/*.py'))
    if (root / 'tools/phone').is_file():
        found.add(root / 'tools/phone')
    result = []
    for p in found:
        rel = p.relative_to(root)
        if p.is_file() and not any(part in SKIP_DIRS or part.startswith('.') for part in rel.parts):
            result.append(rel.as_posix())
    return sorted(result)


def is_test(path, lang):
    if lang == 'kotlin':
        return '/src/test/' in '/' + path or '/src/androidTest/' in '/' + path
    return fnmatch.fnmatchcase(path, 'tools/test_*.py')


def git_commit(root):
    try:
        r = subprocess.run(['git', 'rev-parse', 'HEAD'], cwd=root, capture_output=True,
                           text=True, timeout=10)
    except (OSError, subprocess.SubprocessError):
        return None
    sha = r.stdout.strip()
    return sha if r.returncode == 0 and re.fullmatch(r'[0-9a-f]{40,64}', sha) else None


def pick(cands, caller, tiers):
    """Resolution precedence: same type, same file (lowest line), same package, corpus (unique).

    Function-local declarations are visible only inside their enclosing function, Kotlin
    `private` functions only in their file; production code never resolves into test files.
    """
    scope = set(caller.get('_scope', ())) | {caller['id']}
    cands = [c for c in cands if (not c.get('_local') or c['parent'] in scope)
             and c['_test'] <= caller['_test']
             and (not c.get('_private') or c['_file'] == caller['_file'])]
    if not cands:
        return None
    if 'type' in tiers:
        for tid in caller['_types']:
            hit = [c for c in cands if c['parent'] == tid]
            if hit:
                return min(hit, key=lambda c: (c['line'], c['id']))
    hit = [c for c in cands if c['_file'] == caller['_file']]
    if hit:
        return min(hit, key=lambda c: (c['line'], c['id']))
    hit = [c for c in cands if c['_pkg'] == caller['_pkg']]
    if len(hit) == 1:
        return hit[0]
    if hit:
        return None
    return cands[0] if len(cands) == 1 else None


def lang_of(path):
    return 'kotlin' if path.endswith('.kt') else 'python'


def add_edge(edges, a, b, kind, source='static', line=None):
    cur = edges.setdefault((a, b, kind, source), [0, line])
    cur[0] += 1
    if line is not None and (cur[1] is None or line < cur[1]):
        cur[1] = line


def component_of(path, comps):
    for c in comps:
        if any(fnmatch.fnmatchcase(path, g) for g in c.get('paths', [])):
            return 'cmp:' + c['id'].removeprefix('cmp:')
    return UNASSIGNED['id']


def scan_files(root, paths, comps):
    """Return file nodes, type and function declarations, file texts and local import targets."""
    files, types, fns, texts, imports_to = {}, [], [], {}, {}
    py_local = {}
    for path in paths:
        if lang_of(path) == 'python':
            p = Path(path)
            py_local.setdefault(p.parent.as_posix(), {})[p.stem if p.suffix == '.py' else p.name] = path
    for path in paths:
        text = texts[path] = (root / path).read_text(encoding='utf-8', errors='replace')
        lang, fid = lang_of(path), f'file:{path}'
        node = {'id': fid, 'kind': 'file', 'name': Path(path).name, 'lang': lang, 'path': path,
                'parent': component_of(path, comps), 'metrics': {'loc': len(text.splitlines())}}
        targets = imports_to[fid] = set()
        if lang == 'kotlin':
            pkg, imps, ts, fs, node['_binds'] = extract_kotlin(text, path, fid)
            node['package'], node['imports'] = pkg or None, sorted(set(imps))
        else:
            local = py_local.get(Path(path).parent.as_posix(), {})
            imps, ts, fs, node['_binds'] = extract_python(
                text, path, fid, lambda m: m.split('.')[0] not in local)
            listed = set()
            for mod, _bound, level in imps:
                target = local.get(mod.split('.')[0]) or (local.get(mod.split('.')[-1]) if level else None)
                if target is None:
                    listed.add(mod)
                elif target != path:
                    targets.add(f'file:{target}')
            node['package'], node['imports'] = None, sorted(listed)
        files[fid] = node
        for d in ts + fs:
            d['_test'] = is_test(path, lang)
        types.extend(ts)
        fns.extend(fs)
    # Kotlin: a fully qualified import of a top-level corpus declaration links the files.
    fqn = {}
    for d in types + fns:
        if d['lang'] == 'kotlin' and d['_top'] and d['_pkg']:
            fqn.setdefault(f"{d['_pkg']}.{d['name']}", d['_file'])
    for fid, node in files.items():
        if node['lang'] == 'kotlin':
            imports_to[fid].update(t for t in map(fqn.get, node['imports']) if t and t != fid)
    return files, types, fns, texts, imports_to


def by_name(decls):
    index = {}
    for d in sorted(decls, key=lambda d: d['id']):
        index.setdefault((d['lang'], d['name']), []).append(d)
    return index


class Resolver:
    """Call targets. Unqualified and `this.` calls: pick() precedence. `Recv.x()` with a known
    type and `recv.x()` whose type is declared in scope (function, enclosing types, file)
    resolve to that type's members (companions and corpus supertypes included). Any other
    dotted call may only reach a corpus-unique top-level function."""

    def __init__(self, files, types, fns):
        self.files, self.fn_index, self.type_index = files, by_name(fns), by_name(types)
        self.decls = {d['id']: d for d in types + fns}
        self.members, self.companions, self.supers = {}, {}, {}
        for f in fns:
            self.members.setdefault((f['parent'], f['name']), []).append(f)
        for t in types:
            if t['type_kind'] == 'companion object':
                self.companions.setdefault(t['parent'], []).append(t['id'])

    def type_named(self, name, caller):
        return pick(self.type_index.get((caller['lang'], name), []), dict(caller, _types=[]), ())

    def declared(self, recv, caller):
        for did in [caller['id']] + caller.get('_scope', [])[::-1] + caller.get('_types', []):
            t = self.decls.get(did, {}).get('_binds', {}).get(recv)
            if t:
                return t
        return self.files[caller['_file']]['_binds'].get(recv)

    def member(self, tid, name):
        seen, queue = set(), [tid]
        while queue:
            t = queue.pop(0)
            if t in seen:
                continue
            seen.add(t)
            hit = self.members.get((t, name))
            if hit:
                return min(hit, key=lambda c: (c['line'], c['id']))
            queue += self.companions.get(t, []) + self.supers.get(t, [])
        return None

    def call(self, f, name, recv):
        cands = self.fn_index.get((f['lang'], name), [])
        if recv in (None, 'this'):
            return pick(cands, f, ('type',))
        tname = self.declared(recv, f) or (recv if recv[:1].isupper() else None)
        t = self.type_named(tname, f) if tname else None
        hit = self.member(t['id'], name) if t else None
        if hit:
            return hit
        tops = [c for c in cands if c['_top']]
        tops = [c for c in tops if c is pick([c], f, ())]
        return tops[0] if len(tops) == 1 else None


def static_edges(edges, imports_to, files, types, fns):
    for fid in sorted(imports_to):
        for target in sorted(imports_to[fid]):
            add_edge(edges, fid, target, 'imports')
    res = Resolver(files, types, fns)
    for t in types:
        for sup in t['supertypes']:
            target = res.type_named(sup, dict(t, _scope=[]))
            if target and target['id'] != t['id']:
                add_edge(edges, t['id'], target['id'], 'extends', line=t['line'])
                res.supers.setdefault(t['id'], []).append(target['id'])
    for f in fns:
        unresolved = 0
        for name, line, ext_ok, recv in f['_calls']:
            target = res.call(f, name, recv)
            if target is None and ext_ok:
                target = pick(res.type_index.get((f['lang'], name), []), f, ())
                if target is None:
                    continue   # constructor of an unknown type, builtin or external module
            if target is None:
                unresolved += 1
            else:
                add_edge(edges, f['id'], target['id'], 'calls', line=line)
        f['metrics'] = {'loc': f['end_line'] - f['line'] + 1, 'complexity': f['_cx'],
                        'unresolved_calls': unresolved}
    return res.fn_index


def test_references(edges, texts, fns, fn_index):
    """tests_referencing: whole-word mentions in test files (a reference count, not coverage)."""
    words = {}
    for path, text in texts.items():
        if is_test(path, lang_of(path)):
            words.setdefault(lang_of(path), {})[path] = set(WORD.findall(text))
    for f in fns:
        corpus = words.get(f['lang'])
        hits = [] if corpus is None else [p for p, w in sorted(corpus.items())
                                         if p != f['path'] and f['name'] in w]
        refs = None if corpus is None else len(hits)
        f['metrics']['tests_referencing'] = refs
        f['metrics']['health'] = ('unknown' if refs is None else
                                  'watch' if f['metrics']['complexity'] >= 10 and refs == 0 else 'ok')
        if hits and len(fn_index[(f['lang'], f['name'])]) == 1:
            for p in hits:
                add_edge(edges, f'file:{p}', f['id'], 'tests')


def read_traces(edges, sources, fns):
    """Observed behaviour: qualnames (or path#qualname) resolve to fn ids, else `ext:<raw>`."""
    by_qual, by_path_qual = {}, {}
    for f in fns:
        by_qual.setdefault(f['qualname'], []).append(f['id'])
        by_path_qual.setdefault(f"{f['path']}#{f['qualname']}", []).append(f['id'])

    def target(raw):
        if raw is None:
            return None
        raw = str(raw)
        hit = by_path_qual.get(raw) or by_qual.get(raw)
        if hit and (len(hit) == 1 or raw in by_path_qual):
            return sorted(hit)[0]
        tail = [] if hit else [i for q, ids in by_qual.items() if q.endswith('.' + raw) for i in ids]
        return tail[0] if len(tail) == 1 else 'ext:' + raw

    out = []
    for source in sources:
        data = json.loads(Path(source).read_text(encoding='utf-8'))
        name, events = str(data.get('name') or Path(source).stem), []
        for seq, ev in enumerate(data.get('events', []), 1):
            a, b = target(ev.get('from')), target(ev.get('to'))
            label = ev.get('label') or str(ev.get('to')).split('#')[-1].split('.')[-1]
            events.append({'seq': ev.get('seq', seq), 'from': a, 'to': b, 'label': label,
                           't_ms': ev.get('t_ms')})
            if a and b and not a.startswith('ext:') and not b.startswith('ext:'):
                add_edge(edges, a, b, 'calls', source='trace')
        out.append({'id': f'trace:{name}', 'name': name, 'source': str(source), 'events': events})
    return sorted(out, key=lambda t: (t['id'], t['source']))


def build(root, policy, traces=()):
    root = Path(root)
    comps = policy.get('components', [])
    files, types, fns, texts, imports_to = scan_files(root, source_files(root), comps)
    edges = {}
    fn_index = static_edges(edges, imports_to, files, types, fns)
    test_references(edges, texts, fns, fn_index)
    trace_out = read_traces(edges, traces, fns)
    nodes = {'sys': {'id': 'sys', 'kind': 'system', 'name': root.resolve().name, 'parent': None}}
    for c in comps:
        cid = 'cmp:' + c['id'].removeprefix('cmp:')
        nodes[cid] = {'id': cid, 'kind': 'component', 'name': c.get('name', c['id']),
                      'parent': 'sys', 'layer': c.get('layer'), 'description': c.get('description', '')}
    if not comps or any(f['parent'] == UNASSIGNED['id'] for f in files.values()):
        nodes[UNASSIGNED['id']] = dict(UNASSIGNED)
    for d in list(files.values()) + types + fns:
        nodes[d['id']] = {k: v for k, v in d.items() if not k.startswith('_')}
    edge_list = [{'from': a, 'to': b, 'kind': k, 'source': s, 'line': v[1], 'count': v[0]}
                 for (a, b, k, s), v in sorted(edges.items()) if a in nodes and b in nodes]
    return {
        'schema': SCHEMA,
        'generated_from': {'commit': git_commit(root), 'root_name': root.resolve().name,
                           'repo_url': policy.get('repo_url', REPO_URL)},
        'nodes': [nodes[k] for k in sorted(nodes)],
        'edges': edge_list,
        'policy': {'components': comps, 'rules': policy.get('rules', [])},
        'violations': check_policy(policy, nodes, edge_list),
        'traces': trace_out,
    }


# ---------------------------------------------------------------- policy

def file_of(nodes, nid):
    node = nodes.get(nid)
    while node is not None and node['kind'] not in ('file', 'component', 'system'):
        node = nodes.get(node['parent'])
    return node if node is not None and node['kind'] == 'file' else None


def check_policy(policy, nodes, edges):
    out = set()
    for rule in policy.get('rules', []):
        kind, rid = rule.get('kind'), rule.get('id', rule.get('kind'))
        if kind == 'forbid-import':
            sel = rule.get('components', ['*'])
            pats = rule.get('patterns', [])
            for node in nodes.values():
                if node['kind'] != 'file':
                    continue
                cid = node['parent']
                if not any(fnmatch.fnmatchcase(cid, g) or fnmatch.fnmatchcase(cid.removeprefix('cmp:'), g)
                           for g in sel):
                    continue
                for imp in node.get('imports', []):
                    for pat in pats:
                        if imp.startswith(pat):
                            out.add((rid, node['id'], imp, f'{cid} imports {imp} (forbidden prefix {pat})'))
        elif kind == 'layer-order':
            for e in edges:
                if e['kind'] not in ('calls', 'imports', 'extends') or e['source'] != 'static':
                    continue
                fa, fb = file_of(nodes, e['from']), file_of(nodes, e['to'])
                if not fa or not fb:
                    continue
                ca, cb = nodes[fa['parent']], nodes[fb['parent']]
                la, lb = ca.get('layer'), cb.get('layer')
                if la is None or lb is None or la >= lb:
                    continue
                out.add((rid, e['from'], e['to'],
                         f"{ca['id']} (layer {la}) {e['kind']} {cb['id']} (layer {lb})"))
    return [{'rule': r, 'from': a, 'to': b, 'detail': d} for r, a, b, d in sorted(out)]


def load_policy(path, explicit):
    if path.is_file():
        return json.loads(path.read_text(encoding='utf-8'))
    if explicit:
        raise SystemExit(f'codemap: policy not found: {path}')
    return {'components': [], 'rules': []}


def write_atomic(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(data, sort_keys=True, indent=1, ensure_ascii=False) + '\n'
    fd, tmp = tempfile.mkstemp(dir=path.parent, prefix='.graph-', suffix='.tmp')
    try:
        with os.fdopen(fd, 'w', encoding='utf-8') as fh:
            fh.write(text)
        os.replace(tmp, path)
    except BaseException:
        Path(tmp).unlink(missing_ok=True)
        raise


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument('--root', type=Path, default=ROOT)
    ap.add_argument('--policy', type=Path)
    ap.add_argument('--trace', type=Path, action='append', default=[])
    ap.add_argument('--out', type=Path)
    ap.add_argument('--check', action='store_true', help='exit 1 on policy violations')
    args = ap.parse_args(argv)
    root = args.root.resolve()
    policy = load_policy(args.policy or root / 'tools/codemap_policy.json', args.policy is not None)
    graph = build(root, policy, args.trace)
    out = args.out or root / 'explainers/codemap/graph.json'
    write_atomic(out, graph)
    count = {}
    for n in graph['nodes']:
        count[n['kind']] = count.get(n['kind'], 0) + 1
    print(f"codemap: {count.get('file', 0)} files, {count.get('type', 0)} types, "
          f"{count.get('function', 0)} functions, {len(graph['edges'])} edges, "
          f"{len(graph['violations'])} violations -> {out}")
    if args.check and graph['violations']:
        for v in graph['violations']:
            print(f"{v['rule']}: {v['from']} -> {v['to']} ({v['detail']})", file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
