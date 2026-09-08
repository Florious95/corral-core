#!/usr/bin/env python3
"""B1 classifier controls. --hosted also obtains real JUnit body+@After failures.

Synthetic XML checks are parser evidence only. Hosted Gradle XML is archived
unchanged; individual testcases are isolated solely to check their real root
exceptions against the same one-test classifier used by run.py.
"""
import argparse
import ast
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import xml.etree.ElementTree as ET

p = argparse.ArgumentParser()
p.add_argument('--root', type=Path, required=True)
p.add_argument('--hosted', action='store_true')
a = p.parse_args()
root = a.root.resolve()
root.mkdir(parents=True, exist_ok=False)
runner = Path(__file__).with_name('run.py')
module = ast.parse(runner.read_text())
functions = [n for n in module.body if isinstance(n, ast.FunctionDef) and n.name == 'validate_a6_junit']
assert len(functions) == 1
namespace = {'ET': ET, 're': re}
exec(compile(ast.Module(body=functions, type_ignores=[]), str(runner), 'exec'), namespace)
validate = namespace['validate_a6_junit']
classname = 'dev.agentmirror.app.session.Perf16AppRecoveryA6ScenarioTest'
method = 'testOracle'
messages = {'no-abort': 'no RECONNECTING event',
            'screen-corruption': 'cell [20,0] expected:<Cell(text= , width=1)> but was:<Cell(text=X, width=1)>'}
checks = []

def check(label, suite, expected, accept, name=method, cls=classname):
    try:
        validate(ET.tostring(suite), name, expected, cls)
        accepted = True
    except (AssertionError, ET.ParseError):
        accepted = False
    assert accepted == accept, (label, accepted, accept)
    checks.append({'case': label, 'accepted': accepted})

def xml_for(mode):
    suite = ET.Element('testsuite', name=classname, tests='1', failures='1', errors='0', skipped='0')
    test = ET.SubElement(suite, 'testcase', name=method, classname=classname)
    f = ET.SubElement(test, 'failure', type='java.lang.AssertionError', message='java.lang.AssertionError: '+messages[mode])
    f.text = f.get('message')+'\n\tat example.Test.body(Test.java:1)\n'
    return suite

for mode in messages:
    clean = xml_for(mode)
    check(mode+'/single', clean, mode, True)
    bare = copy.deepcopy(clean)
    bare.find('testcase/failure').set('message', messages[mode])
    check(mode+'/bare-message', bare, mode, True)
    for kind in ('failure', 'error'):
        suite = copy.deepcopy(clean)
        extra = ET.SubElement(suite.find('testcase'), kind, type='java.lang.AssertionError', message='teardown failed')
        extra.text = 'java.lang.AssertionError: teardown failed\n\tat example.Test.after(Test.java:2)\n'
        suite.set('failures' if kind == 'failure' else 'errors', '2' if kind == 'failure' else '1')
        check(mode+'/additional-'+kind, suite, mode, False)
    for kind in ('aggregate', 'wrong-root', 'wrong-message', 'suppressed', 'hidden-root', 'wrong-count', 'skipped', 'extra-test'):
        suite = copy.deepcopy(clean)
        f = suite.find('testcase/failure')
        if kind == 'aggregate':
            f.set('type', 'org.junit.runners.model.MultipleFailureException')
            f.text = 'org.junit.runners.model.MultipleFailureException: '+messages[mode]+'; teardown failed'
        elif kind == 'wrong-root': f.set('type', 'java.lang.IllegalStateException')
        elif kind == 'wrong-message': f.set('message', 'unrelated '+messages[mode])
        elif kind == 'suppressed': f.text += '\tSuppressed: java.lang.AssertionError: teardown failed\n'
        elif kind == 'hidden-root': f.text += 'java.lang.AssertionError: teardown failed\n'
        elif kind == 'wrong-count': suite.set('failures', '2')
        elif kind == 'skipped': ET.SubElement(suite.find('testcase'), 'skipped')
        elif kind == 'extra-test': ET.SubElement(suite, 'testcase', name='teardown', classname=classname)
        check(mode+'/'+kind, suite, mode, False)

positive = xml_for('no-abort')
positive.find('testcase').remove(positive.find('testcase/failure'))
positive.set('failures', '0')
check('candidate/single-pass', positive, None, True)
check('candidate/reject-failure', xml_for('no-abort'), None, False)

