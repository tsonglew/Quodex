from pathlib import Path
from types import SimpleNamespace
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from background_power_probe import Probe, background_allowed, fixture_hosts_are_local, validated_default, widget_ids


class BackgroundPowerEvidenceTest(unittest.TestCase):
    def test_explicit_default_is_not_unrestricted_background(self):
        self.assertTrue(background_allowed('No operations.'))
        self.assertTrue(background_allowed('RUN_ANY_IN_BACKGROUND: allow'))
        self.assertFalse(background_allowed('RUN_ANY_IN_BACKGROUND: default'))
        self.assertFalse(background_allowed('RUN_ANY_IN_BACKGROUND: ignore'))

    def test_restore_attempts_remaining_settings_after_command_failure(self):
        probe = Probe(SimpleNamespace(output='/unused'))
        probe.original_op, probe.original_deep, probe.original_saver = 'allow', '0', 'null'
        calls = []

        def shell(*command):
            calls.append(command)
            if len(calls) == 1:
                raise RuntimeError('injected adb failure')

        probe.shell = shell
        with self.assertRaisesRegex(RuntimeError, 'System restoration failed'):
            probe.restore()
        self.assertIn(('dumpsys', 'battery', 'reset'), calls)
        self.assertIn(('dumpsys', 'deviceidle', 'disable', 'deep'), calls)
        self.assertIn(('settings', 'delete', 'global', 'low_power'), calls)
        self.assertIn(('cmd', 'appops', 'set', 'com.tsonglew.quodex', 'RUN_ANY_IN_BACKGROUND', 'allow'), calls)

    def test_fixture_hosts_cannot_include_external_or_missing_routes(self):
        hosts = '127.0.0.1 auth.openai.com chatgpt.com\n::1 auth.openai.com chatgpt.com\n'
        self.assertTrue(fixture_hosts_are_local(hosts))
        self.assertFalse(fixture_hosts_are_local(hosts + '192.0.2.1 chatgpt.com\n'))
        self.assertFalse(fixture_hosts_are_local('# ' + hosts.replace('\n', ' ')))
        self.assertFalse(fixture_hosts_are_local('127.0.0.1 auth.openai.com\n'))

    def test_other_validated_network_does_not_validate_default(self):
        dump = '''Active default network: 101
  NetworkAgentInfo{network{100} Policies : EVER_VALIDATED&IS_VALIDATED}
  NetworkAgentInfo{network{101} Policies : EVER_VALIDATED}
'''
        self.assertFalse(validated_default(dump))
        self.assertFalse(validated_default(dump.replace('Active default network: 101', 'Active default network: none')))
        self.assertTrue(validated_default(dump.replace('network{101} Policies : EVER_VALIDATED',
                                                       'network{101} Policies : EVER_VALIDATED&IS_VALIDATED')))

    def test_historical_or_request_validation_is_not_connection_evidence(self):
        dump = '''Active default network: 101
  NetworkAgentInfo{network{101} Policies : EVER_VALIDATED}
  history: network{101} IS_VALIDATED
  NetworkRequest [ Capabilities: INTERNET&VALIDATED ]
'''
        self.assertFalse(validated_default(dump))

    def test_widget_ids_exclude_other_providers_and_hosts(self):
        dump = '''Widgets:
  [0] id=3
    provider=com.tsonglew.quodex/.widget.QuotaWidgetProvider
  [1] id=4
    provider=com.android.other/.Widget
  [2] id=8
    provider=com.tsonglew.quodex/.widget.CompactWidgetProvider
Hosts:
  [0] id=999
    package=com.tsonglew.quodex/ignored
'''
        self.assertEqual([3, 8], widget_ids(dump))


if __name__ == '__main__':
    unittest.main()
