#!/usr/bin/env python3
"""Run the issue-16 laptop dictionary screen; all evidence stays under corpus/.

One bilingual dictionary, three feed sizes, matched empty controls, public guards,
leave-script-out dictionaries and one predictor/tree-state carry experiment.
"""
import argparse
import csv
import hashlib
import json
from pathlib import Path
import re
import time

import dictionary_score as score
from dictionary_boost import PhraseTree, compile_dictionary
from tdt_decoder import Decoder, tokens_to_text


def sha(path):
    with Path(path).open('rb') as file:
        return hashlib.file_digest(file, 'sha256').hexdigest()


def counts(reference, text, lang, entries):
    result = score.score_text(reference, text, lang, entries)
    return {k: result[k] for k in score.COUNTS}


def missing_segmentations(result, symbols, compiled, language):
    known = {tuple(row['tokens']) for row in compiled}
    terms = {''.join(score.normalise(row['form'], language)): row['term'] for row in compiled}
    groups = []
    for token in result.tokens:
        if not groups or symbols[token].startswith('▁'):
            groups.append([])
        groups[-1].append(token)
    missing = []
    for start in range(len(groups)):
        span = []
        for group in groups[start:start+8]:
            span += group
            trimmed = list(span)
            while trimmed and not any(c.isalnum() for c in symbols[trimmed[-1]]):
                trimmed.pop()
            key = ''.join(score.normalise(tokens_to_text(trimmed, symbols), language))
            if key in terms and tuple(trimmed) not in known:
                missing.append({'term': terms[key], 'tokens': trimmed})
    return missing


