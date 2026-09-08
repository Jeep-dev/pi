# Pi Android

一个给 Pi Coding Agent 使用的 Android 原生 GUI。Pi 本体继续运行在 Termux，App 通过本机 bridge 连接 Pi 的 RPC 模式，不把 Node.js、Linux 环境或模型打进 APK。

## 当前功能

- Pi RPC 聊天和流式事件
- 输入 `/` 调出命令菜单
- 浏览 Termux 项目文件
- 查看和编辑文件并保存
- 查看 Git diff
- 在同一项目目录运行 Termux 终端命令

## Termux 前置配置

使用 GitHub/F-Droid 版 Termux，并在 Termux 中执行：

```bash
pkg update && pkg upgrade
pkg install nodejs termux-api git
npm install -g --ignore-scripts @earendil-works/pi-coding-agent
mkdir -p ~/.pi/agent
```

首次使用 App 前，在 `~/.termux/termux.properties` 中加入：

```properties
allow-external-apps=true
```

然后重启 Termux。若项目位于共享存储，还需要运行：

```bash
termux-setup-storage
```

## GitHub Actions 构建

将本目录上传为 GitHub 仓库后，打开 `Actions → Build Pi Android → Run workflow`。工作流会自动准备 JDK 21、Android SDK，并上传 `app-debug.apk`。

## 项目目录

App 中输入 Termux 内部路径，例如：

```text
/data/data/com.termux/files/home/my-project
```

共享存储路径为：

```text
/storage/emulated/0/Download/my-project
```
