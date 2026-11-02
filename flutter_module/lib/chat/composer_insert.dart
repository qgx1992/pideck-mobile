import 'package:flutter/services.dart';

import 'chat_models.dart';

/// 输入框插入与触发字符判定的纯函数。
///
/// 抽出来是因为这三条规则都有「看起来对、实际会错一个字」的坑：
///
///   * 打 `/` 叫出斜线面板后插入 `/goal ` —— 若不做整段替换就成了 `//goal `；
///   * 打 `@` 叫出文件面板后插入 `@src/a.ts ` —— 同理会变成 `@@src/a.ts `；
///   * 粘贴一段含 `@` 的路径不该弹面板，所以触发只认「刚敲进去的那一个字符」。
///
/// 放在这里就能被单元测试直接盯住，不必把整个 `ChatPage` 拖进测试。

/// 往输入框光标处插一段文本。
///
/// [replaceAll] 为真时整段替换（触发字符还在输入框里的情况）。
TextEditingValue insertIntoComposer(
  TextEditingValue value,
  String text, {
  bool replaceAll = false,
}) {
  final TextSelection selection = value.selection;
  // 光标可能是 -1（控制器刚清空、还没获得焦点）：这时候追加到末尾，
  // 不要因为「起点 -1」就把整段文字当成选区替换掉 —— 那会静默删掉用户输入。
  final bool invalid = selection.start < 0 || selection.end < 0;
  final int start = replaceAll ? 0 : (invalid ? value.text.length : selection.start);
  final int end = replaceAll ? value.text.length : (invalid ? value.text.length : selection.end);
  return TextEditingValue(
    text: value.text.replaceRange(start, end, text),
    selection: TextSelection.collapsed(offset: start + text.length),
  );
}

/// 刚敲进去的那一个字符是不是面板触发字符。
///
/// 返回 `null` 表示不触发。判据：长度只多了 1、光标是折叠的、敲的正好是
/// `/` 或 `@`；`/` 还要求输入框里除了它没有别的东西（`a/b` 不该弹）。
String? composerTrigger({
  required String previous,
  required String next,
  required TextSelection selection,
}) {
  if (next.length != previous.length + 1) return null;
  if (!selection.isCollapsed) return null;
  final int caret = selection.baseOffset;
  if (caret < 1 || caret > next.length) return null;
  final String typed = next.substring(caret - 1, caret);
  if (typed == '@') return '@';
  if (typed == '/' && next.trim() == '/') return '/';
  return null;
}

/// 点面板条目要做的事：要么往输入框插文本，要么回 WebView 执行命令。
enum PickerTapKind { insert, action }

class PickerTap {
  const PickerTap.insert(this.text) : kind = PickerTapKind.insert, itemId = '';

  const PickerTap.action(this.itemId) : kind = PickerTapKind.action, text = '';

  final PickerTapKind kind;

  /// `insert` 时要写进输入框的文本
  final String text;

  /// `action` 时要回给 WebView 的条目 id
  final String itemId;
}

/// 面板条目该走哪条路。
///
/// 有 `insert` 就是纯文本动作；`dir:` 开头的目录条目虽然也算动作，但它是
/// 「原地往下钻」，同样要回 WebView（由它去读下一层目录）。
PickerTap resolvePickerTap(ChatPickerItem item) {
  final String? insert = item.insert;
  if (insert != null) return PickerTap.insert(insert);
  return PickerTap.action(item.id);
}
