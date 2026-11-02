import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pideck_flutter/chat/image_viewer.dart';

/// 1×1 透明 PNG：看图页要能解码真图片，占位字节解不出来就测不到解码这条路。
const String _tinyPngBase64 =
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==';

String _dataUrl(String base64) => 'data:image/png;base64,$base64';

void main() {
  group('ImageViewerPage', () {
    testWidgets('单图：图片能解码、有关闭按钮、不显示页码', (WidgetTester tester) async {
      await tester.pumpWidget(
        MaterialApp(home: ImageViewerPage(images: <String>[_dataUrl(_tinyPngBase64)])),
      );
      await tester.pump();

      expect(find.byType(ImageViewerPage), findsOneWidget);
      expect(find.byType(Image), findsOneWidget);
      expect(find.byIcon(Icons.close_rounded), findsOneWidget);
      // 单图不显示页码
      expect(find.text('1 / 1'), findsNothing);
    });

    testWidgets('多图：显示页码，左右翻页跟着变', (WidgetTester tester) async {
      await tester.pumpWidget(
        MaterialApp(
          home: ImageViewerPage(
            images: <String>[_dataUrl(_tinyPngBase64), _dataUrl(_tinyPngBase64), _dataUrl(_tinyPngBase64)],
            initialIndex: 1,
          ),
        ),
      );
      await tester.pump();

      expect(find.text('2 / 3'), findsOneWidget);

      // PageView 自己不吃拖动（被 InteractiveViewer 抢走），翻页靠甩动后的速度
      await tester.fling(find.byType(ImageViewerPage), const Offset(-400, 0), 1200);
      await tester.pumpAndSettle();
      expect(find.text('3 / 3'), findsOneWidget);

      await tester.fling(find.byType(ImageViewerPage), const Offset(400, 0), 1200);
      await tester.pumpAndSettle();
      expect(find.text('2 / 3'), findsOneWidget);
    });

    testWidgets('坏数据不抛异常，显示占位文案', (WidgetTester tester) async {
      await tester.pumpWidget(
        MaterialApp(home: ImageViewerPage(images: <String>['data:image/png;base64,%%%'])),
      );
      await tester.pump();
      expect(find.text('图片无法显示'), findsOneWidget);
      expect(tester.takeException(), isNull);
    });

    testWidgets('无法识别的来源（既不是 data: 也不是 http）也走占位', (WidgetTester tester) async {
      await tester.pumpWidget(
        MaterialApp(home: ImageViewerPage(images: <String>['file:///tmp/a.png'])),
      );
      await tester.pump();
      expect(find.text('图片无法显示'), findsOneWidget);
    });

    testWidgets('空列表不会崩（openImageViewer 会拦住，但页面本身也要安全）', (WidgetTester tester) async {
      await tester.pumpWidget(MaterialApp(home: ImageViewerPage(images: const <String>[])));
      await tester.pump();
      expect(tester.takeException(), isNull);
    });
  });

  group('openImageViewer', () {
    testWidgets('点图片区域也能关闭', (WidgetTester tester) async {
      await tester.pumpWidget(
        MaterialApp(
          home: Builder(
            builder: (BuildContext context) => Scaffold(
              body: Center(
                child: TextButton(
                  onPressed: () => openImageViewer(context, <String>[_dataUrl(_tinyPngBase64)], 0),
                  child: const Text('open'),
                ),
              ),
            ),
          ),
        ),
      );

      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();

      await tester.tapAt(tester.getCenter(find.byType(ImageViewerPage)));
      await tester.pumpAndSettle();
      expect(find.byType(ImageViewerPage), findsNothing);
    });

    testWidgets('点图片进看图页，返回后回到原页面', (WidgetTester tester) async {
      await tester.pumpWidget(
        MaterialApp(
          home: Builder(
            builder: (BuildContext context) => Scaffold(
              body: Center(
                child: TextButton(
                  onPressed: () => openImageViewer(context, <String>[_dataUrl(_tinyPngBase64)], 0),
                  child: const Text('open'),
                ),
              ),
            ),
          ),
        ),
      );

      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();
      expect(find.byType(ImageViewerPage), findsOneWidget);

      await tester.tap(find.byIcon(Icons.close_rounded));
      await tester.pumpAndSettle();
      expect(find.byType(ImageViewerPage), findsNothing);
      expect(find.text('open'), findsOneWidget);
    });

    testWidgets('空图列表点不开（不压路由）', (WidgetTester tester) async {
      await tester.pumpWidget(
        MaterialApp(
          home: Builder(
            builder: (BuildContext context) => Scaffold(
              body: Center(
                child: TextButton(
                  onPressed: () => openImageViewer(context, const <String>[], 0),
                  child: const Text('open'),
                ),
              ),
            ),
          ),
        ),
      );

      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();
      expect(find.byType(ImageViewerPage), findsNothing);
    });
  });

  test('真实 PNG 能被 base64 解码（守住上面测试用的样本字节）', () {
    final Uint8List bytes = base64Decode(_tinyPngBase64);
    expect(bytes.length, greaterThan(8));
    expect(bytes.sublist(0, 8), <int>[0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]);
  });
}
