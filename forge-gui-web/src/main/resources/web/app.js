// Forge Web — Phase 1 client.
// Connects to the /game WebSocket and renders the full GameView on each setGameView: players
// (life, mana pool, counters, commander), battlefield (tapped/sick, P/T, damage, counters,
// attached equipment/auras nested under their host), hands (yours face-up, opponents as backs),
// other zones as counts, the stack, and combat (attacker → defender, blockers).
// Phase 2 adds outbound actions (tap card -> selectCard, pass, OK/Cancel) and reply modals.
// Plain JS so it serves with no build step.

(function () {
  "use strict";

  var statusEl = document.getElementById("status");
  var titleEl = document.getElementById("title");
  var turnEl = document.getElementById("turn");
  var promptEl = document.getElementById("prompt");
  var buttonsEl = document.getElementById("buttons");
  var stackWrap = document.getElementById("stackWrap");
  var combatWrap = document.getElementById("combatWrap");
  var playersEl = document.getElementById("players");

  var mySeatIds = [];
  var cardNames = {}; // id -> name, rebuilt each render so combat can name blockers.

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

  function handle(type, payload, id) {
    switch (type) {
      case "openView": mySeatIds = payload || []; break;
      case "setGameView": renderGame(payload); break;
      case "prompt": promptEl.textContent = payload && payload.message ? payload.message : ""; break;
      case "buttons": renderButtons(payload); break;
      case "message": if (payload && payload.message) { promptEl.textContent = payload.message; } break;
      case "finishGame": promptEl.textContent = "Game over."; break;
      default:
        // Phase 2+ question types (getChoices, confirm, order, …) will render modals and reply
        // with { replyTo: id, value: ... }. For now, just log so the protocol is visible.
        console.log("unhandled message:", type, payload, "id=", id);
    }
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

  // Build id -> name across every visible zone so combat bands can label blockers by name.
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

  function manaPips(pool) {
    if (!pool) { return null; }
    var wrap = el("span", "mana");
    ["W", "U", "B", "R", "G", "C"].forEach(function (sym) {
      var n = pool[sym];
      if (n) { wrap.appendChild(el("span", "pip " + sym, n + sym)); }
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

  // Your hand is face-up; an opponent's shows any revealed cards plus card backs for the rest.
  function handZone(p) {
    var z = el("div", "zone");
    var revealed = p.hand || [];
    z.appendChild(el("h3", "", "Hand (" + p.handSize + ")"));
    if (mySeatIds.indexOf(p.id) >= 0) {
      revealed.forEach(function (c) { z.appendChild(renderCard(c)); });
    } else {
      revealed.forEach(function (c) { z.appendChild(renderCard(c)); });
      for (var i = revealed.length; i < p.handSize; i++) {
        z.appendChild(el("div", "card back", "🂠"));
      }
    }
    return z;
  }

  // Battlefield nests attached equipment/auras under their host and hides them at the top level.
  function battlefieldZone(cards) {
    var z = el("div", "zone");
    z.appendChild(el("h3", "", "Battlefield (" + (cards ? cards.length : 0) + ")"));
    if (!cards) { return z; }
    var byId = {};
    cards.forEach(function (c) { byId[c.id] = c; });
    cards.forEach(function (c) {
      if (c.attachedToId != null && byId[c.attachedToId]) { return; } // rendered under its host
      z.appendChild(renderCard(c, byId));
    });
    return z;
  }

  function zone(name, cards, count) {
    var z = el("div", "zone");
    var label = name + (typeof count === "number" ? " (" + count + ")" : (cards ? " (" + cards.length + ")" : ""));
    z.appendChild(el("h3", "", label));
    (cards || []).forEach(function (c) { z.appendChild(renderCard(c)); });
    return z;
  }

  function renderCard(c, byId) {
    var cls = "card";
    if (c.tapped) { cls += " tapped"; }
    if (c.sick) { cls += " sick"; }
    if (c.attacking) { cls += " attacking"; }
    if (c.blocking) { cls += " blocking"; }
    var card = el("div", cls);

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

    // Nest attached cards (equipment/auras) if we have the host's zone index.
    if (byId && c.attachedCardIds && c.attachedCardIds.length) {
      var wrap = el("div", "attached");
      c.attachedCardIds.forEach(function (aid) {
        if (byId[aid]) { wrap.appendChild(renderCard(byId[aid], byId)); }
      });
      if (wrap.childNodes.length) { card.appendChild(wrap); }
    }
    return card;
  }

  function renderButtons(b) {
    buttonsEl.innerHTML = "";
    if (!b) { return; }
    // Phase 2 wires these to selectButtonOk / selectButtonCancel over the socket.
    addButton(b.label1 || "OK", b.enable1);
    addButton(b.label2 || "Cancel", b.enable2);
  }

  function addButton(label, enabled) {
    var btn = el("button", "", label);
    if (!enabled) { btn.setAttribute("disabled", "disabled"); }
    buttonsEl.appendChild(btn);
  }

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) { e.className = cls; }
    if (text != null) { e.textContent = text; }
    return e;
  }
})();
