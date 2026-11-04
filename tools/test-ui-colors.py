#!/usr/bin/env python3
"""从真实日夜色板核对正文、弱提示、状态和按钮各状态的 WCAG 对比度。"""
from pathlib import Path
import re
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1] / 'app/src/main/res'
def rgba(value):
    if not isinstance(value, str) or not re.fullmatch(r'#[0-9a-fA-F]{3,4}|#[0-9a-fA-F]{6}|#[0-9a-fA-F]{8}', value):
        raise ValueError('Invalid Android color literal: %r' % value)
    raw = value[1:]
    if len(raw) in (3, 4):
        raw = ''.join(channel * 2 for channel in raw)
    if len(raw) == 6:
        raw = 'ff' + raw
    return [int(raw[i:i + 2], 16) for i in range(0, 8, 2)]


def resolve_colors(definitions, names=None):
    """解析最终配置的标量颜色；缺失、循环和非法值必须明确失败。"""
    resolved = {}

    def resolve(name, chain=()):
        path = chain + (name,)
        if name in chain:
            raise ValueError('Color alias cycle: ' + ' -> '.join(path))
        if name not in definitions:
            raise ValueError('Missing color resource: ' + ' -> '.join(path))
        if name in resolved:
            return resolved[name]
        value = definitions[name].strip()
        if re.fullmatch(r'@color/[a-zA-Z0-9_]+', value):
            color = resolve(value[len('@color/'):], path)
        else:
            try:
                color = rgba(value)
            except ValueError as error:
                raise ValueError(' -> '.join(path) + ': ' + str(error)) from error
        resolved[name] = color
        return color

    for name in definitions if names is None else names:
        resolve(name)
    return resolved


def load_palette(resource_root, mode, names=None):
    """与日夜资源覆盖一致：默认值先加载，再覆盖夜间同名值，最后解析别名。"""
    if mode not in ('values', 'values-night'):
        raise ValueError('Unsupported palette configuration: ' + mode)
    definitions = {}
    for directory in (['values'] if mode == 'values' else ['values', 'values-night']):
        local = {}
        for path in sorted((resource_root / directory).glob('*.xml')):
            for element in ET.parse(path).getroot():
                if element.tag != 'color' and not (element.tag == 'item' and element.get('type') == 'color'):
                    continue
                name = element.get('name')
                if not name:
                    raise ValueError('Unnamed color resource in ' + str(path))
                if name in local:
                    raise ValueError('Duplicate color resource %s in %s' % (name, directory))
                local[name] = ''.join(element.itertext()).strip()
        definitions.update(local)
    return resolve_colors(definitions, names)
def over(top, base):
    a,*rgb=top
    return [255]+[(rgb[i]*a+base[i+1]*(255-a))/255 for i in range(3)]
def luminance(color):
    rgb=[v/255 for v in color[1:]]
    linear=[v/12.92 if v<=.04045 else ((v+.055)/1.055)**2.4 for v in rgb]
    return sum(v*w for v,w in zip(linear,[.2126,.7152,.0722]))
def contrast(a,b):
    low,high=sorted([luminance(a),luminance(b)])
    return (high+.05)/(low+.05)
class ColorsTest(unittest.TestCase):
    def test_all_text_roles_and_button_states(self):
        minimum=100
        foregrounds = ['text','text_secondary','text_muted','primary','ok','warn','err']
        backgrounds = ['surface','card','raised','disabled']
        # 只检查下方全部正文/按钮角色；其他 XML 颜色选择器不是单一 RGBA。
        roles = foregrounds + backgrounds + ['ripple', 'primary_ripple', 'accent_on', 'error_on']
        for mode in ['values','values-night']:
            colors=load_palette(ROOT, mode, roles)
            pairs=[]
            for fg in foregrounds:
                for bg in backgrounds:
                    pairs.append((fg+'/'+bg,colors[fg],colors[bg]))
            for bg in ['surface','card','raised']:
                selected=over(colors['ripple'],colors[bg])
                pairs += [('selected/'+bg,colors['primary'],selected),('selected-pressed/'+bg,colors['primary'],over(colors['ripple'],selected))]
            for fg,bg in [('accent_on','primary'),('error_on','err')]:
                pairs += [(bg,colors[fg],colors[bg]),(bg+'-pressed',colors[fg],over(colors['primary_ripple'],colors[bg]))]
            for name,fg,bg in pairs:
                value=contrast(fg,bg); minimum=min(minimum,value)
                with self.subTest(mode=mode,pair=name): self.assertGreaterEqual(value,4.5)
        print('Minimum palette contrast: %.3f:1'%minimum)


