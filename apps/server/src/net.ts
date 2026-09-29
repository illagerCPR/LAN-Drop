import { networkInterfaces } from "node:os";

/**
 * 列出本机所有非内部 IPv4 地址。
 *
 * 排序刻意把真实局域网地址排在前面：本机通常还有 docker0（172.17.x）、
 * WSL 虚拟网卡等地址，直接把第一个地址印给用户看会误导。
 */
export function lanAddresses(): string[] {
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

/**
 * 是否属于私有/本机地址。
 *
 * LAN-Drop 是局域网工具，没有任何理由接受公网来源：
 * 万一路由器做了端口映射、或跑在云主机上忘了改配置，
 * 这条防线能让服务保持「只有内网可达」。
 */
export function isPrivateAddress(rawIp: string): boolean {
  const ip = rawIp.startsWith("::ffff:") ? rawIp.slice("::ffff:".length) : rawIp;

  if (ip === "::1" || ip === "127.0.0.1") return true;
  if (ip.startsWith("10.")) return true;
  if (ip.startsWith("192.168.")) return true;
  if (ip.startsWith("169.254.")) return true;
  // 唯一本地地址（IPv6 ULA）
  if (/^f[cd][0-9a-f]{2}:/i.test(ip)) return true;
  // 172.16.0.0/12
  if (/^172\.(1[6-9]|2\d|3[01])\./.test(ip)) return true;

  return false;
}

/** 是否来自本机回环。用于「本机免配对」这类只有宿主自己才能享受的便利。 */
export function isLoopback(rawIp: string): boolean {
  const ip = rawIp.startsWith("::ffff:") ? rawIp.slice("::ffff:".length) : rawIp;
  return ip === "::1" || ip === "127.0.0.1" || ip.startsWith("127.");
}
