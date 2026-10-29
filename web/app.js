function getCsrfToken() {
  const match = document.cookie.match(/(?:^|; )motion_csrf=([^;]+)/);
  return match ? decodeURIComponent(match[1]) : "";
}

function apiFetch(url, options = {}) {
  options.credentials = options.credentials || "include";
  options.headers = options.headers || {};
  const method = (options.method || "GET").toUpperCase();
  if (method !== "GET" && method !== "HEAD" && method !== "OPTIONS") {
    const csrf = getCsrfToken();
    if (csrf) {
      options.headers["X-CSRF-Token"] = csrf;
    }
  }
  if (options.body && typeof options.body === "object" && !(options.body instanceof FormData)) {
    options.headers["Content-Type"] = options.headers["Content-Type"] || "application/json";
    options.body = JSON.stringify(options.body);
  }
  return fetch(url, options);
}

(() => {
  "use strict";

  const MAX_OPERATIONS = 10;
  const OPERATION_DEFS = [
    { type: "trim", name: "Trim", desc: "Cut start and end times", icon: "trim" },
    { type: "speed", name: "Speed", desc: "Change playback speed", icon: "speed" },
    { type: "volume", name: "Volume", desc: "Adjust audio level", icon: "volume" },
    { type: "flip", name: "Flip", desc: "Mirror horizontally or vertically", icon: "flip" },
    { type: "to_audio", name: "Extract audio", desc: "Save as MP3", icon: "audio" },
  ];

  const els = {
    themeToggle: document.getElementById("themeToggle"),
    userInfo: document.getElementById("userInfo"),
    logoutBtnTop: document.getElementById("logoutBtnTop"),
    backendStatus: document.getElementById("backendStatus"),
    videoSelectBtn: document.getElementById("videoSelectBtn"),
    videoSelectLabel: document.getElementById("videoSelectLabel"),
    videoSelectMenu: document.getElementById("videoSelectMenu"),
    videoSelectList: document.getElementById("videoSelectList"),
    videoSelectEmpty: document.getElementById("videoSelectEmpty"),
    openUploadFromMenu: document.getElementById("openUploadFromMenu"),
    uploadQuickBtn: document.getElementById("uploadQuickBtn"),
    toolsGrid: document.getElementById("toolsGrid"),
    toolConfig: document.getElementById("toolConfig"),
    toolConfigTitle: document.getElementById("toolConfigTitle"),
    toolConfigDesc: document.getElementById("toolConfigDesc"),
    toolConfigBody: document.getElementById("toolConfigBody"),
    addToolBtn: document.getElementById("addToolBtn"),
    closeToolConfig: document.getElementById("closeToolConfig"),
    editsSection: document.getElementById("editsSection"),
    editsList: document.getElementById("editsList"),
    editsCount: document.getElementById("editsCount"),
    editsEmpty: document.getElementById("editsEmpty"),
    processBtn: document.getElementById("processBtn"),
    processHint: document.getElementById("processHint"),
    previewBody: document.getElementById("previewBody"),
    previewModal: document.getElementById("previewModal"),
    previewModalBody: document.getElementById("previewModalBody"),
    previewModalBackdrop: document.getElementById("previewModalBackdrop"),
    closePreviewModal: document.getElementById("closePreviewModal"),
    expandPreviewBtn: document.getElementById("expandPreviewBtn"),
    toggleBtns: Array.from(document.querySelectorAll("[data-preview-mode]")),
    jobBody: document.getElementById("jobBody"),
    historyBody: document.getElementById("historyBody"),
    uploadModal: document.getElementById("uploadModal"),
    uploadModalBackdrop: document.getElementById("uploadModalBackdrop"),
    closeUploadModal: document.getElementById("closeUploadModal"),
    dropzone: document.getElementById("dropzone"),
    fileInput: document.getElementById("fileInput"),
    browseBtn: document.getElementById("browseBtn"),
    uploadProgress: document.getElementById("uploadProgress"),
    uploadProgressFill: document.getElementById("uploadProgressFill"),
    uploadStatusText: document.getElementById("uploadStatusText"),
    uploadPercent: document.getElementById("uploadPercent"),
    toastContainer: document.getElementById("toastContainer"),
  };

  const state = {
    videos: new Map(),
    selectedId: null,
    edits: [],
    activeTool: null,
    toolConfig: {},
    latestJob: null,
    history: [],
    previewMode: "original",
  };

  function iconSvg(name) {
    if (name === "trim") return '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.6"><path d="M6 6v8M14 6v8M3 10h14" stroke-linecap="round"/></svg>';
    if (name === "speed") return '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.6"><path d="M4 10a6 6 0 1 1 12 0 6 6 0 0 1-12 0Zm6-3v3l2 2" stroke-linecap="round" stroke-linejoin="round"/></svg>';
    if (name === "volume") return '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.6"><path d="M4 8v4h3l4 4V4L7 8H4Zm11 1a3 3 0 0 1 0 2" stroke-linecap="round"/></svg>';
    if (name === "flip") return '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.6"><path d="M7 5 4 8h2v7h2V8h2L7 5Zm6 10 3-3h-2V5h-2v7h-2l3 3Z" stroke-linecap="round" stroke-linejoin="round"/></svg>';
    if (name === "audio") return '<svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.6"><path d="M9 6v8a2 2 0 1 1-2-2V7l8-2v7a2 2 0 1 1-2-2V3L9 6Z"/></svg>';
    return "";
  }

  function setTheme(mode) {
    const root = document.documentElement;
    if (mode === "system") {
      const prefersDark = window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches;
      root.dataset.theme = prefersDark ? "dark" : "light";
      try { localStorage.setItem("motion:theme", "system"); } catch (e) {}
      return;
    }
    root.dataset.theme = mode;
    try { localStorage.setItem("motion:theme", mode); } catch (e) {}
  }

  function initTheme() {
    if (!els.themeToggle) return;
    els.themeToggle.addEventListener("click", () => {
      const current = document.documentElement.dataset.theme || "dark";
      setTheme(current === "dark" ? "light" : "dark");
    });
    if (window.matchMedia) {
      window.matchMedia("(prefers-color-scheme: dark)").addEventListener("change", (e) => {
        try {
          if (localStorage.getItem("motion:theme") === "system") {
            document.documentElement.dataset.theme = e.matches ? "dark" : "light";
          }
        } catch (err) {}
      });
    }
  }

  function toast(msg, type = "info") {
    if (!els.toastContainer) return;
    const el = document.createElement("div");
    el.className = `toast ${type}`;
    el.textContent = msg;
    els.toastContainer.appendChild(el);
    setTimeout(() => el.remove(), 2500);
  }

  function escapeHtml(s) {
    return String(s || "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  }

  async function loadUser() {
    try {
      const res = await apiFetch("/api/auth/me");
      if (!res.ok) return;
      const data = await res.json();
      if (els.userInfo && data.user?.email) els.userInfo.textContent = data.user.email;
    } catch (e) {}
  }

  function setBackendState(state) {
    if (!els.backendStatus) return;
    els.backendStatus.dataset.state = state;
  }

  async function checkBackend() {
    try {
      const res = await apiFetch("/health", { cache: "no-store" });
      setBackendState(res.ok ? "online" : "offline");
    } catch (e) {
      setBackendState("offline");
    }
  }

  function renderTools() {
    if (!els.toolsGrid) return;
    els.toolsGrid.innerHTML = "";
    for (const def of OPERATION_DEFS) {
      const btn = document.createElement("button");
      btn.className = "tool-card" + (state.activeTool === def.type ? " is-selected" : "");
      btn.type = "button";
      btn.setAttribute("role", "radio");
      btn.setAttribute("aria-checked", state.activeTool === def.type ? "true" : "false");
      btn.innerHTML = `<div class="tool-icon">${iconSvg(def.icon)}</div><div class="tool-name">${escapeHtml(def.name)}</div><div class="tool-desc">${escapeHtml(def.desc)}</div>`;
      btn.addEventListener("click", () => selectTool(def));
      els.toolsGrid.appendChild(btn);
    }
  }

  function selectTool(def) {
    state.activeTool = def.type;
    state.toolConfig = {};
    renderTools();
    showToolConfig(def);
  }

  function showToolConfig(def) {
    if (!els.toolConfig) return;
    els.toolConfig.classList.remove("hidden");
    if (els.toolConfigTitle) els.toolConfigTitle.textContent = def.name;
    if (els.toolConfigDesc) els.toolConfigDesc.textContent = def.desc;
    if (els.toolConfigBody) {
      els.toolConfigBody.innerHTML = renderConfigFields(def);
    }
  }

  function renderConfigFields(def) {
    if (def.type === "trim") {
      return `<div class="field"><label class="field-label" for="trimStart">Start time</label><input class="input" id="trimStart" placeholder="00:00:00" /><div class="field-hint">Seconds or HH:MM:SS</div></div><div class="field"><label class="field-label" for="trimEnd">End time</label><input class="input" id="trimEnd" placeholder="00:00:10" /></div>`;
    }
    if (def.type === "speed") {
      return `<div class="field"><label class="field-label" for="speedFactor">Speed</label><select class="select" id="speedFactor"><option value="0.5">0.5× (slower)</option><option value="0.75">0.75×</option><option value="1">1.0×</option><option value="1.25">1.25×</option><option value="1.5">1.5×</option><option value="2">2.0× (faster)</option></select></div>`;
    }
    if (def.type === "volume") {
      return `<div class="field"><label class="field-label" for="volFactor">Volume</label><select class="select" id="volFactor"><option value="0.5">50%</option><option value="1">100%</option><option value="1.5">150%</option><option value="2">200%</option></select></div>`;
    }
    if (def.type === "flip") {
      return `<div class="field"><label class="field-label" for="flipDir">Direction</label><select class="select" id="flipDir"><option value="1">Horizontal (mirror)</option><option value="0">Vertical (upside down)</option></select></div>`;
    }
    if (def.type === "to_audio") {
      return `<div class="field"><span class="field-label">Result</span><div class="field-note">Extracts audio as MP3. This should be the last edit.</div></div>`;
    }
    return "";
  }

  if (els.addToolBtn) {
    els.addToolBtn.addEventListener("click", () => {
      const def = OPERATION_DEFS.find((d) => d.type === state.activeTool);
      if (!def) return;
      if (state.edits.length >= MAX_OPERATIONS) {
        toast("Maximum 10 edits", "warn");
        return;
      }
      const edit = { id: Date.now().toString(), type: def.type, name: def.name };
      if (def.type === "trim") {
        edit.start = document.getElementById("trimStart")?.value || "";
        edit.end = document.getElementById("trimEnd")?.value || "";
        if (!edit.start || !edit.end) {
          toast("Add start and end times", "warn");
          return;
        }
      } else if (def.type === "speed") {
        edit.factor = parseFloat(document.getElementById("speedFactor")?.value || "1");
      } else if (def.type === "volume") {
        edit.factor = parseFloat(document.getElementById("volFactor")?.value || "1");
      } else if (def.type === "flip") {
        edit.orientation = parseInt(document.getElementById("flipDir")?.value || "1", 10);
      }
      state.edits.push(edit);
      if (els.toolConfig) els.toolConfig.classList.add("hidden");
      state.activeTool = null;
      renderTools();
      renderEdits();
      updateProcessState();
    });
  }

  if (els.closeToolConfig) {
    els.closeToolConfig.addEventListener("click", () => {
      if (els.toolConfig) els.toolConfig.classList.add("hidden");
      state.activeTool = null;
      renderTools();
    });
  }

  function renderEdits() {
    if (!els.editsList) return;
    els.editsList.innerHTML = "";
    if (els.editsCount) els.editsCount.textContent = `${state.edits.length} / ${MAX_OPERATIONS}`;
    if (els.editsEmpty) els.editsEmpty.classList.toggle("hidden", state.edits.length > 0);
    state.edits.forEach((edit, idx) => {
      const item = document.createElement("div");
      item.className = "edit-item";
      let sub = "";
      if (edit.type === "trim") sub = `${edit.start} → ${edit.end}`;
      else if (edit.type === "speed") sub = `${edit.factor}×`;
      else if (edit.type === "volume") sub = `${Math.round(edit.factor * 100)}%`;
      else if (edit.type === "flip") sub = edit.orientation === 1 ? "Horizontal" : "Vertical";
      else if (edit.type === "to_audio") sub = "MP3";
      item.innerHTML = `<div class="edit-info"><div class="edit-title">${idx + 1}. ${escapeHtml(edit.name)}</div><div class="edit-sub">${escapeHtml(sub)}</div></div><div class="edit-actions"><button class="edit-action" data-act="up">Up</button><button class="edit-action" data-act="down">Down</button><button class="edit-action" data-act="remove">Remove</button></div>`;
      item.querySelector('[data-act="remove"]').addEventListener("click", () => {
        state.edits.splice(idx, 1);
        renderEdits();
        updateProcessState();
      });
      item.querySelector('[data-act="up"]').addEventListener("click", () => {
        if (idx === 0) return;
        const tmp = state.edits[idx - 1];
        state.edits[idx - 1] = state.edits[idx];
        state.edits[idx] = tmp;
        renderEdits();
        updateProcessState();
      });
      item.querySelector('[data-act="down"]').addEventListener("click", () => {
        if (idx === state.edits.length - 1) return;
        const tmp = state.edits[idx + 1];
        state.edits[idx + 1] = state.edits[idx];
        state.edits[idx] = tmp;
        renderEdits();
        updateProcessState();
      });
      els.editsList.appendChild(item);
    });
  }

  function updateProcessState() {
    if (!els.processBtn) return;
    const video = state.videos.get(state.selectedId);
    if (!video || state.edits.length === 0) {
      els.processBtn.disabled = true;
      if (els.processHint) els.processHint.textContent = "Add at least one edit to process.";
      return;
    }
    els.processBtn.disabled = false;
    if (els.processHint) els.processHint.textContent = "Ready to export.";
  }

  async function loadVideos() {
    try {
      const res = await apiFetch("/videos", { cache: "no-store" });
      if (!res.ok) return;
      const data = await res.json();
      state.videos.clear();
      for (const v of data) {
        state.videos.set(v.id, v);
      }
      renderVideoSelector();
    } catch (e) {}
  }

  function renderVideoSelector() {
    if (!els.videoSelectList) return;
    els.videoSelectList.innerHTML = "";
    const items = Array.from(state.videos.values()).sort((a, b) => (b.createdAt || "").localeCompare(a.createdAt || ""));
    if (items.length === 0 && els.videoSelectEmpty) {
      els.videoSelectEmpty.classList.remove("hidden");
    } else if (els.videoSelectEmpty) {
      els.videoSelectEmpty.classList.add("hidden");
    }
    for (const v of items) {
      const btn = document.createElement("button");
      btn.className = "video-select-item" + (v.id === state.selectedId ? " is-active" : "");
      btn.type = "button";
      btn.innerHTML = `<div class="video-select-name">${escapeHtml(v.name || v.id)}</div><div class="video-select-meta">${v.status || ""}</div>`;
      btn.addEventListener("click", () => {
        selectVideo(v.id);
        if (els.videoSelectMenu) els.videoSelectMenu.classList.add("hidden");
        if (els.videoSelectBtn) els.videoSelectBtn.setAttribute("aria-expanded", "false");
      });
      els.videoSelectList.appendChild(btn);
    }
  }

  function selectVideo(id) {
    state.selectedId = id;
    const v = state.videos.get(id);
    if (els.videoSelectLabel) els.videoSelectLabel.textContent = v?.name || id;
    state.edits = [];
    state.latestJob = null;
    state.history = [];
    renderEdits();
    updateProcessState();
    renderPreview();
    renderJob();
    loadHistory(id);
    renderVideoSelector();
  }

  if (els.videoSelectBtn) {
    els.videoSelectBtn.addEventListener("click", () => {
      if (!els.videoSelectMenu) return;
      const open = !els.videoSelectMenu.classList.contains("hidden");
      els.videoSelectMenu.classList.toggle("hidden", open);
      els.videoSelectBtn.setAttribute("aria-expanded", open ? "false" : "true");
    });
  }
  document.addEventListener("click", (e) => {
    if (!els.videoSelectBtn || !els.videoSelectMenu) return;
    if (!e.target.closest(".video-selector")) {
      els.videoSelectMenu.classList.add("hidden");
      els.videoSelectBtn.setAttribute("aria-expanded", "false");
    }
  });

  function openUpload() {
    if (els.uploadModal) els.uploadModal.classList.remove("hidden");
  }
  function closeUpload() {
    if (els.uploadModal) els.uploadModal.classList.add("hidden");
  }
  if (els.uploadQuickBtn) els.uploadQuickBtn.addEventListener("click", openUpload);
  if (els.openUploadFromMenu) els.openUploadFromMenu.addEventListener("click", () => { if (els.videoSelectMenu) els.videoSelectMenu.classList.add("hidden"); openUpload(); });
  if (els.closeUploadModal) els.closeUploadModal.addEventListener("click", closeUpload);
  if (els.uploadModalBackdrop) els.uploadModalBackdrop.addEventListener("click", closeUpload);
  if (els.browseBtn && els.fileInput) {
    els.browseBtn.addEventListener("click", () => els.fileInput.click());
  }

  if (els.dropzone && els.fileInput) {
    els.dropzone.addEventListener("click", (e) => {
      if (e.target.closest("button")) return;
      els.fileInput.click();
    });
    els.dropzone.addEventListener("dragover", (e) => { e.preventDefault(); });
    els.dropzone.addEventListener("drop", (e) => {
      e.preventDefault();
      if (e.dataTransfer.files.length) {
        uploadFile(e.dataTransfer.files[0]);
      }
    });
    els.fileInput.addEventListener("change", () => {
      if (els.fileInput.files.length) uploadFile(els.fileInput.files[0]);
    });
  }

  async function uploadFile(file) {
    if (!file) return;
    if (file.size > 150 * 1024 * 1024) {
      toast("File too large (150 MB max)", "error");
      return;
    }
    if (els.uploadProgress) {
      els.uploadProgress.classList.remove("hidden");
      if (els.uploadProgressFill) els.uploadProgressFill.style.width = "10%";
      if (els.uploadPercent) els.uploadPercent.textContent = "10%";
      if (els.uploadStatusText) els.uploadStatusText.textContent = "Uploading…";
    }
    const formData = new FormData();
    formData.append("video", file);
    try {
      const res = await apiFetch("/upload", { method: "POST", body: formData });
      if (els.uploadProgress) {
        if (els.uploadProgressFill) els.uploadProgressFill.style.width = "100%";
        if (els.uploadPercent) els.uploadPercent.textContent = "100%";
        if (els.uploadStatusText) els.uploadStatusText.textContent = res.ok ? "Done" : "Failed";
      }
      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        toast(err.error || "Upload failed", "error");
        setTimeout(() => els.uploadProgress?.classList.add("hidden"), 1200);
        return;
      }
      const data = await res.json();
      await loadVideos();
      if (data.id) selectVideo(data.id);
      toast("Uploaded", "success");
      setTimeout(() => { els.uploadProgress?.classList.add("hidden"); closeUpload(); }, 800);
    } catch (e) {
      toast("Upload failed", "error");
      setTimeout(() => els.uploadProgress?.classList.add("hidden"), 1200);
    }
  }

  function renderPreview() {
    if (!els.previewBody) return;
    const video = state.videos.get(state.selectedId);
    if (!video) {
      els.previewBody.innerHTML = '<div class="preview-empty">Select a video to preview</div>';
      return;
    }
    const canOutput = video.status === "completed" && video.hasOutput;
    const mode = state.previewMode === "output" && canOutput ? "output" : (state.previewMode === "output" && !canOutput ? "original" : state.previewMode);
    if (mode === "output" && canOutput) {
      els.previewBody.innerHTML = `<video src="/videos/${encodeURIComponent(video.id)}/output" controls playsinline></video>`;
    } else {
      els.previewBody.innerHTML = `<video src="/videos/${encodeURIComponent(video.id)}/original" controls playsinline></video>`;
    }
  }

  function renderPreviewModal() {
    if (!els.previewModalBody) return;
    const video = state.videos.get(state.selectedId);
    if (!video) {
      els.previewModalBody.innerHTML = '<div class="preview-empty">No video</div>';
      return;
    }
    const canOutput = video.status === "completed" && video.hasOutput;
    const mode = state.previewMode === "output" && canOutput ? "output" : (state.previewMode === "output" && !canOutput ? "original" : state.previewMode);
    if (mode === "output" && canOutput) {
      els.previewModalBody.innerHTML = `<video src="/videos/${encodeURIComponent(video.id)}/output" controls playsinline></video>`;
    } else {
      els.previewModalBody.innerHTML = `<video src="/videos/${encodeURIComponent(video.id)}/original" controls playsinline></video>`;
    }
  }

  els.toggleBtns.forEach((btn) => {
    btn.addEventListener("click", () => {
      els.toggleBtns.forEach((b) => b.classList.remove("active"));
      btn.classList.add("active");
      state.previewMode = btn.dataset.previewMode;
      renderPreview();
      renderPreviewModal();
    });
  });

  if (els.expandPreviewBtn) {
    els.expandPreviewBtn.addEventListener("click", () => {
      if (els.previewModal) {
        els.previewModal.classList.remove("hidden");
        renderPreviewModal();
      }
    });
  }
  if (els.closePreviewModal) els.closePreviewModal.addEventListener("click", () => els.previewModal?.classList.add("hidden"));
  if (els.previewModalBackdrop) els.previewModalBackdrop.addEventListener("click", () => els.previewModal?.classList.add("hidden"));

  async function loadHistory(id) {
    try {
      const res = await apiFetch(`/jobs?videoId=${encodeURIComponent(id)}`, { cache: "no-store" });
      if (!res.ok) return;
      const data = await res.json();
      state.history = Array.isArray(data) ? data : [];
      if (state.history.length > 0) state.latestJob = state.history[0];
      renderJob();
      renderHistory();
    } catch (e) {}
  }

  function renderHistory() {
    if (!els.historyBody) return;
    els.historyBody.innerHTML = "";
    if (state.history.length === 0) {
      els.historyBody.innerHTML = '<div class="results-empty">No history yet</div>';
      return;
    }
    for (const job of state.history.slice(0, 10)) {
      const item = document.createElement("div");
      item.className = "history-item";
      item.innerHTML = `<div class="history-top"><span class="history-status">${escapeHtml(job.status || "")}</span><span class="history-meta">${job.status === "completed" ? "Done" : ""}</span></div>`;
      const actions = document.createElement("div");
      actions.className = "history-actions";
      if (job.status === "completed" && job.hasOutput) {
        const dl = document.createElement("button");
        dl.className = "btn-text";
        dl.type = "button";
        dl.textContent = "Download";
        dl.addEventListener("click", () => downloadJob(job.id));
        actions.appendChild(dl);
        const pv = document.createElement("button");
        pv.className = "btn-text";
        pv.type = "button";
        pv.textContent = "Preview";
        pv.addEventListener("click", () => { state.previewMode = "output"; els.toggleBtns.forEach((b) => { b.classList.toggle("active", b.dataset.previewMode === "output"); }); renderPreview(); });
        actions.appendChild(pv);
      }
      if (actions.children.length > 0) item.appendChild(actions);
      els.historyBody.appendChild(item);
    }
  }

  function renderJob() {
    if (!els.jobBody) return;
    const video = state.videos.get(state.selectedId);
    const job = state.latestJob;
    if (!job || !video) {
      els.jobBody.innerHTML = '<p class="results-empty">Process your edits to see the result here.</p>';
      return;
    }
    if (job.status === "completed" && job.hasOutput) {
      els.jobBody.innerHTML = `<p class="results-empty">Ready</p><div class="result-actions"><button class="btn-primary" id="pvBtn" type="button">Preview result</button><button class="btn-secondary" id="dlBtn" type="button">Download</button></div>`;
      document.getElementById("pvBtn")?.addEventListener("click", () => { state.previewMode = "output"; els.toggleBtns.forEach((b) => { b.classList.toggle("active", b.dataset.previewMode === "output"); }); renderPreview(); if (els.previewModal) { els.previewModal.classList.remove("hidden"); renderPreviewModal(); } });
      document.getElementById("dlBtn")?.addEventListener("click", () => downloadJob(job.id));
    } else if (job.status === "failed") {
      els.jobBody.innerHTML = `<p class="results-empty">Failed</p><p class="results-empty">${escapeHtml(job.error || "")}</p>`;
    } else if (job.status === "processing" || job.status === "queued" || job.status === "cancel_requested") {
      els.jobBody.innerHTML = `<p class="results-empty">${job.status === "processing" ? "Processing" : job.status === "queued" ? "Queued" : "Cancelling"}</p><div class="progress-track"><div class="progress-fill ${job.status === "processing" ? "" : "indeterminate"}" style="width:${Math.max(5, Math.min(100, job.progress || 0))}%"></div></div>`;
    } else {
      els.jobBody.innerHTML = '<p class="results-empty">Process your edits to see the result here.</p>';
    }
  }

  async function downloadJob(id) {
    try {
      const res = await apiFetch(`/jobs/${encodeURIComponent(id)}/download`);
      if (!res.ok) throw new Error("failed");
      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = `motion-${id.slice(0, 8)}.mp4`;
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
    } catch (e) {
      toast("Download failed", "error");
    }
  }

  if (els.processBtn) {
    els.processBtn.addEventListener("click", async () => {
      const video = state.videos.get(state.selectedId);
      if (!video || state.edits.length === 0) return;
      els.processBtn.disabled = true;
      const ops = state.edits.map((e) => {
        if (e.type === "trim") return { type: "trim", start: e.start, end: e.end };
        if (e.type === "speed") return { type: "speed", factor: e.factor };
        if (e.type === "volume") return { type: "volume", factor: e.factor };
        if (e.type === "flip") return { type: "flip", orientation: e.orientation };
        if (e.type === "to_audio") return { type: "to_audio" };
        return { type: e.type };
      });
      try {
        const res = await apiFetch(`/videos/${encodeURIComponent(video.id)}/process`, { method: "POST", body: { operations: ops } });
        if (!res.ok) {
          const err = await res.json().catch(() => ({}));
          toast(err.error || "Failed to process", "error");
          updateProcessState();
          return;
        }
        toast("Processing started", "success");
        setTimeout(() => loadHistory(video.id), 500);
      } catch (e) {
        toast("Failed to process", "error");
        updateProcessState();
      }
    });
  }

  if (els.logoutBtnTop) {
    els.logoutBtnTop.addEventListener("click", async () => {
      try {
        await apiFetch("/api/auth/logout", { method: "POST" });
      } catch (e) {}
      window.location.href = "/login.html";
    });
  }

  initTheme();
  loadUser();
  checkBackend();
  renderTools();
  renderEdits();
  loadVideos();
  setInterval(checkBackend, 30000);
})();
