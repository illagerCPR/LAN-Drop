import { networkInterfaces } from "node:os";

import { loadConfig } from "./config.ts";
import { buildServer } from "./app.ts";

/** 列出本机所有非内部 IPv4 地址；真实局域网地址排在虚拟网桥（docker 等）前面。 */
function lanAddresses(): string[] {
  const addresses: string[] = [];

  for (const infos of Object.values(networkInterfaces())) {
    for (const info of infos ?? []) {
      if (info.family === "IPv4" && !info.internal) {
        addresses.push(info.address);
      }
    }
  }

  const score = (ip: string): number => {
    if (ip.startsWith("192.168.")) return 0;
    if (ip.startsWith("10.")) return 1;
    if (/^172\.(1[6-9]|2\d|3[01])\./.test(ip)) return 3;
    return 2;
  };

  return addresses.sort((a, b) => score(a) - score(b));
}

async function main(): Promise<void> {
  const config = loadConfig();
  const app = await buildServer(config);

  await app.listen({ host: config.host, port: config.port });

  const addresses = lanAddresses();
  const lines: string[] = [
    "",
    "  LAN-Drop 服务端已启动",
    `  数据目录   ${config.dataRoot}`,
    `  文件仓库   ${config.filesRoot}`,
    "",
    "  本机访问   http://localhost:" + config.port,
  ];

  for (const ip of addresses) {
    lines.push(`  手机访问   http://${ip}:${config.port}`);
  }

  lines.push("");
  app.log.info(lines.join("\n"));
}

main().catch((error: unknown) => {
  console.error("[lan-drop] 启动失败：", error);
  process.exitCode = 1;
});
