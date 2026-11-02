/// 发送载荷的语义锁定：计划模式标记、排队/插话、拒收文案。
///
/// 这些字面量与桌面端逐字一致（`app/src/ui/plan-mode.ts` / `send-mode.ts` /
/// `acp-notice.ts`）。改一个字都可能让内置扩展识别不到标记 —— 那样标记会原样
/// 进 prompt，比「没有计划模式」更糟。所以这里逐字符断言。
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:pideck_flutter/runtime/prompt_payload.dart';

void main() {
  group('buildPlanSubmission', () {
    test('没开计划模式：原文照发，不加任何东西', () {
      final PlanSubmission out = buildPlanSubmission('  帮我看看这个 bug  ', false);
      expect(out.message, '  帮我看看这个 bug  ');
      expect(out.planMode, isFalse);
      expect(out.agentMessage, isNull);
    });

    test('开着计划模式：可见消息保持原文，隐藏消息带标记 + 指令 + 格式', () {
      final PlanSubmission out = buildPlanSubmission('重构登录', true);
      expect(out.message, '重构登录');
      expect(out.planMode, isTrue);
      expect(
        out.agentMessage,
        '__PI_DECK_PLAN_MODE__\n'
        '重构登录\n'
        '\n'
        '请先只做只读分析，不要修改文件。最后必须输出以 `Plan:` 开头的编号计划，格式如下：\n'
        'Plan:\n'
        '1. 第一步\n'
        '2. 第二步',
      );
    });

    test('空输入 + 计划模式：给 agent 一句能执行的兜底指令', () {
      final PlanSubmission out = buildPlanSubmission('   ', true);
      expect(out.planMode, isTrue);
      expect(out.agentMessage, contains('请根据已附加的图片或上下文先制定实施计划。'));
    });

    test('斜线命令不包标记（/plan off 得能穿过计划模式发出去）', () {
      final PlanSubmission out = buildPlanSubmission('/plan off', true);
      expect(out.message, '/plan off');
      expect(out.planMode, isFalse);
      expect(out.agentMessage, isNull);
    });
  });

  group('streamingBehaviorFor', () {
    test('空闲时不带（让服务端按普通发送处理）', () {
      expect(streamingBehaviorFor(false, sendModeQueue), isNull);
      expect(streamingBehaviorFor(false, sendModeSteer), isNull);
    });

    test('运行中：插话 → steer，排队 → followUp', () {
      expect(streamingBehaviorFor(true, sendModeSteer), 'steer');
      expect(streamingBehaviorFor(true, sendModeQueue), 'followUp');
    });

    test('未知模式按排队处理（最安全）', () {
      expect(streamingBehaviorFor(true, 'whatever'), 'followUp');
    });
  });

  group('拒收', () {
    test('命中 i18nKey 用本地化模板', () {
      expect(
        rejectionMessage(const PromptRejection(i18nKey: 'acp.turnBusy', i18nParams: <String, Object?>{'tool': 'Claude Code'})),
        'Claude Code 还在回复中，等这一轮结束或先点停止再发',
      );
      expect(
        rejectionMessage(const PromptRejection(i18nKey: 'acp.imagesNotSupported')),
        '该 CLI 不接受图片输入，移除图片后再发',
      );
      expect(
        rejectionMessage(const PromptRejection(i18nKey: 'acp.turnTimeout', i18nParams: <String, Object?>{'tool': 'Codex', 'minutes': 5})),
        '「Codex」回合超时（连续 5 分钟没有任何输出），可以在电脑端把超时保护调大',
      );
    });

    test('未命中的 key 退到服务端原文', () {
      expect(
        rejectionMessage(const PromptRejection(error: 'boom', i18nKey: 'acp.unknown')),
        '发送被拒绝：boom',
      );
    });

    test('什么都没有时给一句能 actionable 的兜底', () {
      expect(rejectionMessage(const PromptRejection()), '发送被拒绝：桌面端没有说明原因，可以重发一次看看');
    });

    test('delivery 不是 unknown 才回滚气泡', () {
      expect(const PromptRejection(delivery: 'rejected').shouldRollback, isTrue);
      expect(const PromptRejection(delivery: 'unknown').shouldRollback, isFalse);
      expect(const PromptRejection().shouldRollback, isTrue);
    });
  });
}
