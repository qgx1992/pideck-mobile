import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:pideck_flutter/settings/settings_models.dart';

/// 这些测试盯的是「宿主推来的 JSON → Dart 模型」这一层。
/// 它是 Flutter 设置页唯一的外部输入，字段名写错会静默变成空值（页面一片空白），
/// 所以每个字段都要有一条断言钉住名字。
void main() {
  test('解析完整的 SettingsData', () {
    final raw = jsonEncode({
      'theme': {
        'mode': 'dark',
        'options': [
          {'value': 'light', 'label': '浅色'},
          {'value': 'dark', 'label': '深色'},
        ],
      },
      'notify': {'enabled': false},
      'connection': {
        'serviceUrl': 'http://10.10.0.172:8090',
        'token': 'abcdefghijklmnop',
        'status': 'open',
        'version': '0.1.21',
        'sseNative': true,
      },
      'devices': [
        {
          'id': 'dev-1',
          'label': '本机',
          'url': 'http://10.10.0.172:8090',
          'active': true,
          'running': 2,
          'pending': 1,
          'enabled': true,
        },
      ],
      'models': [
        {'key': 'a::b', 'name': 'B', 'provider': 'a', 'modelId': 'b'},
      ],
      'skills': [
        {
          'sourceId': 'pi-global',
          'label': 'pi 全局',
          'rows': [
            {
              'key': 'pi-global::demo',
              'name': 'demo',
              'sourceId': 'pi-global',
              'sourceLabel': 'pi 全局',
              'description': '示例技能',
              'enabled': true,
              'valid': false,
              'userOnly': true,
              'warnings': ['缺 SKILL.md'],
            },
          ],
        },
      ],
      'extensions': [
        {
          'key': 'user::/x',
          'source': '/x',
          'name': 'x',
          'scope': 'project',
          'enabled': true,
          'description': '说明',
          'projectId': 'p1',
        },
      ],
      'dshPlugins': [
        {
          'id': 'note',
          'name': '笔记',
          'version': '1.0.0',
          'running': true,
          'description': '记点东西',
          'kind': 'dynamic',
          'packageId': 'note@1',
          'status': 'running',
        },
        {
          'id': 'loader-1',
          'name': 'loader',
          'version': '',
          'running': false,
          'description': '',
          'kind': 'static',
          'packageId': '',
          'status': 'stopped',
        },
      ],
      'dshTarget': '会话「调试」',
      'dshError': '',
      'loading': false,
      'loadError': '',
    });

    final data = SettingsData.fromJson(mapOf(jsonDecode(raw)));

    expect(data.theme.mode, 'dark');
    expect(data.theme.options.map((o) => o.value), ['light', 'dark']);
    expect(data.notifyEnabled, isFalse);
    expect(data.connection.serviceUrl, 'http://10.10.0.172:8090');
    expect(data.connection.version, '0.1.21');
    expect(data.connection.sseNative, isTrue);
    expect(data.devices.single.label, '本机');
    expect(data.devices.single.badges, '当前 · 2 运行中 · 1 待答');
    expect(data.models.single.name, 'B');
    expect(data.skills.single.label, 'pi 全局');
    expect(data.skills.single.rows.single.valid, isFalse);
    expect(data.skills.single.rows.single.subtitle, '示例技能 · 仅手动调用 · 无效 · 缺 SKILL.md');
    expect(data.extensions.single.projectId, 'p1');
    expect(data.extensions.single.subtitle, '项目级 · 说明');
    expect(data.dshPlugins.first.valueLabel, '运行中');
    expect(data.dshPlugins.first.actionable, isTrue);
    expect(data.dshPlugins.last.actionable, isFalse);
    expect(data.dshPlugins.last.subtitle, '静态条目（只读）');
    expect(data.dshTarget, '会话「调试」');
  });

  test('缺字段 / 类型不对时不抛异常，按空值处理', () {
    final data = SettingsData.fromJson(mapOf(jsonDecode('{}')));
    expect(data.theme.mode, '');
    expect(data.theme.options, isEmpty);
    expect(data.notifyEnabled, isTrue); // 默认开启，与 Web 版 isTaskNotifyEnabled 的缺省一致
    expect(data.devices, isEmpty);
    expect(data.skills, isEmpty);
    expect(data.loading, isFalse);

    final broken = SettingsData.fromJson(mapOf(jsonDecode('{"devices":"不是数组"}')));
    expect(broken.devices, isEmpty);
  });

  test('sseNative 缺省时保持 null（老包不显示「数据流通道」这一行）', () {
    final data = SettingsData.fromJson(
      mapOf(jsonDecode('{"connection":{"serviceUrl":"u"}}')),
    );
    expect(data.connection.sseNative, isNull);
    expect(data.connection.serviceUrl, 'u');
  });

  test('maskToken 与 Web 版 maskToken 规则一致', () {
    expect(maskToken(''), '（未设置）');
    expect(maskToken('   '), '（未设置）');
    expect(maskToken('abc'), '***');
    expect(maskToken('12345678'), '********');
    expect(maskToken('abcdefghijklmnop'), 'abcd…mnop');
  });
}
