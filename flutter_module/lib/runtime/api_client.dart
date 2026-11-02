/// PiDeck 服务端 HTTP 客户端（Dart 版）。
///
/// 对应 TS 侧的 `src/api/client.ts`：路径、query、请求头、错误分类都按那份实现抄，
/// 因为它们已经在真机上跑过很久，语义（401→auth、409→runtime、GET 走 `?token=`、
/// 非 GET 走 `Authorization`）跟着服务端实现走，不能凭手感改。
///
/// 与 WebView 的差别只有两点：
///   1. 没有 CORS，理论上可以到处带 `Authorization`，但这里刻意保持与 TS 一致
///      （GET 用 query token），少一份「原生能跑、网页不能跑」的分叉；
///   2. 没有 `fetch` 的 signal，取消靠「订阅取消 → 关掉 HttpClient」。
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'frames.dart';
import 'prompt_payload.dart';
import 'sse.dart';

/// 错误分类，与 `src/api/errors.ts` 的 `PiDeckErrorKind` 一一对应。
enum PiDeckErrorKind { auth, offline, runtime, http, protocol }

class PiDeckException implements Exception {
  PiDeckException(
    this.kind,
    this.message, {
    this.status,
    this.code,
    this.details,
    this.cause,
  });

  final PiDeckErrorKind kind;
  final String message;
  final int? status;
  final String? code;
  final Map<String, Object?>? details;
  final Object? cause;

  bool get isAuth => kind == PiDeckErrorKind.auth;
  bool get isOffline => kind == PiDeckErrorKind.offline;
  bool get isStale => kind == PiDeckErrorKind.runtime;

  /// 给 UI 看的一句话（`describeError` 的等价物）。
  String describe() {
    switch (kind) {
      case PiDeckErrorKind.auth:
        return '配对凭据已失效，请重新配对';
      case PiDeckErrorKind.offline:
        return '连接中断：$message';
      case PiDeckErrorKind.runtime:
        return '运行时已重启，正在恢复';
      case PiDeckErrorKind.http:
        return message;
      case PiDeckErrorKind.protocol:
        return '响应格式异常：$message';
    }
  }

  @override
  String toString() => 'PiDeckException(${kind.name}${status == null ? '' : ' $status'}): $message';
}

/// `/api/sessions/:id/messages` 的窗口。
class MessageWindow {
  const MessageWindow({
    required this.messages,
    this.total = 0,
    this.windowStart = 0,
    this.truncated = false,
  });

  final List<Object?> messages;
  final int total;
  final int windowStart;
  final bool truncated;

  static MessageWindow fromJson(Object? raw) {
    if (raw is! Map) return const MessageWindow(messages: <Object?>[]);
    final Object? list = raw['messages'];
    return MessageWindow(
      messages: list is List ? List<Object?>.from(list) : <Object?>[],
      total: raw['total'] is int ? raw['total'] as int : 0,
      windowStart: raw['windowStart'] is int ? raw['windowStart'] as int : 0,
      truncated: raw['truncated'] == true,
    );
  }
}

/// `/api/state` 的返回：state + etag（304 时不带 state 正文）。
class StateSnapshot {
  const StateSnapshot({this.json, this.etag, this.notModified = false});

  final Map<String, Object?>? json;
  final String? etag;
  final bool notModified;
}

/// 一条待回答的 UI 请求（提问卡）。字段与 `src/types.ts` 的 `PendingUiRequest` 一致。
class PendingUiRequest {
  const PendingUiRequest({
    required this.sessionId,
    required this.requestId,
    required this.method,
    this.agentId = '',
    this.runtimeGeneration = 0,
    this.title = '',
    this.options = const <PendingUiOption>[],
    this.placeholder = '',
    this.prefill = '',
    this.allowOther = false,
    this.multiple = false,
    this.batchQuestions = const <Map<String, Object?>>[],
    this.batchReview = false,
  });

  final String sessionId;
  final String requestId;

  /// select | confirm | input | editor | batch_ask
  final String method;
  final String agentId;
  final int runtimeGeneration;
  final String title;
  final List<PendingUiOption> options;
  final String placeholder;
  final String prefill;
  final bool allowOther;

