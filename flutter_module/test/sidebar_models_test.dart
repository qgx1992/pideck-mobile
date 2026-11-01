import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:pideck_flutter/sidebar/sidebar_models.dart';

/// 侧栏快照的解析。
///
/// 这份 JSON 是 WebView 侧 `buildSidebarSnapshot()` 的产物（字段名见
/// `app/src/platform/flutter-sidebar.ts`）。Dart 侧只解析、不判断规则，
/// 所以这里要守的只有两件事：字段名对得上、坏数据不炸。
String encode(Object value) => jsonEncode(value);

void main() {
  test('完整快照解析：分段、计数、行字段', () {
    final SidebarSnapshot snapshot = SidebarSnapshot.parse(
      encode(<String, Object?>{
        'tab': 'projects',
        'tabs': <Object?>[
          <String, Object?>{'id': 'active', 'label': '活动', 'count': 2},
          <String, Object?>{'id': 'chats', 'label': '聊天', 'count': 0},
          <String, Object?>{'id': 'projects', 'label': '项目', 'count': 5},
        ],
        'query': '',
        'deviceCount': 2,
        'sections': <Object?>[
          <String, Object?>{'key': 'device:e1', 'kind': 'device', 'label': '工作站', 'deviceRunning': 1, 'rows': <Object?>[]},
          <String, Object?>{
            'key': 'project:p1',
            'kind': 'group',
            'label': 'PiDeck-Mobile',
            'projectId': 'p1',
            'collapsed': false,
            'active': true,
            'count': 7,
            'runningCount': 2,
            'hiddenCount': 1,
            'expanded': false,
            'rows': <Object?>[
              <String, Object?>{
                'id': 's1',
                'title': '修通知误报',
                'meta': 'PiDeck-Mobile · 14:05',
                'active': true,
                'running': true,
                'acp': false,
                'dsh': false,
                'badge': 'ACP',
                'badgeKind': 'acp',
                'acpToolName': 'claude-code',
                'deviceLabel': '工作站',
              },
            ],
          },
        ],
      }),
    );

    expect(snapshot.tab, 'projects');
    expect(snapshot.tabs, hasLength(3));
    expect(snapshot.tabs[2].label, '项目');
    expect(snapshot.tabs[2].count, 5);
    expect(snapshot.deviceCount, 2);
    expect(snapshot.isEmpty, isFalse);

    final SidebarSection device = snapshot.sections[0];
    expect(device.isDevice, isTrue);
    expect(device.label, '工作站');
    expect(device.deviceRunning, 1);

    final SidebarSection group = snapshot.sections[1];
    expect(group.isGroup, isTrue);
    expect(group.projectId, 'p1');
    expect(group.count, 7);
    expect(group.runningCount, 2);
    expect(group.hiddenCount, 1);
    expect(group.active, isTrue);

    final SidebarRow row = group.rows.single;
    expect(row.id, 's1');
    expect(row.title, '修通知误报');
    expect(row.meta, 'PiDeck-Mobile · 14:05');
    expect(row.active, isTrue);
    expect(row.running, isTrue);
    expect(row.badge, 'ACP');
    expect(row.badgeKind, 'acp');
    expect(row.acpToolName, 'claude-code');
    expect(row.deviceLabel, '工作站');
  });

  test('空串、空对象、坏 JSON 都返回 empty（侧栏画空列表，不炸）', () {
    for (final String raw in <String>['', '   ', '{}', 'not json', '[1,2,3]']) {
      final SidebarSnapshot snapshot = SidebarSnapshot.parse(raw);
      expect(snapshot.isEmpty, isTrue, reason: 'raw=$raw');
      expect(snapshot.tab, 'active');
      expect(snapshot.tabs, isEmpty);
      expect(snapshot.sections, isEmpty);
    }
  });

  test('缺字段给默认值，不抛', () {
    final SidebarSnapshot snapshot = SidebarSnapshot.parse(
      encode(<String, Object?>{
        'sections': <Object?>[
          <String, Object?>{'key': 'search', 'rows': <Object?>[]},
        ],
      }),
    );
    expect(snapshot.tab, 'active');
    expect(snapshot.deviceCount, 0);
    final SidebarSection section = snapshot.sections.single;
    expect(section.kind, 'flat');
    expect(section.isFlat, isTrue);
    expect(section.collapsed, isFalse);
    expect(section.count, 0);
    expect(section.rows, isEmpty);
  });

  test('空徽标字符串当作没有徽标（pi 后端不显示噪音徽标）', () {
    final SidebarRow row = SidebarRow.fromJson(<String, Object?>{'id': 'a', 'title': 't', 'badge': ''});
    expect(row.badge, isNull);
  });

  test('行标题缺失时给「未命名会话」', () {
    final SidebarRow row = SidebarRow.fromJson(<String, Object?>{'id': 'a'});
    expect(row.title, '未命名会话');
  });

  test('rows 里混进非对象元素时跳过', () {
    final SidebarSection section = SidebarSection.fromJson(<String, Object?>{
      'key': 'chats',
      'kind': 'flat',
      'rows': <Object?>[
        'junk',
        42,
        <String, Object?>{'id': 'ok', 'title': '真行'},
      ],
    });
    expect(section.rows, hasLength(1));
    expect(section.rows.single.id, 'ok');
  });
}
