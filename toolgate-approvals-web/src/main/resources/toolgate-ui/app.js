import {renderGraph} from "./graph.js";

// ToolGate approvals console. Everything shown comes from the server and, through the model, possibly
// from an attacker: it is built with DOM nodes and textContent only, never parsed as HTML.

const API = "/toolgate/api/approvals";
const REFRESH_MS = 30_000;
const HISTORY_DAYS = 7;
const SOON_MS = 2 * 60 * 60 * 1000;

const STATUS = {
    PENDING: ["Waiting for a decision", ""],
    RELEASED: ["Approved, about to run", ""],
    EXECUTING: ["Approved, running", ""],
    EXECUTED: ["Approved and ran", "closed"],
    FAILED: ["Approved, failed when it ran", "alert"],
    UNKNOWN: ["Approved, outcome unknown", "alert"],
    REFUSED: ["Approved, refused on re-check", "closed"],
    REJECTED: ["Rejected", "closed"],
    EXPIRED: ["Expired undecided", "closed"],
    CANCELLED: ["Withdrawn", "closed"],
};
const DECIDED = {APPROVE: "Approved", REJECT: "Rejected", CANCEL: "Withdrawn", RESOLVE: "Resolved"};
const ACCESS = {SUPER_ADMIN: "super admin", REVIEWER: "reviewer", AUDITOR: "auditor", REQUESTER: "requester"};
const SCRIPTS = ["Cyrillic", "Greek", "Armenian", "Hebrew", "Arabic", "Cherokee", "Han", "Hiragana", "Katakana", "Hangul"];

const $ = (id) => document.getElementById(id);

/** Shows what happened: "good" when the step went through, "warn" when it did not, plain for prompts. */
function say(text, tone = "") {
    $("message-text").textContent = text;
    $("message").className = `message ${tone}`;
    $("message-icon").setAttribute("href", tone === "good" ? "#i-ok" : "#i-alert");
}
let current = null;
let view = "inbox";

async function request(path, options = {}) {
    const response = await fetch(API + path, {
        credentials: "same-origin",
        headers: options.body ? {"Content-Type": "application/json", "X-ToolGate-Request": "1"} : {},
        ...options,
    });
    // A form-login application answers an ended session with a redirect to its login page, which fetch
    // follows: HTML instead of JSON means the same as a 401.
    const json = (response.headers.get("Content-Type") || "").includes("application/json");
    if (response.status === 401 || response.redirected || (response.ok && !json)) {
        throw new Error("Your session has ended. Sign in to the application again, then reload this page.");
    }
    const body = await response.json().catch(() => ({}));
    if (!response.ok) {
        throw new Error(body.error || `The server refused the request (${response.status}).`);
    }
    return body;
}

function element(tag, text, className) {
    const node = document.createElement(tag);
    if (text !== undefined && text !== null) {
        node.textContent = String(text);
    }
    if (className) {
        node.className = className;
    }
    return node;
}

function absolute(iso) {
    const date = new Date(iso);
    return Number.isNaN(date.getTime()) ? iso : date.toLocaleString();
}

/** "in 23 h", "in 12 min", or "expired"; and whether it is soon. */
function untilExpiry(iso) {
    const left = new Date(iso).getTime() - Date.now();
    if (Number.isNaN(left)) {
        return {text: iso, soon: false};
    }
    if (left <= 0) {
        return {text: "expired", soon: true};
    }
    const minutes = Math.round(left / 60_000);
    const text = minutes < 60 ? `in ${minutes} min` : minutes < 48 * 60 ? `in ${Math.round(minutes / 60)} h`
        : `in ${Math.round(minutes / 1440)} days`;
    return {text, soon: left < SOON_MS};
}

function party(p) {
    return p.tenant ? `${p.id} in ${p.tenant}` : p.id;
}

const UUID = /\b([0-9a-f]{8})-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b/gi;

/** Long generated IDs, such as session IDs inside a reason, cut to their first 8 characters for reading. */
function readable(text) {
    return text ? text.replace(UUID, "$1…") : text;
}

/** Sets a reason or detail: shortened for reading, the full text on hover. */
function setReadable(node, text) {
    node.textContent = readable(text);
    node.title = text && readable(text) !== text ? text : "";
}

