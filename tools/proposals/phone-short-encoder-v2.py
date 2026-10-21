#!/usr/bin/env -S python3 -I
"""USER-APPLIED PROPOSAL ONLY: exact incident recovery; never run from this path.
Full replacement candidate for tools/phone after independent review and user apply.
Fixed app-scoped operations. No writable local imports.
"""
import sys
import base64
import math
import struct
if __name__ == '__main__' and not sys.flags.isolated:
    sys.exit('invoke the executable directly; isolated Python is required')

from pathlib import Path
import hashlib
import json
import os
import re
import fcntl
import uuid
import shutil
import shlex
import subprocess
import tarfile
import zipfile
import time
import signal


PKG = 'ch.og.dictation'
DEADLINE = None

# No CLI, environment variable or production input can expand this incident policy.
ENCODER_PROBE_RECOVERY = {
    'run': 'issue4-short-cpu-20261020-03',
    'app_sha256': '253982573618d6c4231259f80402e84698387c84db8735103fb7de30b15533d4',
    'test_sha256': '4ebde0968ae2908658037a32549dd09cfc45c6df82c3666c609870ae1410367b',
    'original_app_sha256': 'da17de22e58bae85a9b2ca9a9e1c4202a199c98bfdfcb11e98c864125f3ce106',
    'original_test_sha256': 'ee9df0f9e81b03eaf975436af2fbee362cc971ca881f52aa15ac71a3a01cf1a3',
    'config_sha256': '8c4a14cc51b263871f21c3d0f091b702ad292c4172542826feff2c748fd4dc94',
    'result_sha256': 'c24b366ec782470d8c7e8ca903acce4eb65530515d571ecc2c4bbfd91317bc5c',
    'generated': {
        'encoder-probe-output0.f32': '179bd4034980a9d971cc6543b6c90ea94e5f15d3d2aa848257ae359c343077ff',
        'encoder-probe-output1.i64': '35be322d094f9d154a8aba4733b8497f180353bd7ae7b0a15f90b586b549f28b',
        'encoder-probe-started': 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
        'encoder-probe-profile_2026-10-20_20-19-16_968.json': '50582fbf1576b67f54780e2b345b53ffae95c42d3ce7b604f4ed5afbb34bcb70',
    },
}


def child(argv, *, timeout=30, check=False, capture_output=True, text=False, **kwargs):
    """All host children share the command deadline, including finalizers."""
    remaining = timeout if DEADLINE is None else min(timeout, DEADLINE - time.monotonic())
    if remaining <= 0:
        raise Refused('command deadline expired; inspect/recover the journaled run')
    process = subprocess.Popen(argv, stdout=subprocess.PIPE if capture_output else None,
                               stderr=subprocess.PIPE if capture_output else None,
                               text=text, start_new_session=True, **kwargs)
    try:
        stdout, stderr = process.communicate(timeout=remaining)
    except BaseException:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.wait()
        if process.stdout:
            process.stdout.close()
        if process.stderr:
            process.stderr.close()
        raise
    result = subprocess.CompletedProcess(argv, process.returncode, stdout, stderr)
    if check:
        result.check_returncode()
    return result


def identity(info):
    return {key: info[key] for key in ('sha256', 'version_code')}



def diagnostic_manifest(manifest, package):
    """Only diagnostic installs may vary these optional native-driver declarations.

    Unknown attributes, required drivers, other libraries and all other manifest
    content stay in the fingerprint. Ordinary installs use the original hash.
    """
    drivers = {'libOpenCL.so', 'libOpenCL-pixel.so',
               'libedgetpu_litert.so', 'libedgetpu_util.so'}
    namespace = r'(?:android|' + re.escape('http://schemas.android.com/apk/res/android') + ')'
    name_pattern = re.compile(r'A: ' + namespace +
        r':name\(0x01010003\)="([^"]+)" \(Raw: "\1"\)')
    required_pattern = re.compile(r'A: ' + namespace +
        r':required\(0x0101028e\)=false')
    extraction_pattern = re.compile(r'A: ' + namespace +
        r':extractNativeLibs\(0x010104ea\)=(?:true|false)')
    lines, kept, parents, index = manifest.splitlines(), [], [], 0
    while index < len(lines):
        line = lines[index]
        indent = len(line) - len(line.lstrip())
        element = re.fullmatch(r'E: ([A-Za-z0-9_-]+)', line.strip())
        if element:
            while parents and parents[-1][0] >= indent:
                parents.pop()
            if element.group(1) == 'uses-native-library' and parents and parents[-1][1] == 'application':
                end = index + 1
                while end < len(lines) and len(lines[end]) - len(lines[end].lstrip()) > indent:
                    end += 1
                attributes = [item.strip() for item in lines[index + 1:end]]
                names = [match.group(1) for item in attributes
                         if (match := name_pattern.fullmatch(item))]
                if (len(attributes) == 2 and len(names) == 1 and names[0] in drivers
                        and sum(bool(required_pattern.fullmatch(item)) for item in attributes) == 1):
                    index = end
                    continue
            parents.append((indent, element.group(1)))
        elif (package == PKG + '.test' and parents and parents[-1][1] == 'application'
              and extraction_pattern.fullmatch(line.strip())):
            index += 1
            continue
        kept.append(line)
        index += 1
    return hashlib.sha256('\n'.join(kept).encode()).hexdigest()


def test_matches(receipt, observed):
    if not receipt.get('diagnostic'):
        return True
    allowed = [receipt['test_apk']]
    if receipt['phase'] in ('installing', 'recovering', 'restoring-apk'):
        allowed += [receipt.get('pending_test_apk'), receipt['original_test_apk']]
    return observed.get('test_apk') in allowed



class Refused(RuntimeError):
    pass


def validate_profile(payload):
    if len(payload) > 64 * 1024 * 1024:
        raise Refused('profile exceeds the fixed 64MiB export bound')
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise Refused('duplicate profile field')
            result[key] = value
        return result
    value = json.loads(payload, object_pairs_hook=unique)
    fields = {'schema', 'clock', 'enabled', 'spanLimit', 'sessionLimit', 'attempted', 'retained',
              'dropped', 'runFailures', 'clockFailures', 'sessionDropped', 'activeAtFlush',
              'complete', 'valid', 'sessions', 'spans'}
    roles = {'parakeet.encoder', 'parakeet.decoder', 'parakeet.joiner', 'cohere.encoder',
             'cohere.decoder', 'lid.encoder', 'lid.decoder'}
    def number(item):
        return type(item) is int and 0 <= item <= 2**64 - 1
    if not isinstance(value, dict) or set(value) != fields or value['schema'] != 1 or value['clock'] != 'CLOCK_MONOTONIC':
        raise Refused('unsupported numeric-only profile schema')
    if any(type(value[key]) is not bool for key in ('enabled', 'complete', 'valid')) or any(
            not number(value[key]) for key in ('schema', 'spanLimit', 'sessionLimit', 'attempted', 'retained',
                                             'dropped', 'runFailures', 'clockFailures', 'sessionDropped', 'activeAtFlush')):
        raise Refused('invalid profile counters')
    if value['spanLimit'] != 262144 or value['sessionLimit'] != 128 or not isinstance(value['spans'], list) or not isinstance(value['sessions'], list):
        raise Refused('unsupported profile capacity')
    if len(value['spans']) != value['retained'] or len(value['spans']) > 262144 or len(value['sessions']) > 128:
        raise Refused('profile exceeds fixed record bounds')
    sessions = {}
    for row in value['sessions']:
        if not isinstance(row, dict) or set(row) != {'sessionId', 'role'} or not number(row['sessionId']) or row['sessionId'] == 0 or (not isinstance(row['role'], str) or row['role'] not in roles) or row['sessionId'] in sessions:
            raise Refused('invalid fixed-role session')
        sessions[row['sessionId']] = row['role']
    for row in value['spans']:
        if not isinstance(row, dict) or set(row) != {'role', 'sessionId', 'startNs', 'endNs', 'status'}:
            raise Refused('unsupported profile span')
        if any(not number(row[key]) for key in ('sessionId', 'startNs', 'endNs', 'status')) or row['status'] not in (0, 1) or (not isinstance(row['role'], str) or row['role'] not in roles) or sessions.get(row['sessionId']) != row['role'] or row['endNs'] < row['startNs'] or row['endNs'] > 2**63 - 1:
            raise Refused('invalid numeric profile span')
    if not value['complete'] or not value['valid']:
        raise Refused('native profile is incomplete or invalid; evidence remains on device')
    if any(value[key] for key in ('dropped', 'runFailures', 'clockFailures', 'sessionDropped', 'activeAtFlush')):
        raise Refused('native profile has dropped, failed, or in-flight work')
    if value['attempted'] != value['retained'] or any(row['status'] for row in value['spans']):
        raise Refused('native profile counters or span statuses are inconsistent')
    if not value['enabled'] and value['attempted']:
        raise Refused('disabled native profile contains spans')
    return value


def profile_binding(measured, payload):
    """Bind one successful current measurement to its exact numeric-only trace."""
    value = validate_profile(payload)
    report = measured.get('report', {}) if isinstance(measured, dict) else {}
    timeline = report.get('timeline', {}) if isinstance(report, dict) else {}
    native = timeline.get('nativeProfile', {}) if isinstance(timeline, dict) else {}
    if (not isinstance(measured, dict) or measured.get('outcome') != 'complete' or not isinstance(native, dict)
            or native.get('path') != 'profile.json' or native.get('complete') is not True
            or type(native.get('enabled')) is not bool or native['enabled'] != value['enabled']
            or type(native.get('byteLimit')) is not int or native['byteLimit'] != 64 * 1024 * 1024
            or type(native.get('bytes')) is not int or native['bytes'] != len(payload)
            or native.get('sha256') != hashlib.sha256(payload).hexdigest()
            or timeline.get('valid') is not True or type(timeline.get('droppedEvents')) is not int
            or timeline['droppedEvents'] != 0 or timeline.get('nativeClockAlignment') != 'verified-brackets'):
        raise Refused('current measurement does not verify this complete native profile')
    return dict(bytes=len(payload), sha256=hashlib.sha256(payload).hexdigest(), enabled=value['enabled'])


def digest(path):
    result = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(block)
    return result.hexdigest()


def ordinary_path(path):
    if any(item.is_symlink() for item in (path, *path.parents)):
        raise Refused('host state cannot use symlink paths')


