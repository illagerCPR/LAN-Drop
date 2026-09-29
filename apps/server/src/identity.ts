import { readFile, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { randomUUID } from "node:crypto";

interface ServerIdentity {
  serverId: string;
  createdAt: number;
}

/**
 * 服务端身份：首次启动生成并持久化，之后保持不变。
 *
 * 客户端把 serverId 与自己的 deviceToken 绑定，服务端换机器（identity 变化）时
 * 客户端能识别出「这不是原来那台」，而不是拿着旧 token 反复 401。
 */
export async function loadOrCreateIdentity(dataRoot: string): Promise<ServerIdentity> {
  const identityPath = join(dataRoot, "server.json");

  try {
    const raw = await readFile(identityPath, "utf8");
    const parsed = JSON.parse(raw) as Partial<ServerIdentity>;
    if (typeof parsed.serverId === "string" && parsed.serverId.length > 0) {
      return {
        serverId: parsed.serverId,
        createdAt: parsed.createdAt ?? Date.now(),
      };
    }
  } catch (error) {
    const code = (error as NodeJS.ErrnoException).code;
    if (code !== "ENOENT") {
      throw error;
    }
  }

  const identity: ServerIdentity = {
    serverId: randomUUID(),
    createdAt: Date.now(),
  };
  await writeFile(identityPath, `${JSON.stringify(identity, null, 2)}\n`, "utf8");
  return identity;
}
