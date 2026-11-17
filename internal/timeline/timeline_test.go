package timeline

import (
	"encoding/json"
	"strings"
	"testing"
)

func validProject() *Project {
	return &Project{
		Name:          "Test",
		SchemaVersion: CurrentSchemaVersion,
		Tracks: []Track{
			{
				ID:      "v1",
				Type:    TrackVideo,
				Order:   0,
				Visible: true,
				Clips: []Clip{
					{
						ID: "c1", Kind: KindVideo, AssetID: "asset-1",
						SourceIn: 5, SourceOut: 15, TimelineStart: 20,
						Volume: 1, Opacity: 1, Scale: 1,
					},
				},
			},
		},
	}
}

// renderProject is a project whose single clip starts at the timeline origin,
// which the renderer requires.
func renderProject() *Project {
	return &Project{
		Name: "Render", SchemaVersion: CurrentSchemaVersion,
		Tracks: []Track{{
			ID: "v1", Type: TrackVideo, Order: 0, Visible: true,
			Clips: []Clip{
				{
					ID: "c1", Kind: KindVideo, AssetID: "asset-1",
					SourceIn: 5, SourceOut: 15, TimelineStart: 0,
					Volume: 1, Opacity: 1, Scale: 1,
				},
			},
		}},
	}
}

func TestClipTimeMath(t *testing.T) {
	clip := Clip{SourceIn: 5, SourceOut: 15, TimelineStart: 20}

	if got := clip.Duration(); got != 10 {
		t.Fatalf("duration = %v, want 10", got)
	}
	if got := clip.TimelineEnd(); got != 30 {
		t.Fatalf("timeline end = %v, want 30", got)
	}
	if got := SourceToTimeline(clip, 5); got != 20 {
		t.Fatalf("source 5 -> timeline %v, want 20", got)
	}
	if got := SourceToTimeline(clip, 15); got != 30 {
		t.Fatalf("source 15 -> timeline %v, want 30", got)
	}
	if got := TimelineToSource(clip, 20); got != 5 {
		t.Fatalf("timeline 20 -> source %v, want 5", got)
	}
	if got := TimelineToSource(clip, 30); got != 15 {
		t.Fatalf("timeline 30 -> source %v, want 15", got)
	}
	// Round trip.
	for _, v := range []float64{0, 3.25, 7.5, 12} {
		if got := TimelineToSource(clip, SourceToTimeline(clip, v)); got != v {
			t.Fatalf("round trip %v -> %v", v, got)
		}
	}
	if !clip.Contains(20) || !clip.Contains(29.99) || clip.Contains(30) || clip.Contains(19.99) {
		t.Fatal("Contains boundaries are wrong")
	}
}

func TestProjectDurationAndLookup(t *testing.T) {
	p := validProject()
	p.Tracks = append(p.Tracks, Track{
		ID: "a1", Type: TrackAudio, Order: 1, Visible: true,
		Clips: []Clip{
			{ID: "ac1", Kind: KindAudio, AssetID: "asset-2", SourceIn: 0, SourceOut: 4, TimelineStart: 25, Volume: 1, Opacity: 1, Scale: 1},
		},
	})

	if got := ProjectDuration(p); got != 30 {
		t.Fatalf("project duration = %v, want 30", got)
	}

	ti, ci, ok := FindClip(p, "ac1")
	if !ok || ti != 1 || ci != 0 {
		t.Fatalf("FindClip = (%d,%d,%v), want (1,0,true)", ti, ci, ok)
	}
	if _, _, ok := FindClip(p, "nope"); ok {
		t.Fatal("FindClip found a missing clip")
	}

	audio := TrackByType(p, TrackAudio)
	if audio == nil || audio.ID != "a1" {
		t.Fatalf("TrackByType audio = %v", audio)
	}
}

