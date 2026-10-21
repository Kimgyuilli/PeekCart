#!/usr/bin/env python3
"""Read-only scope check against the explicit D103 predecessor."""
import ast
from pathlib import Path
import re
import subprocess

BASE = '2f16266706d0e15c085e9eed6d4f7bbd608c73f4'

def before(path):
    return subprocess.check_output(['git', 'show', BASE + ':' + path], text=True)

def without_comments(text):
    return '\n'.join(line for line in text.splitlines() if not line.lstrip().startswith('#'))

renderer = 'scripts/codex-review-render.sh'
old, new = before(renderer), Path(renderer).read_text()
marker = "<<'PYRENDER'\n"
a, b = old.split(marker, 1); code_old, tail_old = b.split('\nPYRENDER\n', 1)
c, d = new.split(marker, 1); code_new, tail_new = d.split('\nPYRENDER\n', 1)
assert without_comments(a + marker + tail_old) == without_comments(c + marker + tail_new)

class NoDocstrings(ast.NodeTransformer):
    def visit_FunctionDef(self, node):
        self.generic_visit(node)
        if node.body and isinstance(node.body[0], ast.Expr) and isinstance(node.body[0].value, ast.Constant) and isinstance(node.body[0].value.value, str):
            node.body = node.body[1:]
        return node

old_ast, new_ast = ast.parse(code_old), ast.parse(code_new)
changed_docs = [(o.name, ast.get_docstring(o), ast.get_docstring(n))
                for o, n in zip(old_ast.body, new_ast.body)
                if isinstance(o, ast.FunctionDef) and isinstance(n, ast.FunctionDef)
                and ast.get_docstring(o) != ast.get_docstring(n)]
assert [row[0] for row in changed_docs] == ['clip'], changed_docs
assert ast.dump(NoDocstrings().visit(old_ast)) == ast.dump(NoDocstrings().visit(new_ast))
wrapper = 'scripts/codex-review-render-selftest.sh'
assert without_comments(before(wrapper)) == without_comments(Path(wrapper).read_text())
helper = 'scripts/codex-review-render-contract-selftest.py'
assert before(helper) == Path(helper).read_text()
assert before('.github/workflows/ci.yml') == Path('.github/workflows/ci.yml').read_text()
phase = 'docs/progress/PHASE4.md'

def scope(text):
    text = re.sub(r'^\*\*프로세스\*\*: `/work`\(diff 6천 줄.*$', 'PR3D_SPLIT', text, flags=re.M)
    text = re.sub(r'^\*\*프로세스\*\*: `/work` single 리뷰\(1,569줄\).*$', 'PR3D_SINGLE', text, flags=re.M)
    for start, end, label in [('### 측정 — 리뷰 루프 발산의 실체', '### 적용', 'AUDIT_COUNTS'),
                               ('## D-027 ② — Codex 리뷰 응답 실패 판별', '### 검증 도구가 검증 대상과 같은 출처면 둘 다 틀려도 통과한다', 'RENDERER_HISTORY')]:
        a = text.index(start); b = text.index(end, a)
        text = text[:a] + label + '\n\n' + text[b:]
    return text
assert scope(before(phase)) == scope(Path(phase).read_text())
print('PASS: Bash comments only; Python AST identical excluding clip docstring; D103 helper/workflow unchanged')
print('PASS: PHASE4 outside the two PR3d paragraphs and delegated measurement/renderer-history ranges unchanged')