def sync_directory(path):
    descriptor = os.open(path, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def same_lab(left, right):
    def measured(value):
        return dict(value, files={k: v for k, v in value['files'].items() if k not in ('result.json', 'profile.json')})
    return measured(left) == measured(right)


def recorded_lab_restoration(receipt, observed):
    return bool(receipt.get('lab_restored') and receipt.get('original_apk')
                and observed['apk'] == receipt['original_apk']
                and not observed['lab']['accessible'])


def recovery_result(receipt, observed):
    verification = 'not-in-scope' if receipt['original'] is None else (
        'recorded-before-apk-restore' if recorded_lab_restoration(receipt, observed) else 'current')
    return dict(run=receipt['run'], outcome='recovered', lab_verification=verification)


def cleanup_matches(observed, expected, started):
    if not observed['exists']:
        return True
    if not started:
        return observed == expected
    return all(expected['files'].get(name) == value for name, value in observed['files'].items())


def remove_owned(device, state, receipt, name):
    deleting = receipt.setdefault('deleting', [])
    if name not in deleting:
        deleting.append(name)
        save(state / 'active.json', receipt)
    device.remove(name)


def recoverable(receipt, observed):
    if not receipt or observed['device'] != receipt['device'] or not test_matches(receipt, observed):
        return False
    allowed = [receipt['apk']]
    if receipt['phase'] in ('installing', 'recovering', 'restoring-apk'):
        allowed += [receipt.get('pending_apk'), receipt.get('original_apk')]
    if observed['apk'] not in allowed or observed.get('busy', False):
        return False
    if receipt.get('original_apk') and (not Path(receipt['rollback']).is_file()
            or digest(receipt['rollback']) != receipt['original_apk']['sha256']):
        return False
    if receipt.get('diagnostic') and (not Path(receipt['test_rollback']).is_file() or
            digest(receipt['test_rollback']) != receipt['original_test_apk']['sha256']):
        return False
    if receipt['original'] is None:
        return True
    if recorded_lab_restoration(receipt, observed):
        return True
    if not observed['lab']['accessible'] or observed['owner'] not in (None, receipt['run']):
        return False
    if not cleanup_matches(observed['discard'], receipt['before'], 'discard' in receipt.get('deleting', [])):
        return False
    if observed['disposal']['exists'] and ('disposal_expected' not in receipt or not cleanup_matches(
            observed['disposal'], receipt['disposal_expected'], 'disposal' in receipt.get('deleting', []))):
        return False
    baseline = observed['original'] == receipt['original'] or observed['lab'] == receipt['original']
    allowed_lab = not observed['lab']['exists'] or (
        observed['lab'] in (receipt['original'],receipt['before']) or short_inventory(receipt,observed['lab'])
        if receipt.get('short_encoder') else any(same_lab(observed['lab'], value)
                   for value in (receipt['original'], receipt['before'], receipt['candidate'])))
    return baseline and allowed_lab


def inventory(path):
    if path.is_symlink():
        raise Refused('symlinks are not accepted')
    for entry in path.rglob('*'):
        if entry.is_symlink() or not (entry.is_file() or entry.is_dir()):
            raise Refused('only ordinary files and directories are accepted')
        if not re.fullmatch(r'[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*', entry.relative_to(path).as_posix()):
            raise Refused('unsupported filename')
    return {'accessible': True, 'exists': path.exists(), 'files': {
        p.relative_to(path).as_posix(): digest(p)
        for p in sorted(path.rglob('*')) if p.is_file()}}


def save(path, value):
    ordinary_path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + '-' + uuid.uuid4().hex + '.pending')
    with temporary.open('x') as output:
        json.dump(value, output, sort_keys=True)
        output.flush()
        os.fsync(output.fileno())
    temporary.replace(path)
    sync_directory(path.parent)


def retain_apk(device, state, source):
    if source.is_symlink() or not source.is_file() or source.suffix != '.apk':
        raise Refused('need an ordinary local APK file')
    folder = state / 'apks'
    ordinary_path(folder)
    folder.mkdir(exist_ok=True)
    temporary = folder / (uuid.uuid4().hex + '.apk')
    try:
        with source.open('rb') as input_file, temporary.open('xb') as output:
            shutil.copyfileobj(input_file, output)
            output.flush()
            os.fsync(output.fileno())
        info = device.apk_info(temporary)
        if digest(temporary) != info['sha256']:
            raise Refused('APK snapshot changed during verification')
        target = folder / (info['sha256'] + '.apk')
        ordinary_path(target)
        if target.exists():
            if digest(target) != info['sha256']:
                raise Refused('retained APK changed')
        else:
            temporary.chmod(0o400)
            temporary.replace(target)
            sync_directory(folder)
        return target, info
    finally:
        temporary.unlink(missing_ok=True)


def run(argv, *, device, state):
    global DEADLINE
    previous = DEADLINE
    budget = 600 if argv and argv[0] in ('lab', 'test', 'install', 'profile-install', 'push-variant') else 900 if argv and argv[0] == 'recover' else 30
    DEADLINE = min(previous, time.monotonic() + budget) if previous is not None else time.monotonic() + budget
    try:
        return bounded_run(argv, device=device, state=state)
    finally:
        DEADLINE = previous


def bounded_run(argv, *, device, state):
    ordinary_path(Path(state))
    if argv == ['inspect']:
        return execute(argv, device=device, state=state)
    if len(argv) == 1 and argv[0] in ('status', 'versions', 'thermal', 'logs', 'crash', 'subtypes', 'pull-report'):
        return device.readonly(argv[0])
    grammar = {'push-variant': (3, 2), 'recover': (2, 1), 'uninstall': (1, None), 'stop-app': (1, None),
               'lab': (3, 1), 'test': (3, 1), 'pull-results': (2, 1), 'install': (4, 3),
               'profile-install': (6, 5), 'pull-profile': (2, 1)}
    if not argv or argv[0] not in grammar or len(argv) != grammar[argv[0]][0]:
        raise Refused('unknown command or arguments')
    token = argv[grammar[argv[0]][1]] if grammar[argv[0]][1] is not None else None
    if token is not None and not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,63}', token):
        raise Refused('invalid run identity')
    if argv[0] == 'push-variant':
        source = Path(argv[1])
        inventory(source)
        if not (source / 'variant.json').is_file() or not (source / 'model').is_dir():
            raise Refused('need variant.json and model directory')
    state = Path(state)
    state.mkdir(parents=True, exist_ok=True)
    with (state / 'command.lock').open('a') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise Refused('another command is active') from None
        if token and argv[0] != 'recover' and (state / (token + '.json')).exists():
            raise Refused('run identity was already completed; choose a fresh identity')
        return execute(argv, device=device, state=state)


def exact_recovery_export(path, payload, expected):
    """Durably preserve only preverified incident bytes; never overwrite evidence."""
    ordinary_path(path)
    if path.exists():
        if not path.is_file() or digest(path) != expected or path.stat().st_size != len(payload):
            raise Refused('existing incident export differs; preserve and inspect it')
        return
    temporary = path.with_name(path.name + '-' + uuid.uuid4().hex + '.pending')
    with temporary.open('xb') as output:
        os.fchmod(output.fileno(), 0o600)
        output.write(payload)
        output.flush()
        os.fsync(output.fileno())
    if digest(temporary) != expected:
        raise Refused('pending incident export differs; preserve and inspect it')
    try:
        os.link(temporary, path)  # Atomic publication, never overwrite evidence.
    except FileExistsError:
        if not path.is_file() or digest(path) != expected or path.stat().st_size != len(payload):
            raise Refused('existing incident export differs; preserve and inspect it')
    sync_directory(path.parent)
    temporary.unlink()  # Exceptions above intentionally preserve partial evidence.
    sync_directory(path.parent)


def reconcile_encoder_probe_recovery(device, state, receipt, observed):
    """One incident only. Preserve evidence, journal exact drift, then normal recover.

    This never changes completion or acceptance. All device mutation remains in
    the existing restoration path after ownership/baseline/inventory checks.
    """
    policy = ENCODER_PROBE_RECOVERY
    expected_app = dict(sha256=policy['app_sha256'], version_code=12)
    expected_test = dict(sha256=policy['test_sha256'], version_code=0)
    original_app = dict(sha256=policy['original_app_sha256'], version_code=12)
    original_test = dict(sha256=policy['original_test_sha256'], version_code=0)
    if (receipt.get('run') != policy['run'] or receipt.get('kind') != 'lab'
            or receipt.get('diagnostic') is not True or receipt.get('phase') != 'running'
            or receipt.get('profile_complete') is not False or 'profile_binding' in receipt
            or receipt.get('copy_pending') or receipt.get('lab_restored')
            or receipt.get('ownership_handoff') or receipt.get('remote_released')
            or receipt.get('apk') != expected_app or receipt.get('test_apk') != expected_test
            or receipt.get('original_apk') != original_app or receipt.get('original_test_apk') != original_test):
        raise Refused('not the fixed incomplete encoder-probe incident')
    # Require ordinary, retained exact originals before exporting or journaling.
    for field, expected in (('rollback', original_app), ('test_rollback', original_test)):
        path = state / 'apks' / (expected['sha256'] + '.apk')
        ordinary_path(path)
        if Path(receipt[field]) != path or not path.is_file() or digest(path) != expected['sha256']:
            raise Refused('fixed incident original rollback changed')
    candidate = receipt['candidate']
    files = candidate.get('files', {})
    if (candidate.get('accessible') is not True or candidate.get('exists') is not True
            or files.get('encoder-probe.json') != policy['config_sha256']
            or any(name in files for name in (*policy['generated'], 'result.json'))):
        raise Refused('fixed incident candidate or frozen configuration changed')
    expanded = dict(candidate, files=dict(files, **policy['generated'], **{'result.json': policy['result_sha256']}))

    def snapshot():
        current = device.inspect()
        current['test_apk'] = device.app_identity(PKG + '.test')
        return current

    device.idle()  # Read-only quiescence; never implicitly stop the app.
    current = snapshot()
    if (current != observed or current.get('device') != receipt['device']
            or current.get('owner') != policy['run'] or current.get('apk') != expected_app
            or current.get('test_apk') != expected_test or current.get('busy', False)
            or current.get('original') != receipt['original']
            or current.get('lab') != expanded
            or any(current[name]['exists'] for name in ('stage', 'discard', 'disposal'))):
        raise Refused('fixed incident device, owner, baseline or exact file inventory changed')

    result = device.encoder_probe_recovery_result()
    if not isinstance(result, bytes) or len(result) > 1024 * 1024:
        raise Refused('incident result exceeds fixed 1MiB export bound')
    if hashlib.sha256(result).hexdigest() != policy['result_sha256']:
        raise Refused('incident result digest differs')
    value = json.loads(result)
    if (not isinstance(value, dict) or value.get('schema') != 1 or value.get('eligible') is not False
            or value.get('freeze_sha256') != policy['config_sha256']
            or value.get('build_sha256') != policy['app_sha256']
            or value.get('test_build_sha256') != policy['test_sha256']
            or not isinstance(value.get('frozen'), dict) or value['frozen'].get('provider') != 'cpu'):
        raise Refused('incident result does not bind the fixed synthetic CPU probe')
    outputs = value.get('outputs')
    names = ('encoder-probe-output0.f32', 'encoder-probe-output1.i64')
    if (not isinstance(outputs, list) or len(outputs) != 2 or any(not isinstance(row, dict) for row in outputs)
            or {row.get('file') for row in outputs} != set(names)):
        raise Refused('incident result does not preserve both tensor sidecars')
    for row in outputs:
        raw = base64.b64decode(row.get('raw_base64', ''), validate=True)
        expected = policy['generated'][row['file']]
        if row.get('sha256') != expected or hashlib.sha256(raw).hexdigest() != expected:
            raise Refused('incident embedded tensor digest differs from fixed sidecar')
    profile_name = 'encoder-probe-profile_2026-10-20_20-19-16_968.json'
    profile = device.encoder_probe_recovery_profile()
    if not isinstance(profile, bytes) or len(profile) > 64 * 1024 * 1024:
        raise Refused('incident ORT profile exceeds fixed 64MiB export bound')
    if hashlib.sha256(profile).hexdigest() != policy['generated'][profile_name]:
        raise Refused('incident ORT profile digest differs')
    # Export only after every check, and recheck quiescence/state before disposal.
    device.idle()
    if snapshot() != current:
        raise Refused('fixed incident changed while reading evidence')
    folder = state / 'results'
    ordinary_path(folder)
    folder.mkdir(mode=0o700, exist_ok=True)
    result_path = folder / (policy['run'] + '-encoder-probe-recovery.json')
    profile_path = folder / (policy['run'] + '-encoder-probe-ort-profile.json')
    record_path = folder / (policy['run'] + '-encoder-probe-recovery-receipt.json')
    record = dict(run=policy['run'], kind='incomplete-encoder-probe-recovery-evidence',
        phase_before_recovery='running', profile_complete=False, tensor_verdict='not evaluated',
        native_profile_verdict='not complete; no role-profile acceptance',
        config_sha256=policy['config_sha256'], candidate_before=candidate, candidate_reconciled=expanded,
        result=dict(path=str(result_path), sha256=policy['result_sha256'], bytes=len(result)),
        ort_profile=dict(path=str(profile_path), sha256=policy['generated'][profile_name], bytes=len(profile)))
    ordinary_path(record_path)
    if record_path.exists() and json.loads(record_path.read_text()) != record:
        raise Refused('existing incident evidence receipt differs')
    exact_recovery_export(result_path, result, policy['result_sha256'])
    exact_recovery_export(profile_path, profile, policy['generated'][profile_name])
    if not record_path.exists():
        save(record_path, record)
    device.idle()
    if snapshot() != current:
        raise Refused('fixed incident changed before reconciliation')
    receipt['encoder_probe_recovery'] = record
    receipt['candidate'] = expanded
    # phase stays running and profile_complete stays False. No retry/acceptance.
    save(state / 'active.json', receipt)
    return current