class ColorResolutionTest(unittest.TestCase):
    def test_aliases_resolve_through_multiple_resources(self):
        self.assertEqual([255, 18, 52, 86], resolve_colors({
            'text': '@color/semantic', 'semantic': '@color/token', 'token': '#123456'
        })['text'])

    def test_missing_alias_reports_the_full_dependency_path(self):
        with self.assertRaisesRegex(ValueError, r'Missing color resource: text -> semantic -> absent'):
            resolve_colors({'text': '@color/semantic', 'semantic': '@color/absent'})

    def test_cycles_report_the_full_dependency_path(self):
        with self.assertRaisesRegex(ValueError, r'Color alias cycle: text -> token -> text'):
            resolve_colors({'text': '@color/token', 'token': '@color/text'})
        with self.assertRaisesRegex(ValueError, r'Color alias cycle: text -> text'):
            resolve_colors({'text': '@color/text'})

    def test_only_requested_scalar_roles_are_loaded_but_their_dependencies_remain_required(self):
        definitions = {'text': '@color/token', 'token': '#123456', 'unrelated_selector': '@color/button_states'}
        self.assertEqual([255, 18, 52, 86], resolve_colors(definitions, ['text'])['text'])
        with self.assertRaisesRegex(ValueError, 'Missing color resource: unrelated_selector -> button_states'):
            resolve_colors(definitions, ['unrelated_selector'])

    def test_android_hex_forms_preserve_alpha_and_channels(self):
        for value, expected in [('#123', [255, 17, 34, 51]), ('#8123', [136, 17, 34, 51]),
                                ('#123456', [255, 18, 52, 86]), ('#80123456', [128, 18, 52, 86])]:
            with self.subTest(value=value):
                self.assertEqual(expected, rgba(value))

    def test_invalid_literals_and_unsupported_references_do_not_silently_pass(self):
        for value in ('123456', '#12', '#12345', '#GGGGGG', '@color/', '?attr/textColor', '@android:color/white'):
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, 'text: Invalid Android color literal'):
                resolve_colors({'text': value})

    def test_night_overlay_resolves_inherited_aliases_against_overridden_tokens(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'values').mkdir()
            (root / 'values-night').mkdir()
            (root / 'values/colors.xml').write_text('<resources><color name="text">@color/token</color>'
                '<color name="fallback">#102030</color></resources>')
            (root / 'values/tokens.xml').write_text('<resources><color name="token">#112233</color></resources>')
            (root / 'values-night/tokens.xml').write_text('<resources><item type="color" name="token">#AABBCC</item></resources>')
            self.assertEqual([255, 17, 34, 51], load_palette(root, 'values')['text'])
            night = load_palette(root, 'values-night')
            self.assertEqual([255, 170, 187, 204], night['text'])
            self.assertEqual([255, 16, 32, 48], night['fallback'])

    def test_same_configuration_duplicate_colors_fail_instead_of_file_order_winning(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'values').mkdir()
            for filename in ('colors.xml', 'tokens.xml'):
                (root / 'values' / filename).write_text('<resources><color name="text">#FFFFFF</color></resources>')
            with self.assertRaisesRegex(ValueError, 'Duplicate color resource text in values'):
                load_palette(root, 'values')

if __name__=='__main__': unittest.main()
