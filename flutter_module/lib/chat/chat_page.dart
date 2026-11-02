/// 聊天页（Flutter 版）：**视图迁移**，不是重写状态机。
///
/// 数据全部来自 WebView 的 `ChatSnapshot`（`app/src/platform/flutter-chat.ts`）：
/// 那边跑着唯一的一份 `ChatReducer`（`src/api/frames.ts`），这里只负责画和收集动作。
/// 因此本文件**不允许**做任何协议推断（拼接 delta、猜工具状态、自己算 token），
/// 只能把 `ChatSnapshot` 里已有的东西摆出来。
///
/// 滚动行为对齐 Web 侧 `app/src/ui/chat-view.ts`：
///   - `stick` 阈值 80px（`:65-68`），用户往上翻就停止自动贴底；
///   - 新内容进来后若仍 `stick`，在下一帧跳到最大滚动位置（等价 `scrollToBottom()`）；
///   - 键盘弹出 / 文本变长导致的尺寸变化同样走这条路径（`keepBottomOnResize()`）。
library;

import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../app_theme.dart';
import '../common/app_sheet.dart';
import '../host_channel.dart';
import '../sidebar/sidebar_drawer.dart';
import '../sidebar/sidebar_models.dart';
import '../workspace/workspace_drawer.dart';
import '../workspace/workspace_models.dart';
import '../theme_mode.dart';
import 'chat_models.dart';
import 'composer_insert.dart';
import 'image_viewer.dart';
import 'markdown_view.dart';

/// 与 Web 侧 `chat-view.ts:56-68` 同一个阈值。
const double _stickThreshold = 80;

class ChatScreen extends StatefulWidget {
  const ChatScreen({super.key, this.initialJson = ''});

  /// 宿主在打开本页之前缓存的那份快照，用于首帧（避免白屏）。
  final String initialJson;

  @override
  State<ChatScreen> createState() => _ChatScreenState();
}

class _ChatScreenState extends State<ChatScreen> {
  ChatSnapshot _snapshot = const ChatSnapshot();
  SidebarSnapshot _sidebar = SidebarSnapshot.none;
  final GlobalKey<ScaffoldState> _scaffoldKey = GlobalKey<ScaffoldState>();

  /// 右侧工作区抽屉「用户刚刚点了什么」。
  ///
  /// 快照是数据来源，但**开合动作要立刻有反馈**：点下去先按用户的意图画，
  /// 直到某份快照里的 `workspace.open` 和意图一致才算对齐。
  /// （只在「来了快照就清空意图」的话，关闭动作会被一份还在路上的旧快照顶回来。）
  bool? _workspaceIntent;

  /// 抽屉当前实际显示的开关（快照没给意见时的兜底）。
  bool _workspaceOpen = false;
  final ScrollController _scroll = ScrollController();
  final TextEditingController _composer = TextEditingController();

  /// 上一次的输入框文本（判断「刚敲了一个字符」用）
  String _composerText = '';

  /// 输入框里此刻只剩触发的 `/` 或 `@`：下一条 insert 要整段替换
  bool _composerHoldsTrigger = false;

  /// 任务清单展开着没（折叠状态只在内存里，和 Web 版一致）
  bool _todoExpanded = false;

  /// 刚跳过去的那条消息（高亮 1.4 秒）
  String _highlightId = '';

  /// 历史条目的 key：`/history` 面板跳转要按消息 id 找到它再滚过去
  final Map<int, GlobalKey> _historyKeys = <int, GlobalKey>{};
  final TextEditingController _pickerSearch = TextEditingController();

  /// 当前面板的种类，用来判断要不要清空搜索框（换面板时清）
  String _pickerKind = '';
  final FocusNode _composerFocus = FocusNode();

  /// 一条消息最多带几张图，与 `app/src/ui/image-attach-model.ts` 的 `MAX_IMAGES` 一致。
  static const int _maxImages = 4;

  /// 已经选好、还没发出去的图（缩略图行从它渲染）。
  final List<PickedImage> _images = <PickedImage>[];

  bool _stick = true;
  String _lastRequestId = '';
  String _pendingDraft = '';

  /// 提问卡的输入框必须用常驻 controller：流式时每 40ms 会来一份新快照，
  /// 每次 build 新建一个 controller 会把光标和输入法状态一起冲掉。
  final TextEditingController _pendingController = TextEditingController();

  /// 多选中的临时选择：同样不能放在 build 里，否则每次重建都清空。
  final Set<String> _multiSelection = <String>{};
  final Map<String, Object?> _batchAnswers = <String, Object?>{};
  bool _batchReview = false;

  /// 聊天页发起的底部弹层（DSH 工具）：内容随快照原地更新。
  late final AppSheetHost _sheetHost = AppSheetHost(
    sendPick: (String id) =>
        sendChatAction(<String, Object?>{'type': 'sheetPick', 'id': id}),
    sendClose: () => sendChatAction(<String, Object?>{'type': 'sheetClose'}),
  );

  @override
  void initState() {
    super.initState();
    bindHostChannel(
      onChat: _applyJson,
      onSidebar: _applySidebar,
      onNotice: _notice,
    );
    _scroll.addListener(_onScroll);
    if (widget.initialJson.isNotEmpty) _applyJson(widget.initialJson);
    loadInitialChat().then((String json) {
      if (json == '{}' || json == widget.initialJson) return;
      _applyJson(json);
    });
    loadInitialSidebar().then((String json) {
      if (json == '{}') return;
      _applySidebar(json);
    });
  }

  /// 侧栏快照。分组 / 折叠 / 搜索全部在 WebView 侧算完（`buildSidebarSnapshot()`），
  /// 这里只存下来给抽屉画，**不**做任何规则判断。
  void _applySidebar(String json) {
    if (json.isEmpty) return;
    final SidebarSnapshot next = SidebarSnapshot.parse(json);
    if (!mounted) return;
    setState(() => _sidebar = next);
  }

  @override
  void dispose() {
    _sheetHost.dispose();
    _scroll.removeListener(_onScroll);
    _scroll.dispose();
    _composer.dispose();
    _composerFocus.dispose();
    _pendingController.dispose();
    super.dispose();
  }

