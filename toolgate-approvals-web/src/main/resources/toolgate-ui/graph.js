// The graph of one request's session: every step as a node, in lanes for who acted, with the request in
// focus. Built with SVG DOM nodes and textContent only; nothing from the server is parsed as markup.

const SVG = "http://www.w3.org/2000/svg";
const COLUMN = 206;
const LANE = 118;
const TOP = 46;
const LEFT = 200;
const NODE_W = 172;
const NODE_H = 56;
const LANES = ["Agent", "ToolGate", "People", "Outcome"];
const OUTCOME_CLASS = {
    allow: "allow", deny: "deny", terminate: "deny", downgrade: "downgrade", defer: "defer",
    approve: "allow", reject: "deny", cancel: "neutral", resolve: "neutral", declassified: "neutral",
    executed: "allow", failed: "deny", unknown: "defer", released: "neutral", executing: "neutral",
    refused: "deny", expired: "neutral",
};
const OUTCOME_TEXT = {
    allow: "Allowed", deny: "Denied", terminate: "Ended the run", downgrade: "Safer tool instead", defer: "Held",
    approve: "Approved", reject: "Rejected", cancel: "Withdrew", resolve: "Resolved", declassified: "Declassified",
};

function svg(tag, attributes = {}, text) {
    const node = document.createElementNS(SVG, tag);
    for (const [name, value] of Object.entries(attributes)) {
        node.setAttribute(name, String(value));
    }
    if (text !== undefined) {
        node.textContent = text;
    }
    return node;
}

function shorten(text, max) {
    return text.length <= max ? text : `${text.slice(0, max - 1)}…`;
}

/** A tool's name as people say it: the local prefix dropped, an MCP server kept. */
function toolName(id) {
    return id.startsWith("local/") ? id.slice("local/".length) : id.replace(/^mcp:/, "");
}

const UUID = /\b([0-9a-f]{8})-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b/gi;

function readable(text) {
    return text ? text.replace(UUID, "$1…") : text;
}

function outcomeKey(outcome) {
    return outcome.split(",")[0].trim();
}

/** Places every step: calls take a column with their decision under them; people and the run follow. */
function layout(journey) {
    const nodes = [];
    let column = 0;
    for (const step of journey.steps) {
        const key = outcomeKey(step.outcome);
        if (step.kind === "CALL") {
            nodes.push({id: `${step.id}#call`, step, lane: 0, column, label: toolName(step.title), sub: "asked to run", key: "call"});
            nodes.push({id: `${step.id}#gate`, step, lane: 1, column, label: OUTCOME_TEXT[key] || key,
                sub: step.rule || "", key});
        } else if (step.kind === "DECLASSIFIED") {
            nodes.push({id: step.id, step, lane: 1, column, label: step.title, sub: "label removed", key});
        } else if (step.kind === "PERSON") {
            nodes.push({id: step.id, step, lane: 2, column, label: step.title, sub: OUTCOME_TEXT[key] || key, key});
        } else {
            nodes.push({id: step.id, step, lane: 3, column, label: step.title, sub: step.detail || "", key});
        }
        column++;
    }
    for (const node of nodes) {
        node.x = LEFT + node.column * COLUMN;
        node.y = TOP + node.lane * LANE;
    }
    return nodes;
}

function edges(journey, nodes) {
    const byId = new Map(nodes.map((node) => [node.id, node]));
    const list = [];
    const calls = journey.steps.filter((step) => step.kind === "CALL");
    for (let i = 0; i < calls.length; i++) {
        list.push({from: byId.get(`${calls[i].id}#call`), to: byId.get(`${calls[i].id}#gate`), kind: "decide"});
        if (i + 1 < calls.length) {
            list.push({from: byId.get(`${calls[i].id}#call`), to: byId.get(`${calls[i + 1].id}#call`), kind: "next"});
        }
    }
    const subject = journey.steps.find((step) => step.subject);
    let previous = subject ? byId.get(`${subject.id}#gate`) : null;
    for (const step of journey.steps.filter((s) => s.kind === "PERSON" || s.kind === "RUN")) {
        const node = byId.get(step.id);
        if (previous && node) {
            list.push({from: previous, to: node, kind: "after"});
        }
        previous = node;
    }
    for (const flow of journey.flows) {
        const from = byId.get(`${flow.from}#call`);
        const to = byId.get(`${flow.to}#gate`);
        if (from && to) {
            list.push({from, to, kind: "flow", label: flow.label});
        }
    }
    return list.filter((edge) => edge.from && edge.to);
}

