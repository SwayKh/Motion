package service

import (
	"context"
	"errors"
	"fmt"
	"io"
	"mime/multipart"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"

	"github.com/SwayKh/Motion/internal/config"
	"github.com/SwayKh/Motion/internal/db"
	"github.com/SwayKh/Motion/internal/ffmpeg"
	"github.com/SwayKh/Motion/internal/logger"
	"github.com/SwayKh/Motion/internal/media"
	"github.com/SwayKh/Motion/internal/models"
	"github.com/SwayKh/Motion/internal/quota"
	"github.com/SwayKh/Motion/internal/storage"
	"github.com/SwayKh/Motion/internal/validation"

	"github.com/google/uuid"
)

var allowedVideoExtensions = map[string]struct{}{
	".mp4":  {},
	".m4v":  {},
	".mov":  {},
	".mkv":  {},
	".webm": {},
	".avi":  {},
	".mpeg": {},
	".mpg":  {},
	".3gp":  {},
}

// concatFrameRate is the frame rate every clip is normalised to before the
// concat demuxer joins them. A single fixed rate avoids timeline drift between
// clips that were recorded at different rates.
const concatFrameRate = 30

// VideoService handles uploads and (with ProcessJob) media processing.
type VideoService struct {
	store *db.VideoStore
	st    *storage.Manager
	quota *quota.Manager
	cfg   *config.Config
}

func NewVideoService(store *db.VideoStore, st *storage.Manager, quotaMgr *quota.Manager, cfg *config.Config) *VideoService {
	if st == nil {
		st = storage.Default()
	}
	if cfg == nil {
		cfg = config.Load()
	}
	return &VideoService{
		store: store,
		st:    st,
		quota: quotaMgr,
		cfg:   cfg,
	}
}

type uploadErrorKind int

const (
	uploadErrorInvalid uploadErrorKind = iota
	uploadErrorInternal
)

type uploadError struct {
	kind    uploadErrorKind
	message string
	cause   error
}

func (e *uploadError) Error() string {
	return e.message
}

func (e *uploadError) Unwrap() error {
	return e.cause
}

func invalidUploadError(message string, cause error) error {
	return &uploadError{kind: uploadErrorInvalid, message: message, cause: cause}
}

func internalUploadError(message string, cause error) error {
	return &uploadError{kind: uploadErrorInternal, message: message, cause: cause}
}

func IsUploadClientError(err error) bool {
	var uploadErr *uploadError
	return errors.As(err, &uploadErr) && uploadErr.kind == uploadErrorInvalid
}

