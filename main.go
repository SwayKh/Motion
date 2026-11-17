package main

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"

	"github.com/SwayKh/Motion/internal/api"
	"github.com/SwayKh/Motion/internal/config"
	"github.com/SwayKh/Motion/internal/db"
	"github.com/SwayKh/Motion/internal/job"
	"github.com/SwayKh/Motion/internal/logger"
	"github.com/SwayKh/Motion/internal/storage"
)

func main() {
	logger.Init()

	cfg := config.Load()

	st, err := storage.New(cfg)
	if err != nil {
		log.Fatalf("initialising storage: %v", err)
	}

	// Migration subcommands operate on existing data and exit.
	if len(os.Args) > 1 {
		switch os.Args[1] {
		case "encrypt-media":
			if err := runMediaMigration(cfg, st, true); err != nil {
				log.Fatalf("encrypt-media failed: %v", err)
			}
			return
		case "decrypt-media":
			if err := runMediaMigration(cfg, st, false); err != nil {
				log.Fatalf("decrypt-media failed: %v", err)
			}
			return
		default:
			log.Fatalf("unknown command %q", os.Args[1])
		}
	}

	if err := ensureDirectories(cfg, st); err != nil {
		log.Fatalf("failed creating directories: %v", err)
	}

	database, err := db.Open(filepath.Join(cfg.DataDir, "app.db"))
	if err != nil {
		log.Fatalf("opening database: %v", err)
	}
	defer database.Close()

	videoStore := db.NewVideoStore(database)
	jobStore := db.NewJobStore(database)
	manager := job.NewManager(jobStore, videoStore, st, cfg.MaxGlobalConcurrentJobs)

	// Remove scratch files left behind by a previous crash before workers start.
	sweepTempFiles(cfg, st)

	if err := manager.Recover(); err != nil {
		logger.Error.Printf("failed to recover jobs: %v", err)
	}

	ctx, stop := signal.NotifyContext(
		context.Background(),
		os.Interrupt,
		syscall.SIGTERM,
	)
	defer stop()

	manager.Start(ctx)
	go job.StartCleaner(videoStore, st)
	go startTokenCleaner(ctx, database, cfg)

	port := cfg.Port
	if port == "" {
		port = "8080"
	}
	if !strings.HasPrefix(port, ":") {
		port = ":" + port
	}

	server := &http.Server{
		Addr:              port,
		Handler:           api.NewRouterWithConfig(database, manager, "./web", cfg, st),
		ReadHeaderTimeout: 10 * time.Second,
	}

	logger.Info.Printf("Starting server on %s (encryption=%v)...", port, st.EncryptionEnabled())

	serverErr := make(chan error, 1)
	go func() {
		err := server.ListenAndServe()
		if err != nil && !errors.Is(err, http.ErrServerClosed) {
			serverErr <- err
		}
	}()

	select {
	case err := <-serverErr:
		logger.Error.Printf("server failed: %v", err)
		stop()
	case <-ctx.Done():
		logger.Info.Println("shutdown requested")
	}

	shutdownCtx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	if err := server.Shutdown(shutdownCtx); err != nil {
		logger.Error.Printf("server shutdown failed: %v", err)
	}

	manager.Stop()
	logger.Info.Println("server stopped")
}

func ensureDirectories(cfg *config.Config, st *storage.Manager) error {
	if cfg.DataDir != "" {
		if err := os.MkdirAll(cfg.DataDir, 0o755); err != nil {
			return err
		}
	}
	return st.EnsureDirs()
}

// startTokenCleaner periodically removes expired sessions, password-reset tokens
// and email-verification tokens so the auth tables do not grow without bound.
func startTokenCleaner(ctx context.Context, database *sql.DB, cfg *config.Config) {
	interval := cfg.CleanupInterval
	if interval <= 0 {
		interval = time.Hour
	}
	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	sessions := db.NewSessionStoreWithConfig(database, cfg.SessionIdleTTL, cfg.SessionTTL)
	resets := db.NewResetTokenStore(database)
	verifications := db.NewVerificationTokenStore(database)

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			now := time.Now()
			if _, err := sessions.DeleteExpired(now); err != nil {
				logger.Error.Printf("session cleanup failed err=%v", err)
			}
			if _, err := resets.DeleteExpired(now); err != nil {
				logger.Error.Printf("reset token cleanup failed err=%v", err)
			}
			if _, err := verifications.DeleteExpired(now); err != nil {
				logger.Error.Printf("verification token cleanup failed err=%v", err)
			}
		}
	}
}

