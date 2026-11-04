#!/usr/bin/env python3
"""Static Android resource/consumer contracts; does not substitute for TalkBack or platform NSC."""
import pathlib
import unittest
import xml.etree.ElementTree as ET
ROOT=pathlib.Path(__file__).resolve().parents[1]
A='{http://schemas.android.com/apk/res/android}'

def editable_input_ids(root):
    fields = {}
    for element in root.iter():
        if not element.tag.endswith('EditText'):
            continue
        field_id = element.get(A + 'id', '').replace('@+id/', '@id/')
        if not field_id.startswith('@id/') or field_id == '@id/':
            raise ValueError('Editable input has no resource id')
        if field_id in fields:
            raise ValueError('Duplicate editable input id: ' + field_id)
        fields[field_id] = element
    return fields


def validate_input_labels(root):
    """检查每个真实输入的明确标签，不以历史页面的字段数量代替可访问性。"""
    fields = editable_input_ids(root)
    covered = set()
    for label in root.iter():
        target = label.get(A + 'labelFor')
        if not target:
            continue
        target = target.replace('@+id/', '@id/')
        if target not in fields:
            raise ValueError('Input label target is not an editable field: ' + target)
        text = (label.get(A + 'text') or label.get(A + 'contentDescription') or '').strip()
        if not text or text == '@null':
            raise ValueError('Input label is empty: ' + target)
        if label.get(A + 'visibility') in ('gone', 'invisible'):
            raise ValueError('Input label is hidden: ' + target)
        covered.add(target)
    missing = sorted(fields.keys() - covered)
    if missing:
        raise ValueError('Editable inputs missing explicit labels: ' + ', '.join(missing))
    return set(fields)


class UiBoundaryTest(unittest.TestCase):
    def xml(self,path):return ET.parse(ROOT/path).getroot()
    def test_known_update_domains_deny_http_and_api23_still_declares_compatibility(self):
        config=self.xml('app/src/main/res/xml/network_security_config.xml')
        self.assertEqual('true',config.find('base-config').get('cleartextTrafficPermitted'))
        rules={d.text:c.get('cleartextTrafficPermitted') for c in config.findall('domain-config') for d in c.findall('domain')}
        for host in ['localhost','127.0.0.1','::1']:self.assertEqual('true',rules[host])
        for host in ['dsha.cc','github.com','release-assets.githubusercontent.com','objects.githubusercontent.com']:self.assertEqual('false',rules[host])
        app=self.xml('app/src/main/AndroidManifest.xml').find('application')
        self.assertEqual('@xml/network_security_config',app.get(A+'networkSecurityConfig'));self.assertEqual('true',app.get(A+'usesCleartextTraffic'))
    def test_input_labels_reference_real_existing_fields(self):
        required = {
            'activity_adb_pair.xml': {'@id/adb_pair_host', '@id/adb_pair_port', '@id/adb_pair_code'},
            'fragment_config.xml': {'@id/config_port'},
        }
        for path, expected_fields in required.items():
            with self.subTest(layout=path):
                fields = validate_input_labels(self.xml('app/src/main/res/layout/' + path))
                self.assertTrue(expected_fields <= fields, 'Required editable fields are missing: ' + path)
    def test_static_welcome_rows_do_not_look_clickable_and_status_indicator_is_decorative(self):
        for path in ['welcome_page2.xml','welcome_page3.xml']:
            root=self.xml('app/src/main/res/layout/'+path)
            for row in root.iter():
                if row.get(A+'id','').startswith('@+id/welcome_item_'):
                    self.assertEqual('false',row.get(A+'clickable'));self.assertNotIn('selectableItemBackground',row.get(A+'background',''))
                    self.assertFalse(any(c.get(A+'src')=='@drawable/ic_ui2_chevron' for c in row.iter()))
        root=self.xml('app/src/main/res/layout/fragment_launch.xml');dot=next(e for e in root.iter() if e.get(A+'id')=='@+id/launch_run_dot');self.assertEqual('no',dot.get(A+'importantForAccessibility'))
    def test_intro_labels_have_both_languages_and_no_inline_english_captions(self):
        for language in ['values','values-en']:
            root=self.xml('app/src/main/res/'+language+'/audit154_ui.xml');self.assertEqual(4,len(root.findall('string')))
        for path in ['welcome_page1.xml','welcome_page2.xml','welcome_page3.xml','fragment_device_grants.xml']:
            for node in self.xml('app/src/main/res/layout/'+path).iter():
                self.assertNotIn(node.get(A+'text'),['DEEPSEEK HARNESS · ANDROID','01 / BUILT FOR YOUR FLOW','02 / YOUR DATA, YOUR CHOICE','Computer Use'])


class InputLabelContractTest(unittest.TestCase):
    def layout(self, body):
        return ET.fromstring('<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">'
                             + body + '</LinearLayout>')

    def test_one_real_input_with_a_label_is_accessible_without_a_second_dummy_label(self):
        root = self.layout('<TextView android:text="Port" android:labelFor="@id/port"/>'
                           '<EditText android:id="@+id/port"/>')
        self.assertEqual({'@id/port'}, validate_input_labels(root))

    def test_every_added_editable_input_needs_its_own_label(self):
        root = self.layout('<TextView android:text="Port" android:labelFor="@id/port"/>'
                           '<EditText android:id="@+id/port"/>'
                           '<androidx.appcompat.widget.AppCompatEditText android:id="@+id/unlabeled"/>')
        with self.assertRaisesRegex(ValueError, 'missing explicit labels: @id/unlabeled'):
            validate_input_labels(root)

    def test_existing_non_input_target_is_not_a_valid_input_label(self):
        root = self.layout('<TextView android:text="Port" android:labelFor="@id/port"/>'
                           '<TextView android:id="@+id/port"/>')
        with self.assertRaisesRegex(ValueError, 'not an editable field: @id/port'):
            validate_input_labels(root)

    def test_dangling_label_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'not an editable field: @id/missing'):
            validate_input_labels(self.layout('<TextView android:text="Port" android:labelFor="@id/missing"/>'))

    def test_empty_or_hidden_label_is_rejected(self):
        for attributes, reason in [('', 'empty'), ('android:text="@null"', 'empty'),
                                   ('android:text="Port" android:visibility="gone"', 'hidden'),
                                   ('android:text="Port" android:visibility="invisible"', 'hidden')]:
            root = self.layout('<TextView android:labelFor="@id/port" ' + attributes + '/>'
                               '<EditText android:id="@+id/port"/>')
            with self.subTest(attributes=attributes), self.assertRaisesRegex(ValueError, 'Input label is ' + reason):
                validate_input_labels(root)

    def test_input_without_id_or_with_duplicate_id_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'no resource id'):
            validate_input_labels(self.layout('<EditText/>'))
        with self.assertRaisesRegex(ValueError, 'Duplicate editable input id: @id/port'):
            validate_input_labels(self.layout('<EditText android:id="@+id/port"/><EditText android:id="@+id/port"/>'))

if __name__=='__main__':unittest.main()