  /// 多选（`select` 卡片的勾选框）。Web 侧读的是 `pending.multiple`。
  final bool multiple;
  final List<Map<String, Object?>> batchQuestions;
  final bool batchReview;

  Map<String, Object?> toTarget() => <String, Object?>{
        'sessionId': sessionId,
        'agentId': agentId,
        'runtimeGeneration': runtimeGeneration,
      };

  static PendingUiRequest? fromJson(Object? raw) {
    if (raw is! Map) return null;
    final String requestId = '${raw['requestId'] ?? ''}';
    if (requestId.isEmpty) return null;
    return PendingUiRequest(
      sessionId: '${raw['sessionId'] ?? ''}',
      requestId: requestId,
      method: '${raw['method'] ?? 'select'}',
      agentId: '${raw['agentId'] ?? ''}',
      runtimeGeneration: raw['runtimeGeneration'] is int
          ? raw['runtimeGeneration'] as int
          : int.tryParse('${raw['runtimeGeneration'] ?? ''}') ?? 0,
      title: '${raw['title'] ?? ''}',
      options: <PendingUiOption>[
        for (final Object? item in (raw['options'] is List ? raw['options'] as List : const <Object?>[]))
          if (item is Map) PendingUiOption.fromJson(item),
      ],
      placeholder: '${raw['placeholder'] ?? ''}',
      prefill: '${raw['prefill'] ?? ''}',
      allowOther: raw['allowOther'] == true,
      multiple: raw['multiple'] == true,
      batchQuestions: <Map<String, Object?>>[
        for (final Object? item
            in (raw['batchQuestions'] is List ? raw['batchQuestions'] as List : const <Object?>[]))
          if (item is Map) Map<String, Object?>.from(item),
      ],
      batchReview: raw['batchReview'] == true,
    );
  }
}

class PendingUiOption {
  const PendingUiOption({required this.value, required this.label, this.description = ''});

  final String value;
  final String label;
  final String description;

  static PendingUiOption fromJson(Map<Object?, Object?> raw) => PendingUiOption(
        value: '${raw['value'] ?? ''}',
        label: '${raw['label'] ?? raw['value'] ?? ''}',
        description: '${raw['description'] ?? ''}',
      );
}

/// 「投递」结果（`SendPromptResult`）。
///
/// 关键字段是 `accepted`：桌面端拒收时回 `accepted: false` + 结构化的
/// `i18nKey` / `i18nParams` / `delivery`，以前手机端完全不看返回值，
/// 表现就是「气泡上屏了、什么都没发生、也没有任何提示」。
class SendPromptResult {
  const SendPromptResult({
    this.ok = true,
    this.message = '',
    this.accepted = true,
    this.rejection,
  });

  final bool ok;
  final String message;

  /// 服务端是否收下了这条提问。
  final bool accepted;

  /// 拒收的结构化说明；`accepted` 为真时为 null。
  final PromptRejection? rejection;

  static SendPromptResult fromJson(Object? raw) {
    if (raw is! Map) return const SendPromptResult();
    // 只看 `accepted === false`：老桌面端 `/prompt` 的 result 可能是裸空对象，
    // 那种不能当拒收（与 `app/src/ui/acp-notice.ts` 的 isPromptRejection 同口径）。
    if (raw['accepted'] == false) {
      final Object? params = raw['i18nParams'];
      return SendPromptResult(
        ok: false,
        message: '${raw['message'] ?? raw['error'] ?? ''}',
        accepted: false,
        rejection: PromptRejection(
          error: '${raw['error'] ?? ''}',
          delivery: '${raw['delivery'] ?? ''}',
          i18nKey: '${raw['i18nKey'] ?? ''}',
          i18nParams: params is Map
              ? params.map<String, Object?>(
                  (Object? key, Object? value) => MapEntry<String, Object?>('$key', value),
                )
              : const <String, Object?>{},
        ),
      );
    }
    return SendPromptResult(
      ok: true,
      message: '${raw['message'] ?? raw['error'] ?? ''}',
    );
  }
}

