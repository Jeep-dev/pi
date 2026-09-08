export default function (pi: any) {
  function textOf(content: any): string {
    if (typeof content === "string") return content;
    if (!Array.isArray(content)) return "";
    return content
      .filter((part) => part && part.type === "text")
      .map((part) => String(part.text || ""))
      .join(" ");
  }

  function preview(value: string, max = 54): string {
    const clean = value.replace(/\s+/g, " ").trim();
    return clean.length > max ? clean.slice(0, max) + "…" : clean || "(empty message)";
  }

  function ageMs(ms: number): string {
    const diff = Math.max(0, Date.now() - ms);
    const min = Math.floor(diff / 60000);
    if (min < 1) return "now";
    if (min < 60) return `${min}m`;
    const hours = Math.floor(min / 60);
    if (hours < 24) return `${hours}h`;
    return `${Math.floor(hours / 24)}d`;
  }

  async function loadSessionChoices(ctx: any) {
    // Do not import pi's npm package here. The Android app must work with both
    // older @mariozechner and newer @earendil-works Pi installations.
    const { readdir, open, stat } = await import("node:fs/promises");
    const path = await import("node:path");
    const sessionDir = ctx.sessionManager.getSessionDir();
    const currentPath = ctx.sessionManager.getSessionFile?.();
    const names = (await readdir(sessionDir)).filter((name: string) => name.endsWith(".jsonl"));

    const withStats = await Promise.all(
      names.map(async (name: string) => {
        const file = path.join(sessionDir, name);
        try {
          const info = await stat(file);
          return { file, mtime: info.mtimeMs };
        } catch {
          return null;
        }
      }),
    );

    const recent = withStats
      .filter((item: any) => item && item.file !== currentPath)
      .sort((a: any, b: any) => b.mtime - a.mtime)
      .slice(0, 50);

    const choices = [] as any[];
    for (const item of recent) {
      let firstMessage = "(no messages)";
      let sessionName = "";
      let messageCount = 0;
      try {
        // Read only the first 256 KiB so /resume stays responsive on large sessions.
        const handle = await open(item.file, "r");
        try {
          const buffer = Buffer.alloc(256 * 1024);
          const { bytesRead } = await handle.read(buffer, 0, buffer.length, 0);
          const lines = buffer.subarray(0, bytesRead).toString("utf8").split("\n");
          for (const line of lines) {
            if (!line.trim()) continue;
            let entry: any;
            try { entry = JSON.parse(line); } catch { continue; }
            if (entry?.type === "session_info" && typeof entry.name === "string") sessionName = entry.name;
            if (entry?.type === "message") {
              messageCount++;
              if (firstMessage === "(no messages)" && entry?.message?.role === "user") {
                firstMessage = textOf(entry.message.content) || firstMessage;
              }
            }
          }
        } finally {
          await handle.close();
        }
      } catch {}
      choices.push({
        path: item.file,
        title: preview(sessionName || firstMessage, 46),
        messageCount,
        mtime: item.mtime,
      });
    }
    return choices;
  }

  pi.registerCommand("resume", {
    description: "Pick and resume a previous Pi session on Android",
    handler: async (_args: string, ctx: any) => {
      await ctx.waitForIdle();
      let sessions: any[] = [];
      try {
        sessions = await loadSessionChoices(ctx);
      } catch (error: any) {
        ctx.ui.notify(`读取 sessions 失败：${String(error?.message || error)}`, "error");
        return;
      }

      if (!sessions.length) {
        ctx.ui.notify("当前目录没有其他可继续的 session", "info");
        return;
      }

      const labels = sessions.map((session: any, index: number) =>
        `${index + 1}. ${session.title} · ${session.messageCount || "?"} msg · ${ageMs(session.mtime)}`,
      );
      const selected = await ctx.ui.select("Resume session", labels);
      if (!selected) return;
      const target = sessions[labels.indexOf(selected)];
      if (!target) return;

      const result = await ctx.switchSession(target.path, {
        withSession: async (nextCtx: any) => {
          nextCtx.ui.notify("ANDROID_SESSION_SWITCHED", "info");
        },
      });
      if (result.cancelled) ctx.ui.notify("/resume 已取消", "warning");
    },
  });

  pi.registerCommand("tree", {
    description: "Navigate the current session tree on Android",
    handler: async (args: string, ctx: any) => {
      await ctx.waitForIdle();
      const entries = ctx.sessionManager.getEntries();
      const points = entries
        .filter((entry: any) => entry?.type === "message" && entry?.message?.role === "user")
        .map((entry: any, index: number) => ({
          id: String(entry.id),
          label: `${index + 1}. ${preview(textOf(entry.message.content))} · ${String(entry.id).slice(0, 8)}`,
        }));

      if (!points.length) {
        ctx.ui.notify("当前 session 还没有可跳转的用户消息", "info");
        return;
      }

      const requested = String(args || "").trim();
      let target = requested ? points.find((point: any) => point.id === requested || point.id.startsWith(requested)) : undefined;
      if (!target) {
        const selected = await ctx.ui.select("Session Tree", points.map((point: any) => point.label));
        if (!selected) return;
        target = points.find((point: any) => point.label === selected);
      }
      if (!target) return;

      const result = await ctx.navigateTree(target.id, { summarize: false });
      if (result.cancelled) ctx.ui.notify("/tree 已取消", "warning");
      else ctx.ui.notify(`已跳转到 ${target.id.slice(0, 8)}`, "info");
    },
  });

  pi.registerCommand("fork", {
    description: "Fork from an earlier user message on Android",
    handler: async (_args: string, ctx: any) => {
      await ctx.waitForIdle();
      const entries = ctx.sessionManager.getEntries();
      const points = entries
        .filter((entry: any) => entry?.type === "message" && entry?.message?.role === "user")
        .map((entry: any, index: number) => ({
          id: String(entry.id),
          label: `${index + 1}. ${preview(textOf(entry.message.content))}`,
        }));
      if (!points.length) {
        ctx.ui.notify("当前 session 没有可 fork 的用户消息", "info");
        return;
      }
      const selected = await ctx.ui.select("Fork from message", points.map((point: any) => point.label));
      if (!selected) return;
      const target = points.find((point: any) => point.label === selected);
      if (!target) return;
      const result = await ctx.fork(target.id, {
        withSession: async (nextCtx: any) => nextCtx.ui.notify("ANDROID_SESSION_SWITCHED", "info"),
      });
      if (result.cancelled) ctx.ui.notify("/fork 已取消", "warning");
    },
  });

  pi.registerCommand("name", {
    description: "Set the current session display name",
    handler: async (args: string, ctx: any) => {
      let name = String(args || "").trim();
      if (!name) {
        const entered = await ctx.ui.input("Session name", "输入会话名称");
        if (entered === undefined) return;
        name = String(entered).trim();
      }
      pi.setSessionName(name || undefined);
      ctx.ui.notify(name ? `Session name: ${name}` : "Session name cleared", "info");
    },
  });
}
