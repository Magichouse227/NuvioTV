import assert from "node:assert/strict";
import fs from "node:fs";

const config = JSON.parse(fs.readFileSync(new URL("../ci-problem-matchers.json", import.meta.url)));
const patterns = config.problemMatcher.flatMap(m => m.pattern.map(p => new RegExp(p.regexp)));
for (const line of [
  "> Task :app:compileFullDebugKotlin",
  "> Configure project :app",
  "  > Task :app:assembleFullDebug UP-TO-DATE",
]) assert(!patterns.some(p => p.test(line)), `False error: ${line}`);
for (const line of [
  "e: file:///repo/app/Test.kt:42:9: Unresolved reference",
  "ERROR: Android build failed",
  "Caused by: java.lang.IllegalStateException: broken",
  "Execution failed for task ':app:compileFullDebugKotlin'.",
  "SomeTest > regression FAILED",
]) assert(patterns.some(p => p.test(line)), `Missed error: ${line}`);
console.log("CI problem-matcher regression checks passed.");