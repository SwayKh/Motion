/* Motion timeline component.
 *
 * Renders a timeline.Project as an adaptive ruler plus one lane per track, and
 * provides pointer interactions for seeking, moving, trimming and selecting
 * clips. It is intentionally framework-free and model-driven: it mutates the
 * project object it is given and reports changes through callbacks so the page
 * controller owns persistence.
 */
(function () {
  "use strict";

  const HEAD_W = 150;
  const MIN_DURATION = 10;
  const SNAP_PX = 8;
  const TICK_STEPS = [0.1, 0.2, 0.5, 1, 2, 5, 10, 15, 30, 60, 120, 300, 600];
  const KIND_CLASS = { video: "video", audio: "audio", image: "image", text: "text" };

  function clipDuration(clip) {
    return Math.max(0, (clip.sourceOut || 0) - (clip.sourceIn || 0));
  }

  function clipEnd(clip) {
    return (clip.timelineStart || 0) + clipDuration(clip);
  }

  function formatClock(t, decimals) {
    if (!isFinite(t) || t < 0) t = 0;
    const m = Math.floor(t / 60);
    const s = t - m * 60;
    const str = decimals ? s.toFixed(decimals) : String(Math.floor(s)).padStart(2, "0");
    return m + ":" + (decimals ? str.padStart(4 + decimals, "0") : str);
  }

  function sortedTracks(project) {
    const tracks = (project && project.tracks) || [];
    return tracks.slice().sort((a, b) => (a.order || 0) - (b.order || 0));
  }

  function projectDuration(project) {
    let max = 0;
    for (const track of sortedTracks(project)) {
      for (const clip of track.clips || []) {
        max = Math.max(max, clipEnd(clip));
      }
    }
    return max;
  }

  class Timeline {
    constructor(options) {
      this.root = options.root;
      this.getProject = options.getProject;
      this.getPlayhead = options.getPlayhead || (() => 0);
      this.getAssets = options.getAssets || (() => new Map());
      this.getInvalidClipIds = options.getInvalidClipIds || (() => new Set());
      this.getClipMedia = options.getClipMedia || (() => null);
      this.onSeek = options.onSeek || (() => {});
      this.onChange = options.onChange || (() => {});
      this.onSelect = options.onSelect || (() => {});
      this.onRangeChange = options.onRangeChange || (() => {});
      this.onAddClip = options.onAddClip || null;
      this.onRemoveTrack = options.onRemoveTrack || null;

      this.pps = 60; // pixels per second
      this.selectedClipId = null;
      this.selectedTrackId = null;
      this.drag = null;
      this.range = null; // { start, end } timeline seconds
      this._scrollLeft = 0;

      this.root.classList.add("tl-wrap");
      this.root.innerHTML =
        '<div class="tl-body"><div class="tl-content"></div></div>';
      this.bodyEl = this.root.querySelector(".tl-body");
      this.contentEl = this.root.querySelector(".tl-content");

      this.bodyEl.addEventListener("pointerdown", (e) => this.onPointerDown(e));
      this.bodyEl.addEventListener("scroll", () => {
        this._scrollLeft = this.bodyEl.scrollLeft;
      });
    }

    getZoom() {
      return this.pps;
    }

    duration() {
      return Math.max(projectDuration(this.getProject()), MIN_DURATION);
    }

    setZoom(pps) {
      this.pps = Math.min(600, Math.max(4, pps));
      this.render();
    }

    zoomBy(factor) {
      this.setZoom(this.pps * factor);
    }

    fit() {
      const project = this.getProject();
      const dur = Math.max(projectDuration(project), 1);
      const width = this.bodyEl ? this.bodyEl.clientWidth - HEAD_W : 800;
      this.setZoom(Math.max(4, width / dur));
    }

    select(clipId, trackId) {
      this.selectedClipId = clipId || null;
      this.selectedTrackId = trackId || null;
      this.render();
    }

    getSelection() {
      return { clipId: this.selectedClipId, trackId: this.selectedTrackId };
    }

    getRange() {
      return this.range ? { start: this.range.start, end: this.range.end } : null;
    }

    setRange(range) {
      if (!range) {
        this.range = null;
      } else {
        const start = Math.max(0, Math.min(range.start, range.end));
        const end = Math.max(range.start, range.end);
        this.range = { start, end };
      }
      this.render();
      this.onRangeChange(this.getRange());
    }

    // redraw is used when the page controller has loaded new media (thumbnail
    // or waveform) for a clip and the timeline needs to pick it up.
    redraw() {
      this.render();
    }

    setPlayhead(t) {
      if (this.playheadEl) {
        this.playheadEl.style.left = HEAD_W + Math.max(0, t) * this.pps + "px";
      }
    }

    // ensureVisible scrolls the timeline horizontally so the given time is in
    // view. It is used while playing so the playhead does not run off-screen.
    ensureVisible(t) {
      if (!this.bodyEl) return;
      const x = HEAD_W + Math.max(0, t) * this.pps;
      const left = this.bodyEl.scrollLeft;
      const right = left + this.bodyEl.clientWidth;
      if (x < left + HEAD_W) {
        this.bodyEl.scrollLeft = Math.max(0, x - HEAD_W - 40);
      } else if (x > right - 48) {
        this.bodyEl.scrollLeft = x - this.bodyEl.clientWidth + 96;
      }
    }

    findTrack(trackId) {
      const project = this.getProject();
      return (project.tracks || []).find((t) => t.id === trackId) || null;
    }

    findClip(trackId, clipId) {
      const track = this.findTrack(trackId);
      if (!track) return null;
      return (track.clips || []).find((c) => c.id === clipId) || null;
    }

    assetName(clip) {
      if (clip.kind === "text") return clip.text || "Text";
      const asset = this.getAssets().get(clip.assetId);
      return (asset && asset.name) || clip.assetId || clip.kind;
    }

    render() {
      const project = this.getProject();
      const scrollTop = this.bodyEl.scrollTop;
      const dur = this.duration();
      const contentPx = Math.max(dur * this.pps, this.bodyEl.clientWidth - HEAD_W);
      this.contentEl.style.width = HEAD_W + contentPx + "px";

      const fragment = document.createDocumentFragment();

      // Ruler row.
      const rulerRow = document.createElement("div");
      rulerRow.className = "tl-ruler-row";
      const rulerHead = document.createElement("div");
      rulerHead.className = "tl-ruler-head";
      const ruler = document.createElement("div");
      ruler.className = "tl-ruler";
      this.renderTicks(ruler, dur);
      rulerRow.appendChild(rulerHead);
      rulerRow.appendChild(ruler);
      fragment.appendChild(rulerRow);

      const tracks = sortedTracks(project);
      if (tracks.length === 0) {
        const empty = document.createElement("div");
        empty.className = "tl-empty";
        empty.textContent = "No tracks yet. Add a video or audio track to begin.";
        fragment.appendChild(empty);
      }

      const invalid = this.getInvalidClipIds();
      for (const track of tracks) {
        fragment.appendChild(this.renderTrackRow(track, invalid));
      }

      // Playhead spans the lanes.
      const playhead = document.createElement("div");
      playhead.className = "tl-playhead";
      playhead.style.left = HEAD_W + Math.max(0, this.getPlayhead()) * this.pps + "px";
      this.playheadEl = playhead;

      this.contentEl.replaceChildren(fragment);
      this.contentEl.appendChild(playhead);
      if (this.range && this.range.end - this.range.start > 1e-6) {
        this.contentEl.appendChild(this.renderRange());
      }
      this.bodyEl.scrollTop = scrollTop;
      this.bodyEl.scrollLeft = this._scrollLeft;
    }

    renderRange() {
      const el = document.createElement("div");
      el.className = "tl-range";
      const left = HEAD_W + this.range.start * this.pps;
      el.style.left = left + "px";
      el.style.width = Math.max(2, (this.range.end - this.range.start) * this.pps) + "px";
      for (const side of ["start", "end"]) {
        const handle = document.createElement("div");
        handle.className = "tl-range-handle " + side;
        el.appendChild(handle);
      }
      return el;
    }

    renderTicks(ruler, dur) {
      let step = TICK_STEPS[TICK_STEPS.length - 1];
      for (const candidate of TICK_STEPS) {
        if (candidate * this.pps >= 64) {
          step = candidate;
          break;
        }
      }
      const decimals = step < 1 ? 1 : 0;
      for (let t = 0; t <= dur + 1e-6; t += step) {
        const tick = document.createElement("div");
        tick.className = "tl-tick major";
        tick.style.left = t * this.pps + "px";
        const label = document.createElement("span");
        label.className = "tl-tick-label";
        label.textContent = formatClock(t, decimals);
        tick.appendChild(label);
        ruler.appendChild(tick);
      }
    }

    renderTrackRow(track, invalid) {
      const row = document.createElement("div");
      row.className = "tl-track-row";
      row.dataset.trackId = track.id;
      if (track.id === this.selectedTrackId) row.classList.add("selected");

      const head = document.createElement("div");
      head.className = "tl-track-head";
      head.innerHTML =
        '<span class="tl-track-name"></span>' +
        '<span class="tl-track-type"></span>' +
        '<span class="tl-track-actions"></span>';
      head.querySelector(".tl-track-name").textContent = track.name || track.type;
      head.querySelector(".tl-track-type").textContent = track.type;

      const actions = head.querySelector(".tl-track-actions");
      if (track.type === "audio") {
        const muteBtn = document.createElement("button");
        muteBtn.type = "button";
        muteBtn.className = "tl-track-toggle" + (track.muted ? " off" : "");
        muteBtn.textContent = track.muted ? "Muted" : "Mute";
        muteBtn.addEventListener("click", (e) => {
          e.stopPropagation();
          track.muted = !track.muted;
          this.render();
          this.onChange({ type: "track-toggle", trackId: track.id });
        });
        actions.appendChild(muteBtn);
      } else {
        const visBtn = document.createElement("button");
        visBtn.type = "button";
        visBtn.className = "tl-track-toggle" + (track.visible === false ? " off" : "");
        visBtn.textContent = track.visible === false ? "Hidden" : "Visible";
        visBtn.addEventListener("click", (e) => {
          e.stopPropagation();
          track.visible = track.visible === false;
          this.render();
          this.onChange({ type: "track-toggle", trackId: track.id });
        });
        actions.appendChild(visBtn);
      }

      if (this.onAddClip) {
        const addBtn = document.createElement("button");
        addBtn.type = "button";
        addBtn.className = "tl-track-add";
        addBtn.textContent = "+";
        addBtn.title = "Add a clip to this track";
        addBtn.addEventListener("click", (e) => {
          e.stopPropagation();
          this.onAddClip(track.id);
        });
        actions.appendChild(addBtn);
      }
      if (this.onRemoveTrack) {
        const delBtn = document.createElement("button");
        delBtn.type = "button";
        delBtn.className = "tl-track-remove";
        delBtn.textContent = "×";
        delBtn.title = "Remove this track";
        delBtn.addEventListener("click", (e) => {
          e.stopPropagation();
          this.onRemoveTrack(track.id);
        });
        actions.appendChild(delBtn);
      }

      const lane = document.createElement("div");
      lane.className = "tl-lane";
      lane.dataset.trackId = track.id;

      const clips = (track.clips || []).slice().sort((a, b) => (a.timelineStart || 0) - (b.timelineStart || 0));
      for (const clip of clips) {
        lane.appendChild(this.renderClip(track, clip, invalid.has(clip.id)));
      }

      row.appendChild(head);
      row.appendChild(lane);
      return row;
    }

    renderClip(track, clip, invalid) {
      const el = document.createElement("div");
      el.className = "tl-clip " + (KIND_CLASS[clip.kind] || "video");
      el.dataset.trackId = track.id;
      el.dataset.clipId = clip.id;
      el.style.left = (clip.timelineStart || 0) * this.pps + "px";
      el.style.width = Math.max(6, clipDuration(clip) * this.pps) + "px";
      if (clip.id === this.selectedClipId) el.classList.add("selected");
      if (invalid) el.classList.add("invalid");
      if (track.muted) el.classList.add("muted");
      el.title = this.assetName(clip) + " · " + formatClock(clip.timelineStart, 1) + " → " + formatClock(clipEnd(clip), 1);

      const media = this.getClipMedia(clip);
      if (media && media.url && media.duration > 0) {
        el.classList.add("has-media");
        const layer = document.createElement("div");
        layer.className = "tl-clip-media " + (media.kind || "strip");
        layer.style.backgroundImage = 'url("' + media.url + '")';
        layer.style.backgroundSize = media.duration * this.pps + "px 100%";
        layer.style.backgroundPositionX = -(clip.sourceIn || 0) * this.pps + "px";
        layer.style.backgroundRepeat = "repeat-x";
        el.appendChild(layer);
      }

      const label = document.createElement("span");
      label.className = "tl-clip-label";
      label.textContent = this.assetName(clip);
      el.appendChild(label);

      for (const side of ["left", "right"]) {
        const handle = document.createElement("div");
        handle.className = "tl-clip-handle " + side;
        handle.dataset.side = side;
        el.appendChild(handle);
      }
      return el;
    }

    // ---------- pointer interactions ----------

    onPointerDown(e) {
      if (e.button !== undefined && e.button !== 0) return;
      const project = this.getProject();
      const clipEl = e.target.closest(".tl-clip");
      const handleEl = e.target.closest(".tl-clip-handle");
      const ruler = e.target.closest(".tl-ruler");
      const lane = e.target.closest(".tl-lane");
      const row = e.target.closest(".tl-track-row");
      const trackId = row ? row.dataset.trackId : null;

      if (clipEl) {
        const clipId = clipEl.dataset.clipId;
        const cTrackId = clipEl.dataset.trackId;
        this.selectedClipId = clipId;
        this.selectedTrackId = cTrackId;
        this.onSelect({ clipId, trackId: cTrackId });
        this.render();
        const clip = this.findClip(cTrackId, clipId);
        const track = this.findTrack(cTrackId);
        const rect = clipEl.getBoundingClientRect();
        const mode = handleEl ? (handleEl.dataset.side === "left" ? "trim-left" : "trim-right") : "move";
        this.beginDrag(e, {
          mode,
          clip,
          track,
          el: this.contentEl.querySelector(
            '.tl-clip[data-clip-id="' + CSS.escape(clipId) + '"]'
          ),
          rect,
        });
        return;
      }

      if (ruler || lane) {
        if (trackId) this.selectedTrackId = trackId;
        const seekEl = ruler || lane;
        const rect = seekEl.getBoundingClientRect();
        if (e.shiftKey) {
          this.beginRange(e, rect);
        } else {
          this.beginScrub(e, rect);
        }
        return;
      }
    }

    timeAt(clientX, rect) {
      return Math.max(0, Math.min((clientX - rect.left) / this.pps, this.duration()));
    }

    beginRange(e, rect) {
      this.bodyEl.setPointerCapture(e.pointerId);
      const anchor = this.timeAt(e.clientX, rect);
      this.range = { start: anchor, end: anchor };
      const move = (ev) => {
        this.range.end = this.timeAt(ev.clientX, rect);
        this.render();
      };
      const up = () => {
        this.bodyEl.removeEventListener("pointermove", move);
        this.bodyEl.removeEventListener("pointerup", up);
        this.bodyEl.removeEventListener("pointercancel", up);
        if (this.range && Math.abs(this.range.end - this.range.start) < 0.05) {
          this.range = null;
        } else if (this.range) {
          const a = Math.min(this.range.start, this.range.end);
          const b = Math.max(this.range.start, this.range.end);
          this.range = { start: a, end: b };
        }
        this.render();
        this.onRangeChange(this.getRange());
      };
      this.render();
      this.bodyEl.addEventListener("pointermove", move);
      this.bodyEl.addEventListener("pointerup", up);
      this.bodyEl.addEventListener("pointercancel", up);
    }

    beginScrub(e, rect) {
      this.bodyEl.setPointerCapture(e.pointerId);
      const seek = (clientX) => {
        const t = Math.max(0, Math.min((clientX - rect.left) / this.pps, this.duration()));
        this.onSeek(t);
      };
      seek(e.clientX);
      const move = (ev) => seek(ev.clientX);
      const up = () => {
        this.bodyEl.removeEventListener("pointermove", move);
        this.bodyEl.removeEventListener("pointerup", up);
        this.bodyEl.removeEventListener("pointercancel", up);
      };
      this.bodyEl.addEventListener("pointermove", move);
      this.bodyEl.addEventListener("pointerup", up);
      this.bodyEl.addEventListener("pointercancel", up);
    }

    beginDrag(e, ctx) {
      if (!ctx.clip || !ctx.el) return;
      const { clip, track } = ctx;
      this.bodyEl.setPointerCapture(e.pointerId);
      this.drag = {
        mode: ctx.mode,
        clip,
        track,
        el: ctx.el,
        startX: e.clientX,
        orig: {
          start: clip.timelineStart || 0,
          in: clip.sourceIn || 0,
          out: clip.sourceOut || 0,
        },
        pending: null,
      };
      const move = (ev) => this.onDragMove(ev);
      const up = () => {
        this.bodyEl.removeEventListener("pointermove", move);
        this.bodyEl.removeEventListener("pointerup", up);
        this.bodyEl.removeEventListener("pointercancel", up);
        this.endDrag();
      };
      this.bodyEl.addEventListener("pointermove", move);
      this.bodyEl.addEventListener("pointerup", up);
      this.bodyEl.addEventListener("pointercancel", up);
    }

    onDragMove(e) {
      const drag = this.drag;
      if (!drag) return;
      const dx = e.clientX - drag.startX;
      const track = drag.track;
      const clip = drag.clip;
      const neighbors = this.neighbors(track, clip.id);
      const asset = this.getAssets().get(clip.assetId);
      const assetDuration = asset && asset.duration ? asset.duration : null;

      if (drag.mode === "move") {
        const dur = clipDuration(clip);
        let start = this.snap(drag.orig.start + dx / this.pps, track, clip.id);
        start = Math.max(0, start);
        if (neighbors.prevEnd != null) start = Math.max(start, neighbors.prevEnd);
        if (neighbors.nextStart != null) start = Math.min(start, neighbors.nextStart - dur);
        start = Math.max(0, start);
        drag.pending = { start };
        drag.el.style.left = start * this.pps + "px";
        drag.el.style.width = Math.max(6, dur * this.pps) + "px";
        return;
      }

      if (drag.mode === "trim-left") {
        let start = this.snap(drag.orig.start + dx / this.pps, track, clip.id);
        start = Math.max(0, start);
        if (neighbors.prevEnd != null) start = Math.max(start, neighbors.prevEnd);
        let sourceIn = drag.orig.in + (start - drag.orig.start);
        if (sourceIn < 0) {
          start -= sourceIn;
          sourceIn = 0;
        }
        if (sourceIn > drag.orig.out - 0.05) {
          sourceIn = drag.orig.out - 0.05;
          start = drag.orig.start + (sourceIn - drag.orig.in);
        }
        drag.pending = { start, in: sourceIn, out: drag.orig.out };
        drag.el.style.left = start * this.pps + "px";
        drag.el.style.width = Math.max(6, (drag.orig.out - sourceIn) * this.pps) + "px";
        return;
      }

      // trim-right
      const end = this.snap(clipEnd({ timelineStart: drag.orig.start, sourceIn: drag.orig.in, sourceOut: drag.orig.out }) + dx / this.pps, track, clip.id);
      let sourceOut = drag.orig.out + (end - (drag.orig.start + (drag.orig.out - drag.orig.in)));
      if (neighbors.nextStart != null) {
        const maxOut = drag.orig.out + (neighbors.nextStart - (drag.orig.start + (drag.orig.out - drag.orig.in)));
        sourceOut = Math.min(sourceOut, maxOut);
      }
      if (assetDuration != null) sourceOut = Math.min(sourceOut, assetDuration);
      sourceOut = Math.max(sourceOut, drag.orig.in + 0.05);
      drag.pending = { start: drag.orig.start, in: drag.orig.in, out: sourceOut };
      drag.el.style.width = Math.max(6, (sourceOut - drag.orig.in) * this.pps) + "px";
    }

    endDrag() {
      const drag = this.drag;
      this.drag = null;
      if (!drag || !drag.pending) return;
      const clip = drag.clip;
      if (drag.pending.start != null) clip.timelineStart = round3(drag.pending.start);
      if (drag.pending.in != null) clip.sourceIn = round3(drag.pending.in);
      if (drag.pending.out != null) clip.sourceOut = round3(drag.pending.out);
      this.render();
      this.onChange({ type: drag.mode, clipId: clip.id, trackId: drag.track.id });
    }

    neighbors(track, clipId) {
      const moving = this.findClip(track.id, clipId);
      const movingStart = moving ? moving.timelineStart || 0 : 0;
      const clips = (track.clips || []).slice().sort((a, b) => (a.timelineStart || 0) - (b.timelineStart || 0));
      let prevEnd = null;
      let nextStart = null;
      for (const c of clips) {
        if (c.id === clipId) continue;
        const start = c.timelineStart || 0;
        if (start < movingStart - 1e-6) {
          prevEnd = Math.max(prevEnd ?? -Infinity, clipEnd(c));
        } else if (start > movingStart + 1e-6) {
          nextStart = Math.min(nextStart ?? Infinity, start);
        }
      }
      if (prevEnd === -Infinity) prevEnd = null;
      if (nextStart === Infinity) nextStart = null;
      return { prevEnd, nextStart };
    }

    snap(time, track, movingClipId) {
      const candidates = [0, this.getPlayhead()];
      for (const c of track.clips || []) {
        if (c.id === movingClipId) continue;
        candidates.push(c.timelineStart || 0, clipEnd(c));
      }
      const threshold = SNAP_PX / this.pps;
      let best = time;
      let bestDelta = threshold;
      for (const c of candidates) {
        const delta = Math.abs(c - time);
        if (delta < bestDelta) {
          bestDelta = delta;
          best = c;
        }
      }
      return best;
    }
  }

  function round3(v) {
    return Math.round(v * 1000) / 1000;
  }

  window.MotionTimeline = Timeline;
  window.MotionTimelineUtils = { formatClock, clipDuration, clipEnd, projectDuration };
})();
