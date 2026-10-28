import { createHash, randomUUID } from "node:crypto";
import { mkdir, rename, rm } from "node:fs/promises";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const version = "0.65";
const sha256 = "aba9753b6ab387926179ab7318ba8e0c33dad483ebc72f633e79522265b15214";
const projectRoot = fileURLToPath(new URL("../", import.meta.url));
const cacheDir = join(projectRoot, "build", "tools", "ktfmt");
const jarName = `ktfmt-${version}-with-dependencies.jar`;
const jarPath = join(cacheDir, jarName);

async function main() {
  const java = Bun.which("java") ?? (process.env.JAVA_HOME
    ? join(process.env.JAVA_HOME, "bin", process.platform === "win32" ? "java.exe" : "java")
    : null);
  if (!java) throw new Error("Java 17+ is required. Add java to PATH or set JAVA_HOME.");

  if (!(await Bun.file(jarPath).exists())) {
    console.error(`Downloading ktfmt ${version}...`);
    const response = await fetch(
      `https://github.com/Kotlin/ktfmt/releases/download/v${version}/${jarName}`,
    );
    if (!response.ok) throw new Error(`ktfmt download failed: HTTP ${response.status}`);
    const bytes = await response.arrayBuffer();
    if (createHash("sha256").update(Buffer.from(bytes)).digest("hex") !== sha256) {
      throw new Error("ktfmt download checksum mismatch.");
    }
    await mkdir(cacheDir, { recursive: true });
    const temporaryPath = `${jarPath}.${randomUUID()}.tmp`;
    try {
      await Bun.write(temporaryPath, bytes);
      await rename(temporaryPath, jarPath);
    } finally {
      await rm(temporaryPath, { force: true });
    }
  }

  const args = process.argv.slice(2);
  const child = Bun.spawn(
    [java, "-jar", jarPath, "--kotlinlang-style", ...(args.length ? args : ["shared", "androidApp", "desktopApp"])],
    { cwd: projectRoot, stdin: "inherit", stdout: "inherit", stderr: "inherit" },
  );
  process.exitCode = await child.exited;
}

try {
  await main();
} catch (error) {
  console.error(error instanceof Error ? error.message : String(error));
  process.exitCode = 1;
}
