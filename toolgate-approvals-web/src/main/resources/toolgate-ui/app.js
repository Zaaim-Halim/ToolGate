// ToolGate approvals console. Everything shown comes from the server and, through the model, possibly
// from an attacker: it is rendered with textContent only, never as HTML. No inline script or style.

const API = "/toolgate/api/approvals";
const REFRESH_MS = 30_000;
const HISTORY_DAYS = 7;
const STATUS_LABEL = {
    PENDING: "waiting", RELEASED: "approved", EXECUTING: "running", EXECUTED: "ran", FAILED: "failed",
    UNKNOWN: "outcome unknown", REFUSED: "refused on re-check", REJECTED: "rejected", EXPIRED: "expired",
    CANCELLED: "withdrawn",
};
let view = "inbox";

const $ = (id) => document.getElementById(id);
let current = null;

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

function when(iso) {
    const date = new Date(iso);
    return Number.isNaN(date.getTime()) ? iso : date.toLocaleString();
}

function party(p) {
    return p.tenant ? `${p.id} (${p.tenant})` : p.id;
}

async function loadViewer() {
    try {
        const me = await request("/me");
        $("application").textContent = me.application;
        const roles = me.viewer.roles.length ? ` · ${me.viewer.roles.join(", ")}` : "";
        $("viewer").textContent = `${party(me.viewer)}${roles}`;
    } catch (failure) {
        $("viewer").textContent = failure.message;
    }
}

async function loadInbox() {
    const list = $("items");
    try {
        const items = await request("");
        list.replaceChildren();
        $("inbox-state").textContent = items.length ? "" : "Nothing waits for you.";
        for (const item of items) {
            list.append(row(item));
        }
        if (current && !items.some((item) => item.approvalId === current)) {
            current = null;
            $("detail").hidden = true;
        }
    } catch (failure) {
        $("inbox-state").textContent = failure.message;
    }
}

async function loadHistory() {
    const list = $("history-items");
    const status = $("history-status").value;
    const query = `/history?days=${HISTORY_DAYS}&limit=200` + (status ? `&status=${encodeURIComponent(status)}` : "");
    try {
        const items = await request(query);
        list.replaceChildren(...items.map(row));
        $("history-state").textContent = items.length ? "" : "Nothing decided in this period.";
    } catch (failure) {
        $("history-state").textContent = failure.message;
    }
}

async function loadOverview() {
    const numbers = $("numbers");
    try {
        const [waiting, decided] = await Promise.all([request(""), request(`/history?days=${HISTORY_DAYS}&limit=500`)]);
        const counts = new Map();
        for (const item of decided) {
            counts.set(item.status, (counts.get(item.status) || 0) + 1);
        }
        const oldest = waiting.reduce((first, item) => (!first || item.expiresAt < first.expiresAt ? item : first), null);
        const entries = [
            ["Waiting now", waiting.length],
            ["You can decide", waiting.filter((item) => item.mayDecide).length],
            ["Expiring first", oldest ? when(oldest.expiresAt) : "—"],
            ...["EXECUTED", "FAILED", "UNKNOWN", "REJECTED", "EXPIRED", "CANCELLED", "REFUSED"]
                .map((status) => [`${STATUS_LABEL[status]} (7 days)`, counts.get(status) || 0]),
        ];
        numbers.replaceChildren(...entries.map(([label, value]) => {
            const stat = element("div", null, "stat");
            stat.append(element("dt", label), element("dd", value));
            return stat;
        }));
    } catch (failure) {
        numbers.replaceChildren(element("dd", failure.message));
    }
}

function switchTo(next) {
    view = next;
    for (const name of ["inbox", "history", "overview"]) {
        $(`view-${name}`).hidden = name !== next;
        $(`tab-${name}`).setAttribute("aria-selected", String(name === next));
    }
    $("detail").hidden = true;
    current = null;
    refreshView();
}

function refreshView() {
    if (view === "history") {
        return loadHistory();
    }
    if (view === "overview") {
        return loadOverview();
    }
    return loadInbox();
}

function row(item) {
    const entry = element("li");
    const button = element("button", null, "item");
    button.type = "button";
    button.dataset.approvalId = item.approvalId;
    button.setAttribute("aria-current", item.approvalId === current ? "true" : "false");
    button.append(element("span", item.title, "title"),
        element("span", `${item.tool} · for ${party(item.requester)}`, "sub"),
        element("span", `expires ${when(item.expiresAt)}`, "sub"));
    const flags = element("span", null, "flags");
    if (item.status !== "PENDING") {
        flags.append(element("span", STATUS_LABEL[item.status] || item.status,
            item.status === "UNKNOWN" || item.status === "FAILED" ? "badge warn" : "badge"));
    }
    flags.append(element("span", item.mayDecide ? (item.status === "UNKNOWN" ? "you can resolve" : "you can decide")
        : item.access.toLowerCase().replace("_", " "), item.mayDecide ? "badge ok" : "badge"));
    if (item.provenance.length) {
        flags.append(element("span", "untrusted input", "badge warn"));
    }
    if (item.arguments.some((argument) => argument.warnings.length)) {
        flags.append(element("span", "check values", "badge warn"));
    }
    button.append(flags);
    button.addEventListener("click", () => open(item.approvalId));
    entry.append(button);
    return entry;
}

