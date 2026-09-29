import type { FileRefDto } from "@lan-drop/protocol";

import { fileUrl } from "../api.ts";
import { fileIcon, formatBytes } from "../util.ts";

/**
 * 文件消息卡片：图片类内嵌预览（点击新标签页放大），
 * 其余展示图标 + 文件名 + 大小，并提供「另存为」下载链接。
 */
export function FileCard(props: { file: FileRefDto }) {
  const { file } = props;
  const isImage = (file.mime ?? "").startsWith("image/");

  return (
    <div className="file-card">
      {isImage && (
        <a className="file-preview-link" href={fileUrl(file.id)} target="_blank" rel="noreferrer">
          <img className="file-preview" src={fileUrl(file.id)} alt={file.name} loading="lazy" />
        </a>
      )}
      <div className="file-row">
        <span className="file-icon" aria-hidden="true">
          {fileIcon(file.name, file.mime)}
        </span>
        <div className="file-meta">
          <div className="file-name">{file.name}</div>
          <div className="file-size">{formatBytes(file.size)}</div>
        </div>
        <a className="file-download" href={fileUrl(file.id, { download: true })}>
          下载
        </a>
      </div>
    </div>
  );
}
