// Forge Web — Phase 0 client.
// Connects to the /game WebSocket, renders the full GameView on each setGameView, and shows
// prompts/buttons. Phase 2 adds outbound actions (tap card -> selectCard, pass, OK/Cancel).
// Written as plain JS so it serves with no build step; Phase 1 migrates this to TypeScript
// under forge-gui-web/web/ with a tiny esbuild step that emits back into resources/web/.

(function () {
  "use strict";

  var statusEl = document.getElementById("status");
  var titleEl = document.getElementById("title");
  var turnEl = document.getElementById("turn");
  var promptEl = document.getElementById("prompt");
  var buttonsEl = document.getElementById("buttons");
  var stackWrap = document.getElementById("stackWrap");
  var playersEl = document.getElementById("players");

  var mySeatIds = [];

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

    stackWrap.innerHTML = "";
    if (g.stack && g.stack.length) {
      var sd = el("div", "stack");
      sd.appendChild(el("h3", "", "Stack"));
      g.stack.forEach(function (it) {
        sd.appendChild(el("div", "item", it.text || ("#" + it.id)));
      });
      stackWrap.appendChild(sd);
    }

    playersEl.innerHTML = "";
    (g.players || []).forEach(function (p) {
      playersEl.appendChild(renderPlayer(p, g.playerTurnId));
    });
  }

  function renderPlayer(p, playerTurnId) {
    var box = el("div", "player");
    var h = el("h2");
    h.appendChild(el("span", "life", "♥ " + p.life));
    h.appendChild(el("span", "", p.name || ("Player " + p.id)));
    var tags = [];
    if (p.id === playerTurnId) { tags.push("active turn"); }
    if (p.hasPriority) { tags.push("priority"); }
    if (mySeatIds.indexOf(p.id) >= 0) { tags.push("you"); }
    if (p.ai) { tags.push("AI"); }
    if (tags.length) { h.appendChild(el("span", "tag", "· " + tags.join(" · "))); }
    box.appendChild(h);

    var zones = el("div", "zones");
    zones.appendChild(zone("Hand", p.hand, p.handSize));
    zones.appendChild(zone("Battlefield", p.battlefield));
    zones.appendChild(zone("Command", p.command));
    if (p.commanders && p.commanders.length) { zones.appendChild(zone("Commanders", p.commanders)); }
    zones.appendChild(zone("Graveyard", p.graveyard));
    zones.appendChild(zone("Exile", p.exile));
    zones.appendChild(zone("Library", null, p.librarySize));
    box.appendChild(zones);
    return box;
  }

  function zone(name, cards, count) {
    var z = el("div", "zone");
    var label = name + (typeof count === "number" ? " (" + count + ")" : (cards ? " (" + cards.length + ")" : ""));
    z.appendChild(el("h3", "", label));
    (cards || []).forEach(function (c) { z.appendChild(renderCard(c)); });
    return z;
  }

  function renderCard(c) {
    var card = el("div", "card" + (c.tapped ? " tapped" : ""));
    var n = el("div", "n", c.faceDown ? "(face down)" : (c.name || "?"));
    if (c.creature) {
      var pt = el("span", "pt", c.power + "/" + c.toughness);
      n.appendChild(pt);
    } else if (c.loyalty) {
      n.appendChild(el("span", "pt", c.loyalty));
    }
    card.appendChild(n);
    if (c.type) { card.appendChild(el("div", "t", c.type)); }
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
