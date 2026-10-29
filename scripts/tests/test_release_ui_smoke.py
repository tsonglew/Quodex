import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch, call
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location('release_ui_smoke', Path(__file__).parents[1] / 'release_ui_smoke.py')
ui = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ui)


def screen(target='[300,1010][700,1060]', labels=('Quota', 'Widgets', 'Settings'), rail=False):
    root = ET.fromstring('<hierarchy><node text="Settings" bounds="[20,20][200,80]" />'
                         f'<node text="Privacy and data" bounds="{target}" /></hierarchy>')
    for index, label in enumerate(labels):
        top = 200 + index * 150 if rail else 1000
        left = 20 if rail else 100 + index * 270
        tab = ET.SubElement(root, 'node', clickable='false' if index == 2 else 'true',
                            selected='true' if index == 2 else 'false',
                            bounds=f'[{left},{top}][{left+260},{top+100}]')
        ET.SubElement(tab, 'node', text=label, bounds=f'[{left+20},{top+50}][{left+240},{top+90}]')
    return root


class ReleaseUiSmokeTests(unittest.TestCase):
    def test_button_beneath_transparent_navigation_requires_scrolling(self):
        tree = screen()
        self.assertTrue(ui.covered_by_navigation(ui.find('Privacy and data', tree), tree))

    def test_partially_covered_button_and_system_gesture_area_are_avoided(self):
        for rectangle in ['[300,950][700,1010]', '[300,1110][700,1160]']:
            tree = screen(target=rectangle)
            self.assertTrue(ui.covered_by_navigation(ui.find('Privacy and data', tree), tree))

    def test_visible_button_above_dock_is_clickable(self):
        tree = screen(target='[300,850][700,950]')
        self.assertFalse(ui.covered_by_navigation(ui.find('Privacy and data', tree), tree))

    def test_localized_tabs_remain_clickable_despite_repeated_heading(self):
        for labels in [('Quota', 'Widgets', 'Settings'), ('额度', '小组件', '设置'), ('使用枠', 'ウィジェット', '設定')]:
            tree = screen(labels=labels)
            tabs = ui.navigation_items(tree)
            self.assertEqual(3, len(tabs))
            for label, _ in tabs:
                self.assertFalse(ui.covered_by_navigation(label, tree))

    def test_dialog_without_navigation_and_wide_rail_are_not_treated_as_bottom_overlay(self):
        dialog = ET.fromstring('<hierarchy><node text="Close" bounds="[300,1010][700,1060]" /></hierarchy>')
        self.assertFalse(ui.covered_by_navigation(ui.find('Close', dialog), dialog))
        tree = screen(rail=True)
        self.assertFalse(ui.covered_by_navigation(ui.find('Privacy and data', tree), tree))

    def test_zero_size_node_is_never_tapped(self):
        tree = screen(target='[0,0][0,0]')
        self.assertTrue(ui.covered_by_navigation(ui.find('Privacy and data', tree), tree))

    def test_real_tap_is_sent_only_after_target_has_scrolled_above_dock(self):
        with patch.object(ui, 'nodes', side_effect=[screen(), screen('[300,400][700,450]')]), \
                patch.object(ui, 'shell', return_value='Physical size: 1000x1200') as shell, \
                patch.object(ui.time, 'sleep'):
            ui.click('Privacy and data')
        self.assertEqual([call('wm', 'size'), call('input', 'swipe', '500', '900', '500', '400', '300'),
                          call('input', 'tap', '500', '425')], shell.call_args_list)

    def test_long_settings_page_can_be_searched_beyond_six_swipes(self):
        missing = ET.fromstring('<hierarchy />')
        with patch.object(ui, 'nodes', side_effect=[missing] * 7 + [screen('[300,400][700,450]')]), \
                patch.object(ui, 'shell', return_value='Physical size: 1000x1200') as shell, \
                patch.object(ui.time, 'sleep'):
            ui.click('Privacy and data', scroll=True)
        self.assertEqual(call('input', 'tap', '500', '425'), shell.call_args_list[-1])

    def test_still_occluded_target_fails_instead_of_tapping_the_dock(self):
        with patch.object(ui, 'nodes', return_value=screen()), \
                patch.object(ui, 'shell', return_value='Physical size: 1000x1200') as shell, \
                patch.object(ui.time, 'sleep'):
            with self.assertRaisesRegex(AssertionError, 'Privacy and data'):
                ui.click('Privacy and data')
        self.assertFalse(any(item.args[:2] == ('input', 'tap') for item in shell.call_args_list))


if __name__ == '__main__':
    unittest.main()