  void _notice(String text) {
    if (!mounted || text.isEmpty) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(text), behavior: SnackBarBehavior.floating),
    );
  }

  void _applyJson(String json) {
    if (json.isEmpty) return;
    Map<String, Object?> decoded;
    try {
      final Object? raw = jsonDecode(json);
      if (raw is! Map) return;
      decoded = raw.map(
        (Object? key, Object? value) =>
            MapEntry<String, Object?>(str(key), value),
      );
    } catch (_) {
      return;
    }
    final ChatSnapshot next = ChatSnapshot.fromJson(decoded);
    if (next.sessionId.isEmpty) return;
    // 主题：快照里带着 light / dark / system，写进全局 notifier 让 MaterialApp 重建。
    // 放在 setState 外面 —— 它是整棵树的事，不是这一页的事。
    applyThemeMode(next.themeMode);
    // 换了面板类型就清掉搜索词。放在这里而不是 build 里：build 期间清 controller
    // 会通知 ValueListenableBuilder，在构建中 setState 直接抛断言。
    final String nextKind = next.picker?.kind ?? '';
    if (nextKind.isNotEmpty && nextKind != _pickerKind) {
      _pickerKind = nextKind;
      _pickerSearch.clear();
    }
    // 换了提问卡就清掉草稿，否则上一张卡的内容会漏到下一张
    final String requestId = next.pending?.requestId ?? '';
    if (requestId != _lastRequestId) {
      _lastRequestId = requestId;
      _pendingDraft = next.pending?.prefill ?? '';
      _pendingController.text = _pendingDraft;
      _multiSelection.clear();
      _batchAnswers.clear();
      _batchReview = false;
    }
    setState(() => _snapshot = next);
    _afterLayout();
    _syncWorkspaceDrawer(next.workspace);
    // 弹层要等这一帧画完再弹：setState 期间不能开路由
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      _sheetHost.sync(context, next.sheet);
    });
  }

  /// 统一的「打开 / 收起工作区」：先按用户意图画，再通知 Web，最后真的开合。
  ///
  /// 少了最后那一步 `openEndDrawer()` 就只是把 `endDrawer` 挂上去，
  /// 抽屉不会自己滑出来 —— 这也是「按钮点了没反应」的原因之一。
  void _setWorkspaceOpen(bool open) {
    setState(() {
      _workspaceIntent = open;
      _workspaceOpen = open;
    });
    sendChatAction(<String, Object?>{
      'type': open ? 'workspaceOpen' : 'workspaceClose',
    });
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      final ScaffoldState? scaffold = _scaffoldKey.currentState;
      if (scaffold == null) return;
      if (open && !scaffold.isEndDrawerOpen) {
        scaffold.openEndDrawer();
      } else if (!open && scaffold.isEndDrawerOpen) {
        Navigator.of(context).pop();
      }
    });
  }

  /// 把「Web 侧说抽屉该开着」翻译成真的开/关。
  ///
  /// 两边状态必须由快照单向驱动：Dart 自己开抽屉只会让 Web 侧不知道，
  /// 下一次推送一到（每 80ms 都可能）抽屉就会被强行关掉。
  void _syncWorkspaceDrawer(WorkspaceSnapshot? workspace) {
    final bool? intent = _workspaceIntent;
    // 快照追上了用户的意图 → 意图退休，之后完全听快照
    if (intent != null && (workspace?.open ?? false) == intent) {
      _workspaceIntent = null;
    }
    if (workspace != null) _workspaceOpen = workspace.open;
    final bool want = _workspaceIntent ?? _workspaceOpen;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      final ScaffoldState? scaffold = _scaffoldKey.currentState;
      if (scaffold == null) return;
      final bool showing = scaffold.isEndDrawerOpen;
      if (want == showing) return;
      if (want) {
        scaffold.openEndDrawer();
      } else {
        Navigator.of(context).pop();
      }
    });
  }

  /// 新内容落地后，如果仍然贴底就跳到底部。
  ///
  /// 必须等下一帧：`setState` 之后 `maxScrollExtent` 还是旧值。
  void _afterLayout() {
    if (!_stick) return;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || !_scroll.hasClients) return;
      _scroll.jumpTo(_scroll.position.maxScrollExtent);
    });
  }

  void _onScroll() {
    if (!_scroll.hasClients) return;
    final double distance = _scroll.position.maxScrollExtent - _scroll.offset;
    final bool next = distance <= _stickThreshold;
    if (next != _stick) setState(() => _stick = next);
  }

  void _jumpToBottom() {
    if (!_scroll.hasClients) return;
    setState(() => _stick = true);
    _scroll.animateTo(
      _scroll.position.maxScrollExtent,
      duration: const Duration(milliseconds: 180),
      curve: Curves.easeOut,
    );
  }

  Future<void> _send() async {
    final String typed = _composer.text.trim();
    // 只发图不写字时给服务端一句自然的话：/prompt 的 message 不能为空（与 WebView 版同口径）
    final String text = typed.isEmpty && _images.isNotEmpty ? '看下这张图' : typed;
    if (text.isEmpty) return;
    final List<Map<String, Object?>> images = _images
        .map(
          (PickedImage image) => <String, Object?>{
            'data': image.data,
            'mimeType': image.mimeType,
          },
        )
        .toList(growable: false);
    _composer.clear();
    _composerText = '';
    _composerHoldsTrigger = false;
    _composerFocus.unfocus();
    setState(() {
      _stick = true;
      _images.clear();
    });
    await sendChatAction(<String, Object?>{
      'type': 'send',
      'text': text,
      if (images.isNotEmpty) 'images': images,
    });
  }

  /// 选图：解码与压缩都在原生侧做，这里只收结果。
  Future<void> _pickImage() async {
    if (_images.length >= _maxImages) {
      _notice('最多 $_maxImages 张图');
      return;
    }
    final PickedImage? image = await pickImage();
    if (!mounted || image == null) return;
    setState(() {
      if (_images.length < _maxImages) _images.add(image);
    });
  }

  Future<void> _respond(Map<String, Object?> response) async {
    final ChatPending? pending = _snapshot.pending;
    if (pending == null) return;
    await sendChatAction(<String, Object?>{
      'type': 'respond',
      'requestId': pending.requestId,
      'response': response,
    });
  }

  /// 长按一条消息：菜单项由 WebView 推来（`actionsForRole()` 的结果，ACP 只剩复制）。
  ///
  /// 删除的二次确认、编辑的输入框都在本地做 —— 这两步原本是 WebView 的弹层，
  /// 被 Flutter 页盖着根本看不见，所以必须在 Flutter 里重做。
  Future<void> _showMessageMenu(ChatHistoryItem item) async {
    if (item.isTool) return;
    final List<ChatMenuItem> actions = _snapshot.menusFor(item.role);
    if (actions.isEmpty) return;

    final ChatMenuItem? picked = await showModalBottomSheet<ChatMenuItem>(
      context: context,
      backgroundColor: PiColors.of(context).surface,
      showDragHandle: true,
      builder: (BuildContext sheetContext) {
        final PiColors c = PiColors.of(sheetContext);
        return SafeArea(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: <Widget>[
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 0, 16, 6),
                child: Align(
                  alignment: Alignment.centerLeft,
                  child: Text(
                    item.isUser ? '这条提问' : '这条回答',
                    style: TextStyle(color: c.textMuted, fontSize: 12),
                  ),
                ),
              ),
              for (final ChatMenuItem action in actions)
                ListTile(
                  dense: true,
                  title: Text(
                    action.label,
                    style: TextStyle(
                      color: action.danger ? c.danger : c.text,
                      fontSize: 14,
                    ),
                  ),
                  onTap: () => Navigator.of(sheetContext).pop(action),
                ),
            ],
          ),
        );
      },
    );

    if (!mounted || picked == null) return;

    if (picked.id == 'delete') {
      final bool ok = await _confirm(
        title: '删除这条消息？',
        body: '删除后这一条会从会话里消失（不可恢复），确认？',
        confirmLabel: '确认删除',
        danger: true,
      );
      if (!ok) return;
      _sendMessageAction(item, picked.id);
      return;
    }

    if (picked.id == 'edit') {
      final String? next = await _editText(item.text);
      if (next == null) return;
      if (next.trim() == item.text.trim()) return;
      _sendMessageAction(item, picked.id, text: next);
      return;
    }

    _sendMessageAction(item, picked.id);
  }

  void _sendMessageAction(ChatHistoryItem item, String action, {String? text}) {
    final Map<String, Object?> payload = <String, Object?>{
      'type': 'messageAction',
      'id': item.id,
      'role': item.role,
      'action': action,
    };
    if (text != null) payload['text'] = text;
    sendChatAction(payload);
  }

  Future<bool> _confirm({
    required String title,
    required String body,
    required String confirmLabel,
    bool danger = false,
  }) async {
    final bool? ok = await showDialog<bool>(
      context: context,
      builder: (BuildContext dialogContext) {
        final PiColors c = PiColors.of(dialogContext);
        return AlertDialog(
          backgroundColor: c.surface,
          title: Text(title, style: TextStyle(color: c.text, fontSize: 15)),
          content: Text(
            body,
            style: TextStyle(color: c.textSecondary, fontSize: 13),
          ),
          actions: <Widget>[
            TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(false),
              child: Text('取消', style: TextStyle(color: c.textMuted)),
            ),
            FilledButton(
              style: danger
                  ? FilledButton.styleFrom(backgroundColor: c.danger)
                  : null,
              onPressed: () => Navigator.of(dialogContext).pop(true),
              child: Text(confirmLabel),
            ),
          ],
        );
      },
    );
    return ok == true;
  }

  Future<String?> _editText(String current) async {
    final TextEditingController controller = TextEditingController(
      text: current,
    );
    final String? next = await showDialog<String>(
      context: context,
      builder: (BuildContext dialogContext) {
        final PiColors c = PiColors.of(dialogContext);
        return AlertDialog(
          backgroundColor: c.surface,
          title: Text('编辑这条消息', style: TextStyle(color: c.text, fontSize: 15)),
          content: TextField(
            controller: controller,
            autofocus: true,
            maxLines: 8,
            minLines: 3,
            style: TextStyle(color: c.text, fontSize: 14),
            decoration: InputDecoration(
              hintText: '内容不能为空',
              hintStyle: TextStyle(color: c.textMuted),
            ),
          ),
          actions: <Widget>[
            TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(),
              child: Text('取消', style: TextStyle(color: c.textMuted)),
            ),
            FilledButton(
              onPressed: () => Navigator.of(dialogContext).pop(controller.text),
              child: const Text('保存'),
            ),
          ],
        );
      },
    );
    controller.dispose();
    if (next == null) return null;
    if (next.trim().isEmpty) {
      _notice('内容不能为空');
      return null;
    }
    return next;
  }

  @override
  Widget build(BuildContext context) {
    final PiColors c = PiColors.of(context);
    final ChatSnapshot snapshot = _snapshot;
    final ChatLive? live = snapshot.live;
    // 一句历史都没有、也没有在跑的流：这时画首屏引导（对齐 Web 版 `.history.is-empty-state`）
    final bool bare = snapshot.history.isEmpty && live == null;
    final int historyCount = snapshot.history.length;
    final int liveCount = live?.blocks.length ?? 0;
    // 优先听用户刚点的意图，其次听快照，最后才回退到本地记录
    final bool workspaceOpen = _workspaceIntent ?? snapshot.workspace?.open ?? _workspaceOpen;

    return Scaffold(
      key: _scaffoldKey,
      backgroundColor: c.background,
      drawer: SidebarDrawer(
        snapshot: _sidebar,
        onAction: sendSidebarAction,
        onClose: () => _scaffoldKey.currentState?.closeDrawer(),
      ),
      // 右侧工作区：数据来自快照（可能还没到），开合状态本地先记一份。
      // 滑动手势保留 —— 用户的第一反应就是从右边缘往左滑。
      endDrawer: workspaceOpen
          ? WorkspaceDrawer(
              snapshot: snapshot.workspace ??
                  WorkspaceSnapshot(
                    open: true,
                    loading: true,
                    title: snapshot.title,
                  ),
              onAction: sendChatAction,
            )
          : null,
      onEndDrawerChanged: (bool opened) {
        final bool current =
            _workspaceIntent ?? _snapshot.workspace?.open ?? _workspaceOpen;
        if (opened == current) return;
        _setWorkspaceOpen(opened);
      },
      appBar: AppBar(
        titleSpacing: 0,
        leading: IconButton(
          tooltip: '会话列表',
          icon: const Icon(Icons.menu_rounded),
          onPressed: () => _scaffoldKey.currentState?.openDrawer(),
        ),
        title: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: <Widget>[
            Text(
              snapshot.title.isEmpty ? '会话' : snapshot.title,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: Theme.of(context).textTheme.titleMedium,
            ),
            if (snapshot.isRunning)
              Text(
                snapshot.statusText.isEmpty ? '运行中…' : snapshot.statusText,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: Theme.of(context).textTheme.labelSmall
                    ?.copyWith(color: Theme.of(context).colorScheme.primary),
              ),
          ],
        ),
        actions: <Widget>[
          IconButton(
            tooltip: workspaceOpen ? '收起工作区' : '工作区',
            icon: Icon(
              workspaceOpen ? Icons.view_sidebar_rounded : Icons.view_sidebar_outlined,
              color: c.textMuted,
            ),
            onPressed: () => _setWorkspaceOpen(!workspaceOpen),
          ),
          IconButton(
            tooltip: '刷新',
            icon: Icon(Icons.refresh_rounded, color: c.textMuted),
            onPressed: () =>
                sendChatAction(<String, Object?>{'type': 'refresh'}),
          ),
          IconButton(
            tooltip: '返回网页版',
            icon: Icon(Icons.open_in_browser_rounded, color: c.textMuted),
            onPressed: () => closeHost(),
          ),
        ],
      ),
      body: PopScope(
        canPop: false,
        // 返回键：先收最上面一层（抽屉 / 选择面板），什么都收不掉才算「要退出」。
        // shell 模式下这一页盖着整屏，退回去只会看到空白的网页版，所以交给 Web 侧退 App。
        onPopInvokedWithResult: (bool didPop, Object? result) {
          if (didPop) return;
          final ScaffoldState? scaffold = _scaffoldKey.currentState;
          if (scaffold != null && scaffold.isDrawerOpen) {
            scaffold.closeDrawer();
            return;
          }
          if (scaffold != null && scaffold.isEndDrawerOpen) {
            _setWorkspaceOpen(false);
            return;
          }
          if (_snapshot.picker != null) {
            sendChatAction(<String, Object?>{'type': 'closePicker'});
            return;
          }
          sendChatAction(<String, Object?>{'type': 'exitApp'});
        },
        child: Column(
          children: <Widget>[
            _headerBar(c, snapshot.header),
            Expanded(
              child: bare && snapshot.suggestions.isNotEmpty
                  ? _emptyState(c, snapshot)
                  : Stack(
                      children: <Widget>[
                        ListView.builder(
                          controller: _scroll,
                          padding: const EdgeInsets.fromLTRB(12, 8, 12, 12),
                          itemCount: historyCount + liveCount + 1,
                          itemBuilder: (BuildContext context, int index) {
                            if (index < historyCount) {
                              return _HistoryTile(
                                key: _historyKeys.putIfAbsent(
                                  index,
                                  () => GlobalKey(),
                                ),
                                item: snapshot.history[index],
                                highlighted:
                                    _highlightId.isNotEmpty &&
                                    snapshot.history[index].id == _highlightId,
                                onLongPress: () =>
                                    _showMessageMenu(snapshot.history[index]),
                              );
                            }
                            final int blockIndex = index - historyCount;
                            if (live != null &&
                                blockIndex < live.blocks.length) {
                              final ChatBlockView block =
                                  live.blocks[blockIndex];
                              return _BlockTile(
                                key: ValueKey<String>('b:${block.key}'),
                                block: block,
                              );
                            }
                            return _footer(context, c, snapshot);
                          },
                        ),
                        if (!_stick)
                          Positioned(
                            right: 12,
                            bottom: 12,
                            child: FloatingActionButton.small(
                              heroTag: 'chat-jump-bottom',
                              backgroundColor: c.surface,
                              foregroundColor: c.text,
                              onPressed: _jumpToBottom,
                              child: const Icon(Icons.arrow_downward_rounded),
                            ),
                          ),
                      ],
                    ),
            ),
            if (snapshot.picker != null)
              _pickerPanel(c, snapshot.picker!)
            else
              _composerBar(c, snapshot),
          ],
        ),
      ),
    );
  }

  /// 空会话的首屏引导：徽标 + 标题 + 副标题 + 三条提示词。
  ///
  /// 对齐 Web 版 `.empty-state`（92px 圆底徽标、23px 标题、13.5px 副标题、胶囊提示词）。
  /// 以前这里只有几个飘在输入框上方的 chip —— 没有标题托底，看着像残留的按钮。
  Widget _emptyState(PiColors c, ChatSnapshot snapshot) {
    return LayoutBuilder(
      builder: (BuildContext context, BoxConstraints constraints) {
        return SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(20, 24, 20, 36),
          child: ConstrainedBox(
            constraints: BoxConstraints(minHeight: constraints.maxHeight - 60),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: <Widget>[
                CircleAvatar(
                  radius: 44,
                  backgroundColor: Theme.of(context)
                      .colorScheme
                      .primaryContainer,
                  child: Icon(
                    Icons.chat_bubble_outline_rounded,
                    size: 36,
                    color: Theme.of(context).colorScheme.onPrimaryContainer,
                  ),
                ),
                const SizedBox(height: 20),
                Text(
                  snapshot.emptyTitle.isEmpty
                      ? '开始使用你的 PiDeck'
                      : snapshot.emptyTitle,
                  textAlign: TextAlign.center,
                  style: Theme.of(context).textTheme.headlineSmall
                      ?.copyWith(color: c.text),
                ),
                if (snapshot.emptySubtitle.isNotEmpty)
                  Padding(
                    padding: const EdgeInsets.only(top: 8),
                    child: Text(
                      snapshot.emptySubtitle,
                      textAlign: TextAlign.center,
                      style: Theme.of(context).textTheme.bodyMedium
                          ?.copyWith(color: c.textMuted),
                    ),
                  ),
                const SizedBox(height: 20),
                Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  alignment: WrapAlignment.center,
                  children: <Widget>[
                    for (final String prompt in snapshot.suggestions)
                      _suggestionChip(c, prompt),
                  ],
                ),
              ],
            ),
          ),
        );
      },
    );
  }

  Widget _footer(BuildContext context, PiColors c, ChatSnapshot snapshot) {
    final List<Widget> children = <Widget>[];
    if (snapshot.loading && !snapshot.hasContent) {
      children.add(
        Padding(
          padding: const EdgeInsets.symmetric(vertical: 24),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: <Widget>[
              SizedBox(
                width: 14,
                height: 14,
                child: CircularProgressIndicator(
                  strokeWidth: 2,
                  color: c.accent,
                ),
              ),
              const SizedBox(width: 8),
              Text('加载中…', style: TextStyle(color: c.textMuted, fontSize: 13)),
            ],
          ),
        ),
      );
    }
    if (snapshot.error != null) {
      children.add(_errorCard(c, snapshot.error!));
    }
    if (snapshot.empty != null && !snapshot.hasContent) {
      children.add(
        Padding(
          padding: const EdgeInsets.symmetric(vertical: 32),
          child: Center(
            child: Text(
              snapshot.empty!,
              textAlign: TextAlign.center,
              style: TextStyle(color: c.textMuted, fontSize: 13, height: 1.5),
            ),
          ),
        ),
      );
    }
    if (snapshot.pending != null) {
      children.add(_pendingCard(context, c, snapshot.pending!));
    }
    final ChatPerf? perf = snapshot.perf;
    if (perf != null) {
      final List<String> chips = <String>[
        if (perf.ttftMs != null) '首字 ${_fmtDuration(perf.ttftMs!)}',
        if (perf.streamingTps != null)
          '${perf.streamingTps!.toStringAsFixed(1)} tok/s',
        if (perf.averageTps != null)
          '均 ${perf.averageTps!.toStringAsFixed(1)} tok/s',
        if (perf.outputTokens != null) '${perf.outputTokens} tokens',
      ];
      if (chips.isNotEmpty) {
        children.add(
          Padding(
            padding: const EdgeInsets.only(top: 4, bottom: 4),
            child: Wrap(
              spacing: 6,
              runSpacing: 4,
              children: chips
                  .map(
                    (String text) => Text(
                      text,
                      style: TextStyle(fontSize: 11, color: c.textMuted),
                    ),
                  )
                  .toList(growable: false),
            ),
          ),
        );
      }
    }
    if (children.isEmpty) return const SizedBox(height: 4);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: children,
    );
  }

  Widget _errorCard(PiColors c, ChatErrorInfo error) {
    return Container(
      margin: const EdgeInsets.symmetric(vertical: 8),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: c.surface,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: c.danger.withValues(alpha: 0.5)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Text(
            error.title.isEmpty ? '出错了' : error.title,
            style: TextStyle(
              color: c.danger,
              fontWeight: FontWeight.w600,
              fontSize: 13.5,
            ),
          ),
          if (error.detail.isNotEmpty) ...<Widget>[
            const SizedBox(height: 4),
            SelectableText(
              error.detail,
              style: TextStyle(color: c.textMuted, fontSize: 11.5),
            ),
          ],
          if (error.hint.isNotEmpty) ...<Widget>[
            const SizedBox(height: 6),
            Text(
              error.hint,
              style: TextStyle(
                color: c.textSecondary,
                fontSize: 12,
                height: 1.45,
              ),
            ),
          ],
          const SizedBox(height: 8),
          Align(
            alignment: Alignment.centerRight,
            child: TextButton(
              onPressed: () =>
                  sendChatAction(<String, Object?>{'type': 'refresh'}),
              child: const Text('重试'),
            ),
          ),
        ],
      ),
    );
  }

  /* ----------------------------- 提问卡 ----------------------------- */

  Widget _pendingCard(BuildContext context, PiColors c, ChatPending pending) {
    final List<ChatBatchOption> options = pending.options
        .map((String value) => ChatBatchOption(label: value, value: value))
        .toList(growable: false);
    // PiDeck 在 select 没有选项时会降级成 input（`chat-view.ts:665` 同口径）
    final String method = pending.method == 'select' && options.isEmpty
        ? 'input'
        : pending.method;

    final Widget body;
    if (method == 'batch_ask' && pending.batchQuestions.isNotEmpty) {
      body = _batchBody(context, c, pending);
    } else if (method == 'select' && options.isNotEmpty) {
      body = _selectBody(context, c, pending, options);
    } else if (method == 'confirm') {
      body = Row(
        children: <Widget>[
          Expanded(
            child: FilledButton(
              onPressed: () => _respond(<String, Object?>{'confirmed': true}),
              child: const Text('允许'),
            ),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: OutlinedButton(
              onPressed: () => _respond(<String, Object?>{'confirmed': false}),
              child: const Text('拒绝'),
            ),
          ),
        ],
      );
    } else if (method == 'input' || method == 'editor') {
      body = Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: <Widget>[
          TextField(
            controller: _pendingController,
            minLines: method == 'editor' ? 4 : 1,
            maxLines: method == 'editor' ? 10 : 4,
            style: TextStyle(color: c.text, fontSize: 13.5),
            decoration: InputDecoration(
              hintText: pending.placeholder.isNotEmpty
                  ? pending.placeholder
                  : (method == 'editor' ? '编辑内容…' : '输入回答…'),
              hintStyle: TextStyle(color: c.textMuted, fontSize: 13),
              filled: true,
              fillColor: c.surfaceAlt,
              border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(8),
                borderSide: BorderSide.none,
              ),
              isDense: true,
            ),
            onChanged: (String value) => _pendingDraft = value,
          ),
          const SizedBox(height: 8),
          Align(
            alignment: Alignment.centerRight,
            child: FilledButton(
              onPressed: () =>
                  _respond(<String, Object?>{'value': _pendingDraft.trim()}),
              child: Text(_pendingDraft.trim().isEmpty ? '留空提交' : '提交'),
            ),
          ),
        ],
      );
    } else {
      body = Text(
        '暂不支持 ${pending.method} 类型，请回 Web 版处理',
        style: TextStyle(color: c.textMuted, fontSize: 12.5),
      );
    }

    return Container(
      margin: const EdgeInsets.symmetric(vertical: 8),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: c.surface,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: c.accent.withValues(alpha: 0.45)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: <Widget>[
          Text(
            'AI 提问',
            style: TextStyle(
              fontSize: 10.5,
              letterSpacing: 0.6,
              color: c.accent,
            ),
          ),
          const SizedBox(height: 4),
          Text(
            pending.title.isEmpty ? '需要你的确认' : pending.title,
            style: TextStyle(
              color: c.text,
              fontSize: 14,
              fontWeight: FontWeight.w600,
            ),
          ),
          const SizedBox(height: 10),
          body,
          const SizedBox(height: 6),
          Align(
            alignment: Alignment.centerLeft,
            child: TextButton(
              onPressed: () => _respond(<String, Object?>{'cancelled': true}),
              child: Text(
                '取消',
                style: TextStyle(color: c.textMuted, fontSize: 12.5),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _selectBody(
    BuildContext context,
    PiColors c,
    ChatPending pending,
    List<ChatBatchOption> options,
  ) {
    final Set<String> selected = _multiSelection;

    if (!pending.multiple) {
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: <Widget>[
          for (final ChatBatchOption option in options)
            Padding(
              padding: const EdgeInsets.only(bottom: 6),
              child: OutlinedButton(
                style: OutlinedButton.styleFrom(
                  alignment: Alignment.centerLeft,
                  side: BorderSide(color: c.divider),
                  foregroundColor: c.text,
                  padding: const EdgeInsets.symmetric(
                    horizontal: 12,
                    vertical: 10,
                  ),
                ),
                onPressed: () =>
                    _respond(<String, Object?>{'value': option.value}),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: <Widget>[
                    Text(
                      option.label,
                      style: TextStyle(color: c.text, fontSize: 13.5),
                    ),
                    if (option.description.isNotEmpty)
                      Padding(
                        padding: const EdgeInsets.only(top: 2),
                        child: Text(
                          option.description,
                          style: TextStyle(color: c.textMuted, fontSize: 11.5),
                        ),
                      ),
                  ],
                ),
              ),
            ),
          if (pending.allowOther) _otherInput(c),
        ],
      );
    }

    return StatefulBuilder(
      builder: (BuildContext context, void Function(void Function()) setLocal) {
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: <Widget>[
            for (final ChatBatchOption option in options)
              Padding(
                padding: const EdgeInsets.only(bottom: 6),
                child: OutlinedButton(
                  style: OutlinedButton.styleFrom(
                    alignment: Alignment.centerLeft,
                    side: BorderSide(
                      color: selected.contains(option.value)
                          ? c.accent
                          : c.divider,
                    ),
                    foregroundColor: c.text,
                    padding: const EdgeInsets.symmetric(
                      horizontal: 12,
                      vertical: 10,
                    ),
                  ),
                  onPressed: () => setState(() {
                    if (selected.contains(option.value)) {
                      selected.remove(option.value);
                    } else {
                      selected.add(option.value);
                    }
                  }),
                  child: Row(
                    children: <Widget>[
                      Icon(
                        selected.contains(option.value)
                            ? Icons.check_box_rounded
                            : Icons.check_box_outline_blank_rounded,
                        size: 16,
                        color: selected.contains(option.value)
                            ? c.accent
                            : c.textMuted,
                      ),
                      const SizedBox(width: 8),
                      Expanded(
                        child: Text(
                          option.label,
                          style: TextStyle(color: c.text, fontSize: 13.5),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            Align(
              alignment: Alignment.centerRight,
              child: FilledButton(
                onPressed: selected.isEmpty
                    ? null
                    : () => _respond(<String, Object?>{
                        'value': selected.join('、'),
                      }),
                child: const Text('提交'),
              ),
            ),
          ],
        );
      },
    );
  }

  Widget _otherInput(PiColors c) {
    return Padding(
      padding: const EdgeInsets.only(top: 4),
      child: TextField(
        controller: _pendingController,
        minLines: 1,
        maxLines: 3,
        style: TextStyle(color: c.text, fontSize: 13.5),
        decoration: InputDecoration(
          hintText: '或输入自定义内容…',
          hintStyle: TextStyle(color: c.textMuted, fontSize: 13),
          filled: true,
          fillColor: c.surfaceAlt,
          border: OutlineInputBorder(
            borderRadius: BorderRadius.circular(8),
            borderSide: BorderSide.none,
          ),
          isDense: true,
        ),
        onChanged: (String value) => _pendingDraft = value,
        onSubmitted: (String value) {
          if (value.trim().isEmpty) return;
          _respond(<String, Object?>{'value': value.trim()});
        },
      ),
    );
  }

  /// 一次问多题：逐题作答 → 审阅 → 统一提交。
  ///
  /// 提交格式与 Web 侧一致（`batchAnswerPayload`），不在这里另发明一种。
  Widget _batchBody(BuildContext context, PiColors c, ChatPending pending) {
    final List<ChatBatchQuestion> questions = pending.batchQuestions;
    final int answered = questions
        .where((ChatBatchQuestion q) => _batchAnswers.containsKey(q.id))
        .length;

    return StatefulBuilder(
      builder: (BuildContext context, void Function(void Function()) setLocal) {
        if (_batchReview) {
          return Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: <Widget>[
              Text(
                '已答 $answered/${questions.length}',
                style: TextStyle(fontSize: 11.5, color: c.textMuted),
              ),
              const SizedBox(height: 8),
              for (final ChatBatchQuestion question in questions)
                Padding(
                  padding: const EdgeInsets.only(bottom: 8),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: <Widget>[
                      Text(
                        question.question,
                        style: TextStyle(
                          fontSize: 12.5,
                          color: c.textSecondary,
                        ),
                      ),
                      Text(
                        _batchAnswers.containsKey(question.id)
                            ? (batchAnswerLabel(_batchAnswers[question.id])
                                      .isEmpty
                                  ? '（留空）'
                                  : batchAnswerLabel(
                                      _batchAnswers[question.id],
                                    ))
                            : '未回答',
                        style: TextStyle(fontSize: 13, color: c.text),
                      ),
                    ],
                  ),
                ),
              Row(
                children: <Widget>[
                  TextButton(
                    onPressed: () => setLocal(() => _batchReview = false),
                    child: const Text('返回'),
                  ),
                  const Spacer(),
                  FilledButton(
                    onPressed: answered < questions.length
                        ? null
                        : () {
                            final String payload = batchAnswerPayload(
                              questions,
                              _batchAnswers,
                            );
                            _respond(<String, Object?>{'value': payload});
                          },
                    child: Text(answered < questions.length ? '还有未回答' : '提交'),
                  ),
                ],
              ),
            ],
          );
        }

        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: <Widget>[
            Text(
              '已答 $answered/${questions.length}',
              style: TextStyle(fontSize: 11.5, color: c.textMuted),
            ),
            const SizedBox(height: 8),
            for (final ChatBatchQuestion question in questions)
              Padding(
                padding: const EdgeInsets.only(bottom: 10),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: <Widget>[
                    if (question.header.isNotEmpty)
                      Text(
                        question.header,
                        style: TextStyle(
                          fontSize: 10.5,
                          color: c.accent,
                          letterSpacing: 0.5,
                        ),
                      ),
                    Text(
                      question.question,
                      style: TextStyle(
                        fontSize: 13,
                        color: c.text,
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                    const SizedBox(height: 6),
                    if (question.kind == 'confirm')
                      Row(
                        children: <Widget>[
                          for (final bool answer in <bool>[true, false])
                            Padding(
                              padding: const EdgeInsets.only(right: 8),
                              child: OutlinedButton(
                                onPressed: () => setLocal(
                                  () => _batchAnswers[question.id] = answer,
                                ),
                                child: Text(answer ? '是' : '否'),
                              ),
                            ),
                        ],
                      )
                    else if (question.options.isEmpty)
                      TextField(
                        style: TextStyle(color: c.text, fontSize: 13),
                        decoration: InputDecoration(
                          hintText: question.placeholder.isEmpty
                              ? '输入回答…'
                              : question.placeholder,
                          hintStyle: TextStyle(
                            color: c.textMuted,
                            fontSize: 12.5,
                          ),
                          filled: true,
                          fillColor: c.surfaceAlt,
                          isDense: true,
                          border: OutlineInputBorder(
                            borderRadius: BorderRadius.circular(8),
                            borderSide: BorderSide.none,
                          ),
                        ),
                        onChanged: (String value) =>
                            _batchAnswers[question.id] = value,
                      )
                    else
                      Wrap(
                        spacing: 6,
                        runSpacing: 6,
                        children: question.options
                            .map((ChatBatchOption option) {
                              final Object? current =
                                  _batchAnswers[question.id];
                              final bool picked = question.multiple
                                  ? (current is List &&
                                        current.contains(option.value))
                                  : current == option.value;
                              return ChoiceChip(
                                label: Text(
                                  option.label,
                                  style: const TextStyle(fontSize: 12.5),
                                ),
                                selected: picked,
                                onSelected: (bool on) => setLocal(() {
                                  if (!question.multiple) {
                                    _batchAnswers[question.id] = option.value;
                                    return;
                                  }
                                  final List<String> list = current is List
                                      ? current.map(str).toList()
                                      : <String>[];
                                  if (on) {
                                    list.add(option.value);
                                  } else {
                                    list.remove(option.value);
                                  }
                                  _batchAnswers[question.id] = list;
                                }),
                              );
                            })
                            .toList(growable: false),
                      ),
                  ],
                ),
              ),
            Align(
              alignment: Alignment.centerRight,
              child: OutlinedButton(
                onPressed: answered == 0
                    ? null
                    : () => setLocal(() => _batchReview = true),
                child: const Text('审阅并提交'),
              ),
            ),
          ],
        );
      },
    );
  }

  /* ----------------------------- 输入栏 ----------------------------- */

  /// 顶栏：meta、chips、待办/子代理摘要。
  ///
  /// 这里**不做任何判断** —— chips 是 WebView 按后端能力算好之后给的，
  /// 该不该出现「模型」入口由 `composerToolChips()` 决定，Dart 只管画。
  Widget _headerBar(PiColors c, ChatHeaderView header) {
    final List<Widget> chips = <Widget>[];
    for (final ChatChip chip in header.chips) {
      chips.add(
        _headerChip(
          c,
          label: chip.label,
          muted: chip.muted,
          active: chip.active,
          onTap: () => sendChatAction(<String, Object?>{
            'type': 'chip',
            'key': chip.key,
          }),
        ),
      );
    }
    final ChatContextChip? context = header.context;
    if (context != null) {
      chips.add(
        _headerChip(
          c,
          label: context.compacting ? '压缩中…' : '${context.percent}%',
          muted: !context.overflow && context.percent < 60,
          active: context.overflow,
          onTap: () => sendChatAction(<String, Object?>{'type': 'context'}),
        ),
      );
    }
    if (header.showSendMode) {
      chips.add(
        _headerChip(
          c,
          label: header.sendMode == 'steer' ? '插话' : '排队',
          onTap: () => sendChatAction(<String, Object?>{'type': 'sendMode'}),
        ),
      );
    }
    if (chips.isEmpty &&
        header.meta.isEmpty &&
        header.todo == null &&
        header.subagents == null) {
      return const SizedBox.shrink();
    }
    final ChatTodoSummary? todo = header.todo;
    final ChatSubagentSummary? subagents = header.subagents;
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(12, 2, 12, 6),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          if (header.meta.isNotEmpty)
            Padding(
              padding: const EdgeInsets.only(bottom: 4),
              child: Text(
                header.meta,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(fontSize: 11.5, color: c.textMuted),
              ),
            ),
          if (chips.isNotEmpty)
            Wrap(spacing: 6, runSpacing: 6, children: chips),
          if (todo != null) _todoRow(c, todo),
          if (subagents != null)
            Padding(
              padding: const EdgeInsets.only(top: 4),
              child: Text(
                subagents.active > 0
                    ? '子代理 ${subagents.active}/${subagents.total} 进行中'
                    : '子代理 ${subagents.total} 个已结束',
                style: TextStyle(fontSize: 11, color: c.textMuted),
              ),
            ),
        ],
      ),
    );
  }

  /// 空态提示词：点一下填进输入框（不直接发 —— 用户多半想改两个字再发）。
  Widget _suggestionChip(PiColors c, String prompt) {
    return ActionChip(
      onPressed: () {
        _composer.value = TextEditingValue(
          text: prompt,
          selection: TextSelection.collapsed(offset: prompt.length),
        );
        _composerText = prompt;
        _composerHoldsTrigger = false;
        _composerFocus.requestFocus();
        setState(() {});
      },
      visualDensity: VisualDensity.compact,
      backgroundColor: Theme.of(context).colorScheme.surfaceContainerHigh,
      side: BorderSide.none,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
      label: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 300),
        child: Text(
          prompt,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: TextStyle(fontSize: 13, height: 1.35, color: c.textSecondary),
        ),
      ),
    );
  }

  /// 任务清单：一行摘要，点一下展开逐条（清单由 agent 维护，条目本身不可点选）。
  Widget _todoRow(PiColors c, ChatTodoSummary todo) {
    final String summary = todo.current.isEmpty
        ? '任务 ${todo.done}/${todo.total}'
        : '任务 ${todo.done}/${todo.total} · ${todo.current}';
    final bool expandable = todo.items.isNotEmpty;
    return Padding(
      padding: const EdgeInsets.only(top: 6),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          InkWell(
            onTap: expandable
                ? () => setState(() => _todoExpanded = !_todoExpanded)
                : null,
            borderRadius: BorderRadius.circular(6),
            child: Row(
              children: <Widget>[
                Expanded(
                  child: Text(
                    summary,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(fontSize: 11, color: c.textMuted),
                  ),
                ),
                if (expandable)
                  Icon(
                    _todoExpanded
                        ? Icons.expand_less_rounded
                        : Icons.expand_more_rounded,
                    size: 14,
                    color: c.textMuted,
                  ),
              ],
            ),
          ),
          if (expandable && _todoExpanded)
            Padding(
              padding: const EdgeInsets.only(top: 4),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: <Widget>[
                  for (final ChatTodoItem item in todo.items)
                    Padding(
                      padding: const EdgeInsets.only(top: 2),
                      child: Row(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: <Widget>[
                          _todoGlyph(c, item.status),
                          const SizedBox(width: 6),
                          Expanded(
                            child: Text(
                              item.text,
                              style: TextStyle(
                                fontSize: 11.5,
                                height: 1.35,
                                color: item.status == 'completed'
                                    ? c.textMuted
                                    : c.textSecondary,
                                decoration: item.status == 'completed'
                                    ? TextDecoration.lineThrough
                                    : null,
                                decorationColor: c.textMuted,
                              ),
                            ),
                          ),
                        ],
                      ),
                    ),
                ],
              ),
            ),
        ],
      ),
    );
  }

  /// 单条状态图标：完成=对勾，进行中=实心点，待办=空心圈。
  Widget _todoGlyph(PiColors c, String status) {
    if (status == 'completed') {
      return Icon(Icons.check_rounded, size: 12, color: c.accent);
    }
    if (status == 'in_progress') {
      return Padding(
        padding: const EdgeInsets.only(top: 4),
        child: Container(
          width: 7,
          height: 7,
          decoration: BoxDecoration(color: c.accent, shape: BoxShape.circle),
        ),
      );
    }
    return Padding(
      padding: const EdgeInsets.only(top: 3),
      child: Container(
        width: 7,
        height: 7,
        decoration: BoxDecoration(
          border: Border.all(color: c.textMuted, width: 1.2),
          shape: BoxShape.circle,
        ),
      ),
    );
  }

  Widget _headerChip(
    PiColors c, {
    required String label,
    required VoidCallback onTap,
    bool muted = false,
    bool active = false,
  }) {
    final ColorScheme scheme = Theme.of(context).colorScheme;
    final Color color = active
        ? scheme.onSecondaryContainer
        : (muted ? c.textMuted : c.textSecondary);
    // 用真的 M3 组件，不要继续手画胶囊：ActionChip 自带涟漪、无障碍语义、
    // 跟随 chipTheme 的尺寸与形状，也才有"原生控件"的手感。
    return ActionChip(
      onPressed: onTap,
      visualDensity: VisualDensity.compact,
      materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
      backgroundColor: active
          ? scheme.secondaryContainer
          : scheme.surfaceContainerHigh,
      side: BorderSide.none,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(8)),
      label: Text(
        label,
        style: TextStyle(
          fontSize: 12,
          color: color,
          fontWeight: active ? FontWeight.w600 : FontWeight.w500,
        ),
      ),
    );
  }

  /// 面板里点了一项。
  ///
  /// 带 `insert` 的是**纯文本动作**（提示词 / 斜线命令 / @ 文件）：直接写进本页
  /// 输入框再回一条 `closePicker`。其余（模型、思考、安全、压缩上下文）要
  /// WebView 真去干活，照旧回 `pickerSelect`。
  void _onPickerTap(ChatPickerView picker, ChatPickerItem item) {
    // 「历史」面板是唯一不进输入框、也不回 WebView 的一种：它要的是滚动，
    // 而这份历史的 id → 下标就在本地快照里，绕回 WebView 去操作用户看不见的 DOM 没有意义。
    if (picker.kind == 'history') {
      sendChatAction(<String, Object?>{'type': 'closePicker'});
      unawaited(_jumpToHistoryMessage(item.id));
      return;
    }
    final PickerTap tap = resolvePickerTap(item);
    if (tap.kind == PickerTapKind.insert) {
      _insertComposerText(tap.text);
      sendChatAction(<String, Object?>{'type': 'closePicker'});
      return;
    }
    sendChatAction(<String, Object?>{
      'type': 'pickerSelect',
      'kind': picker.kind,
      'id': tap.itemId,
    });
  }

  /// 滚到某条历史消息，并高亮 1.4 秒。
  ///
  /// `ListView.builder` 是懒构建的：目标项没渲染出来就没有 context 可 `ensureVisible`。
  /// 所以先在 `[0, maxScrollExtent]` 上做**二分**：跳到中点 → 这一帧渲染出来的下标区间
  /// 若包含目标就收敛，否则按目标在中点之上/之下砍半。单调性严格成立（下标随偏移单调），
  /// 一般 3~5 跳就能落到目标附近，比按「平均高度 × 下标」估算稳得多。
  Future<void> _jumpToHistoryMessage(String id) async {
    final int target = _snapshot.history.indexWhere(
      (ChatHistoryItem item) => item.id == id,
    );
    if (target < 0) {
      _notice('这条消息不在已加载范围内，先向上滚动加载更早的历史');
      return;
    }
    if (!_scroll.hasClients) return;
    double lo = 0;
    double hi = _scroll.position.maxScrollExtent;
    for (int step = 0; step < 16; step++) {
      if (_historyKeys[target]?.currentContext != null) break;
      final double mid = (lo + hi) / 2;
      if ((mid - _scroll.offset).abs() < 1 || hi - lo < 1) break;
      _scroll.jumpTo(mid.clamp(0, hi));
      await WidgetsBinding.instance.endOfFrame;
      final (int first, int last) = _renderedHistoryRange();
      if (first < 0) break;
      if (target < first) {
        hi = mid;
      } else if (target > last) {
        lo = mid;
      } else {
        break;
      }
    }
    final BuildContext? ctx = _historyKeys[target]?.currentContext;
    if (!mounted) return;
    if (ctx == null) {
      _notice('已跳到附近，继续向上滑动可以加载更早的历史');
      return;
    }
    if (!ctx.mounted) return;
    await Scrollable.ensureVisible(
      ctx,
      alignment: 0.12,
      duration: const Duration(milliseconds: 180),
    );
    if (!mounted) return;
    setState(() => _highlightId = id);
    await Future<void>.delayed(const Duration(milliseconds: 1400));
    if (!mounted || _highlightId != id) return;
    setState(() => _highlightId = '');
  }

  /// 当前这一帧里真正构建出来的历史下标区间；一条都没建出来时返回 `(-1, -1)`。
  (int, int) _renderedHistoryRange() {
    int first = -1;
    int last = -1;
    for (final MapEntry<int, GlobalKey> entry in _historyKeys.entries) {
      if (entry.value.currentContext == null) continue;
      if (first < 0 || entry.key < first) first = entry.key;
      if (entry.key > last) last = entry.key;
    }
    return (first, last);
  }

  /// 往输入框光标处插一段文本。
  ///
  /// `_composerHoldsTrigger` 为真时整段替换：斜杠和 @ 是「先打触发字符、
  /// 再选一项」，留着那个字符会变成 `//goal` 或 `@@src/a.ts`。
  void _insertComposerText(String text) {
    final bool replaceAll = _composerHoldsTrigger;
    _composerHoldsTrigger = false;
    _composer.value = insertIntoComposer(
      _composer.value,
      text,
      replaceAll: replaceAll,
    );
    _composerText = _composer.value.text;
    _composerFocus.requestFocus();
    setState(() {});
  }

  /// 输入框内容变化：顺手判断要不要叫出 `/` 或 `@` 面板。
  ///
  /// 只认「刚敲进去的那一个字符」，粘贴进来的 `/`、`@` 不弹（粘贴路径时
  /// 弹面板很烦）；Web 版也是这个规则。
  void _onComposerChanged(String text) {
    final String previous = _composerText;
    _composerText = text;
    // 这里**不能** setState：`_composerText` 只用来判断「刚打出来的是 / 还是 @」，
    // 不驱动任何界面元素。整页重建一次就是几十条消息的 build + 布局，
    // 打字时每敲一个字卡一下，就是这一行造成的。
    final String? trigger = composerTrigger(
      previous: previous,
      next: text,
      selection: _composer.selection,
    );
    if (trigger == null) return;
    _composerHoldsTrigger = true;
    sendChatAction(<String, Object?>{
      'type': trigger == '/' ? 'slash' : 'mention',
    });
  }

  /// 选择面板（模型 / 思考 / 安全 / 提示词 / 上下文）。
  ///
  /// 用内联面板而不是 `showModalBottomSheet`：面板内容是 WebView 异步推过来的，
  /// 弹层的开合状态跟着快照走很容易出现「关了又弹」。内联面板是纯函数式的 ——
  /// `picker == null` 就收起，没有中间状态。
  Widget _pickerPanel(PiColors c, ChatPickerView picker) {
    // 搜索框的内容只影响面板本身：用 ValueListenableBuilder 把重建范围锁在面板里，
    // 不要在 onChanged 里 setState（那会把整页聊天记录重建一遍）。
    return ValueListenableBuilder<TextEditingValue>(
      valueListenable: _pickerSearch,
      builder: (BuildContext context, TextEditingValue value, Widget? _) =>
          _pickerPanelBody(c, picker, value.text),
    );
  }

  Widget _pickerPanelBody(PiColors c, ChatPickerView picker, String rawQuery) {
    final String query = rawQuery.trim().toLowerCase();
    final List<ChatPickerItem> items = query.isEmpty
        ? picker.items
        : picker.items
              .where(
                (ChatPickerItem item) =>
                    item.label.toLowerCase().contains(query) ||
                    item.description.toLowerCase().contains(query),
              )
              .toList(growable: false);

    final List<Widget> rows = <Widget>[];
    String section = '';
    for (final ChatPickerItem item in items) {
      if (item.section.isNotEmpty && item.section != section) {
        section = item.section;
        rows.add(
          Padding(
            padding: const EdgeInsets.fromLTRB(14, 10, 14, 4),
            child: Text(
              section,
              style: TextStyle(fontSize: 11, color: c.textMuted),
            ),
          ),
        );
      }
      rows.add(
        InkWell(
          key: ValueKey<String>('picker:${picker.kind}:${item.id}'),
          onTap: () => _onPickerTap(picker, item),
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
            child: Row(
              children: <Widget>[
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: <Widget>[
                      Text(
                        item.label,
                        style: TextStyle(
                          fontSize: 13.5,
                          color: item.danger ? c.danger : c.text,
                          fontWeight: item.selected
                              ? FontWeight.w600
                              : FontWeight.w400,
                        ),
                      ),
                      if (item.description.isNotEmpty)
                        Padding(
                          padding: const EdgeInsets.only(top: 2),
                          child: Text(
                            item.description,
                            maxLines: 2,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              fontSize: 11.5,
                              color: c.textMuted,
                            ),
                          ),
                        ),
                    ],
                  ),
                ),
                if (item.selected)
                  Icon(Icons.check_rounded, size: 16, color: c.accent),
              ],
            ),
          ),
        ),
      );
    }

    return Container(
      decoration: BoxDecoration(
        color: c.surface,
        border: Border(top: BorderSide(color: c.divider)),
      ),
      child: SafeArea(
        top: false,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: <Widget>[
            Padding(
              padding: const EdgeInsets.fromLTRB(14, 8, 6, 0),
              child: Row(
                children: <Widget>[
                  Expanded(
                    child: Text(
                      picker.title,
                      style: TextStyle(
                        fontSize: 13.5,
                        fontWeight: FontWeight.w600,
                        color: c.text,
                      ),
                    ),
                  ),
                  IconButton(
                    tooltip: '关闭',
                    icon: Icon(
                      Icons.close_rounded,
                      size: 18,
                      color: c.textMuted,
                    ),
                    onPressed: () => sendChatAction(<String, Object?>{
                      'type': 'closePicker',
                    }),
                  ),
                ],
              ),
            ),
            if (picker.subtitle.isNotEmpty)
              Padding(
                padding: const EdgeInsets.fromLTRB(14, 0, 14, 6),
                child: Align(
                  alignment: Alignment.centerLeft,
                  child: Text(
                    picker.subtitle,
                    style: TextStyle(fontSize: 11, color: c.textMuted),
                  ),
                ),
              ),
            if (picker.searchable)
              Padding(
                padding: const EdgeInsets.fromLTRB(12, 0, 12, 6),
                child: TextField(
                  controller: _pickerSearch,
                  style: TextStyle(fontSize: 13, color: c.text),
                  decoration: InputDecoration(
                    isDense: true,
                    hintText: '搜索',
                    hintStyle: TextStyle(fontSize: 13, color: c.textMuted),
                    filled: true,
                    fillColor: c.surfaceAlt,
                    border: OutlineInputBorder(
                      borderRadius: BorderRadius.circular(10),
                      borderSide: BorderSide.none,
                    ),
                    contentPadding: const EdgeInsets.symmetric(
                      horizontal: 10,
                      vertical: 8,
                    ),
                  ),
                  onSubmitted: (String value) =>
                      sendChatAction(<String, Object?>{
                        'type': 'pickerQuery',
                        'kind': picker.kind,
                        'query': value,
                        // 原样带回：@ 提及要靠它留在当前目录
                        'context': picker.context,
                      }),
                ),
              ),
            if (rows.isEmpty)
              Padding(
                padding: const EdgeInsets.fromLTRB(14, 6, 14, 16),
                child: Align(
                  alignment: Alignment.centerLeft,
                  child: Text(
                    picker.empty.isEmpty ? '没有可选项' : picker.empty,
                    style: TextStyle(fontSize: 12, color: c.textMuted),
                  ),
                ),
              )
            else
              ConstrainedBox(
                constraints: BoxConstraints(
                  maxHeight: MediaQuery.of(context).size.height * 0.42,
                ),
                child: ListView(shrinkWrap: true, children: rows),
              ),
          ],
        ),
      ),
    );
  }

  Widget _composerBar(PiColors c, ChatSnapshot snapshot) {
    final bool running = snapshot.isRunning;
    final bool canSend = _composer.text.trim().isNotEmpty || _images.isNotEmpty;
    final ColorScheme scheme = Theme.of(context).colorScheme;
    return Material(
      color: scheme.surface,
      child: SafeArea(
        top: false,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: <Widget>[
            if (_images.isNotEmpty) _attachmentRow(c),
            Padding(
              padding: const EdgeInsets.fromLTRB(6, 8, 8, 8),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.end,
                children: <Widget>[
                  IconButton.filledTonal(
                    tooltip: '添加图片',
                    visualDensity: VisualDensity.compact,
                    icon: const Icon(
                      Icons.add_photo_alternate_outlined,
                      size: 20,
                    ),
                    onPressed: _images.length >= _maxImages ? null : _pickImage,
                  ),
                  if (snapshot.header.promptCount > 0) ...<Widget>[
                    const SizedBox(width: 4),
                    IconButton.filledTonal(
                      tooltip: '提示词模板',
                      visualDensity: VisualDensity.compact,
                      icon: const Icon(Icons.bolt_outlined, size: 20),
                      onPressed: () =>
                          sendChatAction(<String, Object?>{'type': 'prompts'}),
                    ),
                  ],
                  const SizedBox(width: 8),
                  Expanded(
                    child: TextField(
                      controller: _composer,
                      focusNode: _composerFocus,
                      minLines: 1,
                      maxLines: 6,
                      textInputAction: TextInputAction.newline,
                      style: TextStyle(color: c.text, fontSize: 15),
                      decoration: InputDecoration(
                        hintText: running ? '运行中，输入会排队' : '发消息…',
                        hintStyle: TextStyle(color: c.textMuted, fontSize: 14),
                        filled: true,
                        fillColor: scheme.surfaceContainerHighest,
                        isDense: true,
                        contentPadding: const EdgeInsets.symmetric(
                          horizontal: 18,
                          vertical: 12,
                        ),
                        border: OutlineInputBorder(
                          borderRadius: BorderRadius.circular(24),
                          borderSide: BorderSide.none,
                        ),
                        enabledBorder: OutlineInputBorder(
                          borderRadius: BorderRadius.circular(24),
                          borderSide: BorderSide.none,
                        ),
                        focusedBorder: OutlineInputBorder(
                          borderRadius: BorderRadius.circular(24),
                          borderSide: BorderSide(
                            color: scheme.primary,
                            width: 1.4,
                          ),
                        ),
                      ),
                      onChanged: _onComposerChanged,
                    ),
                  ),
                  const SizedBox(width: 8),
                  if (running)
                    IconButton.filledTonal(
                      tooltip: '停止',
                      style: IconButton.styleFrom(foregroundColor: c.danger),
                      icon: const Icon(Icons.stop_rounded),
                      onPressed: () =>
                          sendChatAction(<String, Object?>{'type': 'stop'}),
                    )
                  else
                    IconButton.filled(
                      tooltip: '发送',
                      icon: const Icon(Icons.arrow_upward_rounded),
                      onPressed: canSend ? _send : null,
                    ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  /// 待发送图片的缩略图行（与 WebView 版的 `.attachments` 同位置：输入框上方）。
  Widget _attachmentRow(PiColors c) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(12, 8, 12, 0),
      child: Wrap(
        spacing: 8,
        runSpacing: 8,
        children: <Widget>[
          for (int index = 0; index < _images.length; index++)
            Stack(
              clipBehavior: Clip.none,
              children: <Widget>[
                ClipRRect(
                  borderRadius: BorderRadius.circular(8),
                  child: Image.memory(
                    _images[index].bytesOf,
                    width: 56,
                    height: 56,
                    fit: BoxFit.cover,
                    gaplessPlayback: true,
                  ),
                ),
                Positioned(
                  top: -6,
                  right: -6,
                  child: GestureDetector(
                    onTap: () => setState(() => _images.removeAt(index)),
                    child: Container(
                      decoration: BoxDecoration(
                        color: c.surfaceAlt,
                        shape: BoxShape.circle,
                        border: Border.all(color: c.divider),
                      ),
                      padding: const EdgeInsets.all(2),
                      child: Icon(
                        Icons.close_rounded,
                        size: 14,
                        color: c.textSecondary,
                      ),
                    ),
                  ),
                ),
              ],
            ),
        ],
      ),
    );
  }
}

/* ------------------------------ 历史条目 ------------------------------ */

class _HistoryTile extends StatelessWidget {
  const _HistoryTile({
    super.key,
    required this.item,
    required this.onLongPress,
    this.highlighted = false,
  });

  final ChatHistoryItem item;
  final VoidCallback onLongPress;

  /// 从「历史」面板跳过来的那一条：淡一层底色，让人看清落点
  final bool highlighted;

  @override
  Widget build(BuildContext context) {
    final Widget body = _body(context);
    if (!highlighted) return body;
    final PiColors c = PiColors.of(context);
    return Container(
      decoration: BoxDecoration(
        color: c.accent.withValues(alpha: 0.10),
        borderRadius: BorderRadius.circular(12),
      ),
      child: body,
    );
  }

  Widget _body(BuildContext context) {
    final PiColors c = PiColors.of(context);
    // M3 的角色色（容器/其上文字）不在 PiColors 里 —— 它只管"语义"，角色色直接取 scheme。
    final ColorScheme scheme = Theme.of(context).colorScheme;

    if (item.isTool) {
      return _ToolCard(
        name: item.toolName.isEmpty ? 'tool' : item.toolName,
        state: item.toolState,
        detail: item.toolDetail,
        header: item.time,
      );
    }

    if (item.isUser) {
      // Material 3 的对话惯例：用户消息靠右、填 `primaryContainer`、圆角 20，
      // 右下角收一个小圆角表示"这条是出口"。助手消息不套气泡（正文里还有
      // 代码块、表格、工具卡，套一层框就是框套框）。
      return Align(
        alignment: Alignment.centerRight,
        child: GestureDetector(
          onLongPress: onLongPress,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: <Widget>[
              ConstrainedBox(
                constraints: BoxConstraints(
                  maxWidth: MediaQuery.sizeOf(context).width * 0.82,
                ),
                child: Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 16,
                    vertical: 12,
                  ),
                  decoration: BoxDecoration(
                    color: scheme.primaryContainer,
                    borderRadius: const BorderRadius.only(
                      topLeft: Radius.circular(20),
                      topRight: Radius.circular(20),
                      bottomLeft: Radius.circular(20),
                      bottomRight: Radius.circular(6),
                    ),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: <Widget>[
                      if (item.images.isNotEmpty) _images(context, item.images),
                      if (item.text.isNotEmpty)
                        MarkdownView(
                          source: item.text,
                          compact: true,
                          baseStyle: TextStyle(
                            color: scheme.onPrimaryContainer,
                            fontSize: 15,
                          ),
                        ),
                    ],
                  ),
                ),
              ),
              _timeLabel(context, c, item.time, align: CrossAxisAlignment.end),
            ],
          ),
        ),
      );
    }

    // 助手消息：整宽正文 + 顶部一个极小的时间行（M3 里靠排版分层，不靠边框）。
    return GestureDetector(
      onLongPress: onLongPress,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          if (item.thinking.isNotEmpty) _ThinkingBlock(text: item.thinking),
          if (item.text.isNotEmpty) MarkdownView(source: item.text),
          if (item.images.isNotEmpty) _images(context, item.images),
          _timeLabel(context, c, item.time),
        ],
      ),
    );
  }

  /// 时间行：气泡外的一行小字（M3 的 `labelSmall`，比正文低两级的层级）。
  Widget _timeLabel(
    BuildContext context,
    PiColors c,
    String time, {
    CrossAxisAlignment align = CrossAxisAlignment.start,
  }) {
    if (time.isEmpty) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.only(left: 6, right: 6, top: 6, bottom: 12),
      child: Text(
        time,
        style: Theme.of(context).textTheme.labelSmall
            ?.copyWith(color: c.textMuted),
      ),
    );
  }

  Widget _images(BuildContext context, List<String> images) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 6),
      child: Wrap(
        spacing: 6,
        runSpacing: 6,
        children: <Widget>[
          for (int index = 0; index < images.length; index++)
            _ChatImage(
              source: images[index],
              // 点开的是全屏看图，可以在这条消息的所有图之间左右翻
              onTap: () => openImageViewer(context, images, index),
            ),
        ],
      ),
    );
  }
}

