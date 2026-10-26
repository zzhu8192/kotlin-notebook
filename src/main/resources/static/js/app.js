import { marked } from "../vendor/vendor.js";
import { createEditor, setEditorKind, showDiagnostics, isCursorOnFirstLine, isCursorOnLastLine } from "./editor.js";

// ---------------------------------------------------------------------------------------------------------------
// Server API
// ---------------------------------------------------------------------------------------------------------------

async function request(method, path, body, signal) {
  const response = await fetch(path, {
    method,
    signal,
    headers: body === undefined ? {} : { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) {
    let message = `${response.status} ${response.statusText}`;
    try {
      message = (await response.json()).error ?? message;
    } catch {
      // Keep the status line.
    }
    throw new Error(message);
  }
  return response.status === 204 ? null : response.json();
}

const query = (name) => `name=${encodeURIComponent(name)}`;

const api = {
  info: () => request("GET", "/api/info"),
  list: () => request("GET", "/api/notebooks"),
  load: (name) => request("GET", `/api/notebook?${query(name)}`),
  save: (name, cells) => request("PUT", `/api/notebook?${query(name)}`, { cells }),
  create: (name, cells) => request("POST", "/api/notebook", { name, cells }),
  remove: (name) => request("DELETE", `/api/notebook?${query(name)}`),
  rename: (from, to) => request("POST", "/api/notebook/rename", { from, to }),
  complete: (notebook, cellId, code, cursor, signal) =>
    request("POST", "/api/complete", { notebook, cellId, code, cursor }, signal).then((r) => r.items),
};

// ---------------------------------------------------------------------------------------------------------------
// Models
// ---------------------------------------------------------------------------------------------------------------

const newId = () => {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  if (typeof crypto !== "undefined" && typeof crypto.getRandomValues === "function") {
    return "10000000-1000-4000-8000-100000000000".replace(/[018]/g, (c) =>
      (Number(c) ^ (crypto.getRandomValues(new Uint8Array(1))[0] & (15 >> (Number(c) / 4)))).toString(16)
    );
  }
  return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === "x" ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
};

function newCell(type = "kotlin", source = "") {
  return {
    id: newId(),
    type,
    source,
    outputs: [],
    status: null, // null | queued | running | ok | error | interrupted | cancelled
    executionCount: null,
    startedAt: null,
    durationMs: null,
    diagnostics: [],
    editing: type === "markdown" && source === "",
  };
}

/** One open notebook: its cells, save state and kernel connection. Lives on while other notebooks are shown. */
class Notebook {
  constructor(name, cells) {
    this.name = name;
    this.cells = cells.map((c) => newCell(c.type, c.source));
    if (this.cells.length === 0) this.cells.push(newCell());
    this.saveState = "saved"; // saved | dirty | saving | error
    this.saveError = null;
    this.saveTimer = null;
    this.savePromise = null;
    this.kernel = { state: "disconnected", message: null };
    this.connection = new KernelConnection(this);
  }

  cell(id) {
    return this.cells.find((c) => c.id === id);
  }

  markDirty() {
    this.saveState = "dirty";
    clearTimeout(this.saveTimer);
    this.saveTimer = setTimeout(() => this.save(), 800);
    ui.renderSaveState(this);
  }

  async save() {
    clearTimeout(this.saveTimer);
    if (this.savePromise) {
      // A save is in flight; save again afterwards so the latest edits are written.
      await this.savePromise;
      if (this.saveState !== "dirty") return;
    }
    if (this.saveState === "saved") return;
    const cells = this.cells.map(({ type, source }) => ({ type, source }));
    const name = this.name;
    this.saveState = "saving";
    ui.renderSaveState(this);
    this.savePromise = api
      .save(name, cells)
      .then(() => {
        if (this.saveState === "saving") this.saveState = "saved";
        this.saveError = null;
      })
      .catch((error) => {
        this.saveState = "error";
        this.saveError = error.message;
      })
      .finally(() => {
        this.savePromise = null;
        ui.renderSaveState(this);
      });
    await this.savePromise;
  }

  /** Applies a kernel message to the model, then lets the UI update if this notebook is on screen. */
  handle(message) {
    if (message.type === "kernel") {
      this.kernel = { state: message.state, message: message.message ?? null };
      ui.renderKernel(this);
      return;
    }
    const cell = this.cell(message.cellId);
    if (!cell) return; // A cell from another browser tab, or one deleted since it was queued.
    switch (message.type) {
      case "queued":
        cell.status = "queued";
        break;
      case "started":
        cell.status = "running";
        cell.executionCount = message.executionCount;
        cell.startedAt = performance.now();
        break;
      case "stream": {
        const last = cell.outputs[cell.outputs.length - 1];
        if (last && last.kind === "stream" && last.stream === message.stream) last.text += message.text;
        else cell.outputs.push({ kind: "stream", stream: message.stream, text: message.text });
        ui.appendStream(this, cell, message.stream, message.text);
        return;
      }
      case "result":
        cell.outputs.push({ kind: "result", text: message.text, valueType: message.valueType, html: message.html });
        break;
      case "error":
        cell.outputs.push({ kind: "error", text: message.text });
        break;
      case "finished":
        cell.status = message.status;
        cell.durationMs = message.durationMs;
        cell.diagnostics = message.diagnostics;
        break;
    }
    ui.renderCellState(this, cell);
  }

  run(cells) {
    const kotlin = [];
    for (const cell of cells) {
      if (cell.type === "markdown") {
        cell.editing = false;
        ui.renderCellState(this, cell);
        continue;
      }
      cell.outputs = [];
      cell.diagnostics = [];
      cell.status = "queued";
      cell.durationMs = null;
      kotlin.push(cell);
      ui.renderCellState(this, cell);
    }
    if (kotlin.length === 0) return;
    const sent = this.connection.send({ type: "run", cells: kotlin.map((c) => ({ cellId: c.id, code: c.source })) });
    if (!sent) {
      for (const cell of kotlin) {
        cell.status = "error";
        cell.outputs = [{ kind: "error", text: "Not connected to the kernel. Reconnecting, try again in a moment." }];
        ui.renderCellState(this, cell);
      }
    }
    if (this.saveState === "dirty") this.save();
  }

  /** Cells the kernel will not report on any more, e.g. after a restart, stop showing as queued or running. */
  abandonRunningCells(status) {
    for (const cell of this.cells) {
      if (cell.status === "queued" || cell.status === "running") {
        cell.status = status;
        ui.renderCellState(this, cell);
      }
    }
  }

  close() {
    clearTimeout(this.saveTimer);
    this.connection.close();
  }
}

/** The WebSocket to a notebook's kernel. Reconnects with backoff until closed. */
class KernelConnection {
  constructor(notebook) {
    this.notebook = notebook;
    this.socket = null;
    this.closed = false;
    this.retryDelay = 500;
    this.connect();
  }

  connect() {
    if (this.closed) return;
    const protocol = location.protocol === "https:" ? "wss:" : "ws:";
    const socket = new WebSocket(`${protocol}//${location.host}/ws?${query(this.notebook.name)}`);
    this.socket = socket;
    socket.onopen = () => {
      this.retryDelay = 500;
    };
    socket.onmessage = (event) => this.notebook.handle(JSON.parse(event.data));
    socket.onclose = () => {
      if (this.socket !== socket) return;
      this.socket = null;
      if (this.closed) return;
      this.notebook.handle({ type: "kernel", state: "disconnected" });
      this.notebook.abandonRunningCells("interrupted");
      setTimeout(() => this.connect(), this.retryDelay);
      this.retryDelay = Math.min(this.retryDelay * 2, 8000);
    };
  }

  /** Reconnects under the notebook's current name, e.g. after a rename. */
  reconnect() {
    const old = this.socket;
    this.socket = null;
    old?.close();
    this.connect();
  }

  send(message) {
    if (this.socket?.readyState !== WebSocket.OPEN) return false;
    this.socket.send(JSON.stringify(message));
    return true;
  }

  close() {
    this.closed = true;
    this.socket?.close();
  }
}

// ---------------------------------------------------------------------------------------------------------------
// UI
// ---------------------------------------------------------------------------------------------------------------

const $ = (selector, root = document) => root.querySelector(selector);

function element(tag, attributes = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attributes)) {
    if (value == null || value === false) continue;
    if (key === "class") node.className = value;
    else if (key.startsWith("on")) node.addEventListener(key.slice(2), value);
    else if (key === "html") node.innerHTML = value;
    else node.setAttribute(key, value === true ? "" : value);
  }
  for (const child of children.flat()) {
    if (child == null || child === false) continue;
    node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return node;
}

const ICONS = {
  run: '<svg viewBox="0 0 16 16"><path d="M4.5 2.8v10.4c0 .6.6.9 1.1.6l8.2-5.2c.5-.3.5-.9 0-1.2L5.6 2.2c-.5-.3-1.1 0-1.1.6z"/></svg>',
  runAll: '<svg viewBox="0 0 16 16"><path d="M2 2.8v10.4c0 .6.6.9 1.1.6l7.2-5.2c.5-.3.5-.9 0-1.2L3.1 2.2C2.6 1.9 2 2.2 2 2.8z"/><path d="M11 2.5h1.5a.5.5 0 0 1 .5.5v10a.5.5 0 0 1-.5.5H11a.5.5 0 0 1-.5-.5V3a.5.5 0 0 1 .5-.5z"/></svg>',
  stop: '<svg viewBox="0 0 16 16"><rect x="3.5" y="3.5" width="9" height="9" rx="1.5"/></svg>',
  restart: '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M13 8a5 5 0 1 1-1.6-3.7"/><path d="M13 2.5v3h-3"/></svg>',
  clear: '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"><path d="M3 4.5h10M6.5 4.5V3h3v1.5M4.5 4.5l.6 8.1c0 .5.5.9 1 .9h3.8c.5 0 1-.4 1-.9l.6-8.1"/></svg>',
  up: '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M8 13V3M4 7l4-4 4 4"/></svg>',
  down: '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M8 3v10M4 9l4 4 4-4"/></svg>',
  trash: '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M2.5 4.5h11M6 4.5V3h4v1.5M4 4.5l.7 8.6c0 .5.5.9 1 .9h4.6c.5 0 1-.4 1-.9l.7-8.6M6.7 7v4.5M9.3 7v4.5"/></svg>',
  plus: '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"><path d="M8 3.5v9M3.5 8h9"/></svg>',
  pencil: '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M10.5 2.8l2.7 2.7-7.6 7.6-3.3.6.6-3.3z"/></svg>',
  moon: '<svg viewBox="0 0 16 16"><path d="M13.6 10.1A6 6 0 0 1 5.9 2.4a6 6 0 1 0 7.7 7.7z"/></svg>',
  sun: '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"><circle cx="8" cy="8" r="2.8"/><path d="M8 1.5v1.3M8 13.2v1.3M1.5 8h1.3M13.2 8h1.3M3.4 3.4l.9.9M11.7 11.7l.9.9M3.4 12.6l.9-.9M11.7 4.3l.9-.9"/></svg>',
  notebook: '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.3"><rect x="3" y="1.8" width="10" height="12.4" rx="1.5"/><path d="M5.5 5h5M5.5 7.5h5M5.5 10h3" stroke-linecap="round"/></svg>',
};

const icon = (name) => element("span", { class: "icon", "aria-hidden": "true", html: ICONS[name] });

function iconButton(name, title, onclick, extraClass = "") {
  return element("button", { class: `icon-button ${extraClass}`, title, "aria-label": title, type: "button", onclick }, icon(name));
}

function formatDuration(ms) {
  if (ms < 1000) return `${Math.max(1, Math.round(ms))} ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(ms < 10_000 ? 2 : 1)} s`;
  const minutes = Math.floor(ms / 60_000);
  return `${minutes} min ${Math.round((ms % 60_000) / 1000)} s`;
}

const KERNEL_LABELS = {
  disconnected: "Disconnected",
  starting: "Starting kernel",
  idle: "Idle",
  busy: "Busy",
  failed: "Kernel failed",
};

class Ui {
  constructor() {
    this.notebooks = new Map(); // name -> Notebook
    this.active = null;
    this.views = new Map(); // cell id -> CellView, for the active notebook
    this.focusedCellId = null;
    this.names = [];
    this.timer = setInterval(() => this.tickRunningCells(), 100);
  }

  async start() {
    this.applyTheme(localStorage.getItem("kotnb.theme") ?? (matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light"));
    $("#theme-toggle").addEventListener("click", () => this.applyTheme(document.documentElement.dataset.theme === "dark" ? "light" : "dark"));
    $("#new-notebook").addEventListener("click", () => this.promptNewNotebook());
    $("#empty-new").addEventListener("click", () => this.promptNewNotebook());
    $("#run-all").addEventListener("click", () => this.active?.run(this.active.cells));
    $("#interrupt").addEventListener("click", () => this.active?.connection.send({ type: "interrupt" }));
    $("#restart").addEventListener("click", () => this.restartKernel());
    $("#clear-outputs").addEventListener("click", () => this.clearOutputs());
    $("#notebook-title").addEventListener("click", () => this.beginRename());
    $("#delete-notebook").addEventListener("click", () => this.deleteNotebook());
    window.addEventListener("hashchange", () => this.route());
    window.addEventListener("keydown", (event) => {
      if ((event.ctrlKey || event.metaKey) && event.key === "s") {
        event.preventDefault();
        this.active?.save();
      }
    });
    window.addEventListener("beforeunload", (event) => {
      for (const notebook of this.notebooks.values()) {
        if (notebook.saveState === "dirty" || notebook.saveState === "saving") {
          notebook.save();
          event.preventDefault();
        }
      }
    });

    api.info().then((info) => {
      $("#version").textContent = `Kotlin ${info.kotlinVersion}`;
      $("#version").title = `Kotlin ${info.kotlinVersion} on Java ${info.javaVersion}\nNotebooks in ${info.notebookDirectory}`;
    });
    await this.refreshList();
    await this.route();
  }

  applyTheme(theme) {
    document.documentElement.dataset.theme = theme;
    localStorage.setItem("kotnb.theme", theme);
    const toggle = $("#theme-toggle");
    toggle.replaceChildren(icon(theme === "dark" ? "sun" : "moon"));
    toggle.title = theme === "dark" ? "Switch to light theme" : "Switch to dark theme";
  }

  // ---- notebook list and routing ----

  async refreshList() {
    try {
      this.names = (await api.list()).map((n) => n.name);
    } catch (error) {
      this.toast(`Could not list notebooks: ${error.message}`);
    }
    this.renderList();
  }

  renderList() {
    const list = $("#notebook-list");
    list.replaceChildren(
      ...this.names.map((name) => {
        const slash = name.lastIndexOf("/");
        const folder = slash >= 0 ? name.slice(0, slash + 1) : "";
        const base = name.slice(slash + 1);
        return element(
          "li",
          {},
          element(
            "a",
            {
              href: `#/${encodeURI(name)}`,
              class: this.active?.name === name ? "active" : null,
              title: `${name}.kot`,
            },
            icon("notebook"),
            element("span", { class: "notebook-label" }, folder && element("span", { class: "folder" }, folder), base),
          ),
        );
      }),
    );
    $("#list-empty").hidden = this.names.length > 0;
  }

  currentRouteName() {
    const hash = decodeURI(location.hash.replace(/^#\/?/, ""));
    return hash || null;
  }

  async route() {
    const name = this.currentRouteName();
    if (!name) {
      if (this.names.length > 0 && !this.active) {
        location.replace(`#/${encodeURI(this.names[0])}`);
        return;
      }
      this.show(null);
      return;
    }
    if (this.active?.name === name) return;
    let notebook = this.notebooks.get(name);
    if (!notebook) {
      try {
        const loaded = await api.load(name);
        notebook = this.notebooks.get(loaded.name) ?? new Notebook(loaded.name, loaded.cells);
        this.notebooks.set(notebook.name, notebook);
      } catch (error) {
        this.toast(`Could not open “${name}”: ${error.message}`);
        this.show(null);
        return;
      }
    }
    this.show(notebook);
  }

  show(notebook) {
    if (this.active && this.active !== notebook) this.syncSources();
    this.active = notebook;
    this.views.clear();
    this.focusedCellId = null;
    $("#workspace").hidden = !notebook;
    $("#empty-state").hidden = !!notebook;
    this.renderList();
    if (!notebook) {
      document.title = "Kotlin Notebook";
      return;
    }
    document.title = `${notebook.name} · Kotlin Notebook`;
    $("#notebook-title").textContent = notebook.name;
    this.renderSaveState(notebook);
    this.renderKernel(notebook);
    this.renderCells();
    $("#cells").scrollTop = 0;
  }

  /** Editors push changes to models as they happen; this only guards against a pending CodeMirror update. */
  syncSources() {
    for (const [id, view] of this.views) {
      const cell = this.active.cell(id);
      if (cell) cell.source = view.editor.state.doc.toString();
    }
  }

  async promptNewNotebook() {
    const name = await this.dialog({
      title: "New notebook",
      message: "Saved as notebooks/<name>.kot. Use “/” to put it in a folder.",
      input: this.suggestName(),
      confirm: "Create",
    });
    if (name == null) return;
    try {
      const created = await api.create(name, [{ type: "kotlin", source: "" }]);
      await this.refreshList();
      location.hash = `#/${encodeURI(created.name)}`;
    } catch (error) {
      this.toast(error.message);
    }
  }

  suggestName() {
    for (let i = 1; ; i++) {
      const candidate = i === 1 ? "Untitled" : `Untitled ${i}`;
      if (!this.names.includes(candidate)) return candidate;
    }
  }

  beginRename() {
    const notebook = this.active;
    if (!notebook) return;
    const title = $("#notebook-title");
    const input = element("input", { class: "title-input", value: notebook.name, "aria-label": "Notebook name", spellcheck: "false" });
    title.hidden = true;
    title.after(input);
    input.focus();
    input.select();
    let done = false;
    const finish = async (commit) => {
      if (done) return;
      done = true;
      const to = input.value.trim();
      input.remove();
      title.hidden = false;
      if (!commit || !to || to === notebook.name) return;
      try {
        await notebook.save();
        const renamed = await api.rename(notebook.name, to);
        this.notebooks.delete(notebook.name);
        notebook.name = renamed.name;
        this.notebooks.set(notebook.name, notebook);
        notebook.connection.reconnect();
        await this.refreshList();
        history.replaceState(null, "", `#/${encodeURI(notebook.name)}`);
        this.show(notebook);
      } catch (error) {
        this.toast(error.message);
      }
    };
    input.addEventListener("keydown", (event) => {
      if (event.key === "Enter") finish(true);
      if (event.key === "Escape") finish(false);
    });
    input.addEventListener("blur", () => finish(true));
  }

  async deleteNotebook() {
    const notebook = this.active;
    if (!notebook) return;
    const ok = await this.dialog({
      title: `Delete “${notebook.name}”?`,
      message: `This deletes ${notebook.name}.kot and stops its kernel. It cannot be undone.`,
      confirm: "Delete",
      danger: true,
    });
    if (!ok) return;
    try {
      clearTimeout(notebook.saveTimer);
      await api.remove(notebook.name);
      notebook.close();
      this.notebooks.delete(notebook.name);
      this.active = null;
      await this.refreshList();
      location.hash = this.names.length ? `#/${encodeURI(this.names[0])}` : "#/";
      if (!this.names.length) this.show(null);
    } catch (error) {
      this.toast(error.message);
    }
  }

  async restartKernel() {
    const notebook = this.active;
    if (!notebook) return;
    if (notebook.connection.send({ type: "restart" })) {
      notebook.abandonRunningCells("interrupted");
      for (const cell of notebook.cells) {
        cell.executionCount = null;
        this.renderCellState(notebook, cell);
      }
    }
  }

  clearOutputs() {
    const notebook = this.active;
    if (!notebook) return;
    for (const cell of notebook.cells) {
      if (cell.status === "queued" || cell.status === "running") continue;
      cell.outputs = [];
      cell.diagnostics = [];
      cell.status = null;
      cell.executionCount = null;
      this.renderCellState(notebook, cell);
    }
  }

  // ---- header ----

  renderSaveState(notebook) {
    if (notebook !== this.active) return;
    const label = $("#save-state");
    const text = { saved: "Saved", dirty: "Edited", saving: "Saving…", error: "Not saved" }[notebook.saveState];
    label.textContent = text;
    label.dataset.state = notebook.saveState;
    label.title = notebook.saveState === "error" ? `Saving failed: ${notebook.saveError}` : `${notebook.name}.kot`;
  }

  renderKernel(notebook) {
    if (notebook !== this.active) return;
    const pill = $("#kernel-state");
    pill.dataset.state = notebook.kernel.state;
    $("#kernel-label").textContent = KERNEL_LABELS[notebook.kernel.state] ?? notebook.kernel.state;
    pill.title = notebook.kernel.message ?? "Kotlin kernel for this notebook";
    $("#interrupt").disabled = notebook.kernel.state !== "busy";
  }

  // ---- cells ----

  renderCells() {
    const notebook = this.active;
    const container = $("#cells");
    container.replaceChildren();
    container.append(this.insertBar(0));
    notebook.cells.forEach((cell, index) => {
      const view = new CellView(this, notebook, cell);
      this.views.set(cell.id, view);
      container.append(view.root, this.insertBar(index + 1));
    });
  }

  insertBar(index) {
    const isLast = index === this.active.cells.length;
    return element(
      "div",
      { class: `insert-bar${isLast ? " last" : ""}` },
      element("button", { type: "button", class: "insert-button", onclick: () => this.insertCell(index, "kotlin") }, icon("plus"), "Code"),
      element("button", { type: "button", class: "insert-button", onclick: () => this.insertCell(index, "markdown") }, icon("plus"), "Markdown"),
    );
  }

  insertCell(index, type) {
    const notebook = this.active;
    const cell = newCell(type);
    cell.editing = type === "markdown";
    notebook.cells.splice(index, 0, cell);
    notebook.markDirty();
    this.renderCells();
    this.views.get(cell.id)?.focus();
    return cell;
  }

  moveCell(cell, delta) {
    const notebook = this.active;
    const index = notebook.cells.indexOf(cell);
    const target = index + delta;
    if (target < 0 || target >= notebook.cells.length) return;
    notebook.cells.splice(index, 1);
    notebook.cells.splice(target, 0, cell);
    notebook.markDirty();
    this.renderCells();
    this.views.get(cell.id)?.focus();
  }

  deleteCell(cell) {
    const notebook = this.active;
    const index = notebook.cells.indexOf(cell);
    notebook.cells.splice(index, 1);
    if (notebook.cells.length === 0) notebook.cells.push(newCell());
    notebook.markDirty();
    this.renderCells();
    const neighbor = notebook.cells[Math.min(index, notebook.cells.length - 1)];
    this.views.get(neighbor.id)?.focus();
  }

  focusNeighbor(cell, delta, where = "start") {
    const notebook = this.active;
    const neighbor = notebook.cells[notebook.cells.indexOf(cell) + delta];
    if (!neighbor) return false;
    this.views.get(neighbor.id)?.focus(where);
    return true;
  }

  runAndAdvance(cell) {
    const notebook = this.active;
    notebook.run([cell]);
    const index = notebook.cells.indexOf(cell);
    if (index === notebook.cells.length - 1) this.insertCell(index + 1, "kotlin");
    else this.views.get(notebook.cells[index + 1].id)?.focus();
  }

  setFocused(cell) {
    if (this.focusedCellId === cell.id) return;
    this.views.get(this.focusedCellId)?.root.classList.remove("focused");
    this.focusedCellId = cell.id;
    this.views.get(cell.id)?.root.classList.add("focused");
  }

  renderCellState(notebook, cell) {
    if (notebook !== this.active) return;
    this.views.get(cell.id)?.renderState();
  }

  appendStream(notebook, cell, stream, text) {
    if (notebook !== this.active) return;
    this.views.get(cell.id)?.appendStream(stream, text);
  }

  tickRunningCells() {
    if (!this.active) return;
    for (const cell of this.active.cells) {
      if (cell.status === "running") this.views.get(cell.id)?.renderStatus();
    }
  }

  // ---- dialogs ----

  /** A small modal. Resolves to the input text (or true without input) when confirmed, null when cancelled. */
  dialog({ title, message, input, confirm, danger = false }) {
    return new Promise((resolve) => {
      const field = input != null ? element("input", { class: "dialog-input", value: input, spellcheck: "false", "aria-label": title }) : null;
      const error = element("div", { class: "dialog-error", hidden: true });
      const close = (value) => {
        overlay.remove();
        resolve(value);
      };
      const submit = () => {
        if (!field) return close(true);
        const value = field.value.trim();
        if (!value) {
          error.textContent = "Enter a name";
          error.hidden = false;
          return;
        }
        close(value);
      };
      const overlay = element(
        "div",
        { class: "overlay", onmousedown: (event) => event.target === overlay && close(null) },
        element(
          "div",
          { class: "dialog", role: "dialog", "aria-modal": "true", "aria-label": title },
          element("h2", {}, title),
          message && element("p", {}, message),
          field,
          error,
          element(
            "div",
            { class: "dialog-actions" },
            element("button", { type: "button", class: "button", onclick: () => close(null) }, "Cancel"),
            element("button", { type: "button", class: `button primary${danger ? " danger" : ""}`, onclick: submit }, confirm),
          ),
        ),
      );
      overlay.addEventListener("keydown", (event) => {
        if (event.key === "Escape") close(null);
        if (event.key === "Enter") submit();
      });
      document.body.append(overlay);
      if (field) {
        field.focus();
        field.select();
      } else {
        overlay.querySelector(".primary").focus();
      }
    });
  }

  toast(message) {
    const node = element("div", { class: "toast", role: "status" }, message);
    $("#toasts").append(node);
    setTimeout(() => node.classList.add("leaving"), 4500);
    setTimeout(() => node.remove(), 5000);
  }
}

/** The DOM for one cell of the active notebook. */
class CellView {
  constructor(ui, notebook, cell) {
    this.ui = ui;
    this.notebook = notebook;
    this.cell = cell;

    this.count = element("div", { class: "execution-count" });
    this.runButton = iconButton("run", "Run cell (Shift+Enter)", () => this.run(), "run-button");
    this.typeSelect = element(
      "select",
      { class: "type-select", "aria-label": "Cell type", onchange: (event) => this.changeType(event.target.value) },
      element("option", { value: "kotlin" }, "Kotlin"),
      element("option", { value: "markdown" }, "Markdown"),
    );
    this.typeSelect.value = cell.type;
    this.editorHost = element("div", { class: "editor" });
    this.markdown = element("div", { class: "markdown-body", ondblclick: () => this.editMarkdown(), title: "Double-click to edit" });
    this.output = element("div", { class: "output" });
    this.status = element("div", { class: "cell-status" });

    this.root = element(
      "section",
      { class: "cell", "data-cell-id": cell.id },
      element("div", { class: "gutter" }, this.runButton, this.count),
      element(
        "div",
        { class: "cell-main" },
        element(
          "div",
          { class: "cell-toolbar" },
          this.typeSelect,
          iconButton("up", "Move cell up", () => ui.moveCell(cell, -1)),
          iconButton("down", "Move cell down", () => ui.moveCell(cell, 1)),
          iconButton("trash", "Delete cell", () => ui.deleteCell(cell), "danger"),
        ),
        this.editorHost,
        this.markdown,
        this.output,
        this.status,
      ),
    );
    this.root.addEventListener("mousedown", () => ui.setFocused(cell));

    this.editor = createEditor(this.editorHost, {
      doc: cell.source,
      kind: cell.type,
      handlers: {
        onChange: (text) => {
          cell.source = text;
          notebook.markDirty();
        },
        onFocus: () => ui.setFocused(cell),
        complete: (code, cursor, signal) =>
          cell.type === "kotlin" ? api.complete(notebook.name, cell.id, code, cursor, signal) : Promise.resolve([]),
        keys: [
          { key: "Shift-Enter", run: () => (ui.runAndAdvance(cell), true) },
          { key: "Mod-Enter", run: () => (this.run(), true) },
          {
            key: "Alt-Enter",
            run: () => {
              this.run();
              ui.insertCell(notebook.cells.indexOf(cell) + 1, "kotlin");
              return true;
            },
          },
        ],
        navigationKeys: [
          { key: "ArrowUp", run: (view) => isCursorOnFirstLine(view) && ui.focusNeighbor(cell, -1, "end") },
          { key: "ArrowDown", run: (view) => isCursorOnLastLine(view) && ui.focusNeighbor(cell, 1, "start") },
        ],
      },
    });
    this.renderState();
  }

  run() {
    if (this.cell.type === "markdown") {
      this.cell.editing = false;
      this.renderState();
      return;
    }
    this.notebook.run([this.cell]);
  }

  focus(where = "start") {
    if (this.cell.type === "markdown" && !this.cell.editing) {
      this.ui.setFocused(this.cell);
      this.root.scrollIntoView({ block: "nearest" });
      return;
    }
    const position = where === "end" ? this.editor.state.doc.length : 0;
    this.editor.dispatch({ selection: { anchor: position }, scrollIntoView: true });
    this.editor.focus();
  }

  changeType(type) {
    this.cell.type = type;
    this.cell.outputs = [];
    this.cell.diagnostics = [];
    this.cell.status = null;
    this.cell.executionCount = null;
    this.cell.editing = type === "markdown";
    setEditorKind(this.editor, type);
    this.notebook.markDirty();
    this.renderState();
    this.focus("end");
  }

  editMarkdown() {
    this.cell.editing = true;
    this.renderState();
    this.focus("end");
  }

  renderState() {
    const cell = this.cell;
    const isMarkdown = cell.type === "markdown";
    this.root.classList.toggle("markdown", isMarkdown);
    this.root.dataset.status = cell.status ?? "";
    const showRendered = isMarkdown && !cell.editing;
    this.editorHost.hidden = showRendered;
    this.markdown.hidden = !showRendered;
    if (showRendered) {
      this.markdown.classList.toggle("empty", !cell.source.trim());
      this.markdown.innerHTML = cell.source.trim() ? marked.parse(cell.source) : "Empty markdown cell. Double-click to edit.";
    }
    this.runButton.title = isMarkdown ? "Render markdown (Shift+Enter)" : "Run cell (Shift+Enter)";
    this.count.textContent = isMarkdown ? "" : cell.status === "running" ? "[*]" : cell.status === "queued" ? "[ ]" : cell.executionCount ? `[${cell.executionCount}]` : "";
    this.renderStatus();
    this.renderOutputs();
    showDiagnostics(this.editor, cell.diagnostics);
  }

  renderStatus() {
    const cell = this.cell;
    const status = this.status;
    status.dataset.status = cell.status ?? "";
    if (cell.type === "markdown" || !cell.status) {
      status.hidden = true;
      return;
    }
    status.hidden = false;
    const elapsed = cell.startedAt ? performance.now() - cell.startedAt : 0;
    const text = {
      queued: "Queued",
      running: `Running… ${formatDuration(elapsed)}`,
      ok: `Done in ${formatDuration(cell.durationMs ?? 0)}`,
      error: cell.durationMs ? `Failed after ${formatDuration(cell.durationMs)}` : "Failed",
      interrupted: "Interrupted",
      cancelled: "Cancelled because an earlier cell failed",
    }[cell.status];
    status.replaceChildren(element("span", { class: "status-dot" }), text);
  }

  renderOutputs() {
    const cell = this.cell;
    this.output.replaceChildren();
    if (cell.type === "markdown") {
      this.output.hidden = true;
      return;
    }
    const errors = cell.diagnostics.filter((d) => d.severity === "ERROR");
    if (errors.length) this.output.append(this.renderDiagnostics(errors));
    for (const item of cell.outputs) {
      if (item.kind === "stream") this.output.append(element("pre", { class: `stream ${item.stream}` }, item.text));
      else if (item.kind === "error") this.output.append(element("pre", { class: "runtime-error" }, item.text));
      else if (item.kind === "result") this.output.append(this.renderResult(item));
    }
    this.output.hidden = this.output.childElementCount === 0;
  }

  renderResult(item) {
    if (item.html) return element("div", { class: "result html-result", html: item.text });
    return element(
      "div",
      { class: "result" },
      element("pre", { class: "result-value" }, item.text),
      element("span", { class: "result-type", title: "Type of the cell's value" }, item.valueType),
    );
  }

  renderDiagnostics(diagnostics) {
    return element(
      "ul",
      { class: "diagnostics" },
      diagnostics.map((d) =>
        element(
          "li",
          {
            class: "diagnostic",
            title: d.line ? "Go to error" : null,
            onclick: () => d.line && this.goTo(d.line, d.column),
          },
          element("span", { class: "diagnostic-position" }, d.line ? `${d.line}:${d.column ?? 1}` : "error"),
          element("span", { class: "diagnostic-message" }, d.message),
        ),
      ),
    );
  }

  goTo(line, column) {
    const doc = this.editor.state.doc;
    const target = doc.line(Math.min(line, doc.lines));
    const anchor = Math.min(target.from + Math.max((column ?? 1) - 1, 0), target.to);
    this.editor.dispatch({ selection: { anchor }, scrollIntoView: true });
    this.editor.focus();
  }

  appendStream(stream, text) {
    const last = this.output.lastElementChild;
    if (last && last.matches(`pre.stream.${stream}`)) {
      last.append(text);
    } else {
      this.output.append(element("pre", { class: `stream ${stream}` }, text));
    }
    this.output.hidden = false;
  }
}

const ui = new Ui();
ui.start();
