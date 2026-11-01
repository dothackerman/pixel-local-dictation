from contextlib import redirect_stderr, redirect_stdout
import io
import json
from pathlib import Path
import tempfile
import unittest

import codemap

ENGINE_KT = '''package demo.app

import java.net.URL
import demo.app.util.Helper

/* block /* nested */ fun fake() { */
class Engine(private val name: String) : Base(), AutoCloseable {
    companion object {
        fun create(): Engine = Engine("x")
    }

    fun decode(samples: List<Int>): String {
        val label = "count ${samples.size}) // not a comment"
        val raw = """raw ${helper(1)} ) { """
        samples.forEach { s ->
            run { if (s > 0 && s < 10) log(s) }
        }
        return when (samples.size) {
            0 -> "none"
            1 -> "one"
            else -> label + raw
        }
    }

    private fun log(x: Int) = println(
        x
    )

    override fun close() {
        log(decode(listOf(1)))
    }
}

fun String.shout(): String = uppercase()

fun <T> helper(n: T): T {
    val c = '{'
    val q = '\\''
    return n
}

fun log(x: Int) {}
'''

OTHER_KT = '''package demo.app

data class Base(val id: Int)
sealed class Shape
enum class Mode { A, B }
annotation class Marker
interface Port { fun open(): Int }

fun shared() = 1
fun pick() = shared() + remote() + twice() + log(2) + helper(3)
'''

UTIL_KT = '''package demo.app.util

object Helper {
    fun remote() = 2
}
fun twice() = 1
'''

DUP_KT = '''package demo.dup

fun twice() = 2
'''

RECV_KT = '''package demo.app

class Store(val path: String) {
    fun read(): Int = 1
    companion object { fun open(p: String) = Store(p) }
}

class Session {
    fun matches(token: Long?): Boolean = true
}

val globalStore = Store("g")

class User(private val store: Store) {
    private val lazyStore by lazy { Store("l") }
    fun use(hash: String, other: Store): Int {
        val local = Store("x")
        hash.matches(Regex("[a-f]+"))
        other.read(); local.read(); store.read(); lazyStore.read(); globalStore.read()
        Store.open("p")
        compute().read()
        mystery.topOnly()
        mystery.plainTop()
        return 0
    }
}

fun Any.topOnly() = 1
fun plainTop() = 2
'''

GAMMA_PY = '''import beta as b
from os import path


class Store:
    def read(self):
        return 1


class Other:
    def read(self):
        return 2


class Holder:
    def __init__(self):
        self.store = Store()

    def use(self, other: Other, data):
        local = Store()
        self.store.read()
        other.read()
        local.read()
        data.read()
        Store.read(local)
        b.go()
        path.join('a')


def sha(handle):
    return handle.read()
'''

ENGINE_TEST_KT = '''package demo.app

class EngineTest {
    fun decodes() { Engine("a").decode(listOf()) }
}
'''

ALPHA_PY = '''import json
import beta
from os import path


class A:
    def m(self):
        self.n()
        return beta.go() + json.dumps(1) + len([])

    def n(self):
        return 1


def f(x):
    if x and x > 1 or x < -1:
        return 1
    elif x:
        for i in range(3):
            pass
    try:
        g = [y for y in x if y]
    except ValueError:
        pass
    return 2 if x else unknown_call()
'''

BETA_PY = '''def go():
    return 1
'''

TEST_ALPHA_PY = '''import alpha


def test_f():
    alpha.f(1)
'''

POLICY = {
    'components': [
        {'id': 'tests', 'name': 'Tests', 'layer': 3, 'paths': ['app/src/test/*', 'tools/test_*']},
        {'id': 'app-core', 'name': 'Core', 'layer': 1, 'paths': ['app/src/main/*/demo/app/Engine.kt']},
        {'id': 'app-outer', 'name': 'Outer', 'layer': 2, 'paths': ['app/src/main/*/demo/app/*']},
        {'id': 'tools', 'name': 'Tools', 'layer': 2, 'paths': ['tools/*']},
    ],
    'rules': [
        {'id': 'no-network', 'kind': 'forbid-import', 'components': ['app-*'],
         'patterns': ['java.net.'], 'description': 'no network'},
        {'id': 'layers', 'kind': 'layer-order', 'description': 'outer depends on inner'},
    ],
}


