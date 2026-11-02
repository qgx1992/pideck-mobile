import 'package:flutter_test/flutter_test.dart';
import 'package:pideck_flutter/workspace/workspace_native.dart';

/// 工作区纯函数层的对拍测试。
///
/// 期望值全部由 **node 直接跑 TS 版**得到（`app/src/ui/workspace-model.ts`
/// 与 `app/src/platform/flutter-workspace.ts`），不是手写的 —— 手写很容易
/// 把「我觉得应该这样」写进断言里，那时两边就已经分叉了。
///
/// 注意：时间相关的用例只用**不带时区偏移**的 ISO 串。带 `+08:00` 的会被
/// 转成本地时间，CI 的 runner 是 UTC，断言会随机器漂。
void main() {
  group('parseGitStatus', () {
    test('四分组按固定顺序、空组不出现、Windows 路径压成相对路径', () {
      final Map<String, Object?> view = parseGitStatus(<String, Object?>{
        'repo': true,
        'branch': <String, Object?>{
          'current': 'master',
          'branches': <Object?>['master', 'dev'],
        },
        'groups': <String, Object?>{
          'merge': <Object?>[],
          'index': <Object?>[
            <String, Object?>{'path': r'D:\proj\src\a.ts', 'letter': 'M'},
          ],
          'workingTree': <Object?>[
            <String, Object?>{'path': r'D:\proj\b.ts', 'status': 2},
            <String, Object?>{'path': '', 'letter': 'M'},
            <String, Object?>{'letter': 'M'},
          ],
          'untracked': <Object?>[
            <String, Object?>{'path': r'E:\other\deep\c.ts', 'letter': '?'},
          ],
        },
      }, r'D:\proj\');

      expect(view['isRepo'], isTrue);
      expect(view['branch'], 'master');
      expect(view['branches'], <Object?>['master', 'dev']);
      expect(view['total'], 3);
      final List<Object?> groups = view['groups']! as List<Object?>;
      // merge 是空的 → 整组不出现；顺序固定为 merge → index → workingTree → untracked
      expect(
        groups.map((Object? group) => (group! as Map<String, Object?>)['key']).toList(),
        <Object?>['index', 'workingTree', 'untracked'],
      );
      expect(
        groups.map((Object? group) => (group! as Map<String, Object?>)['title']).toList(),
        <Object?>['已暂存', '已修改', '未跟踪'],
      );
      final List<Object?> indexFiles =
          (groups.first! as Map<String, Object?>)['files']! as List<Object?>;
      expect((indexFiles.first! as Map<String, Object?>)['relativePath'], 'src/a.ts');
      // 没有 letter 的条目补 "?"（服务端偶尔只给数字 status）
      final List<Object?> workingFiles =
          (groups[1]! as Map<String, Object?>)['files']! as List<Object?>;
      expect(workingFiles.length, 1);
      expect((workingFiles.first! as Map<String, Object?>)['letter'], '?');
      // 别的盘符：只保留最后两段，别让整行被路径撑爆
      final List<Object?> untrackedFiles =
          (groups[2]! as Map<String, Object?>)['files']! as List<Object?>;
      expect((untrackedFiles.first! as Map<String, Object?>)['relativePath'], 'deep/c.ts');
    });

    test('不是仓库 / 传 null → isRepo:false 的空视图', () {
      for (final Object? raw in <Object?>[<String, Object?>{'repo': false}, null]) {
        final Map<String, Object?> view = parseGitStatus(raw, 'D:/proj');
        expect(view['isRepo'], isFalse);
        expect(view['branch'], '');
        expect(view['groups'], isEmpty);
        expect(view['total'], 0);
      }
    });

    test('仓库但没改动 → isRepo:true 且分组全空', () {
      final Map<String, Object?> view = parseGitStatus(<String, Object?>{
        'repo': true,
        'branch': <String, Object?>{},
        'groups': <String, Object?>{},
      }, '/home/x/proj');
      expect(view['isRepo'], isTrue);
      expect(view['branch'], '');
      expect(view['groups'], isEmpty);
      expect(view['total'], 0);
    });
  });

  group('parseGitLog', () {
    test('丢空条目、补 shortHash、message 只留第一行并 trim', () {
      final List<Map<String, Object?>> commits = parseGitLog(<String, Object?>{
        'commits': <Object?>[
          <String, Object?>{
            'hash': 'abcdef1234567',
            'message': '  第一行  \n第二行',
            'authorName': 'a',
            'authorDate': '2026-11-02T15:04:00',
          },
          <String, Object?>{
            'hash': '1234567890abc',
            'shortHash': '1234567',
            'message': 'single',
            'authorName': 'b',
            'authorDate': 'bad-date-value',
          },
          <String, Object?>{'hash': '', 'message': 'no hash'},
          null,
          'nope',
        ],
      });
      expect(commits.length, 2);
      expect(commits[0]['shortHash'], 'abcdef1');
      expect(commits[0]['message'], '第一行');
      expect(commits[1]['shortHash'], '1234567');
      expect(commits[1]['authorDate'], 'bad-date-value');
    });

    test('没有 commits 字段 → 空列表', () {
      expect(parseGitLog(<String, Object?>{}), isEmpty);
      expect(parseGitLog(null), isEmpty);
    });
  });

  group('formatCommitDate', () {
    test('ISO 无偏移按本地时间格式化（与 TS 版同一份期望）', () {
      expect(formatCommitDate('2026-11-02T15:04:00'), '11-02 15:04');
      expect(formatCommitDate('2026-01-09T00:05:00'), '01-09 00:05');
    });

    test('解析不了就退回前 16 个字符', () {
      expect(formatCommitDate('not-a-date-at-all-long-string'), 'not-a-date-at-al');
      expect(formatCommitDate(''), '');
    });
  });

  group('sortWorkspaceEntries', () {
    test('目录在前，其余按名称（数字按数值）', () {
      final List<Map<String, Object?>> sorted = sortWorkspaceEntries(<Map<String, Object?>>[
        <String, Object?>{'name': 'b', 'path': 'b', 'dir': false},
        <String, Object?>{'name': 'a', 'path': 'a', 'dir': false},
        <String, Object?>{'name': 'z', 'path': 'z', 'dir': true},
        <String, Object?>{'name': 'file10.txt', 'path': 'file10.txt', 'dir': false},
        <String, Object?>{'name': 'file2.txt', 'path': 'file2.txt', 'dir': false},
        <String, Object?>{'name': 'File1', 'path': 'File1', 'dir': false},
      ]);
      expect(
        sorted.map((Map<String, Object?> row) => row['name']).toList(),
        <Object?>['z', 'a', 'b', 'File1', 'file2.txt', 'file10.txt'],
      );
    });

    test('naturalCompare 的进位规则（number 语义）', () {
      expect(naturalCompare('file2', 'file10'), lessThan(0));
      expect(naturalCompare('file10', 'file2'), greaterThan(0));
      expect(naturalCompare('a1', 'a01'), 0);
      expect(naturalCompare('a', 'a'), 0);
      expect(naturalCompare('abc', 'abd'), lessThan(0));
    });
  });

  group('truncatePreview', () {
    test('超长截断并标记，短的保持原样', () {
      expect(truncatePreview('short', max: 5), <String, Object?>{
        'content': 'short',
        'truncated': false,
      });
      expect(truncatePreview('abcdefghij', max: 5), <String, Object?>{
        'content': 'abcde',
        'truncated': true,
      });
      expect(truncatePreview('0123456789', max: 5), <String, Object?>{
        'content': '01234',
        'truncated': true,
      });
    });

    test('默认上限 8000 个字符（等于再多一个就切）', () {
      expect((truncatePreview('x' * 8000))['truncated'], isFalse);
      final Map<String, Object?> cut = truncatePreview('x' * 8001);
      expect((cut['content']! as String).length, 8000);
      expect(cut['truncated'], isTrue);
      expect(previewMaxChars, 8000);
    });
  });

  group('路径换算', () {
    test('crumbsToPath 丢掉第一段（项目名）', () {
      expect(crumbsToPath(<String>['项目']), '');
      expect(crumbsToPath(<String>['项目', 'src']), 'src');
      expect(crumbsToPath(<String>['项目', 'src', 'ui']), 'src/ui');
    });

    test('lastSegment 认两种分隔符，空路径原样返回', () {
      expect(lastSegment(r'D:\proj\src\ui'), 'ui');
      expect(lastSegment('a/b/c.txt'), 'c.txt');
      expect(lastSegment('plain.txt'), 'plain.txt');
      expect(lastSegment(r'D:\'), 'D:');
      expect(lastSegment(''), '');
    });

    test('toRelativePath 去前缀、跨盘只留最后两段', () {
      expect(toRelativePath(r'D:\proj\src\a.ts', r'D:\proj\'), 'src/a.ts');
      expect(toRelativePath('D:/proj/src/a.ts', 'd:/proj'), 'src/a.ts');
      expect(toRelativePath(r'E:\other\x\y.ts', r'D:\proj'), 'x/y.ts');
      expect(toRelativePath('relative/a.ts', ''), 'relative/a.ts');
    });
  });

  group('previewTextOf / formatBytes', () {
    test('四种 kind 的说明文案与 Web 版逐字一致', () {
      expect(
        previewTextOf(kind: 'binary', size: 2048, content: ''),
        '（二进制文件，2 KB，桌面端接口只提供文本，看不了内容）',
      );
      expect(
        previewTextOf(kind: 'tooLarge', size: 3 * 1024 * 1024, content: ''),
        '（文件 3.0 MB，超过 2 MB 读不全，不预览）',
      );
      expect(
        previewTextOf(kind: 'truncated', size: 2 * 1024 * 1024, content: 'abc'),
        'abc\n\n…（文件共 2.0 MB，这里只显示前 512 KB）',
      );
      expect(previewTextOf(kind: 'text', size: 0, content: ''), '（空文件）');
      expect(previewTextOf(kind: 'text', size: 10, content: 'hi'), 'hi');
      expect(
        previewTextOf(kind: 'text', size: previewMaxBytes + 1, content: 'ignored'),
        '文件过大（512 KB），超过 512 KB 不预览。',
      );
    });

    test('formatBytes 三档', () {
      expect(formatBytes(0), '0 B');
      expect(formatBytes(1023), '1023 B');
      expect(formatBytes(1024), '1 KB');
      expect(formatBytes(1536), '2 KB');
      expect(formatBytes(1024 * 1024), '1.0 MB');
    });
  });

  group('emptyWorkspaceSnapshotJson', () {
    test('默认看文件页签、抽屉关着、字段齐全', () {
      final Map<String, Object?> empty = emptyWorkspaceSnapshotJson();
      expect(
        empty.keys.toList(),
        <String>[
          'open',
          'tab',
          'title',
          'loading',
          'empty',
          'branch',
          'changedTotal',
          'changes',
          'commits',
          'crumbs',
          'entries',
          'preview',
          'todos',
        ],
      );
      expect(empty['open'], isFalse);
      expect(empty['tab'], 'files');
      expect(empty['preview'], isNull);
    });
  });
}