// Upload validates and stores an uploaded file. The file is streamed to a
// scratch file so it can be probed before it is moved into place and
// (optionally) encrypted. userID owns the resulting record and is used for
// quota accounting; it may be empty for anonymous uploads.
func (s *VideoService) Upload(file multipart.File, header *multipart.FileHeader, userID string) (*models.Video, error) {
	defer file.Close()

	originalName := filepath.Base(header.Filename)
	ext := strings.ToLower(filepath.Ext(originalName))
	if ext == "" {
		return nil, invalidUploadError("file must have a file extension", nil)
	}
	if _, ok := allowedVideoExtensions[ext]; !ok {
		return nil, invalidUploadError(fmt.Sprintf(
			"unsupported file type %s; use MP4, MOV, MKV, WebM, AVI, M4V, MPEG, or 3GP",
			ext,
		), nil)
	}

	maxBytes := s.cfg.MaxUploadBytes
	if header.Size > maxBytes {
		return nil, invalidUploadError(fmt.Sprintf("file exceeds the %d MB limit", maxBytes>>20), nil)
	}

	release, err := s.acquireUploadQuota(userID, header.Size)
	if err != nil {
		return nil, err
	}
	defer release()

	id := uuid.NewString()

	tmp, err := s.st.NewTempFile("upload-*" + ext)
	if err != nil {
		logger.Error.Printf("creating upload scratch file: %v", err)
		return nil, internalUploadError("failed to save uploaded file", err)
	}
	tmpPath := tmp.Name()
	cleanupTemp := true
	defer func() {
		if cleanupTemp {
			_ = os.Remove(tmpPath)
		}
	}()

	written, err := tmp.ReadFrom(file)
	if closeErr := tmp.Close(); err == nil {
		err = closeErr
	}
	if err != nil {
		logger.Error.Printf("copying upload file: %v", err)
		return nil, internalUploadError("failed to save uploaded file", err)
	}
	if written > maxBytes {
		return nil, invalidUploadError(fmt.Sprintf("file exceeds the %d MB limit", maxBytes>>20), nil)
	}

	raw, err := ffmpeg.NewProcessor(tmpPath).GetMetadata()
	if err != nil {
		logger.Error.Printf("probing video metadata: %v", err)
		if ffmpeg.IsProbeUnavailableError(err) {
			return nil, internalUploadError("media probing is unavailable", err)
		}
		return nil, invalidUploadError("unable to read video metadata; the file may be unsupported or corrupt", err)
	}

	meta, err := media.ExtractMetaData(raw)
	if err != nil {
		logger.Error.Printf("extracting video metadata: %v", err)
		return nil, internalUploadError("failed to read video metadata", err)
	}

	if meta.Duration > s.cfg.MaxDurationSeconds {
		return nil, invalidUploadError(fmt.Sprintf("video exceeds the %d minute limit", int(s.cfg.MaxDurationSeconds)/60), nil)
	}
	if meta.Width > 0 && (meta.Width > s.cfg.MaxWidth || meta.Height > s.cfg.MaxHeight) {
		return nil, invalidUploadError(fmt.Sprintf("video exceeds the %dp limit", s.cfg.MaxHeight), nil)
	}
	if meta.Width == 0 {
		return nil, invalidUploadError("file does not contain a video stream", nil)
	}

	filename := id + ext
	storedPath, encrypted, wrappedKey, err := s.st.StoreUpload(tmpPath, filename)
	if err != nil {
		logger.Error.Printf("storing upload: %v", err)
		return nil, internalUploadError("failed to save uploaded file", err)
	}
	cleanupTemp = false

	video := &models.Video{
		ID: id,

		OriginalPath: storedPath,
		OriginalName: originalName,
		FileSize:     written,

		Status:   models.StatusUploaded,
		Progress: 0,

		Duration: meta.Duration,
		Width:    meta.Width,
		Height:   meta.Height,

		Encrypted: encrypted,
		EncKey:    wrappedKey,
	}
	if userID != "" {
		uid := userID
		video.UserID = &uid
	}

	if err := s.store.Create(video); err != nil {
		_ = s.st.Remove(storedPath)
		logger.Error.Printf("persisting uploaded video: %v", err)
		return nil, internalUploadError("failed to save video record", err)
	}

	return video, nil
}

func (s *VideoService) acquireUploadQuota(userID string, incomingBytes int64) (func(), error) {
	if s.quota == nil {
		return func() {}, nil
	}
	release, err := s.quota.AcquireUpload(userID, incomingBytes)
	if err != nil {
		if errors.Is(err, quota.ErrExceeded) {
			return nil, invalidUploadError(err.Error(), err)
		}
		return nil, internalUploadError("failed to check upload quota", err)
	}
	return release, nil
}

// ProcessResult describes a completed job output.
type ProcessResult struct {
	OutputPath string
	OutputType string
	Encrypted  bool
	EncKey     string
}

// SourceResolver loads a video record referenced by an operation (an audio
// track or an extra concat clip).
type SourceResolver func(videoID string) (*models.Video, error)

// ProcessInput bundles everything ProcessJob needs. It is a struct rather than
// a long argument list so callers stay readable as the pipeline grows.
type ProcessInput struct {
	Job        *models.Job
	Video      *models.Video
	Storage    *storage.Manager
	Resolve    SourceResolver
	Request    models.ProcessRequest
	OnProgress func(float64)
}

