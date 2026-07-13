// Forge Web — Phase 2 client.
// Renders the full GameView (Phase 1) AND drives the interaction loop: clicking a card sends
// selectCard, the two engine buttons send selectButtonOk/Cancel, a persistent action bar sends
// pass-until-EOT / undo / concede, mana pips send useMana, and getAbilityToPlay pops a chooser
// whose selection is replied to the blocking game thread. Plain JS, no build step.

(function () {
  "use strict";

  var statusEl = document.getElementById("status");
  var titleEl = document.getElementById("title");
  var turnEl = document.getElementById("turn");
  var promptEl = document.getElementById("prompt");
  var buttonsEl = document.getElementById("buttons");
  var actionbarEl = document.getElementById("actionbar");
  var manabarEl = document.getElementById("manabar");
  var stackWrap = document.getElementById("stackWrap");
  var combatWrap = document.getElementById("combatWrap");
  var playersEl = document.getElementById("players");
  var modalEl = document.getElementById("modal");
  var setupEl = document.getElementById("setup");

  var mySeatIds = [];
  var cardNames = {};        // id -> name, rebuilt each render so combat can name blockers.
  var selectable = {};       // id -> true for cards the engine will accept a click on.
  var commanders = {};       // id -> true for commander cards, marked wherever they are.
  var lastGame = null;       // last GameView, so a selectables update can re-render.

  // MagicColor byte values the engine's useMana(byte) expects.
  var MANA_BYTE = { W: 1, U: 2, B: 4, R: 8, G: 16, C: 0 };

  var proto = location.protocol === "https:" ? "wss:" : "ws:";
  var ws;
  var reconnectDelay = 500;

  // Phones drop the socket whenever Safari backgrounds the tab or the screen locks. Auto-reconnect
  // with backoff; the server rebinds the SAME game session and pushes a full resync on reconnect,
  // so the game continues (a pending question is re-sent) rather than restarting.
  function connect() {
    ws = new WebSocket(proto + "//" + location.host + "/game");
    ws.onopen = function () { setStatus("connected"); reconnectDelay = 500; };
    ws.onclose = function () { setStatus("disconnected — reconnecting…"); scheduleReconnect(); };
    ws.onerror = function () { setStatus("connection error"); };
    ws.onmessage = function (ev) {
      var msg;
      try { msg = JSON.parse(ev.data); } catch (e) { return; }
      handle(msg.type, msg.payload, msg.id);
    };
  }

  function scheduleReconnect() {
    setTimeout(connect, reconnectDelay);
    reconnectDelay = Math.min(reconnectDelay * 2, 5000);
  }

  function setStatus(s) { statusEl.textContent = s; }

  // ── Outbound ───────────────────────────────────────────────────────────────
  function sendAction(type, payload) {
    if (ws.readyState === WebSocket.OPEN) { ws.send(JSON.stringify({ type: type, payload: payload || null })); }
  }
  function reply(id, value) {
    if (ws.readyState === WebSocket.OPEN) { ws.send(JSON.stringify({ replyTo: id, value: value })); }
  }

  renderActionBar();
  connect();

  // ── Inbound ────────────────────────────────────────────────────────────────
  function handle(type, payload, id) {
    switch (type) {
      case "openView": mySeatIds = payload || []; break;
      case "setup": renderSetup(payload); break;
      case "setGameView": hideSetup(); lastGame = payload; renderGame(payload); break;
      case "prompt": promptEl.textContent = payload && payload.message ? payload.message : ""; break;
      case "buttons": renderButtons(payload); break;
      case "message": if (payload && payload.message) { promptEl.textContent = payload.message; } break;
      case "selectables": setSelectables(payload); break;
      case "getAbilityToPlay": showAbilityDialog(payload, id); break;
      case "confirm": showConfirm(payload, id); break;
      case "confirmDialog": showConfirmDialog(payload, id); break;
      case "optionDialog": showOptionDialog(payload, id); break;
      case "inputDialog": showInputDialog(payload, id); break;
      case "getChoices": showMultiSelect(payload, id); break;
      case "chooseEntity": showChooseEntity(payload, id); break;
      case "chooseEntities": showMultiSelect(payload, id); break;
      case "order": showOrder(payload, id); break;
      case "assignDamage": showDistribute(id, payload && payload.damage, payload && payload.blockers, 0,
        payload ? ("Assign " + payload.damage + " damage" + (payload.attackerName ? " from " + payload.attackerName : "")) : "Assign damage"); break;
      case "assignAmount": showDistribute(id, payload && payload.amount, payload && payload.options,
        (payload && payload.atLeastOne) ? 1 : 0, (payload && payload.label) || "Distribute amount"); break;
      case "gameOver": showGameOver(payload); break;
      default:
        console.log("unhandled message:", type, payload, "id=", id);
    }
  }

  function setSelectables(ids) {
    selectable = {};
    (ids || []).forEach(function (id) { selectable[id] = true; });
    if (lastGame) { renderGame(lastGame); } // re-mark cards with the new selectable set
  }

  function renderGame(g) {
    if (!g) { return; }
    titleEl.textContent = g.title || "Forge Web";
    turnEl.textContent = "Turn " + g.turn + (g.phase ? " · " + g.phase : "");
    if (g.gameOver) { turnEl.textContent += g.winner ? " · WINNER: " + g.winner : " · game over"; }

    indexCardNames(g);
    commanders = {};
    (g.players || []).forEach(function (p) {
      (p.commanderIds || []).forEach(function (id) { commanders[id] = true; });
    });
    renderStack(g.stack);
    renderCombat(g.combat);
    renderManaBar(g);

    playersEl.innerHTML = "";
    (g.players || []).forEach(function (p) {
      playersEl.appendChild(renderPlayer(p, g.playerTurnId));
    });
  }

  function indexCardNames(g) {
    cardNames = {};
    (g.players || []).forEach(function (p) {
      ["battlefield", "hand", "graveyard", "exile", "command"].forEach(function (z) {
        (p[z] || []).forEach(function (c) { if (c) { cardNames[c.id] = c.name; } });
      });
    });
  }

  function renderStack(stack) {
    stackWrap.innerHTML = "";
    if (!stack || !stack.length) { return; }
    var sd = el("div", "stack");
    sd.appendChild(el("h3", "", "Stack"));
    stack.forEach(function (it) {
      var item = el("div", "item", it.text || ("#" + it.id));
      if (it.card) { item.onclick = function () { showCardDetail(it.card); }; } // tap to read the card
      sd.appendChild(item);
    });
    stackWrap.appendChild(sd);
  }

  // A prominent, always-visible mana pool for you: tap a pip to spend that mana on the current cost.
  function renderManaBar(g) {
    manabarEl.innerHTML = "";
    var me = (g.players || []).filter(function (p) { return mySeatIds.indexOf(p.id) >= 0; })[0];
    if (!me) { return; }
    manabarEl.appendChild(el("span", "mb-label", "Mana pool:"));
    var pips = me.manaPool ? manaPips(me.manaPool, true) : null;
    if (pips) { manabarEl.appendChild(pips); }
    else { manabarEl.appendChild(el("span", "mb-empty", "empty — tap a land to add mana")); }
  }

  function renderCombat(combat) {
    combatWrap.innerHTML = "";
    if (!combat || !combat.bands || !combat.bands.length) { return; }
    var box = el("div", "combat");
    box.appendChild(el("h3", "", "Combat"));
    combat.bands.forEach(function (b) {
      var line = b.attackerName + " → " + (b.defenderName || "?");
      if (b.blockerIds && b.blockerIds.length) {
        line += "  ⛊ blocked by " + b.blockerIds.map(nameOf).join(", ");
      } else {
        line += "  (unblocked)";
      }
      box.appendChild(el("div", "band", line));
    });
    combatWrap.appendChild(box);
  }

  function nameOf(id) { return cardNames[id] || ("#" + id); }

  function renderPlayer(p, playerTurnId) {
    var box = el("div", "player");
    var h = el("h2");
    h.appendChild(el("span", "life", "♥ " + p.life));
    h.appendChild(el("span", "", p.name || ("Player " + p.id)));

    var pool = manaPips(p.manaPool);
    if (pool) { h.appendChild(pool); }
    var ctrs = playerCounters(p.counters);
    if (ctrs) { h.appendChild(ctrs); }

    var tags = [];
    if (p.id === playerTurnId) { tags.push("active turn"); }
    if (p.hasPriority) { tags.push("priority"); }
    if (mySeatIds.indexOf(p.id) >= 0) { tags.push("you"); }
    if (p.ai) { tags.push("AI"); }
    if (tags.length) { h.appendChild(el("span", "tag", "· " + tags.join(" · "))); }
    box.appendChild(h);

    var zones = el("div", "zones");
    zones.appendChild(handZone(p));
    zones.appendChild(battlefieldZone(p.battlefield));
    zones.appendChild(zone("Command", p.command));
    zones.appendChild(compactZone("Graveyard", p.graveyard));
    zones.appendChild(compactZone("Exile", p.exile));
    zones.appendChild(zone("Library", null, p.librarySize));
    box.appendChild(zones);
    return box;
  }

  // Only your own floating mana is clickable (useMana pays from your pool during InputPayMana).
  function manaPips(pool, clickable) {
    if (!pool) { return null; }
    var wrap = el("span", "mana");
    ["W", "U", "B", "R", "G", "C"].forEach(function (sym) {
      var n = pool[sym];
      if (!n) { return; }
      var pip = el("span", "pip " + sym + (clickable ? " clickable" : ""), n + sym);
      if (clickable) { pip.onclick = function () { sendAction("useMana", { color: MANA_BYTE[sym] }); }; }
      wrap.appendChild(pip);
    });
    return wrap.childNodes.length ? wrap : null;
  }

  function playerCounters(counters) {
    if (!counters) { return null; }
    var wrap = el("span", "pctrs");
    Object.keys(counters).forEach(function (k) {
      wrap.appendChild(el("span", "ctr", k + " " + counters[k]));
    });
    return wrap.childNodes.length ? wrap : null;
  }

  function handZone(p) {
    var z = el("div", "zone");
    var cards = p.hand || [];
    var mine = mySeatIds.indexOf(p.id) >= 0;
    z.appendChild(el("h3", "", "Hand (" + p.handSize + ")"));
    // Hidden cards (an opponent's hand) arrive as face-down placeholders — show them as card backs;
    // your own (and any genuinely revealed) cards render face-up.
    cards.forEach(function (c) {
      z.appendChild(c.faceDown ? el("div", "card back", "🂠") : renderCard(c));
    });
    for (var i = cards.length; i < p.handSize; i++) {
      z.appendChild(el("div", "card back", "🂠"));
    }
    return z;
  }

  function battlefieldZone(cards) {
    var z = el("div", "zone");
    if (!cards) { z.appendChild(el("h3", "", "Battlefield (0)")); return z; }
    var byId = {};
    cards.forEach(function (c) { byId[c.id] = c; });
    var childrenOf = {};
    var topLevel = [];
    cards.forEach(function (c) {
      if (c.attachedToId != null && byId[c.attachedToId]) {
        (childrenOf[c.attachedToId] = childrenOf[c.attachedToId] || []).push(c);
      } else {
        topLevel.push(c);
      }
    });
    z.appendChild(el("h3", "", "Battlefield (" + topLevel.length + ")"));
    topLevel.forEach(function (c) { z.appendChild(renderCard(c, childrenOf)); });
    return z;
  }

  function zone(name, cards, count) {
    var z = el("div", "zone");
    var label = name + (typeof count === "number" ? " (" + count + ")" : (cards ? " (" + cards.length + ")" : ""));
    z.appendChild(el("h3", "", label));
    (cards || []).forEach(function (c) { z.appendChild(renderCard(c)); });
    return z;
  }

  // A compact zone (graveyard / exile): just a count + a View button that opens the full list, so
  // these seldom-inspected zones don't dominate the board.
  function compactZone(name, cards) {
    var z = el("div", "zone compact");
    var n = cards ? cards.length : 0;
    z.appendChild(el("h3", "", name + " (" + n + ")"));
    if (n > 0) {
      // Highlight View when the engine has marked a card here selectable (flashback / targeting),
      // so the player knows to open the zone even though its cards aren't on the board.
      var hasSelectable = cards.some(function (c) { return selectable[c.id]; });
      var view = el("button", "zone-view" + (hasSelectable ? " selectable" : ""), hasSelectable ? "View ●" : "View");
      view.onclick = function () { showZoneCards(name, cards); };
      z.appendChild(view);
    }
    return z;
  }

  // List every card in a zone in an overlay; tap a card for its full detail (which offers a Select
  // action when the card is playable/targetable). The View button flagged which zones have those.
  function showZoneCards(name, cards) {
    var overlay = el("div", "detail-overlay");
    function close() { if (overlay.parentNode) { overlay.parentNode.removeChild(overlay); } }
    var d = el("div", "dialog");
    d.appendChild(el("h3", "", name + " (" + cards.length + ")"));
    var list = el("div", "zone-cards");
    cards.forEach(function (c) {
      var row = el("button", "opt" + (selectable[c.id] ? " sel" : ""),
        (commanders[c.id] ? "👑 " : "") + (c.name || "?") + (c.type ? " — " + c.type : "") + (selectable[c.id] ? "  ●" : ""));
      row.onclick = function () { showCardDetail(c); };
      list.appendChild(row);
    });
    d.appendChild(list);
    var closeBtn = el("button", "opt", "Close");
    closeBtn.onclick = close;
    d.appendChild(closeBtn);
    overlay.appendChild(d);
    var armed = false;
    setTimeout(function () { armed = true; }, 200);
    overlay.onclick = function (e) { if (armed && e.target === overlay) { close(); } };
    document.body.appendChild(overlay);
  }

  function renderCard(c, childrenOf) {
    var cls = "card";
    if (c.tapped) { cls += " tapped"; }
    if (c.sick) { cls += " sick"; }
    if (c.attacking) { cls += " attacking"; }
    if (c.blocking) { cls += " blocking"; }
    if (selectable[c.id]) { cls += " selectable"; }
    if (commanders[c.id]) { cls += " cmdr"; }
    var card = el("div", cls);
    attachCardInteractions(card, c); // tap = play (selectCard), long-press = card detail

    var n = el("div", "n", (commanders[c.id] ? "👑 " : "") + (c.faceDown ? "(face down)" : (c.name || "?")));
    if (c.creature) {
      var ptText = c.power + "/" + c.toughness + (c.damage ? " (" + c.damage + " dmg)" : "");
      n.appendChild(el("span", "pt", ptText));
    } else if (c.loyalty) {
      n.appendChild(el("span", "pt", c.loyalty));
    }
    card.appendChild(n);
    if (c.type) { card.appendChild(el("div", "t", c.type)); }

    if (c.counters) {
      var ctrs = el("div", "ctrs");
      Object.keys(c.counters).forEach(function (k) {
        ctrs.appendChild(el("span", "ctr", k + " " + c.counters[k]));
      });
      card.appendChild(ctrs);
    }

    var children = childrenOf && childrenOf[c.id];
    if (children && children.length) {
      var wrap = el("div", "attached");
      children.forEach(function (child) { wrap.appendChild(renderCard(child, childrenOf)); });
      card.appendChild(wrap);
    }
    return card;
  }

  // The two engine-driven buttons (their labels/enabled state come from updateButtons).
  function renderButtons(b) {
    buttonsEl.innerHTML = "";
    if (!b) { return; }
    addButton(b.label1 || "OK", b.enable1, function () { sendAction("selectButtonOk"); });
    addButton(b.label2 || "Cancel", b.enable2, function () { sendAction("selectButtonCancel"); });
  }

  function addButton(label, enabled, onclick) {
    var btn = el("button", "", label);
    if (!enabled) { btn.setAttribute("disabled", "disabled"); }
    else { btn.onclick = onclick; }
    buttonsEl.appendChild(btn);
  }

  // Persistent controls that are meaningful whenever it's your turn to act.
  function renderActionBar() {
    actionbarEl.innerHTML = "";
    bar("Pass ▶▶ (until EOT)", function () { sendAction("passPriorityUntilEndOfTurn"); });
    bar("Undo", function () { sendAction("undoLastAction"); });
    bar("Alpha Strike", function () { sendAction("alphaStrike"); });
    bar("Resync", function () { sendAction("requestResync"); });
    var concede = bar("Concede", function () {
      if (window.confirm("Concede this game?")) { sendAction("concede"); }
    });
    concede.className = "danger";
  }

  function bar(label, onclick) {
    var btn = el("button", "", label);
    btn.onclick = onclick;
    actionbarEl.appendChild(btn);
    return btn;
  }

  // ── Dialogs (reply to a blocking game-thread question) ───────────────────────
  // The reply unblocks the game thread, so EVERY dialog must be answerable to completion — each
  // path below eventually calls reply(id, ...). A Cancel replies too (typically index -1, which
  // the server reads as "no choice" and resolves to its safe default), so a misclick can't trap
  // the user waiting on the question.

  function showAbilityDialog(payload, id) {
    var opts = (payload && payload.abilities) || [];
    openSingle((payload && payload.hostName) || "Choose an ability", opts.map(function (o) {
      return { label: o.description || ("Option " + o.index), value: { index: o.index } };
    }), function (value) { reply(id, value); }, function () { reply(id, { index: -1 }); });
  }

  function showConfirm(payload, id) {
    var q = (payload && payload.question) || "Confirm";
    var opts = payload && payload.options;
    if (opts && opts.length) {
      // Custom labels: the engine's confirm() returns true iff the FIRST option was chosen.
      modalButtons(q, opts.map(function (label, i) {
        return { label: label, onClick: function () { reply(id, { value: i === 0 }); } };
      }));
    } else {
      modalButtons(q, [
        { label: "Yes", onClick: function () { reply(id, { value: true }); } },
        { label: "No", onClick: function () { reply(id, { value: false }); } }
      ]);
    }
  }

  function showConfirmDialog(payload, id) {
    var p = payload || {};
    modalButtons(p.title || p.message || "Confirm", [
      { label: p.yesText || "Yes", onClick: function () { reply(id, { value: true }); } },
      { label: p.noText || "No", onClick: function () { reply(id, { value: false }); } }
    ], p.title ? p.message : null);
  }

  function showOptionDialog(payload, id) {
    var p = payload || {};
    var buttons = (p.options || []).map(function (o) {
      return { label: o.label, onClick: function () { reply(id, { index: o.index }); } };
    });
    buttons.push({ label: "Cancel", onClick: function () { reply(id, {}); } });
    modalButtons(p.title || p.message || "Choose", buttons, p.title ? p.message : null);
  }

  function showInputDialog(payload, id) {
    var p = payload || {};
    if (p.options && p.options.length) {
      var buttons = p.options.map(function (o) {
        return { label: o, onClick: function () { reply(id, { value: o }); } };
      });
      buttons.push({ label: "Cancel", onClick: function () { reply(id, { value: null }); } });
      modalButtons(p.title || p.message || "Input", buttons, p.title ? p.message : null);
      return;
    }
    var dialog = el("div", "dialog");
    dialog.appendChild(el("h3", "", p.title || "Input"));
    if (p.message) { dialog.appendChild(el("div", "", p.message)); }
    var input = document.createElement("input");
    input.type = p.isNumeric ? "number" : "text";
    input.value = p.initial != null ? p.initial : "";
    dialog.appendChild(input);
    dialog.appendChild(optButton("OK", function () { reply(id, { value: input.value }); }));
    dialog.appendChild(optButton("Cancel", function () { reply(id, { value: null }); }));
    showDialog(dialog);
    setTimeout(function () { input.focus(); }, 0);
  }

  // Single choice (chooseSingleEntityForEffect — e.g. searching your library). Tap an option to pick
  // it; long-press to read the card. A search box filters a long list. Cancel replies index -1
  // (optional -> null on the server; mandatory -> the server falls back to the first option).
  function showChooseEntity(payload, id) {
    var p = payload || {};
    var options = p.options || [];
    var dialog = el("div", "dialog");
    dialog.appendChild(el("h3", "", p.title || "Choose"));
    var listWrap = el("div", "optlist");
    var search = maybeSearch(options, dialog, function () { renderOpts(); });
    function renderOpts() {
      var q = search ? search.value.trim().toLowerCase() : "";
      listWrap.innerHTML = "";
      options.forEach(function (o) {
        if (q && (o.label || "").toLowerCase().indexOf(q) < 0) { return; }
        listWrap.appendChild(optionRow(o, o.label, function () { closeModal(); reply(id, { index: o.index }); }));
      });
    }
    dialog.appendChild(listWrap);
    dialog.appendChild(optButton("Cancel", function () { reply(id, { index: -1 }); }));
    renderOpts();
    showDialog(dialog);
  }

  // Multi-select for getChoices / chooseEntitiesForEffect (min..max). Negative min/max = unbounded
  // (reveal() sends -1/-1 as a pure acknowledgement, Confirm enabled at 0). Tap toggles; long-press
  // reads the card; a search box filters a long list.
  function showMultiSelect(payload, id) {
    var p = payload || {};
    var options = p.options || [];
    var min = (p.min != null && p.min >= 0) ? p.min : 0;
    var max = (p.max != null && p.max >= 0) ? p.max : options.length;
    var effMin = Math.min(min, options.length);
    var selected = {};
    (p.selected || []).forEach(function (i) { selected[i] = true; });

    var dialog = el("div", "dialog");
    dialog.appendChild(el("h3", "", p.message || p.title || "Choose " + min + "–" + max));
    var confirm = optButton("Confirm", function () { reply(id, { indices: Object.keys(selected).map(Number) }); });
    function box(o) { return (selected[o.index] ? "☑ " : "☐ ") + o.label; }
    function refreshConfirm() {
      var n = Object.keys(selected).length;
      confirm.disabled = !(n >= effMin && n <= max);
    }
    var listWrap = el("div", "optlist");
    var search = maybeSearch(options, dialog, function () { renderOpts(); });
    function renderOpts() {
      var q = search ? search.value.trim().toLowerCase() : "";
      listWrap.innerHTML = "";
      options.forEach(function (o) {
        if (q && (o.label || "").toLowerCase().indexOf(q) < 0) { return; }
        listWrap.appendChild(optionRow(o, box(o), function () {
          if (selected[o.index]) { delete selected[o.index]; }
          else {
            if (Object.keys(selected).length >= max) { if (max === 1) { selected = {}; } else { return; } }
            selected[o.index] = true;
          }
          renderOpts();       // reflect ☑/☐ (and max===1 replacement)
          refreshConfirm();
        }));
      });
    }
    dialog.appendChild(listWrap);
    dialog.appendChild(confirm);
    renderOpts();
    refreshConfirm();
    showDialog(dialog);
  }

  // A choice-option button: tap runs onTap; long-press shows the card's detail (if it carries one).
  function optionRow(o, labelText, onTap) {
    var btn = el("button", "opt", labelText);
    if (o.card) {
      var timer = null, longPressed = false;
      function start() { longPressed = false; timer = setTimeout(function () { longPressed = true; showCardDetail(o.card); }, 450); }
      function cancel() { if (timer) { clearTimeout(timer); timer = null; } }
      btn.addEventListener("touchstart", start, { passive: true });
      btn.addEventListener("touchmove", cancel, { passive: true });
      btn.addEventListener("touchend", function (e) { cancel(); if (longPressed) { e.preventDefault(); } }, { passive: false });
      btn.addEventListener("mousedown", start);
      btn.addEventListener("mouseup", cancel);
      btn.addEventListener("mouseleave", cancel);
      btn.onclick = function () { if (longPressed) { longPressed = false; return; } onTap(); };
    } else {
      btn.onclick = onTap;
    }
    return btn;
  }

  // A search box shown above a long option list; filters on input. Returns null for short lists.
  function maybeSearch(options, dialog, onChange) {
    if (!options || options.length <= 6) { return null; }
    var s = document.createElement("input");
    s.type = "text";
    s.placeholder = "Search…";
    s.className = "search";
    s.oninput = onChange;
    dialog.appendChild(s);
    return s;
  }

  // Tap items in the desired order (order()); Confirm enables once all are placed.
  function showOrder(payload, id) {
    var p = payload || {};
    var options = p.options || [];
    var order = [];
    var dialog = el("div", "dialog");
    dialog.appendChild(el("h3", "", p.title || "Order"));
    if (p.top) { dialog.appendChild(el("div", "", "Top: " + p.top)); }
    var confirm = optButton("Confirm order", function () { reply(id, { indices: order }); });
    confirm.disabled = true;
    options.forEach(function (o) {
      var b = plainButton(o.label, function () {
        if (order.indexOf(o.index) >= 0) { return; }
        order.push(o.index);
        b.textContent = order.length + ". " + o.label;
        b.disabled = true;
        confirm.disabled = order.length !== options.length;
      });
      o._ob = b;
      dialog.appendChild(b);
    });
    dialog.appendChild(confirm);
    dialog.appendChild(plainButton("Reset", function () {
      order = [];
      options.forEach(function (o) { o._ob.textContent = o.label; o._ob.disabled = false; });
      confirm.disabled = true;
    }));
    showDialog(dialog);
  }

  // Distribute a fixed total across items (assignCombatDamage / assignGenericAmount). Each amount
  // is clamped to >= 0 (so a typed negative can't make the running total lie), and Confirm requires
  // sum === total and every item >= minEach (minEach = 1 enforces "at least one to each").
  function showDistribute(id, total, items, minEach, title) {
    items = items || [];
    var dialog = el("div", "dialog");
    dialog.appendChild(el("h3", "", title));
    var tally = el("div", "", "");
    dialog.appendChild(tally);
    var inputs = [];
    function amounts() { return inputs.map(function (i) { return Math.max(0, parseInt(i.value, 10) || 0); }); }
    var confirm = optButton("Confirm", function () { reply(id, { amounts: amounts() }); });
    function refresh() {
      var vals = amounts();
      var s = vals.reduce(function (a, v) { return a + v; }, 0);
      var meetsMin = vals.every(function (v) { return v >= minEach; });
      tally.textContent = "Assigned " + s + " / " + total;
      confirm.disabled = !(s === total && meetsMin);
    }
    items.forEach(function (it) {
      var row = el("div", "");
      row.appendChild(el("span", "", it.label + (it.lethal != null ? " (lethal " + it.lethal + ")" : "") + ": "));
      var input = document.createElement("input");
      input.type = "number"; input.min = "0"; input.value = "0";
      input.oninput = refresh;
      inputs.push(input);
      row.appendChild(input);
      dialog.appendChild(row);
    });
    dialog.appendChild(confirm);
    refresh();
    showDialog(dialog);
  }

  // ── Modal primitives ─────────────────────────────────────────────────────────
  function openSingle(title, options, onPick, onCancel) {
    var dialog = el("div", "dialog");
    dialog.appendChild(el("h3", "", title));
    options.forEach(function (opt) {
      dialog.appendChild(optButton(opt.label, function () { onPick(opt.value); }));
    });
    dialog.appendChild(optButton("Cancel", function () { if (onCancel) { onCancel(); } }));
    showDialog(dialog);
  }

  function modalButtons(title, buttons, subtitle) {
    var dialog = el("div", "dialog");
    dialog.appendChild(el("h3", "", title));
    if (subtitle) { dialog.appendChild(el("div", "", subtitle)); }
    buttons.forEach(function (b) { dialog.appendChild(optButton(b.label, b.onClick)); });
    showDialog(dialog);
  }

  // A terminal modal button: closes the modal, then runs its (reply-sending) handler.
  function optButton(label, onClick) {
    var b = el("button", "opt", label);
    b.onclick = function () { closeModal(); onClick(); };
    return b;
  }

  // A non-terminal modal button (toggle / order / reset): runs its handler without closing.
  function plainButton(label, onClick) {
    var b = el("button", "opt", label);
    b.onclick = onClick;
    return b;
  }

  function showDialog(dialog) {
    modalEl.innerHTML = "";
    modalEl.appendChild(dialog);
    modalEl.classList.add("show");
  }

  function closeModal() {
    modalEl.classList.remove("show");
    modalEl.innerHTML = "";
  }

  // ── Card interactions: tap = play (selectCard); long-press = read the full card ───────────────
  function attachCardInteractions(cardEl, c) {
    var timer = null;
    var longPressed = false;
    function start() { longPressed = false; timer = setTimeout(function () { longPressed = true; showCardDetail(c); }, 450); }
    function cancel() { if (timer) { clearTimeout(timer); timer = null; } }
    cardEl.addEventListener("touchstart", start, { passive: true });
    cardEl.addEventListener("touchmove", cancel, { passive: true });
    // Non-passive: after a long-press, preventDefault suppresses the emulated mouse/click events for
    // this gesture, so the finger-lift doesn't immediately dismiss the detail overlay it just opened.
    cardEl.addEventListener("touchend", function (e) { cancel(); if (longPressed) { e.preventDefault(); } }, { passive: false });
    cardEl.addEventListener("mousedown", start);
    cardEl.addEventListener("mouseup", cancel);
    cardEl.addEventListener("mouseleave", cancel);
    cardEl.onclick = function (e) {
      e.stopPropagation();
      if (longPressed) { longPressed = false; return; } // a long-press already opened the detail
      sendAction("selectCard", { cardId: c.id });
    };
  }

  // Full card readout in its own overlay (independent of the game-question modal), tap to dismiss.
  function showCardDetail(c) {
    var overlay = el("div", "detail-overlay");
    var d = el("div", "dialog");
    d.appendChild(el("h3", "", (commanders[c.id] ? "👑 " : "") + (c.faceDown ? "(face down)" : (c.name || "?"))));
    if (c.manaCost) { d.appendChild(el("div", "detail-mc", c.manaCost)); }
    if (c.type) { d.appendChild(el("div", "detail-type", c.type)); }
    if (c.creature) { d.appendChild(el("div", "detail-pt", c.power + "/" + c.toughness + (c.damage ? " (" + c.damage + " dmg)" : ""))); }
    else if (c.loyalty) { d.appendChild(el("div", "detail-pt", "Loyalty: " + c.loyalty)); }
    if (c.text) { d.appendChild(el("div", "detail-text", c.text)); }
    // If the engine will accept a click on this card (e.g. a flashback/escape card in the graveyard,
    // or a targeting prompt), offer a Select action here — this is how such cards are played/chosen
    // from the compact graveyard/exile viewer, which otherwise only shows detail.
    if (selectable[c.id]) {
      var sel = el("button", "opt start", "Select");
      sel.onclick = function () { closeAllOverlays(); sendAction("selectCard", { cardId: c.id }); };
      d.appendChild(sel);
    }
    d.appendChild(el("button", "opt", "Close"));
    overlay.appendChild(d);
    // Arm dismissal only after the opening gesture has fully settled, so a late emulated click from
    // the long-press can't close the overlay the instant it appears.
    var armed = false;
    setTimeout(function () { armed = true; }, 350);
    overlay.onclick = function () { if (armed && overlay.parentNode) { overlay.parentNode.removeChild(overlay); } };
    document.body.appendChild(overlay);
  }

  function closeAllOverlays() {
    var o = document.getElementsByClassName("detail-overlay");
    while (o.length) { o[0].parentNode.removeChild(o[0]); }
  }

  // ── Lobby / setup screen ──────────────────────────────────────────────────────────────────────
  function renderSetup(payload) {
    // Returning to the lobby (e.g. after a concede): clear any leftover in-game UI behind the setup.
    closeModal();
    var overlays = document.getElementsByClassName("detail-overlay");
    while (overlays.length) { overlays[0].parentNode.removeChild(overlays[0]); }
    buttonsEl.innerHTML = "";
    promptEl.textContent = "";
    var decks = (payload && payload.decks) || [{ id: "random", label: "🎲 Random" }];
    var maxAi = (payload && payload.maxAi) || 5;
    var aiCount = Math.max(1, Math.min(maxAi, (payload && payload.defaultAi) || 1));
    var aiSelects = [];

    var box = el("div", "dialog setup-dialog");
    box.appendChild(el("h3", "", "New Commander Game"));
    if (payload && payload.error) { box.appendChild(el("div", "setup-error", payload.error)); }

    var aiWrap = el("div", "");
    function rebuild() {
      aiWrap.innerHTML = "";
      aiSelects = [];
      for (var i = 0; i < aiCount; i++) {
        aiWrap.appendChild(el("span", "setup-label", "Opponent " + (i + 1) + " deck:"));
        var s = deckPicker(decks);
        aiWrap.appendChild(s);
        aiSelects.push(s);
      }
    }
    var count = el("span", "setup-count", String(aiCount));
    function setCount(n) { aiCount = Math.max(1, Math.min(maxAi, n)); count.textContent = String(aiCount); rebuild(); }

    var aiRow = el("div", "setup-row");
    aiRow.appendChild(el("span", "setup-label", "Opponents (AI):"));
    aiRow.appendChild(barBtn("−", function () { setCount(aiCount - 1); }));
    aiRow.appendChild(count);
    aiRow.appendChild(barBtn("+", function () { setCount(aiCount + 1); }));
    box.appendChild(aiRow);

    box.appendChild(el("span", "setup-label", "Your deck:"));
    var humanSel = deckPicker(decks);
    box.appendChild(humanSel);

    box.appendChild(aiWrap);
    rebuild();

    var start = el("button", "opt start", "Start Game");
    start.onclick = function () {
      sendAction("startGame", {
        aiCount: aiCount,
        human: humanSel.selectedId,
        ai: aiSelects.map(function (s) { return s.selectedId; })
      });
      // Keep the setup visible (just disabled) until the game actually starts — setGameView calls
      // hideSetup(). If the start fails, the server re-sends setup with an error, re-enabling this.
      start.disabled = true;
      start.textContent = "Starting…";
      setStatus("starting game…");
    };
    box.appendChild(start);

    setupEl.innerHTML = "";
    setupEl.appendChild(box);
    setupEl.classList.add("show");
  }

  function hideSetup() { setupEl.classList.remove("show"); setupEl.innerHTML = ""; }

  // Natural game end: show the outcome with a Back-to-Menu button (leaveMatch -> lobby). Uses its
  // own overlay so it doesn't clash with the game-question modal.
  function showGameOver(payload) {
    var winner = payload && payload.winner;
    var mine = winner && mySeatIds.length && lastGame &&
      (lastGame.players || []).some(function (p) { return mySeatIds.indexOf(p.id) >= 0 && p.name === winner; });
    var overlay = el("div", "detail-overlay");
    var d = el("div", "dialog");
    d.appendChild(el("h3", "", winner ? (mine ? "You win!" : winner + " wins") : "Game over"));
    var btn = el("button", "opt start", "Back to Menu");
    btn.onclick = function () { if (overlay.parentNode) { overlay.parentNode.removeChild(overlay); } sendAction("leaveMatch"); };
    d.appendChild(btn);
    overlay.appendChild(d);
    document.body.appendChild(overlay);
  }

  // A searchable deck picker: a button showing the current choice; tapping it opens a filter box +
  // a scrollable list of matching decks. Exposes `.selectedId` (read at Start time). Defaults to the
  // first deck (Random). Handles long saved+precon deck lists far better than a native <select>.
  function deckPicker(decks) {
    var container = el("div", "deckpick");
    var selected = decks[0] || { id: "random", label: "🎲 Random" };
    container.selectedId = selected.id;

    var current = el("button", "deckpick-current", selected.label);
    var panel = el("div", "deckpick-panel");
    var search = document.createElement("input");
    search.type = "text";
    search.placeholder = "Search decks…";
    var list = el("div", "deckpick-list");

    function renderList() {
      var q = search.value.trim().toLowerCase();
      list.innerHTML = "";
      decks.forEach(function (d) {
        if (q && d.label.toLowerCase().indexOf(q) < 0) { return; }
        var opt = el("button", "deckpick-opt" + (d.id === selected.id ? " sel" : ""), d.label);
        opt.onclick = function () {
          selected = d;
          container.selectedId = d.id;
          current.textContent = d.label;
          collapse();
        };
        list.appendChild(opt);
      });
    }
    function expand() {
      panel.style.display = "block";
      search.value = "";
      renderList();
      setTimeout(function () { search.focus(); }, 0);
    }
    function collapse() { panel.style.display = "none"; }

    current.onclick = function () { panel.style.display === "block" ? collapse() : expand(); };
    search.oninput = renderList;
    panel.style.display = "none";
    panel.appendChild(search);
    panel.appendChild(list);
    container.appendChild(current);
    container.appendChild(panel);
    return container;
  }

  function barBtn(label, onclick) { var b = el("button", "", label); b.onclick = onclick; return b; }

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) { e.className = cls; }
    if (text != null) { e.textContent = text; }
    return e;
  }
})();
