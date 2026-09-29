import { createRoot } from "react-dom/client";

import { App } from "./App.tsx";
import "./styles.css";

const container = document.getElementById("root");
if (container === null) {
  throw new Error("未找到 #root 挂载点");
}

createRoot(container).render(<App />);
