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
  return fetch(url, options);
}

(() => {
  "use strict";

  const MAX_OPERATIONS = 10;
  const MIN_SPEED = 0.5;
  const MAX_SPEED = 2.0;
  const MAX_UPLOAD_BYTES = 150 * 1024 * 1024;
  const ACTIVE_STATUSES = new Set(["queued", "processing", "cancel_requested"]);
  const SELECTION_KEY = "motion:selected-video";

  const ALLOWED_EXTENSIONS = new Set([
    ".mp4",
    ".m4v",
    ".mov",
    ".mkv",
    ".webm",
    ".avi",
    ".mpeg",
    ".mpg",
    ".3gp",
  ]);

  const STATUS_LABELS = {
    uploaded: "Ready",
    queued: "Queued",
    processing: "Processing",
    cancel_requested: "Cancelling",
    cancelled: "Cancelled",
    completed: "Completed",
    failed: "Failed",
  };

  const OPERATION_LABELS = {
    trim: "Trim",
    speed: "Change speed",
    volume: "Adjust volume",
    flip: "Flip",
    to_audio: "Extract audio",
  };

  const OPERATION_OPTIONS = [
    ["trim", "Trim"],
    ["speed", "Change speed"],
    ["volume", "Adjust volume"],
    ["flip", "Flip"],
    ["to_audio", "Extract audio (MP3)"],
  ];

  const els = {
    backendStatus: document.getElementById("backendStatus"),
    backendStatusText: document.getElementById("backendStatusText"),
    refreshBtn: document.getElementById("refreshBtn"),
    dropzone: document.getElementById("dropzone"),
    fileInput: document.getElementById("fileInput"),
    uploadProgress: document.getElementById("uploadProgress"),
    uploadProgressFill: document.getElementById("uploadProgressFill"),
    uploadStatusText: document.getElementById("uploadStatusText"),
    uploadPercent: document.getElementById("uploadPercent"),
    libraryCount: document.getElementById("libraryCount"),
    libraryList: document.getElementById("libraryList"),
    libraryEmpty: document.getElementById("libraryEmpty"),
    libraryError: document.getElementById("libraryError"),
    workspaceEmpty: document.getElementById("workspaceEmpty"),
    workspaceContent: document.getElementById("workspaceContent"),
    workspaceTitle: document.getElementById("workspaceTitle"),
    workspaceSub: document.getElementById("workspaceSub"),
    previewBody: document.getElementById("previewBody"),
    operationsBody: document.getElementById("operationsBody"),
    jobBody: document.getElementById("jobBody"),
    toastContainer: document.getElementById("toastContainer"),
  };

  let uploadStateTimer = null;

  const state = {
    videos: new Map(),
    jobs: new Map(),
    latestJobIds: new Map(),
    selectedId: null,
    pipelines: new Map(),
    previewModes: new Map(),
    pollTimers: new Map(),
    submittingId: null,
    uploading: false,
    backend: "checking",
    addType: "trim",
  };

  // ───────────────────────── Helpers ─────────────────────────

  function escapeHTML(value) {
    return String(value ?? "").replace(/[&<>"']/g, (char) => {
      switch (char) {
        case "&":
          return "&amp;";
        case "<":
          return "&lt;";
        case ">":
          return "&gt;";
        case '"':
          return "&quot;";
        default:
          return "&#39;";
      }
    });
  }

  function clamp(value, min, max) {
    return Math.min(max, Math.max(min, value));
  }

  function formatDuration(seconds) {
    const value = Number(seconds);
    if (!Number.isFinite(value) || value < 0) return "—";
    if (value === 0) return "00:00";
    const total = Math.floor(value);
    const hours = Math.floor(total / 3600);
    const minutes = Math.floor((total % 3600) / 60);
    const secs = total % 60;

    if (hours > 0) {
      return `${String(hours).padStart(2, "0")}:${String(minutes).padStart(2, "0")}:${String(secs).padStart(2, "0")}`;
    }
    return `${String(minutes).padStart(2, "0")}:${String(secs).padStart(2, "0")}`;
  }

  function formatTimecode(seconds, includeMilliseconds = false) {
    if (!Number.isFinite(seconds) || seconds < 0) return "00:00:00";
    const whole = Math.floor(seconds);
    const hours = Math.floor(whole / 3600);
    const minutes = Math.floor((whole % 3600) / 60);
    const secs = whole % 60;
    let result = `${String(hours).padStart(2, "0")}:${String(minutes).padStart(2, "0")}:${String(secs).padStart(2, "0")}`;

    if (includeMilliseconds) {
      const millis = Math.round((seconds - whole) * 1000);
      if (millis > 0) {
        result += `.${String(Math.min(millis, 999)).padStart(3, "0")}`;
      }
    }
    return result;
  }

  function formatSize(bytes) {
    const value = Number(bytes);
    if (!Number.isFinite(value) || value <= 0) return "—";
    const units = ["B", "KB", "MB", "GB"];
    let size = value;
    let index = 0;
    while (size >= 1024 && index < units.length - 1) {
      size /= 1024;
      index += 1;
    }
    const precision = index >= 2 ? 1 : 0;
    return `${size.toFixed(precision)} ${units[index]}`;
  }

  function formatDate(value) {
    if (!value) return "—";
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return "—";
    return date.toLocaleString(undefined, {
      dateStyle: "medium",
      timeStyle: "short",
    });
  }

  function shortId(id) {
    return id ? String(id).slice(0, 8) : "—";
  }

  function statusClass(status) {
    return Object.prototype.hasOwnProperty.call(STATUS_LABELS, status) ? status : "uploaded";
  }

  function statusLabel(status) {
    return STATUS_LABELS[status] || status || "Unknown";
  }

  function isActive(video) {
    return Boolean(video && ACTIVE_STATUSES.has(video.status));
  }

  function resolutionText(video) {
    if (!video || !video.width) return "—";
    return `${video.width} × ${video.height}`;
  }

  function mediaURL(id, kind) {
    return `/videos/${encodeURIComponent(id)}/${kind}`;
  }

  function jobMediaURL(id, kind) {
    return `/jobs/${encodeURIComponent(id)}/${kind}`;
  }

  function jobsForVideo(id) {
    return Array.from(state.jobs.values())
      .filter((job) => job.videoId === id)
      .sort((a, b) => new Date(b.createdAt || 0) - new Date(a.createdAt || 0));
  }

  function latestJobFor(id) {
    const latestID = state.latestJobIds.get(id);
    if (latestID) {
      const job = state.jobs.get(latestID);
      if (job && job.videoId === id) return job;
    }
    return jobsForVideo(id)[0] || null;
  }

  function isEditable(video) {
    return Boolean(video && !isActive(video) && video.hasOriginal);
  }

  function jobOutputExtension(job) {
    return job && job.outputType === "audio" ? ".mp3" : ".mp4";
  }

  function jobDownloadFilename(job) {
    const name = job?.videoName || job?.videoId || job?.id || "video";
    const dot = name.lastIndexOf(".");
    const base = dot > 0 ? name.slice(0, dot) : name;
    return `${base}-edited-${shortId(job.id)}${jobOutputExtension(job)}`;
  }

  function normalizeStoredOperation(op) {
    if (!op || typeof op !== "object") return null;
    if (op.type === "trim") {
      return { type: "trim", start: op.start || "", end: op.end || "" };
    }
    if (op.type === "speed" || op.type === "volume") {
      return { type: op.type, factor: op.factor };
    }
    if (op.type === "flip") {
      return { type: "flip", orientation: op.orientation };
    }
    return { type: op.type };
  }

  function selectedVideo() {
    return state.selectedId ? state.videos.get(state.selectedId) : null;
  }

  function pipelineFor(id) {
    if (!state.pipelines.has(id)) state.pipelines.set(id, []);
    return state.pipelines.get(id);
  }

  function parseTimeInput(value) {
    const raw = String(value ?? "").trim();
    if (!raw) return NaN;

    if (!raw.includes(":")) {
      const seconds = Number(raw);
      return Number.isFinite(seconds) && seconds >= 0 ? seconds : NaN;
    }

    const parts = raw.split(":");
    if (parts.length < 2 || parts.length > 3) return NaN;

    let total = 0;
    for (let index = 0; index < parts.length; index += 1) {
      const part = parts[index].trim();
      if (!part) return NaN;
      const number = Number(part);
      if (!Number.isFinite(number) || number < 0) return NaN;
      if (index > 0 && number >= 60) return NaN;
      total = total * 60 + number;
    }
    return total;
  }

  function readStoredSelection() {
    try {
      return localStorage.getItem(SELECTION_KEY);
    } catch {
      return null;
    }
  }

  function storeSelection(id) {
    try {
      if (id) localStorage.setItem(SELECTION_KEY, id);
      else localStorage.removeItem(SELECTION_KEY);
    } catch {
      // Storage may be unavailable in private browsing; selection still works
      // for the current page.
    }
  }

  async function readError(response) {
    let message = `Request failed with status ${response.status}`;
    try {
      const contentType = response.headers.get("content-type") || "";
      if (contentType.includes("application/json")) {
        const payload = await response.json();
        if (payload && typeof payload.error === "string" && payload.error.trim()) {
          message = payload.error.trim();
        }
      } else {
        const text = (await response.text()).trim();
        if (text) message = text;
      }
    } catch {
      // Keep the status-based fallback.
    }
    return message;
  }

  function toast(message, kind = "info", timeout = 4200) {
    const element = document.createElement("div");
    element.className = `toast ${kind}`;
    element.textContent = message;
    els.toastContainer.appendChild(element);

    window.setTimeout(() => {
      element.classList.add("out");
      window.setTimeout(() => element.remove(), 180);
    }, timeout);
  }

  function setBackend(status) {
    state.backend = status;
    els.backendStatus.dataset.state = status;
    if (status === "online") {
      els.backendStatusText.textContent = "Backend online";
    } else if (status === "offline") {
      els.backendStatusText.textContent = "Backend offline";
    } else {
      els.backendStatusText.textContent = "Checking backend…";
    }
  }

  function outputExtension(video) {
    return video && video.outputType === "audio" ? ".mp3" : ".mp4";
  }

  function downloadFilename(video) {
    const name = video.name || video.id || "video";
    const dot = name.lastIndexOf(".");
    const base = dot > 0 ? name.slice(0, dot) : name;
    return `${base}-edited${outputExtension(video)}`;
  }

  // ───────────────────────── Initialisation ─────────────────────────

  function init() {
    bindStaticEvents();
    loadAll();
  }

  function bindStaticEvents() {
    els.refreshBtn.addEventListener("click", () => loadAll(true));

    els.libraryError.addEventListener("click", (event) => {
      if (event.target.closest("[data-retry]")) loadAll(true);
    });

    els.libraryList.addEventListener("click", (event) => {
      const item = event.target.closest(".library-item");
      if (item && item.dataset.id) selectVideo(item.dataset.id);
    });

    els.operationsBody.addEventListener("click", handleOperationsClick);
    els.operationsBody.addEventListener("input", handleOperationsInput);
    els.operationsBody.addEventListener("change", handleOperationsChange);

    els.previewBody.addEventListener("click", handlePreviewClick);
    if (els.jobBody) {
      els.jobBody.addEventListener("click", handleJobClick);
    }
    document.addEventListener("click", (event) => {
      const jobPreview = event.target.closest("[data-job-preview]");
      if (jobPreview) {
        event.preventDefault();
        event.stopPropagation();
        const video = selectedVideo();
        if (video) {
          state.previewModes.set(video.id, jobPreview.dataset.jobPreview);
          renderPreview(video);
          try {
            const preview = document.querySelector('.preview-panel');
            if (preview) preview.scrollIntoView({ behavior: 'smooth', block: 'start' });
          } catch (e) {}
        }
        return;
      }
      const previewMode = event.target.closest("[data-preview-mode]");
      if (previewMode) {
        handlePreviewClick(event);
      }
    });

    setupDropzone();

    window.addEventListener("dragover", (event) => event.preventDefault());
    window.addEventListener("drop", (event) => event.preventDefault());
  }

  async function loadAll(showRefreshState = false) {
    const refreshIcon = els.refreshBtn.querySelector("svg");
    if (showRefreshState) {
      els.refreshBtn.disabled = true;
      if (refreshIcon) refreshIcon.classList.add("spin");
    }

    setBackend("checking");

    try {
      const online = await checkHealth();
      if (online) {
        await loadVideos();
      } else {
        showLibraryError("Backend is unreachable. Start the Go server, then retry.");
        renderLibrary();
      }
    } finally {
      if (showRefreshState) {
        els.refreshBtn.disabled = false;
        if (refreshIcon) refreshIcon.classList.remove("spin");
      }
    }
  }

  async function checkHealth() {
    try {
      const response = await apiFetch("/health", { cache: "no-store" });
      if (!response.ok) throw new Error("unhealthy");
      setBackend("online");
      return true;
    } catch {
      setBackend("offline");
      return false;
    }
  }

  async function loadVideos() {
    clearLibraryError();

    try {
      const response = await apiFetch("/videos", { cache: "no-store" });
      if (!response.ok) {
        throw new Error(await readError(response));
      }

      const payload = await response.json();
      if (!Array.isArray(payload)) {
        throw new Error("Unexpected response from the server.");
      }

      const nextVideos = new Map();
      for (const video of payload) {
        if (video && typeof video.id === "string") {
          nextVideos.set(video.id, video);
        }
      }

      state.videos = nextVideos;
      setBackend("online");
      await loadJobs();

      for (const id of Array.from(state.jobs.keys())) {
        const job = state.jobs.get(id);
        if (job && !state.videos.has(job.videoId)) state.jobs.delete(id);
      }

      for (const video of state.videos.values()) {
        if (!state.pipelines.has(video.id)) {
          state.pipelines.set(video.id, []);
        }
      }

      for (const id of Array.from(state.pipelines.keys())) {
        if (!state.videos.has(id)) state.pipelines.delete(id);
      }
      for (const id of Array.from(state.previewModes.keys())) {
        if (!state.videos.has(id)) state.previewModes.delete(id);
      }
      for (const id of Array.from(state.pollTimers.keys())) {
        if (!state.videos.has(id)) {
          window.clearTimeout(state.pollTimers.get(id));
          state.pollTimers.delete(id);
        }
      }

      if (state.selectedId && !state.videos.has(state.selectedId)) {
        state.selectedId = null;
      }
      if (!state.selectedId) {
        const stored = readStoredSelection();
        if (stored && state.videos.has(stored)) {
          state.selectedId = stored;
        } else if (state.videos.size > 0) {
          state.selectedId = state.videos.keys().next().value;
          storeSelection(state.selectedId);
        }
      }

      for (const video of state.videos.values()) {
        if (isActive(video)) ensurePolling(video.id);
      }

      renderLibrary();
      renderWorkspace();
    } catch (error) {
      setBackend("offline");
      showLibraryError(`Could not load the video library. ${error.message}`);
      renderLibrary();
      if (state.videos.size === 0) renderWorkspace();
    }
  }

  async function loadJobs() {
    try {
      const response = await apiFetch("/jobs", { cache: "no-store" });
      if (!response.ok) throw new Error(await readError(response));

      const payload = await response.json();
      if (!Array.isArray(payload)) throw new Error("Unexpected job history response.");

      const jobs = new Map();
      const latestJobs = new Map();
      for (const job of payload) {
        if (job && typeof job.id === "string" && typeof job.videoId === "string") {
          jobs.set(job.id, job);
          if (!latestJobs.has(job.videoId)) latestJobs.set(job.videoId, job.id);
        }
      }
      state.jobs = jobs;
      state.latestJobIds = latestJobs;
    } catch {
      // Job history is supplemental. Keep the current snapshot and let the
      // next refresh or poll retry it.
    }
  }

  async function refreshJobsForVideo(id) {
    try {
      const response = await apiFetch(`/jobs?videoId=${encodeURIComponent(id)}`, {
        cache: "no-store",
      });
      if (!response.ok) throw new Error(await readError(response));

      const payload = await response.json();
      if (!Array.isArray(payload)) return;

      for (const job of Array.from(state.jobs.values())) {
        if (job.videoId === id) state.jobs.delete(job.id);
      }
      state.latestJobIds.delete(id);
      for (const job of payload) {
        if (job && typeof job.id === "string") {
          state.jobs.set(job.id, job);
          if (!state.latestJobIds.has(id)) state.latestJobIds.set(id, job.id);
        }
      }
    } catch {
      // Polling will retry.
    }
  }

  // ───────────────────────── Library ─────────────────────────

  function showLibraryError(message) {
    els.libraryError.innerHTML = `
      <span>${escapeHTML(message)}</span>
      <div><button class="btn btn-small" type="button" data-retry="1">Retry</button></div>
    `;
    els.libraryError.classList.remove("hidden");
    els.libraryEmpty.classList.add("hidden");
  }

  function clearLibraryError() {
    els.libraryError.classList.add("hidden");
    els.libraryError.innerHTML = "";
  }

  function renderLibrary() {
    const videos = Array.from(state.videos.values()).sort((a, b) => {
      const left = new Date(a.createdAt || 0).getTime();
      const right = new Date(b.createdAt || 0).getTime();
      return right - left;
    });

    els.libraryCount.textContent = String(videos.length);
    els.libraryList.innerHTML = "";

    if (videos.length === 0) {
      const hasError = !els.libraryError.classList.contains("hidden");
      els.libraryEmpty.classList.toggle("hidden", hasError);
      return;
    }

    els.libraryEmpty.classList.add("hidden");

    for (const video of videos) {
      els.libraryList.appendChild(buildLibraryItem(video));
    }
  }

  function buildLibraryItem(video) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = `library-item${video.id === state.selectedId ? " selected" : ""}`;
    button.dataset.id = video.id;
    if (video.id === state.selectedId) button.setAttribute("aria-current", "true");

    const active = isActive(video);
    const progress = clamp(Number(video.progress) || 0, 0, 100);
    const meta = [
      formatDuration(video.duration),
      resolutionText(video),
      formatSize(video.fileSize),
    ].filter((value) => value && value !== "—");

    const progressMarkup = active
      ? `
        <div class="library-progress">
          <div class="library-progress-label">
            <span>${
              video.status === "queued"
                ? "Waiting for worker"
                : video.status === "cancel_requested"
                  ? "Cancelling"
                  : "Processing"
            }</span>
            <span>${progress > 0 ? `${Math.round(progress)}%` : "—"}</span>
          </div>
          <div class="progress-track">
            <div class="progress-fill ${progress > 0 ? "" : "indeterminate"}" style="width:${progress}%"></div>
          </div>
        </div>
      `
      : "";

    button.innerHTML = `
      <div class="library-item-top">
        <span class="library-name" title="${escapeHTML(video.name || video.id)}">${escapeHTML(video.name || shortId(video.id))}</span>
        <span class="status-badge status-${statusClass(video.status)}">${escapeHTML(statusLabel(video.status))}</span>
      </div>
      <div class="library-meta">${meta.map((value) => `<span>${escapeHTML(value)}</span>`).join("")}</div>
      ${progressMarkup}
    `;

    if (video.status === "failed" && video.error) {
      button.title = video.error;
    }

    return button;
  }

  function selectVideo(id) {
    if (!state.videos.has(id)) return;
    state.selectedId = id;
    storeSelection(id);
    renderLibrary();
    renderWorkspace();
  }

  // ───────────────────────── Workspace ─────────────────────────

  function renderWorkspace() {
    const video = selectedVideo();

    if (!video) {
      els.workspaceContent.classList.add("hidden");
      els.workspaceEmpty.classList.remove("hidden");
      return;
    }

    els.workspaceEmpty.classList.add("hidden");
    els.workspaceContent.classList.remove("hidden");

    els.workspaceTitle.textContent = video.name || shortId(video.id);
    els.workspaceTitle.title = video.name || video.id || "";
    els.workspaceSub.textContent = [
      formatDuration(video.duration),
      resolutionText(video),
      formatSize(video.fileSize),
    ]
      .filter((value) => value && value !== "—")
      .join(" · ");

    renderPreview(video);
    renderOperations(video);
    renderJob(video);
  }

  function previewModeFor(video) {
    const canOutput = video.status === "completed" && video.hasOutput;
    let mode = state.previewModes.get(video.id);

    if (mode === "output" && !canOutput) mode = "original";
    if (!mode) mode = canOutput ? "output" : "original";

    return mode;
  }

  function renderPreview(video) {
    const canOutput = video.status === "completed" && video.hasOutput;
    const mode = previewModeFor(video);
    const showingOutput = mode === "output" && canOutput;
    const audioOnly = showingOutput
      ? video.outputType === "audio"
      : !video.width;

    const source = mediaURL(video.id, showingOutput ? "output" : "original");
    let mediaMarkup;
    if (audioOnly) {
      mediaMarkup = `
        <div class="audio-preview">
          <div class="audio-preview-icon" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6">
              <path d="M9 17V6l10-2v11" stroke-linecap="round" stroke-linejoin="round" />
              <circle cx="6" cy="17" r="3" />
              <circle cx="16" cy="15" r="3" />
            </svg>
          </div>
          <div class="audio-preview-title">${showingOutput ? "Audio output (MP3)" : "Audio file"}</div>
          <p class="audio-preview-note">
            ${
              showingOutput
                ? "This pipeline produced an audio-only file, so it is played here as audio rather than loaded into the video preview."
                : "This upload has no video stream. It can be previewed as audio."
            }
          </p>
          <audio controls preload="metadata" crossorigin="use-credentials" src="${source}"></audio>
        </div>
      `;
    } else {
      mediaMarkup = `<video controls playsinline preload="metadata" crossorigin="use-credentials" src="${source}"></video>`;
    }

    els.previewBody.innerHTML = `
      <div class="preview-head">
        <h2>Preview</h2>
        <div class="segmented" role="group" aria-label="Preview source">
          <button type="button" data-preview-mode="original" class="${showingOutput ? "" : "active"}" aria-pressed="${showingOutput ? "false" : "true"}">Original</button>
          <button type="button" data-preview-mode="output" class="${showingOutput ? "active" : ""}" aria-pressed="${showingOutput ? "true" : "false"}" ${canOutput ? "" : "disabled"}>
            ${canOutput && video.outputType === "audio" ? "Audio" : "Processed"}
          </button>
        </div>
      </div>
      <div class="preview-stage${audioOnly ? " is-audio" : ""}" id="previewStage">
        ${mediaMarkup}
      </div>
    `;

    const media = els.previewBody.querySelector("video, audio");
    if (media) {
      media.addEventListener(
        "error",
        () => {
          const stage = document.getElementById("previewStage");
          if (!stage) return;
          stage.innerHTML = `
            <div class="preview-placeholder">
              <svg viewBox="0 0 48 48" fill="none" stroke="currentColor" stroke-width="1.2" aria-hidden="true">
                <rect x="3" y="9" width="42" height="30" rx="3" />
                <path d="m16 17 16 14M32 17 16 31" stroke-linecap="round" />
              </svg>
              <strong>Preview unavailable</strong>
              <span>${
                showingOutput
                  ? "The processed file could not be loaded. It may still be finishing, or it may have been removed."
                  : "The original file could not be loaded. It may have been removed by cleanup."
              }</span>
            </div>
          `;
        },
        { once: true },
      );
    }
  }

  function handlePreviewClick(event) {
    const video = selectedVideo();
    if (!video) return;

    const modeButton = event.target.closest("[data-preview-mode]");
    if (modeButton && !modeButton.disabled) {
      event.preventDefault();
      event.stopPropagation();
      state.previewModes.set(video.id, modeButton.dataset.previewMode);
      renderPreview(video);
      return;
    }

    const downloadButton = event.target.closest("[data-download]");
    if (downloadButton) {
      downloadOutput(downloadButton.dataset.download);
    }
  }

  // ───────────────────────── Operations ─────────────────────────

  function renderOperations(video) {
    const pipeline = pipelineFor(video.id);
    const editable = isEditable(video);
    const hasHistory = jobsForVideo(video.id).length > 0;
    const intro = editable
      ? hasHistory
        ? "Adjust the pipeline and start another attempt. Previous outputs stay in job history."
        : "Add operations to build the processing sequence."
      : "The submitted pipeline is locked while this job runs.";

    els.operationsBody.innerHTML = `
      <div class="section-head">
        <div class="section-title">
          <h2>Edit video</h2>
          <p>${escapeHTML(intro)}</p>
        </div>
        <span class="section-meta">${
          editable
            ? `${pipeline.length} of ${MAX_OPERATIONS} operations`
            : escapeHTML(statusLabel(video.status))
        }</span>
      </div>
      <div class="operations-inner">
        ${
          editable
            ? renderEditableOperations(video)
            : renderLockedOperations(video)
        }
      </div>
    `;

    if (editable) {
      renderDraftFields(video);
      updatePipelineValidation(video);
    }
  }

  function renderEditableOperations(video) {
    const pipeline = pipelineFor(video.id);

    const options = OPERATION_OPTIONS.map(
      ([value, label]) =>
        `<option value="${value}" ${state.addType === value ? "selected" : ""}>${escapeHTML(label)}</option>`,
    ).join("");

    const rows = pipeline.length
      ? pipeline.map((op, index) => operationRowHTML(op, index, pipeline.length)).join("")
      : `<div class="pipeline-empty">No operations yet. Add one above to build the processing sequence.</div>`;

    return `
      <div class="builder">
        <div class="builder-row">
          <div class="field">
            <label for="opType">Operation</label>
            <select class="select" id="opType">${options}</select>
          </div>
          <button class="btn btn-secondary" type="button" data-add-op="1">Add operation</button>
        </div>
        <div class="draft-fields" id="draftFields"></div>
      </div>

      <div class="pipeline-head">
        <span>Pipeline</span>
        <span>Runs top to bottom</span>
      </div>
      <div class="pipeline-list" id="pipelineList">${rows}</div>
      <div id="pipelineValidation"></div>

      <div class="submit-row">
        <span class="submit-hint" id="submitHint"></span>
        <button class="btn btn-primary" id="processBtn" type="button" disabled>${latestJobFor(video.id) ? "Process again" : "Process video"}</button>
      </div>
    `;
  }

  function renderLockedOperations(video) {
    const messages = {
      queued: "This video is queued for a worker. The pipeline is locked until the job finishes.",
      processing: "FFmpeg is processing this video. The pipeline is locked while the job runs.",
      cancel_requested:
        "Cancellation has been requested. The pipeline stays locked until the worker stops.",
    };

    const pipeline = pipelineFor(video.id);
    const className = "notice";
    const readonlyRows = pipeline.length
      ? `
        <div class="pipeline-head">
          <span>Submitted pipeline</span>
          <span>${pipeline.length} operation${pipeline.length === 1 ? "" : "s"}</span>
        </div>
        <div class="pipeline-list">
          ${pipeline
            .map(
              (op, index) => `
                <div class="op-row op-row-readonly">
                  <div class="op-order"><span>${index + 1}</span></div>
                  <div class="op-main">
                    <div class="op-head">
                      <span class="op-title">${escapeHTML(OPERATION_LABELS[op.type] || op.type)}</span>
                      <span class="op-summary">${escapeHTML(operationSummary(op))}</span>
                    </div>
                  </div>
                </div>
              `,
            )
            .join("")}
        </div>
      `
      : "";

    return `
      <div class="locked-note">
        <div class="${className}">
          <strong>${escapeHTML(statusLabel(video.status))}.</strong>
          ${escapeHTML(messages[video.status] || "This video cannot be edited in its current state.")}
        </div>
        ${readonlyRows}
      </div>
    `;
  }

  function renderDraftFields(video) {
    const container = document.getElementById("draftFields");
    if (!container) return;

    const type = state.addType;
    let markup = "";

    if (type === "trim") {
      const endValue =
        Number(video.duration) > 0
          ? formatTimecode(video.duration, true)
          : "00:00:10.000";
      markup = `
        <div class="field-row">
          <div class="field">
            <label for="draftStart">Start time</label>
            <input class="input" id="draftStart" type="text" value="00:00:00.000" placeholder="00:00:00" />
          </div>
          <div class="field">
            <label for="draftEnd">End time</label>
            <input class="input" id="draftEnd" type="text" value="${escapeHTML(endValue)}" placeholder="00:00:10" />
          </div>
        </div>
        <div class="field-help">Accepts seconds, MM:SS, or HH:MM:SS. End must be later than start and no longer than the video.</div>
      `;
    } else if (type === "speed") {
      markup = `
        <div class="field-row">
          <div class="field">
            <label for="draftFactor">Speed multiplier</label>
            <input class="input" id="draftFactor" type="number" value="1" min="${MIN_SPEED}" max="${MAX_SPEED}" step="0.05" />
          </div>
        </div>
        <div class="preset-row">
          ${[0.5, 1, 1.5, 2]
            .map(
              (value) =>
                `<button class="preset" type="button" data-draft-speed="${value}">${value}×</button>`,
            )
            .join("")}
        </div>
        <div class="field-help">Allowed range: ${MIN_SPEED}× to ${MAX_SPEED}×. 1× keeps the original timing.</div>
      `;
    } else if (type === "volume") {
      markup = `
        <div class="field-row">
          <div class="field">
            <label for="draftVolumeRange">Volume (slider)</label>
            <input id="draftVolumeRange" type="range" min="0.1" max="2" step="0.05" value="1" />
          </div>
          <div class="field">
            <label for="draftVolumeNumber">Multiplier</label>
            <input class="input" id="draftVolumeNumber" type="number" value="1" min="0.01" step="0.05" />
          </div>
        </div>
        <div class="field-help">Changes the audio inside the file, not your device volume. 1 = unchanged; must be greater than 0.</div>
      `;
    } else if (type === "flip") {
      markup = `
        <div class="field-row">
          <div class="field">
            <label for="draftOrientation">Direction</label>
            <select class="select" id="draftOrientation">
              <option value="0">Vertical (mirror top/bottom)</option>
              <option value="1">Horizontal (mirror left/right)</option>
            </select>
          </div>
        </div>
        <div class="field-help">Vertical flips the image upside down; horizontal mirrors it left to right.</div>
      `;
    } else if (type === "to_audio") {
      markup = `
        <div class="field-help">
          Extracts the audio track as an MP3 file. It must be the last operation in the pipeline; video operations after it are not allowed.
        </div>
      `;
    }

    container.innerHTML = markup;
  }

  function operationRowHTML(op, index, total) {
    const label = OPERATION_LABELS[op.type] || op.type;
    let fields = "";

    if (op.type === "trim") {
      fields = `
        <div class="field-row">
          <div class="field">
            <label>Start</label>
            <input class="input" data-field="start" type="text" value="${escapeHTML(op.start || "")}" placeholder="00:00:00" />
          </div>
          <div class="field">
            <label>End</label>
            <input class="input" data-field="end" type="text" value="${escapeHTML(op.end || "")}" placeholder="00:00:05" />
          </div>
        </div>
        <div class="field-help">Seconds, MM:SS, or HH:MM:SS.</div>
      `;
    } else if (op.type === "speed") {
      fields = `
        <div class="field-row">
          <div class="field">
            <label>Speed multiplier</label>
            <input class="input" data-field="factor" type="number" value="${escapeHTML(op.factor ?? "1")}" min="${MIN_SPEED}" max="${MAX_SPEED}" step="0.05" />
          </div>
        </div>
        <div class="preset-row">
          ${[0.5, 1, 1.5, 2]
            .map(
              (value) =>
                `<button class="preset" type="button" data-speed-preset="${value}">${value}×</button>`,
            )
            .join("")}
        </div>
      `;
    } else if (op.type === "volume") {
      fields = `
        <div class="field-row">
          <div class="field">
            <label>Volume multiplier</label>
            <input class="input" data-field="factor" type="number" value="${escapeHTML(op.factor ?? "1")}" min="0.01" step="0.05" />
          </div>
        </div>
        <div class="field-help">Audio only. 1 = unchanged; must be greater than 0.</div>
      `;
    } else if (op.type === "flip") {
      fields = `
        <div class="field-row">
          <div class="field">
            <label>Direction</label>
            <select class="select" data-field="orientation">
              <option value="0" ${String(op.orientation) === "0" ? "selected" : ""}>Vertical</option>
              <option value="1" ${String(op.orientation) === "1" ? "selected" : ""}>Horizontal</option>
            </select>
          </div>
        </div>
      `;
    } else if (op.type === "to_audio") {
      fields = `<div class="op-note">Output: MP3 audio · must be the last operation.</div>`;
    }

    return `
      <div class="op-row" data-index="${index}">
        <div class="op-order"><span>${index + 1}</span></div>
        <div class="op-main">
          <div class="op-head">
            <span class="op-title">${escapeHTML(label)}</span>
            <span class="op-summary">${escapeHTML(operationSummary(op))}</span>
          </div>
          ${fields}
        </div>
        <div class="op-controls">
          <button class="icon-btn" type="button" data-action="up" title="Move up" ${index === 0 ? "disabled" : ""}>↑</button>
          <button class="icon-btn" type="button" data-action="down" title="Move down" ${index === total - 1 ? "disabled" : ""}>↓</button>
          <button class="icon-btn remove" type="button" data-action="remove" title="Remove operation">×</button>
        </div>
      </div>
    `;
  }

  function operationSummary(op) {
    switch (op.type) {
      case "trim":
        return `${op.start || "?"} → ${op.end || "?"}`;
      case "speed":
        return `×${op.factor || "?"}`;
      case "volume":
        return `×${op.factor || "?"}`;
      case "flip":
        return String(op.orientation) === "0" ? "vertical" : "horizontal";
      case "to_audio":
        return "MP3 audio";
      default:
        return "";
    }
  }

  function updateOperationSummary(row, op) {
    const summary = row.querySelector(".op-summary");
    if (summary) summary.textContent = operationSummary(op);
  }

  function handleOperationsClick(event) {
    const video = selectedVideo();
    if (!video || !isEditable(video)) return;

    if (event.target.closest("[data-add-op]")) {
      addOperation(video);
      return;
    }

    const processButton = event.target.closest("#processBtn");
    if (processButton) {
      submitPipeline(video);
      return;
    }

    const draftPreset = event.target.closest("[data-draft-speed]");
    if (draftPreset) {
      const input = document.getElementById("draftFactor");
      if (input) input.value = draftPreset.dataset.draftSpeed;
      return;
    }

    const row = event.target.closest(".op-row");
    if (!row) return;

    const index = Number(row.dataset.index);
    const pipeline = pipelineFor(video.id);
    const op = pipeline[index];
    if (!op) return;

    const speedPreset = event.target.closest("[data-speed-preset]");
    if (speedPreset) {
      op.factor = speedPreset.dataset.speedPreset;
      const input = row.querySelector('[data-field="factor"]');
      if (input) input.value = op.factor;
      updateOperationSummary(row, op);
      updatePipelineValidation(video);
      return;
    }

    const action = event.target.closest("[data-action]");
    if (!action) return;

    if (action.dataset.action === "remove") {
      pipeline.splice(index, 1);
      renderOperations(video);
      return;
    }

    if (action.dataset.action === "up" && index > 0) {
      [pipeline[index - 1], pipeline[index]] = [pipeline[index], pipeline[index - 1]];
      renderOperations(video);
      return;
    }

    if (action.dataset.action === "down" && index < pipeline.length - 1) {
      [pipeline[index + 1], pipeline[index]] = [pipeline[index], pipeline[index + 1]];
      renderOperations(video);
    }
  }

  function handleOperationsInput(event) {
    syncDraftVolume(event.target);

    const video = selectedVideo();
    if (!video || !isEditable(video)) return;

    const row = event.target.closest(".op-row");
    if (!row) return;

    const field = event.target.dataset.field;
    if (!field) return;

    const index = Number(row.dataset.index);
    const op = pipelineFor(video.id)[index];
    if (!op) return;

    op[field] = event.target.value;
    updateOperationSummary(row, op);
    updatePipelineValidation(video);
  }

  function handleOperationsChange(event) {
    if (event.target.id === "opType") {
      state.addType = event.target.value;
      const video = selectedVideo();
      if (video) renderDraftFields(video);
      return;
    }

    const video = selectedVideo();
    if (!video || !isEditable(video)) return;

    const row = event.target.closest(".op-row");
    if (!row) return;

    const field = event.target.dataset.field;
    if (!field) return;

    const index = Number(row.dataset.index);
    const op = pipelineFor(video.id)[index];
    if (!op) return;

    if ((field === "start" || field === "end") && op.type === "trim") {
      const seconds = parseTimeInput(event.target.value);
      if (Number.isFinite(seconds)) {
        event.target.value = formatTimecode(seconds, true);
      }
    }

    op[field] = event.target.value;
    updateOperationSummary(row, op);
    updatePipelineValidation(video);
  }

  function syncDraftVolume(target) {
    if (target.id === "draftVolumeRange") {
      const number = document.getElementById("draftVolumeNumber");
      if (number) number.value = target.value;
    } else if (target.id === "draftVolumeNumber") {
      const range = document.getElementById("draftVolumeRange");
      const value = Number(target.value);
      if (range && Number.isFinite(value) && value >= 0.1 && value <= 2) {
        range.value = String(value);
      }
    }
  }

  function addOperation(video) {
    const pipeline = pipelineFor(video.id);

    if (pipeline.length >= MAX_OPERATIONS) {
      toast(`A pipeline can contain at most ${MAX_OPERATIONS} operations.`, "warn");
      return;
    }

    if (pipeline.some((op) => op.type === "to_audio")) {
      toast("Extract audio must be the last operation. Remove it before adding another.", "warn");
      return;
    }

    const type = state.addType;
    let operation = null;

    if (type === "trim") {
      const startValue = document.getElementById("draftStart")?.value ?? "";
      const endValue = document.getElementById("draftEnd")?.value ?? "";
      const start = parseTimeInput(startValue);
      const end = parseTimeInput(endValue);

      if (!Number.isFinite(start)) {
        toast("Enter a valid trim start time.", "error");
        return;
      }
      if (!Number.isFinite(end)) {
        toast("Enter a valid trim end time.", "error");
        return;
      }
      if (start >= end) {
        toast("Trim end must be later than trim start.", "error");
        return;
      }
      if (Number(video.duration) > 0 && end > Number(video.duration) + 0.001) {
        toast("Trim end cannot exceed the video duration.", "error");
        return;
      }

      operation = {
        type: "trim",
        start: formatTimecode(start, true),
        end: formatTimecode(end, true),
      };
    } else if (type === "speed") {
      const factor = Number(document.getElementById("draftFactor")?.value);
      if (!Number.isFinite(factor) || factor < MIN_SPEED || factor > MAX_SPEED) {
        toast(`Speed must be between ${MIN_SPEED}× and ${MAX_SPEED}×.`, "error");
        return;
      }
      operation = { type: "speed", factor: String(factor) };
    } else if (type === "volume") {
      const factor = Number(document.getElementById("draftVolumeNumber")?.value);
      if (!Number.isFinite(factor) || factor <= 0) {
        toast("Volume must be a number greater than 0.", "error");
        return;
      }
      operation = { type: "volume", factor: String(factor) };
    } else if (type === "flip") {
      operation = {
        type: "flip",
        orientation: document.getElementById("draftOrientation")?.value ?? "0",
      };
    } else if (type === "to_audio") {
      operation = { type: "to_audio" };
    }

    if (!operation) return;

    pipeline.push(operation);
    renderOperations(video);
  }

  function validatePipeline(video) {
    const pipeline = pipelineFor(video.id);
    const errors = [];
    const warnings = [];
    let toAudioIndex = -1;

    if (pipeline.length === 0) {
      errors.push("Add at least one operation.");
    }
    if (pipeline.length > MAX_OPERATIONS) {
      errors.push(`Too many operations (maximum ${MAX_OPERATIONS}).`);
    }

    pipeline.forEach((op, index) => {
      const label = `${index + 1}. ${OPERATION_LABELS[op.type] || op.type}`;

      if (op.type === "trim") {
        const start = parseTimeInput(op.start);
        const end = parseTimeInput(op.end);

        if (!Number.isFinite(start)) errors.push(`${label}: start time is invalid.`);
        if (!Number.isFinite(end)) errors.push(`${label}: end time is invalid.`);
        if (Number.isFinite(start) && Number.isFinite(end) && start >= end) {
          errors.push(`${label}: end time must be later than start time.`);
        }
        if (
          Number.isFinite(end) &&
          Number(video.duration) > 0 &&
          end > Number(video.duration) + 0.001
        ) {
          errors.push(`${label}: end time exceeds the video duration.`);
        }
      } else if (op.type === "speed") {
        const factor = Number(op.factor);
        if (
          !Number.isFinite(factor) ||
          factor < MIN_SPEED ||
          factor > MAX_SPEED
        ) {
          errors.push(`${label}: speed must be between ${MIN_SPEED}× and ${MAX_SPEED}×.`);
        }
      } else if (op.type === "volume") {
        const factor = Number(op.factor);
        if (!Number.isFinite(factor) || factor <= 0) {
          errors.push(`${label}: volume must be greater than 0.`);
        }
      } else if (op.type === "flip") {
        const orientation = String(op.orientation);
        if (orientation !== "0" && orientation !== "1") {
          errors.push(`${label}: choose a flip direction.`);
        }
      } else if (op.type === "to_audio") {
        if (toAudioIndex !== -1) {
          errors.push("Only one extract audio operation is allowed.");
        }
        toAudioIndex = index;
      } else {
        errors.push(`${label}: unsupported operation.`);
      }
    });

    if (toAudioIndex !== -1 && toAudioIndex !== pipeline.length - 1) {
      errors.push("Extract audio must be the last operation.");
    }
    if (toAudioIndex !== -1 && pipeline.some((op) => op.type === "flip")) {
      warnings.push("Flip has no effect on an MP3-only output.");
    }

    return { errors, warnings };
  }

  function updatePipelineValidation(video) {
    if (!video || !isEditable(video)) return;

    const pipeline = pipelineFor(video.id);
    const { errors, warnings } = validatePipeline(video);
    const validation = document.getElementById("pipelineValidation");
    const processButton = document.getElementById("processBtn");
    const hint = document.getElementById("submitHint");

    if (validation) {
      if (errors.length > 0) {
        validation.className = "validation-box";
        validation.innerHTML = `<ul>${errors.map((error) => `<li>${escapeHTML(error)}</li>`).join("")}</ul>`;
      } else if (warnings.length > 0) {
        validation.className = "validation-box warn";
        validation.innerHTML = `<ul>${warnings.map((warning) => `<li>${escapeHTML(warning)}</li>`).join("")}</ul>`;
      } else {
        validation.className = "";
        validation.innerHTML = "";
      }
    }

    if (processButton) {
      processButton.disabled = errors.length > 0 || state.submittingId === video.id;
      processButton.textContent =
        state.submittingId === video.id
          ? "Starting…"
          : latestJobFor(video.id)
            ? "Process again"
            : "Process video";
    }

    if (hint) {
      if (errors.length > 0) {
        hint.textContent = "Fix the pipeline errors before submitting.";
      } else if (pipeline.length > 0) {
        hint.textContent = `${pipeline.length} operation${pipeline.length === 1 ? "" : "s"} · runs as one job`;
      } else {
        hint.textContent = "Add at least one operation.";
      }
    }
  }

  function buildOperationsPayload(video) {
    return pipelineFor(video.id).map((op) => {
      if (op.type === "trim") {
        return { type: "trim", start: op.start, end: op.end };
      }
      if (op.type === "speed" || op.type === "volume") {
        return { type: op.type, factor: Number(op.factor) };
      }
      if (op.type === "flip") {
        return { type: "flip", orientation: Number(op.orientation) };
      }
      return { type: op.type };
    });
  }

  async function submitPipeline(video) {
    if (state.submittingId) return;

    const { errors } = validatePipeline(video);
    if (errors.length > 0) {
      toast(errors[0], "error");
      return;
    }

    if (!isEditable(video)) {
      toast("Wait for the active job to finish before starting another attempt.", "warn");
      return;
    }

    state.submittingId = video.id;
    updatePipelineValidation(video);

    try {
      const response = await apiFetch(`/videos/${encodeURIComponent(video.id)}/process`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ operations: buildOperationsPayload(video) }),
      });

      if (!response.ok) {
        throw new Error(await readError(response));
      }

      const payload = await response.json().catch(() => ({}));
      if (payload && typeof payload.id === "string" && payload.videoId === video.id) {
        state.jobs.set(payload.id, payload);
        state.latestJobIds.set(video.id, payload.id);
      }
      const updated = {
        ...video,
        status: payload.status || "queued",
        progress: 0,
        error: "",
        hasOutput: false,
        outputType: "",
      };

      state.videos.set(video.id, updated);
      state.previewModes.set(video.id, "original");
      setBackend("online");

      toast(
        payload.id
          ? `Processing job ${shortId(payload.id)} queued.`
          : "Processing job queued.",
        "success",
      );
      renderLibrary();
      renderWorkspace();
      ensurePolling(video.id);
    } catch (error) {
      toast(error.message, "error");
    } finally {
      state.submittingId = null;
      const current = selectedVideo();
      if (current && current.id === video.id) {
        renderOperations(current);
      }
    }
  }

  // ───────────────────────── Job panel ─────────────────────────

  function renderJob(video) {
    const latest = latestJobFor(video.id);
    const history = jobsForVideo(video.id);
    const status = latest ? latest.status : video.status;
    const progress = clamp(
      Number(latest ? latest.progress : video.progress) || 0,
      0,
      100,
    );
    const canOutput =
      status === "completed" &&
      (latest ? latest.hasOutput : video.hasOutput);
    const outputType = latest ? latest.outputType : video.outputType;
    const outputJobID = latest ? latest.id : null;
    const error = latest ? latest.error : video.error;
    const showProgress =
      status === "queued" ||
      status === "processing" ||
      status === "cancel_requested" ||
      status === "completed" ||
      status === "failed" ||
      status === "cancelled";

    let progressMarkup = "";
    if (showProgress) {
      const indeterminate = status === "queued" || status === "cancel_requested";
      const label =
        status === "queued"
          ? "Waiting for a worker"
          : status === "processing"
            ? progress > 0
              ? `${Math.round(progress)}% complete`
              : "Preparing…"
            : status === "cancel_requested"
              ? "Cancelling…"
              : status === "completed"
                ? "Complete"
                : status === "cancelled"
                  ? "Cancelled"
                  : "Stopped";

      progressMarkup = `
        <div class="job-progress">
          <div class="job-progress-head">
            <span>Progress</span>
            <span>${escapeHTML(label)}</span>
          </div>
          <div class="progress-track large">
            <div class="progress-fill ${indeterminate ? "indeterminate" : ""}" style="width:${
              status === "completed" ? 100 : progress
            }%"></div>
          </div>
        </div>
      `;
    }

    const descriptions = {
      uploaded:
        "Build an edit, then process this video. The original stays available for preview.",
      queued: "The job is in the queue. Processing will start automatically.",
      processing:
        "FFmpeg is processing the video. You can keep editing other videos while this finishes.",
      cancel_requested:
        "Cancellation has been requested. FFmpeg is stopping and the output will be discarded.",
      completed: "Your edited file is ready to preview or download.",
      cancelled:
        "This attempt was cancelled. The source file is still available for another attempt.",
      failed:
        "The job stopped before producing an output. The original is still available.",
    };

    const outputActions = canOutput
      ? `
        <div class="output-actions">
          <button class="btn btn-primary" type="button" data-job-download="${escapeHTML(outputJobID)}">
            <svg viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true">
              <path d="M10 3v9m0 0 3.5-3.5M10 12 6.5 8.5" stroke-linecap="round" stroke-linejoin="round" />
              <path d="M4 14.5v1A1.5 1.5 0 0 0 5.5 17h9a1.5 1.5 0 0 0 1.5-1.5v-1" stroke-linecap="round" />
            </svg>
            Download ${outputType === "audio" ? "MP3" : "MP4"}
          </button>
          <button class="btn btn-secondary" type="button" data-job-preview="output">Preview output</button>
          <button class="btn btn-ghost" type="button" data-job-preview="original">View original</button>
        </div>
      `
      : "";

    const cancelAction =
      latest && (latest.status === "queued" || latest.status === "processing")
        ? `
        <div class="output-actions">
          <button class="btn btn-ghost" type="button" data-job-cancel="${escapeHTML(latest.id)}">
            Cancel job ${escapeHTML(shortId(latest.id))}
          </button>
        </div>
      `
        : "";

    const statusExtras = [];
    if (status === "failed" && error) {
      statusExtras.push(`<div class="error-box">${escapeHTML(error)}</div>`);
    }
    if (status === "cancelled") {
      statusExtras.push(
        `<div class="notice">No output was kept for this attempt. You can adjust the pipeline and process the source again.</div>`,
      );
    }
    if (status === "completed" && canOutput) {
      statusExtras.push(
        `<div class="success-box">${outputType === "audio" ? "MP3 audio is ready." : "MP4 video is ready."}</div>`,
      );
    }
    if (latest && latest.hasSource === false && !isActive(video)) {
      statusExtras.push(
        `<div class="error-box">The original source file is no longer available.</div>`,
      );
    }

    const pipelineCount =
      latest && Array.isArray(latest.operations)
        ? latest.operations.length
        : pipelineFor(video.id).length;
    const pipelineText = latest
      ? `${pipelineCount} operation${pipelineCount === 1 ? "" : "s"}`
      : "Not submitted";

    els.jobBody.innerHTML = `
      <div class="section-head">
        <div class="section-title">
          <h2>Process</h2>
          <p>Run attempts and monitor their history.</p>
        </div>
        <span class="status-pill status-${statusClass(status)}">${escapeHTML(statusLabel(status))}</span>
      </div>
      <div class="job-inner">
        <p class="job-description">${escapeHTML(descriptions[status] || "")}</p>
        ${progressMarkup}
        ${statusExtras.join("")}
        ${outputActions}
        ${cancelAction}
        <details class="technical-details">
          <summary>Technical details</summary>
          <dl class="job-details">
            <div><dt>Job ID</dt><dd title="${escapeHTML(latest?.id || "")}">${escapeHTML(latest?.id || "—")}</dd></div>
            <div><dt>Video ID</dt><dd title="${escapeHTML(video.id)}">${escapeHTML(video.id)}</dd></div>
            <div><dt>Created</dt><dd>${escapeHTML(formatDate(latest?.createdAt || video.createdAt))}</dd></div>
            <div><dt>Started</dt><dd>${escapeHTML(formatDate(latest?.startedAt))}</dd></div>
            <div><dt>Finished</dt><dd>${escapeHTML(formatDate(latest?.finishedAt))}</dd></div>
            <div><dt>Pipeline</dt><dd>${escapeHTML(pipelineText)}</dd></div>
            <div><dt>Attempts</dt><dd>${history.length}</dd></div>
            <div><dt>Output</dt><dd>${
              canOutput
                ? outputType === "audio"
                  ? "MP3 audio"
                  : "MP4 video"
                : "—"
            }</dd></div>
          </dl>
        </details>
        ${renderJobHistory(history, latest?.id)}
      </div>
    `;
  }

  function renderJobHistory(history, latestID) {
    if (history.length === 0) return "";

    const rows = history
      .map((job) => {
        const active =
          job.status === "queued" ||
          job.status === "processing" ||
          job.status === "cancel_requested";
        const operations = Array.isArray(job.operations) ? job.operations.length : 0;
        const canDownload = job.status === "completed" && job.hasOutput;
        const canCancel = job.status === "queued" || job.status === "processing";

        const actions = [];
        if (canDownload) {
          actions.push(
            `<button class="btn btn-small btn-secondary" type="button" data-job-preview="output">Preview output</button>`,
          );
          actions.push(
            `<button class="btn btn-small" type="button" data-history-download="${escapeHTML(job.id)}">Download</button>`,
          );
        }
        if (canCancel) {
          actions.push(
            `<button class="btn btn-small btn-ghost" type="button" data-history-cancel="${escapeHTML(job.id)}">Cancel</button>`,
          );
        }

        return `
          <div class="history-item${job.id === latestID ? " is-current" : ""}">
            <div class="history-main">
              <div class="history-title">
                <span class="status-badge status-${statusClass(job.status)}">${escapeHTML(statusLabel(job.status))}</span>
                <span class="history-id">${escapeHTML(shortId(job.id))}</span>
                ${job.id === latestID ? `<span class="history-current">Current</span>` : ""}
              </div>
              <div class="history-meta">
                <span>${escapeHTML(formatDate(job.createdAt))}</span>
                <span>${operations} op${operations === 1 ? "" : "s"}</span>
                ${job.error ? `<span class="history-error" title="${escapeHTML(job.error)}">${escapeHTML(job.error)}</span>` : ""}
              </div>
              ${
                active
                  ? `<div class="progress-track history-progress"><div class="progress-fill ${
                      job.status === "processing" ? "" : "indeterminate"
                    }" style="width:${clamp(Number(job.progress) || 0, 0, 100)}%"></div></div>`
                  : ""
              }
            </div>
            ${actions.length > 0 ? `<div class="history-actions">${actions.join("")}</div>` : ""}
          </div>
        `;
      })
      .join("");

    return `
      <div class="job-history">
        <div class="history-head">
          <span>Job history</span>
          <span>${history.length} attempt${history.length === 1 ? "" : "s"}</span>
        </div>
        <div class="history-list">${rows}</div>
      </div>
    `;
  }

  function handleJobClick(event) {
    const video = selectedVideo();
    if (!video) return;

    const cancelButton = event.target.closest("[data-job-cancel], [data-history-cancel]");
    if (cancelButton) {
      cancelJob(cancelButton.dataset.jobCancel || cancelButton.dataset.historyCancel);
      return;
    }

    const downloadButton = event.target.closest("[data-job-download]");
    if (downloadButton) {
      downloadJobOutput(downloadButton.dataset.jobDownload);
      return;
    }

    const historyDownload = event.target.closest("[data-history-download]");
    if (historyDownload) {
      downloadJobOutput(historyDownload.dataset.historyDownload);
      return;
    }

    const previewButton = event.target.closest("[data-job-preview]");
    if (previewButton) {
      event.preventDefault();
      event.stopPropagation();
      state.previewModes.set(video.id, previewButton.dataset.jobPreview);
      renderPreview(video);
      // Also try to scroll to preview
      try {
        const preview = document.querySelector('.preview-panel');
        if (preview) preview.scrollIntoView({ behavior: 'smooth', block: 'start' });
      } catch (e) {}
      return;
    }
  }

  // ───────────────────────── Polling ─────────────────────────

  function ensurePolling(id) {
    if (state.pollTimers.has(id)) return;

    const timer = window.setTimeout(() => pollJob(id), 600);
    state.pollTimers.set(id, timer);
  }

  async function pollJob(id) {
    let keepPolling = false;

    try {
      const response = await apiFetch(`/videos/${encodeURIComponent(id)}`, {
        cache: "no-store",
      });

      if (response.status === 404) {
        handleRemovedVideo(id);
        return;
      }

      if (!response.ok) {
        throw new Error(await readError(response));
      }

      const updated = await response.json();
      const previous = state.videos.get(id);

      if (!updated || typeof updated.id !== "string") {
        throw new Error("Unexpected status response.");
      }

      state.videos.set(id, updated);
      setBackend("online");
      await refreshJobsForVideo(id);

      if (previous && previous.status !== updated.status) {
        if (updated.status === "completed") {
          if (state.selectedId === id) state.previewModes.set(id, "output");
          toast("Processing complete — output is ready.", "success");
        } else if (updated.status === "failed") {
          toast(updated.error ? `Processing failed: ${updated.error}` : "Processing failed.", "error", 6000);
        } else if (updated.status === "cancelled") {
          toast("Job cancelled. No output was kept.", "warn");
        }
      }

      if (state.selectedId === id) {
        renderWorkspace();
        const v = state.videos.get(id);
        if (v) renderPreview(v);
        renderJob(v);
      }

      renderLibrary();

      keepPolling = isActive(updated);
    } catch (error) {
      setBackend("offline");
      const video = state.videos.get(id);
      keepPolling = isActive(video);

      if (!keepPolling) {
        toast(`Lost contact with the job: ${error.message}`, "warn");
      }
    }

    if (keepPolling && state.videos.has(id)) {
      state.pollTimers.set(
        id,
        window.setTimeout(() => pollJob(id), 1400),
      );
    } else {
      state.pollTimers.delete(id);
    }
  }

  function handleRemovedVideo(id) {
    const wasSelected = state.selectedId === id;
    const timer = state.pollTimers.get(id);
    if (timer) window.clearTimeout(timer);
    state.pollTimers.delete(id);
    state.videos.delete(id);
    state.pipelines.delete(id);
    state.previewModes.delete(id);
    for (const job of Array.from(state.jobs.values())) {
      if (job.videoId === id) state.jobs.delete(job.id);
    }
    state.latestJobIds.delete(id);

    if (wasSelected) {
      state.selectedId = null;
      storeSelection(null);
    }

    renderLibrary();
    renderWorkspace();
    toast("A video is no longer available. It may have been cleaned up.", "warn");
  }

  // ───────────────────────── Upload ─────────────────────────

  function setupDropzone() {
    els.dropzone.addEventListener("click", (event) => {
      if (event.target.closest("input")) return;
      if (state.uploading) return;
      els.fileInput.click();
    });

    els.dropzone.addEventListener("keydown", (event) => {
      if (event.key === "Enter" || event.key === " ") {
        event.preventDefault();
        if (!state.uploading) els.fileInput.click();
      }
    });

    for (const eventName of ["dragenter", "dragover"]) {
      els.dropzone.addEventListener(eventName, (event) => {
        event.preventDefault();
        event.stopPropagation();
        if (!state.uploading) els.dropzone.classList.add("dragging");
      });
    }

    for (const eventName of ["dragleave", "dragend"]) {
      els.dropzone.addEventListener(eventName, (event) => {
        event.preventDefault();
        event.stopPropagation();
        els.dropzone.classList.remove("dragging");
      });
    }

    els.dropzone.addEventListener("drop", (event) => {
      event.preventDefault();
      event.stopPropagation();
      els.dropzone.classList.remove("dragging");
      if (state.uploading) return;

      const file = event.dataTransfer?.files?.[0];
      if (file) uploadFile(file);
    });

    els.fileInput.addEventListener("change", () => {
      const file = els.fileInput.files?.[0];
      els.fileInput.value = "";
      if (file) uploadFile(file);
    });
  }

  function validateUpload(file) {
    const dot = file.name.lastIndexOf(".");
    const extension = dot > 0 ? file.name.slice(dot).toLowerCase() : "";

    if (!ALLOWED_EXTENSIONS.has(extension)) {
      return `Unsupported file type${extension ? ` (${extension})` : ""}. Use MP4, MOV, MKV, WebM, AVI, M4V, MPEG, or 3GP.`;
    }
    if (file.size <= 0) {
      return "The selected file is empty.";
    }
    if (file.size > MAX_UPLOAD_BYTES) {
      return "The file exceeds the 150 MB upload limit.";
    }
    return null;
  }

  function showUploadProgress(file) {
    els.uploadProgress.classList.remove("hidden");
    els.uploadProgressFill.style.width = "0%";
    els.uploadStatusText.textContent = `Uploading ${file.name}…`;
    els.uploadPercent.textContent = "0%";
  }

  function setUploadVisualState(name) {
    window.clearTimeout(uploadStateTimer);
    els.dropzone.classList.remove("is-uploading", "is-success", "is-error");
    if (!name) return;
    els.dropzone.classList.add(`is-${name}`);
    if (name === "success" || name === "error") {
      uploadStateTimer = window.setTimeout(() => {
        els.dropzone.classList.remove(`is-${name}`);
      }, 3200);
    }
  }

  function uploadFile(file) {
    if (state.uploading) {
      toast("An upload is already in progress.", "warn");
      return;
    }

    const validationError = validateUpload(file);
    if (validationError) {
      toast(validationError, "error");
      return;
    }

    state.uploading = true;
    els.dropzone.classList.add("busy");
    setUploadVisualState("uploading");
    showUploadProgress(file);

    const formData = new FormData();
    formData.append("video", file);

    const xhr = new XMLHttpRequest();
    xhr.open("POST", "/upload");
    xhr.withCredentials = true;
    const csrfToken = typeof getCsrfToken === "function" ? getCsrfToken() : null;
    if (csrfToken) {
      xhr.setRequestHeader("X-CSRF-Token", csrfToken);
    }

    xhr.upload.addEventListener("progress", (event) => {
      if (!event.lengthComputable) return;
      const percent = clamp((event.loaded / event.total) * 100, 0, 100);
      els.uploadProgressFill.style.width = `${percent}%`;
      els.uploadPercent.textContent = `${Math.round(percent)}%`;
    });

    xhr.addEventListener("load", () => {
      state.uploading = false;
      els.dropzone.classList.remove("busy");

      let payload = null;
      try {
        payload = JSON.parse(xhr.responseText);
      } catch {
        payload = null;
      }

      if (xhr.status >= 200 && xhr.status < 300 && payload) {
        setUploadVisualState("success");
        els.uploadProgressFill.style.width = "100%";
        els.uploadPercent.textContent = "100%";
        els.uploadStatusText.textContent = `Uploaded ${payload.name || file.name}`;

        state.videos.set(payload.id, payload);
        state.pipelines.set(payload.id, []);
        state.previewModes.set(payload.id, "original");
        state.addType = "trim";
        state.selectedId = payload.id;
        storeSelection(payload.id);
        setBackend("online");

        renderLibrary();
        renderWorkspace();
        toast(`Uploaded ${payload.name || file.name}`, "success");

        window.setTimeout(() => {
          els.uploadProgress.classList.add("hidden");
        }, 1800);
      } else {
        const message =
          (payload && typeof payload.error === "string" && payload.error) ||
          `Upload failed with status ${xhr.status}.`;
        setUploadVisualState("error");
        els.uploadStatusText.textContent = `Upload failed: ${message}`;
        els.uploadPercent.textContent = "—";
        els.uploadProgressFill.style.width = "0%";
        toast(message, "error", 6000);
      }
    });

    xhr.addEventListener("error", () => {
      state.uploading = false;
      els.dropzone.classList.remove("busy");
      setUploadVisualState("error");
      els.uploadStatusText.textContent = "Upload failed: the backend is unreachable.";
      els.uploadPercent.textContent = "—";
      setBackend("offline");
      toast("Upload failed: the backend is unreachable.", "error", 6000);
    });

    xhr.addEventListener("abort", () => {
      state.uploading = false;
      els.dropzone.classList.remove("busy");
      setUploadVisualState("");
      els.uploadStatusText.textContent = "Upload cancelled.";
      els.uploadPercent.textContent = "—";
    });

    xhr.send(formData);
  }

  // ───────────────────────── Download ─────────────────────────

  async function cancelJob(id) {
    if (!id) return;

    try {
      const response = await apiFetch(`/jobs/${encodeURIComponent(id)}/cancel`, {
        method: "POST",
        cache: "no-store",
      });
      if (!response.ok) throw new Error(await readError(response));

      const job = await response.json();
      if (job && typeof job.id === "string") state.jobs.set(job.id, job);

      const video = state.videos.get(job?.videoId || selectedVideo()?.id);
      if (video) {
        state.videos.set(video.id, {
          ...video,
          status: job?.status || "cancel_requested",
          error: job?.error || video.error,
        });
        ensurePolling(video.id);
      }

      toast(
        job?.status === "cancelled" ? "Job cancelled." : "Cancelling job…",
        "warn",
      );
      renderLibrary();
      renderWorkspace();
    } catch (error) {
      toast(`Could not cancel the job: ${error.message}`, "error");
    }
  }

  async function downloadJobOutput(id) {
    const job = state.jobs.get(id);
    if (!job || job.status !== "completed" || !job.hasOutput) {
      toast("The processed output is not available.", "warn");
      return;
    }

    try {
      const response = await apiFetch(jobMediaURL(id, "download"), {
        method: "HEAD",
        cache: "no-store",
      });

      if (!response.ok) throw new Error(await readError(response));

      setBackend("online");
      const link = document.createElement("a");
      link.href = jobMediaURL(id, "download");
      link.download = jobDownloadFilename(job);
      document.body.appendChild(link);
      link.click();
      link.remove();
    } catch (error) {
      toast(`Download failed: ${error.message}`, "error");
    }
  }

  async function downloadOutput(id) {
    const video = state.videos.get(id);
    if (!video || !video.hasOutput) {
      toast("The processed output is not available.", "warn");
      return;
    }

    try {
      const response = await apiFetch(mediaURL(id, "download"), {
        method: "HEAD",
        cache: "no-store",
      });

      if (!response.ok) {
        throw new Error(await readError(response));
      }

      setBackend("online");

      const link = document.createElement("a");
      link.href = mediaURL(id, "download");
      link.download = downloadFilename(video);
      document.body.appendChild(link);
      link.click();
      link.remove();
    } catch (error) {
      toast(`Download failed: ${error.message}`, "error");
    }
  }

  init();
})();
