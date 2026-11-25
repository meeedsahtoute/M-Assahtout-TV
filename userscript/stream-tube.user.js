// ==UserScript==
// @name         M. ASSAHTOUT TV: open in app
// @namespace    https://github.com/meeedsahtoute/M-Assahtout-TV
// @version      1.0.0
// @description  Adds an "Open in M. ASSAHTOUT TV" button to YouTube, opening videos, Shorts, channels and playlists directly in the desktop client.
// @author       Mohammed Assahtout (m.assahtoute)
// @license      MIT
// @match        https://www.youtube.com/*
// @match        https://m.youtube.com/*
// @match        https://music.youtube.com/*
// @icon         https://raw.githubusercontent.com/meeedsahtoute/M-Assahtout-TV/main/assets/logo.png
// @homepageURL  https://github.com/meeedsahtoute/M-Assahtout-TV
// @supportURL   https://github.com/meeedsahtoute/M-Assahtout-TV/issues
// @downloadURL  https://raw.githubusercontent.com/meeedsahtoute/M-Assahtout-TV/main/userscript/stream-tube.user.js
// @updateURL    https://raw.githubusercontent.com/meeedsahtoute/M-Assahtout-TV/main/userscript/stream-tube.user.js
// @grant        GM_getValue
// @grant        GM_setValue
// @grant        GM_registerMenuCommand
// @run-at       document-idle
// @noframes
// ==/UserScript==

// Registers the streamtube:// link scheme when installed (or on first portable run).
(function () {
  "use strict";

  const BUTTON_ID = "stream-tube-open";
  const get = (k, d) => (typeof GM_getValue === "function" ? GM_getValue(k, d) : d);
  const set = (k, v) => typeof GM_setValue === "function" && GM_setValue(k, v);

  /** The YouTube page this tab shows, if Stream Tube can open it. */
  function target() {
    const u = new URL(location.href);
    const p = u.pathname;
    if (p === "/watch" && u.searchParams.get("v")) return { kind: "video", url: u };
    if (p.startsWith("/shorts/")) return { kind: "video", url: u };
    if (p === "/playlist" && u.searchParams.get("list")) return { kind: "playlist", url: u };
    if (/^\/(@[^/]+|channel\/[^/]+|c\/[^/]+|user\/[^/]+)/.test(p)) return { kind: "channel", url: u };
    return null;
  }

  function playingVideo() {
    return document.querySelector("#movie_player video, video.html5-main-video, ytd-reel-video-renderer[is-active] video, video");
  }

  /** The link Stream Tube opens: the page's URL, with the current position for videos. */
  function appLink(t) {
    const url = new URL(t.url.href);
    if (t.kind === "video") {
      const v = playingVideo();
      if (v && v.currentTime > 5 && !(v.duration && v.duration - v.currentTime < 5)) {
        url.searchParams.set("t", Math.floor(v.currentTime) + "s");
      }
    }
    return "streamtube://open?url=" + encodeURIComponent(url.href);
  }

  function openInApp() {
    const t = target();
    if (!t) return;
    const v = playingVideo();
    const link = appLink(t);
    if (v && !v.paused) v.pause();
    location.href = link;
  }

  function button() {
    const b = document.createElement("button");
    b.id = BUTTON_ID;
    b.type = "button";
    b.title = "Open in M. ASSAHTOUT TV";
    b.setAttribute("aria-label", "Open in M. ASSAHTOUT TV");
    const icon = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    icon.setAttribute("viewBox", "0 0 24 24");
    icon.setAttribute("width", "18");
    icon.setAttribute("height", "18");
    const path = document.createElementNS("http://www.w3.org/2000/svg", "path");
    path.setAttribute("d", "M8 5.14v13.72a1 1 0 0 0 1.5.86l11-6.86a1 1 0 0 0 0-1.72l-11-6.86A1 1 0 0 0 8 5.14z");
    path.setAttribute("fill", "currentColor");
    icon.appendChild(path);
    const label = document.createElement("span");
    label.textContent = "Open in M. ASSAHTOUT TV";
    b.append(icon, label);
    Object.assign(b.style, {
      position: "fixed",
      right: "20px",
      bottom: "20px",
      zIndex: "2147483647",
      display: "flex",
      alignItems: "center",
      gap: "8px",
      padding: "10px 16px",
      border: "none",
      borderRadius: "999px",
      background: "#ff0033",
      color: "#fff",
      font: "500 14px/1 Roboto, Arial, sans-serif",
      boxShadow: "0 4px 14px rgba(0,0,0,.35)",
      cursor: "pointer",
      opacity: "0.92",
      transition: "opacity .15s, transform .15s",
    });
    b.addEventListener("mouseenter", () => { b.style.opacity = "1"; b.style.transform = "translateY(-1px)"; });
    b.addEventListener("mouseleave", () => { b.style.opacity = "0.92"; b.style.transform = ""; });
    b.addEventListener("click", (e) => {
      e.preventDefault();
      e.stopPropagation();
      openInApp();
    });
    return b;
  }

  let lastAuto = "";

  /** Shows the button where it applies, and hands videos over by itself when asked to. */
  function update() {
    const t = target();
    const existing = document.getElementById(BUTTON_ID);
    const hidden = get("hideButton", false);
    if (!t || hidden) {
      if (existing) existing.remove();
    } else if (!existing && document.body) {
      document.body.appendChild(button());
    }
    if (t && t.kind === "video" && get("autoOpen", false) && lastAuto !== location.href) {
      lastAuto = location.href;
      openInApp();
    }
  }

  function toggle(key, on, off) {
    const v = !get(key, false);
    set(key, v);
    alert(v ? on : off);
    update();
  }

  if (typeof GM_registerMenuCommand === "function") {
    GM_registerMenuCommand("Open this page in M. ASSAHTOUT TV", openInApp);
    GM_registerMenuCommand("Always open videos in M. ASSAHTOUT TV (on/off)", () =>
      toggle("autoOpen", "Videos now open in M. ASSAHTOUT TV automatically.", "Videos stay in the browser again."));
    GM_registerMenuCommand("Show or hide the button", () =>
      toggle("hideButton", "The button is hidden. Use this menu to open pages in M. ASSAHTOUT TV.", "The button is back."));
  }

  // Alt+S opens the current page in the app
  document.addEventListener("keydown", (e) => {
    if (e.altKey && !e.ctrlKey && !e.metaKey && (e.key === "s" || e.key === "S")) {
      const el = document.activeElement;
      if (el && (el.isContentEditable || /^(INPUT|TEXTAREA)$/.test(el.tagName))) return;
      e.preventDefault();
      openInApp();
    }
  }, true);

  // YouTube is a single-page app: follow its navigations
  window.addEventListener("yt-navigate-finish", update);
  window.addEventListener("popstate", update);
  let href = location.href;
  setInterval(() => {
    if (href !== location.href) {
      href = location.href;
      update();
    }
  }, 700);
  update();
})();
