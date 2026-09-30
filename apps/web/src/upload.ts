import type {
  CreateUploadRequest,
  CreateUploadResponse,
  MessageDto,
  UploadStatusDto,
} from "@lan-drop/protocol";

/**
 * 上传控制器：把「建会话 → 分片追加 → 完成」这条流水线做成可暂停、可续传、可取消的队列。
 *
 * 这一层刻意与 React 无关（依赖全部注入），原因有二：
 *   - 断点续传的正确性靠的是几条不变量，不是界面；抽出来才能在 Node 里直接单测
 *     （`test/upload.test.ts`）；
 *   - 「暂停」的语义必须写清楚：它**不打断正在飞的那一片**，只在分片之间生效。
 *     4 MiB 的一片在局域网上是毫秒级，用中断请求换来的「立刻停」并不值得——
 *     中途掐断会留下半片残字节，把状态机拖进「服务端认为收到了、客户端以为没发」的
 *     模糊地带（服务端有 `truncateTo` 兜底，但那属于容错，不该拿它当常规路径）。
 */

/** 一片上传的结果。 */
export interface UploadChunkResult {
  receivedBytes: number;
  realigned: boolean;
}

/**
 * 上传来源的最小投影。
 *
 * `File` 天然满足这个形状；测试里传个假对象即可，不必造 Blob。
 */
export interface UploadSource {
  readonly name: string;
  readonly size: number;
  readonly type: string;
  slice(start: number, end: number): Blob;
}

/** 控制器用到的全部网络能力（由 `api.ts` 提供实现，测试里替换成脚本化的假实现）。 */
export interface UploadTransport {
  create(request: CreateUploadRequest): Promise<CreateUploadResponse>;
  /** 查询服务端权威进度；会话已不存在（404）时返回 `null`，调用方据此重建会话。 */
  status(uploadId: string): Promise<UploadStatusDto | null>;
  chunk(uploadId: string, offset: number, chunk: Blob): Promise<UploadChunkResult>;
  complete(uploadId: string): Promise<MessageDto>;
  /** 中止会话（服务端会删掉临时分片）。尽力而为，不抛。 */
  abort(uploadId: string): Promise<void>;
}

/** 一条上传在界面上的阶段。 */
export type UploadPhase = "queued" | "running" | "pausing" | "paused" | "failed" | "done";

/** 交给界面的只读快照。 */
export interface UploadSnapshot {
  id: string;
  name: string;
  size: number;
  /** 服务端**已确认落盘**的字节数（不是「已发出」的字节数） */
  offset: number;
  phase: UploadPhase;
  error: string | null;
}

/**
 * 服务端连续多少次「没有前进」就放弃。
 *
 * 正常情况下每次追加都会让 `receivedBytes` 变大；原地不动只有两种可能：
 * 会话在服务端已被中止（如另一个标签页点了取消），或服务端状态被外部改动。
 * 没有这个计数器，这两种情况都会变成一个安静的空转循环。
 */
const MAX_STALLED_CHUNKS = 3;

interface UploadJob {
  readonly id: string;
  readonly source: UploadSource;
  /** 服务端上传会话；尚未建立时为 null */
  uploadId: string | null;
  offset: number;
  chunkSize: number;
  phase: UploadPhase;
  error: string | null;
  /** 用户按了暂停：分片之间检查 */
  pauseRequested: boolean;
  canceled: boolean;
  stalled: number;
}

export interface UploaderOptions {
  transport: UploadTransport;
  /** 任何状态变化后调用，参数是变化后的完整快照（界面直接拿去渲染） */
  onChange: (snapshots: UploadSnapshot[]) => void;
  /** 上传完成、服务端已落库时调用，参数是权威消息 */
  onComplete: (message: MessageDto) => void;
}

export class Uploader {
  readonly #transport: UploadTransport;
  readonly #onChange: (snapshots: UploadSnapshot[]) => void;
  readonly #onComplete: (message: MessageDto) => void;

  #jobs: UploadJob[] = [];
  #sequence = 0;
  /** 队列里同时只有一个任务在跑；暂停的任务不占队列 */
  #pumping = false;

  constructor(options: UploaderOptions) {
    this.#transport = options.transport;
    this.#onChange = options.onChange;
    this.#onComplete = options.onComplete;
  }

  /** 当前所有任务的只读快照。 */
  get snapshots(): UploadSnapshot[] {
    return this.#snapshot();
  }

