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
  return fetch(url, options).then((res) => {
    // Protected API calls only return 401 when the session expired mid-use;
    // send the user back to login instead of failing with a cryptic error.
    if (res.status === 401
        && !/^\/api\/auth(\/|$)/.test(url)
        && url !== "/api/config"
        && url !== "/health"
        && !/(login|register|reset|index|landing)\.html/.test(location.pathname)) {
      window.location.href = "/login.html";
    }
    return res;
  });
}

(() => {
  "use strict";

  const MAX_OPERATIONS = 20;
  const ALLOWED_EXTENSIONS = [".mp4", ".m4v", ".mov", ".mkv", ".webm", ".avi", ".mpeg", ".mpg", ".3gp"];
  const ACTIVE_STATUSES = ["queued", "processing", "cancel_requested"];
  const POLL_INTERVAL_MS = 1500;
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
    verifyBanner: document.getElementById("verifyBanner"),
    verifyResendBtn: document.getElementById("verifyResendBtn"),
    videoSelectBtn: document.getElementById("videoSelectBtn"),
    videoSelectLabel: document.getElementById("videoSelectLabel"),
    videoSelectMenu: document.getElementById("videoSelectMenu"),
    videoSelectList: document.getElementById("videoSelectList"),
    videoSelectEmpty: document.getElementById("videoSelectEmpty"),
    refreshLibraryBtn: document.getElementById("refreshLibraryBtn"),
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
    deleteVideoBtn: document.getElementById("deleteVideoBtn"),
    openEditorWithVideoBtn: document.getElementById("openEditorWithVideoBtn"),
    toastContainer: document.getElementById("toastContainer"),
  };

  const state = {
    videos: new Map(),
    selectedId: null,
    edits: [],
    activeTool: null,
    toolConfig: {},
    latestJob: null,
    selectedJobId: null,
    history: [],
    previewMode: "original",
    pollTimer: null,
    verifying: false,
    currentUser: null,
    requireEmailVerification: false,
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
      if (!res.ok) return false;
      const data = await res.json();
      if (els.userInfo && data.user?.email) els.userInfo.textContent = data.user.email;
      state.currentUser = data.user || null;
      renderVerifyBanner(state.currentUser);
      return !!state.currentUser;
    } catch (e) {
      return false;
    }
  }

  async function loadConfig() {
    try {
      const res = await apiFetch("/api/config", { cache: "no-store" });
      if (!res.ok) return;
      const data = await res.json();
      state.requireEmailVerification = !!data.requireEmailVerification;
      renderVerifyBanner(state.currentUser);
    } catch (e) {}
  }

  function renderVerifyBanner(user) {
    if (!els.verifyBanner) return;
    const required = state.requireEmailVerification && user && user.emailVerified === false;
    els.verifyBanner.classList.toggle("hidden", !required);
  }

  function setBackendState(value) {
    if (!els.backendStatus) return;
    els.backendStatus.dataset.state = value;
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
      const res = await apiFetch("/api/videos", { cache: "no-store" });
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
      btn.innerHTML = `<div class="video-select-name">${escapeHtml(v.name || v.id)}</div><div class="video-select-meta">${escapeHtml(v.status || "")}</div>`;
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
    state.selectedJobId = null;
    state.history = [];
    stopPolling();
    renderEdits();
    updateProcessState();
    renderPreview();
    renderJob();
    renderHistory();
    loadHistory(id);
    renderVideoSelector();
    updateVideoActions();
  }

  function updateVideoActions() {
    if (els.deleteVideoBtn) els.deleteVideoBtn.disabled = !state.selectedId;
    if (els.openEditorWithVideoBtn) els.openEditorWithVideoBtn.disabled = !state.selectedId;
  }

  async function deleteSelectedVideo() {
    if (!state.selectedId) return;
    const v = state.videos.get(state.selectedId);
    if (!confirm("Delete " + ((v && v.name) || "this video") + "? This cannot be undone.")) return;
    const id = state.selectedId;
    try {
      const res = await apiFetch("/api/videos/" + encodeURIComponent(id), { method: "DELETE" });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) {
        toast(data.error || "Could not delete video", "error");
        return;
      }
      toast("Video deleted", "success");
      state.selectedId = null;
      state.edits = [];
      state.history = [];
      state.latestJob = null;
      state.selectedJobId = null;
      stopPolling();
      if (els.videoSelectLabel) els.videoSelectLabel.textContent = "Choose or upload a video";
      renderEdits();
      updateProcessState();
      renderPreview();
      renderJob();
      renderHistory();
      updateVideoActions();
      await loadVideos();
    } catch (e) {
      toast("Could not delete video", "error");
    }
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
  if (els.refreshLibraryBtn) {
    els.refreshLibraryBtn.addEventListener("click", async () => {
      await loadVideos();
      if (state.selectedId) await loadHistory(state.selectedId);
      toast("Library refreshed", "success");
    });
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

  function hasAllowedExtension(name) {
    const idx = String(name || "").lastIndexOf(".");
    if (idx < 0) return false;
    return ALLOWED_EXTENSIONS.includes(name.slice(idx).toLowerCase());
  }

  // Upload via XHR so real transfer progress can be shown; fetch cannot report upload progress.
  function uploadWithProgress(formData, onProgress) {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open("POST", "/api/upload");
      const csrf = getCsrfToken();
      if (csrf) {
        xhr.setRequestHeader("X-CSRF-Token", csrf);
      }
      xhr.upload.addEventListener("progress", (e) => {
        if (e.lengthComputable) {
          onProgress(Math.min(99, Math.round((e.loaded / e.total) * 100)));
        }
      });
      xhr.addEventListener("load", () => {
        let body = null;
        try { body = JSON.parse(xhr.responseText); } catch (e) { body = null; }
        resolve({
          ok: xhr.status >= 200 && xhr.status < 300,
          status: xhr.status,
          json: async () => body || {},
        });
      });
      xhr.addEventListener("error", () => reject(new Error("network error")));
      xhr.addEventListener("abort", () => reject(new Error("aborted")));
      xhr.send(formData);
    });
  }

  async function uploadFile(file) {
    if (!file) return;
    if (!hasAllowedExtension(file.name)) {
      toast("Unsupported file type. Use MP4, MOV, MKV, WebM, AVI, M4V, MPEG or 3GP.", "error");
      return;
    }
    if (file.size > 150 * 1024 * 1024) {
      toast("File too large (150 MB max)", "error");
      return;
    }
    if (els.uploadProgress) {
      els.uploadProgress.classList.remove("hidden");
      if (els.uploadProgressFill) els.uploadProgressFill.style.width = "0%";
      if (els.uploadPercent) els.uploadPercent.textContent = "0%";
      if (els.uploadStatusText) els.uploadStatusText.textContent = "Uploading…";
    }
    const setProgress = (pct, label) => {
      if (els.uploadProgressFill) els.uploadProgressFill.style.width = pct + "%";
      if (els.uploadPercent) els.uploadPercent.textContent = pct + "%";
      if (label && els.uploadStatusText) els.uploadStatusText.textContent = label;
    };
    const formData = new FormData();
    formData.append("video", file);
    try {
      const res = await uploadWithProgress(formData, (pct) => setProgress(pct, "Uploading…"));
      // Server work (probe + optional encryption) happens after the last byte arrives.
      setProgress(100, res.ok ? "Done" : "Failed");
      if (!res.ok) {
        const err = await res.json();
        if (res.status === 401) {
          window.location.href = "/login.html";
          return;
        }
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
      setProgress(0, "Failed");
      toast("Upload failed", "error");
      setTimeout(() => els.uploadProgress?.classList.add("hidden"), 1200);
    }
  }

  // Resolve the media element and URL for the current preview mode. When a
  // specific completed job is selected, preview that job's output rather than
  // the video's latest output.
  function resolvePreviewSource() {
    const video = state.videos.get(state.selectedId);
    if (!video) return null;

    if (state.previewMode === "output") {
      if (state.selectedJobId) {
        const job = state.history.find((j) => j.id === state.selectedJobId);
        if (job && job.status === "completed" && job.hasOutput) {
          return { url: `/api/jobs/${encodeURIComponent(job.id)}/output`, type: job.outputType || "video" };
        }
      }
      if (video.status === "completed" && video.hasOutput) {
        return { url: `/api/videos/${encodeURIComponent(video.id)}/output`, type: video.outputType || "video" };
      }
    }
    return { url: `/api/videos/${encodeURIComponent(video.id)}/original`, type: "video" };
  }

  function previewMarkup(source) {
    if (source.type === "audio") {
      return `<audio src="${source.url}" controls playsinline></audio>`;
    }
    return `<video src="${source.url}" controls playsinline></video>`;
  }

  function renderPreview() {
    if (!els.previewBody) return;
    const video = state.videos.get(state.selectedId);
    if (!video) {
      els.previewBody.innerHTML = '<div class="preview-empty">Select a video to preview</div>';
      return;
    }
    els.previewBody.innerHTML = previewMarkup(resolvePreviewSource());
  }

  function renderPreviewModal() {
    if (!els.previewModalBody) return;
    const video = state.videos.get(state.selectedId);
    if (!video) {
      els.previewModalBody.innerHTML = '<div class="preview-empty">No video</div>';
      return;
    }
    els.previewModalBody.innerHTML = previewMarkup(resolvePreviewSource());
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

  function hasActiveJobs() {
    return state.history.some((j) => ACTIVE_STATUSES.includes(j.status));
  }

  function stopPolling() {
    if (state.pollTimer) {
      clearInterval(state.pollTimer);
      state.pollTimer = null;
    }
  }

  function startPolling() {
    if (state.pollTimer) return;
    if (!hasActiveJobs()) return;
    state.pollTimer = setInterval(async () => {
      const id = state.selectedId;
      if (!id) {
        stopPolling();
        return;
      }
      await loadHistory(id, { poll: true });
      if (!hasActiveJobs()) {
        stopPolling();
        await loadVideos();
        renderPreview();
        renderVideoSelector();
      }
    }, POLL_INTERVAL_MS);
  }

  async function loadHistory(id, options = {}) {
    try {
      const res = await apiFetch(`/api/jobs?videoId=${encodeURIComponent(id)}`, { cache: "no-store" });
      if (!res.ok) return;
      const data = await res.json();
      state.history = Array.isArray(data) ? data : [];
      if (state.history.length > 0) {
        // Prefer the newest active job as the "latest" result; otherwise the
        // newest job.
        state.latestJob = state.history.find((j) => ACTIVE_STATUSES.includes(j.status)) || state.history[0];
      } else {
        state.latestJob = null;
      }
      if (state.selectedJobId && !state.history.some((j) => j.id === state.selectedJobId)) {
        state.selectedJobId = null;
      }
      renderJob();
      renderHistory();
      renderPreview();
      if (options.poll) return;
      if (hasActiveJobs()) startPolling();
    } catch (e) {}
  }

  async function cancelJob(id) {
    try {
      const res = await apiFetch(`/api/jobs/${encodeURIComponent(id)}/cancel`, { method: "POST" });
      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        toast(err.error || "Could not cancel job", "error");
        return;
      }
      toast("Cancellation requested", "success");
      if (state.selectedId) {
        await loadHistory(state.selectedId);
      }
    } catch (e) {
      toast("Could not cancel job", "error");
    }
  }

  async function deleteJob(id) {
    try {
      const res = await apiFetch(`/api/jobs/${encodeURIComponent(id)}`, { method: "DELETE" });
      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        toast(err.error || "Could not delete job", "error");
        return;
      }
      toast("Job deleted", "success");
      if (state.selectedId) {
        await loadHistory(state.selectedId);
      }
      await loadVideos();
    } catch (e) {
      toast("Could not delete job", "error");
    }
  }

  function selectJobPreview(id) {
    state.selectedJobId = id;
    state.previewMode = "output";
    els.toggleBtns.forEach((b) => b.classList.toggle("active", b.dataset.previewMode === "output"));
    renderPreview();
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
        pv.addEventListener("click", () => selectJobPreview(job.id));
        actions.appendChild(pv);
      }
      if (ACTIVE_STATUSES.includes(job.status)) {
        const cancel = document.createElement("button");
        cancel.className = "btn-text";
        cancel.type = "button";
        cancel.textContent = "Cancel";
        cancel.disabled = job.status === "cancel_requested";
        cancel.addEventListener("click", () => cancelJob(job.id));
        actions.appendChild(cancel);
      } else if (job.status === "completed" || job.status === "failed" || job.status === "cancelled") {
        const del = document.createElement("button");
        del.className = "btn-text";
        del.type = "button";
        del.textContent = "Delete";
        del.addEventListener("click", () => deleteJob(job.id));
        actions.appendChild(del);
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
      document.getElementById("pvBtn")?.addEventListener("click", () => { selectJobPreview(job.id); if (els.previewModal) { els.previewModal.classList.remove("hidden"); renderPreviewModal(); } });
      document.getElementById("dlBtn")?.addEventListener("click", () => downloadJob(job.id));
    } else if (job.status === "failed") {
      els.jobBody.innerHTML = `<p class="results-empty">Failed</p><p class="results-empty">${escapeHtml(job.error || "")}</p>`;
    } else if (ACTIVE_STATUSES.includes(job.status)) {
      const label = job.status === "processing" ? "Processing" : job.status === "queued" ? "Queued" : "Cancelling";
      els.jobBody.innerHTML = `<p class="results-empty">${label}</p><div class="progress-track"><div class="progress-fill ${job.status === "processing" ? "" : "indeterminate"}" style="width:${Math.max(5, Math.min(100, job.progress || 0))}%"></div></div><div class="result-actions"><button class="btn-secondary" id="cancelJobBtn" type="button">Cancel</button></div>`;
      document.getElementById("cancelJobBtn")?.addEventListener("click", () => cancelJob(job.id));
    } else {
      els.jobBody.innerHTML = '<p class="results-empty">Process your edits to see the result here.</p>';
    }
  }

  function filenameFromDisposition(disposition, fallback) {
    if (!disposition) return fallback;
    const utf8 = disposition.match(/filename\*=UTF-8''([^;]+)/i);
    if (utf8) {
      try { return decodeURIComponent(utf8[1]); } catch (e) { return fallback; }
    }
    const plain = disposition.match(/filename="?([^";]+)"?/i);
    if (plain) return plain[1];
    return fallback;
  }

  async function downloadJob(id) {
    try {
      const res = await apiFetch(`/api/jobs/${encodeURIComponent(id)}/download`);
      if (!res.ok) throw new Error("failed");
      const job = state.history.find((j) => j.id === id);
      const fallbackExt = job && job.outputType === "audio" ? ".mp3" : ".mp4";
      const fallback = `motion-${id.slice(0, 8)}${fallbackExt}`;
      const filename = filenameFromDisposition(res.headers.get("Content-Disposition"), fallback);
      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = filename;
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
        const res = await apiFetch(`/api/videos/${encodeURIComponent(video.id)}/process`, { method: "POST", body: { operations: ops } });
        if (!res.ok) {
          const err = await res.json().catch(() => ({}));
          toast(err.error || "Failed to process", "error");
          updateProcessState();
          return;
        }
        state.selectedJobId = null;
        toast("Processing started", "success");
        // The server reset this video to processing with no output; mirror that
        // locally so preview rendering does not briefly request a stale output.
        const localVideo = state.videos.get(video.id);
        if (localVideo) {
          localVideo.status = "processing";
          localVideo.hasOutput = false;
          localVideo.outputType = "";
          localVideo.progress = 0;
        }
        await loadHistory(video.id);
        startPolling();
      } catch (e) {
        toast("Failed to process", "error");
        updateProcessState();
      }
    });
  }

  if (els.verifyResendBtn) {
    els.verifyResendBtn.addEventListener("click", async () => {
      if (state.verifying) return;
      state.verifying = true;
      els.verifyResendBtn.disabled = true;
      try {
        const res = await apiFetch("/api/auth/verify/resend", { method: "POST" });
        const data = await res.json().catch(() => ({}));
        toast(res.ok ? (data.message || "Verification email sent") : (data.error || "Could not send verification email"), res.ok ? "success" : "error");
      } catch (e) {
        toast("Could not send verification email", "error");
      } finally {
        state.verifying = false;
        els.verifyResendBtn.disabled = false;
      }
    });
  }

  // Logout is handled once by the shared auth.js (doLogout), which all pages
  // include; do not attach a second, conflicting navigation here.

  window.addEventListener("beforeunload", stopPolling);

  if (els.deleteVideoBtn) els.deleteVideoBtn.addEventListener("click", deleteSelectedVideo);
  if (els.openEditorWithVideoBtn) {
    els.openEditorWithVideoBtn.addEventListener("click", () => {
      if (!state.selectedId) return;
      window.location.href = "/editor.html?video=" + encodeURIComponent(state.selectedId);
    });
  }

  initTheme();
  renderTools();
  renderEdits();
  // Only bootstrap authenticated data loads: auth-guard.js redirects anonymous
  // visitors to the login page, so firing library/config requests first would
  // only produce console noise.
  (async () => {
    const authed = await loadUser();
    if (!authed) return;
    loadConfig();
    loadVideos();
  })();
  checkBackend();
  setInterval(checkBackend, 30000);
})();
