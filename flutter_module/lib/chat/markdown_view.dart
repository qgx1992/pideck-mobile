/// markdown 的渲染层：把 [parseMarkdown] 的块画出来。
///
/// 与 Web 侧 `app/src/ui/markdown.ts` 的差别：
///   - 不做语法高亮（那边有 451 行的 `highlight.ts`，这里只给等宽字体 + 背景）；
///   - 解析结果走 `parseMarkdownCached()` 的缓存 —— 组件是无状态的，
///     快照一推就重建，不缓存的话每条可见消息都会重解析一遍。
library;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../app_theme.dart';
import '../host_channel.dart';
import 'markdown.dart';

class MarkdownView extends StatelessWidget {
  const MarkdownView({
    super.key,
    required this.source,
    this.baseStyle,
    this.selectable = false,
    this.compact = false,
  });

  final String source;
  final TextStyle? baseStyle;

  /// 正文是否可选。
  ///
  /// 默认 **false**：`SelectableText` 自己会吃掉长按手势（它要开始选词），
  /// 外层 `GestureDetector.onLongPress` 就永远收不到 —— 症状是「长按助手消息
  /// 弹不出菜单」，而菜单里本来就有「复制」。代码块仍然可选（`_CodeBlock`），
  /// 那里需要复制片段；要整条复制走长按菜单。
  final bool selectable;

  /// 气泡里（用户消息）用更小的行距，别把屏幕占满。
  final bool compact;

  @override
  Widget build(BuildContext context) {
    final PiColors c = PiColors.of(context);
    final TextStyle base = (baseStyle ?? TextStyle(color: c.text, fontSize: 15)).copyWith(
      height: compact ? 1.35 : 1.5,
    );
    final MdInlineStyle style = MdInlineStyle(
      base: base,
      strong: base.copyWith(fontWeight: FontWeight.w600),
      emphasis: base.copyWith(fontStyle: FontStyle.italic),
      code: base.copyWith(
        fontFamily: 'monospace',
        fontSize: base.fontSize! - 1,
        backgroundColor: c.surfaceAlt,
      ),
      codeBackground: c.surfaceAlt,
      link: base.copyWith(color: c.accent, decoration: TextDecoration.underline),
    );

    final List<MdBlock> blocks = parseMarkdownCached(source);
    if (blocks.isEmpty) {
      return Text(source, style: base);
    }

    final List<Widget> children = <Widget>[];
    for (final MdBlock block in blocks) {
      children.add(_blockWidget(context, block, style, base, c));
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: children,
    );
  }

  Widget _blockWidget(
    BuildContext context,
    MdBlock block,
    MdInlineStyle style,
    TextStyle base,
    PiColors c,
  ) {
    switch (block.kind) {
      case MdBlockKind.heading:
        final double size = switch (block.level) {
          1 => 20,
          2 => 18,
          3 => 16.5,
          _ => 15.5,
        };
        return Padding(
          padding: const EdgeInsets.only(top: 10, bottom: 4),
          child: _text(
            parseInline(
              block.text,
              MdInlineStyle(
                base: base.copyWith(fontSize: size, fontWeight: FontWeight.w600, color: c.text),
                strong: base.copyWith(fontSize: size, fontWeight: FontWeight.w700, color: c.text),
                emphasis: base.copyWith(fontSize: size, fontStyle: FontStyle.italic, color: c.text),
                code: style.code.copyWith(fontSize: size - 1),
                codeBackground: style.codeBackground,
                link: style.link.copyWith(fontSize: size),
              ),
            ),
          ),
        );
      case MdBlockKind.code:
        return _CodeBlock(lang: block.lang, code: block.text, colors: c);
      case MdBlockKind.bullet:
        return Padding(
          padding: const EdgeInsets.only(bottom: 2),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: block.items
                .map((String item) => Padding(
                      padding: const EdgeInsets.only(left: 2, top: 2),
                      child: Row(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: <Widget>[
                          Padding(
                            padding: const EdgeInsets.only(top: 1, right: 6),
                            child: Text('•', style: base.copyWith(color: c.textMuted)),
                          ),
                          Expanded(child: _text(parseInline(item, style))),
                        ],
                      ),
                    ))
                .toList(growable: false),
          ),
        );
      case MdBlockKind.ordered:
        return Padding(
          padding: const EdgeInsets.only(bottom: 2),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              for (int i = 0; i < block.items.length; i += 1)
                Padding(
                  padding: const EdgeInsets.only(left: 2, top: 2),
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: <Widget>[
                      Padding(
                        padding: const EdgeInsets.only(top: 1, right: 6),
                        child: Text('${i + 1}.', style: base.copyWith(color: c.textMuted)),
                      ),
                      Expanded(child: _text(parseInline(block.items[i], style))),
                    ],
                  ),
                ),
            ],
          ),
        );
      case MdBlockKind.quote:
        return Container(
          margin: const EdgeInsets.symmetric(vertical: 4),
          padding: const EdgeInsets.only(left: 10),
          decoration: BoxDecoration(
            border: Border(left: BorderSide(color: c.divider, width: 2)),
          ),
          child: _text(
            parseInline(
              block.text,
              MdInlineStyle(
                base: base.copyWith(color: c.textSecondary),
                strong: style.strong.copyWith(color: c.textSecondary),
                emphasis: style.emphasis.copyWith(color: c.textSecondary),
                code: style.code,
                codeBackground: style.codeBackground,
                link: style.link,
              ),
            ),
          ),
        );
      case MdBlockKind.rule:
        return Padding(
          padding: const EdgeInsets.symmetric(vertical: 8),
          child: Divider(color: c.divider, height: 1),
        );
      case MdBlockKind.paragraph:
        return Padding(
          padding: EdgeInsets.only(bottom: compact ? 4 : 6),
          child: _text(parseInline(block.text, style)),
        );
    }
  }

  Widget _text(List<InlineSpan> spans) {
    if (selectable) {
      return SelectableText.rich(TextSpan(children: spans));
    }
    return Text.rich(TextSpan(children: spans));
  }
}

