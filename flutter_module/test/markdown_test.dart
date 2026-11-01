import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pideck_flutter/chat/markdown.dart';

MdInlineStyle _style() {
  const TextStyle base = TextStyle(fontSize: 14);
  return MdInlineStyle(
    base: base,
    strong: base.copyWith(fontWeight: FontWeight.w600),
    emphasis: base.copyWith(fontStyle: FontStyle.italic),
    code: base.copyWith(fontFamily: 'monospace'),
    codeBackground: const Color(0xFF000000),
    link: base.copyWith(decoration: TextDecoration.underline),
  );
}

String _flatten(List<InlineSpan> spans) =>
    spans.map((InlineSpan span) => span is TextSpan ? (span.text ?? '') : '').join();

void main() {
  group('parseMarkdown 块级', () {
    test('段落、标题、围栏代码、列表、引用、分隔线', () {
      final List<MdBlock> blocks = parseMarkdown(
        '# 标题\n\n'
        '第一段\n第二行\n\n'
        '```dart\nvoid main() {\n  print(1);\n}\n```\n\n'
        '- 甲\n- 乙\n\n'
        '1. 一\n2. 二\n\n'
        '> 引用\n> 第二行\n\n'
        '---\n',
      );

      expect(blocks.map((MdBlock block) => block.kind).toList(), <MdBlockKind>[
        MdBlockKind.heading,
        MdBlockKind.paragraph,
        MdBlockKind.code,
        MdBlockKind.bullet,
        MdBlockKind.ordered,
        MdBlockKind.quote,
        MdBlockKind.rule,
      ]);
      expect(blocks[0].level, 1);
      expect(blocks[0].text, '标题');
      expect(blocks[1].text, '第一段\n第二行');
      expect(blocks[2].lang, 'dart');
      expect(blocks[2].text, 'void main() {\n  print(1);\n}');
      expect(blocks[3].items, <String>['甲', '乙']);
      expect(blocks[4].items, <String>['一', '二']);
      expect(blocks[5].text, '引用\n第二行');
    });

    test('代码块里的 markdown 不当结构', () {
      final List<MdBlock> blocks = parseMarkdown('```\n# 不是标题\n- 不是列表\n```\n');
      expect(blocks, hasLength(1));
      expect(blocks.single.kind, MdBlockKind.code);
      expect(blocks.single.text, '# 不是标题\n- 不是列表');
    });

    test('未闭合的围栏吃到文末，不丢内容', () {
      final List<MdBlock> blocks = parseMarkdown('```sh\nls\npwd\n');
      expect(blocks.single.kind, MdBlockKind.code);
      expect(blocks.single.text, 'ls\npwd');
    });

    test('列表续行并回上一项', () {
      final List<MdBlock> blocks = parseMarkdown('- 第一项\n  接着写\n- 第二项\n');
      expect(blocks.single.items, <String>['第一项 接着写', '第二项']);
    });

    test('空文本得到空块列表', () {
      expect(parseMarkdown(''), isEmpty);
    });
  });

  group('parseInline 行内', () {
    test('纯文本原样', () {
      expect(_flatten(parseInline('你好世界', _style())), '你好世界');
    });

    test('行内代码 / 粗体 / 斜体 / 删除线 / 链接', () {
      final List<InlineSpan> spans = parseInline(
        'a `code` b **bold** c *it* d ~~del~~ e [link](https://x.dev) f',
        _style(),
      );
      expect(_flatten(spans), 'a code b bold c it d del e link f');
    });

    test('未闭合的标记当普通文本', () {
      expect(_flatten(parseInline('**没闭合', _style())), '**没闭合');
      expect(_flatten(parseInline('`没闭合', _style())), '`没闭合');
    });

    test('样式落到了正确的片段上', () {
      final List<InlineSpan> spans = parseInline('x **b** y', _style());
      final TextSpan bold = spans.whereType<TextSpan>().firstWhere((TextSpan s) => s.text == 'b');
      expect(bold.style!.fontWeight, FontWeight.w600);
    });

    test('行内代码带等宽字体', () {
      final List<InlineSpan> spans = parseInline('`ls`', _style());
      final TextSpan code = spans.whereType<TextSpan>().firstWhere((TextSpan s) => s.text == 'ls');
      expect(code.style!.fontFamily, 'monospace');
    });
  });
}