/// 与 `src/api/client.ts` 的 `PiDeckClient` 同构的最小实现。
///
/// 只覆盖「聊天页自己跑起来」需要的端点；侧栏 / 设置页仍是 WebView 推送快照，
/// 等这些页面也搬过来时再按同一份对照往下补。
/// `[DONE]` 在协议里等价于一次 `finish` 帧（对应 TS 侧 `session.ts` 的处理）。
///
/// 这里用逐键赋值而不是一次性写出的映射字面量：`app/tests/flutter-actions.test.ts`
/// 会抓 Dart 里所有「type 键 + 字符串字面量」当成「聊天动作名」，它分不清帧类型和
/// 动作名，写成字面量会被误判成 Dart 发了个叫 finish 的动作。
Map<String, Object?> doneFrame() {
  final Map<String, Object?> frame = <String, Object?>{};
  frame['type'] = 'finish';
  return frame;
}

class PiDeckClient {
  PiDeckClient({
    required String baseUrl,
    required String token,
    this.timeout = const Duration(seconds: 20),
    this.connectTimeout = const Duration(seconds: 15),
  })  : baseUrl = _normalizeBaseUrl(baseUrl),
        token = token.trim();

  /// 服务端根地址，末尾不带 `/`。
  final String baseUrl;
  final String token;

  /// 普通请求超时（`fetchWithTimeout` 默认 20s）。
  final Duration timeout;

  /// 建连超时。流式请求**不设**整体超时（对应 TS 的 `readTimeoutMs: 0`）。
  final Duration connectTimeout;

  HttpClient? _http;

  HttpClient get _client => _http ??= HttpClient()..connectionTimeout = connectTimeout;

  bool get isConfigured => baseUrl.isNotEmpty && token.isNotEmpty;

  void close() {
    _http?.close(force: true);
    _http = null;
  }

  static String _normalizeBaseUrl(String raw) {
    String value = raw.trim();
    while (value.endsWith('/')) {
      value = value.substring(0, value.length - 1);
    }
    return value;
  }

  /* ------------------------------------------------------------------ */
  /* 低层：URL / 头 / 错误                                               */
  /* ------------------------------------------------------------------ */

  /// `buildUrl`：baseUrl + path，再并入 query（null / 空串跳过，与 TS 一致）。
  Uri buildUri(String path, [Map<String, Object?>? query]) {
    final Uri base = Uri.parse('$baseUrl$path');
    final Map<String, String> merged = <String, String>{
      for (final MapEntry<String, String> entry in base.queryParameters.entries) entry.key: entry.value,
    };
    query?.forEach((String key, Object? value) {
      if (value == null) return;
      final String text = '$value';
      if (text.isEmpty) return;
      merged[key] = text;
    });
    if (merged.isEmpty) return base;
    return base.replace(queryParameters: merged);
  }

  /// `withQueryToken`：GET / SSE 用，把 token 放进 query。
  Uri withQueryToken(Uri uri) {
    if (token.isEmpty) return uri;
    final Map<String, String> params = Map<String, String>.from(uri.queryParameters);
    params['token'] = token;
    return uri.replace(queryParameters: params);
  }

  /// GET 不带 Authorization（对齐 TS，避开 CORS 预检那一套）；
  /// 非 GET 用 Bearer。`anonymous` 用于配对前的 `/api/health`，不带任何凭据。
  Map<String, String> headers({
    bool jsonBody = false,
    bool withAuth = true,
    String? accept,
  }) {
    final Map<String, String> out = <String, String>{'accept': accept ?? 'application/json'};
    if (jsonBody) out['content-type'] = 'application/json';
    if (withAuth && token.isNotEmpty) out['authorization'] = 'Bearer $token';
    return out;
  }