/**
 * A line that tells requests apart: the short values the review view shows, such as "order 7744, amount 1250".
 * Short values first, since identifiers and amounts say more at a glance than free text.
 */
function summary(item) {
    return item.arguments.filter((argument) => argument.visibility === "SHOWN" && argument.display.length <= 24)
        .sort((a, b) => a.display.length - b.display.length)
        .slice(0, 3)
        .map((argument) => `${argument.name} ${argument.display}`)
        .join(", ");
}

const VISIBILITY_ORDER = {SHOWN: 0, MASKED: 1, HIDDEN: 2};

// Loading

async function loadViewer() {
    try {
        const me = await request("/me");
        $("application").textContent = me.application;
        const roles = me.viewer.roles.length ? `, ${me.viewer.roles.join(", ")}` : "";
        $("viewer").textContent = `${party(me.viewer)}${roles}`;
        $("initials").textContent = me.viewer.id.slice(0, 1).toUpperCase();
    } catch (failure) {
        $("viewer").textContent = failure.message;
    }
}

async function loadInbox() {
    try {
        const items = await request("");
        $("items").replaceChildren(...items.map(row));
        $("inbox-state").textContent = items.length ? ""
            : "Nothing waits for you. Requests appear here when an agent asks to do something that needs a person.";
        $("count-inbox").textContent = items.length ? String(items.length) : "";
        if (current && !items.some((item) => item.approvalId === current)) {
            closeSheet();
        }
    } catch (failure) {
        $("inbox-state").textContent = failure.message;
    }
}

async function loadHistory() {
    const status = $("history-status").value;
    const query = `/history?days=${HISTORY_DAYS}&limit=200` + (status ? `&status=${encodeURIComponent(status)}` : "");
    try {
        const items = await request(query);
        $("history-items").replaceChildren(...items.map(row));
        $("history-state").textContent = items.length ? "" : "Nothing was decided in this period.";
    } catch (failure) {
        $("history-state").textContent = failure.message;
    }
}

async function loadOverview() {
    try {
        const [waiting, decided] = await Promise.all([request(""), request(`/history?days=${HISTORY_DAYS}&limit=500`)]);
        const counts = new Map();
        for (const item of decided) {
            counts.set(item.status, (counts.get(item.status) || 0) + 1);
        }
        const first = waiting.reduce((soonest, item) => (!soonest || item.expiresAt < soonest.expiresAt ? item : soonest), null);
        const stats = [
            ["Waiting now", waiting.length],
            ["Waiting for you", waiting.filter((item) => item.mayDecide).length],
            ["Next to expire", first ? untilExpiry(first.expiresAt).text : "none"],
            ["Ran", counts.get("EXECUTED") || 0],
            ["Failed when run", counts.get("FAILED") || 0, true],
            ["Outcome unknown", counts.get("UNKNOWN") || 0, true],
            ["Rejected", counts.get("REJECTED") || 0],
            ["Withdrawn", counts.get("CANCELLED") || 0],
            ["Expired undecided", counts.get("EXPIRED") || 0],
            ["Refused on re-check", counts.get("REFUSED") || 0],
        ];
        $("numbers").replaceChildren(...stats.map(([label, value, alarming]) => {
            const stat = element("div", null, alarming && value > 0 ? "stat alert" : "stat");
            stat.append(element("dt", label), element("dd", value));
            return stat;
        }));
    } catch (failure) {
        $("numbers").replaceChildren(element("dd", failure.message));
    }
}

function switchTo(next) {
    view = next;
    for (const name of ["inbox", "history", "overview"]) {
        $(`view-${name}`).hidden = name !== next;
        $(`tab-${name}`).setAttribute("aria-selected", String(name === next));
    }
    closeSheet();
    $("placeholder").hidden = next === "overview";
    refreshView();
}

function refreshView() {
    if (!$("graph-view").hidden) {
        return undefined;
    }
    if (view === "history") {
        return loadHistory();
    }
    if (view === "overview") {
        return loadOverview();
    }
    return loadInbox();
}

// The queue

