import { StrictMode } from "react";
import { hydrateRoot } from "react-dom/client";
import App from "./App";
import "./index.css";

// The markup in #root is prerendered at build time (multiPageEmit in
// vite.config.ts) - hydrate attaches interactivity instead of replacing it.
hydrateRoot(
  document.getElementById("root")!,
  <StrictMode>
    <App />
  </StrictMode>,
);
