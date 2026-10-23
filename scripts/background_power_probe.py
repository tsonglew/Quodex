"""External API 31 observation of a synthetic account in an owned emulator.

Requires upgrade_lab_server direct TLS, a connected APK and an existing widget.
Never installs APKs, modifies databases, forces jobs or disables real networking.
"""
import argparse
import json
from pathlib import Path
import re
import sqlite3
import subprocess
import tempfile
import time
from urllib.request import urlopen

PACKAGE = 'com.tsonglew.quodex'
SYNTHETIC_ACCOUNT = 'quodex-synthetic-upgrade-account'


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def validated_default(network_dump):
    match = re.search(r'Active default network: (\d+)', network_dump)
    if not match:
        return False
    return any('NetworkAgentInfo{' in line and 'network{' + match[1] + '}' in line
               and 'IS_VALIDATED' in line for line in network_dump.splitlines())


def widget_ids(dump):
    section = dump.split('Widgets:\n', 1)[1].split('Hosts:', 1)[0]
    return sorted(int(m[1]) for m in re.finditer(r'id=(\d+)\n(.*?)(?=\n  \[|\Z)', section, re.S)
                  if PACKAGE + '/' in m[2])


def fixture_hosts_are_local(hosts):
    addresses = {host: set() for host in ('auth.openai.com', 'chatgpt.com')}
    for line in hosts.splitlines():
        fields = line.split('#', 1)[0].split()
        if len(fields) < 2:
            continue
        for host in fields[1:]:
            if host in addresses:
                addresses[host].add(fields[0])
    return all(values and values <= {'127.0.0.1', '::1'} for values in addresses.values())


def background_allowed(appops):
    # Explicit MODE_DEFAULT is not MODE_ALLOWED for this operation on API 31.
    return 'No operations.' in appops or bool(re.search(r'RUN_ANY_IN_BACKGROUND: allow\b', appops))