  /// `toError`：401 → auth、409 → runtime、其余 → http，尽量带出 body 的 code/details。
  Future<PiDeckException> toError(HttpClientResponse response, String fallback) async {
    String text = '';
    try {
      text = await utf8.decodeStream(response);
    } catch (_) {
      text = '';
    }
    String message = fallback;
    String? code;
    Map<String, Object?>? details;
    if (text.trim().isNotEmpty) {
      try {
        final Object? parsed = jsonDecode(text);
        if (parsed is Map) {
          final Object? rawMessage = parsed['message'] ?? parsed['error'];
          if (rawMessage is String && rawMessage.trim().isNotEmpty) message = rawMessage;
          if (parsed['code'] is String) code = parsed['code'] as String;
          if (parsed['params'] is Map) details = Map<String, Object?>.from(parsed['params'] as Map);
        } else if (text.trim().length < 200) {
          message = '$fallback：${text.trim()}';
        }
      } catch (_) {
        if (text.trim().length < 200) message = '$fallback：${text.trim()}';
      }
    }
    final int status = response.statusCode;
    final PiDeckErrorKind kind = status == 401
        ? PiDeckErrorKind.auth
        : status == 409
            ? PiDeckErrorKind.runtime
            : PiDeckErrorKind.http;
    return PiDeckException(kind, message, status: status, code: code, details: details);
  }

  /* ------------------------------------------------------------------ */
  /* 低层：请求                                                          */
  /* ------------------------------------------------------------------ */

  /// `requestJson`：GET 默认把 token 放 query，其它方法放 Authorization。
  Future<Map<String, Object?>?> requestJson(
    String path, {
    String method = 'GET',
    Map<String, Object?>? query,
    Object? body,
    bool anonymous = false,
    Duration? timeoutOverride,
  }) async {
    final bool isGet = method.toUpperCase() == 'GET';
    Uri uri = buildUri(path, query);
    if (isGet && !anonymous) uri = withQueryToken(uri);
    final Map<String, String> head = headers(jsonBody: body != null, withAuth: !isGet && !anonymous);

    HttpClientResponse response;
    try {
      final HttpClientRequest request = await _client.openUrl(method, uri).timeout(connectTimeout);
      head.forEach(request.headers.set);
      if (body != null) request.add(utf8.encode(jsonEncode(body)));
      response = await request.close().timeout(timeoutOverride ?? timeout);
    } on TimeoutException catch (error) {
      throw PiDeckException(PiDeckErrorKind.offline, '请求超时', cause: error);
    } on SocketException catch (error) {
      throw PiDeckException(PiDeckErrorKind.offline, error.message, cause: error);
    } on HttpException catch (error) {
      throw PiDeckException(PiDeckErrorKind.offline, error.message, cause: error);
    }

    if (response.statusCode == 204) {
      await response.drain<void>();
      return null;
    }
    if (response.statusCode >= 400) throw await toError(response, '请求失败');
    final String text = await utf8.decodeStream(response);
    if (text.trim().isEmpty) return null;
    try {
      final Object? parsed = jsonDecode(text);
      if (parsed is Map) return Map<String, Object?>.from(parsed);
      return <String, Object?>{'value': parsed};
    } on FormatException catch (error) {
      throw PiDeckException(PiDeckErrorKind.protocol, '返回的不是 JSON', cause: error);
    }
  }

  /// 取一段纯文本（会话导出用）。
  ///
  /// 与 `requestJson` 的差别只有一个：GET **也带** `Authorization` 头。
  /// 导出的是整段 HTML，塞进 `?token=` 会落到浏览器历史 / 下载记录 / 系统 Intent 里。
  Future<String> requestText(String path, {Map<String, Object?>? query}) async {
    final Uri uri = buildUri(path, query);
    final Map<String, String> head = headers(jsonBody: false, withAuth: true);
    HttpClientResponse response;
    try {
      final HttpClientRequest request = await _client.openUrl('GET', uri).timeout(connectTimeout);
      head.forEach(request.headers.set);
      response = await request.close().timeout(timeout);
    } on TimeoutException catch (error) {
      throw PiDeckException(PiDeckErrorKind.offline, '请求超时', cause: error);
    } on SocketException catch (error) {
      throw PiDeckException(PiDeckErrorKind.offline, error.message, cause: error);
    } on HttpException catch (error) {
      throw PiDeckException(PiDeckErrorKind.offline, error.message, cause: error);
    }
    if (response.statusCode >= 400) throw await toError(response, '请求失败');
    return utf8.decodeStream(response);
  }

