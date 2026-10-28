#!/usr/bin/env node
/**
 * 探针 CLI：不装任何依赖，直接对着真机 PiDeck 验证协议。
 *
 *   node src/cli/probe.ts health   --pair "http://192.168.1.20:8765/?token=..."
 *   node src/cli/probe.ts state    --base http://... --token ...
 *   node src/cli/probe.ts sessions --base ... --token ...
 *   node src/cli/probe.ts tail     --base ... --token ... --session <sessionId>
 *   node src/cli/probe.ts send     --base ... --token ... --session <id> "你好"
 *   node src/cli/probe.ts stop     --base ... --token ... --session <id>
 *
 * 也支持环境变量 PIDECK_BASE / PIDECK_TOKEN。令牌不会被打日志。
 */

import process from "node:process";
import { PiDeckClient } from "../api/client.ts";
import { describeError } from "../api/errors.ts";
import { sleep } from "../api/backoff.ts";
import { parsePairingInput } from "../pairing.ts";
import { SessionChannel } from "../session.ts";
import { StateStore } from "../state/store.ts";
import type { ChatBlock } from "../types.ts";

interface Args {
  command: string;
  positionals: string[];
  base?: string;
  token?: string;
  pair?: string;
  session?: string;
  json: boolean;
  timeoutMs: number;
}

function parseArgs(argv: string[]): Args {
  const args: Args = { command: argv[0] ?? "help", positionals: [], json: false, timeoutMs: 300_000 };
  for (let i = 1; i < argv.length; i += 1) {
    const arg = argv[i] as string;
    if (arg === "--json") {
      args.json = true;
      continue;
    }
    if (arg === "--base" || arg === "--token" || arg === "--pair" || arg === "--session") {
      const value = argv[i + 1];
      if (value === undefined) throw new Error(`${arg} 缺少值`);
      if (arg === "--base") args.base = value;
      else if (arg === "--token") args.token = value;
      else if (arg === "--pair") args.pair = value;
      else args.session = value;
      i += 1;
      continue;
    }
    if (arg === "--timeout") {
      const value = Number(argv[i + 1]);
      if (Number.isFinite(value) && value > 0) args.timeoutMs = value * 1000;
      i += 1;
      continue;
    }
    args.positionals.push(arg);
  }
  return args;
}

function resolveClient(args: Args): PiDeckClient {
  if (args.pair) {
    const payload = parsePairingInput(args.pair);
    return new PiDeckClient({ baseUrl: payload.baseUrl, token: payload.token });
  }
  const base = args.base ?? process.env.PIDECK_BASE ?? "";
  const token = args.token ?? process.env.PIDECK_TOKEN ?? "";
  if (!base || !token) {
    throw new Error("缺少连接参数：用 --pair <链接>，或 --base/--token，或环境变量 PIDECK_BASE/PIDECK_TOKEN");
  }
  return new PiDeckClient({ baseUrl: base, token });
}

const HELP = `pideck-mobile probe

用法：
  probe.ts health   [--pair <链接> | --base <url> --token <token>]
  probe.ts models   同连接参数 [--json]
  probe.ts state    同连接参数 [--json]
  probe.ts sessions 同连接参数 [--json]
  probe.ts tail     --session <sessionId> [--timeout 秒]
  probe.ts send     --session <sessionId> "消息内容" [--timeout 秒]
  probe.ts stop     --session <sessionId>

环境变量：PIDECK_BASE、PIDECK_TOKEN
`;

class StreamPrinter {
  private printed = new Map<string, number>();
  private reasoningShown = false;

  render(blocks: readonly ChatBlock[]): void {
    for (const block of blocks) {
      if (block.kind === "text") {
        const already = this.printed.get(block.id) ?? 0;
        if (block.text.length > already) {
          process.stdout.write(block.text.slice(already));
          this.printed.set(block.id, block.text.length);
        }
        if (block.done && !this.printed.has(`${block.id}:done`)) {
          process.stdout.write("\n");
          this.printed.set(`${block.id}:done`, 1);
        }
      } else if (block.kind === "reasoning") {
        if (!this.reasoningShown) {
          process.stdout.write("[思考] ");
          this.reasoningShown = true;
        }
      } else if (block.state !== "running" && !this.printed.has(block.toolCallId)) {
        this.printed.set(block.toolCallId, 1);
        const label = block.state === "ok" ? "完成" : "失败";
        process.stdout.write(`\n[工具 ${block.toolName} · ${label}]\n`);
      }
    }
  }
}

async function cmdHealth(client: PiDeckClient, args: Args): Promise<number> {
  const health = await client.health();
  if (args.json) {
    console.log(JSON.stringify(health, null, 2));
    return health.ok ? 0 : 1;
  }
  console.log(`服务：${health.service}  ${health.host}:${health.port}  ok=${health.ok}`);
  if (health.service !== "PiDeck") {
    console.log("⚠ 对面不是 PiDeck，配对地址可能填错了");
    return 1;
  }
  return 0;
}