class Probe:
    def __init__(self, args):
        self.args = args
        self.output = Path(args.output)

    def adb(self, *args, check=True):
        return subprocess.run([self.args.adb, '-s', self.args.serial, *args],
                              capture_output=True, check=check, timeout=30).stdout

    def shell(self, *args):
        return self.adb('shell', *args).decode().strip()

    def state(self):
        with urlopen(f'http://127.0.0.1:{self.args.control_port}/lab/state', timeout=5) as response:
            return json.load(response)

    def database(self, remote, query):
        # These temporary copies are never published; export only selected metadata.
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'copy.db'
            for suffix in ('', '-wal'):
                data = self.adb('exec-out', 'cat', f'/data/user/0/{PACKAGE}/{remote}{suffix}',
                                check=not suffix)
                if data:
                    Path(str(path) + suffix).write_bytes(data)
            connection = sqlite3.connect(path)
            connection.row_factory = sqlite3.Row
            try:
                require(connection.execute('PRAGMA quick_check').fetchone()[0] == 'ok', 'Inconsistent database copy')
                return [dict(row) for row in connection.execute(query)]
            finally:
                connection.close()

    def snapshot(self, label):
        work = self.database('no_backup/androidx.work.workdb',
            "SELECT id,state,interval_duration,last_enqueue_time,period_count,run_attempt_count FROM workspec "
            "WHERE id IN (SELECT work_spec_id FROM workname WHERE name='codex-periodic-sync') AND state NOT IN (2,3,5)")
        quota = self.database('databases/quota.db', 'SELECT accountId,json FROM usage_snapshot WHERE slot=1')
        require(len(quota) == 1 and quota[0]['accountId'] == SYNTHETIC_ACCOUNT, 'Requires synthetic account')
        quota = json.loads(quota[0]['json'])
        require(quota['accountId'] == SYNTHETIC_ACCOUNT and quota['plan'] == 'synthetic', 'Unexpected cache source')
        network = self.shell('dumpsys', 'connectivity')
        jobs = self.shell('dumpsys', 'jobscheduler', PACKAGE)
        result = dict(time=time.time(), server=self.state(), work=work, fetched_at=quota['fetchedAt'],
                      validated=validated_default(network), widgets=widget_ids(self.shell('dumpsys', 'appwidget')))
        (self.output / f'{label}.json').write_text(json.dumps(result, indent=2) + '\n')
        (self.output / f'{label}-jobs.txt').write_text(jobs)
        print(label, json.dumps(result), flush=True)
        return result, jobs

    def crash_evidence(self, label):
        for name, command in (
            ('crash', ('logcat', '-b', 'crash', '-d', '-v', 'threadtime')),
            ('exit-info', ('dumpsys', 'activity', 'exit-info', PACKAGE)),
            ('last-anr', ('dumpsys', 'activity', 'lastanr')),
        ):
            (self.output / f'{label}-{name}.txt').write_text(self.shell(*command))

    def restore(self):
        # Attempt every restoration even if one adb call fails.
        failures = []
        commands = [('dumpsys', 'deviceidle', 'unforce'), ('cmd', 'power', 'set-mode', '0'),
                    ('cmd', 'appops', 'set', PACKAGE, 'RUN_ANY_IN_BACKGROUND', self.original_op),
                    ('dumpsys', 'battery', 'reset'), ('input', 'keyevent', 'KEYCODE_WAKEUP')]
        if self.original_deep == '0':
            commands.append(('dumpsys', 'deviceidle', 'disable', 'deep'))
        if self.original_saver == 'null':
            commands.append(('settings', 'delete', 'global', 'low_power'))
        for command in commands:
            try:
                self.shell(*command)
            except Exception as error:
                failures.append(str(error))
        require(not failures, 'System restoration failed: ' + '; '.join(failures))
        require(background_allowed(self.shell('cmd', 'appops', 'get', PACKAGE, 'RUN_ANY_IN_BACKGROUND')),
                'Background restriction was not cleared')

    def run(self):
        require(self.shell('getprop', 'ro.kernel.qemu') == '1', 'Emulator required')
        require(self.adb('emu', 'avd', 'name').decode().splitlines()[0] == self.args.avd, 'Wrong AVD')
        require(self.shell('getprop', 'ro.build.version.sdk') == '31', 'Parser verified for API 31 only')
        require(self.shell('id', '-u') == '0', 'Requires already-rooted disposable emulator')
        require(self.shell('settings', 'get', 'global', 'http_proxy') in (':0', 'null'), 'Disable global proxy first')
        require(fixture_hosts_are_local(self.shell('cat', '/system/etc/hosts')), 'Fixture hosts must resolve only to loopback')
        reverse = self.adb('reverse', '--list').decode().splitlines()
        require(any(line.split()[1:] == ['tcp:443', f'tcp:{self.args.tls_port}'] for line in reverse), 'Missing direct TLS reverse mapping')
        require(self.shell('dumpsys', 'deviceidle', 'get', 'deep') == 'ACTIVE', 'Another idle experiment is active')
        require(self.shell('dumpsys', 'deviceidle', 'get', 'screen') == 'true', 'Start with screen on')
        require('UPDATES STOPPED' not in self.shell('dumpsys', 'battery'), 'Battery already overridden')
        self.original_deep = self.shell('dumpsys', 'deviceidle', 'enabled', 'deep')
        require(self.original_deep in ('0', '1'), 'Unknown deep idle setting')
        self.original_saver = self.shell('settings', 'get', 'global', 'low_power')
        require(self.original_saver in ('0', 'null'), 'Battery saver already active')
        op = self.shell('cmd', 'appops', 'get', PACKAGE, 'RUN_ANY_IN_BACKGROUND')
        require(background_allowed(op), 'Existing background restriction')
        self.original_op = 'allow'
        require(not self.output.exists(), 'Use a fresh output directory')
        self.output.mkdir(parents=True)
        (self.output / 'original-system.json').write_text(json.dumps(dict(
            deep=self.original_deep, saver=self.original_saver, appops=op), indent=2) + '\n')
        base, _ = self.snapshot('baseline')
        self.crash_evidence('baseline')
        require(len(base['work']) == 1 and base['work'][0]['interval_duration'] == 900000, 'Select 15-minute interval with one active task')
        require(base['work'][0]['period_count'] >= 1 and base['work'][0]['state'] == 0, 'Wait for initial work to settle')
        require(base['validated'] and base['widgets'], 'Requires validated default network and widget')
        require(base['server']['usage'] >= 1 and not base['server']['requireRenewal'], 'Use fresh healthy fixture')
        require(base['time'] * 1000 < base['work'][0]['last_enqueue_time'] + 840000, 'Task is too close to due time; wait for next cycle')
        self.shell('input', 'keyevent', 'KEYCODE_HOME')
        # Widget/session cleanup may briefly keep the just-finished worker's
        # process important. Wait for am kill to be permitted; never force-stop.
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            self.shell('am', 'kill', PACKAGE)
            time.sleep(2)
            if not self.adb('shell', 'pidof', PACKAGE, check=False).strip():
                break
        require(not self.adb('shell', 'pidof', PACKAGE, check=False).strip(), 'App process still alive')
        require(time.time() * 1000 < base['work'][0]['last_enqueue_time'] + 900000,
                'Periodic task became due during setup; retry after it settles')
        try:
            self.shell('dumpsys', 'battery', 'unplug')
            if self.args.mode == 'doze':
                self.shell('dumpsys', 'deviceidle', 'enable', 'deep')
                self.shell('input', 'keyevent', 'KEYCODE_SLEEP')
                self.shell('dumpsys', 'deviceidle', 'force-idle')
            elif self.args.mode == 'restricted':
                self.shell('cmd', 'appops', 'set', PACKAGE, 'RUN_ANY_IN_BACKGROUND', 'ignore')
            else:
                self.shell('cmd', 'power', 'set-mode', '1')
            start = time.monotonic()
            for index in range(32):
                time.sleep(30)
                if self.args.mode == 'doze':
                    require(self.shell('dumpsys', 'deviceidle', 'get', 'deep') == 'IDLE', 'Doze disappeared')
                elif self.args.mode == 'restricted':
                    require('ignore' in self.shell('cmd', 'appops', 'get', PACKAGE, 'RUN_ANY_IN_BACKGROUND'), 'Restriction disappeared')
                else:
                    require(self.shell('settings', 'get', 'global', 'low_power') == '1', 'Saver disappeared')
                count = self.state()['usage'] - base['server']['usage']
                require(0 <= count <= (2 if self.args.mode == 'saver' else 0), 'Unexpected request count')
                require(validated_default(self.shell('dumpsys', 'connectivity')), 'Default network lost validation')
                if index % 4 == 3:
                    print(f'observed {time.monotonic() - start:.1f}s; requests={count}', flush=True)
            end, jobs = self.snapshot('restricted-period')
            require(end['widgets'] == base['widgets'] and len(end['work']) == 1, 'Widget or task lost')
            require(end['work'][0]['id'] == base['work'][0]['id'], 'Task replaced')
            if self.args.mode != 'saver':
                constraint = 'readyNotDozing' if self.args.mode == 'doze' else 'readyNotRestrictedInBg'
                require(constraint + ': false' in jobs, 'Job not blocked by expected restriction')
                require(end['fetched_at'] == base['fetched_at'], 'Success timestamp changed while blocked')
                require(end['work'][0]['last_enqueue_time'] == base['work'][0]['last_enqueue_time'], 'Blocked work unexpectedly ran')
            self.restore()
            deadline = time.monotonic() + 240
            while self.state()['usage'] == base['server']['usage'] and time.monotonic() < deadline:
                time.sleep(5)
            time.sleep(3)  # Let the successful response and WorkManager result commit.
            final, _ = self.snapshot('recovered')
            require(base['server']['usage'] < final['server']['usage'] <= base['server']['usage'] + 2, 'No bounded automatic refresh')
            require(final['fetched_at'] > base['fetched_at'], 'Cache never refreshed')
            require(final['widgets'] == base['widgets'] and len(final['work']) == 1, 'Widget or task lost after recovery')
            require(final['work'][0]['id'] == base['work'][0]['id'] and final['validated'], 'Recovery state mismatch')
        finally:
            try:
                self.restore()
            finally:
                self.crash_evidence('final')
        print('PASS: bounded requests and automatic cache recovery; inspect crash/ANR evidence separately', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--avd', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--control-port', type=int, default=9443)
    parser.add_argument('--tls-port', type=int, default=9444)
    parser.add_argument('--mode', choices=('doze', 'saver', 'restricted'), required=True)
    Probe(parser.parse_args()).run()


if __name__ == '__main__':
    main()
