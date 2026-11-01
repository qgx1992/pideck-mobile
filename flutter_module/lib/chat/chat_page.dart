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

import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../app_theme.dart';
import '../host_channel.dart';
import '../sidebar/sidebar_drawer.dart';
import '../sidebar/sidebar_models.dart';
import 'chat_models.dart';
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
  final ScrollController _scroll = ScrollController();
  final TextEditingController _composer = TextEditingController();
  final FocusNode _composerFocus = FocusNode();

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
      decoded = raw.map((Object? key, Object? value) => MapEntry<String, Object?>(str(key), value));
    } catch (_) {
      return;
    }
    final ChatSnapshot next = ChatSnapshot.fromJson(decoded);
    if (next.sessionId.isEmpty) return;
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
    final String text = _composer.text.trim();
    if (text.isEmpty) return;
    _composer.clear();
    _composerFocus.unfocus();
    setState(() => _stick = true);
    await sendChatAction(<String, Object?>{'type': 'send', 'text': text});
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

  void _showMessageMenu(ChatHistoryItem item) {
    if (item.text.isEmpty) return;
    showModalBottomSheet<void>(
      context: context,
      backgroundColor: PiColors.of(context).surface,
      builder: (BuildContext sheetContext) {
        final PiColors c = PiColors.of(sheetContext);
        return SafeArea(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: <Widget>[
              ListTile(
                leading: Icon(Icons.copy_rounded, color: c.text),
                title: Text('复制', style: TextStyle(color: c.text)),
                onTap: () {
                  Clipboard.setData(ClipboardData(text: item.text));
                  Navigator.of(sheetContext).pop();
                },
              ),
              ListTile(
                leading: Icon(Icons.more_horiz_rounded, color: c.text),
                title: Text('更多操作（回 Web 版）', style: TextStyle(color: c.text)),
                subtitle: Text(
                  '编辑 / 重发 / 分叉 / 删除仍在 WebView 里',
                  style: TextStyle(color: c.textMuted, fontSize: 12),
                ),
                onTap: () async {
                  Navigator.of(sheetContext).pop();
                  // 那边会弹自己的底部菜单；本页让开才能看见它
                  await sendChatAction(<String, Object?>{
                    'type': 'messageAction',
                    'id': item.id,
                    'role': item.role,
                  });
                  await closeHost();
                },
              ),
            ],
          ),
        );
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    final PiColors c = PiColors.of(context);
    final ChatSnapshot snapshot = _snapshot;
    final ChatLive? live = snapshot.live;
    final int historyCount = snapshot.history.length;
    final int liveCount = live?.blocks.length ?? 0;

    return Scaffold(
      key: _scaffoldKey,
      backgroundColor: c.background,
      drawer: SidebarDrawer(
        snapshot: _sidebar,
        onAction: sendSidebarAction,
        onClose: () => _scaffoldKey.currentState?.closeDrawer(),
      ),
      appBar: AppBar(
        backgroundColor: c.background,
        elevation: 0,
        titleSpacing: 0,
        leading: IconButton(
          tooltip: '会话列表',
          icon: Icon(Icons.menu_rounded, color: c.text),
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
              style: TextStyle(fontSize: 15, fontWeight: FontWeight.w600, color: c.text),
            ),
            if (snapshot.isRunning)
              Text(
                snapshot.statusText.isEmpty ? '运行中…' : snapshot.statusText,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(fontSize: 11.5, color: c.accent),
              ),
          ],
        ),
        actions: <Widget>[
          IconButton(
            tooltip: '刷新',
            icon: Icon(Icons.refresh_rounded, color: c.textMuted),
            onPressed: () => sendChatAction(<String, Object?>{'type': 'refresh'}),
          ),
          IconButton(
            tooltip: '返回网页版',
            icon: Icon(Icons.open_in_browser_rounded, color: c.textMuted),
            onPressed: () => closeHost(),
          ),
        ],
      ),
      body: Column(
        children: <Widget>[
          Expanded(
            child: Stack(
              children: <Widget>[
                ListView.builder(
                  controller: _scroll,
                  padding: const EdgeInsets.fromLTRB(12, 8, 12, 12),
                  itemCount: historyCount + liveCount + 1,
                  itemBuilder: (BuildContext context, int index) {
                    if (index < historyCount) {
                      return _HistoryTile(
                        key: ValueKey<String>('h:${snapshot.history[index].id}:$index'),
                        item: snapshot.history[index],
                        onLongPress: () => _showMessageMenu(snapshot.history[index]),
                      );
                    }
                    final int blockIndex = index - historyCount;
                    if (live != null && blockIndex < live.blocks.length) {
                      final ChatBlockView block = live.blocks[blockIndex];
                      return _BlockTile(key: ValueKey<String>('b:${block.key}'), block: block);
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
          _composerBar(c, snapshot),
        ],
      ),
    );
  }

  Widget _footer(BuildContext context, PiColors c, ChatSnapshot snapshot) {
    final List<Widget> children = <Widget>[];
    if (snapshot.loading && !snapshot.hasContent) {
      children.add(Padding(
        padding: const EdgeInsets.symmetric(vertical: 24),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: <Widget>[
            SizedBox(
              width: 14,
              height: 14,
              child: CircularProgressIndicator(strokeWidth: 2, color: c.accent),
            ),
            const SizedBox(width: 8),
            Text('加载中…', style: TextStyle(color: c.textMuted, fontSize: 13)),
          ],
        ),
      ));
    }
    if (snapshot.error != null) {
      children.add(_errorCard(c, snapshot.error!));
    }
    if (snapshot.empty != null && !snapshot.hasContent) {
      children.add(Padding(
        padding: const EdgeInsets.symmetric(vertical: 32),
        child: Center(
          child: Text(
            snapshot.empty!,
            textAlign: TextAlign.center,
            style: TextStyle(color: c.textMuted, fontSize: 13, height: 1.5),
          ),
        ),
      ));
    }
    if (snapshot.pending != null) {
      children.add(_pendingCard(context, c, snapshot.pending!));
    }
    final ChatPerf? perf = snapshot.perf;
    if (perf != null) {
      final List<String> chips = <String>[
        if (perf.ttftMs != null) '首字 ${_fmtDuration(perf.ttftMs!)}',
        if (perf.streamingTps != null) '${perf.streamingTps!.toStringAsFixed(1)} tok/s',
        if (perf.averageTps != null) '均 ${perf.averageTps!.toStringAsFixed(1)} tok/s',
        if (perf.outputTokens != null) '${perf.outputTokens} tokens',
      ];
      if (chips.isNotEmpty) {
        children.add(Padding(
          padding: const EdgeInsets.only(top: 4, bottom: 4),
          child: Wrap(
            spacing: 6,
            runSpacing: 4,
            children: chips
                .map((String text) => Text(text, style: TextStyle(fontSize: 11, color: c.textMuted)))
                .toList(growable: false),
          ),
        ));
      }
    }
    if (children.isEmpty) return const SizedBox(height: 4);
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: children);
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
            style: TextStyle(color: c.danger, fontWeight: FontWeight.w600, fontSize: 13.5),
          ),
          if (error.detail.isNotEmpty) ...<Widget>[
            const SizedBox(height: 4),
            SelectableText(error.detail, style: TextStyle(color: c.textMuted, fontSize: 11.5)),
          ],
          if (error.hint.isNotEmpty) ...<Widget>[
            const SizedBox(height: 6),
            Text(error.hint, style: TextStyle(color: c.textSecondary, fontSize: 12, height: 1.45)),
          ],
          const SizedBox(height: 8),
          Align(
            alignment: Alignment.centerRight,
            child: TextButton(
              onPressed: () => sendChatAction(<String, Object?>{'type': 'refresh'}),
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
    final String method = pending.method == 'select' && options.isEmpty ? 'input' : pending.method;

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
              onPressed: () => _respond(<String, Object?>{'value': _pendingDraft.trim()}),
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
          Text('AI 提问', style: TextStyle(fontSize: 10.5, letterSpacing: 0.6, color: c.accent)),
          const SizedBox(height: 4),
          Text(
            pending.title.isEmpty ? '需要你的确认' : pending.title,
            style: TextStyle(color: c.text, fontSize: 14, fontWeight: FontWeight.w600),
          ),
          const SizedBox(height: 10),
          body,
          const SizedBox(height: 6),
          Align(
            alignment: Alignment.centerLeft,
            child: TextButton(
              onPressed: () => _respond(<String, Object?>{'cancelled': true}),
              child: Text('取消', style: TextStyle(color: c.textMuted, fontSize: 12.5)),
            ),
          ),
        ],
      ),
    );
  }

  Widget _selectBody(BuildContext context, PiColors c, ChatPending pending, List<ChatBatchOption> options) {
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
                  padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                ),
                onPressed: () => _respond(<String, Object?>{'value': option.value}),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: <Widget>[
                    Text(option.label, style: TextStyle(color: c.text, fontSize: 13.5)),
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
                      color: selected.contains(option.value) ? c.accent : c.divider,
                    ),
                    foregroundColor: c.text,
                    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
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
                        color: selected.contains(option.value) ? c.accent : c.textMuted,
                      ),
                      const SizedBox(width: 8),
                      Expanded(child: Text(option.label, style: TextStyle(color: c.text, fontSize: 13.5))),
                    ],
                  ),
                ),
              ),
            Align(
              alignment: Alignment.centerRight,
              child: FilledButton(
                onPressed: selected.isEmpty
                    ? null
                    : () => _respond(<String, Object?>{'value': selected.join('、')}),
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
    final int answered = questions.where((ChatBatchQuestion q) => _batchAnswers.containsKey(q.id)).length;

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
                      Text(question.question, style: TextStyle(fontSize: 12.5, color: c.textSecondary)),
                      Text(
                        _batchAnswers.containsKey(question.id)
                            ? (batchAnswerLabel(_batchAnswers[question.id]).isEmpty
                                ? '（留空）'
                                : batchAnswerLabel(_batchAnswers[question.id]))
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
                            final String payload = batchAnswerPayload(questions, _batchAnswers);
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
                        style: TextStyle(fontSize: 10.5, color: c.accent, letterSpacing: 0.5),
                      ),
                    Text(
                      question.question,
                      style: TextStyle(fontSize: 13, color: c.text, fontWeight: FontWeight.w500),
                    ),
                    const SizedBox(height: 6),
                    if (question.kind == 'confirm')
                      Row(
                        children: <Widget>[
                          for (final bool answer in <bool>[true, false])
                            Padding(
                              padding: const EdgeInsets.only(right: 8),
                              child: OutlinedButton(
                                onPressed: () => setLocal(() => _batchAnswers[question.id] = answer),
                                child: Text(answer ? '是' : '否'),
                              ),
                            ),
                        ],
                      )
                    else if (question.options.isEmpty)
                      TextField(
                        style: TextStyle(color: c.text, fontSize: 13),
                        decoration: InputDecoration(
                          hintText: question.placeholder.isEmpty ? '输入回答…' : question.placeholder,
                          hintStyle: TextStyle(color: c.textMuted, fontSize: 12.5),
                          filled: true,
                          fillColor: c.surfaceAlt,
                          isDense: true,
                          border: OutlineInputBorder(
                            borderRadius: BorderRadius.circular(8),
                            borderSide: BorderSide.none,
                          ),
                        ),
                        onChanged: (String value) => _batchAnswers[question.id] = value,
                      )
                    else
                      Wrap(
                        spacing: 6,
                        runSpacing: 6,
                        children: question.options.map((ChatBatchOption option) {
                          final Object? current = _batchAnswers[question.id];
                          final bool picked = question.multiple
                              ? (current is List && current.contains(option.value))
                              : current == option.value;
                          return ChoiceChip(
                            label: Text(option.label, style: const TextStyle(fontSize: 12.5)),
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
                        }).toList(growable: false),
                      ),
                  ],
                ),
              ),
            Align(
              alignment: Alignment.centerRight,
              child: OutlinedButton(
                onPressed: answered == 0 ? null : () => setLocal(() => _batchReview = true),
                child: const Text('审阅并提交'),
              ),
            ),
          ],
        );
      },
    );
  }

  /* ----------------------------- 输入栏 ----------------------------- */

  Widget _composerBar(PiColors c, ChatSnapshot snapshot) {
    final bool running = snapshot.isRunning;
    return Container(
      decoration: BoxDecoration(
        color: c.background,
        border: Border(top: BorderSide(color: c.divider)),
      ),
      child: SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(12, 8, 8, 8),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: <Widget>[
              Expanded(
                child: TextField(
                  controller: _composer,
                  focusNode: _composerFocus,
                  minLines: 1,
                  maxLines: 6,
                  textInputAction: TextInputAction.newline,
                  style: TextStyle(color: c.text, fontSize: 14.5),
                  decoration: InputDecoration(
                    hintText: running ? '运行中，可继续输入（会排队）' : '发消息…',
                    hintStyle: TextStyle(color: c.textMuted, fontSize: 13.5),
                    filled: true,
                    fillColor: c.surface,
                    isDense: true,
                    contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                    border: OutlineInputBorder(
                      borderRadius: BorderRadius.circular(20),
                      borderSide: BorderSide(color: c.divider),
                    ),
                    enabledBorder: OutlineInputBorder(
                      borderRadius: BorderRadius.circular(20),
                      borderSide: BorderSide(color: c.divider),
                    ),
                  ),
                  onChanged: (String _) => setState(() {}),
                ),
              ),
              const SizedBox(width: 6),
              if (running)
                IconButton.filled(
                  tooltip: '停止',
                  style: IconButton.styleFrom(backgroundColor: c.surfaceAlt, foregroundColor: c.danger),
                  icon: const Icon(Icons.stop_rounded),
                  onPressed: () => sendChatAction(<String, Object?>{'type': 'stop'}),
                )
              else
                IconButton.filled(
                  tooltip: '发送',
                  style: IconButton.styleFrom(
                    backgroundColor: _composer.text.trim().isEmpty ? c.surfaceAlt : c.accent,
                    foregroundColor: _composer.text.trim().isEmpty ? c.textMuted : Colors.white,
                  ),
                  icon: const Icon(Icons.arrow_upward_rounded),
                  onPressed: _composer.text.trim().isEmpty ? null : _send,
                ),
            ],
          ),
        ),
      ),
    );
  }
}