func TestJSONDefaults(t *testing.T) {
	raw := `{
		"name": "From JSON",
		"tracks": [
			{"id":"v1","type":"video","clips":[{"id":"c1","kind":"video","assetId":"a","sourceIn":0,"sourceOut":5,"timelineStart":0}]},
			{"id":"a1","type":"audio","visible":false,"muted":true,"clips":[]}
		]
	}`
	var p Project
	if err := json.Unmarshal([]byte(raw), &p); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}

	clip := p.Tracks[0].Clips[0]
	if clip.Volume != DefaultVolume || clip.Opacity != DefaultOpacity || clip.Scale != DefaultScale {
		t.Fatalf("omitted clip defaults = volume %v opacity %v scale %v", clip.Volume, clip.Opacity, clip.Scale)
	}
	if !p.Tracks[0].Visible {
		t.Fatal("a track without an explicit visible flag should default to visible")
	}
	if p.Tracks[1].Visible {
		t.Fatal("an explicit visible:false must be preserved")
	}
	if !p.Tracks[1].Muted {
		t.Fatal("an explicit muted:true must be preserved")
	}
}

func TestValidateStructure(t *testing.T) {
	tests := []struct {
		name      string
		mutate    func(*Project)
		wantCode  string
		wantValid bool
	}{
		{
			name:      "valid project",
			mutate:    func(*Project) {},
			wantValid: true,
		},
		{
			name:     "missing name",
			mutate:   func(p *Project) { p.Name = "  " },
			wantCode: "missing_name",
		},
		{
			name:     "empty source range",
			mutate:   func(p *Project) { p.Tracks[0].Clips[0].SourceOut = 5 },
			wantCode: "invalid_source_range",
		},
		{
			name: "overlapping clips",
			mutate: func(p *Project) {
				p.Tracks[0].Clips = append(p.Tracks[0].Clips, Clip{
					ID: "c2", Kind: KindVideo, AssetID: "asset-1",
					SourceIn: 0, SourceOut: 5, TimelineStart: 25,
					Volume: 1, Opacity: 1, Scale: 1,
				})
			},
			wantCode: "overlapping_clips",
		},
		{
			name: "negative timeline start",
			mutate: func(p *Project) {
				p.Tracks[0].Clips[0].TimelineStart = -1
			},
			wantCode: "negative_start",
		},
		{
			name: "bad opacity",
			mutate: func(p *Project) {
				p.Tracks[0].Clips[0].Opacity = 1.5
			},
			wantCode: "invalid_opacity",
		},
		{
			name: "invalid track type",
			mutate: func(p *Project) {
				p.Tracks[0].Type = "hologram"
			},
			wantCode: "invalid_track_type",
		},
		{
			name: "clip on wrong track",
			mutate: func(p *Project) {
				p.Tracks[0].Clips[0].Kind = KindAudio
			},
			wantCode: "clip_track_mismatch",
		},
		{
			name: "missing asset reference",
			mutate: func(p *Project) {
				p.Tracks[0].Clips[0].AssetID = ""
			},
			wantCode: "missing_asset",
		},
		{
			name: "empty text clip",
			mutate: func(p *Project) {
				p.Tracks = []Track{{
					ID: "t1", Type: TrackText, Order: 0, Visible: true,
					Clips: []Clip{{ID: "tx", Kind: KindText, SourceIn: 0, SourceOut: 2, TimelineStart: 0, Scale: 1, Opacity: 1}},
				}}
			},
			wantCode: "empty_text",
		},
		{
			name: "unsupported schema",
			mutate: func(p *Project) {
				p.SchemaVersion = 99
			},
			wantCode: "unsupported_schema",
		},
		{
			name: "duplicate track order",
			mutate: func(p *Project) {
				p.Tracks = append(p.Tracks, Track{
					ID: "v2", Type: TrackVideo, Order: 0, Visible: true,
					Clips: []Clip{{ID: "c9", Kind: KindVideo, AssetID: "a", SourceIn: 0, SourceOut: 1, TimelineStart: 0, Volume: 1, Opacity: 1, Scale: 1}},
				})
			},
			wantCode: "duplicate_track_order",
		},
		{
			name: "no clips",
			mutate: func(p *Project) {
				p.Tracks[0].Clips = nil
			},
			wantCode: "no_clips",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			p := validProject()
			tt.mutate(p)
			errs := ValidateStructure(p)
			if tt.wantValid {
				if len(errs) != 0 {
					t.Fatalf("expected valid, got %v", errs)
				}
				return
			}
			if !hasCode(errs, tt.wantCode) {
				t.Fatalf("expected code %q, got %v", tt.wantCode, errs)
			}
		})
	}
}

