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

  async function listSessions(ctx: any): Promise<any[]> {
    const sessionDir = String(ctx.sessionManager.getSessionDir());
    const current = String(ctx.sessionManager.getSessionFile?.() || "");
    // Deliberately avoid imports in the extension module itself. The user's Pi
    // installation already proved the minimal extension loads; all filesystem
    // work happens in a child Node process only when /resume is actually used.
    const script = `
const fs=require('fs'),path=require('path');
const dir=process.argv[1], current=process.argv[2]||'';
function txt(c){if(typeof c==='string')return c;if(!Array.isArray(c))return '';return c.filter(x=>x&&x.type==='text').map(x=>String(x.text||'')).join(' ')}
let out=[];
try{
  for(const name of fs.readdirSync(dir)){
    if(!name.endsWith('.jsonl')) continue;
    const file=path.join(dir,name); if(file===current) continue;
    let stat; try{stat=fs.statSync(file)}catch{continue}
    let first='', title='', count=0;
    try{
      const fd=fs.openSync(file,'r'), b=Buffer.alloc(262144), n=fs.readSync(fd,b,0,b.length,0); fs.closeSync(fd);
      for(const line of b.subarray(0,n).toString('utf8').split('\\n')){
        if(!line.trim()) continue; let e; try{e=JSON.parse(line)}catch{continue}
        if(e&&e.type==='session_info'&&typeof e.name==='string') title=e.name;
        if(e&&e.type==='message'){count++; if(!first&&e.message&&e.message.role==='user') first=txt(e.message.content)}
      }
    }catch{}
    out.push({path:file,title:title||first||name,count,mtime:stat.mtimeMs});
  }
}catch(e){console.error(String(e&&e.message||e));process.exit(2)}
out.sort((a,b)=>b.mtime-a.mtime); console.log(JSON.stringify(out.slice(0,60)));
`;
    const result = await pi.exec("node", ["-e", script, sessionDir, current]);
    if (result.code !== 0) throw new Error(result.stderr || `node exited ${result.code}`);
    return JSON.parse(String(result.stdout || "[]"));
  }

  pi.registerCommand("resume", {
    description: "Pick and resume a previous Pi session on Android",
    handler: async (_args: string, ctx: any) => {
      await ctx.waitForIdle();
      let sessions: any[];
      try { sessions = await listSessions(ctx); }
      catch (error: any) {
        ctx.ui.notify(`读取 sessions 失败：${String(error?.message || error)}`, "error");
        return;
      }
      if (!sessions.length) {
        ctx.ui.notify("当前目录没有其他可继续的 session", "info");
        return;
      }
      const labels = sessions.map((s: any, i: number) => `${i + 1}. ${preview(String(s.title || ""), 44)} · ${s.count || "?"} msg`);
      const selected = await ctx.ui.select("Resume session", labels);
      if (!selected) return;
      const target = sessions[labels.indexOf(selected)];
      if (!target) return;
      const result = await ctx.switchSession(String(target.path), {
        withSession: async (nextCtx: any) => nextCtx.ui.notify("ANDROID_SESSION_SWITCHED", "info"),
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
      const points = ctx.sessionManager.getEntries()
        .filter((entry: any) => entry?.type === "message" && entry?.message?.role === "user")
        .map((entry: any, index: number) => ({ id: String(entry.id), label: `${index + 1}. ${preview(textOf(entry.message.content))}` }));
      if (!points.length) return ctx.ui.notify("当前 session 没有可 fork 的用户消息", "info");
      const selected = await ctx.ui.select("Fork from message", points.map((point: any) => point.label));
      if (!selected) return;
      const target = points.find((point: any) => point.label === selected);
      if (!target) return;
      const result = await ctx.fork(target.id, { withSession: async (nextCtx: any) => nextCtx.ui.notify("ANDROID_SESSION_SWITCHED", "info") });
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