// ProcessJob runs one persisted job. Database state transitions belong to the
// caller so cancellation and shutdown races can be resolved atomically.
func ProcessJob(ctx context.Context, in ProcessInput) (ProcessResult, error) {
	st := in.Storage
	if st == nil {
		st = storage.Default()
	}

	outputExt := ".mp4"
	outputType := "video"
	wantsAudio := false
	hasConcat := false
	hasCompose := false
	for _, op := range in.Request.Operations {
		if op.Type == "to_audio" {
			outputExt = ".mp3"
			outputType = "audio"
			wantsAudio = true
		}
		if op.Type == "concat" {
			hasConcat = true
		}
		if op.Type == "compose" {
			hasCompose = true
		}
	}

	result := ProcessResult{
		OutputPath: st.OutputPath(in.Job.ID + outputExt),
		OutputType: outputType,
	}

	if err := ctx.Err(); err != nil {
		return result, err
	}

	video := in.Video
	if video == nil {
		return result, fmt.Errorf("source video record is unavailable")
	}
	if video.OriginalPath == "" {
		return result, fmt.Errorf("source file is no longer available")
	}

	// Decrypt the primary source into a scratch file that FFmpeg can read.
	srcPath, srcCleanup, err := st.DecryptToTemp(video.OriginalPath, video.EncKey, video.Encrypted)
	if err != nil {
		logger.Error.Printf("job=%s preparing source: %v", in.Job.ID, err)
		return result, fmt.Errorf("source file is no longer available")
	}
	defer srcCleanup()

	if _, err := os.Stat(srcPath); err != nil {
		return result, fmt.Errorf("source file is no longer available")
	}

	raw, err := ffmpeg.NewProcessor(srcPath).GetMetadata()
	if err != nil {
		logger.Error.Printf("job=%s probing source metadata: %v", in.Job.ID, err)
		return result, fmt.Errorf("unable to inspect the source video")
	}
	meta, err := media.ExtractMetaData(raw)
	if err != nil {
		logger.Error.Printf("job=%s extracting source metadata: %v", in.Job.ID, err)
		return result, fmt.Errorf("unable to inspect the source video")
	}
	if wantsAudio && !meta.HasAudio {
		return result, fmt.Errorf("this video has no audio track to extract")
	}

	if err := os.MkdirAll(st.OutputDir(), 0o755); err != nil {
		return result, fmt.Errorf("creating output directory: %w", err)
	}

	tempPath := st.OutputPath("." + in.Job.ID + ".part" + outputExt)
	_ = os.Remove(tempPath)
	_ = os.Remove(result.OutputPath)

	if hasConcat {
		if err := processConcat(ctx, in, srcPath, meta, tempPath); err != nil {
			_ = os.Remove(tempPath)
			if ctxErr := ctx.Err(); ctxErr != nil {
				return result, ctxErr
			}
			logger.Error.Printf("job=%s concat failed err=%v", in.Job.ID, err)
			return result, fmt.Errorf("video processing failed; make sure every clip is a readable video")
		}
	} else if hasCompose {
		if err := processCompose(ctx, in, srcPath, meta, tempPath); err != nil {
			_ = os.Remove(tempPath)
			if ctxErr := ctx.Err(); ctxErr != nil {
				return result, ctxErr
			}
			logger.Error.Printf("job=%s compose failed err=%v", in.Job.ID, err)
			return result, fmt.Errorf("timeline rendering failed; %v", err)
		}
	} else {
		if err := processPipeline(ctx, in, srcPath, meta, tempPath); err != nil {
			_ = os.Remove(tempPath)
			if ctxErr := ctx.Err(); ctxErr != nil {
				return result, ctxErr
			}
			logger.Error.Printf("job=%s processing failed err=%v", in.Job.ID, err)
			return result, err
		}
	}

	if err := ctx.Err(); err != nil {
		_ = os.Remove(tempPath)
		return result, err
	}

	info, err := os.Stat(tempPath)
	if err != nil || info.Size() == 0 {
		_ = os.Remove(tempPath)
		return result, fmt.Errorf("video processing produced no output")
	}

	storedPath, encrypted, wrappedKey, err := st.EncryptOutput(tempPath, in.Job.ID+outputExt)
	if err != nil {
		_ = os.Remove(tempPath)
		return result, fmt.Errorf("saving processing output: %w", err)
	}
	result.OutputPath = storedPath
	result.Encrypted = encrypted
	result.EncKey = wrappedKey

	logger.Info.Printf("job=%s completed output=%s", in.Job.ID, result.OutputPath)
	return result, nil
}