SHORT_ENCODER = 'short-encoder-v1'
# WebGPU runtime session creation without strict CPU-fallback rejection; never Run.
PLACEMENT = 'encoder-placement-v1'
PLACEMENT_SCOPE = 'placement listing only; Run not executed'
SELF_SOURCE = 'tools/proposals/phone-short-encoder-v2.py'
SHORT_LIMIT = 1024 * 1024
SHORT_INPUTS = {
    'audio_signal.f32': 'e8b31e302d11fbf7da124b537ba2d44f88e165da03c6557e2b0f6dc486e025bb',
    'length.i64': '35e3a6176b50da27fcb868bf840f0e76290bd9bf55c40545e67bac66353d0674',
}
SHORT_RUNTIME = dict(version='1.31.0',
    official_aar_sha256='72683787a80d0608a3121b967d8e1239aef48286826661c953211626690540a7',
    core_sha256='8005a741e57117b4d7b476417706c0e9af13cf1b89db47e60f0cbc56e1af91e0',
    jni_sha256='5f2f1a99fd9f65fd6271eed825632f4ae5ada95f5cc13d66605888ccb80cdc29',
    source_commit='402979ac1bef89dcd55add41eaa7277c407599eb')
SHORT_MODEL = {'encoder.onnx':'2144d185b85e7c75077a19afbd8b77f0cb8daf814844c79f213e68e8fbf5a725',
               'encoder.weights':'9a22d372c51455c34f13405da2520baefb7125bd16981397561423ed32d24f36'}


def short_declaration(source, staged, receipt, observed):
    config = source / 'encoder-probe.json'
    if not config.exists():
        return None
    if not receipt or receipt.get('diagnostic') is not True or config.stat().st_size > 65536:
        raise Refused('short encoder needs a diagnostic APK pair and bounded frozen manifest')
    value = json.loads(config.read_bytes())
    fields = {'schema','protocol','input_kind','shape','true_length','provider','eligible','scope',
              'atol','rtol','model','builds','inputs','staging_envelope','runtime','sources'}
    source_names = {'tools/encoder_probe.py','tools/encoder_conversion.py',SELF_SOURCE,
        'app/src/encoderProbe/java/ch/og/dictation/EncoderProbe.java',
        'app/src/androidTest/kotlin/ch/og/dictation/LabTest.kt','app/build.gradle.kts'}
    expected_files = {'encoder-probe.json','variant.json',*SHORT_INPUTS,
                      'model/encoder.onnx','model/encoder.weights'}
    placement = isinstance(value, dict) and value.get('protocol') == PLACEMENT
    if (not isinstance(value, dict) or set(value) != fields or value.get('protocol') not in (SHORT_ENCODER, PLACEMENT)
            or value.get('schema') != 1 or value.get('input_kind') != 'synthetic silence; no recordings'
            or value.get('shape') != [1,128,17] or value.get('true_length') != 17
            or value.get('provider') not in (('webgpu',) if placement else ('cpu','webgpu'))
            or value.get('eligible') is not False
            or value.get('scope') != (PLACEMENT_SCOPE if placement else 'short feasibility checkpoint; full take not run')
            or value.get('atol') != 1e-4 or value.get('rtol') != 1e-3
            or value.get('runtime') != SHORT_RUNTIME or value.get('inputs') != SHORT_INPUTS
            or value.get('model') != SHORT_MODEL
            or set(staged['files']) != expected_files or set(value.get('sources', {})) != source_names
            or any(not re.fullmatch('[0-9a-f]{64}', item) for item in value['sources'].values())
            or value['sources'][SELF_SOURCE] != digest(Path(__file__))
            or value.get('builds') != dict(app_sha256=observed['apk']['sha256'],
                                         test_sha256=observed['test_apk']['sha256'])
            or value.get('staging_envelope') != {'variant.json': staged['files']['variant.json']}
            or value.get('model') != {name: staged['files']['model/' + name]
                                     for name in ('encoder.onnx','encoder.weights')}
            or any(staged['files'][name] != expected for name, expected in SHORT_INPUTS.items())):
        raise Refused('unsupported short encoder frozen declaration or staged bytes')
    return dict(protocol=value['protocol'], manifest=value, manifest_sha256=staged['files']['encoder-probe.json'],
                output_policy=dict(path='result.json', byte_limit=SHORT_LIMIT), attempt=None)


def short_inventory(receipt, current):
    expected = receipt['candidate']
    files = dict(current['files'])
    if receipt['short_encoder'].get('attempt'):
        files.pop('result.json', None)
    return dict(current, files=files) == expected


def short_result(value, declaration):
    """Dedicated synthetic receipt; execution completion never implies GPU proof."""
    attempt = declaration['attempt']
    plan = declaration['manifest']
    if plan['protocol'] == PLACEMENT:
        return placement_result(value, declaration)
    if (not isinstance(value, dict) or value.get('schema') != 1 or value.get('protocol') != SHORT_ENCODER
            or value.get('attempt_id') != attempt['id'] or value.get('eligible') is not False
            or value.get('freeze_sha256') != declaration['manifest_sha256'] or value.get('frozen') != plan
            or value.get('outcome') not in ('complete','failed')
            or type(value.get('pid')) is not int or value['pid'] <= 0
            or type(value.get('processStartElapsedMs')) is not int or value['processStartElapsedMs'] < 0
            or any(value.get(key) != 'unproven' for key in
                   ('adapter_driver_proof','fp32_shader_proof','residual_node_assignment'))):
        raise Refused('unbound or unsupported short encoder receipt')
    complete = value['outcome'] == 'complete'
    bindings = dict(build_sha256=plan['builds']['app_sha256'], test_build_sha256=plan['builds']['test_sha256'],
        runtime_version='1.31.0', runtime_core_sha256=SHORT_RUNTIME['core_sha256'],
        runtime_jni_sha256=SHORT_RUNTIME['jni_sha256'], optimization='ALL_OPT', execution_mode='SEQUENTIAL',
        intra_op_threads=4, inter_op_threads=1, profiling=False,
        cpu_fallback_disabled=plan['provider']=='webgpu')
    if plan['provider'] == 'webgpu':
        bindings['provider_options'] = dict(enableGraphCapture='0', enableInt64='1',
                                          preferredLayout='NCHW', dawnBackendType='Vulkan')
    for name, expected in bindings.items():
        if (complete or name in value) and value.get(name) != expected:
            raise Refused('short encoder installed identity or session options differ')
    memory = value.get('memory')
    phases = {'before-load','after-load','after-output','finally'}
    if (not isinstance(memory,list) or not 1 <= len(memory) <= 4 or any(not isinstance(row,dict) for row in memory)
            or len({row.get('phase') for row in memory}) != len(memory)
            or any(row.get('phase') not in phases or any(type(row.get(k)) is not int or row[k] < 0
                   for k in ('highWaterKb','pssKb','thermalStatus')) or row['thermalStatus'] > 6 for row in memory)
            or (complete and {row['phase'] for row in memory} != phases)):
        raise Refused('short encoder resource observations missing or invalid')
    for key in ('cold_load_ms','run_to_host_output_ms','initialization_elapsed_ms','run_to_failure_ms'):
        if ((complete and key != 'run_to_failure_ms') or key in value) and (type(value.get(key)) not in (int,float)
                or not math.isfinite(value[key]) or value[key] < 0):
            raise Refused('short encoder timing missing or invalid')
    if complete:
        if value.get('checkpoint') != 'outputs-materialized' or value.get('initialization_complete') is not True:
            raise Refused('short encoder output completion missing')
    elif (value.get('checkpoint') not in ('verify','native-load','provider-create','session-create','run-and-host-output')
          or not isinstance(value.get('failure'),str) or not 1 <= len(value['failure']) <= 16384):
        raise Refused('short encoder failed checkpoint missing')
    if complete or 'outputs' in value:
        rows = value.get('outputs')
        expected = [('encoder-probe-output0.f32','FLOAT',[1,1024,3],12288),
                    ('encoder-probe-output1.i64','INT64',[1],8)]
        if not isinstance(rows,list) or len(rows) != 2:
            raise Refused('short encoder tensors missing')
        for row, (name,dtype,shape,size) in zip(rows,expected):
            if (not isinstance(row,dict) or set(row) != {'file','dtype','shape','sha256','raw_base64'}
                    or row['file'] != name or row['dtype'] != dtype or row['shape'] != shape):
                raise Refused('short encoder tensor type or shape differs')
            raw = base64.b64decode(row['raw_base64'], validate=True)
            if len(raw) != size or hashlib.sha256(raw).hexdigest() != row['sha256']:
                raise Refused('short encoder tensor bytes differ')
            if dtype == 'FLOAT' and not all(math.isfinite(x[0]) for x in struct.iter_unpack('<f',raw)):
                raise Refused('short encoder has nonfinite FP32 output')
            if dtype == 'INT64' and struct.unpack('<q',raw) != (3,):
                raise Refused('short encoder output length differs')
    return value['outcome']