async function open(approvalId, keepMessage = false) {
    current = approvalId;
    if (!keepMessage) {
        $("message").textContent = "";
    }
    try {
        show(await request(`/item?id=${encodeURIComponent(approvalId)}`));
    } catch (failure) {
        $("message").textContent = failure.message;
    }
    for (const button of document.querySelectorAll("#items button.item")) {
        button.setAttribute("aria-current", button.dataset.approvalId === approvalId ? "true" : "false");
    }
}

function show(item) {
    const detail = $("detail");
    detail.hidden = false;
    detail.dataset.approvalId = item.approvalId;
    detail.dataset.digest = item.digest;
    $("detail-title").textContent = item.title;
    $("detail-status").textContent = STATUS_LABEL[item.status] || item.status;
    $("detail-meta").textContent = `${item.tool} · agent ${item.agent} · for ${party(item.requester)} · expires ${when(item.expiresAt)}`;
    $("detail-rule").textContent = item.deferralRule;
    $("detail-reason").textContent = item.deferralReason;

    const sources = $("provenance");
    sources.replaceChildren(...item.provenance.map((source) =>
        element("li", `${source.label.replaceAll("_", " ").toLowerCase()} from ${source.tool}`)));
    $("provenance-block").hidden = item.provenance.length === 0;

    const rows = $("arguments");
    rows.replaceChildren(...item.arguments.map(argumentRow));

    const history = $("history");
    history.replaceChildren(...item.decisions.map((decision) => element("li",
        `${when(decision.at)}: ${decision.kind.toLowerCase()} by ${decision.by} (${decision.access.toLowerCase().replace("_", " ")})`
        + (decision.comment ? ` — ${decision.comment}` : ""))));
    $("history-block").hidden = item.decisions.length === 0;

    const pending = item.status === "PENDING";
    const unknown = item.status === "UNKNOWN";
    const mayWithdraw = pending && (item.access === "REQUESTER" || item.access === "SUPER_ADMIN");
    $("decide").hidden = !((pending && item.mayDecide) || mayWithdraw || (unknown && item.mayDecide));
    $("approve").hidden = !(pending && item.mayDecide);
    $("reject").hidden = !(pending && item.mayDecide);
    $("cancel").hidden = !mayWithdraw;
    $("resolve-ran").hidden = !(unknown && item.mayDecide);
    $("resolve-not").hidden = !(unknown && item.mayDecide);
    $("comment-hint").textContent = unknown
        ? "(required: say what you found in the real system)"
        : item.access === "SUPER_ADMIN"
            ? "(required: super admin decisions are audited and need a justification)"
            : "(required to reject or withdraw)";
    $("decide-hint").textContent = unknown && item.mayDecide
        ? "Nobody knows whether this ran. Check the real system, then record what happened."
        : item.mayDecide ? "" : item.access === "AUDITOR" ? "Auditors can see calls but not decide them." : "";
    $("comment").value = "";
}

function argumentRow(argument) {
    const row = element("tr");
    row.append(element("th", argument.name));
    const cell = element("td");
    if (argument.visibility === "HIDDEN") {
        cell.append(element("span", "hidden by policy", "muted"));
    } else {
        cell.append(element("code", argument.display, argument.visibility === "MASKED" ? "masked" : "value"));
    }
    for (const warning of argument.warnings) {
        cell.append(element("span", `⚠ ${warning}`, "warning"));
    }
    row.append(cell);
    return row;
}

async function decide(kind, ran) {
    const detail = $("detail");
    const comment = $("comment").value.trim();
    if (kind !== "approve" && !comment) {
        $("message").textContent = "Say why in the comment first.";
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
        $("message").textContent = describe(kind, outcome);
        await refreshView();
        if (current) {
            // Show the call as it is now, keeping the outcome just reported.
            await open(current, true);
        }
    } catch (failure) {
        $("message").textContent = failure.message;
    } finally {
        setBusy(false);
    }
}

function describe(kind, outcome) {
    const reason = outcome.reason ? ` ${outcome.reason}` : "";
    switch (outcome.outcome) {
        case "released": return "Approved. The action is running; its outcome will show in the history.";
        case "awaiting-confirmation": return `Your approval is recorded.${reason}`;
        case "refused": return `Approved, but ToolGate refused to run it:${reason}`;
        case "not-permitted": return `Not done:${reason}`;
        case "already-decided": return `Someone already decided this call (${outcome.status}).`;
        case "expired": return "This call expired before it was decided.";
        case "done": return kind === "reject" ? "Rejected." : kind === "cancel" ? "Withdrawn."
            : kind === "resolve" ? "Recorded. The agent may use this tool again in that session." : "Done.";
        default: return "Nothing changed: the call was already decided, or you may not do that.";
    }
}

function setBusy(busy) {
    for (const id of ["approve", "reject", "cancel", "resolve-ran", "resolve-not"]) {
        $(id).disabled = busy;
    }
}

$("refresh").addEventListener("click", loadInbox);
$("tab-inbox").addEventListener("click", () => switchTo("inbox"));
$("tab-history").addEventListener("click", () => switchTo("history"));
$("tab-overview").addEventListener("click", () => switchTo("overview"));
$("history-status").addEventListener("change", loadHistory);
$("resolve-ran").addEventListener("click", () => decide("resolve", true));
$("resolve-not").addEventListener("click", () => decide("resolve", false));
$("approve").addEventListener("click", () => decide("approve"));
$("reject").addEventListener("click", () => decide("reject"));
$("cancel").addEventListener("click", () => decide("cancel"));

loadViewer();
loadInbox();
setInterval(refreshView, REFRESH_MS);
