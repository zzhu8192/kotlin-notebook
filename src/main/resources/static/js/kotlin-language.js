// Kotlin syntax highlighting modeled on IntelliJ IDEA's highlighter, as a CodeMirror stream language.
import { StreamLanguage, HighlightStyle, tags as t } from "../vendor/vendor.js";

const HARD_KEYWORDS = new Set([
  "as", "break", "class", "continue", "do", "else", "for", "fun", "if", "in", "interface", "is", "object",
  "package", "return", "super", "this", "throw", "try", "typealias", "typeof", "val", "var", "when", "while",
]);
const LITERAL_KEYWORDS = new Set(["true", "false", "null"]);
// Modifiers and soft keywords that are almost never used as identifiers.
const SOFT_KEYWORDS = new Set([
  "abstract", "actual", "by", "catch", "companion", "const", "constructor", "crossinline", "expect", "external",
  "final", "finally", "import", "infix", "init", "inline", "internal", "lateinit", "noinline", "open", "operator",
  "out", "override", "private", "protected", "public", "reified", "sealed", "suspend", "tailrec", "vararg", "where",
]);
// Soft keywords that are common identifiers, so they only count as keywords before a declaration.
const DECLARATION_MODIFIERS = new Set(["data", "enum", "value", "annotation", "inner"]);
const DECLARATION_AHEAD = /^\s+(?:(?:data|enum|value|annotation|inner|sealed|open|abstract|private|internal|public|protected|inline|suspend|fun)\s+)*(?:class|interface|object|fun)\b/;

const IDENT = /^[A-Za-z_][\w]*/;
const NUMBER = /^(?:0[xX][\da-fA-F_]+|0[bB][01_]+|(?:\d[\d_]*)?\.?\d[\d_]*(?:[eE][+-]?\d[\d_]*)?)(?:[uU]?[lL]|[uU]|[fF])?/;

function startState() {
  // `stack` tracks nested lexical contexts: strings, string template expressions and block comments.
  return { stack: [], depth: 0, afterFun: false, afterDot: false, inImport: false };
}

function copyState(state) {
  return { ...state, stack: state.stack.map((frame) => ({ ...frame })) };
}

function top(state) {
  return state.stack[state.stack.length - 1];
}

function tokenString(stream, state, frame) {
  // Inside "..." or """...""": stop at the next escape, template or end of the string.
  if (frame.raw) {
    if (stream.match('"""')) {
      while (stream.eat('"')); // Kotlin allows extra quotes right before the closing delimiter.
      state.stack.pop();
      return "string";
    }
  } else {
    if (stream.eat('"')) {
      state.stack.pop();
      return "string";
    }
    if (stream.match(/^\\(?:u[\da-fA-F]{4}|.)/)) return "escape";
  }
  const dollars = frame.dollars || 1;
  const prefix = "$".repeat(dollars);
  if (stream.match(prefix + "{")) {
    state.stack.push({ kind: "template", braces: 0 });
    return "templateBrace";
  }
  if (stream.match(new RegExp("^" + prefix.replace(/\$/g, "\\$") + "[A-Za-z_][\\w]*"))) return "templateEntry";
  // Consume plain string content up to something interesting.
  const stopper = frame.raw ? /^[^"$]+/ : /^[^"$\\]+/;
  if (!stream.match(stopper)) stream.next();
  if (!frame.raw && stream.eol()) state.stack.pop(); // Unterminated single-line string.
  return "string";
}

function tokenBlockComment(stream, state, frame) {
  while (!stream.eol()) {
    if (stream.match("/*")) {
      frame.depth++;
    } else if (stream.match("*/")) {
      frame.depth--;
      if (frame.depth === 0) {
        state.stack.pop();
        break;
      }
    } else if (frame.doc && stream.match(/^@\w+/)) {
      return "docTag";
    } else {
      stream.match(/^[^*/@]+/) || stream.next();
    }
  }
  return frame.doc ? "docComment" : "comment";
}

