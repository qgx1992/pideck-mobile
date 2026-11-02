import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pideck_flutter/app_theme.dart';
import 'package:pideck_flutter/chat/chat_page.dart';

/// 输入区：打完字发送按钮必须立刻变亮。
///
/// 这条锁的是一个真实 bug（0.2.5）：打字时为了不整页重建没调 `setState`
/// （0.1.38 的卡顿修复），结果 `canSend` 还是上一帧算的 —— 用户得滚一下
/// 会话界面、等别的原因触发重建，按钮才变亮。现在按钮自己听 controller。
String _snapshot() => jsonEncode(<String, Object?>{
      'sessionId': 's1',
      'title': '测试会话',
      'status': 'idle',
    });

bool _sendEnabled(WidgetTester tester) {
  final IconButton button = tester.widget<IconButton>(
    find.widgetWithIcon(IconButton, Icons.arrow_upward_rounded),
  );
  return button.onPressed != null;
}

void main() {
  testWidgets('打字后发送按钮立刻可用，清空后立刻变灰', (WidgetTester tester) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: buildPiTheme(Brightness.dark),
        home: ChatScreen(initialJson: _snapshot()),
      ),
    );
    await tester.pump();

    expect(_sendEnabled(tester), isFalse, reason: '空输入框不该能发');

    await tester.enterText(find.byType(TextField), '你好');
    await tester.pump();
    expect(_sendEnabled(tester), isTrue, reason: '打完字按钮没变亮 = 又回到「得滚一下」');

    await tester.enterText(find.byType(TextField), '   ');
    await tester.pump();
    expect(_sendEnabled(tester), isFalse, reason: '只有空白也不该能发');

    await tester.enterText(find.byType(TextField), '再打一次');
    await tester.pump();
    expect(_sendEnabled(tester), isTrue);
  });
}