class _CodeBlock extends StatelessWidget {
  const _CodeBlock({required this.lang, required this.code, required this.colors});

  final String lang;
  final String code;
  final PiColors colors;

  /// 存到手机「下载/PiDeck」。落盘规则与 WebView 的 FileSaver 插件共用同一份
  /// 原生实现（`SavedFiles`），所以两边保存位置、重名策略完全一致。
  Future<void> _save(BuildContext context) async {
    final ScaffoldMessengerState messenger = ScaffoldMessenger.of(context);
    final String name = 'snippet-${_stamp()}.${_extensionOf(lang)}';
    final String? location = await saveTextFile(name: name, text: code);
    messenger.showSnackBar(
      SnackBar(
        content: Text(location == null ? '保存失败' : '已保存到 $location'),
        duration: const Duration(seconds: 3),
      ),
    );
  }

  static String _stamp() {
    final DateTime now = DateTime.now();
    String two(int value) => value.toString().padLeft(2, '0');
    return '${now.year}${two(now.month)}${two(now.day)}-${two(now.hour)}${two(now.minute)}${two(now.second)}';
  }

  /// 代码块的语言标记 → 文件扩展名（认不出来就用 .txt，不猜）。
  static String _extensionOf(String lang) {
    switch (lang.toLowerCase()) {
      case 'dart':
        return 'dart';
      case 'ts':
      case 'typescript':
        return 'ts';
      case 'js':
      case 'javascript':
        return 'js';
      case 'json':
        return 'json';
      case 'py':
      case 'python':
        return 'py';
      case 'sh':
      case 'bash':
      case 'shell':
        return 'sh';
      case 'java':
        return 'java';
      case 'kt':
      case 'kotlin':
        return 'kt';
      case 'html':
        return 'html';
      case 'css':
        return 'css';
      case 'sql':
        return 'sql';
      case 'yml':
      case 'yaml':
        return 'yaml';
      default:
        return 'txt';
    }
  }

  @override
  Widget build(BuildContext context) {
    final TextStyle mono = TextStyle(
      fontFamily: 'monospace',
      fontSize: 12.5,
      height: 1.45,
      color: colors.text,
    );
    return Container(
      margin: const EdgeInsets.symmetric(vertical: 6),
      decoration: BoxDecoration(
        color: colors.surfaceAlt,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: colors.divider),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Padding(
            padding: const EdgeInsets.only(left: 10, right: 6, top: 4),
            child: Row(
              children: <Widget>[
                Text(
                  lang.isEmpty ? 'code' : lang,
                  style: TextStyle(fontSize: 11, color: colors.textMuted),
                ),
                const Spacer(),
                IconButton(
                  visualDensity: VisualDensity.compact,
                  iconSize: 16,
                  tooltip: '复制',
                  icon: Icon(Icons.copy_rounded, color: colors.textMuted),
                  onPressed: () {
                    // 复制走宿主剪贴板之外的本地实现：这里不引第三方包，
                    // 直接用 Flutter 自带的 Clipboard。
                    Clipboard.setData(ClipboardData(text: code));
                  },
                ),
                IconButton(
                  visualDensity: VisualDensity.compact,
                  iconSize: 16,
                  tooltip: '保存到手机',
                  icon: Icon(Icons.save_alt_rounded, color: colors.textMuted),
                  onPressed: () => _save(context),
                ),
              ],
            ),
          ),
          SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.only(left: 10, right: 10, bottom: 8),
            child: SelectableText(code, style: mono),
          ),
        ],
      ),
    );
  }
}