  /// 会话导出成一段自包含 HTML（`GET /api/sessions/:id/export-html`）。
  Future<String> sessionExportHtml(String sessionId) =>
      requestText('/api/sessions/${Uri.encodeComponent(sessionId)}/export-html');

  /// SSE：连接 → 逐块喂 [SseParser] → 吐事件。
  ///
  /// 取消订阅时 `finally` 会关掉这一条流专用的 HttpClient（对应 TS 的 `AbortController`）。
  /// 4xx/5xx 在**吐第一个事件之前**抛，避免上层把 401 当成「流正常结束」。
  Stream<SseEvent> streamSse(
    String path, {
    Map<String, Object?>? query,
    void Function()? onOpen,
    void Function()? onActivity,
  }) async* {
    final HttpClient client = HttpClient()..connectionTimeout = connectTimeout;
    try {
      final Uri uri = withQueryToken(buildUri(path, query));
      HttpClientResponse response;
      try {
        final HttpClientRequest request = await client.getUrl(uri).timeout(connectTimeout);
        request.headers.set('accept', 'text/event-stream');
        request.headers.set('cache-control', 'no-cache');
        response = await request.close().timeout(connectTimeout);
      } on TimeoutException catch (error) {
        throw PiDeckException(PiDeckErrorKind.offline, '连接超时', cause: error);
      } on SocketException catch (error) {
        throw PiDeckException(PiDeckErrorKind.offline, error.message, cause: error);
      }
      if (response.statusCode >= 400) throw await toError(response, '流式连接失败');
      onOpen?.call();

      final SseParser parser = SseParser();
      await for (final String chunk in response.transform(utf8.decoder)) {
        onActivity?.call();
        for (final SseEvent event in parser.push(chunk)) {
          yield event;
        }
      }
      for (final SseEvent event in parser.flush()) {
        yield event;
      }
    } finally {
      client.close(force: true);
    }
  }

  /// 会话帧流：`[DONE]` 标记 → `finish` 帧（与 `streamSession` 一致）。
  Stream<Map<String, Object?>> sessionFrames(String sessionId, {void Function()? onOpen}) async* {
    await for (final SseEvent event in streamSse(
      '/api/sessions/${Uri.encodeComponent(sessionId)}/stream',
      onOpen: onOpen,
    )) {
      if (event.data == sseDoneMarker) {
        yield doneFrame();
        continue;
      }
      final Map<String, Object?>? frame = safeParseFrame(event.data);
      if (frame != null) yield frame;
    }
  }

  /* ------------------------------------------------------------------ */
  /* 端点                                                                */
  /* ------------------------------------------------------------------ */

  /// 免鉴权探活（配对前用；这里用于「打开页面前确认对面是 PiDeck」）。
  Future<Map<String, Object?>?> health() =>
      requestJson('/api/health', anonymous: true, timeoutOverride: const Duration(seconds: 6));

  Future<StateSnapshot> getState({String? etag}) async {
    final Uri uri = withQueryToken(buildUri('/api/state'));
    HttpClientResponse response;
    try {
      final HttpClientRequest request = await _client.getUrl(uri).timeout(connectTimeout);
      final Map<String, String> head = headers(withAuth: false);
      if (etag != null && etag.isNotEmpty) head['if-none-match'] = etag;
      head.forEach(request.headers.set);
      response = await request.close().timeout(timeout);
    } on TimeoutException catch (error) {
      throw PiDeckException(PiDeckErrorKind.offline, '读取状态超时', cause: error);
    } on SocketException catch (error) {
      throw PiDeckException(PiDeckErrorKind.offline, error.message, cause: error);
    }
    final String? nextEtag = response.headers.value('etag');
    if (response.statusCode == 304) {
      await response.drain<void>();
      return StateSnapshot(etag: etag ?? nextEtag, notModified: true);
    }
    if (response.statusCode >= 400) throw await toError(response, '读取状态失败');
    final String text = await utf8.decodeStream(response);
    try {
      final Object? parsed = jsonDecode(text);
      return StateSnapshot(
        json: parsed is Map ? Map<String, Object?>.from(parsed) : null,
        etag: nextEtag,
      );
    } on FormatException catch (error) {
      throw PiDeckException(PiDeckErrorKind.protocol, '状态不是 JSON', cause: error);
    }
  }

