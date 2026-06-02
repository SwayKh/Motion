package service

import (
	"fmt"
	"mime/multipart"
	"os"
	"path/filepath"

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

func NewVideoService(store *db.VideoStore) *VideoService {
	return &VideoService{
		store: store,
	}
}

func (s *VideoService) Upload(file multipart.File, header *multipart.FileHeader) (*models.Video, error) {
	defer file.Close()

	// Generate video ID
	id := uuid.NewString()

	// Get extension
	ext := filepath.Ext(header.Filename)
	if ext == "" {
		return nil, fmt.Errorf("missing file extension")
	}

	// Create upload path
	filename := id + ext
	uploadPath := filepath.Join("uploads", filename)

	// Create destination file
	dst, err := os.Create(uploadPath)
	if err != nil {
		return nil, fmt.Errorf(
			"creating upload file: %w",
			err,
		)
	}
	defer dst.Close()

	// Copy uploaded file
	_, err = dst.ReadFrom(file)
	if err != nil {
		return nil, fmt.Errorf(
			"copying upload file: %w",
			err,
		)
	}

	// Probe metadata
	// After os.Create / dst.ReadFrom succeeds, any early return should clean up:
	raw, err := ffmpeg.NewProcessor(uploadPath).GetMetadata()
	if err != nil {
		os.Remove(uploadPath)
		return nil, fmt.Errorf("probing video metadata: %w", err)
	}

	meta, err := media.ExtractMetaData(raw)
	if err != nil {
		os.Remove(uploadPath)
		return nil, fmt.Errorf("extracting metadata: %w", err)
	}

	if meta.Duration > 300 {
		os.Remove(uploadPath)
		return nil, fmt.Errorf("video exceeds 5 minute limit")
	}

	if meta.Width > 0 && (meta.Width > 1920 || meta.Height > 1080) {
		os.Remove(uploadPath)
		return nil, fmt.Errorf("video exceeds 1080p limit")
	}

	// Create model
	video := &models.Video{
		ID: id,

		OriginalPath: uploadPath,

		Status:   models.StatusUploaded,
		Progress: 0,

		Duration: meta.Duration,
		Width:    meta.Width,
		Height:   meta.Height,
	}

	// Persist to DB
	err = s.store.Create(*video)
	if err != nil {
		return nil, fmt.Errorf(
			"persisting uploaded video: %w",
			err,
		)
	}

	return video, nil
}

func ProcessVideo(
	store *db.VideoStore,
	video *models.Video,
	req models.ProcessRequest,
) {
	logger.Info.Printf("video=%s started processing", video.ID)
	// Determine output extension
	outputExt := ".mp4"
	for _, op := range req.Operations {
		if op.Type == "to_audio" {
			outputExt = ".mp3"
			break
		}
	}

	outputPath := filepath.Join("outputs", video.ID+outputExt)

	processor := ffmpeg.NewProcessor(video.OriginalPath)

	applied := 0
	for _, op := range req.Operations {
		switch op.Type {
		case "trim":
			processor.Trim(op.Start, op.End)
			applied++
		case "speed":
			if op.Factor <= 0 {
				store.Fail(video.ID, "speed factor must be greater than 0")
				return
			}
			processor.Speed(op.Factor)
			applied++
		case "volume":
			if op.Factor < 0 {
				store.Fail(video.ID, "volume factor cannot be negative")
				return
			}
			processor.Volume(op.Factor)
			applied++
		case "flip":
			if !op.Direction.Valid() {
				store.Fail(video.ID, fmt.Sprintf("invalid flip direction: %d", op.Direction))
				return
			}
			processor.Flip(op.Direction)
			applied++
		case "to_audio":
			processor.ToAudio()
			applied++
		default:
			logger.Error.Printf("video=%s unknown operation type=%s", video.ID, op.Type)
		}
	}

	if applied == 0 {
		store.Fail(video.ID, "no valid operations provided")
		return
	}

	processor.OnProgress(func(progress float64) {
		err := store.UpdateProgress(video.ID, progress)
		if err != nil {
			logger.Error.Printf("video=%s progress update failed err=%v", video.ID, err)
		}
	})

	err := processor.Output(outputPath)
	if err != nil {
		store.Fail(video.ID, err.Error())
		logger.Error.Printf("video=%s processing failed err=%v", video.ID, err)
		return
	}

	err = store.Complete(video.ID, outputPath)
	if err != nil {
		logger.Error.Printf(
			"video=%s completion update failed err=%v",
			video.ID,
			err,
		)
	}
	logger.Info.Printf(
		"video=%s completed output=%s",
		video.ID,
		outputPath,
	)
}
