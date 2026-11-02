/// 工作区抽屉的纯函数层：Git 状态解析、目录排序、预览截断、路径换算。
///
/// 逐条对应 Web 侧两份源码：
///   - `app/src/ui/workspace-model.ts`（`parseGitStatus` / `parseGitLog` /
///     `formatCommitDate` / `toRelativePath` / `truncatePreview` 等）
///   - `app/src/platform/flutter-workspace.ts`（`emptyWorkspaceSnapshot` /
///     `sortWorkspaceEntries` / `crumbsToPath` / `lastSegment`）
///
/// 这里**不联网**、不碰 UI，只做数据变换 —— 所以能逐字节对拍，也好写单测。
/// 真正的加载顺序与超时在 `workspace_runtime.dart`，渲染在 `workspace_drawer.dart`。
library;

/// 预览超过这个长度就截断（与 Web 侧 `PREVIEW_MAX_CHARS` 一致）。
const int previewMaxChars = 8000;

const String _groupMerge = '冲突中';
const String _groupIndex = '已暂存';
const String _groupWorkingTree = '已修改';
const String _groupUntracked = '未跟踪';

const List<String> _groupOrder = <String>['merge', 'index', 'workingTree', 'untracked'];

const Map<String, String> _groupTitles = <String, String>{
  'merge': _groupMerge,
  'index': _groupIndex,
  'workingTree': _groupWorkingTree,
  'untracked': _groupUntracked,
};

String _str(Object? value) => value is String ? value : '';

List<Object?> _list(Object? value) => value is List ? value : const <Object?>[];

Map<String, Object?> _obj(Object? value) {
  if (value is Map<String, Object?>) return value;
  if (value is Map) {
    return value.map((Object? key, Object? item) => MapEntry<String, Object?>(key.toString(), item));
  }
  return const <String, Object?>{};
}

/// 空快照：字段顺序与 `emptyWorkspaceSnapshot()` 一致（键序也是契约）。
Map<String, Object?> emptyWorkspaceSnapshotJson() => <String, Object?>{
      'open': false,
      // 与 main.ts 的初始 tab 一致：默认看文件
      'tab': 'files',
      'title': '',
      'loading': false,
      'empty': '',
      'branch': '',
      'changedTotal': 0,
      'changes': <Object?>[],
      'commits': <Object?>[],
      'crumbs': <Object?>[],
      'entries': <Object?>[],
      'preview': null,
      'todos': <Object?>[],
    };

/// 把绝对路径压成相对项目根的路径，兼容 Windows 反斜杠。
///
/// 不同盘符 / 已经是相对路径时只保留**最后两段** —— 整行被长路径撑爆比丢信息更糟。
String toRelativePath(String absolute, String projectPath) {
  final String normalized = absolute.replaceAll('\\', '/');
  String root = projectPath.replaceAll('\\', '/');
  root = root.replaceAll(RegExp(r'/+$'), '');
  if (root.isEmpty) return normalized;
  final String lowerNormalized = normalized.toLowerCase();
  final String lowerRoot = root.toLowerCase();
  if (lowerNormalized.startsWith('$lowerRoot/')) {
    return normalized.substring(root.length + 1);
  }
  final List<String> segments = normalized.split('/').where((String s) => s.isNotEmpty).toList();
  final int from = segments.length <= 2 ? 0 : segments.length - 2;
  return segments.sublist(from).join('/');
}

