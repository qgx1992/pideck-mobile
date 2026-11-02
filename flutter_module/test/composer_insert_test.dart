import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pideck_flutter/chat/chat_models.dart';
import 'package:pideck_flutter/chat/composer_insert.dart';

TextEditingValue _value(String text, int caret) =>
    TextEditingValue(text: text, selection: TextSelection.collapsed(offset: caret));

void main() {
  group('insertIntoComposer', () {
    test('插到光标处，光标跟在插入内容后面', () {
      final TextEditingValue next = insertIntoComposer(_value('ab', 1), 'XY');
      expect(next.text, 'aXYb');
      expect(next.selection.baseOffset, 3);
    });

    test('有选区时替换选区', () {
      const TextEditingValue value = TextEditingValue(
        text: 'hello world',
        selection: TextSelection(baseOffset: 6, extentOffset: 11),
      );
      final TextEditingValue next = insertIntoComposer(value, 'there ');
      expect(next.text, 'hello there ');
      expect(next.selection.baseOffset, 12);
    });

    test('没有光标（selection 为 -1）时追加到末尾', () {
      final TextEditingValue next = insertIntoComposer(_value('abc', -1), '/goal ');
      expect(next.text, 'abc/goal ');
      expect(next.selection.baseOffset, 9);
    });

    test('replaceAll 整段替换，避免 //goal 与 @@path', () {
      expect(insertIntoComposer(_value('/', 1), '/goal ', replaceAll: true).text, '/goal ');
      expect(insertIntoComposer(_value('@', 1), '@src/a.ts ', replaceAll: true).text, '@src/a.ts ');
    });
  });

  group('composerTrigger', () {
    test('打 @ 触发', () {
      expect(composerTrigger(previous: 'hi ', next: 'hi @', selection: _value('hi @', 4).selection), '@');
    });

    test('/ 只在输入框里没有别的东西时触发', () {
      expect(composerTrigger(previous: '', next: '/', selection: _value('/', 1).selection), '/');
      expect(composerTrigger(previous: 'a', next: 'a/', selection: _value('a/', 2).selection), isNull);
      expect(composerTrigger(previous: '/x', next: '/x/', selection: _value('/x/', 3).selection), isNull);
    });

    test('一次进来多个字符（粘贴）不触发', () {
      expect(composerTrigger(previous: '', next: '@src/', selection: _value('@src/', 5).selection), isNull);
    });

    test('不是触发字符就不动', () {
      expect(composerTrigger(previous: 'a', next: 'ab', selection: _value('ab', 2).selection), isNull);
    });

    test('光标是区间选区时不触发', () {
      const TextSelection range = TextSelection(baseOffset: 0, extentOffset: 2);
      expect(composerTrigger(previous: 'a', next: 'a@', selection: range), isNull);
    });

    test('光标位置越界不炸', () {
      expect(composerTrigger(previous: '', next: '@', selection: _value('@', 0).selection), isNull);
      expect(composerTrigger(previous: '', next: '@', selection: _value('@', 9).selection), isNull);
    });
  });

  group('resolvePickerTap', () {
    test('带 insert 的是纯文本动作', () {
      const ChatPickerItem item = ChatPickerItem(id: 'goal', label: '/goal', insert: '/goal ');
      final PickerTap tap = resolvePickerTap(item);
      expect(tap.kind, PickerTapKind.insert);
      expect(tap.text, '/goal ');
    });

    test('没有 insert 的回 WebView 执行', () {
      const ChatPickerItem item = ChatPickerItem(id: 'dir:src', label: 'src/');
      final PickerTap tap = resolvePickerTap(item);
      expect(tap.kind, PickerTapKind.action);
      expect(tap.itemId, 'dir:src');
    });
  });

  group('ChatPickerItem.fromJson', () {
    test('insert 有就给、没有就 null', () {
      final ChatPickerItem withInsert = ChatPickerItem.fromJson(<String, Object?>{
        'id': 'file:src/a.ts',
        'label': 'a.ts',
        'insert': '@src/a.ts ',
      });
      expect(withInsert.insert, '@src/a.ts ');

      final ChatPickerItem without = ChatPickerItem.fromJson(<String, Object?>{'id': 'model', 'label': 'gpt'});
      expect(without.insert, isNull);

      final ChatPickerItem wrongType = ChatPickerItem.fromJson(<String, Object?>{'id': 'x', 'label': 'x', 'insert': 3});
      expect(wrongType.insert, isNull);
    });
  });
}
