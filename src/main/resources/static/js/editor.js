// CodeMirror setup shared by all cells.
import {
  EditorView, EditorState, Compartment, Prec, keymap, lineNumbers, highlightActiveLine, highlightActiveLineGutter,
  drawSelection, highlightSpecialChars, placeholder, indentOnInput, bracketMatching, indentUnit, syntaxHighlighting,
  defaultKeymap, history, historyKeymap, indentWithTab, autocompletion, completionKeymap, closeBrackets,
  closeBracketsKeymap, acceptCompletion, searchKeymap, highlightSelectionMatches, setDiagnostics, markdownLanguage,
} from "../vendor/vendor.js";
import { kotlinLanguage, kotlinHighlightStyle } from "./kotlin-language.js";

const COMPLETION_TYPES = {
  method: "method",
  property: "property",
  class: "class",
  package: "namespace",
  keyword: "keyword",
  parameter: "variable",
  file: "text",
};

/**
 * Builds a CodeMirror completion source backed by the server's Kotlin completer.
 * `request(code, cursor, signal)` must resolve to the server's completion items.
 */
export function kotlinCompletionSource(request) {
  return async (context) => {
    const word = context.matchBefore(/[\w$]*/);
    const charBefore = context.state.sliceDoc(Math.max(0, word.from - 1), word.from);
    if (!context.explicit && word.from === word.to && charBefore !== ".") return null;
    if (!context.explicit && insideStringOrComment(context)) return null;

    const controller = new AbortController();
    context.addEventListener("abort", () => controller.abort());
    let items;
    try {
      items = await request(context.state.doc.toString(), context.pos, controller.signal);
    } catch (error) {
      if (error.name !== "AbortError") console.warn("Completion failed", error);
      return null;
    }
    if (!items.length) return null;
    return {
      from: word.from,
      validFor: /^[\w$]*$/,
      options: items.map((item) => ({
        label: item.displayText,
        detail: item.tail,
        type: COMPLETION_TYPES[item.icon] ?? "text",
        apply: applyCompletion(item.text),
        deprecated: item.deprecated,
      })),
    };
  };
}

function insideStringOrComment(context) {
  const line = context.state.doc.lineAt(context.pos);
  const before = line.text.slice(0, context.pos - line.from);
  if (/\/\/.*$/.test(before.replace(/"(?:\\.|[^"\\])*"/g, '""'))) return true;
  const quotes = (before.replace(/\\./g, "").match(/"/g) || []).length;
  return quotes % 2 === 1 && !/\$\{[^}]*$/.test(before);
}

/** Inserts a completion the way IntelliJ does: `name(` becomes `name(|)` and `name { ` becomes `name { | }`. */
function applyCompletion(text) {
  return (view, completion, from, to) => {
    let insert = text;
    let cursor = text.length;
    const next = view.state.sliceDoc(to, to + 1);
    if (text.endsWith("(") && next !== "(") {
      insert = text + ")";
      cursor = text.length;
    } else if (text.endsWith("{ ") && next !== "{") {
      insert = text + " }";
      cursor = text.length;
    }
    view.dispatch({
      changes: { from, to, insert },
      selection: { anchor: from + cursor },
      userEvent: "input.complete",
    });
  };
}

const languageCompartment = new Compartment();

/**
 * Creates a cell editor.
 * `handlers` provides `onChange(text)`, `onFocus()`, `complete`, `keys` (bindings that win over everything) and
 * `navigationKeys` (bindings that only apply when the completion popup does not want the key).
 */
export function createEditor(parent, { doc, kind, handlers }) {
  const state = EditorState.create({
    doc,
    extensions: [
      lineNumbers(),
      highlightActiveLineGutter(),
      highlightSpecialChars(),
      history(),
      drawSelection(),
      indentOnInput(),
      bracketMatching(),
      closeBrackets(),
      highlightActiveLine(),
      highlightSelectionMatches(),
      indentUnit.of("    "),
      EditorState.tabSize.of(4),
      syntaxHighlighting(kotlinHighlightStyle),
      languageCompartment.of(languageFor(kind)),
      autocompletion({
        override: [kotlinCompletionSource(handlers.complete)],
        activateOnTypingDelay: 80,
        maxRenderedOptions: 200,
        icons: true,
        optionClass: (option) => (option.deprecated ? "cm-completion-deprecated" : ""),
      }),
      Prec.highest(keymap.of(handlers.keys)),
      keymap.of([
        { key: "Tab", run: acceptCompletion },
        ...closeBracketsKeymap,
        ...completionKeymap,
        ...handlers.navigationKeys,
        ...defaultKeymap,
        ...searchKeymap,
        ...historyKeymap,
        indentWithTab,
      ]),
      placeholder(kind === "markdown" ? "Markdown text, Shift+Enter to render" : "Kotlin code, Shift+Enter to run"),
      EditorView.updateListener.of((update) => {
        if (update.docChanged) handlers.onChange(update.state.doc.toString());
        if (update.focusChanged && update.view.hasFocus) handlers.onFocus();
      }),
    ],
  });
  return new EditorView({ state, parent });
}

export function setEditorKind(view, kind) {
  view.dispatch({ effects: languageCompartment.reconfigure(languageFor(kind)) });
  view.contentDOM.setAttribute("aria-label", kind === "markdown" ? "Markdown cell" : "Kotlin cell");
}

function languageFor(kind) {
  return kind === "markdown" ? [markdownLanguage(), EditorView.lineWrapping] : kotlinLanguage;
}

/** Shows compiler diagnostics (1-based line/column, as sent by the server) as squiggles in the editor. */
export function showDiagnostics(view, diagnostics) {
  const doc = view.state.doc;
  const toPos = (line, column) => {
    if (line == null) return 0;
    const target = doc.line(Math.min(Math.max(line, 1), doc.lines));
    return Math.min(target.from + Math.max((column ?? 1) - 1, 0), target.to);
  };
  const marks = diagnostics.map((d) => {
    const from = toPos(d.line, d.column);
    let to = d.endLine != null ? toPos(d.endLine, d.endColumn) : from;
    if (to <= from) to = Math.min(from + 1, doc.length);
    return {
      from: Math.min(from, to),
      to,
      severity: d.severity === "ERROR" ? "error" : "warning",
      message: d.message,
    };
  });
  view.dispatch(setDiagnostics(view.state, marks));
}

export function isCursorOnFirstLine(view) {
  const { head, empty } = view.state.selection.main;
  return empty && view.state.doc.lineAt(head).number === 1;
}

export function isCursorOnLastLine(view) {
  const { head, empty } = view.state.selection.main;
  return empty && view.state.doc.lineAt(head).number === view.state.doc.lines;
}
