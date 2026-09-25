import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const themeSource = await readFile(new URL("../app/src/main/java/com/piandroid/PiTheme.kt", import.meta.url), "utf8");
const mainSource = await readFile(new URL("../app/src/main/java/com/piandroid/MainActivity.kt", import.meta.url), "utf8");
const markdownSource = await readFile(new URL("../app/src/main/java/com/piandroid/MarkdownContent.kt", import.meta.url), "utf8");
const componentsSource = await readFile(new URL("../app/src/main/java/com/piandroid/PiComponents.kt", import.meta.url), "utf8");

function palette(name) {
  const body = themeSource.match(new RegExp(`val ${name}PiColors = PiColors\\(([\\s\\S]*?)\\n\\)`))?.[1];
  assert.ok(body, `${name} palette missing`);
  return Object.fromEntries([...body.matchAll(/(\w+)\s*=\s*Color\(0x([0-9A-F]{8})\)/g)].map(match => [match[1], match[2]]));
}

const dark = palette("Dark");
const light = palette("Light");
const gray = palette("Gray");
// Every palette must define exactly the same color roles.
const roles = Object.keys(dark).sort();
assert.equal(roles.length, 41);
for (const [name, colors] of [["Light", light], ["Gray", gray]]) {
  assert.deepEqual(Object.keys(colors).sort(), roles, `${name} palette roles differ from Dark`);
}
assert.match(themeSource, /val DarkPiColors = PiColors\(\s*isLight = false/);
assert.match(themeSource, /val LightPiColors = PiColors\(\s*isLight = true/);
assert.match(themeSource, /val GrayPiColors = PiColors\(\s*isLight = false/);

function luminance(argb) {
  const rgb = argb.slice(-6).match(/../g).map(value => Number.parseInt(value, 16) / 255)
    .map(value => value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4);
  return 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2];
}
function contrast(a, b) {
  const first = luminance(a), second = luminance(b);
  return (Math.max(first, second) + 0.05) / (Math.min(first, second) + 0.05);
}
for (const [name, colors] of [["Dark", dark], ["Light", light], ["Gray", gray]]) {
  for (const foreground of ["textMain", "textMuted", "blue", "accent", "danger", "success"]) {
    for (const background of ["bg", "cardBg", "panelBg", "userBg"]) {
      assert.ok(contrast(colors[foreground], colors[background]) >= 4.5,
        `${name} ${foreground} on ${background} does not meet WCAG AA contrast`);
    }
  }
  for (const foreground of ["markdownText", "markdownMuted", "markdownCyan", "markdownAccent", "markdownCodeText"]) {
    assert.ok(contrast(colors[foreground], colors.markdownCodeBg) >= 4.5,
      `${name} ${foreground} on markdownCodeBg has insufficient contrast`);
  }
  for (const foreground of ["toolTitle", "toolOutput", "toolMeta", "toolDiffAdded", "toolDiffRemoved"]) {
    for (const background of ["toolBg", "toolPendingBg", "toolErrorBg"]) {
      assert.ok(contrast(colors[foreground], colors[background]) >= 4.5,
        `${name} ${foreground} on ${background} has insufficient contrast`);
    }
  }
  assert.ok(contrast(colors.onAccent, colors.accent) >= 4.5, `${name} onAccent on accent has insufficient contrast`);
}

assert.doesNotMatch(mainSource, /Color\(0x[0-9A-F]+\)/, "MainActivity contains an unthemed hard-coded color");
assert.doesNotMatch(markdownSource, /Color\(0x[0-9A-F]+\)/, "MarkdownContent contains an unthemed hard-coded color");
assert.doesNotMatch(componentsSource, /Color\(0x[0-9A-F]+\)/, "PiComponents contains an unthemed hard-coded color");
assert.match(mainSource, /LocalCommand\("themes"/);
assert.match(mainSource, /Panel\.Themes -> ThemesPanel/);
assert.match(mainSource, /putString\("theme"/);
console.log("Theme palette and integration tests passed");
