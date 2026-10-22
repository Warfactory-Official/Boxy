"""Similarity of Boxy's seed LOD code to a reference Java codebase.

Usage: python tools/similarity_scan.py <reference source dir> [ours dir]

Two measures per category (top-level package under seedlod/), both against the WHOLE reference corpus,
so moving code between files or packages does not hide it:
  lines  - share of our non-trivial normalized lines that occur anywhere in the reference
  tokens - share of our 20-token shingles (identifiers collapsed to ID, literals to LIT) found in the
           reference; insensitive to renaming, so it catches copied logic with new names.
           Calibration: Boxy's independently written vss package scores ~1% lines / ~5% tokens
           (ordinary Java and Minecraft API idioms); a verbatim copy reads ~97% / ~98%. Package and import
           lines are excluded from both measures.

With --common DIR (repeatable), overlap that also occurs in a neutral corpus (Minecraft/NeoForge sources,
Boxy's own independent code) is not counted: the same field declaration or API call written everywhere is an
idiom, not evidence of copying. What remains is overlap distinctive to the reference.
"""
import collections, os, re, sys

KEYWORDS = set("""abstract assert boolean break byte case catch char class continue default do double else enum
extends final finally float for if implements import instanceof int interface long new package private protected
public return short static super switch synchronized this throw throws try void volatile while var record yield
true false null""".split())
TOKEN = re.compile(r'"(?:\\.|[^"\\])*"' r"|'(?:\\.|[^'\\])*'" r'|\d[\w.]*|[A-Za-z_$][\w$]*|==|!=|<=|>=|&&|\|\||->|::|<<|>>>?|[^\s\w]')
K = 20

def strip_comments(text):
    return re.sub(r'/\*.*?\*/|//[^\n]*', '', text, flags=re.S)

def code_lines(text):
    out = []
    for line in strip_comments(text).splitlines():
        line = re.sub(r'\s+', ' ', line.strip())
        if not line or line.startswith(('import ', 'package ', '@Override')):
            continue
        out.append(line)
    return out

def trivial(line):
    return len(re.sub(r'[\s{}();,]', '', line)) < 12

def strip_header(text):
    return re.sub(r'^\s*(package|import)\s[^;]*;', '', text, flags=re.M)

def tokens(text):
    toks = []
    for t in TOKEN.findall(strip_header(strip_comments(text))):
        if t[0] in '"\'' or t[0].isdigit():
            toks.append('LIT')
        elif t[0].isalpha() or t[0] in '_$':
            toks.append(t if t in KEYWORDS else 'ID')
        else:
            toks.append(t)
    return toks

def shingles(toks):
    return {tuple(toks[i:i + K]) for i in range(len(toks) - K + 1)}

def java_files(root):
    for dirpath, _, files in os.walk(root):
        for name in files:
            if name.endswith('.java'):
                path = os.path.join(dirpath, name)
                yield os.path.relpath(path, root).replace(os.sep, '/'), open(path, encoding='utf-8').read()

def main():
    import argparse
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('reference')
    parser.add_argument('ours', nargs='?', default='src/main/java/com/golem/boxy/seedlod')
    parser.add_argument('--common', action='append', default=[],
                        help='neutral source dir (e.g. Minecraft/NeoForge sources); overlap also found there is an idiom, '
                             'not copying, and is not counted')
    parser.add_argument('--show', action='store_true', help='print every counted line and token run')
    args = parser.parse_args()

    ours = []
    our_lines, our_shingles = set(), set()
    for rel, text in sorted(java_files(args.ours)):
        lines = [l for l in code_lines(text) if not trivial(l)]
        sh = shingles(tokens(text))
        ours.append((rel, text, lines, sh))
        our_lines.update(lines)
        our_shingles |= sh

    ref_lines, ref_shingles = set(), set()
    for _, text in java_files(args.reference):
        ref_lines.update(l for l in code_lines(text) if not trivial(l) and l in our_lines)
        ref_shingles |= shingles(tokens(text)) & our_shingles

    common_lines, common_shingles = set(), set()
    for root in args.common:
        for _, text in java_files(root):
            common_lines.update(l for l in code_lines(text) if l in ref_lines)
            common_shingles |= shingles(tokens(text)) & ref_shingles
    counted_lines = ref_lines - common_lines
    counted_shingles = ref_shingles - common_shingles

    cats = collections.defaultdict(lambda: [0, 0, 0, 0])
    files = []
    for rel, text, lines, sh in ours:
        cat = rel.split('/')[0] if '/' in rel else '(root)'
        same = [l for l in lines if l in counted_lines]
        shared = sh & counted_shingles
        c = cats[cat]
        c[0] += len(same); c[1] += len(lines); c[2] += len(shared); c[3] += len(sh)
        files.append((rel, len(lines), len(same) / max(1, len(lines)), len(shared) / max(1, len(sh))))
        if args.show and (same or shared):
            print('==', rel)
            for l in same:
                print('   line ', l[:140])
            toks = tokens(text)
            print('   token runs:', len(shared))
    title = 'distinctive (overlap not also in --common)' if args.common else 'all overlap'
    print(title)
    print(f"{'category':12s} {'lines':>7s} {'line%':>6s} {'token%':>7s}")
    worst = 0.0
    for cat, (s_, n, ss, sn) in sorted(cats.items(), key=lambda kv: -kv[1][1]):
        lp, tp = 100 * s_ / max(1, n), 100 * ss / max(1, sn)
        worst = max(worst, lp, tp)
        print(f"{cat:12s} {n:7d} {lp:6.1f} {tp:7.1f}")
    tl = sum(c[0] for c in cats.values()) / max(1, sum(c[1] for c in cats.values()))
    tt = sum(c[2] for c in cats.values()) / max(1, sum(c[3] for c in cats.values()))
    print(f"{'TOTAL':12s} {sum(c[1] for c in cats.values()):7d} {100 * tl:6.1f} {100 * tt:7.1f}")
    print()
    print("files above 20% on either measure:")
    for rel, n, lp, tp in sorted(files, key=lambda f: -max(f[2], f[3])):
        if max(lp, tp) > 0.20:
            print(f"  {rel:40s} {n:5d} lines  line {100 * lp:5.1f}%  token {100 * tp:5.1f}%")
    print()
    print(f"worst category: {worst:.1f}%  ({'PASS' if worst < 20 else 'FAIL'} < 20%)")

if __name__ == '__main__':
    main()
