import { readFileSync } from "node:fs";

const html = readFileSync("index.html", "utf8");
const inlineScripts = [...html.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/gi)]
  .map(match => match[1])
  .filter(source => source.trim());

if (inlineScripts.length === 0) throw new Error("No inline application script found.");
for (const [index, source] of inlineScripts.entries()) {
  new Function(source);
  console.log(\`PASS: inline JavaScript block \${index + 1} parses\`);
}

const requiredIds = [
  "songTitle", "lyrics", "saveSong", "openPerformance", "lyricStage",
  "stageLyrics", "scrollSpeed", "scrollToggle", "recordButton",
  "libraryPage", "songList", "takeList"
];
for (const id of requiredIds) {
  if (!new RegExp(\`id=["']\${id}["']\`).test(html)) {
    throw new Error(\`Missing required UI element: #\${id}\`);
  }
  console.log(\`PASS: #\${id} exists\`);
}

for (const feature of ["updateStageActiveLine", "startAutoScroll", "saveCurrentSong", "deleteSong", "deleteTake"]) {
  if (!inlineScripts.some(source => source.includes(feature))) {
    throw new Error(\`Missing required feature: \${feature}\`);
  }
  console.log(\`PASS: \${feature} is present\`);
}

console.log("Hearthsong website self-test passed.");