function row(item) {
    const entry = element("li");
    const button = element("button", null, "item");
    button.type = "button";
    button.dataset.approvalId = item.approvalId;
    button.setAttribute("aria-current", item.approvalId === current ? "true" : "false");

    const expiry = untilExpiry(item.expiresAt);
    const when = item.status === "PENDING" ? element("span", expiry.text, expiry.soon ? "when soon" : "when")
        : element("span", STATUS[item.status]?.[0] || item.status, "when");
    button.append(element("span", item.title, "title"), when);
    const line = summary(item);
    if (line) {
        button.append(element("span", line, "summary"));
    }
    button.append(element("span", `For ${party(item.requester)}`, "who"));

    const marks = element("span", null, "marks");
    if (item.provenance.length) {
        marks.append(element("span", "Outside content", "mark caution"));
    }
    if (item.arguments.some((argument) => argument.warnings.length)) {
        marks.append(element("span", "Suspicious characters", "mark caution"));
    }
    if (item.status === "UNKNOWN" && item.mayDecide) {
        marks.append(element("span", "Needs resolving", "mark yours"));
    } else if (item.status === "PENDING" && !item.mayDecide) {
        marks.append(element("span", item.access === "REQUESTER" ? "Your request" : "View only", "mark"));
    }
    button.append(marks);
    button.addEventListener("click", () => open(item.approvalId));
    entry.append(button);
    return entry;
}

// The decision sheet

async function open(approvalId, keepMessage = false) {
    current = approvalId;
    if (!keepMessage) {
        say("");
    }
    try {
        show(await request(`/item?id=${encodeURIComponent(approvalId)}`));
    } catch (failure) {
        say(failure.message, "warn");
    }
    for (const button of document.querySelectorAll("button.item")) {
        button.setAttribute("aria-current", button.dataset.approvalId === approvalId ? "true" : "false");
    }
}

function closeSheet() {
    current = null;
    $("detail").hidden = true;
    $("placeholder").hidden = view === "overview";
}

function fact(label, value, className) {
    const wrapper = element("div");
    wrapper.append(element("dt", label), element("dd", value, className));
    return wrapper;
}

function show(item) {
    const detail = $("detail");
    detail.hidden = false;
    $("placeholder").hidden = true;
    detail.dataset.approvalId = item.approvalId;
    detail.dataset.digest = item.digest;

    const [stateText, stateClass] = STATUS[item.status] || [item.status, ""];
    const state = $("detail-status");
    state.textContent = stateText;
    state.className = `state ${stateClass}`;
    $("detail-title").textContent = item.title;

    const facts = [fact("For", party(item.requester)), fact("Agent", item.agent), fact("Tool", item.tool)];
    if (item.status === "PENDING") {
        const expiry = untilExpiry(item.expiresAt);
        const expires = fact("Expires", expiry.text, expiry.soon ? "soon" : null);
        expires.querySelector("dd").title = absolute(item.expiresAt);
        facts.push(expires);
    }
    facts.push(fact("You are", ACCESS[item.access] || item.access));
    $("detail-meta").replaceChildren(...facts);
    $("why-title").textContent = item.status === "PENDING" ? "Why it waits" : "Why it waited";

    setReadable($("detail-reason"), item.deferralReason);
    $("detail-rule").textContent = item.deferralRule;

    $("provenance").replaceChildren(...item.provenance.map((source) =>
        element("li", `${source.label === "UNTRUSTED_CONTENT" ? "Untrusted content" : "Private data"} from ${source.tool}`)));
    $("provenance-block").hidden = item.provenance.length === 0;

    const ordered = [...item.arguments].sort((a, b) => VISIBILITY_ORDER[a.visibility] - VISIBILITY_ORDER[b.visibility]);
    $("arguments").replaceChildren(...ordered.map(argumentRow));

    $("history").replaceChildren(...item.decisions.map((decision) => {
        const entry = element("li", `${DECIDED[decision.kind] || decision.kind} by ${decision.by}, `
            + `${ACCESS[decision.access] || decision.access}, ${absolute(decision.at)}`);
        if (decision.comment) {
            const note = element("span", null, "note");
            setReadable(note, decision.comment);
            entry.append(note);
        }
        return entry;
    }));
    $("history-block").hidden = item.decisions.length === 0;

    showDecision(item);
}

