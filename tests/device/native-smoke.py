#!/usr/bin/env python3
"""Standard-library adb verifier. Never clears app data or clicks order/account mutations."""
import argparse
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PACKAGE = 'cn.run.freepocket'
RUNNER = PACKAGE + '.test/cn.run.freepocket.tests.NativeVerification'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output-dir', type=Path, required=True)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--mode', choices=['release-readonly', 'debug-recovery', 'debug-benchmark', 'debug-browser', 'debug-slow'], required=True)
    args = parser.parse_args()
    out = args.output_dir.resolve()
    if out.exists() and any(out.iterdir()):
        raise RuntimeError("Output directory must be empty to keep this run unambiguous")
    out.mkdir(parents=True, exist_ok=True)
    log = []

    def adb(*command, timeout=60, allowed_codes=(0,)):
        result = subprocess.run([args.adb, '-s', args.serial, *command], capture_output=True, timeout=timeout)
        stdout = result.stdout.decode('utf-8', errors='replace')
        stderr = result.stderr.decode('utf-8', errors='replace')
        log.append({'command': list(command), 'exitCode': result.returncode, 'stdout': stdout, 'stderr': stderr})
        (out / 'commands.json').write_text(json.dumps(log, ensure_ascii=False, indent=2), encoding='utf-8')
        if result.returncode not in allowed_codes:
            raise RuntimeError(f'adb command failed: {command}: {stderr}')
        return stdout

    def instrument(mode, artifact):
        # Reject stale output even when instrumentation aborts without a nonzero adb exit code.
        adb('shell', 'rm', '-f', f'/sdcard/Android/data/{PACKAGE}/files/native-verification/{artifact}')
        result = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'mode', mode, RUNNER, timeout=100)
        (out / f'{mode}-instrumentation.txt').write_text(result, encoding='utf-8')
        if f'INSTRUMENTATION_RESULT: verification=passed: {mode}' not in result or 'INSTRUMENTATION_CODE: -1' not in result:
            raise RuntimeError(f'Instrumentation did not pass {mode}')
        adb('pull', f'/sdcard/Android/data/{PACKAGE}/files/native-verification/{artifact}', str(out / artifact))
        data = json.loads((out / artifact).read_text(encoding='utf-8'))
        if mode in ['recoveryPrepare', 'localRecoveryResume', 'commitBenchmark']:
            if data.get('mode') != mode or data.get('passed') is not True:
                raise RuntimeError('Diagnostic result mode/pass mismatch')
        if data.get('ordersRequested') != 0 or data.get('fixtureOnly') is not True:
            raise RuntimeError('Expected isolated zero-order fixture')
        required_flags = {
            'progressTimeoutFixture': ['readTimeoutVisible', 'progressPreserved'],
            'showFailureFixture': ['invalidAndExternalRejected', 'closedRejected'],
            'accountDraftFixture': ['draftPreserved', 'committedCountryReflected'],
            'slowProgressFixture': ['productionPageStartedDelegated', 'progressAndReminderPreserved'],
            'networkErrorFixture': ['nativeErrorRedacted'],
            'timeoutFixture': ['readTimeoutVisible'],
            'browserFixture': ['windowOpenRouted', 'staleNonceRejected', 'updatedScriptInChildFrame'],
        }
        if any(data.get(flag) is not True for flag in required_flags.get(mode, [])):
            raise RuntimeError(f'Diagnostic assertions missing for {mode}')
        return data

    def dump(name):
        result = adb('shell', 'uiautomator', 'dump', '/sdcard/native-readonly-window.xml')
        if 'dumped to' not in result:
            raise RuntimeError('UI hierarchy dump failed')
        adb('pull', '/sdcard/native-readonly-window.xml', str(out / f'{name}.xml'))
        root = ET.parse(out / f'{name}.xml').getroot()
        if not any(n.get('package') == PACKAGE for n in root.iter('node')):
            raise RuntimeError('Target app is not foreground')
        return root

    def tap_entry(root, resource_id):
        # Only these two read-only panel entries can ever be clicked by this driver.
        assert resource_id in ['account', 'history']
        nodes = [n for n in root.iter('node') if n.get('resource-id') == f'{PACKAGE}:id/{resource_id}' and n.get('enabled') == 'true']
        if len(nodes) != 1:
            raise RuntimeError(f'Missing unique enabled {resource_id} entry')
        bounds = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', nodes[0].get('bounds', ''))
        if not bounds:
            raise RuntimeError('Invalid entry bounds')
        x1, y1, x2, y2 = map(int, bounds.groups())
        if x2 <= x1 or y2 <= y1:
            raise RuntimeError('Entry is off screen')
        adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
        time.sleep(.5)

    if adb('get-state').strip() != 'device':
        raise RuntimeError('Explicit device is not online')
    package_info = adb('shell', 'dumpsys', 'package', PACKAGE)
    debug = bool(re.search(r'flags=\[[^\]]*\bDEBUGGABLE\b', package_info))
    if args.mode == 'release-readonly':
        if debug:
            raise RuntimeError('release-readonly requires a non-debuggable APK')
        result = adb('shell', 'am', 'start', '-W', '-n', PACKAGE + '/.MainActivity')
        if 'Status: ok' not in result or 'Error:' in result:
            raise RuntimeError('Release activity did not start')
        time.sleep(2)
        home = dump('release-home')
        if not any(n.get('resource-id') == PACKAGE + ':id/catalog' for n in home.iter('node')):
            raise RuntimeError('Native catalogue missing')
        if any('WebView' in n.get('class', '') for n in home.iter('node')):
            raise RuntimeError('Startup contains WebView')
        for entry, title in [('account', '账号与设置'), ('history', '领取记录')]:
            tap_entry(home, entry)
            panel = dump('release-' + entry)
            if not any(title in n.get('text', '') for n in panel.iter('node')):
                raise RuntimeError(f'{entry} panel missing')
            adb('shell', 'input', 'keyevent', '4')
            time.sleep(.5)
            home = dump('release-return-' + entry)
        summary = {'passed': True, 'mode': args.mode, 'debuggable': False, 'ordersRequested': 0,
                   'scope': 'Actual release startup and account/history panel navigation only; no real-order acceptance'}
    else:
        if not debug:
            raise RuntimeError('Debug fixtures require a debuggable diagnostic APK')
        if args.mode == 'debug-recovery':
            prepared = instrument('recoveryPrepare', 'recoveryPrepare.json')
            if prepared.get('productPermitsGrantedToFakeTransport') != 1 or prepared.get('reservationDurableBeforePermit') is not True:
                raise RuntimeError('Preparation did not verify durable reservation before fake product permit')
            adb('shell', 'am', 'force-stop', PACKAGE)
            if adb('shell', 'pidof', PACKAGE, allowed_codes=(0, 1)).strip():
                raise RuntimeError('External force-stop did not terminate target process')
            resumed = instrument('localRecoveryResume', 'localRecoveryResume.json')
            if prepared['pid'] == resumed['pid'] or prepared['runId'] != resumed['runId'] or prepared['reservations'] != resumed['reservations']:
                raise RuntimeError('Process/run/reservation recovery mismatch')
            if resumed.get('permissionsGranted') != 0 or resumed.get('observedModes') != ['discover', 'syncFab']:
                raise RuntimeError('Recovery was not read-only')
            summary = resumed
        elif args.mode == 'debug-benchmark':
            summary = instrument('commitBenchmark', 'commitBenchmark.json')
            if summary['totalSamples'] != summary['cold']['samples'] + summary['repeated']['samples'] + summary['retainedHistory']['samples']:
                raise RuntimeError('Benchmark sample count mismatch')
        elif args.mode == 'debug-slow':
            summary = instrument('slowProgressFixture', 'slow-progress-fixture.json')
        else:
            summary = {mode: instrument(mode, artifact) for mode, artifact in [
                ('browserFixture', 'browser-fixture.json'), ('timeoutFixture', 'timeout-fixture.json'),
                ('progressTimeoutFixture', 'progress-timeout-fixture.json'),
                ('showFailureFixture', 'show-failure-fixture.json'),
                ('accountDraftFixture', 'account-draft-fixture.json'),
                ('networkErrorFixture', 'network-error-fixture.json')]}
    (out / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'passed': True, 'mode': args.mode, 'output': str(out)}, ensure_ascii=False))


if __name__ == '__main__':
    main()