// processPipeline applies the standard single-source operations to the primary
// video and writes the result to tempPath.
func processPipeline(ctx context.Context, in ProcessInput, srcPath string, meta *media.VideoMetadata, tempPath string) error {
	processor := ffmpeg.NewProcessorWithAudio(srcPath, meta.HasAudio)

	type audioTemp struct {
		path    string
		cleanup func()
	}
	var audioTemps []audioTemp
	defer func() {
		for _, t := range audioTemps {
			t.cleanup()
		}
	}()

	applied := 0
	for _, op := range in.Request.Operations {
		switch op.Type {
		case "trim", "slice":
			processor.Slice(op.Start, op.End)
			applied++
		case "remove_section":
			processor.RemoveSection(op.Start, op.End)
			applied++
		case "speed":
			if op.Factor <= 0 {
				return fmt.Errorf("speed factor must be greater than 0")
			}
			processor.Speed(op.Factor)
			applied++
		case "volume":
			if err := validation.ValidateVolumeFactor(op.Factor); err != nil {
				return err
			}
			if !meta.HasAudio {
				return fmt.Errorf("this video has no audio track to adjust")
			}
			processor.Volume(op.Factor)
			applied++
		case "flip":
			if !op.Direction.Valid() {
				return fmt.Errorf("invalid flip direction: %d", op.Direction)
			}
			processor.Flip(op.Direction)
			applied++
		case "to_audio":
			processor.ToAudio()
			applied++
		case "add_audio", "replace_audio":
			audio, err := resolveAudioSource(in, op, meta.Duration)
			if err != nil {
				return err
			}
			audioTemps = append(audioTemps, audioTemp{path: audio.path, cleanup: audio.cleanup})
			processor.AddAudio(ffmpeg.AudioInput{
				Path:          audio.path,
				Volume:        op.AudioVolume,
				OffsetSeconds: audio.offset,
				TrimStart:     audio.trimStart,
				TrimEnd:       audio.trimEnd,
				Mix:           op.Type == "add_audio",
			})
			applied++
		default:
			logger.Error.Printf("job=%s unknown operation type=%s", in.Job.ID, op.Type)
		}
	}

	if applied == 0 {
		return fmt.Errorf("no valid operations provided")
	}

	processor.OnProgress(func(progress float64) {
		if ctx.Err() != nil {
			return
		}
		if in.OnProgress != nil {
			in.OnProgress(progress)
		}
	})

	return processor.OutputContext(ctx, tempPath)
}

type resolvedAudio struct {
	path      string
	cleanup   func()
	offset    float64
	trimStart float64
	trimEnd   float64
}

func resolveAudioSource(in ProcessInput, op models.Operation, sourceDuration float64) (resolvedAudio, error) {
	if in.Resolve == nil {
		return resolvedAudio{}, fmt.Errorf("audio source is not available")
	}
	source, err := in.Resolve(op.AudioSourceID)
	if err != nil {
		return resolvedAudio{}, fmt.Errorf("audio source video is not available")
	}
	path, cleanup, err := in.Storage.DecryptToTemp(source.OriginalPath, source.EncKey, source.Encrypted)
	if err != nil {
		return resolvedAudio{}, fmt.Errorf("audio source video is not available")
	}

	raw, err := ffmpeg.NewProcessor(path).GetMetadata()
	if err != nil {
		cleanup()
		return resolvedAudio{}, fmt.Errorf("unable to inspect the audio source")
	}
	meta, err := media.ExtractMetaData(raw)
	if err != nil {
		cleanup()
		return resolvedAudio{}, fmt.Errorf("unable to inspect the audio source")
	}
	if !meta.HasAudio {
		cleanup()
		return resolvedAudio{}, fmt.Errorf("the selected audio source has no audio track")
	}

	resolved := resolvedAudio{path: path, cleanup: cleanup}
	if op.Offset != "" {
		seconds, err := ffmpeg.ParseTimecode(op.Offset)
		if err != nil {
			cleanup()
			return resolvedAudio{}, fmt.Errorf("invalid audio offset")
		}
		resolved.offset = seconds
	}
	if op.AudioStart != "" {
		seconds, err := ffmpeg.ParseTimecode(op.AudioStart)
		if err != nil {
			cleanup()
			return resolvedAudio{}, fmt.Errorf("invalid audio start")
		}
		resolved.trimStart = seconds
	}
	if op.AudioEnd != "" {
		seconds, err := ffmpeg.ParseTimecode(op.AudioEnd)
		if err != nil {
			cleanup()
			return resolvedAudio{}, fmt.Errorf("invalid audio end")
		}
		resolved.trimEnd = seconds
	}
	_ = sourceDuration
	return resolved, nil
}

