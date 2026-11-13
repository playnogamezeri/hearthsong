import { readFileSync } from "node:fs";

const html = readFileSync("index.html", "utf8");
const inlineScripts = [...html.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/gi)]
  .map(match => match[1])
  .filter(source => source.trim());

if (inlineScripts.length === 0) throw new Error("No inline application script found.");
for (let index = 0; index < inlineScripts.length; index++) {
  new Function(inlineScripts[index]);
  console.log("PASS: inline JavaScript block " + (index + 1) + " parses");
}

const ids = [...html.matchAll(/\bid=["']([^"']+)["']/g)].map(match => match[1]);
const duplicateIds = ids.filter((id, index) => ids.indexOf(id) !== index);
if (duplicateIds.length) throw new Error("Duplicate HTML IDs: " + [...new Set(duplicateIds)].join(", "));
if ((html.match(/<nav\b/g) || []).length !== 1) throw new Error("Expected exactly one main navigation row.");
if ((html.match(/<footer\b/g) || []).length !== 1) throw new Error("Expected exactly one footer.");
if (/<footer[^>]*>[\s\S]*?<\/main>/i.test(html)) throw new Error("Malformed footer closing tag.");
console.log("PASS: unique IDs and clean page structure");

const requiredIds = [
  "songTitle", "lyrics", "saveSong", "openPerformance", "lyricStage",
  "stageLyrics", "scrollSpeed", "scrollToggle", "recordButton",
  "libraryPage", "songList", "takeList", "settingsTab", "writeAutotune", "stageAutotune",
  "settingsPage", "tuneMode", "tuneStyle", "tuneStrength", "tuneRetune", "tuneFormant"
];
for (const id of requiredIds) {
  if (!html.includes('id="' + id + '"') && !html.includes("id='" + id + "'")) {
    throw new Error("Missing required UI element: #" + id);
  }
  console.log("PASS: #" + id + " exists");
}

for (const feature of ["updateStageActiveLine", "startAutoScroll", "saveCurrentSong", "deleteSong", "deleteTake",
  "FormantCorrectionNode", "JackYinTracker", "EQ_PRESETS", "renderEQ", "tuneDryGain", "tuneWetGain"]) {
  if (!inlineScripts.some(source => source.includes(feature))) {
    throw new Error("Missing required feature: " + feature);
  }
  console.log("PASS: " + feature + " is present");
}

const recordHandlerStart = html.indexOf("$('recordButton').onclick=async()=>{");
const recordHandlerEnd = html.indexOf("$('keepTake').onclick", recordHandlerStart);
const recordHandler = html.slice(recordHandlerStart, recordHandlerEnd);
if (recordHandlerStart < 0 || recordHandlerEnd < 0) throw new Error("Recording handler not found.");
if ((recordHandler.match(/getUserMedia\(/g) || []).length !== 1) throw new Error("Recording handler must request the microphone exactly once.");
if ((recordHandler.match(/new MediaRecorder\(/g) || []).length !== 1) throw new Error("Recording handler must create exactly one recorder.");
if (!recordHandler.includes("new MediaRecorder(recordStream")) throw new Error("Recorder is not connected to the processed audio stream.");
console.log("PASS: microphone and recorder are connected once to the selected signal path");

console.log("Hearthsong website self-test passed.");