def placement_result(value, declaration):
    """Bounded placement listing. The wrapper checks shape and counts, never ADR-023 acceptance."""
    attempt = declaration['attempt']
    plan = declaration['manifest']
    if (not isinstance(value, dict) or value.get('schema') != 1 or value.get('protocol') != PLACEMENT
            or value.get('attempt_id') != attempt['id'] or value.get('eligible') is not False
            or value.get('freeze_sha256') != declaration['manifest_sha256'] or value.get('frozen') != plan
            or value.get('outcome') not in ('complete','failed')
            or type(value.get('pid')) is not int or value['pid'] <= 0
            or type(value.get('processStartElapsedMs')) is not int or value['processStartElapsedMs'] < 0
            or any(key in value for key in ('outputs','run_to_host_output_ms','run_to_failure_ms'))
            or any(value.get(key) != 'unproven' for key in ('adapter_driver_proof','fp32_shader_proof'))):
        raise Refused('unbound or unsupported placement receipt')
    complete = value['outcome'] == 'complete'
    bindings = dict(build_sha256=plan['builds']['app_sha256'], test_build_sha256=plan['builds']['test_sha256'],
        runtime_version='1.31.0', runtime_core_sha256=SHORT_RUNTIME['core_sha256'],
        runtime_jni_sha256=SHORT_RUNTIME['jni_sha256'], optimization='ALL_OPT', execution_mode='SEQUENTIAL',
        intra_op_threads=4, inter_op_threads=1, profiling=False, cpu_fallback_disabled=False,
        provider_options=dict(enableGraphCapture='0', enableInt64='1', preferredLayout='NCHW', dawnBackendType='Vulkan'))
    for name, expected in bindings.items():
        if (complete or name in value) and value.get(name) != expected:
            raise Refused('placement installed identity or session options differ')
    memory = value.get('memory')
    phases = {'before-load','after-load','finally'}
    if (not isinstance(memory,list) or not 1 <= len(memory) <= 3 or any(not isinstance(row,dict) for row in memory)
            or len({row.get('phase') for row in memory}) != len(memory)
            or any(row.get('phase') not in phases or any(type(row.get(k)) is not int or row[k] < 0
                   for k in ('highWaterKb','pssKb','thermalStatus')) or row['thermalStatus'] > 6 for row in memory)
            or (complete and {row['phase'] for row in memory} != phases)):
        raise Refused('placement resource observations missing or invalid')
    for key in ('cold_load_ms','initialization_elapsed_ms','placement_read_ms'):
        if (complete or key in value) and (type(value.get(key)) not in (int,float)
                or not math.isfinite(value[key]) or value[key] < 0):
            raise Refused('placement timing missing or invalid')
    if complete:
        if (value.get('checkpoint') != 'placement-listed' or value.get('initialization_complete') is not True
                or value.get('residual_node_assignment') not in ('listed','incomplete')):
            raise Refused('placement completion missing')
        listing = value.get('placement')
        text = lambda item: isinstance(item,str) and 1 <= len(item) <= 512
        # ONNX permits unnamed nodes, so only the op type must be nonempty.
        pair = lambda item: (isinstance(item,list) and len(item) == 2 and text(item[0])
                             and isinstance(item[1],str) and len(item[1]) <= 512)
        if (not isinstance(listing,dict) or set(listing) != {'complete','providers','causes','log_lines'}
                or type(listing['complete']) is not bool or type(listing['log_lines']) is not int
                or listing['log_lines'] < 0 or not isinstance(listing['providers'],dict)
                or len(listing['providers']) > 4 or not isinstance(listing['causes'],dict)
                or set(listing['causes']) != {'kernel_not_found','cpu_preferred'}):
            raise Refused('placement listing malformed')
        nodes = 0
        for provider, row in listing['providers'].items():
            if (not re.fullmatch(r'[A-Za-z]+ExecutionProvider', provider) or not isinstance(row,dict)
                    or set(row) != {'declared','all','nodes'} or type(row['declared']) is not int
                    or not 0 <= row['declared'] <= 20000 or type(row['all']) is not bool
                    or not isinstance(row['nodes'],list) or not all(map(pair,row['nodes']))
                    or (row['all'] and row['nodes'])):
                raise Refused('placement provider row malformed')
            nodes += len(row['nodes'])
        causes = listing['causes']
        if (nodes > 20000 or not isinstance(causes['kernel_not_found'],list)
                or not all(map(pair,causes['kernel_not_found'])) or len(causes['kernel_not_found']) > 20000
                or not isinstance(causes['cpu_preferred'],list)
                or not all(isinstance(item,str) and len(item) <= 512 for item in causes['cpu_preferred'])
                or len(causes['cpu_preferred']) > 20000):
            raise Refused('placement causes malformed')
        counted = bool(listing['providers']) and all(
            row['all'] or len(row['nodes']) == row['declared'] for row in listing['providers'].values())
        if listing['complete'] is not counted or value['residual_node_assignment'] != ('listed' if counted else 'incomplete'):
            raise Refused('placement completeness differs from declared counts')
    elif (value.get('checkpoint') not in ('verify','native-load','provider-create','session-create','placement-read')
          or not isinstance(value.get('failure'),str) or not 1 <= len(value['failure']) <= 16384
          or 'placement' in value):
        raise Refused('placement failed checkpoint missing')
    return value['outcome']


def short_capture(device, state, receipt, accept=False):
    declaration = receipt['short_encoder']
    attempt = declaration['attempt']
    device.idle()
    observed = device.inspect()
    observed['test_apk'] = device.app_identity(PKG + '.test')
    if (observed['device'] != receipt['device'] or observed['owner'] != receipt['run']
            or observed['apk'] != receipt['apk'] or observed['test_apk'] != receipt['test_apk']
            or observed['original'] != receipt['original']
            or not short_inventory(receipt, observed['lab'])):
        attempt['state'] = 'unresolved-execution'
        attempt['reason'] = 'unexpected device, ownership, APK or lab inventory'
        save(state / 'active.json', receipt)
        raise Refused('short encoder inventory changed; preserve and inspect before recovery')
    present = 'result.json' in observed['lab']['files']
    payload = device.short_encoder_result() if present else b''
    reason = 'no result from reserved attempt'
    valid = None
    truncated = len(payload) > SHORT_LIMIT
    if present and not truncated:
        if hashlib.sha256(payload).hexdigest() != observed['lab']['files']['result.json']:
            raise Refused('short encoder result changed while reading')
        try:
            if (attempt.get('accepted_result_sha256') is not None and
                    attempt['accepted_result_sha256'] != hashlib.sha256(payload).hexdigest()):
                raise Refused('result changed after its first accepted receipt')
            value = json.loads(payload)
            valid = short_result(value, declaration)
            reason = 'instrumentation completion unavailable'
        except (Refused,ValueError,TypeError,KeyError) as error:
            reason = str(error)[:2048]
    elif truncated:
        reason = 'result exceeds fixed byte bound; prefix retained'
    if (accept or attempt.get('accepted_result_sha256') is None) and attempt['instrumentation_success'] and valid:
        attempt['state'] = valid
        attempt['accepted_result_sha256'] = hashlib.sha256(payload).hexdigest()
        receipt['phase'] = 'active'
    elif not valid or not attempt['instrumentation_success']:
        attempt['state'] = 'unresolved-execution'
    attempt['reason'] = None if attempt['state'] in ('complete','failed') else reason
    device.idle()
    current = device.inspect()
    current['test_apk'] = device.app_identity(PKG + '.test')
    if current != observed:
        attempt.update(state='unresolved-execution',reason='inventory changed while reading result')
        save(state / 'active.json',receipt)
        raise Refused('short encoder inventory changed while reading result')
    if attempt['state'] in ('complete','failed') and valid == attempt['state']:
        exported = payload
    else:
        exported = json.dumps(dict(protocol=declaration['protocol'], outcome='unresolved-execution', eligible=False,
            attempt_id=attempt['id'], freeze_sha256=declaration['manifest_sha256'], reason=reason,
            result_present=present, truncated=truncated, payload_base64=base64.b64encode(payload[:SHORT_LIMIT]).decode(),
            payload_sha256=hashlib.sha256(payload[:SHORT_LIMIT]).hexdigest()),sort_keys=True).encode()
    folder = state / 'results'
    ordinary_path(folder)
    folder.mkdir(mode=0o700, exist_ok=True)
    identity = hashlib.sha256(exported).hexdigest()
    target = folder / (receipt['run'] + '-short-' + attempt['id'] + '-' + identity + '.json')
    exact_recovery_export(target, exported, identity)
    evidence = dict(path=str(target), sha256=identity, state=attempt['state'], bytes=len(exported))
    if evidence not in attempt.setdefault('evidence',[]):
        attempt['evidence'].append(evidence)
    save(state / 'active.json', receipt)
    return dict(run=receipt['run'], outcome=attempt['state'], **evidence)


def short_lab(device, state, receipt, test_apk):
    declaration = receipt['short_encoder']
    if declaration.get('attempt') is not None or receipt['phase'] != 'active':
        raise Refused('short encoder attempt already reserved; recover, never retry this run')
    device.idle()
    declaration['attempt'] = dict(id=uuid.uuid4().hex, state='unresolved-execution', instrumentation_success=False)
    receipt['phase'] = 'running'
    receipt['profile_complete'] = False
    receipt.pop('profile_binding', None)
    save(state / 'active.json', receipt)  # Immutable attempt reservation precedes launch.
    try:
        output = device.instrument(test_apk, native_profile=False, short_encoder_attempt=declaration['attempt']['id'])
        (state / (receipt['run'] + '-instrumentation.txt')).write_text(output)
        declaration['attempt']['instrumentation_success'] = bool(re.search(r'OK \([1-9][0-9]* tests?\)',output)
            and re.search(r'^INSTRUMENTATION_CODE:\s*-1\s*$',output,re.M)
            and not any(x in output for x in ('FAILURES!!!','INSTRUMENTATION_FAILED','Process crashed'))
            and not re.search(r'^INSTRUMENTATION_STATUS_CODE:\s*-\d+\s*$',output,re.M))
        save(state / 'active.json', receipt)
    except BaseException as error:
        declaration['attempt']['reason'] = str(error)[:2048]
        save(state / 'active.json', receipt)
        raise
    return short_capture(device,state,receipt,accept=True)