def write_tree(root, kotlin_tests=True):
    files = {
        'app/src/main/kotlin/demo/app/Engine.kt': ENGINE_KT,
        'app/src/main/kotlin/demo/app/Other.kt': OTHER_KT,
        'app/src/main/kotlin/demo/app/util/Util.kt': UTIL_KT,
        'app/src/main/kotlin/demo/dup/Dup.kt': DUP_KT,
        'app/src/main/kotlin/demo/app/Recv.kt': RECV_KT,
        'tools/gamma.py': GAMMA_PY,
        'app/build/generated/Skip.kt': 'fun skipped() {}',
        'tools/alpha.py': ALPHA_PY,
        'tools/beta.py': BETA_PY,
        'tools/test_alpha.py': TEST_ALPHA_PY,
    }
    if kotlin_tests:
        files['app/src/test/kotlin/demo/app/EngineTest.kt'] = ENGINE_TEST_KT
    for rel, text in files.items():
        p = Path(root) / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text)


class CodemapFixtureTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name) / 'repo'
        write_tree(self.root)
        self.graph = codemap.build(self.root, POLICY)
        self.nodes = {n['id']: n for n in self.graph['nodes']}

    def fn(self, path, qual):
        hits = [n for n in self.graph['nodes'] if n['kind'] == 'function'
                and n['path'].endswith(path) and n['qualname'] == qual]
        self.assertEqual(len(hits), 1, (path, qual))
        return hits[0]

    def calls(self, node):
        return {e['to'] for e in self.graph['edges'] if e['from'] == node['id'] and e['kind'] == 'calls'}

    def test_kotlin_declarations_and_stable_ids(self):
        e = 'app/src/main/kotlin/demo/app/Engine.kt'
        decode = self.fn('Engine.kt', 'Engine.decode')
        self.assertEqual(decode['id'], f'fn:{e}#Engine.decode:12')
        self.assertEqual((decode['line'], decode['end_line']), (12, 23))
        self.assertEqual(decode['signature'], 'fun decode(samples: List<Int>): String')
        self.assertEqual(self.fn('Engine.kt', 'Engine.Companion.create')['parent'],
                         f'type:{e}#Engine.Companion')
        self.assertEqual(self.nodes[f'type:{e}#Engine.Companion']['type_kind'], 'companion object')
        engine = self.nodes[f'type:{e}#Engine']
        self.assertEqual((engine['line'], engine['end_line']), (7, 32))
        self.assertEqual(engine['supertypes'], ['Base', 'AutoCloseable'])
        log = self.fn('Engine.kt', 'Engine.log')
        self.assertEqual((log['line'], log['end_line']), (25, 27))
        shout = self.fn('Engine.kt', 'shout')
        self.assertEqual(shout['receiver'], 'String')
        self.assertEqual(shout['end_line'], 34)
        self.assertEqual(self.fn('Engine.kt', 'helper')['end_line'], 40)
        self.assertNotIn('fake', {n.get('name') for n in self.graph['nodes']})
        self.assertNotIn('skipped', {n.get('name') for n in self.graph['nodes']})
        f = self.nodes[f'file:{e}']
        self.assertEqual(f['package'], 'demo.app')
        self.assertEqual(f['imports'], ['demo.app.util.Helper', 'java.net.URL'])
        kinds = {n['name']: n['type_kind'] for n in self.graph['nodes'] if n['kind'] == 'type'}
        self.assertEqual([kinds[k] for k in ('Base', 'Shape', 'Mode', 'Marker', 'Port', 'Helper')],
                         ['data class', 'sealed class', 'enum', 'annotation class', 'interface', 'object'])

    def test_kotlin_call_resolution_precedence(self):
        decode = self.fn('Engine.kt', 'Engine.decode')
        engine_log = self.fn('Engine.kt', 'Engine.log')
        helper = self.fn('Engine.kt', 'helper')
        self.assertEqual(self.calls(decode), {engine_log['id'], helper['id']})   # template call too
        self.assertEqual(decode['metrics']['unresolved_calls'], 0)
        close = self.fn('Engine.kt', 'Engine.close')
        self.assertEqual(self.calls(close), {engine_log['id'], decode['id']})
        self.assertEqual(close['metrics']['unresolved_calls'], 1)   # listOf
        pick = self.fn('Other.kt', 'pick')
        self.assertEqual(self.calls(pick), {
            self.fn('Other.kt', 'shared')['id'],                       # same file
            self.fn('Engine.kt', 'log')['id'],                         # same package, top-level
            helper['id'],                                              # same package
            self.fn('Util.kt', 'Helper.remote')['id'],                 # unique in corpus
        })
        self.assertEqual(pick['metrics']['unresolved_calls'], 1)   # twice: two packages
        create = self.fn('Engine.kt', 'Engine.Companion.create')
        self.assertIn('type:app/src/main/kotlin/demo/app/Engine.kt#Engine', self.calls(create))
        self.assertIn({'from': 'type:app/src/main/kotlin/demo/app/Engine.kt#Engine',
                       'to': 'type:app/src/main/kotlin/demo/app/Other.kt#Base', 'kind': 'extends',
                       'source': 'static', 'line': 7, 'count': 1}, self.graph['edges'])

    def test_dotted_calls_need_receiver_type_evidence(self):
        use = self.fn('Recv.kt', 'User.use')
        recv = 'app/src/main/kotlin/demo/app/Recv.kt'
        self.assertEqual(self.calls(use), {
            self.fn('Recv.kt', 'Store.read')['id'],             # param, local, ctor param, lazy, file val
            self.fn('Recv.kt', 'Store.Companion.open')['id'],   # Type.x() reaches the companion
            self.fn('Recv.kt', 'topOnly')['id'],                # unknown receiver: unique extension only
            f'type:{recv}#Store',                               # constructor
        })
        # hash.matches(Regex()): String is not a corpus type, Session.matches is a member elsewhere;
        # compute(), compute().read() and mystery.plainTop() (not an extension) have no target.
        self.assertEqual(use['metrics']['unresolved_calls'], 4)
        py = self.fn('gamma.py', 'Holder.use')
        self.assertEqual(self.calls(py), {self.fn('gamma.py', 'Store.read')['id'],
                                          self.fn('gamma.py', 'Other.read')['id'],
                                          self.fn('beta.py', 'go')['id'],   # module alias
                                          'type:tools/gamma.py#Store'})
        self.assertEqual(py['metrics']['unresolved_calls'], 1)   # data.read(); path.join external
        sha = self.fn('gamma.py', 'sha')   # handle.read() must not reach Store.read or a module read
        self.assertEqual(self.calls(sha), set())
        self.assertEqual(sha['metrics']['unresolved_calls'], 1)

    def test_complexity(self):
        self.assertEqual(self.fn('Engine.kt', 'Engine.decode')['metrics']['complexity'], 5)
        self.assertEqual(self.fn('Engine.kt', 'helper')['metrics']['complexity'], 1)
        # if, and, or, elif, for, comprehension-if, except, ifexp
        self.assertEqual(self.fn('alpha.py', 'f')['metrics']['complexity'], 9)

    def test_python_extraction(self):
        m = self.fn('alpha.py', 'A.m')
        self.assertEqual(m['signature'], 'def m(self)')
        self.assertEqual(self.calls(m), {self.fn('alpha.py', 'A.n')['id'], self.fn('beta.py', 'go')['id']})
        self.assertEqual(m['metrics']['unresolved_calls'], 0)   # json.dumps external, len builtin
        self.assertEqual(self.fn('alpha.py', 'f')['metrics']['unresolved_calls'], 1)
        alpha = self.nodes['file:tools/alpha.py']
        self.assertEqual(alpha['imports'], ['json', 'os.path'])
        imports = {(e['from'], e['to']) for e in self.graph['edges'] if e['kind'] == 'imports'}
        self.assertIn(('file:tools/alpha.py', 'file:tools/beta.py'), imports)
        self.assertEqual(self.nodes['type:tools/alpha.py#A']['parent'], 'file:tools/alpha.py')

    def test_tests_referencing_and_health(self):
        self.assertEqual(self.fn('Engine.kt', 'Engine.decode')['metrics']['tests_referencing'], 1)
        self.assertEqual(self.fn('Engine.kt', 'shout')['metrics']['tests_referencing'], 0)
        self.assertEqual(self.fn('alpha.py', 'f')['metrics']['tests_referencing'], 1)
        self.assertEqual(self.fn('alpha.py', 'f')['metrics']['health'], 'ok')
        with tempfile.TemporaryDirectory() as d:
            write_tree(d, kotlin_tests=False)
            g = codemap.build(Path(d), POLICY)
            k = [n for n in g['nodes'] if n['kind'] == 'function' and n['lang'] == 'kotlin']
            self.assertTrue(k and all(n['metrics']['tests_referencing'] is None for n in k))
            self.assertTrue(all(n['metrics']['health'] == 'unknown' for n in k))

    def test_policy_violations(self):
        v = self.graph['violations']
        self.assertEqual(v, sorted(v, key=lambda x: (x['rule'], x['from'], x['to'], x['detail'])))
        self.assertIn(('no-network', 'file:app/src/main/kotlin/demo/app/Engine.kt', 'java.net.URL'),
                      {(x['rule'], x['from'], x['to']) for x in v})
        layer = {(x['from'], x['to']) for x in v if x['rule'] == 'layers'}
        # core (1) -> outer (2): Engine extends Base.
        self.assertIn(('type:app/src/main/kotlin/demo/app/Engine.kt#Engine',
                       'type:app/src/main/kotlin/demo/app/Other.kt#Base'), layer)
        # outer (2) -> core (1) is allowed; tests (3) -> anything allowed.
        self.assertFalse(any(a.startswith('fn:app/src/main/kotlin/demo/app/Other.kt') for a, _ in layer))
        self.assertFalse(any('test' in a for a, _ in layer))

    def test_unassigned_component(self):
        policy = {'components': [{'id': 'tools', 'name': 'Tools', 'layer': 1, 'paths': ['tools/*']}],
                  'rules': [{'id': 'layers', 'kind': 'layer-order'}]}
        g = codemap.build(self.root, policy)
        nodes = {n['id']: n for n in g['nodes']}
        self.assertEqual(nodes['file:app/src/main/kotlin/demo/app/Engine.kt']['parent'], 'cmp:unassigned')
        self.assertIsNone(nodes['cmp:unassigned']['layer'])
        self.assertEqual(nodes['file:tools/beta.py']['parent'], 'cmp:tools')
        self.assertEqual(g['violations'], [])

    def test_trace_resolution(self):
        trace = self.root / 'run.json'
        trace.write_text(json.dumps({'name': 'demo', 'events': [
            {'from': None, 'to': 'Engine.decode', 't_ms': 1.5},
            {'from': 'Engine.decode', 'to': 'app/src/main/kotlin/demo/app/Engine.kt#Engine.log', 't_ms': 2},
            {'from': 'Engine.log', 'to': 'Native.run', 'label': 'jni'},
            {'from': 'Engine.decode', 'to': 'twice'},
        ]}))
        g = codemap.build(self.root, POLICY, [trace])
        (t,) = g['traces']
        self.assertEqual((t['id'], t['name'], t['source']), ('trace:demo', 'demo', str(trace)))
        decode = self.fn('Engine.kt', 'Engine.decode')['id']
        log = self.fn('Engine.kt', 'Engine.log')['id']
        ev = t['events']
        self.assertEqual([e['seq'] for e in ev], [1, 2, 3, 4])
        self.assertEqual((ev[0]['from'], ev[0]['to'], ev[0]['label'], ev[0]['t_ms']), (None, decode, 'decode', 1.5))
        self.assertEqual((ev[1]['from'], ev[1]['to']), (decode, log))
        self.assertEqual((ev[2]['to'], ev[2]['label']), ('ext:Native.run', 'jni'))
        self.assertEqual(ev[3]['to'], 'ext:twice')   # ambiguous: two packages define twice
        self.assertIn({'from': decode, 'to': log, 'kind': 'calls', 'source': 'trace', 'line': None,
                       'count': 1}, g['edges'])

    def test_edges_reference_nodes_and_output_is_deterministic(self):
        ids = {n['id'] for n in self.graph['nodes']}
        self.assertTrue(all(e['from'] in ids and e['to'] in ids for e in self.graph['edges']))
        out = Path(self.tmp.name) / 'out/nested/graph.json'
        policy = Path(self.tmp.name) / 'policy.json'
        policy.write_text(json.dumps(POLICY))
        args = ['--root', str(self.root), '--policy', str(policy), '--out', str(out)]
        with redirect_stdout(io.StringIO()) as stdout, redirect_stderr(io.StringIO()) as stderr:
            self.assertEqual(codemap.main(args), 0)
            first = out.read_bytes()
            self.assertEqual(codemap.main(args + ['--check']), 1)
        self.assertIn('10 files', stdout.getvalue())
        self.assertIn('no-network: file:app/src/main/kotlin/demo/app/Engine.kt -> java.net.URL',
                      stderr.getvalue())
        self.assertEqual(first, out.read_bytes())
        self.assertTrue(first.endswith(b'}\n'))
        self.assertEqual(json.loads(first)['generated_from']['commit'], None)


class CodemapRepoTests(unittest.TestCase):
    def test_real_repo(self):
        with tempfile.TemporaryDirectory() as d:
            out = Path(d) / 'graph.json'
            with redirect_stdout(io.StringIO()):
                codemap.main(['--out', str(out)])
            g = json.loads(out.read_text())
        self.assertEqual(g['schema'], 'codemap/1')
        files = [n for n in g['nodes'] if n['kind'] == 'file']
        self.assertGreater(sum(n['lang'] == 'kotlin' for n in files), 40)
        self.assertGreater(sum(n['kind'] == 'function' for n in g['nodes']), 300)
        self.assertTrue(any(e['kind'] == 'calls' for e in g['edges']))
        self.assertIn('file:tools/phone', {n['id'] for n in files})


if __name__ == '__main__':
    unittest.main()