function path(edge) {
    const a = edge.from;
    const b = edge.to;
    if (edge.kind === "decide") {
        return `M${a.x} ${a.y + NODE_H / 2} L${b.x} ${b.y - NODE_H / 2}`;
    }
    if (edge.kind === "next") {
        return `M${a.x + NODE_W / 2} ${a.y} L${b.x - NODE_W / 2} ${b.y}`;
    }
    if (edge.kind === "flow") {
        // Arcs above the agent lane, so taint reads as something carried along, apart from the order of steps.
        const lift = 34 + Math.min(80, Math.abs(b.x - a.x) / 6);
        return `M${a.x} ${a.y - NODE_H / 2} C${a.x} ${a.y - NODE_H / 2 - lift}, ${b.x + 24} ${a.y - NODE_H / 2 - lift}, `
            + `${b.x + 24} ${a.y - NODE_H / 2} L${b.x + 24} ${b.y - NODE_H / 2}`;
    }
    const midX = (a.x + b.x) / 2;
    return `M${a.x + NODE_W / 2} ${a.y} C${midX} ${a.y}, ${midX} ${b.y}, ${b.x - NODE_W / 2} ${b.y}`;
}

/** Everything that led to a node: earlier steps on its path, and the calls whose taint reached it. */
function ancestry(edgeList, focus) {
    const related = new Set([focus.id]);
    const relatedEdges = new Set();
    let grew = true;
    while (grew) {
        grew = false;
        for (const edge of edgeList) {
            if (related.has(edge.to.id) && !relatedEdges.has(edge)) {
                relatedEdges.add(edge);
                if (!related.has(edge.from.id)) {
                    related.add(edge.from.id);
                    grew = true;
                }
            }
        }
    }
    // A call that fed the focus keeps its own decision in view: it is part of the story.
    for (const id of [...related]) {
        if (id.endsWith("#call")) {
            related.add(id.replace(/#call$/, "#gate"));
        }
    }
    // What happened to the focus afterwards stays in view too.
    for (const edge of edgeList) {
        if (edge.from.id === focus.id && edge.kind !== "next") {
            related.add(edge.to.id);
            relatedEdges.add(edge);
        }
    }
    return {related, relatedEdges};
}

export function renderGraph(container, inspector, journey, onFocusChange) {
    const nodes = layout(journey);
    const edgeList = edges(journey, nodes);
    const width = LEFT + Math.max(1, nodes.reduce((max, node) => Math.max(max, node.column), 0) + 1) * COLUMN + 40;
    // Lanes down to the lowest one in use: a request nobody decided yet has no People or Outcome lane.
    const lanesInUse = Math.max(2, Math.max(...nodes.map((node) => node.lane)) + 1);
    const height = TOP + lanesInUse * LANE;
    const root = svg("svg", {class: "graph", role: "group", "aria-roledescription": "graph"});
    const defs = svg("defs");
    for (const [id, cls] of [["arrow", "arrow"], ["arrow-flow", "arrow flow"]]) {
        const marker = svg("marker", {id, viewBox: "0 0 10 10", refX: 9, refY: 5, markerWidth: 7, markerHeight: 7,
            orient: "auto-start-reverse"});
        marker.append(svg("path", {d: "M0 0 L10 5 L0 10 z", class: cls}));
        defs.append(marker);
    }
    root.append(defs);
    const world = svg("g", {class: "world"});
    root.append(world);

    // Lane bands move with the graph; their names stay pinned to the left edge, over the bands.
    const laneNames = svg("g", {class: "lane-names"});
    const laneLabels = [];
    LANES.slice(0, lanesInUse).forEach((name, index) => {
        const y = TOP + index * LANE;
        world.append(svg("rect", {class: `lane ${index % 2 ? "odd" : "even"}`, x: -4000, y: y - LANE / 2,
            width: width + 8000, height: LANE}));
        const label = svg("g", {class: "lane-label"});
        label.append(svg("rect", {class: "lane-pill", x: 0, y: -12, width: 76, height: 24, rx: 12}));
        label.append(svg("text", {class: "lane-name", x: 38, y: 4}, name));
        laneNames.append(label);
        laneLabels.push({label, y});
    });

    const edgeLayer = svg("g", {class: "edges"});
    const edgeElements = new Map();
    for (const edge of edgeList) {
        const line = svg("path", {d: path(edge), class: `edge ${edge.kind}`,
            "marker-end": edge.kind === "flow" ? "url(#arrow-flow)" : "url(#arrow)"});
        if (edge.label) {
            line.append(svg("title", {}, `${edge.label === "UNTRUSTED_CONTENT" ? "Untrusted content" : "Private data"} `
                + `from ${edge.from.step.title}`));
        }
        edgeLayer.append(line);
        edgeElements.set(edge, line);
    }
    world.append(edgeLayer);

    const nodeElements = new Map();
    const nodeLayer = svg("g", {class: "nodes"});
    for (const node of nodes) {
        const group = svg("g", {class: `node ${OUTCOME_CLASS[node.key] || node.key} lane-${node.lane}`,
            transform: `translate(${node.x - NODE_W / 2} ${node.y - NODE_H / 2})`, tabindex: -1, role: "button",
            "aria-label": `${LANES[node.lane]}: ${node.label}${node.sub ? `, ${node.sub}` : ""}`});
        if (node.step.subject) {
            group.classList.add("subject");
            group.append(svg("rect", {class: "halo", x: -8, y: -8, width: NODE_W + 16, height: NODE_H + 16, rx: 14}));
        }
        group.append(svg("rect", {class: "box", width: NODE_W, height: NODE_H, rx: node.lane === 1 ? NODE_H / 2 : 8}));
        group.append(svg("text", {class: "label", x: 16, y: 24}, shorten(node.label, 19)));
        group.append(svg("text", {class: "sub", x: 16, y: 42}, shorten(node.sub, 23)));
        if (node.step.labelsAdded.length && node.lane === 0) {
            group.append(svg("circle", {class: "taint-dot", cx: NODE_W - 12, cy: 12, r: 5}));
        }
        if (node.step.subject && node.lane === 1) {
            group.append(svg("text", {class: "subject-tag", x: NODE_W / 2, y: NODE_H + 24}, "This request"));
        }
        group.append(svg("title", {}, `${node.label}${node.sub ? ` — ${node.sub}` : ""}`));
        group.addEventListener("click", () => focus(node));
        group.addEventListener("keydown", (event) => {
            if (event.key === "Enter" || event.key === " ") {
                event.preventDefault();
                focus(node);
            }
        });
        nodeLayer.append(group);
        nodeElements.set(node, group);
    }
    world.append(nodeLayer);
    root.append(laneNames);
    container.replaceChildren(root);

    // Pan and zoom, on the world group: drag the background, wheel or pinch to zoom around the pointer.
    let scale = 1;
    let tx = 0;
    let ty = 0;
    const apply = () => {
        world.setAttribute("transform", `translate(${tx} ${ty}) scale(${scale})`);
        for (const {label, y} of laneLabels) {
            label.setAttribute("transform", `translate(12 ${ty + y * scale})`);
        }
    };
    const zoomAt = (factor, cx, cy) => {
        const next = Math.min(2.5, Math.max(0.25, scale * factor));
        tx = cx - (cx - tx) * (next / scale);
        ty = cy - (cy - ty) * (next / scale);
        scale = next;
        apply();
    };
    const center = (node, across = 0.5) => {
        const box = container.getBoundingClientRect();
        tx = box.width * across - node.x * scale;
        ty = box.height / 2 - ((bounds.top + bounds.bottom) / 2) * scale;
        apply();
    };
    // Fit the steps, not the empty lanes: from the lane names to the last node, and from the taint arcs
    // above the agent lane to the lowest lane in use.
    const bounds = {
        left: Math.min(...nodes.map((node) => node.x)) - NODE_W / 2 - 100,
        right: Math.max(...nodes.map((node) => node.x)) + NODE_W / 2 + 32,
        top: Math.min(...nodes.map((node) => node.y)) - NODE_H / 2 - (edgeList.some((edge) => edge.kind === "flow") ? 130 : 30),
        bottom: Math.max(...nodes.map((node) => node.y)) + NODE_H / 2 + 44,
    };
    const fit = () => {
        const box = container.getBoundingClientRect();
        const contentWidth = bounds.right - bounds.left;
        const contentHeight = bounds.bottom - bounds.top;
        scale = Math.min(1.25, Math.max(0.25, Math.min((box.width - 24) / contentWidth, (box.height - 24) / contentHeight)));
        tx = (box.width - contentWidth * scale) / 2 - bounds.left * scale;
        ty = (box.height - contentHeight * scale) / 2 - bounds.top * scale;
        apply();
    };
    root.addEventListener("wheel", (event) => {
        event.preventDefault();
        const box = container.getBoundingClientRect();
        zoomAt(event.deltaY < 0 ? 1.12 : 1 / 1.12, event.clientX - box.left, event.clientY - box.top);
    }, {passive: false});
    let drag = null;
    root.addEventListener("pointerdown", (event) => {
        if (event.target.closest(".node")) {
            return;
        }
        drag = {x: event.clientX - tx, y: event.clientY - ty};
        root.setPointerCapture(event.pointerId);
        root.classList.add("dragging");
    });
    root.addEventListener("pointermove", (event) => {
        if (drag) {
            tx = event.clientX - drag.x;
            ty = event.clientY - drag.y;
            apply();
        }
    });
    root.addEventListener("pointerup", () => {
        drag = null;
        root.classList.remove("dragging");
    });

    // Focus: the chosen step, what led to it and what followed stay bright; the rest of the session dims.
    let focused = null;
    function focus(node, recenter = false) {
        focused = node;
        const {related, relatedEdges} = ancestry(edgeList, node);
        for (const [each, element] of nodeElements) {
            element.classList.toggle("focused", each === node);
            element.classList.toggle("dim", !related.has(each.id));
            element.setAttribute("tabindex", each === node ? "0" : "-1");
        }
        for (const [edge, element] of edgeElements) {
            element.classList.toggle("dim", !relatedEdges.has(edge));
            element.classList.toggle("lit", relatedEdges.has(edge));
        }
        inspect(inspector, node, journey);
        if (recenter) {
            center(node, 0.6);
        }
        onFocusChange?.(node);
    }

    container.addEventListener("keydown", (event) => {
        if (!focused) {
            return;
        }
        const moves = {ArrowRight: [0, 1], ArrowLeft: [0, -1], ArrowDown: [1, 0], ArrowUp: [-1, 0]};
        const move = moves[event.key];
        if (!move) {
            return;
        }
        event.preventDefault();
        const [dLane, dColumn] = move;
        const candidates = nodes.filter((node) => dColumn
            ? Math.sign(node.column - focused.column) === dColumn
            : Math.sign(node.lane - focused.lane) === dLane);
        const next = candidates.sort((a, b) =>
            (Math.abs(a.column - focused.column) * 3 + Math.abs(a.lane - focused.lane))
            - (Math.abs(b.column - focused.column) * 3 + Math.abs(b.lane - focused.lane)))[0];
        if (next) {
            focus(next, true);
            nodeElements.get(next).focus();
        }
    });

    // Open on the whole story when it fits readably; otherwise a little smaller, with the request toward the
    // right so the steps that led to it are in view.
    const subjectNode = nodes.find((node) => node.step.subject && node.lane === 1) || nodes[0];
    fit();
    const readable = scale >= 0.72;
    if (subjectNode) {
        focus(subjectNode, false);
        if (!readable) {
            scale = 0.82;
            center(subjectNode, 0.72);
        }
    }

    return {
        zoomIn: () => { const box = container.getBoundingClientRect(); zoomAt(1.2, box.width / 2, box.height / 2); },
        zoomOut: () => { const box = container.getBoundingClientRect(); zoomAt(1 / 1.2, box.width / 2, box.height / 2); },
        fit,
        mermaid: () => mermaid(journey),
        nodeCount: nodes.length,
    };
}

function inspect(inspector, node, journey) {
    const step = node.step;
    const facts = [
        ["Who", LANES[node.lane]],
        ["What", node.lane === 1 && step.kind === "CALL" ? `Decision on ${step.title}` : step.title],
        ["Result", OUTCOME_TEXT[outcomeKey(step.outcome)] || step.outcome],
        ["When", new Date(step.at).toLocaleString()],
    ];
    if (step.rule) {
        facts.push(["Rule", step.rule]);
    }
    if (step.labelsAdded.length) {
        facts.push([step.kind === "DECLASSIFIED" ? "Removed" : "Brought in",
            step.labelsAdded.map((label) => label === "UNTRUSTED_CONTENT" ? "untrusted content" : "private data").join(", ")]);
    }
    if (step.detail) {
        facts.push([step.kind === "PERSON" ? "Note" : "Detail", readable(step.detail)]);
    }
    const into = journey.flows.filter((flow) => flow.to === step.id).map((flow) => {
        const source = journey.steps.find((candidate) => candidate.id === flow.from);
        return `${flow.label === "UNTRUSTED_CONTENT" ? "Untrusted content" : "Private data"} from ${source ? toolName(source.title) : flow.from}`;
    });
    if (into.length && node.lane === 1) {
        facts.push(["Shaped by", into.join("; ")]);
    }
    const heading = document.createElement("h2");
    heading.textContent = step.subject ? "This request" : node.label;
    const list = document.createElement("dl");
    for (const [term, value] of facts) {
        const wrapper = document.createElement("div");
        const dt = document.createElement("dt");
        dt.textContent = term;
        const dd = document.createElement("dd");
        dd.textContent = value;
        wrapper.append(dt, dd);
        list.append(wrapper);
    }
    inspector.replaceChildren(heading, list);
}

/** The same graph as Mermaid text, for a report; labels are quoted so nothing in them is read as syntax. */
function mermaid(journey) {
    const safe = (text) => text.replace(/["\[\]{}()<>|`#;]/g, " ").replace(/\s+/g, " ").trim();
    const lines = ["flowchart LR"];
    const ids = new Map();
    let count = 0;
    const id = (key) => {
        if (!ids.has(key)) {
            ids.set(key, `n${count++}`);
        }
        return ids.get(key);
    };
    let previousCall = null;
    let previous = null;
    for (const step of journey.steps) {
        const outcome = OUTCOME_TEXT[outcomeKey(step.outcome)] || step.outcome;
        if (step.kind === "CALL") {
            lines.push(`  ${id(`${step.id}#call`)}["${safe(step.title)}"]`);
            lines.push(`  ${id(`${step.id}#gate`)}(["${safe(outcome)}${step.rule ? ` ${safe(step.rule)}` : ""}"])`);
            lines.push(`  ${id(`${step.id}#call`)} --> ${id(`${step.id}#gate`)}`);
            if (previousCall) {
                lines.push(`  ${id(`${previousCall}#call`)} --> ${id(`${step.id}#call`)}`);
            }
            previousCall = step.id;
            if (step.subject) {
                previous = `${step.id}#gate`;
                lines.push(`  style ${id(`${step.id}#gate`)} stroke-width:4px`);
            }
        } else if (step.kind === "PERSON" || step.kind === "RUN") {
            lines.push(`  ${id(step.id)}{{"${safe(step.title)} ${safe(outcome)}"}}`);
            if (previous) {
                lines.push(`  ${id(previous)} --> ${id(step.id)}`);
            }
            previous = step.id;
        }
    }
    for (const flow of journey.flows) {
        if (ids.has(`${flow.from}#call`) && ids.has(`${flow.to}#gate`)) {
            lines.push(`  ${id(`${flow.from}#call`)} -. ${flow.label === "UNTRUSTED_CONTENT" ? "untrusted" : "private"} .-> ${id(`${flow.to}#gate`)}`);
        }
    }
    return lines.join("\n");
}