/// 图片：`data:` 走内存解码，其余当 URL 拉。两路都不落盘。
/// 缩略图边长：照抄 Web 的 `.msg-image`（128×128，object-fit: cover）。
const double _thumb = 128;

/// `data:` 图片的解码缓存（FIFO 24 条）。
///
/// base64 解码 + 解码成位图都不便宜（一张 1600px 的图有几百 KB 的 base64）。
/// `_ChatImage` 每次重建都会重新走一遍代码路径，而快照是流式刷新的 ——
/// 一条带图的消息会让整段流式输出都在卡。字节缓存在这里、位图缓存交给 Flutter 自己。
final Map<String, Uint8List> _imageBytesCache = <String, Uint8List>{};

Uint8List? _decodeDataUrl(String source) {
  final Uint8List? hit = _imageBytesCache[source];
  if (hit != null) return hit;
  final int comma = source.indexOf(',');
  if (comma < 0) return null;
  try {
    final Uint8List bytes = base64Decode(source.substring(comma + 1));
    if (_imageBytesCache.length >= 24) {
      _imageBytesCache.remove(_imageBytesCache.keys.first);
    }
    _imageBytesCache[source] = bytes;
    return bytes;
  } on FormatException {
    return null;
  }
}

class _ChatImage extends StatelessWidget {
  const _ChatImage({required this.source, this.onTap});