function token(stream, state) {
  const frame = top(state);
  if (frame && frame.kind === "string") return tokenString(stream, state, frame);
  if (frame && frame.kind === "comment") return tokenBlockComment(stream, state, frame);
  if (stream.sol()) state.inImport = false;
  if (stream.eatSpace()) return null;
  if (state.inImport && stream.match(/^[\w.*`]+/)) return "variable"; // Package paths are not property accesses.

  const wasAfterDot = state.afterDot;
  state.afterDot = false;

  if (stream.match("//")) {
    stream.skipToEnd();
    return "comment";
  }
  if (stream.match(/^\/\*\*(?!\/)/)) {
    state.stack.push({ kind: "comment", depth: 1, doc: true });
    return tokenBlockComment(stream, state, top(state));
  }
  if (stream.match("/*")) {
    state.stack.push({ kind: "comment", depth: 1, doc: false });
    return tokenBlockComment(stream, state, top(state));
  }

  const multiDollar = stream.match(/^(\$+)(?=")/);
  if (multiDollar || stream.peek() === '"') {
    const dollars = multiDollar ? multiDollar[1].length : 1;
    const raw = !!stream.match('"""');
    if (!raw) stream.next();
    state.stack.push({ kind: "string", raw, dollars });
    return "string";
  }
  if (stream.match(/^'(?:\\(?:u[\da-fA-F]{4}|.)|[^'\\])'/)) return "string";

  if (frame && frame.kind === "template") {
    if (stream.peek() === "{") frame.braces++;
    if (stream.peek() === "}") {
      if (frame.braces === 0) {
        stream.next();
        state.stack.pop();
        return "templateBrace";
      }
      frame.braces--;
    }
  }

  // `return@outer`, `break@loop` and `this@Outer` reference labels; other `@Name`s are annotations.
  if (/(?:return|break|continue|this|super)$/.test(stream.string.slice(0, stream.start)) && stream.match(/^@\w+/)) {
    return "label";
  }
  if (stream.match(/^@(?:file:)?[A-Za-z_][\w.]*/)) return "annotation";
  if (stream.match(/^[A-Za-z_]\w*@(?![\w])/)) return "label";
  if (stream.match(/^`[^`]+`/)) return wasAfterDot ? "property" : "variable";
  if (/\d/.test(stream.peek()) || (stream.peek() === "." && /\d/.test(stream.string.charAt(stream.pos + 1)))) {
    if (stream.match(NUMBER)) return "number";
  }

  const ident = stream.match(IDENT);
  if (ident) return identifier(stream, state, ident[0], wasAfterDot);

  const ch = stream.next();
  if (ch === "{" || ch === "(" || ch === "[") state.depth++;
  if (ch === "}" || ch === ")" || ch === "]") state.depth = Math.max(0, state.depth - 1);
  if (ch === "(") state.afterFun = false;
  if (ch === "." || (ch === "?" && stream.eat("."))) state.afterDot = true;
  if (ch === ":" && stream.eat(":")) state.afterDot = true;
  if (/[{}()[\]]/.test(ch)) return "bracket";
  if (/[;,]/.test(ch)) return "punctuation";
  return "operator";
}

function identifier(stream, state, word, afterDot) {
  if (afterDot) {
    // `.name(`, `.name {` and `.name<` are calls; anything else reads a property.
    return stream.match(/^\s*(?:[({]|<[\w\s,<>?*.]*>\s*\()/, false) ? "variable" : "property";
  }
  if (LITERAL_KEYWORDS.has(word)) return "keyword";
  if (word === "import" || word === "package") {
    state.inImport = true;
    return "keyword";
  }
  if (HARD_KEYWORDS.has(word)) {
    state.afterFun = word === "fun";
    return "keyword";
  }
  if (SOFT_KEYWORDS.has(word)) return "keyword";
  if (DECLARATION_MODIFIERS.has(word) && stream.match(DECLARATION_AHEAD, false)) return "keyword";
  if (state.afterFun) {
    // In `fun <T> Receiver.name(`, the identifier followed by `.` is the receiver type, not the name.
    if (stream.match(/^\s*\./, false)) return /^[A-Z]/.test(word) ? "type" : "variable";
    state.afterFun = false;
    return "functionDefinition";
  }
  return /^[A-Z]/.test(word) ? "type" : "variable";
}

function indent(state, textAfter, context) {
  if (top(state)) return null; // Inside a string or comment: keep the user's indentation.
  const closing = /^\s*[}\])]/.test(textAfter) ? 1 : 0;
  return Math.max(0, state.depth - closing) * context.unit;
}

export const kotlinLanguage = StreamLanguage.define({
  name: "kotlin",
  startState,
  copyState,
  token,
  indent,
  languageData: {
    commentTokens: { line: "//", block: { open: "/*", close: "*/" } },
    closeBrackets: { brackets: ["(", "[", "{", '"', "'"] },
    indentOnInput: /^\s*[}\])]$/,
  },
  tokenTable: {
    keyword: t.keyword,
    string: t.string,
    escape: t.escape,
    templateEntry: t.special(t.string),
    templateBrace: t.special(t.brace),
    number: t.number,
    comment: t.lineComment,
    docComment: t.docComment,
    docTag: t.special(t.docComment),
    annotation: t.annotation,
    label: t.labelName,
    property: t.propertyName,
    variable: t.variableName,
    type: t.typeName,
    functionDefinition: t.function(t.definition(t.variableName)),
    bracket: t.bracket,
    punctuation: t.punctuation,
    operator: t.operator,
  },
});

// Colors come from CSS variables so light (IntelliJ Light) and dark (Darcula) themes switch without rebuilding editors.
export const kotlinHighlightStyle = HighlightStyle.define([
  { tag: t.keyword, color: "var(--hl-keyword)", fontWeight: "var(--hl-keyword-weight)" },
  { tag: t.string, color: "var(--hl-string)" },
  { tag: [t.escape, t.special(t.string), t.special(t.brace)], color: "var(--hl-escape)", fontWeight: "var(--hl-escape-weight)" },
  { tag: t.number, color: "var(--hl-number)" },
  { tag: t.lineComment, color: "var(--hl-comment)", fontStyle: "italic" },
  { tag: t.docComment, color: "var(--hl-doc-comment)", fontStyle: "italic" },
  { tag: t.special(t.docComment), color: "var(--hl-doc-comment)", fontStyle: "italic", fontWeight: "bold" },
  { tag: t.annotation, color: "var(--hl-annotation)" },
  { tag: t.labelName, color: "var(--hl-label)" },
  { tag: t.propertyName, color: "var(--hl-property)" },
  { tag: t.function(t.definition(t.variableName)), color: "var(--hl-function-declaration)" },
  // Markdown cells.
  { tag: t.heading, color: "var(--hl-keyword)", fontWeight: "bold" },
  { tag: t.emphasis, fontStyle: "italic" },
  { tag: t.strong, fontWeight: "bold" },
  { tag: t.link, color: "var(--hl-number)", textDecoration: "underline" },
  { tag: t.url, color: "var(--hl-string)" },
  { tag: t.monospace, color: "var(--hl-string)" },
  { tag: t.quote, color: "var(--hl-comment)", fontStyle: "italic" },
  { tag: [t.processingInstruction, t.contentSeparator], color: "var(--hl-annotation)" },
  { tag: t.list, color: "var(--hl-text)" },
]);