// sweepTempFiles deletes scratch files older than the retention window. It is
// best effort and only ever touches the configured temp directory.
func sweepTempFiles(cfg *config.Config, st *storage.Manager) {
	dir := st.TempDir()
	if dir == "" {
		return
	}
	cutoff := time.Now().Add(-cfg.Retention)
	entries, err := os.ReadDir(dir)
	if err != nil {
		return
	}
	for _, entry := range entries {
		info, err := entry.Info()
		if err != nil || info.IsDir() {
			continue
		}
		if info.ModTime().Before(cutoff) {
			_ = os.Remove(filepath.Join(dir, entry.Name()))
		}
	}
}

// runMediaMigration walks every stored file and encrypts or decrypts it in
// place, updating the database keys. It is idempotent and safe to re-run.
func runMediaMigration(cfg *config.Config, st *storage.Manager, encrypt bool) error {
	if !st.EncryptionEnabled() {
		return errors.New("MOTION_ENCRYPTION_KEY must be set to migrate media")
	}
	if err := st.EnsureDirs(); err != nil {
		return err
	}

	database, err := db.Open(filepath.Join(cfg.DataDir, "app.db"))
	if err != nil {
		return err
	}
	defer database.Close()

	videos := db.NewVideoStore(database)
	jobs := db.NewJobStore(database)

	// A file can be referenced by both a video row and a job row, so remember
	// the wrapped key we produced for each path to avoid double processing.
	keys := make(map[string]string)

	process := func(path string) (string, bool) {
		if path == "" {
			return "", false
		}
		if key, ok := keys[path]; ok {
			return key, true
		}
		if _, err := os.Stat(path); err != nil {
			return "", false
		}
		if encrypt {
			key, err := st.EncryptInPlace(path)
			if err != nil {
				logger.Error.Printf("encrypting %s: %v", path, err)
				return "", false
			}
			keys[path] = key
			return key, true
		}
		return "", false
	}

	allVideos, err := videos.ListAll()
	if err != nil {
		return err
	}
	for i := range allVideos {
		video := &allVideos[i]
		if encrypt {
			if !video.Encrypted {
				if key, ok := process(video.OriginalPath); ok {
					if err := videos.SetOriginalEncryption(video.ID, true, key); err != nil {
						logger.Error.Printf("recording original encryption for %s: %v", video.ID, err)
					}
				}
			}
			if !video.OutputEncrypted {
				if key, ok := process(video.OutputPath); ok {
					if err := videos.SetOutputEncryption(video.ID, true, key); err != nil {
						logger.Error.Printf("recording output encryption for %s: %v", video.ID, err)
					}
				}
			}
			continue
		}

		// Decrypt.
		if video.Encrypted && video.EncKey != "" {
			if err := st.DecryptInPlace(video.OriginalPath, video.EncKey); err != nil {
				logger.Error.Printf("decrypting %s: %v", video.OriginalPath, err)
			} else if err := videos.SetOriginalEncryption(video.ID, false, ""); err != nil {
				logger.Error.Printf("recording original decryption for %s: %v", video.ID, err)
			}
		}
		if video.OutputEncrypted && video.OutputEncKey != "" {
			if err := st.DecryptInPlace(video.OutputPath, video.OutputEncKey); err != nil {
				logger.Error.Printf("decrypting %s: %v", video.OutputPath, err)
			} else if err := videos.SetOutputEncryption(video.ID, false, ""); err != nil {
				logger.Error.Printf("recording output decryption for %s: %v", video.ID, err)
			}
		}
	}

	allJobs, err := jobs.ListAll()
	if err != nil {
		return err
	}
	for i := range allJobs {
		jobRecord := &allJobs[i]
		if encrypt {
			if !jobRecord.Encrypted {
				if key, ok := process(jobRecord.OutputPath); ok {
					if err := jobs.SetOutputEncryption(jobRecord.ID, true, key); err != nil {
						logger.Error.Printf("recording job encryption for %s: %v", jobRecord.ID, err)
					}
				}
			}
			continue
		}
		if jobRecord.Encrypted && jobRecord.EncKey != "" {
			if err := st.DecryptInPlace(jobRecord.OutputPath, jobRecord.EncKey); err != nil {
				logger.Error.Printf("decrypting %s: %v", jobRecord.OutputPath, err)
			} else if err := jobs.SetOutputEncryption(jobRecord.ID, false, ""); err != nil {
				logger.Error.Printf("recording job decryption for %s: %v", jobRecord.ID, err)
			}
		}
	}

	action := "encrypted"
	if !encrypt {
		action = "decrypted"
	}
	fmt.Printf("%s media migration complete\n", action)
	return nil
}