  final String source;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final PiColors c = PiColors.of(context);
    Widget child;
    if (source.startsWith('data:')) {
      final Uint8List? bytes = _decodeDataUrl(source);
      child = bytes == null
          ? SizedBox(width: _thumb, height: _thumb)
          // cacheWidth：原图可能 1600px，缩略图只要 128 逻辑像素 —— 按 3 倍解码足够，
          // 不传的话每次重建都要从整张位图缩一遍
          : Image.memory(
              bytes,
              width: _thumb,
              height: _thumb,
              fit: BoxFit.cover,
              cacheWidth: (_thumb * 3).round(),
            );
    } else if (source.startsWith('http')) {
      child = Image.network(
        source,
        width: _thumb,
        height: _thumb,
        fit: BoxFit.cover,
        cacheWidth: (_thumb * 3).round(),
        errorBuilder: (BuildContext context, Object error, StackTrace? stack) =>
            SizedBox(
              width: _thumb,
              height: _thumb,
              child: Center(
                child: Icon(Icons.broken_image_outlined, color: c.textMuted),
              ),
            ),
      );
    } else {
      child = SizedBox(width: _thumb, height: _thumb);
    }
    final Widget clipped = Container(
      decoration: BoxDecoration(
        color: c.surface,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: c.divider),
      ),
      child: ClipRRect(borderRadius: BorderRadius.circular(9), child: child),
    );
    if (onTap == null) return clipped;
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: onTap,
      child: clipped,
    );
  }
}

