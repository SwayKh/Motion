package service

import (
	"fmt"
	"mime/multipart"
	"net/http"
	"os"
	"path/filepath"

	"VideoProject/internal/db"
	"VideoProject/internal/ffmpeg"
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
		return nil, err
	}
	defer dst.Close()

	// Copy uploaded file
	_, err = dst.ReadFrom(file)
	if err != nil {
		return nil, err
	}

	// Probe metadata

	raw, _ := ffmpeg.NewProcessor(uploadPath).GetMetadata()
	meta, err := media.ExtractMetaData(raw)
	if err != nil {
		return nil, err
	}

	// Validation
	if meta.Duration > 300 {
		return nil, fmt.Errorf("video exceeds 5 minute limit")
	}

	if meta.Width > 1920 || meta.Height > 1080 {
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
		return nil, err
	}

	return video, nil
}

