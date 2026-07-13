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
  var stackWrap = document.getElementById("stackWrap");
  var combatWrap = document.getElementById("combatWrap");
  var playersEl = document.getElementById("players");
  var modalEl = document.getElementById("modal");

  var mySeatIds = [];
  var cardNames = {};        // id -> name, rebuilt each render so combat can name blockers.
  var selectable = {};       // id -> true for cards the engine will accept a click on.
  var lastGame = null;       // last GameView, so a selectables update can re-render.

  // MagicColor byte values the engine's useMana(byte) expects.
  var MANA_BYTE = { W: 1, U: 2, B: 4, R: 8, G: 16, C: 0 };

  var proto = location.protocol === "https:" ? "wss:" : "ws:";
  var ws = new WebSocket(proto + "//" + location.host + "/game");

  ws.onopen = function () { setStatus("connected"); };
  ws.onclose = function () { setStatus("disconnected"); };
  ws.onerror = function () { setStatus("error"); };
  ws.onmessage = function (ev) {
    var msg;
    try { msg = JSON.parse(ev.data); } catch (e) { return; }
    handle(msg.type, msg.payload, msg.id);
  };

  function setStatus(s) { statusEl.textContent = s; }

  // ── Outbound ───────────────────────────────────────────────────────────────
  function sendAction(type, payload) {
    if (ws.readyState === WebSocket.OPEN) { ws.send(JSON.stringify({ type: type, payload: payload || null })); }
  }
  function reply(id, value) {
    if (ws.readyState === WebSocket.OPEN) { ws.send(JSON.stringify({ replyTo: id, value: value })); }
  }

  renderActionBar();

  // ── Inbound ────────────────────────────────────────────────────────────────
  function handle(type, payload, id) {
    switch (type) {
      case "openView": mySeatIds = payload || []; break;
      case "setGameView": lastGame = payload; renderGame(payload); break;
      case "prompt": promptEl.textContent = payload && payload.message ? payload.message : ""; break;
      case "buttons": renderButtons(payload); break;
      case "message": if (payload && payload.message) { promptEl.textContent = payload.message; } break;
      case "selectables": setSelectables(payload); break;
      case "getAbilityToPlay": showAbilityDialog(payload, id); break;
      case "finishGame": promptEl.textContent = "Game over."; break;
      default:
        // Phase 3 question types (getChoices, confirm, order, assignCombatDamage, …) will render
        // modals and reply with { replyTo: id, value: ... }. For now, just log.
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
    renderStack(g.stack);
    renderCombat(g.combat);

    playersEl.innerHTML = "";
    (g.players || []).forEach(function (p) {
      playersEl.appendChild(renderPlayer(p, g.playerTurnId));
    });
  }

  function indexCardNames(g) {
    cardNames = {};
    (g.players || []).forEach(function (p) {
      ["battlefield", "hand", "graveyard", "exile", "command", "commanders"].forEach(function (z) {
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
      sd.appendChild(el("div", "item", it.text || ("#" + it.id)));
    });
    stackWrap.appendChild(sd);
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
    if (p.commanders && p.commanders.length) { zones.appendChild(zone("Commanders", p.commanders)); }
    zones.appendChild(zone("Graveyard", p.graveyard));
    zones.appendChild(zone("Exile", p.exile));
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
    var revealed = p.hand || [];
    var mine = mySeatIds.indexOf(p.id) >= 0;
    z.appendChild(el("h3", "", "Hand (" + p.handSize + ")"));
    revealed.forEach(function (c) { z.appendChild(renderCard(c)); });
    if (!mine) {
      for (var i = revealed.length; i < p.handSize; i++) {
        z.appendChild(el("div", "card back", "🂠"));
      }
    }
    // Your own mana pool is worth showing as clickable pips in the hand zone during payment.
    if (mine && p.manaPool) {
      var pool = manaPips(p.manaPool, true);
      if (pool) { z.appendChild(pool); }
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

  function renderCard(c, childrenOf) {
    var cls = "card";
    if (c.tapped) { cls += " tapped"; }
    if (c.sick) { cls += " sick"; }
    if (c.attacking) { cls += " attacking"; }
    if (c.blocking) { cls += " blocking"; }
    if (selectable[c.id]) { cls += " selectable"; }
    var card = el("div", cls);
    card.onclick = function (e) { e.stopPropagation(); sendAction("selectCard", { cardId: c.id }); };

    var n = el("div", "n", c.faceDown ? "(face down)" : (c.name || "?"));
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
  // The reply unblocks the game thread, so every dialog MUST be answerable. A Cancel always
  // replies too (here with index -1, which getAbilityToPlay reads as "no ability" -> null),
  // so a misclick can never trap the user waiting on the question.
  function showAbilityDialog(payload, id) {
    var opts = (payload && payload.abilities) || [];
    openModal((payload && payload.hostName) || "Choose an ability", opts.map(function (o) {
      return { label: o.description || ("Option " + o.index), value: { index: o.index } };
    }), function (value) { reply(id, value); }, function () { reply(id, { index: -1 }); });
  }

  function openModal(title, options, onPick, onCancel) {
    var dialog = el("div", "dialog");
    dialog.appendChild(el("h3", "", title));
    options.forEach(function (opt) {
      var b = el("button", "opt", opt.label);
      b.onclick = function () { closeModal(); onPick(opt.value); };
      dialog.appendChild(b);
    });
    var cancel = el("button", "opt", "Cancel");
    cancel.onclick = function () { closeModal(); if (onCancel) { onCancel(); } };
    dialog.appendChild(cancel);
    modalEl.innerHTML = "";
    modalEl.appendChild(dialog);
    modalEl.classList.add("show");
  }

  function closeModal() {
    modalEl.classList.remove("show");
    modalEl.innerHTML = "";
  }

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) { e.className = cls; }
    if (text != null) { e.textContent = text; }
    return e;
  }
})();
