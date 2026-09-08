import { SessionManager } from "@earendil-works/pi-coding-agent";

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

  function age(value: Date): string {
    const ms = Math.max(0, Date.now() - value.getTime());
    const min = Math.floor(ms / 60000);
    if (min < 1) return "now";
    if (min < 60) return `${min}m`;
    const hours = Math.floor(min / 60);
    if (hours < 24) return `${hours}h`;
    const days = Math.floor(hours / 24);
    return `${days}d`;
  }

  pi.registerCommand("resume", {
    description: "Pick and resume a previous Pi session on Android",
    handler: async (_args: string, ctx: any) => {
      await ctx.waitForIdle();
      const cwd = ctx.sessionManager.getCwd();
      const sessionDir = ctx.sessionManager.getSessionDir?.();
      const currentPath = ctx.sessionManager.getSessionFile?.();
      const sessions = (await SessionManager.list(cwd, sessionDir))
        .filter((session: any) => session.path !== currentPath)
        .sort((a: any, b: any) => b.modified.getTime() - a.modified.getTime())
        .slice(0, 60);

      if (!sessions.length) {
        ctx.ui.notify("当前目录没有其他可继续的 session", "info");
        return;
      }

      const labels = sessions.map((session: any, index: number) => {
        const title = preview(session.name || session.firstMessage, 46);
        return `${index + 1}. ${title}  · ${session.messageCount} msg · ${age(session.modified)}`;
      });
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
          label: `${index + 1}. ${preview(textOf(entry.message.content))}  · ${String(entry.id).slice(0, 8)}`,
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
