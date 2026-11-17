package timeline

import (
	"fmt"
	"sort"
	"strconv"

	"github.com/SwayKh/Motion/internal/models"
)

// RenderPlan is the outcome of translating a timeline into work the existing
// job pipeline can execute.
type RenderPlan struct {
	// PrimaryVideoID is the owned video the job is recorded against.
	PrimaryVideoID string
	// Request is the flat operation list handed to the job worker.
	Request models.ProcessRequest
	// Duration is the expected output length in seconds.
	Duration float64
}

// visualTrack groups the visible clips of one composited (video/image/text)
// track, tagged with its layer order.
type visualTrack struct {
	order int
	typ   TrackType
	clips []Clip
}

// BuildRenderPlan translates a validated timeline into a process request.
//
// Two shapes are produced:
//
//   - The proven single-source pipeline for the simple case: one visible video
//     track whose clips start at the origin and are contiguous. One clip becomes
//     a slice, several become a concat, and audio clips become add_audio
//     operations.
//   - A single compose operation for everything else: several video tracks
//     (picture-in-picture), text overlays, timeline gaps, or a multi-clip join
//     that also carries audio. The worker turns a compose plan into an FFmpeg
//     filter_complex graph.
//
// Keeping the simple case on the original path avoids re-encoding material that
// does not need it and keeps the fast path's output familiar.
func BuildRenderPlan(p *Project, resolve AssetResolver) (RenderPlan, error) {
	if p == nil {
		return RenderPlan{}, fmt.Errorf("the project is empty")
	}

	var visual []visualTrack
	var audioClips []Clip

	for ti := range p.Tracks {
		track := &p.Tracks[ti]
		switch track.Type {
		case TrackVideo, TrackImage, TrackText:
			if !track.Visible {
				continue
			}
			if clips := SortedClips(*track); len(clips) > 0 {
				visual = append(visual, visualTrack{order: track.Order, typ: track.Type, clips: clips})
			}
		case TrackAudio:
			if track.Muted {
				continue
			}
			audioClips = append(audioClips, SortedClips(*track)...)
		}
	}

	sort.SliceStable(visual, func(i, j int) bool { return visual[i].order < visual[j].order })

	// The lowest video track is the background canvas. It must exist and begin
	// at the origin so the output has a defined frame at t=0.
	baseIndex := -1
	for i := range visual {
		if visual[i].typ == TrackVideo {
			baseIndex = i
			break
		}
	}
	if baseIndex == -1 {
		return RenderPlan{}, fmt.Errorf("the project has no visible video clip to render")
	}
	baseClips := visual[baseIndex].clips
	if baseClips[0].TimelineStart > epsilon {
		return RenderPlan{}, fmt.Errorf("the renderer needs a video clip that starts at the timeline origin")
	}
	if baseClips[0].AssetID == "" {
		return RenderPlan{}, fmt.Errorf("the first video clip does not reference an asset")
	}

	primary := baseClips[0].AssetID
	if resolve != nil {
		if _, ok := resolve(primary); !ok {
			return RenderPlan{}, fmt.Errorf("the first video clip references an asset that is missing or not accessible")
		}
	}

	sort.SliceStable(audioClips, func(i, j int) bool {
		return audioClips[i].TimelineStart < audioClips[j].TimelineStart
	})

	simple := len(visual) == 1 && baseContiguous(baseClips) && (len(baseClips) == 1 || len(audioClips) == 0)
	if simple {
		return buildSimplePlan(p, baseClips, audioClips, primary, resolve)
	}

	plan, err := buildComposePlan(p, visual, baseIndex, audioClips, resolve)
	if err != nil {
		return RenderPlan{}, err
	}
	return RenderPlan{
		PrimaryVideoID: primary,
		Request:        models.ProcessRequest{Operations: []models.Operation{{Type: "compose", Compose: plan}}},
		Duration:       ProjectDuration(p),
	}, nil
}

// baseContiguous reports whether the clips on the background track play back to
// back with no gap.
func baseContiguous(clips []Clip) bool {
	for i := 1; i < len(clips); i++ {
		if !nearlyEqual(clips[i].TimelineStart, clips[i-1].TimelineEnd()) {
			return false
		}
	}
	return true
}

