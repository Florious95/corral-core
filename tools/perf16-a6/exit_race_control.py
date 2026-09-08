#!/usr/bin/env python3
"""Hosted-only real A6 shutdown-race negative; never a behavior PASS."""
import argparse
import json
import os
from pathlib import Path
import runpy
import sys
import xml.etree.ElementTree as ET

p=argparse.ArgumentParser()
p.add_argument('--runner',type=Path,required=True)
p.add_argument('--binary',type=Path,required=True)
p.add_argument('--root',type=Path,required=True)
a=p.parse_args()
assert os.environ.get('GITHUB_ACTIONS')=='true' and sys.platform=='darwin', 'hosted macOS only'
assert not a.root.exists(), 'use a new private root'
sys.argv=[str(a.runner.resolve()),'--stage','bridge','--control','shutdown-race','--binary',str(a.binary.resolve()),'--root',str(a.root.resolve())]
failed=False
try:
    # Use the bounded runner's own finally/signal handling; do not kill an
    # outer Python process before its resource cleanup can execute.
    runpy.run_path(str(a.runner.resolve()),run_name='__main__')
except SystemExit as error:
    failed=error.code not in (None,0)
assert failed, 'exit race escaped the final gate'
root=a.root
receipt=json.loads((root/'result.json').read_text())
assert receipt['error']=='fixture race, including shutdown phase' and not receipt['behavior_pass'], receipt
cleanup=json.loads((root/'cleanup.json').read_text())
assert not cleanup['errors'] and cleanup['exits']['fixture']!=0, cleanup
suite=ET.parse(root/'junit.xml').getroot()
assert suite.get('tests')=='1' and suite.get('failures')=='0' and suite.get('errors')=='0' and suite.get('skipped','0')=='0', suite.attrib
assert [t.get('name') for t in suite.findall('testcase')]==['realGoServerTmuxBridgeOverflow_servicePumpReauthListSubscribeClearsStaticScreen']
log=(root/'fixture.log').read_text()
assert 'DATA RACE' in log and 'A6 deliberate shutdown race completed:' in log
(root/'shutdown-race-control.json').write_text(json.dumps({'app_named_test_pass':True,'exit_race_rejected':True,'cleanup_checked':True,'behavior_pass':False},indent=2))
print('A6 shutdown-race negative rejected after real named App PASS')
