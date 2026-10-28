import { fileURLToPath } from "node:url";
import { join } from "node:path";

const projectRoot = fileURLToPath(new URL("../", import.meta.url));
const wrapper = process.platform === "win32" ? "kotlin.bat" : "kotlin";

const child = Bun.spawn([join(projectRoot, wrapper), ...process.argv.slice(2)], {
  cwd: projectRoot,
  stdin: "inherit",
  stdout: "inherit",
  stderr: "inherit",
});

process.exitCode = await child.exited;
