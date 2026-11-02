import 'package:flutter_test/flutter_test.dart';
import 'package:pideck_flutter/workspace/workspace_models.dart';

void main() {
  test('解析：Git 改动 / 提交 / 分支', () {
    final WorkspaceSnapshot snapshot = WorkspaceSnapshot.fromJson(<String, Object?>{
      'open': true,
      'tab': 'git',
      'title': 'PiDeck',
      'branch': 'master',
      'changedTotal': 2,
      'changes': <Object?>[
        <String, Object?>{'letter': 'M', 'path': 'app/src/main.ts', 'group': '已修改'},
        <String, Object?>{'letter': '??', 'path': 'notes.md', 'group': '未跟踪'},
      ],
      'commits': <Object?>[
        <String, Object?>{'sha': 'abc1234', 'subject': 'feat: x', 'date': '11-02 18:00'},
      ],
    });

    expect(snapshot.open, isTrue);
    expect(snapshot.branch, 'master');
    expect(snapshot.changedTotal, 2);
    expect(snapshot.changes.length, 2);
    expect(snapshot.changes.first.letter, 'M');
    expect(snapshot.commits.single.sha, 'abc1234');
    expect(snapshot.changeGroups, <String>['已修改', '未跟踪']);
  });

  test('解析：文件列表 / 面包屑 / 目录标记', () {
    final WorkspaceSnapshot snapshot = WorkspaceSnapshot.fromJson(<String, Object?>{
      'tab': 'files',
      'crumbs': <Object?>['PiDeck', 'app'],
      'entries': <Object?>[
        <String, Object?>{'name': 'src', 'path': 'app/src', 'dir': true},
        <String, Object?>{'name': 'README.md', 'path': 'app/README.md', 'dir': false},
      ],
    });

    expect(snapshot.crumbs, <String>['PiDeck', 'app']);
    expect(snapshot.entries.first.dir, isTrue);
    expect(snapshot.entries.last.dir, isFalse);
    expect(snapshot.entries.last.path, 'app/README.md');
  });

  test('解析：预览与待办', () {
    final WorkspaceSnapshot snapshot = WorkspaceSnapshot.fromJson(<String, Object?>{
      'tab': 'todos',
      'preview': <String, Object?>{'path': 'a.md', 'content': '# hi', 'truncated': true},
      'todos': <Object?>[
        <String, Object?>{'text': '✓ 一\n• 二', 'status': 'completed', 'at': '1/2 · 18:00'},
      ],
    });

    expect(snapshot.preview?.path, 'a.md');
    expect(snapshot.preview?.truncated, isTrue);
    expect(snapshot.todos.single.finished, isTrue);
    expect(snapshot.todos.single.text, contains('• 二'));
  });

  test('没给 tab 时默认看文件', () {
    expect(WorkspaceSnapshot.fromJson(const <String, Object?>{}).tab, 'files');
    expect(const WorkspaceSnapshot().tab, 'files');
  });

  test('坏数据兜底：非法 tab / 缺字段 / null', () {
    final WorkspaceSnapshot snapshot = WorkspaceSnapshot.fromJson(<String, Object?>{'tab': 'nope'});
    expect(snapshot.tab, 'nope');
    expect(snapshot.changes, isEmpty);
    expect(snapshot.preview, isNull);
    expect(WorkspaceSnapshot.parse(''), isNull);
    expect(WorkspaceSnapshot.parse('{'), isNull);
    expect(WorkspaceSnapshot.parse('[]'), isNull);
  });
}
