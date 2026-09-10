import { constants, copyFileSync, existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { execFile } from "node:child_process";
import { homedir, tmpdir } from "node:os";
import { basename, dirname, extname, join, parse, resolve } from "node:path";
import { promisify } from "node:util";
import { ProjectTrustStore } from "@earendil-works/pi-coding-agent";

const execFileAsync = promisify(execFile);

export default function (pi: any) {
  function textOf(content: any): string {
    if (typeof content === "string") return content;
    if (!Array.isArray(content)) return "";
    return content
      .filter((part) => part && part.type === "text")
      .map((part) => String(part.text || ""))
      .join("");
  }

  function preview(value: string, limit = 92): string {
    const clean = value.replace(/\s+/g, " ").trim();
    return clean.length > limit ? clean.slice(0, limit) + "…" : clean || "(empty)";
  }

  function pathArgument(value: string): string {
    const trimmed = value.trim();
    if (trimmed.length >= 2 && ((trimmed.startsWith('"') && trimmed.endsWith('"')) || (trimmed.startsWith("'") && trimmed.endsWith("'")))) {
      return trimmed.slice(1, -1);
    }
    return trimmed;
  }

  function resolveUserPath(value: string, cwd: string): string {
    const trimmed = pathArgument(value);
    if (trimmed === "~") return homedir();
    if (trimmed.startsWith("~/")) return join(homedir(), trimmed.slice(2));
    return resolve(cwd, trimmed);
  }

  function writeActiveBranch(ctx: any, outputPath: string): string {
    const source = ctx.sessionManager.getSessionFile();
    const header = source && existsSync(source)
      ? JSON.parse(readFileSync(source, "utf8").split(/\r?\n/, 1)[0])
      : {
          type: "session",
          version: 3,
          id: ctx.sessionManager.getSessionId(),
          timestamp: new Date().toISOString(),
          cwd: ctx.cwd,
        };
    const lines = [JSON.stringify({ ...header, cwd: ctx.cwd })];
    let parentId: string | null = null;
    for (const entry of ctx.sessionManager.getBranch()) {
      lines.push(JSON.stringify({ ...entry, parentId }));
      parentId = String(entry.id);
    }
    mkdirSync(dirname(outputPath), { recursive: true });
    writeFileSync(outputPath, `${lines.join("\n")}\n`, { mode: 0o600 });
    return outputPath;
  }

  async function exportActiveBranch(ctx: any, requested: string): Promise<string> {
    const stamp = new Date().toISOString().replace(/[:.]/g, "-");
    const outputPath = resolveUserPath(requested || `pi-session-${stamp}.html`, ctx.cwd);
    if (extname(outputPath).toLowerCase() === ".jsonl") return writeActiveBranch(ctx, outputPath);

    const htmlPath = extname(outputPath) ? outputPath : `${outputPath}.html`;
    const temporaryJsonl = join(tmpdir(), `pi-android-export-${process.pid}-${Date.now()}.jsonl`);
    writeActiveBranch(ctx, temporaryJsonl);
    try {
      const piExecutable = join(process.env.PREFIX || "/data/data/com.termux/files/usr", "bin", "pi");
      await execFileAsync(piExecutable, ["--export", temporaryJsonl, htmlPath], {
        cwd: ctx.cwd,
        env: process.env,
        timeout: 120_000,
        maxBuffer: 4 * 1024 * 1024,
      });
      return htmlPath;
    } finally {
      try { await import("node:fs/promises").then((fs) => fs.unlink(temporaryJsonl)); } catch {}
    }
  }

  function userPoints(ctx: any) {
    return ctx.sessionManager.getEntries()
      .filter((entry: any) => entry?.type === "message" && entry?.message?.role === "user")
      .map((entry: any, index: number) => ({
        id: String(entry.id),
        label: `${index + 1}. ${preview(textOf(entry.message.content), 72)} · ${String(entry.id).slice(0, 8)}`,
      }));
  }

  function entryText(entry: any): string {
    if (!entry) return "";
    if (entry.type === "message") {
      const message = entry.message || {};
      if (message.role === "user") return `user: ${preview(textOf(message.content))}`;
      if (message.role === "assistant") {
        const text = textOf(message.content);
        if (text.trim()) return `assistant: ${preview(text)}`;
        if (message.stopReason === "aborted") return "assistant: (aborted)";
        if (message.errorMessage) return `assistant: ${preview(String(message.errorMessage))}`;
        return "assistant: (tool call)";
      }
      if (message.role === "toolResult") return `[${message.toolName || "tool"}]`;
      if (message.role === "bashExecution") return `[bash]: ${preview(String(message.command || ""))}`;
      return `[${message.role || "message"}]`;
    }
    if (entry.type === "custom_message") return `[${entry.customType || "custom"}]: ${preview(textOf(entry.content))}`;
    if (entry.type === "compaction") return `[compaction: ${Math.round(Number(entry.tokensBefore || 0) / 1000)}k tokens]`;
    if (entry.type === "branch_summary") return `[branch summary]: ${preview(String(entry.summary || ""))}`;
    if (entry.type === "model_change") return `[model: ${entry.provider || "?"}/${entry.modelId || "?"}]`;
    if (entry.type === "thinking_level_change") return `[thinking: ${entry.thinkingLevel || "off"}]`;
    if (entry.type === "session_info") return `[session: ${entry.name || "unnamed"}]`;
    if (entry.type === "custom") return `[${entry.customType || "custom state"}]`;
    return `[${String(entry.type || "entry").replace(/_/g, " ")}]`;
  }

  // Pi's tree is a conversation tree, not an execution log.  Assistant tool-call
  // messages, tool results, bash executions, compaction and internal extension
  // entries are kept in the session file but are not branch points in /tree.
  // Collapsing those invisible nodes below preserves the real user-message
  // parent/child structure, including branches created after tool runs.
  function isVisibleEntry(entry: any, _isLeaf: boolean): boolean {
    return entry?.type === "message" && entry?.message?.role === "user";
  }

  function treePoints(ctx: any) {
    const roots = ctx.sessionManager.getTree() || [];
    const leafId = ctx.sessionManager.getLeafId();
    const allById = new Map<string, any>();

    const indexAll = (nodes: any[]) => {
      for (const node of nodes) {
        allById.set(String(node.entry.id), node);
        indexAll(node.children || []);
      }
    };
    indexAll(roots);

    const active = new Set<string>();
    let activeId: string | null = leafId ? String(leafId) : null;
    while (activeId) {
      active.add(activeId);
      const node = allById.get(activeId);
      if (node?.entry?.type === "custom" && node.entry.customType === "__android_tree_edit__" && node.entry.data?.targetId) {
        active.add(String(node.entry.data.targetId));
      }
      activeId = node?.entry?.parentId == null ? null : String(node.entry.parentId);
    }

    const ordered = (nodes: any[]) => [...nodes].sort(
      (a, b) => Number(active.has(String(b.entry.id))) - Number(active.has(String(a.entry.id))),
    );

    const visibleById = new Map<string, any>();
    const visibleRoots: any[] = [];
    const collectVisible = (nodes: any[], visibleParent: any | null) => {
      for (const node of ordered(nodes)) {
        const id = String(node.entry.id);
        const visible = isVisibleEntry(node.entry, id === leafId);
        const parent = visible ? node : visibleParent;
        if (visible) {
          const copy = { node, children: [] as any[] };
          visibleById.set(id, copy);
          if (visibleParent) visibleById.get(String(visibleParent.entry.id))?.children.push(copy);
          else visibleRoots.push(copy);
        }
        collectVisible(node.children || [], parent);
      }
    };
    collectVisible(roots, null);

    const points: any[] = [];
    const render = (item: any, prefix: string, connector: string) => {
      const node = item.node;
      const id = String(node.entry.id);
      const pathMark = active.has(id) ? "● " : "  ";
      const label = node.label ? `[${node.label}] ` : "";
      const line = `${prefix}${connector}${pathMark}${label}${entryText(node.entry)} · ${id.slice(0, 8)}`;
      points.push({ id, label: line, entry: node.entry, entryLabel: node.label });

      const childPrefix = connector === "├─ " ? `${prefix}│  ` : connector === "└─ " ? `${prefix}   ` : prefix;
      if (item.children.length === 1) {
        render(item.children[0], childPrefix, "");
      } else {
        item.children.forEach((child: any, index: number) => {
          render(child, childPrefix, index === item.children.length - 1 ? "└─ " : "├─ ");
        });
      }
    };
    visibleRoots.forEach((root, index) => {
      render(root, "", visibleRoots.length > 1 ? (index === visibleRoots.length - 1 ? "└─ " : "├─ ") : "");
    });
    return points;
  }

  async function chooseUserPoint(ctx: any, title: string) {
    const points = userPoints(ctx);
    if (!points.length) {
      ctx.ui.notify("当前 session 还没有用户消息", "info");
      return undefined;
    }
    const selected = await ctx.ui.select(title, points.map((point: any) => point.label));
    return points.find((point: any) => point.label === selected);
  }

  async function chooseTreePoint(ctx: any) {
    const points = treePoints(ctx);
    if (!points.length) {
      ctx.ui.notify("当前 session 还没有可导航的条目", "info");
      return undefined;
    }
    const selected = await ctx.ui.select("Session Tree", points.map((point: any) => point.label));
    return points.find((point: any) => point.label === selected);
  }

  pi.registerCommand("tree", {
    description: "Navigate the user-message session tree",
    handler: async (args: string, ctx: any) => {
      await ctx.waitForIdle();
      const requested = String(args || "").trim();
      let target = requested
        ? treePoints(ctx).find((point: any) => point.id === requested || point.id.startsWith(requested))
        : await chooseTreePoint(ctx);
      if (!target) return;

      const physicalLeaf = ctx.sessionManager.getLeafEntry();
      const logicalLeafId = physicalLeaf?.type === "custom" && physicalLeaf.customType === "__android_tree_edit__"
        ? String(physicalLeaf.data?.targetId || "")
        : physicalLeaf?.type === "label"
          ? String(physicalLeaf.parentId || "")
          : String(ctx.sessionManager.getLeafId() || "");
      if (target.id === logicalLeafId) {
        if (physicalLeaf?.type === "custom" && physicalLeaf.customType === "__android_tree_edit__") {
          ctx.ui.setEditorText(String(physicalLeaf.data?.editorText ?? ""));
        }
        ctx.ui.notify("已经位于这个节点", "info");
        return;
      }

      const editableText = target.entry?.type === "message" && target.entry?.message?.role === "user"
        ? textOf(target.entry.message.content)
        : target.entry?.type === "custom_message"
          ? textOf(target.entry.content)
          : undefined;
      const result = await ctx.navigateTree(target.id, { summarize: false });
      if (result.cancelled) {
        ctx.ui.notify("/tree 已取消", "warning");
        return;
      }

      // Persist immediately without entering model context. User selections branch from
      // the parent and retain their editable text even when Android or Pi is restarted.
      const selectedLeaf = ctx.sessionManager.getLeafId();
      if (editableText !== undefined) {
        ctx.sessionManager.appendCustomEntry("__android_tree_edit__", { targetId: target.id, editorText: editableText });
      } else if (selectedLeaf) {
        ctx.sessionManager.appendLabelChange(String(selectedLeaf), target.entryLabel);
      }

      if (editableText !== undefined) ctx.ui.setEditorText(editableText);
      ctx.ui.notify("ANDROID_SESSION_SWITCHED", "info");
    },
  });

  pi.registerCommand("fork", {
    description: "Fork from an earlier user message on Android",
    handler: async (args: string, ctx: any) => {
      await ctx.waitForIdle();
      const points = userPoints(ctx);
      const requested = String(args || "").trim();
      const target = requested
        ? points.find((point: any) => point.id === requested || point.id.startsWith(requested))
        : await chooseUserPoint(ctx, "Fork from message");
      if (!target) return;
      const result = await ctx.fork(target.id, { position: "before" });
      if (result.cancelled) ctx.ui.notify("/fork 已取消", "warning");
    },
  });

  pi.registerCommand("name", {
    description: "Set the current session name on Android",
    handler: async (args: string, ctx: any) => {
      const requested = String(args || "").trim();
      const name = requested || await ctx.ui.input("Session name", "输入会话名称");
      if (name === undefined) return;
      pi.setSessionName(String(name).trim());
      ctx.ui.notify(`会话名称：${String(name).trim() || "(none)"}`, "info");
    },
  });

  pi.registerCommand("export", {
    description: "Export the active branch to HTML or JSONL",
    handler: async (args: string, ctx: any) => {
      await ctx.waitForIdle();
      try {
        const output = await exportActiveBranch(ctx, pathArgument(String(args || "")));
        ctx.ui.notify(`已导出当前分支：${output}`, "info");
      } catch (error: any) {
        ctx.ui.notify(`导出失败：${error?.message || error}`, "error");
      }
    },
  });

  pi.registerCommand("import", {
    description: "Import and resume a JSONL session",
    handler: async (args: string, ctx: any) => {
      await ctx.waitForIdle();
      const input = pathArgument(String(args || ""));
      if (!input) {
        ctx.ui.notify("用法：/import <path.jsonl>", "warning");
        return;
      }
      const source = resolveUserPath(input, ctx.cwd);
      if (!existsSync(source) || extname(source).toLowerCase() !== ".jsonl") {
        ctx.ui.notify(`找不到 JSONL session：${source}`, "error");
        return;
      }
      const confirmed = await ctx.ui.confirm("Import session", `导入并切换到 ${source}？`);
      if (!confirmed) return;

      const currentFile = ctx.sessionManager.getSessionFile();
      const sessionDir = currentFile ? dirname(currentFile) : join(homedir(), ".pi", "agent", "sessions");
      mkdirSync(sessionDir, { recursive: true });
      let destination = join(sessionDir, basename(source));
      if (resolve(source) !== resolve(destination)) {
        const parts = parse(destination);
        let suffix = 1;
        while (existsSync(destination)) destination = join(parts.dir, `${parts.name}-${suffix++}${parts.ext}`);
        copyFileSync(source, destination, constants.COPYFILE_EXCL);
      }
      const importedFrom = source;
      const result = await ctx.switchSession(destination, {
        withSession: async (next: any) => {
          next.ui.notify(`已导入 session：${importedFrom}`, "info");
          next.ui.notify("ANDROID_SESSION_SWITCHED", "info");
        },
      });
      if (result.cancelled) ctx.ui.notify("导入已取消", "warning");
    },
  });

  pi.registerCommand("share", {
    description: "Share the active branch as a private GitHub gist",
    handler: async (_args: string, ctx: any) => {
      await ctx.waitForIdle();
      const confirmed = await ctx.ui.confirm("Share session", "将当前分支导出并创建私密 GitHub Gist？");
      if (!confirmed) return;
      const directory = join(tmpdir(), `pi-android-share-${process.pid}-${Date.now()}`);
      mkdirSync(directory, { recursive: true });
      const html = join(directory, "session.html");
      try {
        await exportActiveBranch(ctx, html);
        const { stdout } = await execFileAsync("gh", ["gist", "create", "--public=false", html], {
          cwd: ctx.cwd,
          env: process.env,
          timeout: 120_000,
          maxBuffer: 1024 * 1024,
        });
        const gistUrl = stdout.trim();
        const gistId = gistUrl.split("/").pop();
        if (!gistId) throw new Error("无法读取 Gist ID");
        ctx.ui.notify(`分享链接：https://pi.dev/session/#${gistId}\nGist：${gistUrl}`, "info");
      } catch (error: any) {
        ctx.ui.notify(`分享失败：${error?.message || error}。请先在 Termux 运行 gh auth login。`, "error");
      } finally {
        try { await import("node:fs/promises").then((fs) => fs.rm(directory, { recursive: true, force: true })); } catch {}
      }
    },
  });

  pi.registerCommand("trust", {
    description: "Save the project trust decision",
    handler: async (_args: string, ctx: any) => {
      const store = new ProjectTrustStore(join(homedir(), ".pi", "agent"));
      const existing = store.get(ctx.cwd);
      const choice = await ctx.ui.select(`Project trust · 当前：${existing === null ? "未设置" : existing ? "trusted" : "untrusted"}`, [
        "Trust this directory",
        "Do not trust this directory",
        "Clear saved decision",
      ]);
      if (!choice) return;
      store.set(ctx.cwd, choice === "Clear saved decision" ? null : choice === "Trust this directory");
      ctx.ui.notify("信任设置已保存；执行 /reload 或重新连接后生效", "info");
    },
  });

  pi.registerCommand("reload", {
    description: "Reload extensions, skills, prompts, themes, and context files",
    handler: async (_args: string, ctx: any) => {
      await ctx.waitForIdle();
      await ctx.reload();
      ctx.ui.notify("资源已重新加载", "info");
    },
  });

  pi.registerCommand("login", {
    description: "Show secure provider authentication instructions",
    handler: async (_args: string, ctx: any) => {
      ctx.ui.notify("为避免在 Android 对话记录中暴露密钥，认证请在 Termux 原版 Pi 中执行 /login；完成后回到这里执行 /reload。", "warning");
    },
  });

  pi.registerCommand("logout", {
    description: "Show secure provider logout instructions",
    handler: async (_args: string, ctx: any) => {
      ctx.ui.notify("凭据删除请在 Termux 原版 Pi 中执行 /logout；完成后回到这里执行 /reload。", "warning");
    },
  });

  pi.registerCommand("__android_checkpoint", {
    description: "Persist the active branch before an Android bridge upgrade",
    handler: async (_args: string, ctx: any) => {
      await ctx.waitForIdle();
      const leafId = ctx.sessionManager.getLeafId();
      if (!leafId) return;
      let currentLabel: string | undefined;
      const visit = (nodes: any[]) => {
        for (const node of nodes || []) {
          if (String(node.entry?.id) === String(leafId)) currentLabel = node.label;
          visit(node.children || []);
        }
      };
      visit(ctx.sessionManager.getTree());
      ctx.sessionManager.appendLabelChange(String(leafId), currentLabel);
    },
  });

  pi.registerCommand("quit", {
    description: "Gracefully stop the current Pi RPC process",
    handler: async (_args: string, ctx: any) => {
      const confirmed = await ctx.ui.confirm("Quit Pi", "保存 session 并停止当前 Agent？");
      if (confirmed) ctx.shutdown();
    },
  });
}