// processConcat joins the clips listed in a concat operation. Every clip is
// normalised to a common resolution, frame rate and audio layout in a temporary
// MPEG-TS file; the concat demuxer then joins them without re-encoding.
func processConcat(ctx context.Context, in ProcessInput, srcPath string, meta *media.VideoMetadata, tempPath string) error {
	if in.Resolve == nil {
		return fmt.Errorf("clip sources are not available")
	}

	var concatOp *models.Operation
	for i := range in.Request.Operations {
		if in.Request.Operations[i].Type == "concat" {
			concatOp = &in.Request.Operations[i]
			break
		}
	}
	if concatOp == nil {
		return fmt.Errorf("no concat operation provided")
	}

	width, height := concatDimensions(in.Video, meta)
	fps := concatFrameRate

	scratch, err := os.MkdirTemp(in.Storage.TempDir(), "concat-*")
	if err != nil {
		if err := os.MkdirAll(in.Storage.TempDir(), 0o700); err != nil {
			return err
		}
		scratch, err = os.MkdirTemp(in.Storage.TempDir(), "concat-*")
		if err != nil {
			return err
		}
	}
	defer os.RemoveAll(scratch)

	var segments []string
	for i, clip := range concatOp.Clips {
		clipPath := srcPath
		clipHasAudio := meta.HasAudio
		cleanup := func() {}

		if clip.VideoID != "" && clip.VideoID != in.Video.ID {
			source, err := in.Resolve(clip.VideoID)
			if err != nil {
				return fmt.Errorf("clip %d is not available", i)
			}
			clipPath, cleanup, err = in.Storage.DecryptToTemp(source.OriginalPath, source.EncKey, source.Encrypted)
			if err != nil {
				return fmt.Errorf("clip %d is not available", i)
			}
			clipHasAudio = sourceHasAudio(clipPath)
		} else if clip.VideoID == "" && i > 0 {
			// Empty VideoID always means the job's own source.
			clipPath = srcPath
			clipHasAudio = meta.HasAudio
		}

		segment := filepath.Join(scratch, fmt.Sprintf("segment-%d.ts", i))
		if err := normalizeClip(ctx, clipPath, segment, clip.Start, clip.End, width, height, fps, clipHasAudio); err != nil {
			cleanup()
			return err
		}
		cleanup()
		segments = append(segments, segment)
	}

	listPath := filepath.Join(scratch, "list.txt")
	var listBuilder strings.Builder
	for _, segment := range segments {
		listBuilder.WriteString("file '" + strings.ReplaceAll(segment, "'", `'\''`) + "'\n")
	}
	if err := os.WriteFile(listPath, []byte(listBuilder.String()), 0o600); err != nil {
		return err
	}

	args := []string{
		"-hide_banner", "-loglevel", "error", "-y",
		"-f", "concat", "-safe", "0",
		"-i", listPath,
		"-c", "copy",
		"-bsf:a", "aac_adtstoasc",
		tempPath,
	}
	cmd := exec.CommandContext(ctx, "ffmpeg", args...)
	if output, err := cmd.CombinedOutput(); err != nil {
		return fmt.Errorf("concat: %w: %s", err, strings.TrimSpace(string(output)))
	}
	return nil
}

func concatDimensions(video *models.Video, meta *media.VideoMetadata) (int, int) {
	width, height := video.Width, video.Height
	if width <= 0 || height <= 0 {
		width, height = meta.Width, meta.Height
	}
	if width <= 0 || height <= 0 {
		width, height = 640, 360
	}
	// H.264 requires even dimensions.
	if width%2 != 0 {
		width--
	}
	if height%2 != 0 {
		height--
	}
	if width < 2 {
		width = 2
	}
	if height < 2 {
		height = 2
	}
	return width, height
}

func sourceHasAudio(path string) bool {
	raw, err := ffmpeg.NewProcessor(path).GetMetadata()
	if err != nil {
		return false
	}
	meta, err := media.ExtractMetaData(raw)
	if err != nil {
		return false
	}
	return meta.HasAudio
}