class _ThinkingBlock extends StatefulWidget {
  const _ThinkingBlock({required this.text});

  final String text;

  @override
  State<_ThinkingBlock> createState() => _ThinkingBlockState();
}

class _ThinkingBlockState extends State<_ThinkingBlock> {
  bool _open = false;

  @override
  Widget build(BuildContext context) {
    final PiColors c = PiColors.of(context);
    final String firstLine = widget.text.split('\n').first;
    return Padding(
      padding: const EdgeInsets.only(bottom: 6),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          InkWell(
            onTap: () => setState(() => _open = !_open),
            child: Row(
              children: <Widget>[
                Icon(
                  _open ? Icons.expand_less_rounded : Icons.expand_more_rounded,
                  size: 14,
                  color: c.textMuted,
                ),
                const SizedBox(width: 2),
                Text(
                  '思考过程',
                  style: TextStyle(fontSize: 11, color: c.textMuted),
                ),
                if (!_open)
                  Expanded(
                    child: Padding(
                      padding: const EdgeInsets.only(left: 6),
                      child: Text(
                        firstLine,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          fontSize: 11,
                          color: c.textMuted.withValues(alpha: 0.7),
                        ),
                      ),
                    ),
                  ),
              ],
            ),
          ),
          if (_open)
            Container(
              margin: const EdgeInsets.only(top: 4),
              padding: const EdgeInsets.only(left: 8),
              decoration: BoxDecoration(
                border: Border(left: BorderSide(color: c.divider, width: 2)),
              ),
              child: MarkdownView(
                source: widget.text,
                baseStyle: TextStyle(
                  color: c.textSecondary,
                  fontSize: 12.5,
                  fontStyle: FontStyle.italic,
                ),
              ),
            ),
        ],
      ),
    );
  }
}

