import type { MessageDto } from "@lan-drop/protocol";
import type { ReactElement } from "react";

import { formatDayLabel, formatTime } from "../util.ts";
import { FileCard } from "./FileCard.tsx";

/**
 * 消息时间线：按 seq 升序渲染，跨天插入日期分隔条。
 * 自己发的消息靠右，其他设备的靠左并展示发送者名称。
 */
export function MessageList(props: { messages: readonly MessageDto[]; ownDeviceId: string | null }) {
  const { messages, ownDeviceId } = props;

  const nodes: ReactElement[] = [];
  let lastDay = "";

  for (const message of messages) {
    const day = formatDayLabel(message.createdAt);
    if (day !== lastDay) {
      lastDay = day;
      nodes.push(
        <li key={`day-${message.seq}`} className="day-divider">
          <span>{day}</span>
        </li>,
      );
    }

    const own = ownDeviceId !== null && message.senderId === ownDeviceId;

    nodes.push(
      <li key={message.seq} className={own ? "msg own" : "msg"}>
        {!own && <div className="msg-sender">{message.senderName}</div>}
        <div className="msg-body">
          {message.kind === "file" && message.file !== undefined ? (
            <FileCard file={message.file} />
          ) : null}
          {message.kind === "link" && message.text !== undefined && message.text.length > 0 ? (
            <a className="msg-link" href={message.text} target="_blank" rel="noreferrer">
              {message.text}
            </a>
          ) : null}
          {message.kind === "text" && message.text !== undefined && message.text.length > 0 ? (
            <div className="msg-text">{message.text}</div>
          ) : null}
        </div>
        <div className="msg-time">{formatTime(message.createdAt)}</div>
      </li>,
    );
  }

  if (nodes.length === 0) {
    return (
      <ul className="message-list-inner empty-hint">
        <li className="empty">还没有任何内容 —— 发送一段文字，或把文件拖进来试试</li>
      </ul>
    );
  }

  return <ul className="message-list-inner">{nodes}</ul>;
}