async function cmdState(client: PiDeckClient, args: Args): Promise<number> {
  const { state } = await client.getState();
  if (args.json) {
    console.log(JSON.stringify(state, null, 2));
    return 0;
  }
  console.log(`项目 ${state.projects.length} · 会话 ${state.sessions.length} · 运行时 ${state.runtimes.length} · 待应答 ${state.pendingUiRequests.length}`);
  for (const project of state.projects) {
    const sessions = state.sessions.filter((s) => s.projectId === project.id);
    console.log(`  ${project.name ?? project.id}（${project.kind ?? "?"}，${sessions.length} 个会话）`);
  }
  return 0;
}

async function cmdSessions(client: PiDeckClient, args: Args): Promise<number> {
  const { state } = await client.getState();
  const rows = state.sessions
    .slice()
    .sort((a, b) => (b.updatedAt ?? 0) - (a.updatedAt ?? 0))
    .map((session) => ({
      id: session.id,
      title: session.title,
      status: session.status,
      backend: session.backend ?? "pi",
      model: session.model?.modelName ?? session.model?.modelId ?? "",
      updatedAt: session.updatedAt ?? 0,
    }));
  if (args.json) {
    console.log(JSON.stringify(rows, null, 2));
    return 0;
  }
  for (const row of rows) {
    const when = row.updatedAt ? new Date(row.updatedAt).toISOString().slice(0, 16).replace("T", " ") : "-";
    console.log(`${row.id}  [${row.status}/${row.backend}]  ${when}  ${row.title}`);
  }
  return 0;
}

async function cmdModels(client: PiDeckClient, args: Args): Promise<number> {
  const models = await client.listModels(true);
  if (args.json) {
    console.log(JSON.stringify(models, null, 2));
    return 0;
  }
  console.log(`共 ${models.length} 个模型`);
  for (const model of models) {
    const provider = model.provider ?? model.providerId ?? "?";
    const id = model.id ?? model.modelId ?? "?";
    console.log(`  ${provider} / ${id}  ${model.name ?? ""}`);
  }
  return 0;
}

async function cmdTail(client: PiDeckClient, args: Args): Promise<number> {
  const sessionId = requireSession(args);
  const printer = new StreamPrinter();
  const channel = new SessionChannel({
    client,
    sessionId,
    onUpdate: (c) => printer.render(c.reducer.blocks),
    onStatus: (status) => {
      if (status === "connected" || status === "reconnecting") process.stderr.write(`\n[${status}]\n`);
    },
    onError: (error) => process.stderr.write(`\n[错误] ${describeError(error)}\n`),
  });

  channel.start();
  const deadline = Date.now() + args.timeoutMs;
  while (Date.now() < deadline) {
    const status = channel.reducer.status;
    if (status === "finished" || status === "error") break;
    await sleep(100);
  }
  channel.close();
  process.stdout.write("\n");
  return channel.reducer.status === "error" ? 1 : 0;
}

async function cmdSend(client: PiDeckClient, args: Args): Promise<number> {
  const sessionId = requireSession(args);
  const message = args.positionals.join(" ").trim();
  if (!message) throw new Error("消息内容为空");

  const printer = new StreamPrinter();
  const channel = new SessionChannel({
    client,
    sessionId,
    onUpdate: (c) => printer.render(c.reducer.blocks),
    onError: (error) => process.stderr.write(`\n[错误] ${describeError(error)}\n`),
  });

  channel.start();
  await channel.send(message);

  const deadline = Date.now() + args.timeoutMs;
  while (Date.now() < deadline) {
    const status = channel.reducer.status;
    if (status === "finished" || status === "error") break;
    await sleep(100);
  }
  const finalStatus = channel.reducer.status;
  const error = channel.reducer.error;
  channel.close();
  process.stdout.write("\n");
  if (error) {
    console.error(`[失败] ${error}`);
    return 1;
  }
  return finalStatus === "finished" ? 0 : 2;
}

async function cmdStop(client: PiDeckClient, args: Args): Promise<number> {
  const sessionId = requireSession(args);
  const store = new StateStore({ client });
  await store.refresh();
  const target = store.targetOf(sessionId);
  if (!target) throw new Error("该会话没有运行中的实例，无需停止");
  await client.abort(sessionId, target);
  console.log(`已请求中止：${sessionId}（generation ${target.runtimeGeneration}）`);
  return 0;
}

function requireSession(args: Args): string {
  const sessionId = args.session ?? args.positionals[0];
  if (!sessionId) throw new Error("缺少 --session <sessionId>");
  return sessionId;
}

async function main(): Promise<number> {
  const args = parseArgs(process.argv.slice(2));
  if (args.command === "help" || args.command === "--help" || args.command === "-h") {
    console.log(HELP);
    return 0;
  }
  const client = resolveClient(args);
  switch (args.command) {
    case "health":
      return cmdHealth(client, args);
    case "state":
      return cmdState(client, args);
    case "sessions":
      return cmdSessions(client, args);
    case "models":
      return cmdModels(client, args);
    case "tail":
      return cmdTail(client, args);
    case "send":
      return cmdSend(client, args);
    case "stop":
      return cmdStop(client, args);
    default:
      console.error(`未知命令：${args.command}\n`);
      console.log(HELP);
      return 64;
  }
}

main()
  .then((code) => process.exit(code))
  .catch((error: unknown) => {
    console.error(`✗ ${describeError(error)}`);
    const code = (error as { status?: number }).status;
    process.exit(code === 401 ? 77 : 1);
  });