/* ------------------------------ 历史条目 ------------------------------ */

class _HistoryTile extends StatelessWidget {
  const _HistoryTile({super.key, required this.item, required this.onLongPress});

  final ChatHistoryItem item;
  final VoidCallback onLongPress;

  @override
  Widget build(BuildContext context) {
    final PiColors c = PiColors.of(context);

    if (item.isTool) {
      return _ToolCard(
        name: item.toolName.isEmpty ? 'tool' : item.toolName,
        state: item.toolState,
        detail: item.toolDetail,
        header: item.time,
      );
    }

    if (item.isUser) {
      return Align(
        alignment: Alignment.centerRight,
        child: GestureDetector(
          onLongPress: onLongPress,
          child: Container(
            constraints: BoxConstraints(maxWidth: MediaQuery.sizeOf(context).width * 0.82),
            margin: const EdgeInsets.only(top: 6, bottom: 6),
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 9),
            decoration: BoxDecoration(
              color: c.accent.withValues(alpha: 0.16),
              borderRadius: BorderRadius.circular(14),
              border: Border.all(color: c.accent.withValues(alpha: 0.3)),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                if (item.images.isNotEmpty) _images(context, item.images),
                if (item.text.isNotEmpty)
                  MarkdownView(source: item.text, compact: true, baseStyle: TextStyle(color: c.text, fontSize: 14.5)),
                if (item.time.isNotEmpty)
                  Padding(
                    padding: const EdgeInsets.only(top: 4),
                    child: Text(item.time, style: TextStyle(fontSize: 10.5, color: c.textMuted)),
                  ),
              ],
            ),
          ),
        ),
      );
    }

    return GestureDetector(
      onLongPress: onLongPress,
      child: Container(
        margin: const EdgeInsets.only(top: 6, bottom: 6),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            if (item.thinking.isNotEmpty) _ThinkingBlock(text: item.thinking),
            if (item.text.isNotEmpty) MarkdownView(source: item.text),
            if (item.images.isNotEmpty) _images(context, item.images),
            if (item.time.isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(top: 2),
                child: Text(item.time, style: TextStyle(fontSize: 10.5, color: c.textMuted)),
              ),
          ],
        ),
      ),
    );
  }

  Widget _images(BuildContext context, List<String> images) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 6),
      child: Wrap(
        spacing: 6,
        runSpacing: 6,
        children: images.map((String source) => _ChatImage(source: source)).toList(growable: false),
      ),
    );
  }
}