func TestValidateAssets(t *testing.T) {
	resolve := func(id string) (Asset, bool) {
		switch id {
		case "asset-1":
			return Asset{ID: id, Duration: 30, HasVideo: true, HasAudio: true}, true
		case "audio-1":
			return Asset{ID: id, Duration: 10, HasAudio: true}, true
		default:
			return Asset{}, false
		}
	}

	if errs := Validate(validProject(), resolve); len(errs) != 0 {
		t.Fatalf("valid project reported errors: %v", errs)
	}

	missing := validProject()
	missing.Tracks[0].Clips[0].AssetID = "ghost"
	if errs := Validate(missing, resolve); !hasCode(errs, "asset_unavailable") {
		t.Fatalf("missing asset not reported: %v", errs)
	}

	tooLong := validProject()
	tooLong.Tracks[0].Clips[0].SourceOut = 99
	if errs := Validate(tooLong, resolve); !hasCode(errs, "source_out_of_range") {
		t.Fatalf("out of range source not reported: %v", errs)
	}

	// A resolver that never resolves (another user's asset) must fail closed.
	if errs := Validate(validProject(), func(string) (Asset, bool) { return Asset{}, false }); !hasCode(errs, "asset_unavailable") {
		t.Fatalf("unowned asset not reported: %v", errs)
	}
}

func TestBuildRenderPlanSingleClip(t *testing.T) {
	resolve := func(id string) (Asset, bool) {
		return Asset{ID: id, Duration: 30, HasVideo: true, HasAudio: true}, true
	}

	p := renderProject()
	plan, err := BuildRenderPlan(p, resolve)
	if err != nil {
		t.Fatalf("BuildRenderPlan: %v", err)
	}
	if plan.PrimaryVideoID != "asset-1" {
		t.Fatalf("primary = %q", plan.PrimaryVideoID)
	}
	if plan.Duration != 10 {
		t.Fatalf("duration = %v, want 10", plan.Duration)
	}
	if len(plan.Request.Operations) != 1 {
		t.Fatalf("operations = %v", plan.Request.Operations)
	}
	op := plan.Request.Operations[0]
	if op.Type != "slice" || op.Start != "5" || op.End != "15" {
		t.Fatalf("slice op = %+v", op)
	}
}

func TestBuildRenderPlanConcat(t *testing.T) {
	resolve := func(id string) (Asset, bool) {
		return Asset{ID: id, Duration: 30, HasVideo: true, HasAudio: true}, true
	}

	p := &Project{
		Name: "join", SchemaVersion: CurrentSchemaVersion,
		Tracks: []Track{{
			ID: "v1", Type: TrackVideo, Order: 0, Visible: true,
			Clips: []Clip{
				{ID: "c1", Kind: KindVideo, AssetID: "a1", SourceIn: 0, SourceOut: 5, TimelineStart: 0, Volume: 1, Opacity: 1, Scale: 1},
				{ID: "c2", Kind: KindVideo, AssetID: "a2", SourceIn: 1, SourceOut: 4, TimelineStart: 5, Volume: 1, Opacity: 1, Scale: 1},
			},
		}},
	}

	plan, err := BuildRenderPlan(p, resolve)
	if err != nil {
		t.Fatalf("BuildRenderPlan: %v", err)
	}
	if plan.PrimaryVideoID != "a1" {
		t.Fatalf("primary = %q", plan.PrimaryVideoID)
	}
	if plan.Duration != 8 {
		t.Fatalf("duration = %v, want 8", plan.Duration)
	}
	if len(plan.Request.Operations) != 1 || plan.Request.Operations[0].Type != "concat" {
		t.Fatalf("operations = %+v", plan.Request.Operations)
	}
	clips := plan.Request.Operations[0].Clips
	if len(clips) != 2 || clips[0].VideoID != "a1" || clips[1].VideoID != "a2" {
		t.Fatalf("concat clips = %+v", clips)
	}
	if clips[0].Start != "0" || clips[0].End != "5" || clips[1].Start != "1" || clips[1].End != "4" {
		t.Fatalf("concat clip ranges = %+v", clips)
	}
}