/// `GET /api/git/status` → 视图模型。
///
/// `repo !== true` 时返回 `isRepo:false`；某个分组没有文件就整组跳过（不画空标题）；
/// **分组顺序固定**为 merge → index → workingTree → untracked。
Map<String, Object?> parseGitStatus(Object? raw, String projectPath) {
  final Map<String, Object?> record = _obj(raw);
  if (record['repo'] != true) {
    return <String, Object?>{
      'isRepo': false,
      'branch': '',
      'branches': <Object?>[],
      'groups': <Object?>[],
      'total': 0,
    };
  }
  final Map<String, Object?> branchInfo = _obj(record['branch']);
  final Map<String, Object?> groupsRaw = _obj(record['groups']);

  final List<Object?> groups = <Object?>[];
  int total = 0;
  for (final String key in _groupOrder) {
    final List<Object?> files = <Object?>[];
    for (final Object? item in _list(groupsRaw[key])) {
      if (item is! Map) continue;
      final Map<String, Object?> entry = _obj(item);
      final String path = _str(entry['path']);
      if (path.isEmpty) continue;
      files.add(<String, Object?>{
        'path': path,
        'relativePath': toRelativePath(path, projectPath),
        'letter': entry['letter'] is String ? entry['letter'] : '?',
        'group': key,
      });
    }
    if (files.isEmpty) continue;
    total += files.length;
    groups.add(<String, Object?>{
      'key': key,
      'title': _groupTitles[key] ?? key,
      'files': files,
    });
  }

  return <String, Object?>{
    'isRepo': true,
    'branch': _str(branchInfo['current']),
    'branches': <Object?>[
      for (final Object? item in _list(branchInfo['branches']))
        if (item is String) item,
    ],
    'groups': groups,
    'total': total,
  };
}

/// `GET /api/git/log` → 提交列表。没有 hash 的条目直接丢掉（服务端偶尔塞空行）。
List<Map<String, Object?>> parseGitLog(Object? raw) {
  final Map<String, Object?> record = _obj(raw);
  final List<Map<String, Object?>> commits = <Map<String, Object?>>[];
  for (final Object? item in _list(record['commits'])) {
    if (item is! Map) continue;
    final Map<String, Object?> commit = _obj(item);
    final String hash = _str(commit['hash']);
    if (hash.isEmpty) continue;
    final String message = _str(commit['message']);
    commits.add(<String, Object?>{
      'hash': hash,
      'shortHash': _str(commit['shortHash']).isEmpty
          ? (hash.length <= 7 ? hash : hash.substring(0, 7))
          : _str(commit['shortHash']),
      'message': message.isEmpty ? '' : message.split('\n').first.trim(),
      'authorName': _str(commit['authorName']),
      'authorDate': _str(commit['authorDate']),
    });
  }
  return commits;
}

/// 提交时间显示成 `10-27 15:20`（本地时区）。
///
/// 解析不了就退回 `raw.slice(0,16)`（与 Web 侧一致，别在这里抛异常）。
String formatCommitDate(String raw) {
  if (raw.isEmpty) return '';
  final DateTime? parsed = DateTime.tryParse(raw);
  if (parsed == null) return raw.length <= 16 ? raw : raw.substring(0, 16);
  final DateTime local = parsed.toLocal();
  String two(int value) => value < 10 ? '0$value' : '$value';
  return '${two(local.month)}-${two(local.day)} ${two(local.hour)}:${two(local.minute)}';
}

/// 目录在前、其余按名称排。
///
/// Web 侧用的是 `localeCompare(name, "zh-Hans-CN", {numeric: true})`：
/// 中文按拼音、数字按数值。Dart 没有 ICU，这里退化成「数字按数值 + 其余按码元」——
/// 英文/ASCII 文件名与 Web 完全一致，纯中文名可能与拼音序不同（渲染顺序差异，不影响功能）。
List<Map<String, Object?>> sortWorkspaceEntries(Iterable<Map<String, Object?>> rows) {
  final List<Map<String, Object?>> list = List<Map<String, Object?>>.of(rows);
  list.sort((Map<String, Object?> a, Map<String, Object?> b) {
    final bool aDir = a['dir'] == true;
    final bool bDir = b['dir'] == true;
    if (aDir != bDir) return aDir ? -1 : 1;
    return naturalCompare(_str(a['name']), _str(b['name']));
  });
  return list;
}