def execute(argv, *, device, state):
    state = Path(state)
    journal = state / 'active.json'
    receipt = json.loads(journal.read_text()) if journal.exists() else None
    observed = device.inspect()
    if (argv == ['inspect'] or argv[0] == 'profile-install' or (receipt and receipt.get('diagnostic'))) and hasattr(device, 'app_identity'):
        observed['test_apk'] = device.app_identity(PKG + '.test')
    accessible = observed['lab']['accessible']
    if argv == ['inspect']:
        return dict(observed, lab_status='observed' if accessible else 'inaccessible',
                    recoverable=recoverable(receipt, observed), recovery=receipt)
    if argv == ['uninstall']:
        if receipt or observed['owner']:
            raise Refused('recover the active run before destructive removal')
        device.idle()
        device.uninstall()
        return {'outcome': 'uninstalled', 'recoverable': False}
    if receipt:
        allowed = [receipt['apk']]
        if receipt['phase'] in ('installing', 'recovering', 'restoring-apk'):
            allowed += [receipt.get('pending_apk'), receipt.get('original_apk')]
        if observed['device'] != receipt['device'] or observed['apk'] not in allowed or not test_matches(receipt, observed):
            raise Refused('device or installed app changed; recovery refused')
    if argv == ['stop-app']:
        if observed['owner'] and (not receipt or observed['owner'] != receipt['run']):
            raise Refused('another operator owns the on-device lab')
        device.stop_app()
        return {'outcome': 'stopped'}
    if argv[0] == 'profile-install':
        if receipt or observed['owner'] is not None:
            raise Refused('recover the current run before diagnostic provisioning')
        candidate, original, test_candidate, test_original = map(Path, argv[1:5])
        token = argv[5]
        retained = [retain_apk(device, state, item) for item in (original, candidate, test_original, test_candidate)]
        (rollback, old), (snapshot, new), (test_rollback, test_old), (test_snapshot, test_new) = retained
        if identity(old) != observed['apk'] or identity(test_old) != observed['test_apk']:
            raise Refused('both retained originals must match installed APK bytes')
        for before, after, package in ((old, new, PKG), (test_old, test_new, PKG + '.test')):
            if before['package'] != package or after['package'] != package or any(
                    before[field] != after[field] for field in ('version_code', 'signer', 'diagnostic_manifest')):
                raise Refused('diagnostic APKs require the same package/version/signer and constrained manifest')
        if test_old.get('target') != PKG or test_new.get('target') != PKG:
            raise Refused('diagnostic instrumentation must target only the dictation app')
        if old.get('debuggable') is not True:
            raise Refused('diagnostic baseline must be a verified debuggable original APK')
        device.idle()
        fresh = device.inspect()
        if (fresh['apk'] != identity(old) or device.app_identity(PKG + '.test') != identity(test_old)
                or fresh['lab'] != observed['lab'] or not fresh['lab']['accessible']
                or fresh.get('owner') is not None
                or any(fresh[name]['exists'] for name in ('original', 'stage', 'discard', 'disposal'))):
            raise Refused('need a fresh accessible exact original APK pair and unowned lab baseline')
        receipt = dict(run=token, kind='lab', diagnostic=True, device=observed['device'],
                       apk=observed['apk'], test_apk=observed['test_apk'], original=fresh['lab'],
                       before=fresh['lab'], candidate=fresh['lab'],
                       original_apk=observed['apk'], original_test_apk=observed['test_apk'],
                       rollback=str(rollback), test_rollback=str(test_rollback),
                       phase='snapshotting', pending_apk=identity(new), pending_test_apk=identity(test_new))
        save(journal, receipt)
        device.claim(token)
        if fresh['lab']['exists']:
            device.move('lab', 'original')
        if device.inventory('original') != fresh['lab']:
            raise Refused('pre-install lab snapshot did not verify; recover this run')
        receipt['phase'] = 'installing'
        save(journal, receipt)
        device.install(snapshot)
        if device.app_identity() != receipt['pending_apk']:
            raise Refused('diagnostic app installation did not verify; recover this run')
        device.install_test(test_snapshot)
        actual = device.inspect()
        actual['test_apk'] = device.app_identity(PKG + '.test')
        if actual['apk'] != receipt['pending_apk'] or actual['test_apk'] != receipt['pending_test_apk']:
            raise Refused('diagnostic APK pair did not verify; recover this run')
        receipt.update(apk=actual['apk'], test_apk=actual['test_apk'], before=actual['lab'],
                       candidate=actual['lab'], phase='installed')
        save(journal, receipt)
        return dict(run=token, outcome='installed-diagnostic-pair')
    if argv[0] == 'install':
        if receipt and receipt.get('diagnostic'):
            raise Refused('recover diagnostic provisioning before ordinary install')
        candidate, original, token = Path(argv[1]), Path(argv[2]), argv[3]
        if receipt and (receipt['run'] != token or receipt['kind'] != 'install' or receipt['phase'] != 'installed'):
            raise Refused('recover the current run before installing another build')
        if observed['owner'] is not None:
            raise Refused('an on-device lab run owns the application')
        rollback, old_info = retain_apk(device, state, original)
        candidate_snapshot, new_info = retain_apk(device, state, candidate)
        baseline = receipt['original_apk'] if receipt else observed['apk']
        if old_info['sha256'] != baseline['sha256']:
            raise Refused('original APK does not match installed baseline')
        for field in ('package', 'version_code', 'payload', 'signer', 'manifest'):
            if old_info[field] != new_info[field]:
                raise Refused('only same-version, same-signer, unchanged-payload builds are recoverable')
        if old_info['package'] != PKG or old_info['version_code'] != observed['apk']['version_code']:
            raise Refused('wrong package or unsupported version rollback')
        device.idle()
        if receipt is None:
            receipt = dict(run=token, kind='install', device=observed['device'], apk=observed['apk'],
                           original=None, original_apk=baseline, rollback=str(rollback))
        receipt.update(phase='installing', pending_apk={
            'sha256': new_info['sha256'], 'version_code': new_info['version_code']})
        save(journal, receipt)
        device.install(candidate_snapshot)
        actual = device.inspect()['apk']
        if actual != receipt['pending_apk']:
            raise Refused('installed APK identity did not verify; recover this run')
        receipt.update(apk=actual, phase='installed')
        save(journal, receipt)
        return dict(run=token, outcome='installed')
    if argv[0] in ('lab', 'test', 'pull-results', 'pull-profile'):
        if not receipt or receipt['run'] != argv[1] or observed['owner'] != receipt['run']:
            raise Refused('no matching active run')
        if not (short_inventory(receipt,observed['lab']) if receipt.get('short_encoder')
                else same_lab(observed['lab'], receipt['candidate'])):
            raise Refused('lab changed outside the active run')
        if receipt.get('short_encoder'):
            if argv[0] == 'lab':
                return short_lab(device,state,receipt,Path(argv[2]))
            if argv[0] == 'pull-results' and receipt['short_encoder'].get('attempt'):
                return short_capture(device,state,receipt)
            raise Refused('short encoder probes support only one lab attempt and bounded results, no role profile')
        if argv[0] == 'pull-profile':
            if not receipt.get('diagnostic') or (receipt['phase'] != 'active' or not receipt.get('profile_complete')):
                raise Refused('profile retrieval requires a completed diagnostic measurement')
            payload = device.profile_result()
            binding = profile_binding(json.loads(device.result()), payload)
            if binding != receipt.get('profile_binding'):
                raise Refused('profile differs from the successful measurement receipt')
            target = state / 'results' / (receipt['run'] + '-profile-' + uuid.uuid4().hex + '.json')
            target.parent.mkdir(exist_ok=True)
            ordinary_path(target)
            with target.open('xb') as output:
                output.write(payload)
            return dict(run=receipt['run'], path=str(target), files=1, bytes=len(payload))
        if argv[0] in ('lab', 'test'):
            if receipt['phase'] != 'active':
                raise Refused('interrupted operation requires recovery')
            device.idle()
            receipt['phase'] = 'running'
            receipt['profile_complete'] = False
            receipt.pop('profile_binding', None)
            save(journal, receipt)
            device.clear_result()
            if receipt.get('diagnostic'):
                output = device.instrument(Path(argv[2]), full=argv[0] == 'test', native_profile=True)
            else:
                output = device.instrument(Path(argv[2]), full=argv[0] == 'test')
            (state / (receipt['run'] + '-instrumentation.txt')).write_text(output)
            if not re.search(r'OK \([1-9][0-9]* tests?\)', output) or any(
                    marker in output for marker in ('FAILURES!!!', 'INSTRUMENTATION_FAILED', 'Process crashed')) or (
                    re.search(r'^INSTRUMENTATION_STATUS_CODE:\s*-\d+\s*$', output, re.M)) or not (
                    re.search(r'^INSTRUMENTATION_CODE:\s*-1\s*$', output, re.M)):
                raise Refused('instrumentation did not report a successful nonempty test run')
            measured = json.loads(device.result())
            if not isinstance(measured, dict) or not (measured.get('takes') or measured.get('outcome') == 'complete'):
                raise Refused('instrumentation produced no complete fresh lab result')
            if receipt.get('diagnostic'):
                receipt['profile_binding'] = profile_binding(measured, device.profile_result())
            receipt['phase'] = 'active'
            receipt['profile_complete'] = bool(receipt.get('diagnostic'))
            save(journal, receipt)
            return dict(run=receipt['run'], outcome='complete')
        payload = device.result()
        json.loads(payload)
        target = state / 'results' / (receipt['run'] + '-' + uuid.uuid4().hex + '.json')
        target.parent.mkdir(exist_ok=True)
        ordinary_path(target)
        with target.open('xb') as output:
            output.write(payload)
        return dict(run=receipt['run'], path=str(target))
    if len(argv) == 3 and argv[0] == 'push-variant':
        source, token = Path(argv[1]), argv[2]
        if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,63}', token):
            raise Refused('invalid run identity')
        staged = inventory(source)
        if receipt and receipt.get('short_encoder',{}).get('attempt') is not None:
            raise Refused('short encoder attempt already reserved; restaging cannot authorize a retry')
        short = short_declaration(source,staged,receipt,observed)
        if not (source / 'variant.json').is_file() or not (source / 'model').is_dir():
            raise Refused('need variant.json and model directory')
        if not accessible:
            raise Refused('app-private lab state inaccessible; no baseline can be preserved')
        if (receipt is None or receipt['kind'] == 'install') and observed['owner'] is not None:
            raise Refused('on-device owner has no matching local lab receipt')
        if receipt and (receipt['run'] != token or (receipt['kind'] == 'lab' and observed['owner'] != token)):
            raise Refused('another run owns the recovery baseline')
        if receipt and receipt['phase'] not in ('active', 'installed'):
            raise Refused('interrupted operation requires recovery')
        if receipt and receipt['kind'] == 'lab' and not same_lab(observed['lab'], receipt['candidate']):
            raise Refused('lab changed outside the active run')
        if receipt and receipt['kind'] == 'lab' and observed['original'] != receipt['original']:
            raise Refused('preserved original changed or disappeared')
        if any(observed[name]['exists'] for name in ('stage', 'discard', 'disposal')):
            raise Refused('unexpected staging state; recover first')
        device.idle()
        if receipt is None:
            receipt = dict(run=token, kind='lab', device=observed['device'], apk=observed['apk'],
                           original=observed['lab'], original_apk=None)
        if receipt['kind'] == 'install':
            receipt.update(kind='lab', original=observed['lab'])
        first = observed['owner'] is None
        if first and observed['original']['exists']:
            raise Refused('unowned preserved lab exists')
        receipt.update(before=observed['lab'], candidate=staged, phase='staging', deleting=[], profile_complete=False)
        if short is not None:
            receipt['short_encoder'] = short
        else:
            receipt.pop('short_encoder',None)
        save(journal, receipt)
        if first:
            device.claim(token)
        receipt['copy_id'] = uuid.uuid4().hex
        receipt['copy_pending'] = True
        save(journal, receipt)
        device.stage(source, receipt['copy_id'])
        receipt['copy_pending'] = False
        save(journal, receipt)
        if device.inventory('stage') != staged:
            raise Refused('staged content does not match')
        if device.inventory('lab') != observed['lab']:
            raise Refused('lab changed while staging')
        receipt['phase'] = 'promoting'
        save(journal, receipt)
        if receipt['original']['exists'] and not device.inventory('original')['exists']:
            device.move('lab', 'original')
        elif device.inventory('lab')['exists']:
            device.move('lab', 'discard')
        device.move('stage', 'lab')
        remove_owned(device, state, receipt, 'discard')
        receipt['phase'] = 'active'
        save(journal, receipt)
        device.cleanup_copy(receipt['copy_id'])
        return dict(run=token, outcome='staged')
    if len(argv) == 2 and argv[0] == 'recover':
        if receipt is None:
            previous = state / (argv[1] + '.json')
            if previous.exists():
                receipt = json.loads(previous.read_text())
                if receipt.get('diagnostic') and hasattr(device, 'app_identity'):
                    observed['test_apk'] = device.app_identity(PKG + '.test')
                if observed['device'] != receipt['device'] or observed['apk'] != receipt['apk'] or not test_matches(receipt, observed):
                    raise Refused('device or installed app changed')
                if receipt['original'] is None or observed['lab'] == receipt['original'] or recorded_lab_restoration(receipt, observed):
                    return recovery_result(receipt, observed)
                raise Refused('recovered state has since changed')
        if receipt is None or receipt['run'] != argv[1]:
            raise Refused('no matching recovery receipt')
        # Never mutate private paths or restore APKs while a detached copy may run.
        if receipt.get('copy_pending'):
            device.stage_quiescent(receipt['copy_id'])
            receipt['copy_pending'] = False
            save(journal, receipt)
        device.idle()
        if (receipt.get('short_encoder',{}).get('attempt') is not None
                and receipt['phase'] in ('running','active')):
            short_capture(device,state,receipt)
            observed = device.inspect()
            observed['test_apk'] = device.app_identity(PKG + '.test')
            accessible = observed['lab']['accessible']
        if (receipt.get('run') == ENCODER_PROBE_RECOVERY['run'] and receipt.get('phase') == 'running'
                and not same_lab(observed['lab'], receipt['candidate'])):
            observed = reconcile_encoder_probe_recovery(device, state, receipt, observed)
            accessible = observed['lab']['accessible']
        if (receipt.get('diagnostic') and receipt['original'] is not None and accessible
                and observed.get('owner') is None and not receipt.get('ownership_handoff')
                and receipt['phase'] not in ('staging', 'snapshotting')):
            raise Refused('diagnostic owner disappeared before journaled restoration handoff')
        if receipt['original'] is not None and not recorded_lab_restoration(receipt, observed):
            if not accessible:
                raise Refused('private lab state is inaccessible without a verified restoration milestone')
            if observed['owner'] not in (receipt['run'], None):
                raise Refused('different operation owns this lab')
            if observed['lab']['exists'] and not (
                    observed['lab'] in (receipt['original'],receipt['before']) or short_inventory(receipt,observed['lab'])
                    if receipt.get('short_encoder') else any(same_lab(observed['lab'], allowed)
                    for allowed in (receipt['original'], receipt['before'], receipt['candidate']))):
                raise Refused('lab changed outside the active run')
            if observed['original']['exists'] and observed['original'] != receipt['original']:
                raise Refused('preserved original changed')
            if not cleanup_matches(observed['discard'], receipt['before'], 'discard' in receipt.get('deleting', [])):
                raise Refused('displaced lab changed')
            if observed['disposal']['exists'] and ('disposal_expected' not in receipt or not cleanup_matches(
                    observed['disposal'], receipt['disposal_expected'], 'disposal' in receipt.get('deleting', []))):
                raise Refused('recovery disposal changed')
            receipt['phase'] = 'recovering'
            save(journal, receipt)
            must_displace = observed['lab']['exists'] and (observed['original']['exists'] or not receipt['original']['exists'])
            if must_displace:
                if observed['disposal']['exists']:
                    raise Refused('both active candidate and recovery disposal exist')
                receipt['disposal_expected'] = observed['lab']
                save(journal, receipt)
                device.move('lab', 'disposal')
            if observed['original']['exists']:
                device.move('original', 'lab')
            elif observed['lab'] != receipt['original']:
                if receipt['original']['exists']:
                    raise Refused('preserved original missing')
            remove_owned(device, state, receipt, 'stage')
            remove_owned(device, state, receipt, 'discard')
            remove_owned(device, state, receipt, 'disposal')
            device.cleanup_transfer(receipt['run'])
            if receipt.get('copy_id'):
                device.cleanup_copy(receipt['copy_id'])
            if device.inventory('lab') != receipt['original']:
                raise Refused('restored lab does not match original')
            receipt['lab_restored'] = True
            save(journal, receipt)
        if receipt.get('diagnostic'):
            receipt['phase'] = 'restoring-apk'
            save(journal, receipt)
            test_rollback = Path(receipt['test_rollback'])
            if digest(test_rollback) != receipt['original_test_apk']['sha256']:
                raise Refused('retained original test APK changed')
            if device.app_identity(PKG + '.test') != receipt['original_test_apk']:
                device.install_test(test_rollback)
            if device.app_identity(PKG + '.test') != receipt['original_test_apk']:
                raise Refused('test APK recovery did not verify')
            receipt['test_apk'] = receipt['original_test_apk']
            save(journal, receipt)
        if receipt.get('original_apk'):
            receipt['phase'] = 'restoring-apk'
            save(journal, receipt)
            # Final APP may disable run-as. Transfer ownership to the durable
            # local receipt only after lab and TEST restoration are verified.
            if receipt['original'] is not None and not receipt.get('remote_released'):
                handoff = device.inspect()
                if handoff['lab'] != receipt['original'] or handoff.get('owner') not in (receipt['run'], None):
                    raise Refused('restoration ownership handoff did not verify')
                receipt['ownership_handoff'] = True
                save(journal, receipt)
                if handoff.get('owner') == receipt['run']:
                    device.release(receipt['run'])
                if device.inspect().get('owner') is not None:
                    raise Refused('restoration ownership release did not verify')
                receipt['remote_released'] = True
                save(journal, receipt)
            rollback = Path(receipt['rollback'])
            if digest(rollback) != receipt['original_apk']['sha256']:
                raise Refused('retained original APK changed')
            if device.inspect()['apk'] != receipt['original_apk']:
                device.install(rollback)
            if device.inspect()['apk'] != receipt['original_apk']:
                raise Refused('APK recovery did not verify')
            receipt['apk'] = receipt['original_apk']
        if not receipt.get('original_apk') and receipt['original'] is not None:
            release_state = device.inspect()
            if release_state.get('owner') == receipt['run']:
                device.release(receipt['run'])
            if device.inspect().get('owner') is not None:
                raise Refused('recovery ownership release did not verify')
        final = device.inspect()
        if receipt.get('diagnostic'):
            final['test_apk'] = device.app_identity(PKG + '.test')
        if receipt.get('diagnostic') and (final['apk'] != receipt['original_apk'] or final['test_apk'] != receipt['original_test_apk']):
            raise Refused('restored APK pair did not verify')
        receipt['phase'] = 'recovered'
        save(state / (receipt['run'] + '.json'), receipt)
        journal.unlink()
        return recovery_result(receipt, final)
    raise Refused('unknown command or arguments')


