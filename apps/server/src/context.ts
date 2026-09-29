import type { ServerConfig } from "./config.ts";
import type { PairingManager } from "./auth.ts";
import type { Hub } from "./hub.ts";
import type { Store } from "./store.ts";

/** 路由层共享的依赖集合，显式传递而不做全局单例，便于测试时替换。 */
export interface AppContext {
  config: ServerConfig;
  store: Store;
  hub: Hub;
  pairing: PairingManager;
  /** 服务端身份（首次启动生成后持久化），客户端用它识别「还是不是原来那台」 */
  serverId: string;
}