def run(args):
    import pipeline
    import variant
    root = args.root.resolve()
    output = args.output.resolve()
    if not output.is_relative_to(root/'corpus'):
        raise ValueError('screen evidence must remain inside corpus/')
    output.mkdir(parents=True, exist_ok=True)
    dictionary_path = root/'corpus/lab/issue11-dictionary.txt'
    entries = dictionary_path.read_text().splitlines()
    refs = json.loads((root/'corpus/lab/issue11/references.json').read_text())
    scripts = json.loads((root/'corpus/takes.json').read_text())
    config = variant.load(root/'variants/baseline.json')
    model = root/'models/parakeet-v3-matmul8'
    compiled = compile_dictionary(entries, model/'bpe.vocab', model/'tokens.txt')
    (output/'dictionary.json').write_text(json.dumps(compiled, ensure_ascii=False, indent=2)+'\n')
    tree = PhraseTree([row['tokens'] for row in compiled])
    decoder = Decoder(model, args.runtime, root/'corpus/lab/issue11/encoder-cache')
    cells = []
    for name, (lang, reference) in refs.items():
        path = root/'corpus/files/benchmark'/name
        audio = pipeline.read_wav(str(path))
        for feed in (640,512,800):
            encoded = [decoder.encode(part)[0] for _,part,_ in pipeline.chunks(config, str(root/'models/silero_vad.onnx'), audio, feed)]
            cells.append(dict(id=name, script=scripts[name], lang=lang, reference=reference,
                              feed=feed, encoded=encoded, input_sha256=sha(path)))
    guard = []
    for row in csv.DictReader((root/'models/guard/refs.tsv').open(), delimiter='\t'):
        path = root/'models/guard'/row['lang']/(row['id']+'.wav')
        guard.append(dict(id=row['id'], lang=row['lang'], reference=row['reference'],
                          encoded=decoder.encode(pipeline.read_wav(str(path)))[0], input_sha256=sha(path)))
    # Each held-out script removes terms occurring anywhere in that script's reference.
    fold_trees = {}
    for script in set(scripts.values()):
        script_refs = [(lang,text) for name,(lang,text) in refs.items() if scripts[name]==script]
        held_terms = {term for term in entries if any(
            ''.join(score.normalise(term,lang)) in ''.join(score.normalise(text,lang)) for lang,text in script_refs)}
        fold_trees[script] = PhraseTree([row['tokens'] for row in compiled if row['term'] not in held_terms])
    manifest = dict(protocol='dictionary-greedy-screen-v1', alphas=args.alpha, tuning_feed=640,
                    runtime=decoder.identity, dictionary_sha256=sha(dictionary_path),
                    references_sha256=sha(root/'corpus/lab/issue11/references.json'),
                    sources={p.name:sha(p) for p in [Path(__file__),Path(__file__).with_name('tdt_decoder.py'),
                        Path(__file__).with_name('dictionary_boost.py'),Path(__file__).with_name('dictionary_score.py')]},
                    inputs=[{k:v for k,v in c.items() if k in ('id','feed','input_sha256')} for c in cells+guard])
    (output/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    rows = []
    controls = {}
    stock_report = json.loads((root/'corpus/lab/issue11/baseline-fresh.json').read_text())
    stock = {(take['file'],run.get('feed',640)):run['text'] for take in stock_report['takes'] for run in [take,*take['jitter']]}
    guard_stock = {r['id']:r['stock_text'] for r in json.loads((root/'corpus/lab/issue11/python-parity-eigen.json').read_text())['clips']}

    def takes(alpha, mode, carry=False):
        outputs = []
        for cell in cells:
            selected_tree = fold_trees[cell['script']] if mode=='leave-script-out' else tree
            state = None
            parts, missing = [], []
            calls = 0
            for encoded in cell['encoded']:
                result = decoder.decode(encoded, tree=selected_tree if alpha else None, alpha=alpha, continuation=state)
                if carry:
                    state = result.continuation
                parts.append(result.text)
                calls += result.joiner_calls
                missing += missing_segmentations(result, decoder.symbols, compiled, cell['lang'])
            text = pipeline.post_join(config,parts)
            output_row = {k:v for k,v in cell.items() if k not in ('encoded','reference')}
            output_row.update(alpha=alpha, mode=mode, carry=carry, text=text, joiner_calls=calls,
                              missing_segmentations=missing, **counts(cell['reference'],text,cell['lang'],entries))
            outputs.append(output_row)
            key=(cell['id'],cell['feed'],carry)
            if alpha==0:
                controls[key]=output_row
                if not carry and text != stock[cell['id'],cell['feed']]:
                    raise ValueError('zero-bonus full-take text differs from stock')
            else:
                control=controls[key]
                output_row['control_id']=f"alpha0:{cell['id']}:{cell['feed']}:{carry}"
                for count in score.COUNTS:
                    output_row[count+'_delta']=output_row[count]-control[count]
        return outputs

    def save():
        fields=sorted({key for row in rows for key in row if key not in ('text','missing_segmentations')})
        with (output/'cells.csv').open('w') as file:
            writer=csv.DictWriter(file,fieldnames=fields,extrasaction='ignore');writer.writeheader();writer.writerows(rows)

    outcomes=[]
    for alpha in args.alpha:
        begun=time.monotonic()
        normal=takes(alpha,'reset')
        held=takes(alpha,'leave-script-out') if alpha else normal
        guards=[]
        for cell in guard:
            result=decoder.decode(cell['encoded'],tree=tree if alpha else None,alpha=alpha)
            text=pipeline.post_join(config,[result.text])
            row=dict(id=cell['id'],lang=cell['lang'],alpha=alpha,mode='guard',carry=False,feed=0,
                     text=text,joiner_calls=result.joiner_calls,**counts(cell['reference'],text,cell['lang'],entries))
            if alpha==0:
                if result.text!=guard_stock[cell['id']]:
                    raise ValueError('zero-bonus guard differs from stock')
                controls[cell['id']]=row
            else:
                row['control_id']='alpha0:'+cell['id']
                for count in score.COUNTS:
                    row[count+'_delta']=row[count]-controls[cell['id']][count]
            guards.append(row)
        rows += normal + (held if alpha else []) + guards
        evidence=dict(alpha=alpha,takes=normal,leave_scripts_out=held,guard=guards)
        (output/f'alpha-{alpha:g}.json').write_text(json.dumps(evidence,ensure_ascii=False,indent=2)+'\n')
        totals=[]
        for feed in (640,512,800):
            for lang in ('de','en'):
                selected=[r for r in normal if r['feed']==feed and r['lang']==lang]
                left=[r for r in held if r['feed']==feed and r['lang']==lang]
                total=dict(feed=feed,lang=lang,listed_delta=sum(r.get('listed_errors_delta',0) for r in selected),
                    nonlisted_delta=sum(r.get('other_errors_delta',0)+r.get('number_format_errors_delta',0) for r in selected),
                    leave_script_errors_delta=sum(r.get('errors_delta',0) for r in left))
                total['passed']=alpha>0 and total['listed_delta']<=-3 and total['nonlisted_delta']<=1 and total['leave_script_errors_delta']<=0
                totals.append(total)
        false_accepts=sum(r['dictionary_insertions']+r['dictionary_substitutions'] for r in guards)
        outcome=dict(alpha=alpha,totals=totals,guard_false_accepts=false_accepts,
                     introduced_guard_false_accepts=sum(max(0,r.get('dictionary_insertions_delta',0))+max(0,r.get('dictionary_substitutions_delta',0)) for r in guards),
                     passed=false_accepts==0 and all(r['passed'] for r in totals),seconds=time.monotonic()-begun)
        outcomes.append(outcome)
        save()
        (output/'outcomes.json').write_text(json.dumps(outcomes,indent=2)+'\n')
        print(json.dumps(outcome),flush=True)
    passing=[row['alpha'] for row in outcomes if row['passed']]
    chosen=max(passing) if passing else None
    diagnostic_alpha=chosen if chosen is not None else min((row for row in outcomes if row['alpha']>0),key=lambda row:sum(t['listed_delta'] for t in row['totals']))['alpha']
    carried_control=takes(0,'carry',True)
    carried=takes(diagnostic_alpha,'carry',True)
    reset=json.loads((output/f'alpha-{diagnostic_alpha:g}.json').read_text())['takes']
    # Keep only with per-language benefit and no newly introduced harm against reset.
    carried_held=takes(diagnostic_alpha,'leave-script-out',True) if chosen is not None else []
    keep=chosen is not None and sum(r['errors'] for r in carried)<sum(r['errors'] for r in reset)
    for feed in (640,512,800):
        for lang in ('de','en'):
            selected=[r for r in carried if r['feed']==feed and r['lang']==lang]
            held=[r for r in carried_held if r['feed']==feed and r['lang']==lang]
            keep=keep and (sum(r['listed_errors_delta'] for r in selected)<=-3
                and sum(r['other_errors_delta']+r['number_format_errors_delta'] for r in selected)<=1
                and bool(held) and sum(r['errors_delta'] for r in held)<=0)
    rows+=carried_control+carried+carried_held;save()
    verdict=dict(chosen_alpha=chosen,verdict='advance' if chosen is not None else 'stop',
                 carried_alpha=diagnostic_alpha,carry_predictor_and_tree=keep,
                 carry_rows=carried,carry_controls=carried_control,carry_leave_scripts_out=carried_held,
                 limits='Development evidence; no phone result or adoption. Guards require absolute zero, including pre-existing baseline errors.')
    (output/'verdict.json').write_text(json.dumps(verdict,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({k:v for k,v in verdict.items() if k not in ('carry_rows','carry_controls')}),flush=True)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root',type=Path,required=True)
    parser.add_argument('--runtime',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--alpha',type=float,nargs='+',default=[0,.1,.25,.5,.75,1,1.5,2])
    args=parser.parse_args()
    if args.alpha[0]!=0 or len(set(args.alpha))!=len(args.alpha) or any(a<0 for a in args.alpha):
        parser.error('alpha grid must start with zero, be unique and nonnegative')
    run(args)


if __name__=='__main__':
    main()
