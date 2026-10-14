#!/usr/bin/env python3
"""Prepare local inventories and staging only. Never runs inference or accesses a device.

All private manifests and staged assets remain under corpus/lab/release-1a.
Existing smoke evidence is retained. This is not a launch command.
"""
import json
import math
import os
from pathlib import Path
import subprocess
import wave
import measurement_identity as identity
import variant

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'corpus/lab/release-1a'


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + '\n')


def link(source, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists():
        if identity.sha(source) != identity.sha(destination):
            raise ValueError('staged artifact differs: ' + str(destination))
        return
    os.link(source, destination)


def main():
    for sid in ('de-4', 'de-5', 'en-4', 'en-5'):
        if (ROOT/'corpus/scripts'/(sid+'.txt')).exists():
            raise ValueError('reserved hold-out is present; refuse preparation')
    vpath = ROOT / 'variants/dict-beam8-fp32-s20-stock.json'
    v = variant.load(vpath)
    endpoints = json.loads((OUT / 'endpoint-suite.json').read_text())['inputs']
    with (ROOT / 'corpus/takes.json').open() as source:
        names = sorted(json.load(source))
    takes = []
    for name in names:
        path = ROOT / 'corpus/files/benchmark' / name
        with wave.open(str(path)) as audio:
            samples = audio.getnframes()
        takes.append(dict(id=name, path=str(path.relative_to(ROOT)), sha256=identity.sha(path), samples=samples))
    provenance = dict(platform='laptop', build_sha256=identity.digest(identity.source_files(ROOT)),
        runtime_sha256=identity.digest(identity.runtime_files()), evaluator_sha256=identity.digest(identity.evaluator_files(ROOT)),
        inputs_sha256=identity.digest(takes+endpoints), references_sha256=identity.digest(identity.reference_files(ROOT)),
        feed=640, timing_protocol='diagnostic-1a-v1', model_sha256=identity.digest(identity.model_files(ROOT,v)),
        configuration_sha256=identity.sha(vpath),dictionary_sha256=identity.sha(ROOT/'corpus/lab'/v['hotwords']['file']))
    full_inputs = takes + endpoints
    write(OUT/'diagnostic-suite.json',dict(provenance=provenance, inputs=full_inputs))
    write(OUT/'quality-suite.json',dict(provenance=dict(provenance,inputs_sha256=identity.digest(takes),timing_protocol='quality-three-feed-v1'),inputs=takes))
    app = ROOT/'app/build/outputs/apk/benchmark/app-benchmark.apk'
    test = ROOT/'app/build/outputs/apk/androidTest/benchmark/app-benchmark-androidTest.apk'
    phone_runs=[]
    for mode in ('unpaced-only','paced-only','persistent','historical-full'):
        directory=OUT/'push'/mode
        directory.mkdir(parents=True,exist_ok=True)
        (directory/'variant.json').write_bytes(vpath.read_bytes())
        for source in sorted((ROOT/'models'/v['model']).iterdir()):
            if source.is_file(): link(source,directory/'model'/source.name)
        link(ROOT/'models/silero_vad.onnx',directory/'silero_vad.onnx')
        link(ROOT/'corpus/lab'/v['hotwords']['file'],directory/v['hotwords']['file'])
        phone_inputs=[]
        for item in takes:
            phone_inputs.append(dict(item,path='../benchmark/'+item['id']))
        for item in endpoints:
            source=ROOT/item['path']; link(source,directory/'fixtures'/source.name)
            phone_inputs.append(dict(item,path='fixtures/'+source.name))
        artifacts={str(p.relative_to(directory)):identity.sha(p) for p in sorted(directory.rglob('*')) if p.is_file() and p.name not in ('measurement.json','historical-manifest.json')}
        pp=dict(provenance,platform='phone',build_sha256=identity.sha(app),mode=mode,inputs_sha256=identity.digest(phone_inputs),
            runtime_sha256=identity.sha(ROOT/'app/libs/sherpa-onnx-1.13.8.aar'),
            model_sha256=identity.digest({k:val for k,val in artifacts.items() if k.startswith('model/')}),
            timing_protocol='historical-full-v1' if mode=='historical-full' else 'diagnostic-1a-v1')
        repetitions=2 if mode=='persistent' else 1
        manifest=dict(provenance=pp,mode=mode,repetitions=repetitions,artifacts=artifacts,inputs=phone_inputs)
        write(directory/('historical-manifest.json' if mode=='historical-full' else 'measurement.json'),manifest)
        phone_runs.append(dict(mode=mode,staging=str(directory.relative_to(ROOT)),process_repetitions=2,
            within_process_repetitions=repetitions,timeout_seconds=2400 if mode=="persistent" else 1800,
            warmup_seconds=1,
            max_decodes=1 + (sum(math.ceil(t["samples"]/16000/20)+1 for t in takes) +
                (sum(math.ceil(i["stop_sample"]/16000/20)+1 for i in endpoints) if mode!="historical-full" else
                 sum(math.ceil(t["samples"]/16000/20)+1 for t in (min(takes,key=lambda t:t["samples"]),max(takes,key=lambda t:t["samples"]))))) * repetitions,
            input_audio_seconds=round(sum(i['samples']/16000 for i in takes)*repetitions +
                (sum(i['stop_sample']/16000 for i in endpoints)*repetitions if mode!='historical-full' else (min(t['samples'] for t in takes)+max(t['samples'] for t in takes))/16000) + 1,3)))
    order=['unpaced-only','paced-only','persistent','historical-full','historical-full','persistent','paced-only','unpaced-only']
    write(OUT/'manifest.json',dict(status='not ready',source_commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
        source_files=identity.source_files(ROOT),evaluator_revision=4,variant_schema=4,
        apk=dict(path=str(app.relative_to(ROOT)),sha256=identity.sha(app)),test_apk=dict(path=str(test.relative_to(ROOT)),sha256=identity.sha(test)),
        environment='corpus/lab/release-1a/environment.json',phone_runs=phone_runs,phone_order=order,
        phone_window=None,phone_build_verified=False,phone_activation_verified=False,
        limits=dict(phone_processes=10,phone_planned_processes=8,phone_retries=2,phone_seconds=14400,
                    restoration_margin_seconds=900,laptop_processes=12,laptop_seconds=28800,storage_bytes=10_000_000_000),
        laptop_plan=dict(quality_controls=['baseline-stock','dict-beam8-fp32-s20-stock'],quality_process_repetitions=2,
                         diagnostic_modes=['unpaced-only','paced-only','persistent'],diagnostic_process_repetitions=2,
                         planned_processes=10,retries=2),
        takes=takes,endpoints=endpoints,decode_upper_bound_per_full_suite=sum(math.ceil(t['samples']/16000/20)+1 for t in takes),
        outstanding=['phone activation and actual build verification','authorized Europe/Zurich phone window and restoration owner',
                     'Android diagnostic smoke on the provisioned build','matching control reproduction in the authorized loop'] ))
    print('Prepared local manifest and four staged modes; launch remains disabled.')

if __name__=='__main__':
    main()
