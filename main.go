package main

import (
	"context"
	"errors"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"VideoProject/internal/api"
	"VideoProject/internal/db"
	"VideoProject/internal/job"
	"VideoProject/internal/logger"
)

func main() {
	logger.Init()

	if err := ensureDirectories(); err != nil {
		log.Fatalf("failed creating directories: %v", err)
	}

	database, err := db.Open("data/app.db")
	if err != nil {
		log.Fatalf("opening database: %v", err)
	}
	defer database.Close()

	videoStore := db.NewVideoStore(database)
	jobStore := db.NewJobStore(database)
	manager := job.NewManager(jobStore, 3)

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
	go job.StartCleaner(videoStore)

	server := &http.Server{
		Addr:              ":8080",
		Handler:           api.NewRouter(database, manager, "./web"),
		ReadHeaderTimeout: 10 * time.Second,
	}

	logger.Info.Println("Starting server on port 8080...")
	logger.Info.Println("Open http://localhost:8080 in your browser")

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

func ensureDirectories() error {
	dirs := []string{
		"uploads",
		"outputs",
		"data",
	}

	for _, dir := range dirs {
		err := os.MkdirAll(dir, 0o755)
		if err != nil {
			return err
		}
	}

	return nil
}
