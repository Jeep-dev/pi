from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java/com/piandroid/MainActivity.kt"
BUILD = ROOT / "app/build.gradle.kts"
TEST = ROOT / "tools/test_active_session_ui.mjs"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, got {count}")
    return text.replace(old, new, 1)


main = MAIN.read_text()

if "stableBottomFrames" not in main:
    main = replace_once(
        main,
        '''private suspend fun LazyListState.scrollToRealBottom() {
    repeat(3) { withFrameNanos { }; val count = layoutInfo.totalItemsCount; if (count == 0) return; val target = count - 1; val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1; if (lastVisible < target - 1) { scrollToItem(target); withFrameNanos { } }; if (!canScrollForward) return; val moved = scrollBy(1_000_000f); if (kotlin.math.abs(moved) < 0.5f || !canScrollForward) return }
}''',
        '''private suspend fun LazyListState.scrollToRealBottom(keepFollowing: () -> Boolean = { true }) {
    // A visible-content mutation is observed before Compose necessarily finishes measuring
    // the new tool/Markdown height. Wait for several stable frames instead of returning on
    // the first frame that still reports the old "already at bottom" layout.
    var stableBottomFrames = 0
    repeat(16) {
        if (!keepFollowing()) return
        withFrameNanos { }
        val count = layoutInfo.totalItemsCount
        if (count == 0) return
        val target = count - 1
        val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (lastVisible < target) {
            scrollToItem(target)
            stableBottomFrames = 0
            return@repeat
        }
        if (!keepFollowing()) return
        if (canScrollForward) {
            scrollBy(1_000_000f)
            stableBottomFrames = 0
        } else {
            stableBottomFrames += 1
            if (stableBottomFrames >= 4) return
        }
    }
}''',
        "stable bottom follower",
    )

if "val verticalInput = abs(available.y) > abs(available.x)" not in main:
    main = replace_once(
        main,
        '''    val userScrollLock = remember(listState) { object : NestedScrollConnection { override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset { if (source == NestedScrollSource.UserInput) { onFollowChange(false); onUserScrollActivity() }; return Offset.Zero }; override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset { if (source == NestedScrollSource.UserInput && isAtBottom()) onFollowChange(true); return Offset.Zero } } }''',
        '''    val userScrollLock = remember(listState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val verticalInput = abs(available.y) > abs(available.x) && abs(available.y) > 0.5f
                if (source == NestedScrollSource.UserInput && verticalInput) {
                    onFollowChange(false)
                    onUserScrollActivity()
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                val verticalInput = abs(consumed.y) > abs(consumed.x) || abs(available.y) > abs(available.x)
                if (source == NestedScrollSource.UserInput && verticalInput && isAtBottom()) onFollowChange(true)
                return Offset.Zero
            }
        }
    }''',
        "vertical-only follow lock",
    )

if "chatListState.scrollToRealBottom { followOutput }" not in main:
    main = replace_once(
        main,
        "if (followOutput && lines.isNotEmpty()) chatListState.scrollToRealBottom()",
        "if (followOutput && lines.isNotEmpty()) chatListState.scrollToRealBottom { followOutput }",
        "stream follow call",
    )
    main = replace_once(
        main,
        '''        if (imeBottom > 0 && followOutput && lines.isNotEmpty()) {
            delay(80)
            chatListState.scrollToRealBottom()
        }''',
        '''        if (imeBottom > 0 && followOutput && lines.isNotEmpty()) {
            delay(80)
            chatListState.scrollToRealBottom { followOutput }
        }''',
        "ime follow call",
    )

if '"""Pi Android v5.19.20' not in main:
    main = replace_once(
        main,
        '"""Pi Android v5.19.18\n                |• Markdown 表格和代码块优先接管横向滑动，不再误触 Session 侧栏',
        '"""Pi Android v5.19.20\n                |• 修复 web search / 工具结束后 Compose 延迟重排导致的偶发自动跟随失效\n                |• 自动贴底等待布局连续稳定多帧；手动上滑会立即中止贴底\n                |• 只有纵向手势会暂停自动跟随，横向表格/代码滑动不再误关 follow\n                |• Markdown 表格和代码块优先接管横向滑动，不再误触 Session 侧栏',
        "changelog bottom follow",
    )
MAIN.write_text(main)

build = BUILD.read_text()
build = build.replace("// v5.19.19: keep every Markdown table row equal-height when cells wrap.", "// v5.19.20: make bottom-follow resilient to delayed Compose remeasurement.")
build = build.replace("versionCode = 118", "versionCode = 119")
build = build.replace('versionName = "5.19.19"', 'versionName = "5.19.20"')
BUILD.write_text(build)

test = TEST.read_text()
if "bottom follow must survive delayed Compose remeasurement" not in test:
    test += '\nassert.ok(main.includes("stableBottomFrames"), "bottom follow must survive delayed Compose remeasurement");\n'
    test += 'assert.ok(main.includes("repeat(16)"), "bottom follow must wait across multiple layout frames");\n'
    test += 'assert.ok(main.includes("scrollToRealBottom { followOutput }"), "automatic scrolling must stop immediately after the user disables follow");\n'
    test += 'assert.ok(main.includes("abs(available.y) > abs(available.x)"), "horizontal table/code scrolling must not disable bottom follow");\n'
TEST.write_text(test)

print("v5.19.20 bottom-follow repair applied")
