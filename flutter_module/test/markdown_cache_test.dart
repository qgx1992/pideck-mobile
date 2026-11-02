import 'package:flutter_test/flutter_test.dart';
import 'package:pideck_flutter/chat/markdown.dart';

void main() {
  setUp(debugClearParseCache);

  test('同一段文本第二次解析直接命中缓存（同一次 List 实例）', () {
    const String source = '# 标题\n\n正文 **粗体** 。';
    final List<MdBlock> first = parseMarkdownCached(source);
    final List<MdBlock> second = parseMarkdownCached(source);
    expect(first, isNotEmpty);
    expect(identical(first, second), isTrue, reason: '没有缓存就会每帧重解析一遍整页历史');
  });

  test('文本变了就是另一份结果（流式追加不能被旧结果粘住）', () {
    final List<MdBlock> a = parseMarkdownCached('第一行');
    final List<MdBlock> b = parseMarkdownCached('第一行\n第二行');
    expect(identical(a, b), isFalse);
    expect(parseMarkdownCached('第一行\n第二行'), same(b));
  });

  test('缓存有上限：写 120 条也只有 48 条留在里面', () {
    for (int i = 0; i < 120; i += 1) {
      parseMarkdownCached('段落 $i');
    }
    expect(debugParseCacheSize(), 48);
    // 最早的那条被挤掉了：再解析是新实例，但取两次仍然命中
    final List<MdBlock> early = parseMarkdownCached('段落 0');
    expect(identical(parseMarkdownCached('段落 0'), early), isTrue);
    // 最近写入的还在
    final List<MdBlock> late = parseMarkdownCached('段落 119');
    expect(identical(late, parseMarkdownCached('段落 119')), isTrue);
  });
}
