export default function (pi: any) {
  function textOf(content: any): string {
    if (typeof content === "string") return content;
    if (!Array.isArray(content)) return "";
    return content
      .filter((part) => part && part.type === "text")
      .map((part) => String(part.text || ""))
      .join(" ");
  }

  function preview(value: string): string {
    const clean = value.replace(/\s+/g, " ").trim();
    return clean.length > 72 ? clean.slice(0, 72) + "…" : clean || "(empty message)";
  }

  function userPoints(ctx: any) {
    return ctx.sessionManager.getEntries()
      .filter((entry: any) => entry?.type === "message" && entry?.message?.role === "user")
      .map((entry: any, index: number) => ({
        id: String(entry.id),
        label: `${index + 1}. ${preview(textOf(entry.message.content))} · ${String(entry.id).slice(0, 8)}`,
      }));
  }

  async function choosePoint(ctx: any, title: string) {
    const points = userPoints(ctx);
    if (!points.length) {
      ctx.ui.notify("当前 session 还没有用户消息", "info");
      return undefined;
    }
    const selected = await ctx.ui.select(title, points.map((point: any) => point.label));
    return points.find((point: any) => point.label === selected);
  }

  pi.registerCommand("tree", {
    description: "Navigate the current session tree on Android",
    handler: async (args: string, ctx: any) => {
      await ctx.waitForIdle();
      const points = userPoints(ctx);
      const requested = String(args || "").trim();
      const target = requested
        ? points.find((point: any) => point.id === requested || point.id.startsWith(requested))
        : await choosePoint(ctx, "Session Tree");
      if (!target) return;
      const result = await ctx.navigateTree(target.id, { summarize: false });
      if (result.cancelled) ctx.ui.notify("/tree 已取消", "warning");
      else ctx.ui.notify("ANDROID_SESSION_SWITCHED", "info");
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
        : await choosePoint(ctx, "Fork from message");
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