// buildSimplePlan emits the original single-source operation list.
func buildSimplePlan(p *Project, clips, audioClips []Clip, primary string, resolve AssetResolver) (RenderPlan, error) {
	var ops []models.Operation

	if len(clips) == 1 {
		ops = append(ops, models.Operation{
			Type:  "slice",
			Start: formatSeconds(clips[0].SourceIn),
			End:   formatSeconds(clips[0].SourceOut),
		})
	} else {
		joined := make([]models.Clip, len(clips))
		for i, c := range clips {
			joined[i] = models.Clip{
				VideoID: c.AssetID,
				Start:   formatSeconds(c.SourceIn),
				End:     formatSeconds(c.SourceOut),
			}
		}
		ops = append(ops, models.Operation{Type: "concat", Clips: joined})
	}

	for _, c := range audioClips {
		if c.Volume <= 0 {
			continue
		}
		if c.AssetID == "" {
			return RenderPlan{}, fmt.Errorf("an audio clip does not reference an asset")
		}
		if resolve != nil {
			if _, ok := resolve(c.AssetID); !ok {
				return RenderPlan{}, fmt.Errorf("an audio clip references an asset that is missing or not accessible")
			}
		}
		ops = append(ops, models.Operation{
			Type:          "add_audio",
			AudioSourceID: c.AssetID,
			AudioVolume:   c.Volume,
			Offset:        formatSeconds(c.TimelineStart),
			AudioStart:    formatSeconds(c.SourceIn),
			AudioEnd:      formatSeconds(c.SourceOut),
		})
	}

	return RenderPlan{
		PrimaryVideoID: primary,
		Request:        models.ProcessRequest{Operations: ops},
		Duration:       ProjectDuration(p),
	}, nil
}

// buildComposePlan describes the whole timeline as an ordered stack of layers
// plus an audio mix.
func buildComposePlan(p *Project, visual []visualTrack, baseIndex int, audioClips []Clip, resolve AssetResolver) (*models.ComposePlan, error) {
	plan := &models.ComposePlan{
		Width:    p.Render.Width,
		Height:   p.Render.Height,
		FPS:      p.Render.FPS,
		Duration: formatSeconds(ProjectDuration(p)),
	}

	for i := range visual {
		for _, c := range visual[i].clips {
			layer := models.ComposeLayer{
				VideoID:       c.AssetID,
				SourceIn:      formatSeconds(c.SourceIn),
				SourceOut:     formatSeconds(c.SourceOut),
				TimelineStart: formatSeconds(c.TimelineStart),
				TimelineEnd:   formatSeconds(c.TimelineEnd()),
				X:             c.X,
				Y:             c.Y,
				Scale:         c.Scale,
				Opacity:       c.Opacity,
			}

			switch visual[i].typ {
			case TrackText:
				layer.Kind = "text"
				layer.Text = c.Text
				layer.VideoID = ""
			case TrackImage:
				layer.Kind = "image"
			default:
				layer.Kind = "video"
				layer.Base = i == baseIndex
			}

			if layer.Kind != "text" {
				if layer.VideoID == "" {
					return nil, fmt.Errorf("a %s clip does not reference an asset", layer.Kind)
				}
				if resolve != nil {
					if _, ok := resolve(layer.VideoID); !ok {
						return nil, fmt.Errorf("a %s clip references an asset that is missing or not accessible", layer.Kind)
					}
				}
			}
			plan.Layers = append(plan.Layers, layer)
		}
	}

	// The background video keeps its own audio; overlay video is visual only.
	for _, c := range visual[baseIndex].clips {
		if c.Volume <= 0 || c.AssetID == "" {
			continue
		}
		plan.Audio = append(plan.Audio, models.ComposeAudio{
			VideoID:       c.AssetID,
			SourceIn:      formatSeconds(c.SourceIn),
			SourceOut:     formatSeconds(c.SourceOut),
			TimelineStart: formatSeconds(c.TimelineStart),
			Volume:        c.Volume,
		})
	}
	for _, c := range audioClips {
		if c.Volume <= 0 {
			continue
		}
		if c.AssetID == "" {
			return nil, fmt.Errorf("an audio clip does not reference an asset")
		}
		if resolve != nil {
			if _, ok := resolve(c.AssetID); !ok {
				return nil, fmt.Errorf("an audio clip references an asset that is missing or not accessible")
			}
		}
		plan.Audio = append(plan.Audio, models.ComposeAudio{
			VideoID:       c.AssetID,
			SourceIn:      formatSeconds(c.SourceIn),
			SourceOut:     formatSeconds(c.SourceOut),
			TimelineStart: formatSeconds(c.TimelineStart),
			Volume:        c.Volume,
		})
	}
	plan.HasAudio = len(plan.Audio) > 0

	return plan, nil
}

// formatSeconds renders a seconds value without trailing zeros so it round
// trips through the timestamp parser.
func formatSeconds(value float64) string {
	return strconv.FormatFloat(value, 'f', -1, 64)
}