  #snapshot(): UploadSnapshot[] {
    return this.#jobs.map((job) => ({
      id: job.id,
      name: job.source.name,
      size: job.source.size,
      offset: job.offset,
      phase: job.phase,
      error: job.error,
    }));
  }

  /** 状态变化后统一从这里出去，避免有的分支忘了通知界面。 */
  #emit(): void {
    this.#onChange(this.#snapshot());
  }

  /** 入队一个文件并立刻尝试开工。 */
  add(source: UploadSource): string {
    this.#sequence += 1;
    const job: UploadJob = {
      id: `upload-${this.#sequence}`,
      source,
      uploadId: null,
      offset: 0,
      chunkSize: 0,
      phase: "queued",
      error: null,
      pauseRequested: false,
      canceled: false,
      stalled: 0,
    };
    this.#jobs.push(job);
    this.#emit();
    void this.#pump();
    return job.id;
  }

  /** 暂停。排队中的立即停住，正在跑的等当前分片落地（界面显示「暂停中…」）。 */
  pause(id: string): void {
    const job = this.#find(id);
    if (job === undefined || job.phase === "paused") return;

    job.pauseRequested = true;
    if (job.phase === "queued") {
      job.phase = "paused";
    } else if (job.phase === "running") {
      job.phase = "pausing";
    } else {
      return;
    }
    this.#emit();
  }

  /**
   * 继续。
   *
   * 重新入队后会先向服务端问一次权威进度（[Uploader.#prepare]）——本地记的 offset
   * 可能落后于服务端（上一片其实写进去了、只是响应没回来）。这正是服务端提供
   * `GET /uploads/:id` 的唯一理由。
   */
  resume(id: string): void {
    const job = this.#find(id);
    if (job === undefined || (job.phase !== "paused" && job.phase !== "pausing")) return;

    job.pauseRequested = false;
    job.phase = "queued";
    this.#emit();
    void this.#pump();
  }

  /** 重试一次失败的上传；同样从服务端锚点接着走，不是从头再来。 */
  retry(id: string): void {
    const job = this.#find(id);
    if (job === undefined || job.phase !== "failed") return;

    job.error = null;
    job.pauseRequested = false;
    job.canceled = false;
    job.phase = "queued";
    this.#emit();
    void this.#pump();
  }

  /** 取消并移除（也用于关掉已完成、失败的任务）。中止会话是尽力而为。 */
  cancel(id: string): void {
    const job = this.#find(id);
    if (job === undefined) return;

    job.canceled = true;
    job.phase = "done";
    this.#jobs = this.#jobs.filter((candidate) => candidate !== job);
    this.#emit();

    const uploadId = job.uploadId;
    if (uploadId !== null) {
      // 服务端删临时分片；失败无所谓（剩下的字节由服务端的清理定时器兜底）
      void this.#transport.abort(uploadId).catch(() => undefined);
    }
  }

  /** 从面板移除一条已结束的记录。 */
  dismiss(id: string): void {
    const job = this.#find(id);
    if (job === undefined) return;
    if (job.phase !== "done" && job.phase !== "failed") return;

    this.#jobs = this.#jobs.filter((candidate) => candidate !== job);
    this.#emit();
  }

  #find(id: string): UploadJob | undefined {
    return this.#jobs.find((job) => job.id === id);
  }

  /**
   * 队列泵：**只挑 `queued` 的任务**。
   *
   * 「暂停不占队列」是有意的——把一个大文件挂起，后面那个小文件应该照常发出去；
   * 若暂停的任务仍占着锁，用户按了暂停就等于把整条队列也按停了。
   *
   * 不变量：[Uploader.#runJob] 返回时该任务必定已离开 `running`/`pausing`
   * （成功、失败或被暂停）。否则 `find` 会再次挑中同一个任务，变成死循环。
   */
  async #pump(): Promise<void> {
    if (this.#pumping) return;
    this.#pumping = true;
    try {
      for (;;) {
        const job = this.#jobs.find((candidate) => candidate.phase === "queued");
        if (job === undefined) break;
        await this.#runJob(job);
      }
    } finally {
      this.#pumping = false;
    }
  }

  /** 建立会话或校准锚点：跑在每次「开工」之前，因此暂停/失败后的继续都是接着传。 */
  async #prepare(job: UploadJob): Promise<void> {
    if (job.uploadId !== null) {
      const status = await this.#transport.status(job.uploadId);
      if (status === null || status.state !== "open") {
        // 会话没了（服务端重启/被中止）：服务端那份临时分片已经不在，只能从零重来
        job.uploadId = null;
      } else {
        job.offset = status.receivedBytes;
        job.chunkSize = status.chunkSize;
      }
    }

    if (job.uploadId === null) {
      const created = await this.#transport.create({
        name: job.source.name,
        size: job.source.size,
        ...(job.source.type.length > 0 ? { mime: job.source.type } : {}),
      });
      job.uploadId = created.uploadId;
      job.offset = created.receivedBytes;
      job.chunkSize = created.chunkSize;
    }
    this.#emit();
  }

  async #runJob(job: UploadJob): Promise<void> {
    job.phase = "running";
    job.error = null;
    job.stalled = 0;
    this.#emit();

    try {
      await this.#prepare(job);

      for (;;) {
        if (job.canceled) return;
        if (job.pauseRequested) {
          job.phase = "paused";
          return;
        }
        // 字节齐了就收尾；此时按下暂停已经没有意义，让完成流程走完
        if (job.offset >= job.source.size) break;

        const uploadId = job.uploadId;
        if (uploadId === null) throw new Error("上传会话丢失");

        const end = Math.min(job.offset + job.chunkSize, job.source.size);
        const result = await this.#transport.chunk(
          uploadId,
          job.offset,
          job.source.slice(job.offset, end),
        );

        if (result.receivedBytes === job.offset) {
          job.stalled += 1;
          if (job.stalled >= MAX_STALLED_CHUNKS) {
            throw new Error("服务端进度没有前进，上传已停下——请重试");
          }
        } else {
          job.stalled = 0;
        }
        job.offset = result.receivedBytes;
        this.#emit();
      }

      const uploadId = job.uploadId;
      if (uploadId === null) throw new Error("上传会话丢失");

      const message = await this.#transport.complete(uploadId);
      job.offset = job.source.size;
      job.phase = "done";
      this.#onComplete(message);
    } catch (cause) {
      job.phase = "failed";
      job.error = cause instanceof Error ? cause.message : String(cause);
    } finally {
      this.#settle(job);
    }
  }

  /**
   * 收尾兜底：**绝不能**让 [Uploader.#runJob] 把任务留在 `running`/`pausing`。
   *
   * 队列只挑 `queued` 的任务，看上去这层保护多余；但 `#runJob` 返回前若因为
   * 新增分支漏了某个状态赋值，泵就会不停挑中同一个任务空转。宁可把它标成失败。
   */
  #settle(job: UploadJob): void {
    const phase: UploadPhase = job.phase;
    if (phase === "running" || phase === "pausing") {
      job.phase = "failed";
      job.error ??= "上传意外中断";
    }
    this.#emit();
  }
}
