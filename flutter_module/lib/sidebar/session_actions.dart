/// 侧栏里会改数据的动作（0.2.13 起归 Dart）：会话改名 / 复制 / 删除、移除项目、新建会话。
///
/// 为什么搬：这些动作**不需要 WebView 参与任何计算**，只是「调一个接口 + 刷新状态 +
/// 说一句结果」。留在 WebView 里唯一的后果是——用户点了改名，得等 WebView 那一侧
/// 处理完才看见，而那一步随时可能因为页面被覆盖而延后。
///
/// 迁移边界（与 `app/src/main.ts` 的 `dispatchFlutterSidebarAction` 对齐）：
///   - 动作成功后仍把原动作**带 `native: true` 回传**给 WebView，让它只做
///     「刷新 + 重画」，不再重复调接口（一个动作只能有一次副作用）；
///   - 删的如果是当前会话，不在这里处理：页面要不要关、WebView 的
///     `activeSessionId` 怎么清，是 WebView 的事（还会牵到 channel 关闭）。
library;

import '../runtime/api_client.dart';
import '../runtime/state_store.dart';

class SessionActions {
  SessionActions({
    required this.client,
    required this.store,
    required this.activeSessionId,
    required this.onNotice,
    required this.onForward,
  });

  final PiDeckClient client;
  final StateStore store;

  /// 当前打开的会话（删除时用来判断「删的是不是自己」）。
  final String activeSessionId;

  /// 结果提示（成功一句、失败一句），由页面决定怎么显示。
  final void Function(String message) onNotice;

  /// 回传动作给 WebView（只用来刷新与重画，不再重复执行）。
  final void Function(Map<String, Object?> action) onForward;

  /// 要不要由 Dart 处理：删当前会话时不行（要由 WebView 收尾）。
  bool canHandleDelete(String id) => id.isNotEmpty && id != activeSessionId;

  Future<void> rename(String id, String rawTitle) async {
    if (id.isEmpty) return;
    final String title = rawTitle.trim();
    if (title.isEmpty) {
      onNotice('标题不能为空');
      return;
    }
    await _run(
      () => client.sessionRecordAction(id, 'update', patch: <String, Object?>{'title': title}),
      '已重命名',
      <String, Object?>{'type': 'sessionRename', 'id': id, 'title': title},
    );
  }

  Future<void> copy(String id) async {
    if (id.isEmpty) return;
    await _run(
      () => client.sessionRecordAction(id, 'copy'),
      '已复制会话',
      <String, Object?>{'type': 'sessionCopy', 'id': id},
    );
  }

  Future<void> delete(String id) async {
    if (id.isEmpty) return;
    if (!canHandleDelete(id)) return;
    await _run(
      () => client.sessionRecordAction(id, 'delete'),
      '已删除会话',
      <String, Object?>{'type': 'sessionDelete', 'id': id},
    );
  }

  Future<void> removeProject(String projectId) async {
    if (projectId.isEmpty) return;
    await _run(
      () => client.deleteProject(projectId),
      '已移除项目',
      <String, Object?>{'type': 'projectRemove', 'projectId': projectId},
    );
  }

  /// 新建会话并返回新 id（失败返回 null）。
  ///
  /// 项目选择顺序照抄 Web：指定 → 当前会话的项目 → 第一个项目。
  Future<String?> create({String projectId = ''}) async {
    final String target = projectId.isNotEmpty ? projectId : _fallbackProjectId();
    if (target.isEmpty) {
      onNotice('没有可用项目：请先在桌面端添加项目目录');
      return null;
    }
    try {
      final Map<String, Object?> session = await client.createSession(target);
      final Object? id = session['id'];
      final String newId = id is String ? id : '';
      if (newId.isEmpty) {
        onNotice('新建会话没有返回 id');
        return null;
      }
      await store.refresh();
      onForward(<String, Object?>{
        'type': 'createSession',
        'native': true,
        'projectId': target,
        'id': newId,
      });
      return newId;
    } on PiDeckException catch (error) {
      onNotice(error.describe());
      return null;
    } on Object catch (error) {
      onNotice('$error');
      return null;
    }
  }

  String _fallbackProjectId() {
    final Object? sessionProject = store.session(activeSessionId)?['projectId'];
    if (sessionProject is String && sessionProject.isNotEmpty) return sessionProject;
    final List<Map<String, Object?>> projects = store.projects();
    if (projects.isEmpty) return '';
    final Object? id = projects.first['id'];
    return id is String ? id : '';
  }

  Future<void> _run(
    Future<void> Function() task,
    String success,
    Map<String, Object?> forward,
  ) async {
    try {
      await task();
    } on PiDeckException catch (error) {
      onNotice(error.describe());
      return;
    } on Object catch (error) {
      onNotice('$error');
      return;
    }
    await store.refresh();
    onNotice(success);
    onForward(<String, Object?>{...forward, 'native': true});
  }
}