/// 图片：`data:` 走内存解码，其余当 URL 拉。两路都不落盘。
class _ChatImage extends StatelessWidget {
  const _ChatImage({required this.source});

  final String source;

  @override
  Widget build(BuildContext context) {
    final PiColors c = PiColors.of(context);
    Widget child;
    if (source.startsWith('data:')) {
      final int comma = source.indexOf(',');
      try {
        final Uint8List bytes = base64Decode(source.substring(comma + 1));
        child = Image.memory(bytes, width: 140, fit: BoxFit.cover);
      } catch (_) {
        child = const SizedBox(width: 140, height: 60);
      }
    } else if (source.startsWith('http')) {
      child = Image.network(
        source,
        width: 140,
        fit: BoxFit.cover,
        errorBuilder: (BuildContext context, Object error, StackTrace? stack) =>
            SizedBox(width: 140, height: 60, child: Center(child: Icon(Icons.broken_image_outlined, color: c.textMuted))),
      );
    } else {
      child = const SizedBox(width: 140, height: 60);
    }
    return ClipRRect(borderRadius: BorderRadius.circular(8), child: child);
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
                Text('思考过程', style: TextStyle(fontSize: 11, color: c.textMuted)),
                if (!_open)
                  Expanded(
                    child: Padding(
                      padding: const EdgeInsets.only(left: 6),
                      child: Text(
                        firstLine,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(fontSize: 11, color: c.textMuted.withValues(alpha: 0.7)),
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
                baseStyle: TextStyle(color: c.textSecondary, fontSize: 12.5, fontStyle: FontStyle.italic),
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
        detail: block.errorText.isNotEmpty ? block.errorText : previewOf(block.output ?? block.input),
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
                baseStyle: TextStyle(color: c.textSecondary, fontSize: 12.5, fontStyle: FontStyle.italic),
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
              child: Container(width: 7, height: 14, color: c.accent.withValues(alpha: 0.7)),
            ),
        ],
      ),
    );
  }
}

class _ToolCard extends StatefulWidget {
  const _ToolCard({required this.name, required this.state, required this.detail, this.header = ''});

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
      margin: const EdgeInsets.symmetric(vertical: 4),
      decoration: BoxDecoration(
        color: c.surface,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: c.divider),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          InkWell(
            onTap: widget.detail.isEmpty ? null : () => setState(() => _open = !_open),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
              child: Row(
                children: <Widget>[
                  Icon(icon, size: 14, color: color),
                  const SizedBox(width: 6),
                  Text(
                    widget.name,
                    style: TextStyle(fontSize: 12, color: c.text, fontFamily: 'monospace'),
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
                      _open ? Icons.expand_less_rounded : Icons.expand_more_rounded,
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
              child: SingleChildScrollView(
                scrollDirection: Axis.horizontal,
                child: SelectableText(
                  widget.detail,
                  style: TextStyle(fontSize: 11.5, fontFamily: 'monospace', height: 1.4, color: c.textSecondary),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

String _fmtDuration(int ms) {
  if (ms < 1000) return '${ms}ms';
  return '${(ms / 1000).toStringAsFixed(2)}s';
}
