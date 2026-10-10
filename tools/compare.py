#!/usr/bin/env python3
"""Milestone 3 comparison table: every decode variant of the corpus takes against the scripts.
Reads corpus/takes.json, the app's take files, corpus/laptop/m3-*.json and corpus/futo/*.txt.
Prints word error rate per language, once on all words and once without numbers and units."""
import sys, json, re, importlib.util, glob, os
spec=importlib.util.spec_from_file_location('score','tools/score.py'); m=importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
takes=json.load(open('corpus/takes.json'))
NUMW=set(m.NUM['de'])|set(m.NUM['en'])|set(m.EN_TENS)|set(m.EN_UNITS)|set(m.EN_ORDINAL_UNITS)|set(m.GERMAN_COMPOUNDS)|{'hundred','thousand','tausend','hundert','zwei','zweitausend','pounds','pound','franken','franc','frank','francs','euros','euro','uhr','am','pm','komma','point','one','eins','ein','zero','null','and','und','o','oh','£','€','%','celsius','half','halb'}
def strip(words): return [w for w in words if not (w in NUMW or re.search(r'\d',w))]
def wer(ref,hyp,lang,nonnum):
    r,h=m.normalise(ref,lang),m.normalise(hyp,lang)
    if nonnum: r,h=strip(r),strip(h)
    return m.errors(r,h),len(r)
variants={}
for f,s in takes.items():
    if '-' not in s: continue
    j=json.load(open('corpus/files/benchmark/'+f[:-4]+'.json')); variants.setdefault('app 0.2.1 (phone)',{})[s]=j['text']
for path in sorted(glob.glob('corpus/laptop/m3-*.json'))+sys.argv[1:]:
    rep=json.load(open(path))
    for t in rep['takes']:
        s=takes[t['file']]
        for kind in ('whole','segmented'):
            if kind in t: variants.setdefault(f"{rep['model']} {kind}",{})[s]=t[kind]['text']
extra=os.path.isdir('corpus/futo') and {f[:-4]:open('corpus/futo/'+f,encoding='utf-8').read() for f in os.listdir('corpus/futo') if f.endswith('.txt') and f!='times.txt'}
if extra: variants['FUTO']=extra
print(f"{'variant':34} {'de all':>12} {'de non-num':>12} {'en all':>12} {'en non-num':>12}")
for name,d in variants.items():
    cells=[]
    for lang in ('de','en'):
        for nn in (False,True):
            e=n=0
            for s,h in d.items():
                if s.startswith(lang):
                    a,b=wer(open(f'corpus/scripts/{s}.txt',encoding='utf-8').read(),h,lang,nn); e+=a; n+=b
            cells.append(f"{e}/{n} {100*e/max(n,1):4.1f}%")
    print(f"{name:34} "+" ".join(f"{c:>12}" for c in cells))