/// 数字按数值、字母不区分大小写比较（`file2` < `file10`，`a` < `File1`）。
///
/// 字母折叠这一步是为了对齐 `localeCompare` 的主级比较：JS 的排序里
/// `a` 排在 `File1` 前面（不按 ASCII 大小写），只按码元比会得到相反的顺序。
int naturalCompare(String a, String b) {
  int i = 0;
  int j = 0;
  while (i < a.length && j < b.length) {
    final int ca = a.codeUnitAt(i);
    final int cb = b.codeUnitAt(j);
    final bool digitA = ca >= 0x30 && ca <= 0x39;
    final bool digitB = cb >= 0x30 && cb <= 0x39;
    if (digitA && digitB) {
      final int startA = i;
      final int startB = j;
      while (i < a.length && a.codeUnitAt(i) >= 0x30 && a.codeUnitAt(i) <= 0x39) {
        i++;
      }
      while (j < b.length && b.codeUnitAt(j) >= 0x30 && b.codeUnitAt(j) <= 0x39) {
        j++;
      }
      final String rawA = a.substring(startA, i).replaceFirst(RegExp(r'^0+(?=.)'), '');
      final String rawB = b.substring(startB, j).replaceFirst(RegExp(r'^0+(?=.)'), '');
      if (rawA.length != rawB.length) return rawA.length < rawB.length ? -1 : 1;
      final int cmp = rawA.compareTo(rawB);
      if (cmp != 0) return cmp < 0 ? -1 : 1;
      continue;
    }
    final int fa = _foldAscii(ca);
    final int fb = _foldAscii(cb);
    if (fa != fb) return fa < fb ? -1 : 1;
    i++;
    j++;
  }
  if (i < a.length) return 1;
  if (j < b.length) return -1;
  return 0;
}

int _foldAscii(int codeUnit) =>
    codeUnit >= 0x41 && codeUnit <= 0x5a ? codeUnit + 0x20 : codeUnit;

/// 预览截断：超长切掉尾巴并标记（别把 512KB 整份推过桥）。
Map<String, Object?> truncatePreview(String content, {int max = previewMaxChars}) {
  if (content.length <= max) {
    return <String, Object?>{'content': content, 'truncated': false};
  }
  return <String, Object?>{'content': content.substring(0, max), 'truncated': true};
}

/// 预览正文：二进制 / 超限 / 被截断都说清楚，不要默默展示半截。
///
/// 对应 `app/src/main.ts:3659-3676` 的 `previewTextOf()`，连文案都照抄 ——
/// 手机和网页看到同一句话，用户才不会以为两边行为不一致。
String previewTextOf({required String kind, required int size, required String content}) {
  final String bytes = formatBytes(size);
  switch (kind) {
    case 'binary':
      return '（二进制文件，$bytes，桌面端接口只提供文本，看不了内容）';
    case 'tooLarge':
      return '（文件 $bytes，超过 2 MB 读不全，不预览）';
    case 'truncated':
      return '$content\n\n…（文件共 $bytes，这里只显示前 512 KB）';
    default:
      if (size > previewMaxBytes) {
        return '文件过大（$bytes），超过 ${previewMaxBytes ~/ 1024} KB 不预览。';
      }
      return content.isEmpty ? '（空文件）' : content;
  }
}

/// 能与预览展开的最大文件：512 KB（与 `FILE_PREVIEW_MAX_BYTES` 一致）。
const int previewMaxBytes = 512 * 1024;

/// 字节数的人类可读形式（`image-attach-model.ts:83-87` 的同款）。
String formatBytes(int bytes) {
  if (bytes < 1024) return '$bytes B';
  if (bytes < 1024 * 1024) return '${(bytes / 1024).round()} KB';
  return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
}

/// 面包屑 → 目录路径（第一段是项目名，不是目录）。
String crumbsToPath(List<String> crumbs) =>
    crumbs.length <= 1 ? '' : crumbs.sublist(1).join('/');

/// 文件路径 → 末尾一段（服务端偶尔在 name 里带路径，进目录时只认最后一段）。
String lastSegment(String path) {
  final List<String> parts = path.split(RegExp(r'[\\/]')).where((String s) => s.isNotEmpty).toList();
  return parts.isEmpty ? path : parts.last;
}