function showDecision(item) {
    const pending = item.status === "PENDING";
    const unknown = item.status === "UNKNOWN";
    const mayWithdraw = pending && (item.access === "REQUESTER" || item.access === "SUPER_ADMIN");
    $("decide").hidden = !((pending && item.mayDecide) || mayWithdraw || (unknown && item.mayDecide));
    $("approve").hidden = !(pending && item.mayDecide);
    $("reject").hidden = !(pending && item.mayDecide);
    $("cancel").hidden = !mayWithdraw;
    $("resolve-ran").hidden = !(unknown && item.mayDecide);
    $("resolve-not").hidden = !(unknown && item.mayDecide);
    $("comment-hint").textContent = unknown ? "required: what you found in the real system"
        : item.access === "SUPER_ADMIN" ? "required: super admin decisions are audited"
            : "required to reject or withdraw";
    $("decide-hint").textContent = unknown
        ? "Nobody knows whether this ran. Check the real system, then record what happened."
        : item.access === "AUDITOR" ? "Auditors can see requests but not decide them." : "";
    $("comment").value = "";
}

// What would run, with suspicious characters marked where they are

function describe(character) {
    const code = `U+${character.codePointAt(0).toString(16).toUpperCase().padStart(4, "0")}`;
    if (/\p{Cf}/u.test(character)) {
        return `an invisible or text-direction character (${code})`;
    }
    if (/\p{Cc}/u.test(character)) {
        return `a control character (${code})`;
    }
    const script = SCRIPTS.find((name) => new RegExp(`\\p{Script=${name}}`, "u").test(character));
    return script ? `“${character}” is a ${script} letter (${code})` : `“${character}” is not ASCII (${code})`;
}

function isSuspect(character) {
    const point = character.codePointAt(0);
    return /\p{Cf}/u.test(character) || (/\p{Cc}/u.test(character) && character !== "\n" && character !== "\t")
        || (point > 0x7f && /[\p{L}\p{N}]/u.test(character));
}

function markedValue(text) {
    const value = element("span", null, "value");
    const notes = new Set();
    let plain = "";
    for (const character of text) {
        if (!isSuspect(character)) {
            plain += character;
            continue;
        }
        if (plain) {
            value.append(document.createTextNode(plain));
            plain = "";
        }
        const note = describe(character);
        notes.add(note);
        const visible = /\p{Cf}|\p{Cc}/u.test(character) ? `⟨${note.match(/U\+[0-9A-F]+/)[0]}⟩` : character;
        const mark = element("mark", visible, "suspect");
        mark.title = note;
        value.append(mark);
    }
    if (plain) {
        value.append(document.createTextNode(plain));
    }
    return {value, notes: [...notes]};
}

function argumentRow(argument) {
    const tableRow = element("tr");
    tableRow.append(element("th", argument.name));
    const cell = element("td");
    if (argument.visibility === "HIDDEN") {
        cell.append(element("span", "Not shown by policy", "withheld"));
    } else if (argument.visibility === "MASKED") {
        cell.append(element("span", argument.display, "value masked"));
    } else {
        const {value, notes} = markedValue(argument.display);
        cell.append(value);
        const list = element("ul", null, "notes");
        for (const note of notes.length ? notes : argument.warnings) {
            list.append(element("li", note));
        }
        if (list.children.length) {
            cell.append(list);
        }
    }
    tableRow.append(cell);
    return tableRow;
}

// The graph of a request's session

let graph = null;
const SIDE_VIEWS = ["view-inbox", "view-history", "view-overview", "detail", "placeholder"];
let hiddenBeforeGraph = [];

async function openGraph() {
    const approvalId = $("detail").dataset.approvalId;
    try {
        const journey = await request(`/journey?id=${encodeURIComponent(approvalId)}`);
        hiddenBeforeGraph = SIDE_VIEWS.filter((id) => $(id).hidden);
        for (const id of SIDE_VIEWS) {
            $(id).hidden = true;
        }
        $("graph-view").hidden = false;
        $("mermaid-box").hidden = true;
        $("graph-title").textContent = `How “${$("detail-title").textContent}” came about`;
        $("graph-note").textContent = journey.complete ? ""
            : "The session's earlier steps are not available, so the graph starts at this request.";
        graph = renderGraph($("graph-canvas"), $("graph-inspector"), journey);
        $("graph-canvas").focus();
    } catch (failure) {
        say(failure.message, "warn");
    }
}