/* ------------------------------ 流式块 ------------------------------ */

class _BlockTile extends StatelessWidget {
  const _BlockTile({super.key, required this.block});

  final ChatBlockView block;

  @override
  Widget build(BuildContext context) {
    final PiColors c = PiColors.of(context);
    if (block.isTool) {
      return _ToolCard(
        name: block.toolName,
        state: block.state,
        detail: block.errorText.isNotEmpty
            ? block.errorText
            : previewOf(block.output ?? block.input),
      );
    }
    if (block.isReasoning) {
      return Padding(
        padding: const EdgeInsets.only(top: 4, bottom: 4),
        child: Container(
          padding: const EdgeInsets.only(left: 8),
          decoration: BoxDecoration(
            border: Border(left: BorderSide(color: c.divider, width: 2)),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              Text(
                block.done ? '已思考' : '思考中…',
                style: TextStyle(fontSize: 10.5, color: c.textMuted),
              ),
              MarkdownView(
                source: block.text,
                baseStyle: TextStyle(
                  color: c.textSecondary,
                  fontSize: 12.5,
                  fontStyle: FontStyle.italic,
                ),
              ),
            ],
          ),
        ),
      );
    }
    // 正文：`done` 之前末尾挂一个闪烁光标，让用户看出还在流
    return Padding(
      padding: const EdgeInsets.only(top: 2, bottom: 2),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          MarkdownView(source: block.text),
          if (!block.done)
            Padding(
              padding: const EdgeInsets.only(top: 2),
              child: Container(
                width: 7,
                height: 14,
                color: c.accent.withValues(alpha: 0.7),
              ),
            ),
        ],
      ),
    );
  }
}