if a.hosted:
    assert sys.platform == 'darwin' and os.environ.get('GITHUB_ACTIONS') == 'true', 'hosted macOS only'
    repo = runner.resolve().parents[2]
    fixture_class = 'dev.agentmirror.app.session.Perf16A6FailureClassifierFixtureTest'
    fixture = repo/'app/app/src/test/java/dev/agentmirror/app/session/Perf16A6FailureClassifierFixtureTest.java'
    xml = repo/'app/app/build/test-results/testDebugUnitTest'/('TEST-'+fixture_class+'.xml')
    assert not fixture.exists(), 'never overwrite an existing test source'
    source = '''package dev.agentmirror.app.session;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import static org.junit.Assert.*;
public class Perf16A6FailureClassifierFixtureTest {
    @Rule public TestName name = new TestName();
    static class Cell {
        final String text;
        Cell(String text) { this.text = text; }
        public String toString() { return "Cell(text=" + text + ", width=1)"; }
    }
    void reconnect() { assertTrue("no RECONNECTING event", false); }
    void cell() { assertEquals("cell [20,0]", new Cell(" "), new Cell("X")); }
    @Test public void expectedReconnect() { reconnect(); }
    @Test public void expectedCell() { cell(); }
    @Test public void mixedReconnect() { reconnect(); }
    @Test public void mixedCell() { cell(); }
    @Test public void mixedError() { reconnect(); }
    @Test public void wrongRoot() { throw new IllegalStateException("no RECONNECTING event"); }
    @Test public void ordinaryPass() { assertTrue(true); }
    @After public void teardown() {
        if (name.getMethodName().equals("mixedError")) throw new IllegalStateException("intentional teardown error");
        if (name.getMethodName().startsWith("mixed")) fail("intentional teardown failure");
    }
}
'''
    (root/'fixture.java').write_text(source)
    fixture.write_text(source)
    xml.unlink(missing_ok=True)
    proc = None
    try:
        command = ['./gradlew', '--no-daemon', '--rerun-tasks', '-Pkotlin.compiler.execution.strategy=in-process', ':app:testDebugUnitTest', '--tests', fixture_class]
        with (root/'gradle.log').open('wb') as log:
            proc = subprocess.Popen(command, cwd=repo/'app', stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
            code = proc.wait(timeout=180)
        assert code != 0, 'real mixed-failure fixture must be red'
        content = xml.read_bytes()
        (root/'junit-original.xml').write_bytes(content)
        suite = ET.fromstring(content)
        assert suite.get('name') == fixture_class and suite.get('tests') == '7' and suite.get('skipped') == '0'
        cases = suite.findall('testcase')
        expected = {'expectedReconnect': ('no-abort', True), 'expectedCell': ('screen-corruption', True),
                    'mixedReconnect': ('no-abort', False), 'mixedCell': ('screen-corruption', False),
                    'mixedError': ('no-abort', False), 'wrongRoot': ('no-abort', False), 'ordinaryPass': (None, True)}
        assert len(cases) == 7 and {t.get('name') for t in cases} == set(expected)
        assert not list(suite.iter('skipped'))
        assert suite.get('failures') == '6' and suite.get('errors') == '0', suite.attrib
        for case in cases:
            # Preserve each original failure/error/type/message/stack verbatim.
            isolated = ET.Element('testsuite', name=fixture_class, tests='1', skipped='0',
                                  failures=str(len(case.findall('failure'))), errors=str(len(case.findall('error'))))
            isolated.append(copy.deepcopy(case))
            mode, accept = expected[case.get('name')]
            check('real-junit/'+case.get('name'), isolated, mode, accept, case.get('name'), fixture_class)
        (root/'hosted.json').write_text(json.dumps({'command': command, 'exit': code, 'junit_sha256': hashlib.sha256(content).hexdigest(), 'executed_tests': 7, 'behavior_pass': False}, indent=2))
    finally:
        fixture.unlink(missing_ok=True)
        if proc is not None:
            # Own only this Gradle group; a timeout/descendant is apparatus red.
            import time
            deadline = time.monotonic()+2
            while True:
                try: os.killpg(proc.pid, 0)
                except ProcessLookupError: break
                if time.monotonic() >= deadline:
                    os.killpg(proc.pid, signal.SIGKILL)
                    proc.wait(timeout=2)
                    raise AssertionError('classifier fixture required process-group KILL')
                time.sleep(.02)

(root/'classifier.json').write_text(json.dumps({'runner_sha256': hashlib.sha256(runner.read_bytes()).hexdigest(), 'checks': checks, 'hosted_junit_executed': a.hosted, 'a6_behavior_pass': False}, indent=2))
print('B1 classifier checks:', len(checks), 'hosted JUnit:', a.hosted)