  Future<MessageWindow> getMessages(String sessionId) async {
    final Map<String, Object?>? body =
        await requestJson('/api/sessions/${Uri.encodeComponent(sessionId)}/messages');
    return MessageWindow.fromJson(body);
  }

  /// 全局状态流：`GET /api/events`，`event: state` 的 data 就是完整快照。
  ///
  /// 服务端是 400ms 去抖 + 5s 兜底 tick 的「变化驱动」推送，收到即**整份替换**
  /// （不做增量合并 —— 协议里没有补丁帧）。
  ///
  /// 注意它**不包含**会话列表变化（新建/删除/改名不在 revision 判据里），
  /// 所以调用方仍要保留低频 `/api/state` 对账（见 `state_store.dart`）。
  Stream<Map<String, Object?>> stateEvents({void Function()? onOpen}) async* {
    await for (final SseEvent event in streamSse('/api/events', onOpen: onOpen)) {
      if (event.event != 'state') continue;
      try {
        final Object? parsed = jsonDecode(event.data);
        if (parsed is Map) yield Map<String, Object?>.from(parsed);
      } on FormatException {
        // 单个坏帧不应断开整条流（与 `streamState` 的 try/catch 同口径）。
      }
    }
  }

  /// `POST /api/sessions`：在某个项目下新建会话。
  Future<Map<String, Object?>> createSession(String projectId) async {
    final Map<String, Object?>? body = await requestJson(
      '/api/sessions',
      method: 'POST',
      body: <String, Object?>{'projectId': projectId},
    );
    if (body == null) {
      throw PiDeckException(PiDeckErrorKind.protocol, '新建会话没有返回内容');
    }
    final Object session = body['session'] ?? body;
    if (session is Map) return Map<String, Object?>.from(session);
    throw PiDeckException(PiDeckErrorKind.protocol, '新建会话返回的不是对象');
  }

  Future<SendPromptResult> sendPrompt(
    String sessionId, {
    required String requestId,
    required String message,
    List<Map<String, Object?>> images = const <Map<String, Object?>>[],
    String? streamingBehavior,
    Map<String, Object?> extra = const <String, Object?>{},
  }) async {
    final Map<String, Object?> body = <String, Object?>{
      'requestId': requestId,
      'message': message,
      if (images.isNotEmpty) 'images': images,
      if (streamingBehavior != null && streamingBehavior.isNotEmpty) 'streamingBehavior': streamingBehavior,
      ...extra,
    };
    final Map<String, Object?>? response = await requestJson(
      '/api/sessions/${Uri.encodeComponent(sessionId)}/prompt',
      method: 'POST',
      body: body,
    );
    return SendPromptResult.fromJson(response?['result']);
  }

  /// `POST /api/sessions/:id/runtime/:action`。
  ///
  /// 除 `activate` 外**必须**带 target（`{sessionId, agentId, runtimeGeneration}`），
  /// 过期会回 409 —— 409 的错误分类是 [PiDeckErrorKind.runtime]，上层据此重拉 /api/state。
  Future<Map<String, Object?>?> runtimeAction(
    String sessionId,
    String action, {
    Map<String, Object?>? target,
    Map<String, Object?> body = const <String, Object?>{},
  }) {
    if (action != 'activate' && target == null) {
      throw PiDeckException(
        PiDeckErrorKind.protocol,
        '$action 需要 runtime target（先拉 /api/state）',
      );
    }
    return requestJson(
      '/api/sessions/${Uri.encodeComponent(sessionId)}/runtime/${Uri.encodeComponent(action)}',
      method: 'POST',
      body: <String, Object?>{
        ...body,
        if (action != 'activate' && target != null) 'target': target,
      },
    );
  }

  /// `POST /api/ui-response`：body 是**平铺**的，不是 `{target: ...}`。
  /// 对应 `src/api/client.ts:696-707`，字段名写错服务端会当非法请求丢掉。
  Future<void> respondUi(PendingUiRequest pending, Map<String, Object?> response) async {
    await requestJson('/api/ui-response', method: 'POST', body: <String, Object?>{
      'sessionId': pending.sessionId,
      'agentId': pending.agentId,
      'runtimeGeneration': pending.runtimeGeneration,
      'requestId': pending.requestId,
      'response': response,
    });
  }

