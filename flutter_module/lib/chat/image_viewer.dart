import 'dart:convert';

import 'package:flutter/material.dart';

/// 全屏看图。
///
/// 为什么不用 `pub` 包：需要的功能只有「捏合缩放 + 左右翻页 + 点一下关掉」，
/// `InteractiveViewer` + `PageView` 三十行就能做到；而引一个看图库进来，
/// 还要连带把它的手势优先级和聊天页的滚动、长按菜单、侧栏滑动调一遍。
///
/// 图片数据来源与聊天页完全一致：`data:` 前缀走 `Image.memory`，
/// `http(s)` 走 `Image.network` —— 两条路在 `_ChatImage` 里已经走过一遍，
/// 这里保持一致（不要在这里改成只认 `data:`，历史消息里的老图是网络地址）。
class ImageViewerPage extends StatefulWidget {
  const ImageViewerPage({super.key, required this.images, this.initialIndex = 0});

  final List<String> images;
  final int initialIndex;

  @override
  State<ImageViewerPage> createState() => _ImageViewerPageState();
}

class _ImageViewerPageState extends State<ImageViewerPage> {
  late final PageController _pages = PageController(initialPage: widget.initialIndex);
  late int _index = widget.initialIndex;

  /// 每张图一个变换控制器：翻回上一张时缩放状态还在，符合系统相册的手感。
  final Map<int, TransformationController> _transforms = <int, TransformationController>{};

  TransformationController _transformOf(int index) =>
      _transforms.putIfAbsent(index, () => TransformationController());

  @override
  void dispose() {
    _pages.dispose();
    for (final TransformationController controller in _transforms.values) {
      controller.dispose();
    }
    super.dispose();
  }

  /// 一根手指在「没放大」的状态下横向甩：翻到上一张/下一张。
  ///
  /// 为什么不用 `PageView` 自带的滑动：`InteractiveViewer` 的缩放手势会把
  /// 祖先的横向拖动全吃掉（手势竞技场里子级先声明胜利），实测 `PageView`
  /// 收不到任何拖动。所以 `PageView` 关掉物理滚动、只当动画容器用，
  /// 滑动由这里接管 —— 缩放态（scale > 1）时仍然交给 `InteractiveViewer`
  /// 自己平移，不然放大后就没法看边角。
  void _onInteractionEnd(int index, ScaleEndDetails details) {
    final double scale = _transformOf(index).value.getMaxScaleOnAxis();
    if (scale > 1.01) return;
    final double velocity = details.velocity.pixelsPerSecond.dx;
    if (velocity.abs() < 240) return;
    final int next = velocity < 0 ? index + 1 : index - 1;
    if (next < 0 || next >= widget.images.length) return;
    _pages.animateToPage(
      next,
      duration: const Duration(milliseconds: 180),
      curve: Curves.easeOut,
    );
  }

  @override
  Widget build(BuildContext context) {
    // 看图页固定黑底，所以**不能**用主题色画按钮：浅色主题下 `c.text` 是深灰，
    // 在纯黑上等于隐形（关闭按钮看得见才怪）。这里用固定的白色。
    final TextStyle counterStyle = TextStyle(
      color: Colors.white.withValues(alpha: 0.8),
      fontSize: 12,
      shadows: const <Shadow>[Shadow(color: Colors.black54, blurRadius: 6)],
    );
    // 看图页固定黑底：浅色主题下白底会把对比度压没，看不清图片边缘
    return Scaffold(
      backgroundColor: Colors.black,
      body: SafeArea(
        child: Stack(
          children: <Widget>[
            PageView.builder(
              controller: _pages,
              // 拖动被 InteractiveViewer 吃掉了，见 _onInteractionEnd 的注释
              physics: const NeverScrollableScrollPhysics(),
              itemCount: widget.images.length,
              onPageChanged: (int index) => setState(() => _index = index),
              itemBuilder: (BuildContext context, int index) {
                return GestureDetector(
                  // 点空白处关闭；双指缩放不触发 onTap
                  onTap: () => Navigator.of(context).maybePop(),
                  child: InteractiveViewer(
                    transformationController: _transformOf(index),
                    minScale: 1,
                    maxScale: 4,
                    onInteractionEnd: (ScaleEndDetails details) => _onInteractionEnd(index, details),
                    child: Center(child: _image(widget.images[index])),
                  ),
                );
              },
            ),
            Positioned(
              top: 8,
              right: 8,
              child: IconButton(
                tooltip: '关闭',
                style: IconButton.styleFrom(
                  backgroundColor: Colors.black.withValues(alpha: 0.35),
                  foregroundColor: Colors.white,
                ),
                icon: const Icon(Icons.close_rounded),
                onPressed: () => Navigator.of(context).maybePop(),
              ),
            ),
            if (widget.images.length > 1)
              Positioned(
                top: 18,
                left: 0,
                right: 0,
                child: IgnorePointer(
                  child: Center(
                    child: Text(
                      '${_index + 1} / ${widget.images.length}',
                      style: counterStyle,
                    ),
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }

  Widget _image(String source) {
    if (source.startsWith('data:')) {
      final int comma = source.indexOf(',');
      if (comma < 0) return _broken();
      try {
        return Image.memory(
          base64Decode(source.substring(comma + 1)),
          fit: BoxFit.contain,
          // 原图可能很大：按屏幕宽度解码，别把内存撑爆
          cacheWidth: (MediaQuery.of(context).size.width * MediaQuery.of(context).devicePixelRatio).round(),
        );
      } on FormatException {
        return _broken();
      }
    }
    if (source.startsWith('http://') || source.startsWith('https://')) {
      return Image.network(
        source,
        fit: BoxFit.contain,
        loadingBuilder: (BuildContext context, Widget child, ImageChunkEvent? progress) {
          if (progress == null) return child;
          final int? total = progress.expectedTotalBytes;
          return Center(
            child: Text(
              total == null
                  ? '加载中…'
                  : '加载中 ${(progress.cumulativeBytesLoaded / total * 100).toStringAsFixed(0)}%',
              style: const TextStyle(color: Colors.white70, fontSize: 12),
            ),
          );
        },
        errorBuilder: (BuildContext context, Object error, StackTrace? stack) => _broken(),
      );
    }
    return _broken();
  }

  Widget _broken() => const Center(
        child: Text('图片无法显示', style: TextStyle(color: Colors.white70, fontSize: 12)),
      );
}

/// 打开全屏看图。
///
/// 用淡入淡出而不是平台默认的左右滑入：看图页是黑底全屏，滑入会把聊天页
/// 拖在边上形成一条白边（浅色主题下尤其明显）。
void openImageViewer(BuildContext context, List<String> images, int initialIndex) {
  if (images.isEmpty) return;
  Navigator.of(context).push(
    PageRouteBuilder<void>(
      opaque: true,
      transitionDuration: const Duration(milliseconds: 160),
      pageBuilder: (BuildContext context, Animation<double> animation, Animation<double> secondary) =>
          ImageViewerPage(images: images, initialIndex: initialIndex),
      transitionsBuilder: (BuildContext context, Animation<double> animation, Animation<double> secondary, Widget child) =>
          FadeTransition(opacity: animation, child: child),
    ),
  );
}
