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
    return `[${String(entry.type || "entry").replace(/_/g, " ")}]`;
  }

  function isVisibleEntry(entry: any, isLeaf: boolean): boolean {
    if (!entry) return false;
    if (isLeaf) return true;
    if (entry.type === "message") {
      if (entry.message?.role !== "assistant") return true;
      const hasText = textOf(entry.message.content).trim().length > 0;
      const stoppedBadly = entry.message.stopReason && !["stop", "toolUse"].includes(entry.message.stopReason);
      return hasText || stoppedBadly;
    }
    return ["custom_message", "compaction", "branch_summary"].includes(entry.type);
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
      points.push({ id, label: line, entry: node.entry });

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
    description: "Navigate the current in-file session tree",
    handler: async (args: string, ctx: any) => {
      await ctx.waitForIdle();
      const requested = String(args || "").trim();
      let target = requested
        ? treePoints(ctx).find((point: any) => point.id === requested || point.id.startsWith(requested))
        : await chooseTreePoint(ctx);
      if (!target) return;

      if (target.id === ctx.sessionManager.getLeafId()) {
        ctx.ui.notify("已经位于这个节点", "info");
        return;
      }

      let summarize = false;
      let customInstructions: string | undefined;
      while (true) {
        const choice = await ctx.ui.select("Summarize branch?", [
          "No summary",
          "Summarize",
          "Summarize with custom prompt",
        ]);
        if (choice === undefined) {
          target = await chooseTreePoint(ctx);
          if (!target) return;
          if (target.id === ctx.sessionManager.getLeafId()) {
            ctx.ui.notify("已经位于这个节点", "info");
            return;
          }
          continue;
        }
        summarize = choice !== "No summary";
        if (choice === "Summarize with custom prompt") {
          const custom = await ctx.ui.editor("Custom summarization instructions", "");
          if (custom === undefined) continue;
          customInstructions = String(custom);
        }
        break;
      }

      const editableText = target.entry?.type === "message" && target.entry?.message?.role === "user"
        ? textOf(target.entry.message.content)
        : target.entry?.type === "custom_message"
          ? textOf(target.entry.content)
          : undefined;
      const result = await ctx.navigateTree(target.id, { summarize, customInstructions });
      if (result.cancelled) {
        ctx.ui.notify("/tree 已取消", "warning");
        return;
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
}