class AndroidPhone:
    """The external device adapter. Every remote path and package is fixed here."""
    paths = {'lab': 'files/lab', 'original': 'files/.phone-lab-original',
             'stage': 'files/.phone-lab-stage', 'discard': 'files/.phone-lab-discard',
             'disposal': 'files/.phone-lab-disposal'}
    owner_path = 'files/.phone-lab-owner'
    test_package = PKG + '.test'
    runner = test_package + '/androidx.test.runner.AndroidJUnitRunner'

    def __init__(self, corpus):
        self.corpus = corpus
        self.sdk = Path(os.environ.get('ANDROID_HOME', Path.home() / 'Android/Sdk'))
        self.bridge_path = self.sdk / 'platform-tools/adb'
        self.serial = None

    def bridge(self, *args, check=True, text=True, timeout=30):
        command = [str(self.bridge_path)]
        if self.serial:
            command += ['-s', self.serial]
        result = child(command + list(args), capture_output=True, text=text, timeout=timeout)
        if check and result.returncode:
            raise Refused('device command failed; inspect/recover before retrying')
        return result

    def connect(self):
        if self.serial:
            return
        output = self.bridge('devices').stdout
        devices = [line.split() for line in output.splitlines()[1:] if line.strip()]
        if len(devices) != 1 or devices[0][1:] != ['device']:
            raise Refused('need exactly one authorized phone connection')
        self.serial = devices[0][0]
        model = self.bridge('shell', 'getprop ro.product.model').stdout.strip()
        if model != 'Pixel 10 Pro XL':
            raise Refused('connected device is not the permitted Pixel 10 Pro XL')

    def shell(self, code, **kwargs):
        self.connect()
        return self.bridge('shell', code, **kwargs)

    def private(self, code, **kwargs):
        return self.shell('run-as ' + PKG + ' sh -c ' + shlex.quote(code), **kwargs)

    def app_identity(self, package=PKG):
        if package not in (PKG, self.test_package):
            raise Refused('unsupported package')
        output = self.shell('pm path ' + package).stdout
        paths = [line.removeprefix('package:') for line in output.splitlines() if line.startswith('package:')]
        if len(paths) != 1:
            raise Refused('need one installed base APK; split/missing packages are unsupported')
        hashed = self.shell('sha256sum -- ' + shlex.quote(paths[0])).stdout.split()[0]
        if not re.fullmatch('[0-9a-f]{64}', hashed):
            raise Refused('installed APK hash unavailable')
        details = self.shell('dumpsys package ' + package).stdout
        version = re.search(r'\bversionCode=(\d+)', details)
        if not version:
            raise Refused('installed version code unavailable')
        return {'sha256': hashed, 'version_code': int(version.group(1))}

    def inventory(self, name):
        scope = self.paths[name]
        code = f'''if [ -L {scope} ]; then exit 73; fi
if [ -d {scope} ]; then
  [ -z "$(find {scope} -type l -print -quit)" ] || exit 73
  printf 'present\\n'
  cd {scope} && find . -type f -exec sha256sum {{}} +
elif [ -e {scope} ]; then exit 73
else printf 'absent\\n'; fi'''
        output = self.private(code).stdout.splitlines()
        if output == ['absent']:
            return {'accessible': True, 'exists': False, 'files': {}}
        if not output or output[0] != 'present':
            raise Refused('private file inventory unavailable')
        files = {}
        for line in output[1:]:
            matched = re.fullmatch(r'([0-9a-f]{64})  \./([A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*)', line)
            if not matched or '..' in Path(matched.group(2)).parts:
                raise Refused('unsafe or unsupported private filename')
            files[matched.group(2)] = matched.group(1)
        return {'accessible': True, 'exists': True, 'files': files}

    def inspect(self):
        self.connect()
        observed = dict(device=self.serial, apk=self.app_identity(), owner=None)
        if self.private('pwd', check=False).returncode:
            observed.update({name: {'accessible': False} for name in self.paths})
            return observed
        observed.update({name: self.inventory(name) for name in self.paths})
        owner = self.private(f'if [ -L {self.owner_path} ]; then readlink {self.owner_path}; '
                             f'elif [ -e {self.owner_path} ]; then printf invalid; fi').stdout.strip()
        observed['owner'] = owner or None
        return observed

    def idle(self):
        probe = self.shell('pidof ' + PKG, check=False)
        if probe.returncode == 0 and re.fullmatch(r'\d+(?:\s+\d+)*', probe.stdout.strip()) and not probe.stderr.strip():
            raise Refused('dictation process exists; explicit stop-app approval is required, not just closing the UI')
        if probe.returncode != 1 or probe.stdout.strip() or probe.stderr.strip():
            raise Refused('cannot establish that the dictation process has stopped')

    def stop_app(self):
        # Explicit operation only. Never use it as an implicit idle() fallback.
        if self.shell('am get-current-user').stdout.strip() != '0':
            raise Refused('stop-app supports only the active primary user 0')
        before = self.ime_state()
        processes = self.shell('dumpsys activity processes').stdout
        headers = list(re.finditer(r'^ACTIVITY MANAGER RUNNING PROCESSES \(dumpsys activity processes\)$', processes, re.M))
        completed = list(re.finditer(r'^  mDeviceIdleTempAllowlist=\[[^\n]*\]\s*$', processes, re.M))
        if len(headers) != 1 or len(completed) != 1 or completed[0].start() <= headers[0].end() or re.search(
                r'Active instrumentation:|Instrumentation #|ActiveInstrumentation\{|mActiveInstrumentation=|'
                r'Permission Denial|Permission denied|DUMP TIMEOUT|Exception|Error dumping|Can.t find service', processes):
            raise Refused('instrumentation is active or process state is unrecognized')
        if self.shell('am get-current-user').stdout.strip() != '0' or self.ime_state() != before:
            raise Refused('user or IME state changed before stop')
        self.shell('am force-stop --user 0 ' + PKG)
        self.idle()
        if self.ime_state() != before:
            raise Refused('IME selection changed during stop; no settings restoration attempted')

    def ime_state(self):
        output = self.shell('dumpsys input_method').stdout
        users = re.findall(r'^\s*(?:mCurrentUserId|UserId)=(\d+)\s*$', output, re.M)
        selected = re.findall(r'^\s*(?:mCurMethodId|mSelectedMethodId|mSelectedImeId)=([^\s]+)', output, re.M)
        bound = re.findall(r'^\s*(?:mCurId|mCurImeId)=([^\s]+)', output, re.M)
        values = selected + bound
        invalid = sum(value != 'null' and not re.fullmatch(r'[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+', value) for value in values)
        if not users or set(users) != {'0'} or not selected or not bound or invalid:
            markers = {name: len(re.findall(r'^\s*' + name + r'=', output, re.M))
                       for name in ('mCurrentUserId', 'mCurrentImeUserId', 'UserId')}
            raise Refused('cannot establish IME state: ' + json.dumps(dict(
                user_markers=markers, parsed_user_fields=len(users), primary_only=bool(users) and set(users) == {'0'},
                selected_fields=len(selected), binding_fields=len(bound), invalid_components=invalid), sort_keys=True))
        if any(value.startswith(PKG + '/') for value in values):
            raise Refused('dictation is selected or bound; stopping it could change keyboard settings')
        keys = ('default_input_method', 'enabled_input_methods', 'selected_input_method_subtype',
                'input_methods_subtype_history')
        settings = tuple(self.shell('settings --user 0 get secure ' + key).stdout.strip() for key in keys)
        if not settings[0] or settings[0] == 'null' or settings[0] not in selected or settings[0].startswith(PKG + '/'):
            raise Refused('IME selection is missing or changing')
        return settings

    def claim(self, token):
        quoted = shlex.quote(token)
        self.private(f'ln -s {quoted} {self.owner_path} || '
                     f'[ "$(readlink {self.owner_path})" = {quoted} ]')

    def release(self, token):
        self.private(f'[ "$(readlink {self.owner_path})" = {shlex.quote(token)} ] && rm {self.owner_path}')

    def stage(self, source, copy_id):
        token = self.private('readlink ' + self.owner_path).stdout.strip()
        if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,63}', token):
            raise Refused('missing operation owner')
        temporary = '/data/local/tmp/dictation-lab-' + token
        self.shell('mkdir ' + temporary)
        self.bridge('push', str(source.resolve()) + '/.', temporary, timeout=300)
        # Unique per-attempt marker, bound to the durable local journal.
        # Toybox timeout waits for its direct child (cp), including SIGKILL.
        # An absent marker is unknown remote state, even after host transport death.
        if not re.fullmatch('[0-9a-f]{32}', copy_id):
            raise Refused('invalid copy attempt')
        marker = 'files/.phone-copy-' + copy_id
        self.private('[ ! -e ' + marker + ' ] && [ ! -L ' + marker + ' ] && mkdir ' + self.paths['stage'] +
                     ' && { toybox timeout -s KILL 240 cp -r ' + temporary + '/. ' +
                     self.paths['stage'] + '/; result=$?; printf \'%s\\n\' "$result" > ' +
                     marker + '; exit "$result"; }', timeout=300)
        self.stage_quiescent(copy_id, success=True)
        self.cleanup_transfer(token)

    def stage_quiescent(self, token, success=False):
        if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,63}', token):
            raise Refused('invalid copy owner')
        marker = 'files/.phone-copy-' + token
        result = self.private('[ ! -L ' + marker + ' ] && [ -f ' + marker +
                              ' ] && cat ' + marker, check=False)
        if (result.returncode or not re.fullmatch(r'(?:0|[1-9][0-9]{0,2})\n?', result.stdout)
                or int(result.stdout.strip()) > 255):
            raise Refused('remote copy completion is unproven; journal and all paths retained')
        if success and result.stdout.strip() != '0':
            raise Refused('remote staging copy failed; recover this run')

    def move(self, source, target):
        self.private('mv ' + self.paths[source] + ' ' + self.paths[target])

    def remove(self, name):
        self.private('rm -rf -- ' + self.paths[name])

    def cleanup_transfer(self, token):
        if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,63}', token):
            raise Refused('invalid transfer owner')
        self.shell('rm -rf -- /data/local/tmp/dictation-lab-' + token)

    def cleanup_copy(self, token):
        if not re.fullmatch('[0-9a-f]{32}', token):
            raise Refused('invalid copy attempt')
        self.private('rm -f -- files/.phone-copy-' + token)

    def sdk_tool(self, name):
        candidates = list((self.sdk / 'build-tools').glob('*/' + name))
        if not candidates:
            raise Refused('Android SDK build tool missing: ' + name)
        return max(candidates, key=lambda p: tuple(int(n) for n in re.findall(r'\d+', p.parent.name)))

    def apk_info(self, path):
        if path.is_symlink() or path.suffix != '.apk' or not path.is_file():
            raise Refused('need an ordinary local APK file')
        badging = child([str(self.sdk_tool('aapt2')), 'dump', 'badging', str(path)],
                                 check=True, capture_output=True, text=True).stdout
        package = re.search(r"^package: name='([^']+)' versionCode='(\d*)'", badging, re.M)
        if not package or package.group(1) not in (PKG, self.test_package) or (package.group(1) == PKG and not package.group(2)):
            raise Refused('APK is outside the permitted dictation package family')
        permissions = set(re.findall(r"uses-permission(?:-sdk-23)?: name='([^']+)'", badging))
        if package.group(1) == PKG and permissions - {'android.permission.RECORD_AUDIO', 'android.permission.WAKE_LOCK'}:
            raise Refused('unsupported application permissions')
        tree = child([str(self.sdk_tool('aapt2')), 'dump', 'xmltree', str(path),
                               '--file', 'AndroidManifest.xml'], check=True, capture_output=True, text=True).stdout
        lines, kept, index = tree.splitlines(), [], 0
        while index < len(lines):
            line = lines[index]
            if 'E: uses-permission ' in line or line.strip() == 'E: uses-permission':
                indent, end = len(line) - len(line.lstrip()), index + 1
                while end < len(lines) and len(lines[end]) - len(lines[end].lstrip()) > indent:
                    end += 1
                if any('"android.permission.WAKE_LOCK"' in item for item in lines[index:end]):
                    index = end
                    continue
            if not re.search(r'A: android:debuggable\(', line):
                kept.append(re.sub(r'\s*\(line=\d+\)', '', line))
            index += 1
        manifest = '\n'.join(kept)
        targets = re.findall(r'A: (?:android|http://schemas.android.com/apk/res/android):targetPackage[^=]*="([^"]+)"', tree)
        signing = child([str(self.sdk_tool('apksigner')), 'verify', '--print-certs', str(path)],
                                 check=True, capture_output=True, text=True).stdout
        signers = set(re.findall(r'^(?:Signer #\d+|V\d+ Signer): certificate SHA-256 digest: ([0-9a-f]{64})$', signing, re.M))
        if len(signers) != 1:
            raise Refused('need one verified APK signer')
        payload = hashlib.sha256()
        with zipfile.ZipFile(path) as archive:
            names = archive.namelist()
            if len(names) != len(set(names)):
                raise Refused('duplicate APK archive entries')
            for name in sorted(names):
                if name == 'AndroidManifest.xml' or name.startswith('META-INF/'):
                    continue
                payload.update(name.encode() + b'\0')
                with archive.open(name) as stream:
                    for block in iter(lambda: stream.read(1024 * 1024), b''):
                        payload.update(block)
        return dict(package=package.group(1), version_code=int(package.group(2) or '0'),
                    sha256=digest(path), signer=next(iter(signers)), payload=payload.hexdigest(),
                    manifest=hashlib.sha256(manifest.encode()).hexdigest(),
                    diagnostic_manifest=diagnostic_manifest(manifest, package.group(1)),
                    target=targets[0] if len(targets) == 1 else None,
                    debuggable=bool(re.search(r'^application-debuggable\s*$', badging, re.M)))

    def install(self, path):
        if self.apk_info(path)['package'] != PKG:
            raise Refused('only the dictation app may be installed')
        self.bridge('install', '-r', str(path), timeout=600)

    def install_test(self, path):
        info = self.apk_info(path)
        if info['package'] != self.test_package or info['target'] != PKG:
            raise Refused('instrumentation may target only the dictation app')
        self.bridge('install', '-r', str(path), timeout=600)

    def uninstall(self):
        self.bridge('uninstall', PKG)

    def instrument(self, test_apk, full=False, native_profile=False, short_encoder_attempt=None):
        metadata = self.apk_info(test_apk)
        installed = self.app_identity(self.test_package)
        if metadata['package'] != self.test_package or metadata['sha256'] != installed['sha256']:
            raise Refused('prepared test APK does not match installed instrumentation; no automatic installation')
        instrumentation = self.shell('pm list instrumentation').stdout
        if f'instrumentation:{self.runner} (target={PKG})' not in instrumentation.splitlines():
            raise Refused('installed instrumentation does not target the permitted app')
        classes = PKG + '.LabTest'
        if full:
            classes += ',' + PKG + '.PipelineTest,' + PKG + '.ReplayTest'
        remaining = 570 if DEADLINE is None else min(570, DEADLINE - time.monotonic() - 30)
        if remaining <= 0:
            raise Refused('insufficient measurement budget; recover the journaled run')
        options = ' -e nativeRoleProfiler true' if native_profile else ''
        if short_encoder_attempt is not None:
            if native_profile or full or not re.fullmatch('[0-9a-f]{32}',short_encoder_attempt):
                raise Refused('invalid short encoder attempt binding')
            options += ' -e shortEncoderAttempt ' + short_encoder_attempt
        try:
            return self.shell('am instrument -w -r -e class ' + classes + options + ' ' + self.runner, timeout=remaining).stdout
        except subprocess.TimeoutExpired:
            # Diagnostic profiling explicitly includes this app-scoped timeout abort.
            if not native_profile:
                raise Refused('measurement timed out; remote state unknown, inspect/recover required') from None
            self.abort_measurement()
            raise Refused('measurement timed out; app quiescence verified, run remains incomplete') from None

    def abort_measurement(self):
        if self.shell('am get-current-user').stdout.strip() != '0':
            raise Refused('cannot abort measurement outside primary user 0')
        before = self.ime_state()
        self.shell('am force-stop --user 0 ' + PKG)
        self.idle()
        if self.ime_state() != before or self.shell('am get-current-user').stdout.strip() != '0':
            raise Refused('abort verification failed; journal remains incomplete')
        processes = self.shell('dumpsys activity processes').stdout
        if 'ACTIVITY MANAGER RUNNING PROCESSES' not in processes or 'mDeviceIdleTempAllowlist=' not in processes or re.search(
                r'Active instrumentation:|Instrumentation #|ActiveInstrumentation\{|mActiveInstrumentation=|Permission Denial|DUMP TIMEOUT|Exception|Error dumping', processes):
            raise Refused('cannot verify instrumentation quiescence; journal remains incomplete')

    def result(self):
        return self.bridge('exec-out', 'run-as ' + PKG + ' cat files/lab/result.json', text=False).stdout

    def encoder_probe_recovery_result(self):
        # One fixed ordinary file, bounded output. No caller-supplied path.
        code = 'test -f files/lab/result.json && test ! -L files/lab/result.json && head -c 1048577 files/lab/result.json'
        return self.bridge('exec-out', 'run-as ' + PKG + ' sh -c ' + shlex.quote(code), text=False).stdout

    def encoder_probe_recovery_profile(self):
        # One exact known ORT trace, never a pattern or directory export.
        path = 'files/lab/encoder-probe-profile_2026-10-20_20-19-16_968.json'
        code = 'test -f ' + path + ' && test ! -L ' + path + ' && head -c 67108865 ' + path
        return self.bridge('exec-out', 'run-as ' + PKG + ' sh -c ' + shlex.quote(code), text=False).stdout

    def short_encoder_result(self):
        code = 'test -f files/lab/result.json && test ! -L files/lab/result.json && head -c 1048577 files/lab/result.json'
        return self.bridge('exec-out', 'run-as ' + PKG + ' sh -c ' + shlex.quote(code), text=False).stdout

    def profile_result(self):
        limit = 64 * 1024 * 1024
        # Fixed path, ordinary file, bounded device output; never tar a directory.
        code = 'test -f files/lab/profile.json && test ! -L files/lab/profile.json && head -c ' + str(limit + 1) + ' files/lab/profile.json'
        payload = self.bridge('exec-out', 'run-as ' + PKG + ' sh -c ' + shlex.quote(code), text=False).stdout
        if len(payload) > limit:
            raise Refused('profile exceeds the fixed 64MiB export bound')
        return payload

    def clear_result(self):
        self.private('rm -f -- files/lab/result.json files/lab/profile.json')

    def readonly(self, command):
        if command == 'status':
            return {'status': self.bridge('devices', '-l').stdout}
        self.connect()
        if command == 'versions':
            properties = ('ro.product.model', 'ro.build.version.release', 'ro.build.version.sdk',
                          'ro.build.id', 'ro.build.display.id', 'ro.soc.model')
            versions = {prop: self.shell('getprop ' + prop).stdout.strip() for prop in properties}
            packages = (PKG, 'org.futo.inputmethod.latin', 'org.futo.inputmethod.latin.playstore',
                        'org.futo.voiceinput', 'org.futo.voiceinput.playstore')
            for package in packages:
                details = self.shell('dumpsys package ' + package, check=False).stdout
                version = re.search(r'\bversionName=([^\s]+)', details)
                versions[package] = version.group(1) if version else 'not installed'
            versions['input_methods'] = self.shell('ime list -s').stdout.splitlines()
            return versions
        if command == 'thermal':
            dump = self.shell('dumpsys thermalservice').stdout
            fields = {}
            for name in ('IsStatusOverride', 'Thermal Status', 'HAL Ready'):
                values = re.findall(r'^' + re.escape(name) + r':([^\r\n]*)\r?$', dump, re.M)
                if len(values) != 1:
                    raise Refused('missing or duplicate thermal field')
                fields[name] = values[0].removeprefix(' ')
            if fields['IsStatusOverride'] != 'false' or fields['HAL Ready'] != 'true' or not re.fullmatch('[0-6]', fields['Thermal Status']):
                raise Refused('thermal service unavailable, overridden or invalid')
            return dict(schema='idle-thermal-status-v1', source='android-thermalservice',
                        thermalStatus=int(fields['Thermal Status']))
        if command == 'logs':
            child([str(self.bridge_path), '-s', self.serial, 'logcat', '-v', 'time', '-T', '1',
                            'DictationIme:V', 'Recorder:V', 'Recognizer:V', 'ReplayTest:V',
                            'LabTest:V', 'AndroidRuntime:E', '*:S'], check=True, capture_output=False)
            return {'outcome': 'logs ended'}
        if command == 'crash':
            lines = self.bridge('logcat', '-d', '-b', 'crash').stdout.splitlines()
            selected = set()
            for index, line in enumerate(lines):
                if PKG in line:
                    selected.update(range(max(0, index - 2), min(len(lines), index + 41)))
            return {'crash': '\n'.join(lines[i] for i in sorted(selected)[-200:])}
        if command == 'subtypes':
            return {'subtypes': '\n'.join(line for line in self.shell('ime list -a').stdout.splitlines()
                                        if re.search(r'^[^ ]|mId=|mMode=|mLocale=|mIsAuxiliary=|mSubtypeId=|Enabled', line))}
        if command == 'pull-report':
            return self.pull_report()
        raise Refused('unknown read-only operation')

    def pull_report(self):
        # Explicit user authorization is still required for every recording export.
        destination = self.corpus / 'files' / 'benchmark'
        ordinary_path(destination)
        destination.mkdir(parents=True, exist_ok=True)
        import io
        result = self.bridge('exec-out', 'run-as', PKG, 'tar', 'c', 'files/benchmark', text=False)
        count = 0
        with tarfile.open(fileobj=io.BytesIO(result.stdout), mode='r:') as archive:
            for member in archive:
                name = Path(member.name)
                if member.isdir() and name.parts[:2] == ('files', 'benchmark'):
                    continue
                if not member.isfile() or name.parts[:2] != ('files', 'benchmark') or len(name.parts) != 3 or '..' in name.parts:
                    raise Refused('unexpected benchmark export path')
                target = destination / name.name
                if target.exists():
                    raise Refused('refusing to overwrite an existing benchmark export')
                with archive.extractfile(member) as source, target.open('xb') as output:
                    shutil.copyfileobj(source, output)
                count += 1
        return {'files': count, 'path': str(destination)}