// normalizeClip transcodes one clip into a consistent MPEG-TS segment. A silent
// audio track is synthesised for clips without audio so the concat demuxer can
// join segments that do and do not carry sound.
func normalizeClip(ctx context.Context, inputPath, outputPath, start, end string, width, height, fps int, hasAudio bool) error {
	args := []string{"-hide_banner", "-loglevel", "error", "-y"}

	duration := 0.0
	if start != "" {
		startSec, err := ffmpeg.ParseTimecode(start)
		if err != nil {
			return fmt.Errorf("invalid clip start")
		}
		args = append(args, "-ss", formatSeconds(startSec))
		if end != "" {
			endSec, err := ffmpeg.ParseTimecode(end)
			if err != nil {
				return fmt.Errorf("invalid clip end")
			}
			duration = endSec - startSec
		}
	} else if end != "" {
		endSec, err := ffmpeg.ParseTimecode(end)
		if err != nil {
			return fmt.Errorf("invalid clip end")
		}
		duration = endSec
	}

	args = append(args, "-i", inputPath)
	if duration > 0 {
		args = append(args, "-t", formatSeconds(duration))
	}

	videoFilter := fmt.Sprintf(
		"scale=%d:%d:force_original_aspect_ratio=decrease,pad=%d:%d:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=%d",
		width, height, width, height, fps,
	)

	if hasAudio {
		args = append(args,
			"-map", "0:v:0", "-map", "0:a:0",
			"-vf", videoFilter,
			"-af", "aresample=async=1:first_pts=0",
			"-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
			"-c:a", "aac", "-ar", "48000", "-ac", "2",
			"-f", "mpegts", outputPath,
		)
	} else {
		args = append(args,
			"-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo",
			"-map", "0:v:0", "-map", "1:a:0",
			"-vf", videoFilter,
			"-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
			"-c:a", "aac", "-ar", "48000", "-ac", "2",
			"-shortest",
			"-f", "mpegts", outputPath,
		)
	}

	cmd := exec.CommandContext(ctx, "ffmpeg", args...)
	if output, err := cmd.CombinedOutput(); err != nil {
		return fmt.Errorf("normalising clip: %w: %s", err, strings.TrimSpace(string(output)))
	}
	return nil
}

func formatSeconds(value float64) string {
	return strconv.FormatFloat(value, 'f', -1, 64)
}

// CopyUpload is a small helper used by tests to stream an io.Reader into a file
// without buffering the whole payload.
func copyToFile(dst io.Writer, src io.Reader) (int64, error) {
	return io.Copy(dst, src)
}

