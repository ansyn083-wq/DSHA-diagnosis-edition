#!/usr/bin/env python3
"""Lexical inventory boundaries, growth rejection and comment/source preservation."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location('check_ui_i18n', Path(__file__).with_name('check-ui-i18n.py'))
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)
generator_spec = importlib.util.spec_from_file_location('prepare_ui_languages', Path(__file__).with_name('prepare-ui-languages.py'))
generator = importlib.util.module_from_spec(generator_spec)
generator_spec.loader.exec_module(generator)


class UiI18nInventoryTest(unittest.TestCase):
    def test_native_home_layout_strings_have_complete_english_resources(self):
        root = Path(__file__).resolve().parents[1]
        android = '{http://schemas.android.com/apk/res/android}'
        translated = {}
        for path in (root / 'app/src/main/res/values-en').glob('*.xml'):
            translated.update({node.get('name'): ''.join(node.itertext())
                               for node in ET.parse(path).getroot().findall('string')})
        defaults = {}
        for path in (root / 'app/src/main/res/values').glob('*.xml'):
            defaults.update({node.get('name'): node
                             for node in ET.parse(path).getroot().findall('string')})
        for name in ('activity_diagnostic_home.xml', 'view_native_chat_sidebar.xml'):
            path = root / 'app/src/main/res/layout' / name
            for node in ET.parse(path).getroot().iter():
                for attribute in ('text', 'hint', 'contentDescription'):
                    value = node.get(android + attribute)
                    if not value:
                        continue
                    with self.subTest(layout=name, view=node.get(android + 'id'), attribute=attribute):
                        self.assertTrue(value.startswith('@string/'), value)
                        resource = value.removeprefix('@string/')
                        self.assertIn(resource, defaults)
                        if defaults[resource].get('translatable') != 'false':
                            self.assertIn(resource, translated)
                            self.assertTrue(translated[resource])
                            self.assertFalse(checker.CJK.search(translated[resource]))

    def test_native_chat_accessibility_and_templates_have_no_literal_or_fragment_debt(self):
        root = Path(__file__).resolve().parents[1]
        for name in ('GptLoadingIndicator.java', 'NativeChatPanel.java'):
            with self.subTest(source=name):
                source = (root / 'app/src/main/java/com/deepseekharness/app/ui' / name).read_text(encoding='utf-8')
                rows, _ = checker.java_findings(source)
                self.assertEqual([], rows)

    def test_native_chat_complete_templates_preserve_raw_arguments_in_both_languages(self):
        root = Path(__file__).resolve().parents[1]
        catalog = {item['zh']: item for item in json.loads(
            (root / 'tools/i18n/messages.json').read_text(encoding='utf-8'))}
        for chinese, english in (
            ('%d 秒', '%d s'),
            ('选择模型 · %s', 'Choose model · %s'),
            ('已加载记录的 API 用量 · %d in / %d out', 'API usage in loaded history · %d in / %d out'),
            ('本轮用时 · %s', 'Turn duration · %s'),
        ):
            with self.subTest(template=chinese):
                self.assertIn(chinese, catalog)
                self.assertEqual(english, catalog[chinese]['en'])
                # Compact duration is formatted directly, not reverse-translated as
                # cached state: the existing generic "%s 秒" owns that grammar.
                self.assertEqual(chinese != '%d 秒', bool(catalog[chinese].get('uiFormat')))
                self.assertEqual(generator.format_signature(chinese), generator.format_signature(english))

    def test_catalog_format_grammar_rejects_unsupported_and_preserves_argument_roles(self):
        self.assertEqual(['s','d'], generator.format_signature('%s %d%%'))
        for value in ['%', '%f', '%1$s', '%n']:
            with self.assertRaisesRegex(ValueError, 'UI_FORMAT_UNSUPPORTED'):
                generator.format_signature(value)
        self.assertNotEqual(generator.format_signature('%s %d'), generator.format_signature('%d %s'))

    def test_direct_display_fragment_and_escaped_chinese_are_visible(self):
        rows, dynamic = checker.java_findings('button.setText("中文"); title.setText("\\u4e2d文"); '
            'Toast.makeText(getContext(), "提示", 0); UiText.text("共 ") + count; UiText.text(output);')
        self.assertEqual(3, sum(row[0] == 'java_direct_display_literal' for row in rows))
        self.assertEqual(1, sum(row[0] == 'translated_fragment_concat' for row in rows))
        self.assertEqual(1, dynamic)

    def test_comments_logs_protocol_and_user_text_are_not_display_literal_defects(self):
        source = '// setText("注释");\n/* UiText.text("片段") + n; */\n'
        source += 'log("中文日志"); setText(UiText.format("完整：%s", raw)); '
        source += 'setText(UiText.choose("中文", "English")); setText(webPrompt);'
        self.assertEqual(([], 0), checker.java_findings(source))

    def test_choose_fragments_on_both_sides_include_qualified_calls(self):
        source = 'UiText.choose("共 ", "Total: ") + raw; '
        source += 'raw + UiText.choose(" 项", " items"); '
        source += 'raw + com.deepseekharness.app.util.UiText.choose(" 后缀", " suffix"); '
        source += 'UiText.choose("\\u4e2d文：", "Chinese: ") + raw; '
        source += 'setText(UiText.choose("完整中文", "Whole English")); '
        source += 'setText(UiText.format("参数保持：%s", raw)); '
        source += '// UiText.choose("注释", "Comment") + raw;\n'
        rows, dynamic = checker.java_findings(source)
        self.assertEqual(4, len(rows))
        self.assertTrue(all(row[0] == 'translated_choose_fragment_concat' for row in rows))
        self.assertEqual(2, sum(row[1].startswith('+UiText.choose(') for row in rows))
        self.assertEqual(0, dynamic)

    def test_choose_arguments_keep_raw_values_but_expose_label_fragments(self):
        source = 'UiText.choose("共 " + count + " 项", "Total " + count + " items"); '
        source += 'UiText.choose("完整中文", "Whole English") + raw; '
        source += 'setText(UiText.format("总数：%s", compute(a + b)));'
        rows, _ = checker.java_findings(source)
        self.assertEqual(2, len(rows))
        self.assertEqual('translated_choose_argument_concat', rows[0][0])
        self.assertEqual('translated_choose_fragment_concat', rows[1][0])

    def test_complete_paragraph_nonfindings_require_exact_source_and_occurrence(self):
        row = {'kind': 'translated_choose_fragment_concat', 'file': 'app/src/main/A.java',
               'signature': 'UiText.choose("完整句。","Whole sentence.")+', 'sourceSha256': 'a'}
        declaration = dict(row, classification='independent_complete_paragraph_join',
                           reason='Two independently translated paragraphs, separated by a newline.',
                           occurrences=1)
        remaining, accepted, stale = checker.partition_nonfindings([row], [declaration])
        self.assertEqual([], remaining)
        self.assertEqual(1, len(accepted))
        self.assertEqual([], stale)
        self.assertEqual(1, len(checker.partition_nonfindings([row, row], [declaration])[0]))
        changed = dict(row, sourceSha256='b')
        remaining, accepted, stale = checker.partition_nonfindings([changed], [declaration])
        self.assertEqual([changed], remaining)
        self.assertEqual([], accepted)
        self.assertEqual(1, len(stale))

    def test_nonfinding_cannot_be_an_issue_id_or_reasonless_waiver(self):
        with self.assertRaisesRegex(ValueError, 'UI_I18N_NONFINDING_SCHEMA'):
            checker.partition_nonfindings([], [{'classification': 'all_choose_is_safe', 'occurrences': 1}])

    def test_inventory_ignores_debug_history_generated_and_resource_references(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            for source_set in ['main', 'debug']:
                path = root / 'app/src' / source_set / 'res/layout/example.xml'
                path.parent.mkdir(parents=True)
                path.write_text('<TextView android:text="Inline" android:hint="@string/hint" />', encoding='utf-8')
            rows, count = checker.inventory(root)
            self.assertEqual(1, len(rows))
            self.assertEqual('text=Inline', rows[0]['signature'])
            self.assertEqual(0, count)

    def test_removing_one_debt_cannot_hide_a_new_sink_or_extra_occurrence(self):
        row = {'kind': 'xml_literal', 'file': 'app/src/main/res/layout/a.xml', 'signature': 'text=Old'}
        replacement = dict(row, signature='text=New')
        new, removed = checker.compare([replacement], [row])
        self.assertEqual(1, sum(new.values()))
        self.assertEqual(1, sum(removed.values()))
        self.assertEqual(1, sum(checker.compare([row, row], [row])[0].values()))
        self.assertFalse(checker.compare([], [row])[0])


if __name__ == '__main__':
    unittest.main()
