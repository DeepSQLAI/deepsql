// Inline markdown for Agent chat, rendered through dangerouslySetInnerHTML in AgentView.
//
// Every character returned here is parsed as HTML, so the escape is not optional. The
// original applied its substitutions to the raw string and escaped nothing, which made
// agent output — and any database value the agent echoes, such as a table name — render
// as markup in the reader's browser. Confirmed by executing the shipped function:
// boldify('<img src=x onerror=alert(document.cookie)>') returned the payload byte for byte.
//
// The payload is stored rather than reflected: agent_conversation.transcript is replayed
// into this renderer on load, so it fires again on every visit. Auth is an httpOnly
// cookie, so the token itself cannot be read — but client.js sends withCredentials, so
// injected script acts as the reader against the API.
//
// Order is load-bearing. Escaping must happen BEFORE the substitutions; escaping after
// would also escape the <strong> and <code> tags this function emits, and the reader
// would see literal tag text instead of formatting. boldify.test.js pins both halves.

const HTML_ESCAPES = {
  "&": "&amp;",
  "<": "&lt;",
  ">": "&gt;",
  '"': "&quot;",
  "'": "&#39;",
};

function escapeHtml(text) {
  return text.replace(/[&<>"']/g, (char) => HTML_ESCAPES[char]);
}

const PLAIN_CODE_TAG = "<code>$1</code>";

/**
 * Escapes HTML, then renders `**bold**` and `` `code` `` as real tags.
 *
 * `codeTag` lets a caller supply its own <code> markup — Brain's AgentArtifacts styles
 * its inline code differently. Only presentation is a parameter; the escaping above is
 * shared on purpose, so the two renderers cannot drift apart on the half that matters.
 */
export function boldify(text, codeTag = PLAIN_CODE_TAG) {
  if (!text) return "";
  return escapeHtml(text)
    .replace(/\*\*(.*?)\*\*/g, "<strong>$1</strong>")
    .replace(/`([^`]+)`/g, codeTag);
}
