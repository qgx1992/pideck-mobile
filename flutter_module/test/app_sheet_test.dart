import 'package:flutter_test/flutter_test.dart';
import 'package:pideck_flutter/common/app_sheet.dart';

void main() {
  test('解析：标题 / 说明 / 分组 / 选中 / 可点', () {
    final AppSheet sheet = AppSheet.fromJson(<String, Object?>{
      'kind': 'newSession',
      'title': '新建会话',
      'note': '后端：Pi',
      'items': <Object?>[
        <String, Object?>{'id': 'backend:pi', 'label': 'Pi', 'group': '后端', 'selected': true},
        <String, Object?>{'id': 'project:p-a', 'label': 'alpha', 'description': 'C:/work/alpha · 3 个会话', 'group': '工作区', 'actionable': true},
      ],
    });
    expect(sheet.kind, 'newSession');
    expect(sheet.title, '新建会话');
    expect(sheet.note, '后端：Pi');
    expect(sheet.items.length, 2);
    expect(sheet.items.first.selected, isTrue);
    expect(sheet.items.first.actionable, isFalse);
    expect(sheet.items.last.actionable, isTrue);
    expect(sheet.items.last.description, contains('3 个会话'));
  });

  test('解析：缺字段时的兜底（坏数据只画空面板，不炸）', () {
    final AppSheet sheet = AppSheet.fromJson(<String, Object?>{'items': 'nope'});
    expect(sheet.kind, '');
    expect(sheet.title, '');
    expect(sheet.note, '');
    expect(sheet.items, isEmpty);
  });

  test('解析：危险项与禁用项', () {
    final AppSheet sheet = AppSheet.fromJson(<String, Object?>{
      'title': 't',
      'items': <Object?>[
        <String, Object?>{'id': 'x', 'label': '删除', 'danger': true},
        <String, Object?>{'id': 'y', 'label': '锁住', 'disabled': true},
      ],
    });
    expect(sheet.items.first.danger, isTrue);
    expect(sheet.items.last.disabled, isTrue);
  });
}
