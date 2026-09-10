import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const themeSource = await readFile(new URL("../app/src/main/java/com/piandroid/PiTheme.kt", import.meta.url), "utf8");
const mainSource = await readFile(new URL("../app/src/main/java/com/piandroid/MainActivity.kt", import.meta.url), "utf8");
const markdownSource = await readFile(new URL("../app/src/main/java/com/piandroid/MarkdownContent.kt", import.meta.url), "utf8");

function palette(name) {
  const body = themeSource.match(new RegExp(`val ${name}PiColors = PiColors\\(([\\s\\S]*?)\\n\\)`))?.[1];
  assert.ok(body, `${name} palette missing`);
  return Object.fromEntries([...body.matchAll(/(\w+)\s*=\s*Color\(0x([0-9A-F]{8})\)/g)].map(match => [match[1], match[2]]));
}

const dark = palette("Dark");
const light = palette("Light");
const gray = palette("Gray");
assert.equal(Object.keys(dark).length, 31);
assert.equal(Object.keys(light).length, 31);
assert.equal(Object.keys(gray).length, 31);

const originalDark = {
  bg: "FF000000", headerBg: "FF05080A", panelBg: "FF0D1116", cardBg: "FF171B21",
  toolBg: "FF263229", userBg: "FF30313A", border: "FF284864", accent: "FF70E69A",
  blue: "FF79C5FF", textMain: "FFE8EAF0", textMuted: "FF858C96", thinkingText: "FF9A9A9A",
  danger: "FFFF8D8D", scrollBg: "DD41464C", scrollBorder: "FF626970", scrollText: "FFD2D5D8",
  scrollDivider: "FF686E74", headerDivider: "FF151B21", composerBg: "FF050607",
  disabledAction: "FF4B535C", stopButtonBg: "FF6B3030", markdownText: "FFD8D6E3",
  markdownMuted: "FF9491A3", markdownAccent: "FFC5A3FF", markdownCyan: "FF63D1D1",
  markdownBorder: "FF77738E", markdownCodeBg: "FF171620", markdownStrong: "FFF0EEF7",
  markdownInlineCodeBg: "FF252432", markdownCodeText: "FFC9E6E2", markdownQuoteBg: "FF1C1B27",
};
assert.deepEqual(dark, originalDark, "the established dark palette must not change");

function luminance(argb) {
  const rgb = argb.slice(-6).match(/../g).map(value => Number.parseInt(value, 16) / 255)
    .map(value => value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4);
  return 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2];
}
function contrast(a, b) {
  const first = luminance(a), second = luminance(b);
  return (Math.max(first, second) + 0.05) / (Math.min(first, second) + 0.05);
}
for (const [name, colors] of [["Light", light], ["Gray", gray]]) {
  for (const foreground of ["textMain", "textMuted", "blue", "accent", "danger"]) {
    for (const background of ["bg", "cardBg"]) {
      assert.ok(contrast(colors[foreground], colors[background]) >= 4.5,
        `${name} ${foreground} on ${background} does not meet WCAG AA contrast`);
    }
  }
  for (const foreground of ["markdownText", "markdownMuted", "markdownCyan", "markdownAccent"]) {
    assert.ok(contrast(colors[foreground], colors.markdownCodeBg) >= 4.45,
      `${name} ${foreground} on markdownCodeBg has insufficient contrast`);
  }
}

assert.doesNotMatch(mainSource, /Color\(0x[0-9A-F]+\)/, "MainActivity contains an unthemed hard-coded color");
assert.doesNotMatch(markdownSource, /Color\(0x[0-9A-F]+\)/, "MarkdownContent contains an unthemed hard-coded color");
assert.match(mainSource, /LocalCommand\("themes"/);
assert.match(mainSource, /Panel\.Themes -> ThemesPanel/);
assert.match(mainSource, /putString\("theme"/);
console.log("Theme palette and integration tests passed");
