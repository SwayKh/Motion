package job

import (
	"context"
	"os"
	"sync"
	"time"

	"github.com/SwayKh/Motion/internal/db"
	"github.com/SwayKh/Motion/internal/logger"
	"github.com/SwayKh/Motion/internal/models"
	"github.com/SwayKh/Motion/internal/storage"
)

type Manager struct {
	jobs    *db.JobStore
	videos  *db.VideoStore
	st      *storage.Manager
	workers int

	ctx    context.Context
	cancel context.CancelFunc
	wg     sync.WaitGroup
	wake   chan struct{}

	cancelMu       sync.Mutex
	jobCancels     map[string]context.CancelFunc
	pendingCancels map[string]struct{}
}

// NewManager builds a job manager. The video store is used to load source
// records (and to verify ownership of referenced clips/audio) and the storage
// manager resolves and decrypts files.
func NewManager(jobs *db.JobStore, videos *db.VideoStore, st *storage.Manager, workers int) *Manager {
	if workers < 1 {
		workers = 1
	}
	if st == nil {
		st = storage.Default()
	}

	return &Manager{
		jobs:           jobs,
		videos:         videos,
		st:             st,
		workers:        workers,
		wake:           make(chan struct{}, workers),
		jobCancels:     make(map[string]context.CancelFunc),
		pendingCancels: make(map[string]struct{}),
	}
}

func (m *Manager) Start(parent context.Context) {
	m.ctx, m.cancel = context.WithCancel(parent)

	for workerID := 0; workerID < m.workers; workerID++ {
		m.wg.Add(1)
		go m.worker(workerID)
	}
}

func (m *Manager) Stop() {
	if m.cancel != nil {
		m.cancel()
	}
	m.wg.Wait()
}

func (m *Manager) Notify() {
	if m == nil {
		return
	}
	select {
	case m.wake <- struct{}{}:
	default:
	}
}

// Cancel transitions a job to cancelled or cancel_requested and interrupts the
// matching FFmpeg process when it is already running.
func (m *Manager) Cancel(jobID string) (*models.Job, error) {
	job, running, err := m.jobs.RequestCancel(jobID)
	if err != nil {
		return nil, err
	}

	if running {
		m.cancelMu.Lock()
		if cancel := m.jobCancels[jobID]; cancel != nil {
			cancel()
		} else {
			m.pendingCancels[jobID] = struct{}{}
		}
		m.cancelMu.Unlock()
		m.Notify()
	}

	return job, nil
}

func (m *Manager) registerCancel(jobID string, cancel context.CancelFunc) {
	m.cancelMu.Lock()
	m.jobCancels[jobID] = cancel
	pending := false
	if _, ok := m.pendingCancels[jobID]; ok {
		delete(m.pendingCancels, jobID)
		pending = true
	}
	m.cancelMu.Unlock()

	if pending {
		cancel()
	}
}

func (m *Manager) unregisterCancel(jobID string) {
	m.cancelMu.Lock()
	delete(m.jobCancels, jobID)
	delete(m.pendingCancels, jobID)
	m.cancelMu.Unlock()
}

// RemoveArtifacts deletes a job's output and scratch files from storage. It is
// exported so the API layer can clean up files when a job or video is deleted.
func RemoveArtifacts(job *models.Job, st *storage.Manager) {
	removeJobArtifacts(job, st)
}

func removeJobArtifacts(job *models.Job, st *storage.Manager) {
	if job == nil {
		return
	}
	if st == nil {
		st = storage.Default()
	}

	paths := []string{
		job.OutputPath,
		st.OutputPath(job.ID + ".mp4"),
		st.OutputPath(job.ID + ".mp3"),
		st.OutputPath("." + job.ID + ".part.mp4"),
		st.OutputPath("." + job.ID + ".part.mp3"),
	}

	seen := make(map[string]struct{}, len(paths))
	for _, path := range paths {
		if path == "" {
			continue
		}
		if _, ok := seen[path]; ok {
			continue
		}
		seen[path] = struct{}{}
		if err := os.Remove(path); err != nil && !os.IsNotExist(err) {
			logger.Error.Printf("removing job artifact job=%s path=%s err=%v", job.ID, path, err)
		}
	}
}

func (m *Manager) sleep() bool {
	timer := time.NewTimer(2 * time.Second)
	defer timer.Stop()

	select {
	case <-m.ctx.Done():
		return false
	case <-m.wake:
		return true
	case <-timer.C:
		return true
	}
}
