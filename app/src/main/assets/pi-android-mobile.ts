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
    return clean.length > 54 ? clean.slice(0, 54) + "…" : clean || "(empty message)";
  }

  // Keep this extension deliberately tiny. v3 proved this exact tree command
  // loads correctly on the user's Pi installation. Session resume/name/fork are
  // handled through native RPC by the Android bridge instead of extra imports.
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
}
