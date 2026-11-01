/// 极简 markdown：只覆盖 pi 输出里实际会出现的东西，不引第三方包。
///
/// 为什么手写：`flutter_markdown` 已经停止维护、`gpt_markdown` 之类的包会把整份文本
/// 每帧重新解析一遍，流式场景下比手写的块级缓存更慢；而聊天里真正需要的只有
/// 段落 / 标题 / 围栏代码 / 列表 / 引用 / 分隔线 / 行内代码 / 粗斜体 / 链接。
///
/// 刻意**不做**：表格、脚注、HTML 内联、嵌套列表的多级缩进（降级成同级，够用）。
library;

import 'package:flutter/material.dart';

enum MdBlockKind { paragraph, heading, code, bullet, ordered, quote, rule }

class MdBlock {
  const MdBlock(
    this.kind, {
    this.text = '',
    this.level = 0,
    this.lang = '',
    this.items = const <String>[],
  });

  final MdBlockKind kind;
  final String text;
  final int level;
  final String lang;
  final List<String> items;

  @override
  String toString() => 'MdBlock($kind, level=$level, text=${text.length > 40 ? '${text.substring(0, 40)}…' : text})';
}

final RegExp _headingPattern = RegExp(r'^(#{1,6})\s+(.*)$');
final RegExp _bulletPattern = RegExp(r'^\s*([-*+])\s+(.*)$');
final RegExp _orderedPattern = RegExp(r'^\s*(\d+)[.)]\s+(.*)$');
final RegExp _rulePattern = RegExp(r'^\s*([-*_])\s*(\1\s*){2,}$');

/// 把整份 markdown 切成块。围栏代码里的内容不会被当成结构（这是最容易写错的地方）。
List<MdBlock> parseMarkdown(String source) {
  final List<MdBlock> blocks = <MdBlock>[];
  final List<String> lines = source.split('\n');
  final StringBuffer paragraph = StringBuffer();
  final List<String> listItems = <String>[];
  MdBlockKind listKind = MdBlockKind.bullet;

  void flushParagraph() {
    if (paragraph.isEmpty) return;
    blocks.add(MdBlock(MdBlockKind.paragraph, text: paragraph.toString().trimRight()));
    paragraph.clear();
  }

  void flushList() {
    if (listItems.isEmpty) return;
    blocks.add(MdBlock(listKind, items: List<String>.of(listItems)));
    listItems.clear();
  }

  void flushAll() {
    flushParagraph();
    flushList();
  }

  int index = 0;
  while (index < lines.length) {
    final String line = lines[index];
    final String trimmed = line.trim();

    // 围栏代码：``` 或 ~~~，允许任意长度（>=3）
    final RegExpMatch? fence = RegExp(r'^\s*(`{3,}|~{3,})\s*([\w+#.-]*)\s*$').firstMatch(line);
    if (fence != null) {
      flushAll();
      final String marker = fence.group(1)!;
      final String lang = (fence.group(2) ?? '').trim();
      final StringBuffer code = StringBuffer();
      bool closed = false;
      index += 1;
      while (index < lines.length) {
        final String current = lines[index];
        if (current.trim().startsWith(marker)) {
          closed = true;
          index += 1;
          break;
        }
        if (code.isNotEmpty) code.write('\n');
        code.write(current);
        index += 1;
      }
      // 没闭合时最后一个空行会带出一个多余换行（`split('\n')` 的尾巴）
      final String body = closed ? code.toString() : code.toString().trimRight();
      blocks.add(MdBlock(MdBlockKind.code, text: body, lang: lang));
      continue;
    }

    if (trimmed.isEmpty) {
      flushAll();
      index += 1;
      continue;
    }

    final RegExpMatch? heading = _headingPattern.firstMatch(trimmed);
    if (heading != null) {
      flushAll();
      blocks.add(MdBlock(
        MdBlockKind.heading,
        level: heading.group(1)!.length,
        text: heading.group(2)!.trim(),
      ));
      index += 1;
      continue;
    }

    if (_rulePattern.hasMatch(trimmed)) {
      flushAll();
      blocks.add(const MdBlock(MdBlockKind.rule));
      index += 1;
      continue;
    }

    if (trimmed.startsWith('>')) {
      flushAll();
      final StringBuffer quote = StringBuffer();
      while (index < lines.length && lines[index].trim().startsWith('>')) {
        if (quote.isNotEmpty) quote.write('\n');
        quote.write(lines[index].trim().replaceFirst(RegExp(r'^>\s?'), ''));
        index += 1;
      }
      blocks.add(MdBlock(MdBlockKind.quote, text: quote.toString()));
      continue;
    }

    final RegExpMatch? bullet = _bulletPattern.firstMatch(line);
    if (bullet != null) {
      flushParagraph();
      if (listItems.isNotEmpty && listKind != MdBlockKind.bullet) flushList();
      listKind = MdBlockKind.bullet;
      listItems.add(bullet.group(2)!.trim());
      index += 1;
      continue;
    }

    final RegExpMatch? ordered = _orderedPattern.firstMatch(line);
    if (ordered != null) {
      flushParagraph();
      if (listItems.isNotEmpty && listKind != MdBlockKind.ordered) flushList();
      listKind = MdBlockKind.ordered;
      listItems.add(ordered.group(2)!.trim());
      index += 1;
      continue;
    }

    // 列表项的续行（缩进）并回上一项，别拆成新段落
    if (listItems.isNotEmpty && line.startsWith(RegExp(r'\s')) && !trimmed.startsWith('-')) {
      listItems[listItems.length - 1] = '${listItems.last} $trimmed';
      index += 1;
      continue;
    }

    flushList();
    if (paragraph.isNotEmpty) paragraph.write('\n');
    paragraph.write(trimmed);
    index += 1;
  }

  flushAll();
  return blocks;
}