// processCompose renders a multitrack timeline: a stack of visual layers over a
// black canvas plus a mixed audio bed, expressed as one FFmpeg filter_complex
// graph.
func processCompose(ctx context.Context, in ProcessInput, srcPath string, meta *media.VideoMetadata, tempPath string) error {
	if len(in.Request.Operations) == 0 || in.Request.Operations[0].Compose == nil {
		return fmt.Errorf("the compose plan is missing")
	}
	plan := in.Request.Operations[0].Compose

	width, height := concatDimensions(in.Video, meta)
	if plan.Width > 0 {
		width = plan.Width
	}
	if plan.Height > 0 {
		height = plan.Height
	}
	width &^= 1
	height &^= 1
	fps := concatFrameRate
	if plan.FPS > 0 {
		fps = plan.FPS
	}

	duration := meta.Duration
	if plan.Duration != "" {
		if seconds, err := ffmpeg.ParseTimecode(plan.Duration); err == nil && seconds > 0 {
			duration = seconds
		}
	}
	if duration <= 0 {
		return fmt.Errorf("the timeline has no duration")
	}

	inputs := &composeInputs{
		byVideo: map[string]int{},
		primary: in.Video.ID,
		srcPath: srcPath,
		meta:    meta,
	}
	defer inputs.cleanup()

	resolvePath := func(videoID string) (int, error) {
		return inputs.index(in, videoID)
	}

	var filters []string

	// A black canvas of the exact output length is the base every layer is
	// composited onto, so gaps and uncovered areas stay black.
	filters = append(filters, fmt.Sprintf(
		"color=c=black:s=%dx%d:r=%d:d=%s[bg]",
		width, height, fps, formatSeconds(duration),
	))

	current := "bg"
	layerCount := 0
	for _, layer := range plan.Layers {
		start, err := parseOptionalTime(layer.TimelineStart)
		if err != nil {
			return fmt.Errorf("invalid layer start")
		}
		end, err := parseOptionalTime(layer.TimelineEnd)
		if err != nil {
			return fmt.Errorf("invalid layer end")
		}
		if end <= start {
			continue
		}
		next := fmt.Sprintf("v%d", layerCount)
		layerCount++

		if layer.Kind == "text" {
			filters = append(filters, fmt.Sprintf(
				"[%s]%s[%s]", current, drawTextFilter(layer, start, end), next,
			))
			current = next
			continue
		}

		idx, err := resolvePath(layer.VideoID)
		if err != nil {
			return err
		}

		var chain strings.Builder
		fmt.Fprintf(&chain, "[%d:v]trim=start=%s:end=%s,setpts=PTS-STARTPTS",
			idx, formatSeconds(mustTime(layer.SourceIn)), formatSeconds(mustTime(layer.SourceOut)))
		if layer.Base {
			fmt.Fprintf(&chain, ",scale=%d:%d:force_original_aspect_ratio=decrease,pad=%d:%d:(ow-iw)/2:(oh-ih)/2,setsar=1",
				width, height, width, height)
		} else {
			scale := layer.Scale
			if scale <= 0 {
				scale = 1
			}
			fmt.Fprintf(&chain, ",scale=iw*%s:ih*%s", formatSeconds(scale), formatSeconds(scale))
		}
		chain.WriteString(",format=rgba")
		if layer.Opacity < 1 {
			fmt.Fprintf(&chain, ",colorchannelmixer=aa=%s", formatSeconds(clamp01(layer.Opacity)))
		}
		fmt.Fprintf(&chain, ",setpts=PTS-STARTPTS+%s/TB[%s_ov]", formatSeconds(start), next)
		filters = append(filters, chain.String())

		x := layer.X
		y := layer.Y
		if layer.Base {
			x, y = 0, 0
		}
		filters = append(filters, fmt.Sprintf(
			"[%s][%s_ov]overlay=x=%s:y=%s:eof_action=pass:enable='between(t,%s,%s)'[%s]",
			current, next, formatSeconds(x), formatSeconds(y), formatSeconds(start), formatSeconds(end), next,
		))
		current = next
	}

	filters = append(filters, fmt.Sprintf("[%s]format=yuv420p[vout]", current))

	audioLabels := make([]string, 0, len(plan.Audio))
	audioCount := 0
	for _, seg := range plan.Audio {
		if seg.Volume <= 0 {
			continue
		}
		idx, err := resolvePath(seg.VideoID)
		if err != nil {
			return err
		}
		if !inputs.hasAudio(idx) {
			continue
		}
		start, err := parseOptionalTime(seg.TimelineStart)
		if err != nil {
			return fmt.Errorf("invalid audio start")
		}
		label := fmt.Sprintf("a%d", audioCount)
		audioCount++
		filters = append(filters, fmt.Sprintf(
			"[%d:a]atrim=start=%s:end=%s,asetpts=PTS-STARTPTS,volume=%s,adelay=%d:all=1[%s]",
			idx,
			formatSeconds(mustTime(seg.SourceIn)),
			formatSeconds(mustTime(seg.SourceOut)),
			formatSeconds(seg.Volume),
			int64(start*1000+0.5),
			label,
		))
		audioLabels = append(audioLabels, "["+label+"]")
	}

	hasAudioOut := len(audioLabels) > 0
	if hasAudioOut {
		if len(audioLabels) == 1 {
			filters = append(filters, fmt.Sprintf("%salimiter=limit=0.95[aout]", audioLabels[0]))
		} else {
			filters = append(filters, fmt.Sprintf(
				"%samix=inputs=%d:duration=longest:dropout_transition=0:normalize=0,alimiter=limit=0.95[aout]",
				strings.Join(audioLabels, ""), len(audioLabels),
			))
		}
	}

	args := []string{"-hide_banner", "-loglevel", "error", "-y"}
	args = append(args, inputs.args...)
	args = append(args, "-filter_complex", strings.Join(filters, ";"))
	args = append(args, "-map", "[vout]")
	if hasAudioOut {
		args = append(args, "-map", "[aout]")
	}
	args = append(args,
		"-t", formatSeconds(duration),
		"-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-r", strconv.Itoa(fps),
	)
	if hasAudioOut {
		args = append(args, "-c:a", "aac", "-ar", "48000", "-ac", "2")
	}
	args = append(args, tempPath)

	cmd := exec.CommandContext(ctx, "ffmpeg", args...)
	if output, err := cmd.CombinedOutput(); err != nil {
		return fmt.Errorf("%w: %s", err, strings.TrimSpace(string(output)))
	}
	return nil
}

