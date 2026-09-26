"""Read-only export of the independent Debug fixture. Never starts an Activity or sends a gesture."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess


def validate_report(report, run, phase, previous=None):
    if (report.get('version') != 1 or report.get('run_id') != run or
            report.get('model_calls') != 0 or report.get('dispatches') != 3 or
            not isinstance(report.get('instance_id'), str)):
        raise ValueError('invalid_identity_or_counts')
    actions = report.get('actions', [])
    if ([a.get('type') for a in actions] != ['tap', 'longpress', 'swipe'] or
            any(a.get('hit') is not True or a.get('dispatch_returned') is not True for a in actions)):
        raise ValueError('hit_not_confirmed')
    if phase == 'initial':
        if report.get('phase') != 'stale_armed':
            raise ValueError('not_armed')
    else:
        if not previous or any(report.get(k) != previous.get(k) for k in ('run_id', 'instance_id')):
            raise ValueError('instance_changed')
        if (report.get('phase') != 'passed' or report.get('stale_dispatches') != 0 or
                report.get('guarded_execution_entered') is not False or
                report.get('stale_rejection') != 'stale_screenshot_coordinates'):
            raise ValueError('stale_rejection_not_confirmed')
        if report.get('actions') != previous.get('actions') or report.get('stale_binding') != previous.get('stale_binding'):
            raise ValueError('original_evidence_changed')
    names = [a.get('name') for a in report.get('artifacts', [])]
    if names != ['tap.jpg', 'longpress.jpg', 'swipe.jpg', 'stale.jpg']:
        raise ValueError('invalid_artifact_list')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--adb', required=True)
    p.add_argument('--serial', required=True, choices=['emulator-5554', '3B15AM00LXZ00000'])
    p.add_argument('--run-id', required=True)
    p.add_argument('--out', required=True, type=Path)
    p.add_argument('--phase', required=True, choices=['initial', 'final'])
    args = p.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,80}', args.run_id):
        raise ValueError('invalid_run_id')
    directory = args.out.resolve()
    if any((parent / '.git').exists() for parent in (directory, *directory.parents)):
        raise ValueError('evidence_must_be_outside_git')
    if args.phase == 'initial':
        directory.mkdir(mode=0o700, parents=True, exist_ok=False)
        if os.name == 'nt':
            account = subprocess.check_output(['whoami'], text=True).strip()
            subprocess.run(['icacls', str(directory), '/inheritance:r', '/grant:r', account+':(OI)(CI)F'],
                           check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    previous = json.loads((directory/'initial-report.json').read_text(encoding='utf-8')) if args.phase == 'final' else None
    def read(name):
        return subprocess.check_output([args.adb, '-s', args.serial, 'exec-out', 'run-as', 'bot.coati',
            'cat', f'files/coordinate-fixture/{args.run_id}/{name}'], timeout=20, stderr=subprocess.PIPE)
    raw = read('report.json')
    with (directory/f'{args.phase}-report.json').open('xb') as f:
        f.write(raw)
    report = json.loads(raw)
    validate_report(report, args.run_id, args.phase, previous)
    for artifact in report['artifacts']:
        content = read(artifact['name'])
        if hashlib.sha256(content).hexdigest() != artifact['sha256']:
            raise ValueError('artifact_hash_mismatch')
        target = directory/artifact['name']
        if target.exists():
            if target.read_bytes() != content:
                raise ValueError('existing_evidence_mismatch')
        else:
            with target.open('xb') as f:
                f.write(content)
    print(json.dumps(dict(phase=report['phase'], run_id=args.run_id, instance_id=report['instance_id'],
                         dispatches=3, model_calls=0, artifacts_verified=4)))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # No adb output, raw exceptions or request content in host logs.
        print(json.dumps({'ok': False, 'error': 'export_failed', 'exception_type': type(error).__name__}))
        raise SystemExit(1)