USAGE = '''App-scoped phone operations; invoke this executable by its exact absolute path.
  inspect                              read-only APK/lab identities and recovery status
  stop-app                             explicit approval; stop only unselected primary-user app
  push-variant DIR RUN                  preserve original lab; hash and activate candidate
  install CANDIDATE.apk ORIGINAL.apk RUN same version/signer/payload only; retain rollback
  profile-install APP.apk ORIGINAL.apk TEST.apk ORIGINAL-TEST.apk RUN
                                       explicit diagnostic pair; retain both originals
  pull-profile RUN                     one bounded counters-only profile JSON
  lab RUN INSTALLED-TEST.apk             fixed LabTest against verified installed artifacts
  test RUN INSTALLED-TEST.apk            additionally writes benchmark diagnostic reports
  pull-results RUN                      JSON only, into gitignored corpus/lab
  recover RUN                           verify and restore this run's original lab/APK
  status | versions | thermal | logs | crash | subtypes
  pull-report                           recordings export; separate explicit consent required
  uninstall                             destructive app removal; no recovery promise
Build artifacts on the host first. No command builds. Only profile-install replaces instrumentation.
RUN must be unique; use one operator and the same run identity through recovery.
No preferences, recordings, permissions, settings or app-data migrations are restored.
'''


def main():
    global DEADLINE
    operation = sys.argv[1] if len(sys.argv) > 1 else 'help'
    budget = 600 if operation in ('lab', 'test', 'install', 'profile-install', 'push-variant') else 900 if operation == 'recover' else 30
    DEADLINE = time.monotonic() + budget
    os.umask(0o077)
    if sys.argv[1:] in ([], ['help']):
        print(USAGE)
        return 0 if sys.argv[1:] else 2
    script = Path(__file__).resolve()
    if script.parent.name != 'tools' or script.name != 'phone':
        raise Refused('only the checkout tools entry point may contact the phone')
    common = child(['git', 'rev-parse', '--path-format=absolute', '--git-common-dir'],
                            cwd=script.parent, check=True, capture_output=True, text=True).stdout.strip()
    corpus = Path(common).resolve().parent / 'corpus'
    state = corpus / 'lab' / 'phone-operations'
    print(json.dumps(run(sys.argv[1:], device=AndroidPhone(corpus), state=state), sort_keys=True))
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (Refused, OSError, ValueError, KeyError, subprocess.SubprocessError, zipfile.BadZipFile, tarfile.TarError) as error:
        sys.exit('phone: ' + str(error))

