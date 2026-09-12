# Pi Android

一个给 Pi Coding Agent 使用的 Android 原生 GUI。Pi 本体继续运行在 Termux，App 通过本机 bridge 连接 Pi 的 RPC 模式，不把 Node.js、Linux 环境或模型打进 APK。

## 当前功能

- Pi RPC 聊天和流式事件
- 多个独立 Pi Session 并行运行，支持后台工作
- 从左边缘右滑打开 Session 侧栏；每个 Session 独立保存 cwd 和恢复信息
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

## 安全模型

App 首次运行时生成 256 位随机 Bridge Token，并保存在 Android 私有配置中。所有本机 HTTP RPC 请求都必须携带该 Token；Bridge 缺少 Token 时会拒绝启动。文件接口会解析真实路径，拒绝通过符号链接越出当前项目。

Termux 的 `RUN_COMMAND` 能力仍具有当前 Termux 用户的完整权限，请只安装可信 APK。

## 构建

本地构建：

```bash
node tools/test_bridge_auth.mjs
./gradlew :app:assembleDebug
```

GitHub Actions 会直接从已提交源码构建，不再动态套用补丁。配置仓库签名 Secrets 后，会上传使用同一密钥签名的 debug 和 release APK，后续版本可以直接覆盖安装。

## 项目目录

App 中输入 Termux 内部路径，例如：

```text
/data/data/com.termux/files/home/my-project
```

共享存储路径为：

```text
/storage/emulated/0/Download/my-project
```