class _ToolCard extends StatefulWidget {
  const _ToolCard({
    required this.name,
    required this.state,
    required this.detail,
    this.header = '',
  });

  final String name;
  final String state;
  final String detail;
  final String header;

  @override
  State<_ToolCard> createState() => _ToolCardState();
}

class _ToolCardState extends State<_ToolCard> {
  bool _open = false;

  @override
  Widget build(BuildContext context) {
    final PiColors c = PiColors.of(context);
    final (IconData icon, Color color) = switch (widget.state) {
      'ok' => (Icons.check_circle_outline_rounded, c.textMuted),
      'error' => (Icons.error_outline_rounded, c.danger),
      _ => (Icons.autorenew_rounded, c.accent),
    };
    final String firstLine = widget.detail.split('\n').first;
    return Container(
      margin: const EdgeInsets.symmetric(vertical: 6),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(12),
        color: Theme.of(context).colorScheme.surfaceContainerLow,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          InkWell(
            onTap: widget.detail.isEmpty
                ? null
                : () => setState(() => _open = !_open),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
              child: Row(
                children: <Widget>[
                  Icon(icon, size: 16, color: color),
                  const SizedBox(width: 8),
                  Text(
                    widget.name,
                    style: TextStyle(
                      fontSize: 12.5,
                      color: c.text,
                      fontFamily: 'monospace',
                    ),
                  ),
                  if (!_open && firstLine.isNotEmpty)
                    Expanded(
                      child: Padding(
                        padding: const EdgeInsets.only(left: 8),
                        child: Text(
                          firstLine,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(fontSize: 11, color: c.textMuted),
                        ),
                      ),
                    ),
                  if (widget.detail.isNotEmpty)
                    Icon(
                      _open
                          ? Icons.expand_less_rounded
                          : Icons.expand_more_rounded,
                      size: 16,
                      color: c.textMuted,
                    ),
                ],
              ),
            ),
          ),
          if (_open)
            Container(
              width: double.infinity,
              padding: const EdgeInsets.fromLTRB(10, 0, 10, 8),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: <Widget>[
                  SingleChildScrollView(
                    scrollDirection: Axis.horizontal,
                    child: SelectableText(
                      widget.detail,
                      style: TextStyle(
                        fontSize: 11.5,
                        fontFamily: 'monospace',
                        height: 1.4,
                        color: c.textSecondary,
                      ),
                    ),
                  ),
                  Row(
                    mainAxisAlignment: MainAxisAlignment.end,
                    children: <Widget>[
                      _MiniIconButton(
                        icon: Icons.copy_rounded,
                        tooltip: '复制',
                        onTap: () {
                          Clipboard.setData(ClipboardData(text: widget.detail));
                          _notice(context, '已复制');
                        },
                      ),
                      _MiniIconButton(
                        icon: Icons.save_alt_rounded,
                        tooltip: '保存到手机',
                        onTap: () => _saveOutput(context),
                      ),
                    ],
                  ),
                ],
              ),
            ),
        ],
      ),
    );
  }

  /// 工具输出动辄几十 KB，给个落盘入口，不用长按选中再复制。
  Future<void> _saveOutput(BuildContext context) async {
    final ScaffoldMessengerState messenger = ScaffoldMessenger.of(context);
    final DateTime now = DateTime.now();
    String two(int value) => value.toString().padLeft(2, '0');
    final String stamp =
        '${now.year}${two(now.month)}${two(now.day)}-${two(now.hour)}${two(now.minute)}${two(now.second)}';
    final String safeName = widget.name.replaceAll(
      RegExp(r'[^A-Za-z0-9_.-]'),
      '_',
    );
    final String? location = await saveTextFile(
      name: 'tool-$safeName-$stamp.txt',
      text: widget.detail,
    );
    messenger.showSnackBar(
      SnackBar(
        content: Text(location == null ? '保存失败' : '已保存到 $location'),
        duration: const Duration(seconds: 3),
      ),
    );
  }

  void _notice(BuildContext context, String message) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(message),
        duration: const Duration(milliseconds: 900),
      ),
    );
  }
}

/// 工具卡右下角那种 22px 的小图标按钮。
class _MiniIconButton extends StatelessWidget {
  const _MiniIconButton({
    required this.icon,
    required this.tooltip,
    required this.onTap,
  });

  final IconData icon;
  final String tooltip;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final PiColors c = PiColors.of(context);
    return IconButton(
      visualDensity: VisualDensity.compact,
      iconSize: 15,
      tooltip: tooltip,
      color: c.textMuted,
      icon: Icon(icon),
      onPressed: onTap,
    );
  }
}

String _fmtDuration(int ms) {
  if (ms < 1000) return '${ms}ms';
  return '${(ms / 1000).toStringAsFixed(2)}s';
}