/// 行内样式的一组颜色/字号，由调用方从 `PiColors` 里取，避免这里依赖主题实现。
class MdInlineStyle {
  const MdInlineStyle({
    required this.base,
    required this.strong,
    required this.emphasis,
    required this.code,
    required this.codeBackground,
    required this.link,
  });

  final TextStyle base;
  final TextStyle strong;
  final TextStyle emphasis;
  final TextStyle code;
  final Color codeBackground;
  final TextStyle link;
}

/// 行内解析：`code`、**strong**、*em*、~~strike~~、[text](url)。
///
/// 不支持嵌套（`**a *b* c**` 里的斜体不会被识别），pi 的实际输出里极少出现，
/// 换来的是一个不需要回溯的线性扫描。
List<InlineSpan> parseInline(String source, MdInlineStyle style) {
  final List<InlineSpan> spans = <InlineSpan>[];
  final StringBuffer plain = StringBuffer();

  void flushPlain() {
    if (plain.isEmpty) return;
    spans.add(TextSpan(text: plain.toString(), style: style.base));
    plain.clear();
  }

  int index = 0;
  while (index < source.length) {
    final String rest = source.substring(index);

    if (rest.startsWith('`')) {
      final int end = source.indexOf('`', index + 1);
      if (end > index + 1) {
        flushPlain();
        spans.add(TextSpan(
          text: source.substring(index + 1, end),
          style: style.code,
        ));
        index = end + 1;
        continue;
      }
    }

    if (rest.startsWith('**') || rest.startsWith('__')) {
      final String marker = rest.substring(0, 2);
      final int end = source.indexOf(marker, index + 2);
      if (end > index + 2) {
        flushPlain();
        spans.add(TextSpan(text: source.substring(index + 2, end), style: style.strong));
        index = end + 2;
        continue;
      }
    }

    if (rest.startsWith('~~')) {
      final int end = source.indexOf('~~', index + 2);
      if (end > index + 2) {
        flushPlain();
        spans.add(TextSpan(
          text: source.substring(index + 2, end),
          style: style.base.copyWith(decoration: TextDecoration.lineThrough),
        ));
        index = end + 2;
        continue;
      }
    }

    if (rest.startsWith('*') || rest.startsWith('_')) {
      final String marker = rest.substring(0, 1);
      final int end = source.indexOf(marker, index + 1);
      if (end > index + 1 && !source.substring(index + 1, end).contains('\n')) {
        flushPlain();
        spans.add(TextSpan(text: source.substring(index + 1, end), style: style.emphasis));
        index = end + 1;
        continue;
      }
    }

    if (rest.startsWith('[')) {
      final int labelEnd = source.indexOf('](', index + 1);
      if (labelEnd > index + 1) {
        final int urlEnd = source.indexOf(')', labelEnd + 2);
        if (urlEnd > labelEnd + 2) {
          flushPlain();
          spans.add(TextSpan(
            text: source.substring(index + 1, labelEnd),
            style: style.link,
          ));
          index = urlEnd + 1;
          continue;
        }
      }
    }

    plain.write(source[index]);
    index += 1;
  }

  flushPlain();
  return spans;
}
