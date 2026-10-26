package service

import (
	"context"
	"fmt"
	"mime/multipart"
	"os"
	"path/filepath"
	"strings"

	"VideoProject/internal/db"
	"VideoProject/internal/ffmpeg"
	"VideoProject/internal/logger"
	"VideoProject/internal/media"
	"VideoProject/internal/models"

	"github.com/google/uuid"
)

type VideoService struct {
	store *db.VideoStore
}

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

const maxUploadBytes = 150 << 20

func NewVideoService(store *db.VideoStore) *VideoService {
	return &VideoService{
		store: store,
	}
}

func (s *VideoService) Upload(file multipart.File, header *multipart.FileHeader) (*models.Video, error) {
	defer file.Close()

	originalName := filepath.Base(header.Filename)
	ext := strings.ToLower(filepath.Ext(originalName))
	if ext == "" {
		return nil, fmt.Errorf("file must have a file extension")
	}
	if _, ok := allowedVideoExtensions[ext]; !ok {
		return nil, fmt.Errorf(
			"unsupported file type %s; use MP4, MOV, MKV, WebM, AVI, M4V, MPEG, or 3GP",
			ext,
		)
	}
	if header.Size > maxUploadBytes {
		return nil, fmt.Errorf("file exceeds the 150 MB limit")
	}

	// Generate video ID
	id := uuid.NewString()

	// Create upload path
	filename := id + ext
	uploadPath := filepath.Join("uploads", filename)

	// Create destination file
	dst, err := os.Create(uploadPath)
	if err != nil {
		logger.Error.Printf("creating upload file: %v", err)
		return nil, fmt.Errorf("failed to save uploaded file")
	}
	defer dst.Close()

	// Copy uploaded file
	written, err := dst.ReadFrom(file)
	if err != nil {
		os.Remove(uploadPath)
		logger.Error.Printf("copying upload file: %v", err)
		return nil, fmt.Errorf("failed to save uploaded file")
	}

	// Probe metadata
	raw, err := ffmpeg.NewProcessor(uploadPath).GetMetadata()
	if err != nil {
		os.Remove(uploadPath)
		logger.Error.Printf("probing video metadata: %v", err)
		return nil, fmt.Errorf("unable to read video metadata; the file may be unsupported or corrupt")
	}

	meta, err := media.ExtractMetaData(raw)
	if err != nil {
		os.Remove(uploadPath)
		logger.Error.Printf("extracting video metadata: %v", err)
		return nil, fmt.Errorf("unable to read video metadata; the file may be unsupported or corrupt")
	}

	if meta.Duration > 300 {
		os.Remove(uploadPath)
		return nil, fmt.Errorf("video exceeds the 5 minute limit")
	}

	if meta.Width > 0 && (meta.Width > 1920 || meta.Height > 1080) {
		os.Remove(uploadPath)
		return nil, fmt.Errorf("video exceeds the 1080p limit")
	}

	if meta.Width == 0 {
		os.Remove(uploadPath)
		return nil, fmt.Errorf("file does not contain a video stream")
	}

	// Create model
	video := &models.Video{
		ID: id,

		OriginalPath: uploadPath,
		OriginalName: originalName,
		FileSize:     written,

		Status:   models.StatusUploaded,
		Progress: 0,

		Duration: meta.Duration,
		Width:    meta.Width,
		Height:   meta.Height,
	}

	// Persist to DB
	err = s.store.Create(video)
	if err != nil {
		os.Remove(uploadPath)
		logger.Error.Printf("persisting uploaded video: %v", err)
		return nil, fmt.Errorf("failed to save video record")
	}

	return video, nil
}

type ProcessResult struct {
	OutputPath string
	OutputType string
}

// ProcessJob runs one persisted job. Database state transitions belong to the
// caller so cancellation and shutdown races can be resolved atomically.
func ProcessJob(
	ctx context.Context,
	job *models.Job,
	req models.ProcessRequest,
	onProgress func(float64),
) (ProcessResult, error) {
	outputExt := ".mp4"
	outputType := "video"
	wantsAudio := false
	for _, op := range req.Operations {
		if op.Type == "to_audio" {
			outputExt = ".mp3"
			outputType = "audio"
			wantsAudio = true
			break
		}
	}

	result := ProcessResult{
		OutputPath: filepath.Join("outputs", job.ID+outputExt),
		OutputType: outputType,
	}

	if err := ctx.Err(); err != nil {
		return result, err
	}

	if _, err := os.Stat(job.SourcePath); err != nil {
		return result, fmt.Errorf("source file is no longer available")
	}

	if wantsAudio {
		raw, err := ffmpeg.NewProcessor(job.SourcePath).GetMetadata()
		if err == nil {
			meta, err := media.ExtractMetaData(raw)
			if err == nil && !meta.HasAudio {
				return result, fmt.Errorf("this video has no audio track to extract")
			}
		}
	}

	processor := ffmpeg.NewProcessor(job.SourcePath)

	applied := 0
	for _, op := range req.Operations {
		switch op.Type {
		case "trim":
			processor.Trim(op.Start, op.End)
			applied++
		case "speed":
			if op.Factor <= 0 {
				return result, fmt.Errorf("speed factor must be greater than 0")
			}
			processor.Speed(op.Factor)
			applied++
		case "volume":
			if op.Factor < 0 {
				return result, fmt.Errorf("volume factor cannot be negative")
			}
			processor.Volume(op.Factor)
			applied++
		case "flip":
			if !op.Direction.Valid() {
				return result, fmt.Errorf("invalid flip direction: %d", op.Direction)
			}
			processor.Flip(op.Direction)
			applied++
		case "to_audio":
			processor.ToAudio()
			applied++
		default:
			logger.Error.Printf("job=%s unknown operation type=%s", job.ID, op.Type)
		}
	}

	if applied == 0 {
		return result, fmt.Errorf("no valid operations provided")
	}

	if err := os.MkdirAll("outputs", 0o755); err != nil {
		return result, fmt.Errorf("creating output directory: %w", err)
	}

	tempPath := filepath.Join(
		"outputs",
		"."+job.ID+".part"+outputExt,
	)
	_ = os.Remove(tempPath)
	_ = os.Remove(result.OutputPath)

	processor.OnProgress(func(progress float64) {
		if ctx.Err() != nil {
			return
		}
		if onProgress != nil {
			onProgress(progress)
		}
	})

	err := processor.OutputContext(ctx, tempPath)
	if err != nil {
		_ = os.Remove(tempPath)
		if ctxErr := ctx.Err(); ctxErr != nil {
			return result, ctxErr
		}
		logger.Error.Printf("job=%s processing failed err=%v", job.ID, err)
		return result, fmt.Errorf("video processing failed; make sure the file contains the streams required by the selected operations")
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

	if err := os.Rename(tempPath, result.OutputPath); err != nil {
		_ = os.Remove(tempPath)
		return result, fmt.Errorf("saving processing output: %w", err)
	}

	logger.Info.Printf(
		"job=%s completed output=%s",
		job.ID,
		result.OutputPath,
	)

	return result, nil
}