function closeGraph() {
    $("graph-view").hidden = true;
    for (const id of SIDE_VIEWS) {
        $(id).hidden = hiddenBeforeGraph.includes(id);
    }
    graph = null;
    $("open-graph").focus();
}

async function copyMermaid() {
    if (!graph) {
        return;
    }
    const text = graph.mermaid();
    $("mermaid-text").value = text;
    $("mermaid-box").hidden = false;
    try {
        await navigator.clipboard.writeText(text);
        $("graph-note").textContent = "Copied as Mermaid. Paste it into a report or a ticket.";
    } catch {
        $("graph-note").textContent = "Select the Mermaid text below and copy it.";
        $("mermaid-text").select();
    }
}

// Deciding

async function decide(kind, ran) {
    const detail = $("detail");
    const comment = $("comment").value.trim();
    if (kind !== "approve" && !comment) {
        say("Add a note saying why first.", "warn");
        $("comment").focus();
        return;
    }
    const payload = {approvalId: detail.dataset.approvalId, kind, comment: comment || null};
    if (kind === "approve") {
        payload.digest = detail.dataset.digest;
    }
    if (kind === "resolve") {
        payload.ran = ran;
    }
    setBusy(true);
    try {
        const outcome = await request("/decisions", {method: "POST", body: JSON.stringify(payload)});
        say(outcomeText(kind, outcome), ["released", "done"].includes(outcome.outcome) ? "good" : "warn");
        await refreshView();
        if (current) {
            // Show the request as it is now, keeping the outcome just reported.
            await open(current, true);
        }
    } catch (failure) {
        say(failure.message, "warn");
    } finally {
        setBusy(false);
    }
}

function outcomeText(kind, outcome) {
    const reason = outcome.reason ? ` ${outcome.reason}` : "";
    switch (outcome.outcome) {
        case "released": return "Approved. The action is running; its outcome will appear in the decisions.";
        case "awaiting-confirmation": return `Your approval is recorded.${reason}`;
        case "refused": return `Approved, but ToolGate refused to run it:${reason}`;
        case "not-permitted": return `Not done:${reason}`;
        case "already-decided": return `Someone already decided this request (${STATUS[outcome.status]?.[0] || outcome.status}).`;
        case "expired": return "This request expired before it was decided.";
        case "done": return kind === "reject" ? "Rejected." : kind === "cancel" ? "Withdrawn."
            : kind === "resolve" ? "Recorded. The agent may use this tool again in that session." : "Done.";
        default: return "Nothing changed: the request was already decided, or you may not do that.";
    }
}

function setBusy(busy) {
    for (const id of ["approve", "reject", "cancel", "resolve-ran", "resolve-not"]) {
        $(id).disabled = busy;
    }
}

$("refresh").addEventListener("click", loadInbox);
$("open-graph").addEventListener("click", openGraph);
$("graph-back").addEventListener("click", closeGraph);
$("graph-fit").addEventListener("click", () => graph?.fit());
$("graph-zoom-in").addEventListener("click", () => graph?.zoomIn());
$("graph-zoom-out").addEventListener("click", () => graph?.zoomOut());
$("graph-copy").addEventListener("click", copyMermaid);
document.addEventListener("keydown", (event) => {
    if (event.key === "Escape" && !$("graph-view").hidden) {
        closeGraph();
    }
});
$("tab-inbox").addEventListener("click", () => switchTo("inbox"));
$("tab-history").addEventListener("click", () => switchTo("history"));
$("tab-overview").addEventListener("click", () => switchTo("overview"));
$("history-status").addEventListener("change", loadHistory);
$("approve").addEventListener("click", () => decide("approve"));
$("reject").addEventListener("click", () => decide("reject"));
$("cancel").addEventListener("click", () => decide("cancel"));
$("resolve-ran").addEventListener("click", () => decide("resolve", true));
$("resolve-not").addEventListener("click", () => decide("resolve", false));

loadViewer();
loadInbox();
setInterval(refreshView, REFRESH_MS);
