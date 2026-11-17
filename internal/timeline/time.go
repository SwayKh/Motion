package timeline

import "sort"

// Duration returns how long the clip plays on the timeline.
func (c Clip) Duration() float64 {
	d := c.SourceOut - c.SourceIn
	if d < 0 {
		return 0
	}
	return d
}

// TimelineEnd returns the exclusive end of the clip on the timeline.
func (c Clip) TimelineEnd() float64 {
	return c.TimelineStart + c.Duration()
}

// Contains reports whether a timeline time falls inside the clip.
func (c Clip) Contains(timelineTime float64) bool {
	return timelineTime >= c.TimelineStart && timelineTime < c.TimelineEnd()
}

// SourceToTimeline converts a time inside the asset to a timeline time.
func SourceToTimeline(c Clip, sourceTime float64) float64 {
	return c.TimelineStart + (sourceTime - c.SourceIn)
}

// TimelineToSource converts a timeline time to a time inside the asset.
func TimelineToSource(c Clip, timelineTime float64) float64 {
	return c.SourceIn + (timelineTime - c.TimelineStart)
}

// SortedClips returns a copy of the track's clips ordered by timeline start.
func SortedClips(t Track) []Clip {
	clips := make([]Clip, len(t.Clips))
	copy(clips, t.Clips)
	sort.SliceStable(clips, func(i, j int) bool {
		return clips[i].TimelineStart < clips[j].TimelineStart
	})
	return clips
}

// ClipAt returns the clip covering a timeline time, or nil.
func ClipAt(t Track, timelineTime float64) *Clip {
	clips := SortedClips(t)
	for i := range clips {
		if clips[i].Contains(timelineTime) {
			return &clips[i]
		}
	}
	return nil
}

// ProjectDuration is the end of the last clip across every track.
func ProjectDuration(p *Project) float64 {
	duration := 0.0
	for ti := range p.Tracks {
		for ci := range p.Tracks[ti].Clips {
			if end := p.Tracks[ti].Clips[ci].TimelineEnd(); end > duration {
				duration = end
			}
		}
	}
	return duration
}

// FindClip locates a clip by id, returning its track and clip indexes.
func FindClip(p *Project, clipID string) (trackIndex, clipIndex int, ok bool) {
	for ti := range p.Tracks {
		for ci := range p.Tracks[ti].Clips {
			if p.Tracks[ti].Clips[ci].ID == clipID {
				return ti, ci, true
			}
		}
	}
	return -1, -1, false
}

// TrackByType returns the first track of the given type, or nil.
func TrackByType(p *Project, t TrackType) *Track {
	for i := range p.Tracks {
		if p.Tracks[i].Type == t {
			return &p.Tracks[i]
		}
	}
	return nil
}