  Future<Map<String, Object?>?> sessionRecordAction(
    String sessionId,
    String action, {
    Map<String, Object?> patch = const <String, Object?>{},
  }) {
    return requestJson(
      '/api/sessions/${Uri.encodeComponent(sessionId)}/${Uri.encodeComponent(action)}',
      method: 'POST',
      body: patch,
    );
  }

  /// `POST /api/projects/:id/delete`：移除项目（只是从列表里移除，不动磁盘）。
  Future<bool> deleteProject(String projectId) async {
    final Map<String, Object?>? body = await requestJson(
      '/api/projects/${Uri.encodeComponent(projectId)}/delete',
      method: 'POST',
      body: <String, Object?>{},
    );
    return body != null && body['deleted'] == true;
  }

  Future<Object?> getTodo(String sessionId) async {    final Map<String, Object?>? body =
        await requestJson('/api/sessions/${Uri.encodeComponent(sessionId)}/todo');
    return body?['todo'];
  }

  Future<List<Object?>> listModels({bool force = false}) async {
    final Map<String, Object?>? body = await requestJson('/api/models', query: <String, Object?>{
      if (force) 'force': 1,
    });
    final Object? models = body?['models'];
    return models is List ? List<Object?>.from(models) : <Object?>[];
  }

  /// `GET /api/files?projectId&dir`。
  ///
  /// 参数名是 **dir** 不是 path —— 服务端只认 dir，传 path 会被静默忽略，
  /// 表现就是「点进目录后看到的还是根目录」（对应 `src/api/client.ts:719-733`）。
  Future<List<Map<String, Object?>>> listFiles(String projectId, String dir) async {
    final Map<String, Object?>? body = await requestJson(
      '/api/files',
      query: <String, Object?>{
        'projectId': projectId,
        if (dir.isNotEmpty) 'dir': dir,
      },
    );
    final Object? nodes = body?['nodes'];
    if (nodes is! List) return const <Map<String, Object?>>[];
    return <Map<String, Object?>>[
      for (final Object? item in nodes)
        if (item is Map) Map<String, Object?>.from(item),
    ];
  }

  /// `GET /api/file-content?projectId&path`：只对文本成立。
  ///
  /// 服务端分四种情况（`src/api/client.ts:745-781`）：正常 `{content,size}`、
  /// 二进制 `{binary:true,size}`、超 2MB `{truncated:true,content,size}`、
  /// 读不到句柄 `{tooLarge:true,size}`。调用方必须看 [FileContent.kind]，
  /// 不能默默把空串当内容存下来。
  Future<FileContent> fileContent(String projectId, String path) async {
    final Map<String, Object?>? body = await requestJson(
      '/api/file-content',
      query: <String, Object?>{'projectId': projectId, 'path': path},
    );
    final Map<String, Object?> raw = body ?? const <String, Object?>{};
    return FileContent(
      kind: raw['binary'] == true
          ? 'binary'
          : raw['truncated'] == true
              ? 'truncated'
              : raw['tooLarge'] == true
                  ? 'tooLarge'
                  : 'text',
      content: raw['content'] is String ? raw['content']! as String : '',
      size: raw['size'] is num ? (raw['size']! as num).toInt() : 0,
    );
  }

  /// `GET /api/git/status?projectId`：形状由调用方解析（`workspace_native.dart`）。
  Future<Object?> gitStatus(String projectId) =>
      requestJson('/api/git/status', query: <String, Object?>{'projectId': projectId});

  Future<Object?> gitLog(String projectId, {int limit = 20}) => requestJson(
        '/api/git/log',
        query: <String, Object?>{'projectId': projectId, 'limit': limit},
      );
}

/// 文件正文结果：`kind` 决定能不能当文本预览。
class FileContent {
  const FileContent({this.kind = 'text', this.content = '', this.size = 0});

  final String kind;
  final String content;
  final int size;

  bool get isText => kind == 'text' || kind == 'truncated';
}