func TestBuildRenderPlanAudio(t *testing.T) {
	resolve := func(id string) (Asset, bool) {
		return Asset{ID: id, Duration: 30, HasVideo: true, HasAudio: true}, true
	}

	p := renderProject()
	p.Tracks = append(p.Tracks, Track{
		ID: "a1", Type: TrackAudio, Order: 1, Visible: true,
		Clips: []Clip{
			{ID: "ac1", Kind: KindAudio, AssetID: "song", SourceIn: 2, SourceOut: 12, TimelineStart: 3, Volume: 0.5, Opacity: 1, Scale: 1},
		},
	})

	plan, err := BuildRenderPlan(p, resolve)
	if err != nil {
		t.Fatalf("BuildRenderPlan: %v", err)
	}
	if len(plan.Request.Operations) != 2 {
		t.Fatalf("operations = %+v", plan.Request.Operations)
	}
	audio := plan.Request.Operations[1]
	if audio.Type != "add_audio" || audio.AudioSourceID != "song" {
		t.Fatalf("audio op = %+v", audio)
	}
	if audio.AudioVolume != 0.5 || audio.Offset != "3" || audio.AudioStart != "2" || audio.AudioEnd != "12" {
		t.Fatalf("audio op fields = %+v", audio)
	}
}

func TestBuildRenderPlanComposeOverlays(t *testing.T) {
	resolve := func(id string) (Asset, bool) {
		return Asset{ID: id, Duration: 30, HasVideo: true, HasAudio: true}, true
	}

	p := renderProject()
	// A second video track becomes a picture-in-picture layer.
	p.Tracks = append(p.Tracks, Track{
		ID: "v2", Type: TrackVideo, Order: 1, Visible: true,
		Clips: []Clip{{
			ID: "pip", Kind: KindVideo, AssetID: "overlay", SourceIn: 0, SourceOut: 3,
			TimelineStart: 1, Volume: 1, Opacity: 0.8, Scale: 0.3, X: 10, Y: 20,
		}},
	})
	// A text track becomes a drawtext layer.
	p.Tracks = append(p.Tracks, Track{
		ID: "txt", Type: TrackText, Order: 2, Visible: true,
		Clips: []Clip{{
			ID: "t", Kind: KindText, SourceIn: 0, SourceOut: 4,
			TimelineStart: 0, Volume: 1, Opacity: 1, Scale: 1, X: 8, Y: 8, Text: "Hi",
		}},
	})

	plan, err := BuildRenderPlan(p, resolve)
	if err != nil {
		t.Fatalf("BuildRenderPlan: %v", err)
	}
	if len(plan.Request.Operations) != 1 || plan.Request.Operations[0].Type != "compose" {
		t.Fatalf("operations = %+v", plan.Request.Operations)
	}
	compose := plan.Request.Operations[0].Compose
	if compose == nil {
		t.Fatal("compose plan is nil")
	}
	if len(compose.Layers) != 3 {
		t.Fatalf("layers = %+v", compose.Layers)
	}
	if !compose.Layers[0].Base || compose.Layers[0].Kind != "video" {
		t.Fatalf("first layer = %+v", compose.Layers[0])
	}
	pip := compose.Layers[1]
	if pip.Base || pip.Kind != "video" || pip.Scale != 0.3 || pip.X != 10 || pip.Y != 20 {
		t.Fatalf("pip layer = %+v", pip)
	}
	if compose.Layers[2].Kind != "text" || compose.Layers[2].Text != "Hi" {
		t.Fatalf("text layer = %+v", compose.Layers[2])
	}
	// The base clip's own audio is preserved in the mix.
	if len(compose.Audio) == 0 || compose.Audio[0].VideoID != "asset-1" {
		t.Fatalf("audio = %+v", compose.Audio)
	}
	if !compose.HasAudio {
		t.Fatal("expected HasAudio")
	}
}

