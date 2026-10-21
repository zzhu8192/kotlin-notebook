// Re-exports every third-party module the UI needs, so the browser loads a single offline bundle.
export { EditorView, keymap, lineNumbers, highlightActiveLine, highlightActiveLineGutter, drawSelection, placeholder, highlightSpecialChars } from "@codemirror/view";
export { EditorState, Compartment, Prec } from "@codemirror/state";
export { StreamLanguage, HighlightStyle, syntaxHighlighting, indentOnInput, bracketMatching, indentUnit, foldGutter, foldKeymap } from "@codemirror/language";
export { defaultKeymap, history, historyKeymap, indentWithTab, insertNewlineAndIndent } from "@codemirror/commands";
export { autocompletion, completionKeymap, closeBrackets, closeBracketsKeymap, startCompletion, acceptCompletion } from "@codemirror/autocomplete";
export { searchKeymap, highlightSelectionMatches } from "@codemirror/search";
export { setDiagnostics, lintGutter } from "@codemirror/lint";
export { markdown as markdownLanguage } from "@codemirror/lang-markdown";
export { tags } from "@lezer/highlight";
export { marked } from "marked";
