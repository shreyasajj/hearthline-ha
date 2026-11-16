"""Exercise the privileged workflow policy without GitHub writes or PR execution."""
import json
import os
from pathlib import Path
from unittest.mock import patch

import pytest
import yaml

ROOT=Path(__file__).resolve().parents[1]
WORKFLOW=yaml.safe_load((ROOT/'.github/workflows/dependabot-merge.yml').read_text())
SCRIPT='\n'.join(WORKFLOW['jobs']['merge']['steps'][0]['run'].splitlines()[1:-1])
REPO='owner/integration'
HEAD='a'*40
BASE='b'*40

def exercise(author='dependabot[bot]',conclusion='success',current_head=HEAD,head_repo=REPO,draft=False,approved=False):
    calls=[]
    def gh(command,**kwargs):
        args=command[1:]; calls.append(args)
        if args[0]=='pr': return ''
        if '--method' in args: return '{}'
        path=args[1]
        if path.endswith('/actions/runs/1'):
            data={'id':1,'conclusion':conclusion,'event':'pull_request','path':'.github/workflows/ci.yml','head_branch':'dependabot/pip/update','head_sha':HEAD}
        elif path.endswith('/jobs?filter=latest'):
            data={'jobs':[{'name':'CI gate','conclusion':'success'}]}
        elif '/pulls?' in path:
            data=[{'number':7,'user':{'login':author},'draft':draft,'head':{'repo':{'full_name':head_repo},'sha':current_head}}]
        elif path.endswith('/git/ref/heads/main'):
            data={'object':{'sha':BASE}}
        elif '/compare/' in path:
            data={'merge_base_commit':{'sha':BASE}}
        elif path.endswith('/reviews?per_page=100'):
            data=[{'user':{'login':'github-actions[bot]'},'state':'APPROVED','commit_id':HEAD}] if approved else []
        else: raise AssertionError('Unexpected GitHub read: '+path)
        return json.dumps(data)
    with patch.dict(os.environ,{'REPO':REPO,'RUN_ID':'1'}), patch('subprocess.check_output',side_effect=gh):
        try: exec(compile(SCRIPT,'dependabot-workflow','exec'),{})
        except SystemExit:
            if conclusion=='success': raise
    return calls

def writes(calls):
    return [call for call in calls if '--method' in call or call[:2]==['pr','merge']]

def test_approval_is_for_tested_commit_and_precedes_merge():
    changes=writes(exercise())
    assert len(changes)==2
    assert 'event=APPROVE' in changes[0] and 'commit_id='+HEAD in changes[0]
    assert changes[1][:2]==['pr','merge']
    assert changes[1][-2:]==['--match-head-commit',HEAD]

@pytest.mark.parametrize('options',[{'author':'human-owner'},{'conclusion':'failure'},{'current_head':'c'*40},{'head_repo':'fork/integration'},{'draft':True}])
def test_unqualified_pr_has_no_approval_or_merge(options):
    assert writes(exercise(**options))==[]

def test_retry_does_not_post_duplicate_approval():
    changes=writes(exercise(approved=True))
    assert len(changes)==1 and changes[0][:2]==['pr','merge']