func TestBuildRenderPlanComposeGapAndMultiClipAudio(t *testing.T) {
	resolve := func(id string) (Asset, bool) {
		return Asset{ID: id, Duration: 30, HasVideo: true, HasAudio: true}, true
	}

	// Two contiguous clips plus audio: the simple path cannot mix audio into a
	// concat, so a compose plan is produced instead.
	p := renderProject()
	p.Tracks[0].Clips = append(p.Tracks[0].Clips, Clip{
		ID: "c2", Kind: KindVideo, AssetID: "asset-1", SourceIn: 15, SourceOut: 20,
		TimelineStart: 10, Volume: 1, Opacity: 1, Scale: 1,
	})
	p.Tracks = append(p.Tracks, Track{
		ID: "a1", Type: TrackAudio, Order: 1, Visible: true,
		Clips: []Clip{{
			ID: "ac", Kind: KindAudio, AssetID: "song", SourceIn: 0, SourceOut: 5,
			TimelineStart: 2, Volume: 0.5, Opacity: 1, Scale: 1,
		}},
	})

	plan, err := BuildRenderPlan(p, resolve)
	if err != nil {
		t.Fatalf("BuildRenderPlan: %v", err)
	}
	if plan.Request.Operations[0].Type != "compose" {
		t.Fatalf("operations = %+v", plan.Request.Operations)
	}
	if len(plan.Request.Operations[0].Compose.Audio) != 3 {
		t.Fatalf("audio segments = %+v", plan.Request.Operations[0].Compose.Audio)
	}
}

func TestBuildRenderPlanRejections(t *testing.T) {
	resolve := func(id string) (Asset, bool) {
		return Asset{ID: id, Duration: 30, HasVideo: true, HasAudio: true}, true
	}

	base := func() *Project { return renderProject() }

	tests := []struct {
		name    string
		mutate  func(*Project)
		wantErr string
	}{
		{
			name:    "no video clip",
			mutate:  func(p *Project) { p.Tracks = nil },
			wantErr: "no visible video clip",
		},
		{
			name:    "initial gap",
			mutate:  func(p *Project) { p.Tracks[0].Clips[0].TimelineStart = 4 },
			wantErr: "starts at the timeline origin",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			p := base()
			tt.mutate(p)
			_, err := BuildRenderPlan(p, resolve)
			if err == nil || !strings.Contains(err.Error(), tt.wantErr) {
				t.Fatalf("error = %v, want contains %q", err, tt.wantErr)
			}
		})
	}
}

func TestBuildRenderPlanMissingAsset(t *testing.T) {
	_, err := BuildRenderPlan(renderProject(), func(string) (Asset, bool) { return Asset{}, false })
	if err == nil || !strings.Contains(err.Error(), "missing or not accessible") {
		t.Fatalf("error = %v", err)
	}
}

func TestNormalize(t *testing.T) {
	p := &Project{Tracks: []Track{{
		Type: TrackVideo,
		Clips: []Clip{{
			Kind: KindVideo, AssetID: "a",
			SourceIn: 0, SourceOut: 1, TimelineStart: 0,
			Scale: 0, Opacity: 2, Volume: -1,
		}},
	}}}
	p.Normalize()
	if p.SchemaVersion != CurrentSchemaVersion {
		t.Fatalf("schema version = %d", p.SchemaVersion)
	}
	clip := p.Tracks[0].Clips[0]
	if clip.Scale != DefaultScale {
		t.Fatalf("scale = %v", clip.Scale)
	}
	if clip.Opacity != 1 {
		t.Fatalf("opacity = %v", clip.Opacity)
	}
	if clip.Volume != 0 {
		t.Fatalf("volume = %v", clip.Volume)
	}
}

func hasCode(errs ValidationErrors, code string) bool {
	for _, e := range errs {
		if e.Code == code {
			return true
		}
	}
	return false
}
