/* Motion editor page controller.
 *
 * Owns the timeline project state, persistence (debounced autosave with
 * revision-checked updates), the derived preview media cache (thumbnail
 * filmstrips and waveform peaks), the approximate DOM preview and the render
 * flow. The timeline rendering and pointer editing live in timeline.js.
 */
(function () {
  "use strict";

  const U = window.MotionTimelineUtils;
  const clipDuration = U.clipDuration;
  const clipEnd = U.clipEnd;
  const projectDuration = U.projectDuration;
  const formatClock = U.formatClock;

  const $ = (id) => document.getElementById(id);

  function uid() {
    if (window.crypto && window.crypto.randomUUID) return window.crypto.randomUUID();
    return "id-" + Math.random().toString(36).slice(2) + Date.now().toString(36);
  }

  function toast(message, type) {
    const container = $("toastContainer");
    if (!container) return;
    const el = document.createElement("div");
    el.className = "toast " + (type || "info");
    el.textContent = message;
    container.appendChild(el);
    setTimeout(() => el.remove(), 2800);
  }

  const round3 = (v) => Math.round(v * 1000) / 1000;

  // ---------- state ----------
  let project = newProject();
  let videos = new Map();
  let assetMeta = new Map();
  let playhead = 0;
  let playing = false;
  let speed = 1;
  let lastTs = 0;
  let rafId = 0;
  let currentPreviewClipId = null;
  let saveTimer = null;
  let saving = false;
  let pendingSave = false;
  let renderJobId = null;
  let renderPollTimer = null;
  const audioEls = new Map();
  const overlayEls = new Map();
  const thumbs = new Map(); // assetId -> { ready, url, duration, failed }
  const waves = new Map(); // assetId -> { ready, url, duration, failed }

  const video = $("previewVideo");
  const previewFrame = $("previewFrame");
  const previewOverlays = $("previewOverlays");
  const previewEmpty = $("previewEmpty");
  const timeReadout = $("timeReadout");
  const playBtn = $("playBtn");
  const inspectorEmpty = $("inspectorEmpty");
  const inspectorFields = $("inspectorFields");
  const inspectorMeta = $("inspectorMeta");
  const fieldText = $("fieldText");
  const fieldVolume = $("fieldVolume");
  const fieldOpacity = $("fieldOpacity");
  const fieldGeometry = $("fieldGeometry");
  const fieldScale = $("fieldScale");
  const clipText = $("clipText");
  const clipVolume = $("clipVolume");
  const clipOpacity = $("clipOpacity");
  const clipX = $("clipX");
  const clipY = $("clipY");
  const clipScale = $("clipScale");
  const volumeValue = $("volumeValue");
  const opacityValue = $("opacityValue");
  const saveStatus = $("saveStatus");
  const renderStatus = $("renderStatus");
  const renderErrors = $("renderErrors");
  const renderProgress = $("renderProgress");
  const renderProgressFill = $("renderProgressFill");
  const renderOutput = $("renderOutput");
  const renderOutputVideo = $("renderOutputVideo");
  const assetSelect = $("assetSelect");
  const projectNameInput = $("projectNameInput");
  const projectList = $("projectList");
  const zoomLabel = $("zoomLabel");
  const rangeReadout = $("rangeReadout");
  const rippleToggle = $("rippleToggle");
  const speedSelect = $("speedSelect");

  let timeline = null;

  function newProject() {
    return {
      id: null,
      name: "Untitled timeline",
      schemaVersion: 1,
      revision: 0,
      tracks: [
        { id: uid(), type: "video", name: "Video 1", order: 0, visible: true, muted: false, clips: [] },
        { id: uid(), type: "audio", name: "Audio 1", order: 1, visible: true, muted: false, clips: [] },
      ],
      render: {},
    };
  }

  function makeClip(kind, assetId, duration, start) {
    return {
      id: uid(),
      kind,
      assetId: assetId || "",
      sourceIn: 0,
      sourceOut: duration,
      timelineStart: start || 0,
      volume: 1,
      opacity: 1,
      x: 0,
      y: 0,
      scale: 1,
    };
  }

  function trackOfType(type, create) {
    let track = project.tracks.find((t) => t.type === type);
    if (!track && create) {
      const maxOrder = project.tracks.reduce((m, t) => Math.max(m, t.order || 0), -1);
      track = {
        id: uid(),
        type,
        name: type.charAt(0).toUpperCase() + type.slice(1) + " " + (project.tracks.length + 1),
        order: maxOrder + 1,
        visible: true,
        muted: false,
        clips: [],
      };
      project.tracks.push(track);
    }
    return track || null;
  }

  function trackEnd(track) {
    return (track.clips || []).reduce((m, c) => Math.max(m, c.timelineStart + clipDuration(c)), 0);
  }

  function firstVisibleVideoTrack() {
    return (
      project.tracks
        .filter((t) => t.type === "video" && t.visible !== false)
        .sort((a, b) => (a.order || 0) - (b.order || 0))[0] || null
    );
  }

  function findClipById(id) {
    for (const track of project.tracks) {
      const clip = (track.clips || []).find((c) => c.id === id);
      if (clip) return { clip, track };
    }
    return null;
  }

  function videoClipAt(t) {
    const track = firstVisibleVideoTrack();
    if (!track) return null;
    return (track.clips || []).find((c) => t >= c.timelineStart && t < c.timelineStart + clipDuration(c)) || null;
  }

  function totalDuration() {
    return projectDuration(project);
  }

  function invalidClipIds() {
    const bad = new Set();
    for (const track of project.tracks) {
      const clips = (track.clips || []).slice().sort((a, b) => a.timelineStart - b.timelineStart);
      for (const c of clips) {
        if (c.sourceOut <= c.sourceIn || c.timelineStart < 0) bad.add(c.id);
      }
      for (let i = 1; i < clips.length; i++) {
        const prevEnd = clips[i - 1].timelineStart + clipDuration(clips[i - 1]);
        if (clips[i].timelineStart < prevEnd - 1e-6) bad.add(clips[i].id);
      }
    }
    return bad;
  }

  // ---------- derived media (thumbnails + waveforms) ----------

  function assetDuration(clip) {
    const asset = assetMeta.get(clip.assetId);
    if (asset && asset.duration) return asset.duration;
    const cached = thumbs.get(clip.assetId) || waves.get(clip.assetId);
    return (cached && cached.duration) || 0;
  }

  // getClipMedia is called by the timeline for every clip on every render. It
  // returns cached preview media immediately, and lazily starts a background
  // fetch the first time an asset is seen. When the fetch completes the
  // timeline is redrawn.
  function getClipMedia(clip) {
    if (!clip.assetId) return null;
    if (clip.kind === "audio") {
      const entry = waves.get(clip.assetId);
      if (!entry) {
        loadWaveform(clip.assetId);
        return null;
      }
      if (!entry.ready || !entry.url) return null;
      return { kind: "wave", url: entry.url, duration: assetDuration(clip) || entry.duration };
    }
    if (clip.kind === "video" || clip.kind === "image") {
      const entry = thumbs.get(clip.assetId);
      if (!entry) {
        loadThumbnails(clip.assetId);
        return null;
      }
      if (!entry.ready || !entry.url) return null;
      return { kind: "strip", url: entry.url, duration: assetDuration(clip) || entry.duration };
    }
    return null;
  }

  async function loadThumbnails(assetId) {
    thumbs.set(assetId, { ready: false });
    try {
      const res = await apiFetch("/api/videos/" + encodeURIComponent(assetId) + "/thumbnails", { cache: "no-store" });
      if (!res.ok) {
        thumbs.set(assetId, { ready: true, url: null, failed: true });
        return;
      }
      const meta = await res.json();
      thumbs.set(assetId, {
        ready: true,
        url: meta.spriteUrl,
        duration: meta.duration || 0,
      });
      if (timeline) timeline.redraw();
    } catch (e) {
      thumbs.set(assetId, { ready: true, url: null, failed: true });
    }
  }

  async function loadWaveform(assetId) {
    waves.set(assetId, { ready: false });
    try {
      const res = await apiFetch("/api/videos/" + encodeURIComponent(assetId) + "/waveform", { cache: "no-store" });
      if (!res.ok) {
        waves.set(assetId, { ready: true, url: null, failed: true });
        return;
      }
      const data = await res.json();
      const url = data.audio && data.peaks && data.peaks.length ? waveformDataURL(data.peaks) : null;
      waves.set(assetId, { ready: true, url, duration: data.duration || 0 });
      if (timeline) timeline.redraw();
    } catch (e) {
      waves.set(assetId, { ready: true, url: null, failed: true });
    }
  }

  // waveformDataURL renders peak values into a small PNG so the timeline can
  // position and stretch it with plain CSS, exactly like a thumbnail filmstrip.
  function waveformDataURL(peaks) {
    const H = 64;
    const W = Math.min(4096, Math.max(256, peaks.length));
    const canvas = document.createElement("canvas");
    canvas.width = W;
    canvas.height = H;
    const ctx = canvas.getContext("2d");
    if (!ctx) return null;
    ctx.clearRect(0, 0, W, H);
    ctx.fillStyle = "#8fd6a8";
    const mid = H / 2;
    const step = W / peaks.length;
    for (let i = 0; i < peaks.length; i++) {
      const h = Math.max(1, peaks[i] * (mid - 2));
      const x = i * step;
      ctx.fillRect(x, mid - h, Math.max(1, step), h * 2);
    }
    try {
      return canvas.toDataURL("image/png");
    } catch (e) {
      return null;
    }
  }

  // ---------- preview ----------
  function updateReadout() {
    timeReadout.textContent = formatClock(playhead, 1) + " / " + formatClock(totalDuration(), 1);
  }

  function updatePlayBtn() {
    playBtn.textContent = playing ? "Pause" : "Play";
  }

  function preparePreviewClip(clip, t) {
    const target = Math.max(0, clip.sourceIn + (t - clip.timelineStart));
    if (video.dataset.assetId !== clip.assetId) {
      video.dataset.assetId = clip.assetId;
      video.src = "/api/videos/" + encodeURIComponent(clip.assetId) + "/original";
      video.playbackRate = speed;
      video.addEventListener(
        "loadedmetadata",
        () => {
          try {
            video.currentTime = Math.min(target, video.duration || target);
          } catch (e) {}
        },
        { once: true }
      );
      video.load();
    } else if (video.readyState >= 1) {
      try {
        video.currentTime = Math.min(target, video.duration || target);
      } catch (e) {}
    }
    video.playbackRate = speed;
  }

  function seek(t) {
    const total = totalDuration();
    playhead = Math.max(0, Math.min(t, total > 0 ? total : 0));
    timeline.setPlayhead(playhead);
    updateReadout();
    seekPreview(playhead);
    renderOverlays(playhead);
  }

  function seekPreview(t) {
    const clip = videoClipAt(t);
    if (!clip) {
      if (!video.paused) video.pause();
      previewEmpty.classList.toggle("hidden", totalDuration() > 0);
      currentPreviewClipId = null;
      return;
    }
    previewEmpty.classList.add("hidden");
    preparePreviewClip(clip, t);
    currentPreviewClipId = clip.id;
  }

  function overlayGeometry(clip) {
    const renderW = project.render.width || 1920;
    const renderH = project.render.height || 1080;
    const sx = previewFrame.clientWidth / renderW;
    const sy = previewFrame.clientHeight / renderH;
    return {
      left: (clip.x || 0) * sx,
      top: (clip.y || 0) * sy,
      width: renderW * (clip.scale == null ? 1 : clip.scale) * sx,
      scale: clip.scale == null ? 1 : clip.scale,
      renderW,
      sx,
    };
  }

  function renderOverlays(t) {
    const base = firstVisibleVideoTrack();
    const active = new Set();

    for (const track of project.tracks) {
      if (track.visible === false) continue;
      const isText = track.type === "text";
      const isMediaOverlay = (track.type === "video" || track.type === "image") && track !== base;
      if (!isText && !isMediaOverlay) continue;

      for (const clip of track.clips || []) {
        const dur = clipDuration(clip);
        if (t < clip.timelineStart || t >= clip.timelineStart + dur) continue;
        active.add(clip.id);
        const opacity = clip.opacity == null ? 1 : clip.opacity;

        if (isText) {
          let el = overlayEls.get(clip.id);
          if (!el) {
            el = document.createElement("div");
            el.className = "preview-overlay text";
            previewOverlays.appendChild(el);
            overlayEls.set(clip.id, el);
          }
          const geo = overlayGeometry(clip);
          el.textContent = clip.text || "";
          el.style.left = geo.left + "px";
          el.style.top = geo.top + "px";
          el.style.transform = "scale(" + geo.scale + ")";
          el.style.transformOrigin = "top left";
          el.style.opacity = String(opacity);
          continue;
        }

        // Video / image overlay (picture-in-picture).
        let el = overlayEls.get(clip.id);
        if (!el) {
          el = document.createElement("video");
          el.className = "preview-overlay media";
          el.playsInline = true;
          el.muted = true;
          el.preload = "auto";
          el.src = "/api/videos/" + encodeURIComponent(clip.assetId) + "/original";
          previewOverlays.appendChild(el);
          overlayEls.set(clip.id, el);
        }
        const geo = overlayGeometry(clip);
        el.style.left = geo.left + "px";
        el.style.top = geo.top + "px";
        el.style.width = geo.width + "px";
        el.style.opacity = String(opacity);
        const target = clip.sourceIn + (t - clip.timelineStart);
        if (el.readyState >= 1 && Math.abs(el.currentTime - target) > (playing ? 0.3 : 0.05)) {
          try {
            el.currentTime = Math.max(0, target);
          } catch (e) {}
        }
        if (playing) {
          el.playbackRate = speed;
          if (el.paused) el.play().catch(() => {});
        } else if (!el.paused) {
          el.pause();
        }
      }
    }

    for (const [id, el] of overlayEls) {
      if (!active.has(id)) {
        el.remove();
        overlayEls.delete(id);
      }
    }
  }

  function syncAudio(t, isPlaying) {
    for (const track of project.tracks) {
      if (track.type !== "audio" || track.muted) continue;
      for (const clip of track.clips || []) {
        let el = audioEls.get(clip.id);
        if (!el) {
          if (!isPlaying) continue;
          el = document.createElement("audio");
          el.src = "/api/videos/" + encodeURIComponent(clip.assetId) + "/original";
          el.preload = "auto";
          audioEls.set(clip.id, el);
        }
        const active = t >= clip.timelineStart && t < clip.timelineStart + clipDuration(clip);
        if (active && isPlaying) {
          const target = clip.sourceIn + (t - clip.timelineStart);
          if (el.readyState >= 1 && Math.abs(el.currentTime - target) > 0.35) {
            try {
              el.currentTime = Math.max(0, target);
            } catch (e) {}
          }
          el.playbackRate = speed;
          el.volume = Math.min(1, Math.max(0, clip.volume == null ? 1 : clip.volume));
          if (el.paused) el.play().catch(() => {});
        } else if (!el.paused) {
          el.pause();
        }
      }
    }
  }

  function tick(ts) {
    if (!playing) return;
    if (!lastTs) lastTs = ts;
    const dt = ((ts - lastTs) / 1000) * speed;
    lastTs = ts;
    const total = totalDuration();
    playhead = Math.min(total, playhead + dt);

    const clip = videoClipAt(playhead);
    if (clip) {
      if (video.dataset.assetId !== clip.assetId) {
        preparePreviewClip(clip, playhead);
      } else if (video.readyState >= 1) {
        const target = clip.sourceIn + (playhead - clip.timelineStart);
        if (Math.abs(video.currentTime - target) > 0.25) {
          try {
            video.currentTime = Math.max(0, target);
          } catch (e) {}
        }
        if (video.paused) video.play().catch(() => {});
      }
      currentPreviewClipId = clip.id;
    } else if (!video.paused) {
      video.pause();
    }

    timeline.setPlayhead(playhead);
    timeline.ensureVisible(playhead);
    updateReadout();
    renderOverlays(playhead);
    syncAudio(playhead, true);

    if (playhead >= total - 0.01) {
      pause();
      return;
    }
    rafId = requestAnimationFrame(tick);
  }

  function play() {
    if (totalDuration() <= 0) {
      toast("Add a clip to the timeline first", "warn");
      return;
    }
    if (playhead >= totalDuration() - 0.05) seek(0);
    playing = true;
    lastTs = 0;
    updatePlayBtn();
    const clip = videoClipAt(playhead);
    if (clip) {
      preparePreviewClip(clip, playhead);
      video.playbackRate = speed;
      video.play().catch(() => {});
    }
    cancelAnimationFrame(rafId);
    rafId = requestAnimationFrame(tick);
  }

  function pause() {
    playing = false;
    lastTs = 0;
    cancelAnimationFrame(rafId);
    try {
      video.pause();
    } catch (e) {}
    syncAudio(playhead, false);
    for (const el of overlayEls.values()) {
      if (!el.paused) el.pause();
    }
    updatePlayBtn();
  }

  function togglePlay() {
    if (playing) pause();
    else play();
  }

  function applySpeed(value) {
    speed = Math.max(0.25, Math.min(4, Number(value) || 1));
    try {
      video.playbackRate = speed;
    } catch (e) {}
    for (const el of audioEls.values()) el.playbackRate = speed;
    for (const el of overlayEls.values()) el.playbackRate = speed;
  }

  // ---------- persistence ----------
  function markDirty() {
    clearTimeout(saveTimer);
    saveTimer = setTimeout(saveNow, 1200);
  }

  async function saveNow() {
    if (saving) {
      pendingSave = true;
      return;
    }
    saving = true;
    saveStatus.textContent = "Saving…";
    try {
      let res;
      if (project.id) {
        res = await apiFetch("/api/timeline-projects/" + encodeURIComponent(project.id), {
          method: "PUT",
          body: project,
        });
      } else {
        res = await apiFetch("/api/timeline-projects", { method: "POST", body: project });
      }
      const data = await res.json().catch(() => ({}));
      if (res.status === 409) {
        saveStatus.textContent = "Changed in another session — reload to continue";
        toast("This timeline was changed elsewhere", "error");
        return;
      }
      if (!res.ok) {
        saveStatus.textContent = data.error || "Save failed";
        return;
      }
      project.id = data.id || project.id;
      project.revision = data.revision || project.revision;
      saveStatus.textContent = "Saved " + new Date().toLocaleTimeString();
      loadProjects();
    } catch (e) {
      saveStatus.textContent = "Save failed";
    } finally {
      saving = false;
      if (pendingSave) {
        pendingSave = false;
        saveNow();
      }
    }
  }

  async function loadProjects() {
    try {
      const res = await apiFetch("/api/timeline-projects", { cache: "no-store" });
      if (!res.ok) return;
      const list = await res.json();
      renderProjectList(Array.isArray(list) ? list : []);
    } catch (e) {}
  }

  function renderProjectList(list) {
    projectList.replaceChildren();
    if (list.length === 0) {
      const empty = document.createElement("p");
      empty.className = "render-status";
      empty.textContent = "No saved timelines yet.";
      projectList.appendChild(empty);
      return;
    }
    for (const p of list) {
      const item = document.createElement("div");
      item.className = "project-item" + (p.id === project.id ? " active" : "");
      const name = document.createElement("span");
      name.textContent = p.name || "Untitled";
      const meta = document.createElement("span");
      meta.className = "project-meta";
      meta.textContent = p.id === project.id ? "open" : "";
      item.appendChild(name);
      item.appendChild(meta);
      item.addEventListener("click", () => openProject(p.id));
      projectList.appendChild(item);
    }
  }

  async function openProject(id) {
    try {
      const res = await apiFetch("/api/timeline-projects/" + encodeURIComponent(id), { cache: "no-store" });
      if (!res.ok) {
        toast("Could not open timeline", "error");
        return;
      }
      const data = await res.json();
      applyProject(data);
      toast("Timeline opened", "success");
    } catch (e) {
      toast("Could not open timeline", "error");
    }
  }

  function applyProject(data) {
    pause();
    project = data;
    if (!project.tracks) project.tracks = [];
    if (!project.render) project.render = {};
    projectNameInput.value = project.name || "";
    assetMeta = buildAssetMeta();
    applyRenderSettings();
    currentPreviewClipId = null;
    video.removeAttribute("src");
    video.dataset.assetId = "";
    video.load();
    audioEls.clear();
    for (const el of overlayEls.values()) el.remove();
    overlayEls.clear();
    playhead = 0;
    timeline.setRange(null);
    timeline.select(null, null);
    timeline.render();
    timeline.setPlayhead(0);
    updateReadout();
    seekPreview(0);
    renderOverlays(0);
    updateRangeReadout();
    loadProjects();
  }

  function resetProject() {
    applyProject(newProject());
    saveStatus.textContent = "New timeline (unsaved)";
  }

  // ---------- assets ----------
  async function loadVideos() {
    try {
      const res = await apiFetch("/api/videos", { cache: "no-store" });
      if (!res.ok) return;
      const list = await res.json();
      videos = new Map();
      for (const v of list) videos.set(v.id, v);
      assetMeta = buildAssetMeta();
      populateAssetSelect(list);
      timeline.render();
    } catch (e) {}
  }

  function buildAssetMeta() {
    const map = new Map();
    for (const [id, v] of videos) {
      map.set(id, { name: v.name, duration: v.duration || 0, width: v.width, height: v.height });
    }
    return map;
  }

  function populateAssetSelect(list) {
    assetSelect.replaceChildren();
    const usable = list.filter((v) => v.hasOriginal !== false);
    if (usable.length === 0) {
      const opt = document.createElement("option");
      opt.value = "";
      opt.textContent = "No videos — upload one";
      assetSelect.appendChild(opt);
      return;
    }
    for (const v of usable) {
      const opt = document.createElement("option");
      opt.value = v.id;
      opt.textContent = v.name + (v.duration ? " · " + formatClock(v.duration, 0) : "");
      assetSelect.appendChild(opt);
    }
  }

  // ---------- timeline edits ----------
  function trackById(trackId) {
    return (project.tracks || []).find((t) => t.id === trackId) || null;
  }

  function addVideoClip(assetIdArg, trackIdArg) {
    const assetId = assetIdArg || assetSelect.value;
    if (!assetId) {
      toast("Upload a video first", "warn");
      return;
    }
    const target = trackIdArg ? trackById(trackIdArg) : null;
    const track = target && target.type === "video" ? target : trackOfType("video", true);
    const asset = assetMeta.get(assetId);
    const duration = asset && asset.duration ? asset.duration : 5;
    const clip = makeClip("video", assetId, duration, trackEnd(track));
    track.clips.push(clip);
    timeline.select(clip.id, track.id);
    timeline.render();
    seek(clip.timelineStart);
    refreshInspector();
    markDirty();
  }

  function addAudioClip(trackIdArg) {
    const assetId = assetSelect.value;
    if (!assetId) {
      toast("Upload a video first", "warn");
      return;
    }
    const target = trackIdArg ? trackById(trackIdArg) : null;
    const track = target && target.type === "audio" ? target : trackOfType("audio", true);
    const asset = assetMeta.get(assetId);
    const duration = asset && asset.duration ? asset.duration : 5;
    const clip = makeClip("audio", assetId, duration, trackEnd(track));
    track.clips.push(clip);
    timeline.render();
    markDirty();
  }

  function addTextClip(trackIdArg) {
    const text = window.prompt("Text overlay", "Hello");
    if (text == null || text.trim() === "") return;
    const target = trackIdArg ? trackById(trackIdArg) : null;
    const track = target && target.type === "text" ? target : trackOfType("text", true);
    const clip = makeClip("text", "", 3, playhead);
    clip.text = text.trim();
    track.clips.push(clip);
    timeline.select(clip.id, track.id);
    timeline.render();
    refreshInspector();
    markDirty();
  }

  // addTrack appends an empty track of the given type at the end of the stack.
  // A second video track is what makes overlays / picture-in-picture possible:
  // clips on the higher-order video track composite over the lower ones.
  function addTrack(type) {
    const maxOrder = project.tracks.reduce((m, t) => Math.max(m, t.order || 0), -1);
    const count = project.tracks.filter((t) => t.type === type).length + 1;
    const label = type.charAt(0).toUpperCase() + type.slice(1);
    const track = {
      id: uid(),
      type,
      name: label + " " + count,
      order: maxOrder + 1,
      visible: true,
      muted: false,
      clips: [],
    };
    project.tracks.push(track);
    timeline.select(null, track.id);
    timeline.render();
    markDirty();
    toast("Added " + label.toLowerCase() + " track", "success");
    return track;
  }

  function removeTrack(trackId) {
    const track = trackById(trackId);
    if (!track) return;
    const clipCount = (track.clips || []).length;
    if (clipCount > 0 && !window.confirm("Remove “" + (track.name || track.type) + "” and its " + clipCount + " clip(s)?")) {
      return;
    }
    for (const clip of track.clips || []) {
      audioEls.delete(clip.id);
      const el = overlayEls.get(clip.id);
      if (el) {
        el.remove();
        overlayEls.delete(clip.id);
      }
    }
    project.tracks = project.tracks.filter((t) => t.id !== trackId);
    if (timeline.selectedTrackId === trackId) timeline.select(null, null);
    timeline.render();
    refreshInspector();
    markDirty();
  }

  // addClipToTrack is the per-track "+" button: it adds a clip of the track's
  // own kind using the asset currently chosen in the media bar.
  function addClipToTrack(trackId) {
    const track = trackById(trackId);
    if (!track) return;
    if (track.type === "video") addVideoClip(undefined, trackId);
    else if (track.type === "audio") addAudioClip(trackId);
    else if (track.type === "text") addTextClip(trackId);
    else toast("This track type is not supported yet", "warn");
  }

  // splitAtPlayhead cuts every clip that strictly contains the playhead, which
  // is the standard razor behaviour.
  function splitAtPlayhead() {
    let count = 0;
    for (const track of project.tracks) {
      const clips = (track.clips || []).slice();
      for (const clip of clips) {
        const start = clip.timelineStart || 0;
        const end = start + clipDuration(clip);
        if (playhead <= start + 0.05 || playhead >= end - 0.05) continue;
        const splitSource = clip.sourceIn + (playhead - start);
        const right = Object.assign({}, clip, { id: uid(), sourceIn: splitSource, timelineStart: playhead });
        clip.sourceOut = splitSource;
        track.clips.push(right);
        timeline.select(right.id, track.id);
        count++;
      }
    }
    if (count === 0) {
      toast("Place the playhead inside a clip to split", "warn");
      return;
    }
    timeline.render();
    refreshInspector();
    markDirty();
  }

  function duplicateSelectedClip() {
    const sel = timeline.getSelection();
    if (!sel.clipId) {
      toast("Select a clip first", "warn");
      return;
    }
    const found = findClipById(sel.clipId);
    if (!found) return;
    const copy = Object.assign({}, found.clip, { id: uid() });
    copy.timelineStart = clipEnd(found.clip);
    found.track.clips.push(copy);
    timeline.select(copy.id, found.track.id);
    timeline.render();
    refreshInspector();
    markDirty();
  }

  function deleteSelectedClip() {
    const sel = timeline.getSelection();
    if (!sel.clipId) {
      toast("Select a clip first", "warn");
      return;
    }
    const found = findClipById(sel.clipId);
    if (!found) return;
    found.track.clips = found.track.clips.filter((c) => c.id !== sel.clipId);
    audioEls.delete(sel.clipId);
    const el = overlayEls.get(sel.clipId);
    if (el) {
      el.remove();
      overlayEls.delete(sel.clipId);
    }
    timeline.select(null, null);
    timeline.render();
    refreshInspector();
    markDirty();
  }

  // canMerge reports whether clip b can be appended to clip a: same asset,
  // contiguous source ranges and adjacent on the timeline.
  function canMerge(a, b) {
    return (
      a.assetId &&
      a.assetId === b.assetId &&
      Math.abs(a.timelineStart + clipDuration(a) - b.timelineStart) < 0.05 &&
      Math.abs(a.sourceOut - b.sourceIn) < 0.05
    );
  }

  function mergeInto(a, b, track) {
    a.sourceOut = b.sourceOut;
    track.clips = track.clips.filter((c) => c.id !== b.id);
    audioEls.delete(b.id);
    timeline.selectedClipId = a.id;
    timeline.selectedTrackId = track.id;
  }

  // maybeAutoJoin merges a clip with an adjacent, source-contiguous neighbour.
  // It runs after a move or trim so dragging clips together joins them.
  function maybeAutoJoin(clipId, trackId) {
    const track = project.tracks.find((t) => t.id === trackId);
    if (!track) return false;
    const clip = (track.clips || []).find((c) => c.id === clipId);
    if (!clip) return false;
    const sorted = track.clips.slice().sort((a, b) => a.timelineStart - b.timelineStart);
    const idx = sorted.findIndex((c) => c.id === clip.id);
    if (idx >= 0 && idx + 1 < sorted.length && canMerge(clip, sorted[idx + 1])) {
      mergeInto(clip, sorted[idx + 1], track);
      return true;
    }
    if (idx > 0 && canMerge(sorted[idx - 1], clip)) {
      mergeInto(sorted[idx - 1], clip, track);
      return true;
    }
    return false;
  }

  function joinSelectedClip() {
    const sel = timeline.getSelection();
    if (!sel.clipId) {
      toast("Select a clip first", "warn");
      return;
    }
    const found = findClipById(sel.clipId);
    if (!found) return;
    const track = found.track;
    const sorted = track.clips.slice().sort((a, b) => a.timelineStart - b.timelineStart);
    const idx = sorted.findIndex((c) => c.id === found.clip.id);
    const next = idx >= 0 ? sorted[idx + 1] : null;
    if (!next) {
      toast("No next clip on this track to join", "warn");
      return;
    }
    if (canMerge(found.clip, next)) {
      mergeInto(found.clip, next, track);
      toast("Clips joined", "success");
    } else {
      // Not a source-contiguous match: close the gap so the clips butt together.
      next.timelineStart = round3(clipEnd(found.clip));
      toast("Gap closed", "success");
    }
    timeline.render();
    refreshInspector();
    markDirty();
  }

  // ---------- range edits ----------
  function updateRangeReadout() {
    const r = timeline ? timeline.getRange() : null;
    if (!r || r.end - r.start < 1e-6) {
      rangeReadout.textContent = "No range marked";
      return;
    }
    rangeReadout.textContent =
      "Range " + formatClock(r.start, 1) + " → " + formatClock(r.end, 1) + " (" + (r.end - r.start).toFixed(1) + "s)";
  }

  function markIn() {
    const r = timeline.getRange();
    const end = r && r.end > playhead ? r.end : playhead;
    timeline.setRange({ start: playhead, end });
  }

  function markOut() {
    const r = timeline.getRange();
    const start = r ? r.start : playhead;
    timeline.setRange({ start, end: playhead });
  }

  function clearRange() {
    timeline.setRange(null);
  }

  // removeRange deletes the marked span from every track. When ripple is true,
  // later clips slide left by the removed duration; otherwise a gap is left.
  function removeRange(forceRipple) {
    const r = timeline.getRange();
    if (!r || r.end - r.start < 1e-6) {
      toast("Mark a range first (shift-drag the ruler, or press I and O)", "warn");
      return;
    }
    const ripple = forceRipple !== undefined ? forceRipple : !!(rippleToggle && rippleToggle.checked);
    const len = r.end - r.start;
    for (const track of project.tracks) {
      const out = [];
      for (const clip of track.clips || []) {
        const cs = clip.timelineStart || 0;
        const ce = cs + clipDuration(clip);
        if (ce <= r.start + 1e-6) {
          out.push(clip);
          continue;
        }
        if (cs >= r.end - 1e-6) {
          if (ripple) clip.timelineStart = round3(Math.max(0, cs - len));
          out.push(clip);
          continue;
        }
        // Overlaps the removed span: keep the left part, then the right part.
        if (cs < r.start - 1e-6) {
          const left = Object.assign({}, clip);
          left.sourceOut = round3(left.sourceIn + (r.start - cs));
          out.push(left);
        }
        if (ce > r.end + 1e-6) {
          const right = Object.assign({}, clip, { id: uid() });
          right.sourceIn = round3(clip.sourceIn + (r.end - cs));
          right.timelineStart = round3(ripple ? r.start : r.end);
          out.push(right);
        }
        audioEls.delete(clip.id);
      }
      track.clips = out;
    }
    timeline.setRange(null);
    timeline.select(null, null);
    timeline.render();
    refreshInspector();
    markDirty();
    toast(ripple ? "Range removed (rippled)" : "Range removed", "success");
  }

  // keepRange trims the timeline down to the marked span and shifts it to zero.
  function keepRange() {
    const r = timeline.getRange();
    if (!r || r.end - r.start < 1e-6) {
      toast("Mark a range first (shift-drag the ruler, or press I and O)", "warn");
      return;
    }
    for (const track of project.tracks) {
      const out = [];
      for (const clip of track.clips || []) {
        const cs = clip.timelineStart || 0;
        const ce = cs + clipDuration(clip);
        if (ce <= r.start + 1e-6 || cs >= r.end - 1e-6) continue;
        const newStart = Math.max(cs, r.start);
        const newEnd = Math.min(ce, r.end);
        const kept = Object.assign({}, clip, { id: uid() });
        kept.sourceIn = round3(clip.sourceIn + (newStart - cs));
        kept.sourceOut = round3(clip.sourceIn + (newEnd - cs));
        kept.timelineStart = round3(newStart - r.start);
        out.push(kept);
      }
      track.clips = out;
    }
    timeline.setRange(null);
    timeline.select(null, null);
    playhead = 0;
    timeline.render();
    timeline.setPlayhead(0);
    updateReadout();
    seekPreview(0);
    renderOverlays(0);
    refreshInspector();
    markDirty();
    toast("Kept marked range", "success");
  }

  // ---------- inspector ----------
  function selectedClip() {
    const sel = timeline.getSelection();
    if (!sel.clipId) return null;
    return findClipById(sel.clipId);
  }

  function refreshInspector() {
    const found = selectedClip();
    if (!found) {
      inspectorEmpty.classList.remove("hidden");
      inspectorFields.classList.add("hidden");
      if (inspectorMeta) inspectorMeta.textContent = "";
      return;
    }
    const clip = found.clip;
    inspectorEmpty.classList.add("hidden");
    inspectorFields.classList.remove("hidden");
    const isAudio = clip.kind === "audio";
    const isText = clip.kind === "text";
    fieldText.classList.toggle("hidden", !isText);
    fieldVolume.classList.toggle("hidden", isText);
    fieldOpacity.classList.toggle("hidden", isAudio);
    fieldGeometry.classList.toggle("hidden", isAudio);
    fieldScale.classList.toggle("hidden", isAudio);
    if (isText) clipText.value = clip.text || "";
    clipVolume.value = clip.volume == null ? 1 : clip.volume;
    volumeValue.textContent = Math.round((clip.volume == null ? 1 : clip.volume) * 100) + "%";
    clipOpacity.value = clip.opacity == null ? 1 : clip.opacity;
    opacityValue.textContent = Math.round((clip.opacity == null ? 1 : clip.opacity) * 100) + "%";
    clipX.value = clip.x || 0;
    clipY.value = clip.y || 0;
    clipScale.value = clip.scale == null ? 1 : clip.scale;
    if (inspectorMeta) {
      inspectorMeta.textContent =
        (found.track.name || found.track.type) +
        " · " +
        formatClock(clip.timelineStart, 2) +
        " → " +
        formatClock(clipEnd(clip), 2);
    }
  }

  function wireInspector() {
    clipText.addEventListener("input", () => {
      const found = selectedClip();
      if (found) {
        found.clip.text = clipText.value;
        renderOverlays(playhead);
        markDirty();
      }
    });
    clipText.addEventListener("change", () => timeline.render());

    const volume = () => {
      const found = selectedClip();
      if (!found) return;
      found.clip.volume = Number(clipVolume.value);
      volumeValue.textContent = Math.round(found.clip.volume * 100) + "%";
      markDirty();
    };
    clipVolume.addEventListener("input", volume);

    const opacity = () => {
      const found = selectedClip();
      if (!found) return;
      found.clip.opacity = Number(clipOpacity.value);
      opacityValue.textContent = Math.round(found.clip.opacity * 100) + "%";
      markDirty();
      renderOverlays(playhead);
    };
    clipOpacity.addEventListener("input", opacity);

    for (const [el, key] of [
      [clipX, "x"],
      [clipY, "y"],
      [clipScale, "scale"],
    ]) {
      el.addEventListener("input", () => {
        const found = selectedClip();
        if (!found) return;
        found.clip[key] = Number(el.value) || 0;
        markDirty();
        renderOverlays(playhead);
      });
    }
  }

  // ---------- render ----------
  function applyRenderSettings() {
    const r = project.render || {};
    $("renderResolution").value = r.width && r.height ? r.width + "x" + r.height : "";
    $("renderFps").value = r.fps ? String(r.fps) : "";
  }

  function readRenderSettings() {
    const out = {};
    const res = $("renderResolution").value;
    if (res) {
      const parts = res.split("x").map(Number);
      out.width = parts[0];
      out.height = parts[1];
    }
    const fps = Number($("renderFps").value);
    if (fps) out.fps = fps;
    return out;
  }

  function showValidation(errors) {
    renderErrors.replaceChildren();
    if (!errors || errors.length === 0) {
      renderErrors.classList.add("hidden");
      return;
    }
    for (const e of errors) {
      const li = document.createElement("li");
      li.textContent = e.message || e.code || "Invalid timeline";
      renderErrors.appendChild(li);
    }
    renderErrors.classList.remove("hidden");
  }

  async function renderTimeline() {
    pause();
    showValidation([]);
    project.render = readRenderSettings();
    renderStatus.textContent = "Queueing…";
    renderProgress.classList.remove("hidden");
    renderProgressFill.style.width = "0%";
    renderOutput.classList.add("hidden");
    try {
      const res = await apiFetch("/api/timeline/render", { method: "POST", body: { project: project } });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) {
        renderStatus.textContent = data.error || "Render failed";
        showValidation(data.validationErrors);
        return;
      }
      renderJobId = data.id;
      renderStatus.textContent = "Queued…";
      pollRender();
    } catch (e) {
      renderStatus.textContent = "Render failed";
    }
  }

  function pollRender() {
    clearTimeout(renderPollTimer);
    const step = async () => {
      if (!renderJobId) return;
      try {
        const res = await apiFetch("/api/jobs/" + encodeURIComponent(renderJobId), { cache: "no-store" });
        if (!res.ok) {
          renderPollTimer = setTimeout(step, 2000);
          return;
        }
        const job = await res.json();
        const progress = Math.round(job.progress || 0);
        renderStatus.textContent = "Rendering… " + progress + "%";
        renderProgressFill.style.width = progress + "%";
        if (job.status === "completed") {
          renderStatus.textContent = "Render complete.";
          renderProgressFill.style.width = "100%";
          renderOutput.classList.remove("hidden");
          renderOutputVideo.src = "/api/jobs/" + encodeURIComponent(renderJobId) + "/output";
          toast("Render complete", "success");
          return;
        }
        if (job.status === "failed" || job.status === "cancelled") {
          renderStatus.textContent = job.error || "Render " + job.status;
          return;
        }
        renderPollTimer = setTimeout(step, 1500);
      } catch (e) {
        renderPollTimer = setTimeout(step, 2500);
      }
    };
    step();
  }

  // ---------- shell ----------
  function initTheme() {
    const btn = $("themeToggle");
    if (!btn) return;
    btn.addEventListener("click", () => {
      const current = document.documentElement.dataset.theme || "dark";
      const next = current === "dark" ? "light" : "dark";
      document.documentElement.dataset.theme = next;
      try {
        localStorage.setItem("motion:theme", next);
      } catch (e) {}
    });
  }

  async function checkBackend() {
    const dot = $("backendStatus");
    try {
      const res = await apiFetch("/health", { cache: "no-store" });
      if (dot) dot.dataset.state = res.ok ? "online" : "offline";
    } catch (e) {
      if (dot) dot.dataset.state = "offline";
    }
  }

  async function loadVerification() {
    try {
      const [cfgRes, meRes] = await Promise.all([
        apiFetch("/api/config", { cache: "no-store" }),
        apiFetch("/api/auth/me", { cache: "no-store" }),
      ]);
      const cfg = cfgRes.ok ? await cfgRes.json() : {};
      const me = meRes.ok ? await meRes.json() : {};
      const required = cfg.requireEmailVerification && me.user && me.user.emailVerified === false;
      $("verifyBanner").classList.toggle("hidden", !required);
    } catch (e) {}
  }

  function wireShell() {
    $("verifyResendBtn").addEventListener("click", async () => {
      try {
        await apiFetch("/api/auth/verify/resend", { method: "POST" });
        toast("Verification email sent", "success");
      } catch (e) {
        toast("Could not send email", "error");
      }
    });
    $("newProjectBtn").addEventListener("click", () => {
      if (confirm("Start a new timeline? Unsaved changes are kept only in this browser session.")) resetProject();
    });
    $("saveBtn").addEventListener("click", saveNow);
    projectNameInput.addEventListener("input", () => {
      project.name = projectNameInput.value;
      markDirty();
    });
    document.addEventListener("visibilitychange", () => {
      if (document.visibilityState === "hidden" && project.id) saveNow();
    });
  }

  function wireTimeline() {
    timeline = new window.MotionTimeline({
      root: $("timelineRoot"),
      getProject: () => project,
      getPlayhead: () => playhead,
      getAssets: () => assetMeta,
      getInvalidClipIds: invalidClipIds,
      getClipMedia: (clip) => getClipMedia(clip),
      onSeek: (t) => seek(t),
      onSelect: () => refreshInspector(),
      onRangeChange: () => updateRangeReadout(),
      onAddClip: (trackId) => addClipToTrack(trackId),
      onRemoveTrack: (trackId) => removeTrack(trackId),
      onChange: (ev) => {
        if (ev && (ev.type === "move" || ev.type === "trim-left" || ev.type === "trim-right")) {
          if (maybeAutoJoin(ev.clipId, ev.trackId)) {
            timeline.render();
            toast("Clips joined", "success");
          }
        }
        refreshInspector();
        markDirty();
      },
    });
    timeline.render();
    timeline.setPlayhead(0);

    $("playBtn").addEventListener("click", togglePlay);
    $("stepBackBtn").addEventListener("click", () => {
      pause();
      seek(playhead - 1 / 30);
    });
    $("stepFwdBtn").addEventListener("click", () => {
      pause();
      seek(playhead + 1 / 30);
    });
    $("splitBtn").addEventListener("click", splitAtPlayhead);
    $("joinBtn").addEventListener("click", joinSelectedClip);
    $("duplicateBtn").addEventListener("click", duplicateSelectedClip);
    $("deleteClipBtn").addEventListener("click", deleteSelectedClip);
    $("markInBtn").addEventListener("click", markIn);
    $("markOutBtn").addEventListener("click", markOut);
    $("clearRangeBtn").addEventListener("click", clearRange);
    $("removeBtn").addEventListener("click", () => removeRange());
    $("extractBtn").addEventListener("click", () => removeRange(false));
    $("keepBtn").addEventListener("click", keepRange);
    $("addVideoClipBtn").addEventListener("click", () => addVideoClip());
    $("addAudioClipBtn").addEventListener("click", addAudioClip);
    $("addTextClipBtn").addEventListener("click", addTextClip);
    $("addVideoTrackBtn").addEventListener("click", () => addTrack("video"));
    $("addAudioTrackBtn").addEventListener("click", () => addTrack("audio"));
    $("zoomInBtn").addEventListener("click", () => {
      timeline.zoomBy(1.25);
      updateZoomLabel();
    });
    $("zoomOutBtn").addEventListener("click", () => {
      timeline.zoomBy(0.8);
      updateZoomLabel();
    });
    $("zoomFitBtn").addEventListener("click", () => {
      timeline.fit();
      updateZoomLabel();
    });
    $("renderBtn").addEventListener("click", renderTimeline);
    if (speedSelect) speedSelect.addEventListener("change", () => applySpeed(speedSelect.value));
    $("fullscreenBtn").addEventListener("click", toggleFullscreen);

    video.addEventListener("ended", () => {
      if (playing) pause();
    });
    window.addEventListener("resize", () => renderOverlays(playhead));
    document.addEventListener("fullscreenchange", () => {
      $("fullscreenBtn").textContent = document.fullscreenElement ? "Exit fullscreen" : "Fullscreen";
      renderOverlays(playhead);
    });
  }

  function toggleFullscreen() {
    if (document.fullscreenElement) {
      document.exitFullscreen().catch(() => {});
    } else if (previewFrame.requestFullscreen) {
      previewFrame.requestFullscreen().catch(() => {});
    }
  }

  function updateZoomLabel() {
    zoomLabel.textContent = Math.round(timeline.getZoom()) + " px/s";
  }

  // ---------- keyboard ----------
  function isTypingTarget(target) {
    if (!target) return false;
    const tag = target.tagName;
    return tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT" || target.isContentEditable;
  }

  function wireKeyboard() {
    document.addEventListener("keydown", (e) => {
      if (isTypingTarget(e.target)) return;
      const mod = e.ctrlKey || e.metaKey;
      if (mod && (e.key === "s" || e.key === "S")) {
        e.preventDefault();
        saveNow();
        return;
      }
      if (mod) return;
      switch (e.key) {
        case " ":
          e.preventDefault();
          togglePlay();
          break;
        case "s":
        case "S":
          splitAtPlayhead();
          break;
        case "j":
        case "J":
          joinSelectedClip();
          break;
        case "d":
        case "D":
          duplicateSelectedClip();
          break;
        case "i":
        case "I":
          markIn();
          break;
        case "o":
        case "O":
          markOut();
          break;
        case "r":
        case "R":
          removeRange();
          break;
        case "x":
        case "X":
          removeRange(false);
          break;
        case "k":
        case "K":
          keepRange();
          break;
        case "Delete":
        case "Backspace":
          e.preventDefault();
          deleteSelectedClip();
          break;
        case "Escape":
          clearRange();
          timeline.select(null, null);
          refreshInspector();
          break;
        case "ArrowLeft":
          e.preventDefault();
          pause();
          seek(playhead - 1 / 30);
          break;
        case "ArrowRight":
          e.preventDefault();
          pause();
          seek(playhead + 1 / 30);
          break;
        case "Home":
          e.preventDefault();
          seek(0);
          break;
        case "End":
          e.preventDefault();
          seek(totalDuration());
          break;
        case "+":
        case "=":
          timeline.zoomBy(1.25);
          updateZoomLabel();
          break;
        case "-":
        case "_":
          timeline.zoomBy(0.8);
          updateZoomLabel();
          break;
        default:
          break;
      }
    });
  }

  function openFromQuery() {
    const params = new URLSearchParams(window.location.search);
    const projectId = params.get("project");
    const videoId = params.get("video");
    if (projectId) {
      openProject(projectId);
      return;
    }
    if (videoId && assetMeta.has(videoId)) {
      project.name = (assetMeta.get(videoId) || {}).name || project.name;
      projectNameInput.value = project.name;
      addVideoClip(videoId);
    }
  }

  async function init() {
    initTheme();
    wireShell();
    wireTimeline();
    wireInspector();
    wireKeyboard();
    projectNameInput.value = project.name;
    updateReadout();
    updateRangeReadout();
    updateZoomLabel();
    applySpeed(speedSelect ? speedSelect.value : 1);
    checkBackend();
    // Anonymous visitors are redirected by auth-guard.js; skip authenticated
    // bootstrapping so it does not produce 401 console noise first.
    try {
      const me = await apiFetch("/api/auth/me", { cache: "no-store" });
      if (!me.ok) return;
    } catch (e) {
      return;
    }
    loadVideos().then(openFromQuery);
    loadProjects();
    loadVerification();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
