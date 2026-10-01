import { createHash, X509Certificate } from "node:crypto";
import { chmod, mkdir, readFile, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { hostname } from "node:os";

import selfsigned from "selfsigned";

import { lanAddresses } from "./net.ts";

/**
 * 自签 TLS 材料：LAN 监听器的 https 证书 + 客户端指纹固定用的 SPKI 摘要。
 *
 * 信任模型是 SSH 式的 TOFU：证书不经任何 CA 背书，客户端只认「配对时见过的
 * SPKI 指纹」。指纹随配对二维码走（相机是攻击者插不进的视觉信道），因此
 * 证书内容本身（CN/SAN）不参与信任决策——IP 会变（DHCP）、SAN 会过时，
 * 指纹才是唯一身份。
 *
 * 文件布局（数据根下）：
 *   tls/cert.pem  自签证书
 *   tls/key.pem   私钥（丢失 = 换服务器，所有客户端需重新配对）
 *   tls/tls.json  信息性元数据（指纹每次启动都从证书现算，不信任此文件）
 */
export interface TlsMaterial {
  keyPem: string;
  certPem: string;
  /**
   * SPKI（SubjectPublicKeyInfo DER）的 sha256，base64url 无填充。
   * 协议字段、二维码参数、Android 存储统一用这一个形态。
   */
  fingerprintUrlSafe: string;
  notAfter: Date;
}

/** 证书有效期：十年。到期或文件缺失时重新生成，客户端需重新配对。 */
const CERT_DAYS = 3650;

const TLS_DIRNAME = "tls";

interface TlsMeta {
  fingerprintUrlSafe: string;
  notAfter: string;
  createdAt: number;
}

/** 与 discovery.ts 相同的最小日志接口。 */
export interface TlsLogger {
  info(obj: object, msg?: string): void;
  warn(obj: object, msg?: string): void;
}

function fingerprintOf(certPem: string): { spki: Buffer; fingerprintUrlSafe: string } {
  const x509 = new X509Certificate(certPem);
  const spki = x509.publicKey.export({ type: "spki", format: "der" });
  return { spki, fingerprintUrlSafe: createHash("sha256").update(spki).digest("base64url") };
}

/** SAN 要覆盖「用户可能敲进地址栏的全部形态」；IP 会漂移，但这只影响浏览器的
 * 主机名校验，指纹固定路径不受影响。 */
function subjectAltNames(): { type: 2 | 7; value?: string; ip?: string }[] {
  const altNames: { type: 2 | 7; value?: string; ip?: string }[] = [
    { type: 2, value: "localhost" },
    { type: 7, ip: "127.0.0.1" },
    { type: 7, ip: "::1" },
  ];
  const host = hostname().trim().toLowerCase();
  if (host.length > 0 && !/^\d{1,3}(\.\d{1,3}){3}$/.test(host)) {
    altNames.push({ type: 2, value: host });
  }
  for (const ip of lanAddresses()) {
    altNames.push({ type: 7, ip });
  }
  return altNames;
}

async function generateTlsMaterial(): Promise<TlsMaterial> {
  // selfsigned v5 用 notBeforeDate/notAfterDate 表达有效期（没有 days 选项）
  const notBefore = new Date();
  const notAfter = new Date(notBefore.getTime() + CERT_DAYS * 24 * 60 * 60 * 1000);

  const pems = await selfsigned.generate([{ name: "commonName", value: "LAN-Drop" }], {
    notBeforeDate: notBefore,
    notAfterDate: notAfter,
    keySize: 2048,
    extensions: [
      { name: "basicConstraints", cA: false, critical: true },
      {
        name: "keyUsage",
        critical: true,
        digitalSignature: true,
        keyEncipherment: true,
        keyCertSign: false,
        cRLSign: false,
      },
      { name: "extKeyUsage", serverAuth: true },
      { name: "subjectAltName", altNames: subjectAltNames() },
    ],
  });

  const { fingerprintUrlSafe } = fingerprintOf(pems.cert);
  const parsedNotAfter = new X509Certificate(pems.cert).validToDate;

  return { keyPem: pems.private, certPem: pems.cert, fingerprintUrlSafe, notAfter: parsedNotAfter };
}

async function persistTlsMaterial(dir: string, material: TlsMaterial): Promise<void> {
  await mkdir(dir, { recursive: true });
  await writeFile(join(dir, "cert.pem"), material.certPem, "utf8");
  await writeFile(join(dir, "key.pem"), material.keyPem, "utf8");
  // 私钥只对属主可读（POSIX；Windows 忽略此调用，靠目录 ACL 兜底）
  await chmod(join(dir, "key.pem"), 0o600).catch(() => {});
  const meta: TlsMeta = {
    fingerprintUrlSafe: material.fingerprintUrlSafe,
    notAfter: material.notAfter.toISOString(),
    createdAt: Date.now(),
  };
  await writeFile(join(dir, "tls.json"), `${JSON.stringify(meta, null, 2)}\n`, "utf8");
}

/**
 * 载入（或首次生成）TLS 材料。已存证书只要能解析、没过期就原样复用——
 * 指纹每次都从证书现算，客户端固化的指纹因此永远不会和服务端漂移。
 * 过期/损坏则整体重新生成（所有已配对客户端需重新配对，日志里会警告）。
 */
export async function loadOrCreateTlsMaterial(dataRoot: string, log: TlsLogger): Promise<TlsMaterial> {
  const dir = join(dataRoot, TLS_DIRNAME);

  try {
    const certPem = await readFile(join(dir, "cert.pem"), "utf8");
    const keyPem = await readFile(join(dir, "key.pem"), "utf8");
    const x509 = new X509Certificate(certPem);
    const notAfter = x509.validToDate;

    if (notAfter.getTime() <= Date.now()) {
      log.warn({ notAfter: notAfter.toISOString() }, "TLS 证书已过期，重新生成（已配对客户端需重新配对）");
    } else {
      const { fingerprintUrlSafe } = fingerprintOf(certPem);
      log.info({ fingerprint: fingerprintUrlSafe, notAfter: notAfter.toISOString() }, "TLS 证书已载入");
      return { keyPem, certPem, fingerprintUrlSafe, notAfter };
    }
  } catch (error) {
    const code = (error as NodeJS.ErrnoException).code;
    if (code !== "ENOENT") {
      log.warn({ err: error }, "TLS 证书读取/解析失败，重新生成（已配对客户端需重新配对）");
    }
  }

  const material = await generateTlsMaterial();
  await persistTlsMaterial(dir, material);
  log.info(
    { fingerprint: material.fingerprintUrlSafe, notAfter: material.notAfter.toISOString() },
    "已生成自签 TLS 证书",
  );
  return material;
}
