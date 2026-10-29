/**
 * 相机扫码 + 相册二维码解码。
 *
 * 两条路线（都只在国内常见的无 GMS 机型上必须能跑通）：
 *
 *   1. `BarcodeScanner.scan()`——Google 原生全屏扫描器，一个调用搞定（自带手电筒）。
 *      依赖 Google Play Services 的 code scanner 模块；华为等无 GMS 机型直接不可用。
 *   2. `BarcodeScanner.startScan()`——用插件自带的 ML Kit 模型（com.google.mlkit），
 *      相机画面铺在 WebView 下面，取景框由我们自绘（ui/scan-overlay.ts）。
 *
 * 先试 1，不可用/报错就退到 2。用户主动取消不会被当成失败（不会突然又弹一次取景框）。
 *
 * 相册解码不走插件：`readBarcodesFromImage` 只吃本地文件路径，
 * 而 `<input type="file">` 拿到的是 File 对象，用 jsQR 在 canvas 上解更直接，
 * 顺带让浏览器 dev 环境也能验证解码逻辑。
 */

import { Capacitor } from "@capacitor/core";
import type { Barcode } from "@capacitor-mlkit/barcode-scanning";

import { createScanOverlay } from "../ui/scan-overlay.ts";

export type ScanOutcome =
  | { kind: "text"; text: string }
  | { kind: "cancelled" }
  | { kind: "failed"; message: string };

/**
 * 两个重依赖都按需加载：
 *   - 插件 web 实现只为了拿 BarcodeFormat 常量与调用壳
 *   - jsQR 的 dist 里带了一份 demo，整包 ~250KB；只有「从相册选图」才用得上
 * 首屏（配对页）不该为这两种情况付出体积。
 */
type ScannerModule = typeof import("@capacitor-mlkit/barcode-scanning");
let scannerModule: Promise<ScannerModule> | null = null;
function loadScanner(): Promise<ScannerModule> {
  scannerModule ??= import("@capacitor-mlkit/barcode-scanning");
  return scannerModule;
}

/** 相册图片解码前先把长边压到这个尺寸：够清晰，又快。 */
const MAX_DECODE_EDGE = 1600;

/** 从扫码结果里挑第一条可用文本。 */
export function firstBarcodeText(barcodes: readonly Barcode[] | undefined): string {
  for (const barcode of barcodes ?? []) {
    const text = (barcode.rawValue ?? barcode.displayValue ?? "").trim();
    if (text) return text;
  }
  return "";
}

function isCancelError(error: unknown): boolean {
  const message = error instanceof Error ? error.message : String(error);
  return /cancel|abort|取消/i.test(message);
}

function describeScanError(error: unknown): string {
  const message = error instanceof Error ? error.message : String(error);
  if (/not implemented|not available|not supported|unimplemented/i.test(message)) {
    return "这个版本的 App 不支持相机扫码，请改用「从相册选图」或升级 App";
  }
  return `扫码失败：${message}`;
}

/**
 * 用相机扫一个二维码。
 *
 * 注意：调用方不需要自己管理权限/遮罩，全部在这里收尾。
 */
export async function scanQrFromCamera(): Promise<ScanOutcome> {
  if (!Capacitor.isNativePlatform()) {
    return {
      kind: "failed",
      message: "浏览器里没有相机扫码（缺 Barcode Detection API），请用「从相册选图」或直接粘贴链接",
    };
  }

  let scanner: ScannerModule;
  try {
    scanner = await loadScanner();
    const { supported } = await scanner.BarcodeScanner.isSupported();
    if (!supported) return { kind: "failed", message: "这台设备没有可用相机" };
  } catch (error) {
    // 旧 APK 里没有注册插件时 getPlatform 有了、插件却没有
    return { kind: "failed", message: describeScanError(error) };
  }

  const google = await scanWithGoogleUi(scanner);
  if (google) return google;
  return scanWithInlineCamera(scanner);
}

/**
 * 路线 1：Google 原生扫描界面。
 * @returns 用户取消 / 成功的结果；不可用时返回 null（交给路线 2）
 */
async function scanWithGoogleUi(scanner: ScannerModule): Promise<ScanOutcome | null> {
  const { BarcodeFormat, BarcodeScanner } = scanner;
  try {
    const { available } = await BarcodeScanner.isGoogleBarcodeScannerModuleAvailable();
    if (!available) return null;
    const { barcodes } = await BarcodeScanner.scan({
      formats: [BarcodeFormat.QrCode],
      autoZoom: true,
    });
    const text = firstBarcodeText(barcodes);
    // 界面上取消不会走到这里（走 catch 的 cancel），拿不到码就当作取消
    return text ? { kind: "text", text } : { kind: "cancelled" };
  } catch (error) {
    if (isCancelError(error)) return { kind: "cancelled" };
    console.warn("[qr-scan] Google 扫码界面不可用，回退到内置取景框", error);
    return null;
  }
}