// composeInputs lazily registers the unique input files a compose plan needs and
// owns the cleanup of any scratch copies it created.
type composeInputs struct {
	args       []string
	byVideo    map[string]int
	hasAud     map[int]bool
	cleanupFns []func()
	primary    string
	srcPath    string
	meta       *media.VideoMetadata
}

func (c *composeInputs) cleanup() {
	for _, fn := range c.cleanupFns {
		fn()
	}
}

// index returns the FFmpeg input index for a referenced asset, registering the
// input the first time it is seen.
func (c *composeInputs) index(in ProcessInput, videoID string) (int, error) {
	key := videoID
	if key == "" {
		key = c.primary
	}
	if idx, ok := c.byVideo[key]; ok {
		return idx, nil
	}

	path := c.srcPath
	hasAudio := c.meta.HasAudio
	cleanup := func() {}

	if key != c.primary {
		if in.Resolve == nil {
			return 0, fmt.Errorf("a referenced clip is not available")
		}
		source, err := in.Resolve(key)
		if err != nil {
			return 0, fmt.Errorf("a referenced clip is not available")
		}
		path, cleanup, err = in.Storage.DecryptToTemp(source.OriginalPath, source.EncKey, source.Encrypted)
		if err != nil {
			return 0, fmt.Errorf("a referenced clip is not available")
		}
		raw, err := ffmpeg.NewProcessor(path).GetMetadata()
		if err != nil {
			cleanup()
			return 0, fmt.Errorf("unable to inspect a referenced clip")
		}
		clipMeta, err := media.ExtractMetaData(raw)
		if err != nil {
			cleanup()
			return 0, fmt.Errorf("unable to inspect a referenced clip")
		}
		hasAudio = clipMeta.HasAudio
	}

	idx := len(c.args) / 2
	c.args = append(c.args, "-i", path)
	c.byVideo[key] = idx
	if c.hasAud == nil {
		c.hasAud = map[int]bool{}
	}
	c.hasAud[idx] = hasAudio
	c.cleanupFns = append(c.cleanupFns, cleanup)
	return idx, nil
}

func (c *composeInputs) hasAudio(idx int) bool { return c.hasAud[idx] }

// drawTextFilter builds a drawtext filter for a text layer, escaping the text so
// a stray quote or colon cannot break the filter graph.
func drawTextFilter(layer models.ComposeLayer, start, end float64) string {
	fontSize := 36
	if layer.Scale > 0 {
		fontSize = int(36*layer.Scale + 0.5)
	}
	if fontSize < 12 {
		fontSize = 12
	}
	color := fmt.Sprintf("white@%s", formatSeconds(clamp01(layer.Opacity)))
	return fmt.Sprintf(
		"drawtext=text='%s':x=%s:y=%s:fontsize=%d:fontcolor=%s:enable='between(t,%s,%s)'",
		escapeDrawText(layer.Text),
		formatSeconds(layer.X),
		formatSeconds(layer.Y),
		fontSize,
		color,
		formatSeconds(start),
		formatSeconds(end),
	)
}

// escapeDrawText escapes the characters that carry meaning inside an FFmpeg
// filter argument.
func escapeDrawText(text string) string {
	replacer := strings.NewReplacer(
		`\`, `\\`,
		`'`, `\'`,
		`:`, `\:`,
		`%`, `\%`,
		`,`, `\,`,
		`[`, `\[`,
		`]`, `\]`,
		`;`, `\;`,
		"\n", `\n`,
	)
	return replacer.Replace(text)
}

func clamp01(value float64) float64 {
	if value < 0 {
		return 0
	}
	if value > 1 {
		return 1
	}
	return value
}

// parseOptionalTime parses a timestamp that may be empty, returning 0 then.
func parseOptionalTime(value string) (float64, error) {
	if strings.TrimSpace(value) == "" {
		return 0, nil
	}
	return ffmpeg.ParseTimecode(value)
}

// mustTime parses a timestamp, returning 0 for an empty or invalid value. The
// compose plan is built server-side from validated data, so a bad value here
// means an internal bug rather than user input.
func mustTime(value string) float64 {
	seconds, err := parseOptionalTime(value)
	if err != nil {
		return 0
	}
	return seconds
}