async function ensureCameraPermission(scanner: ScannerModule): Promise<ScanOutcome | null> {
  const { BarcodeScanner } = scanner;
  const status = await BarcodeScanner.checkPermissions().catch(() => null);
  if (status?.camera === "granted") return null;
  if (status?.camera === "denied") {
    return { kind: "failed", message: "相机权限被拒绝：系统设置 → 应用 → PiDeck → 权限，打开相机" };
  }
  const requested = await BarcodeScanner.requestPermissions().catch(() => null);
  if (requested?.camera === "granted") return null;
  return { kind: "failed", message: "没有相机权限，无法扫码；也可以用「从相册选图」" };
}

/**
 * 路线 2：自带 ML Kit + 自绘取景框。
 * 相机画面在 WebView 之下，靠 body.barcode-scanner-active 把页面藏起来。
 */
async function scanWithInlineCamera(scanner: ScannerModule): Promise<ScanOutcome> {
  const { BarcodeFormat, BarcodeScanner } = scanner;
  const denied = await ensureCameraPermission(scanner);
  if (denied) return denied;

  let resolveResult: (value: ScanOutcome) => void = () => {};
  const resultPromise = new Promise<ScanOutcome>((resolve) => {
    resolveResult = resolve;
  });

  const overlay = createScanOverlay({
    onCancel: () => resolveResult({ kind: "cancelled" }),
    onTorch: (on) => {
      const action = on ? BarcodeScanner.enableTorch() : BarcodeScanner.disableTorch();
      // 设备没有闪光灯时把按钮状态回滚，避免「点了没反应」
      void action.catch(() => overlay.setTorch(!on));
    },
  });

  const listener = await BarcodeScanner.addListener("barcodesScanned", (event) => {
    const text = firstBarcodeText(event.barcodes);
    if (text) resolveResult({ kind: "text", text });
  }).catch(() => null);

  try {
    await BarcodeScanner.startScan({ formats: [BarcodeFormat.QrCode] });
    const torch = await BarcodeScanner.isTorchAvailable().catch(() => ({ available: false }));
    overlay.setTorchAvailable(torch.available);
  } catch (error) {
    resolveResult({ kind: "failed", message: describeScanError(error) });
  }

  const result = await resultPromise;
  overlay.destroy();
  await listener?.remove().catch(() => undefined);
  await BarcodeScanner.stopScan().catch(() => undefined);
  return result;
}

async function loadBitmap(file: File): Promise<ImageBitmap | HTMLImageElement> {
  if (typeof createImageBitmap === "function") {
    try {
      return await createImageBitmap(file);
    } catch {
      // 某些 WebView 对 HEIC 之类不支持，退回 <img>
    }
  }
  const url = URL.createObjectURL(file);
  try {
    const image = new Image();
    await new Promise<void>((resolve, reject) => {
      image.onload = () => resolve();
      image.onerror = () => reject(new Error("图片解码失败"));
      image.src = url;
    });
    return image;
  } finally {
    URL.revokeObjectURL(url);
  }
}

function edgeLength(bitmap: ImageBitmap | HTMLImageElement): { width: number; height: number } {
  const width = bitmap.width;
  const height = bitmap.height;
  const longest = Math.max(width, height);
  if (longest <= MAX_DECODE_EDGE) return { width, height };
  const scale = MAX_DECODE_EDGE / longest;
  return { width: Math.round(width * scale), height: Math.round(height * scale) };
}

/** 从相册/截图里解二维码。浏览器与原生都能跑（纯 JS）。 */
export async function decodeQrFromImageFile(file: File): Promise<ScanOutcome> {
  try {
    const bitmap = await loadBitmap(file);
    const { width, height } = edgeLength(bitmap);
    if (width === 0 || height === 0) return { kind: "failed", message: "图片尺寸为 0，换一张试试" };

    const canvas = document.createElement("canvas");
    canvas.width = width;
    canvas.height = height;
    const context = canvas.getContext("2d", { willReadFrequently: true });
    if (!context) return { kind: "failed", message: "这个环境读不了图片（canvas 2d 不可用）" };

    context.drawImage(bitmap as CanvasImageSource, 0, 0, width, height);
    const { data } = context.getImageData(0, 0, width, height);
    const { default: jsQR } = await import("jsqr");
    // 反色也试一次：暗底白码的截图很常见
    const found = jsQR(data, width, height, { inversionAttempts: "attemptBoth" });
    if (!found?.data) return { kind: "failed", message: "这张图里没找到二维码，换一张或直接粘贴链接" };
    return { kind: "text", text: found.data };
  } catch (error) {
    return { kind: "failed", message: describeScanError(error) };
  }
}
