package job

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"os"

	"github.com/SwayKh/Motion/internal/logger"
	"github.com/SwayKh/Motion/internal/models"
	"github.com/SwayKh/Motion/internal/service"
)

func (m *Manager) worker(id int) {
	defer m.wg.Done()

	for {
		if m.ctx.Err() != nil {
			return
		}

		job, err := m.jobs.ClaimNext()
		if errors.Is(err, sql.ErrNoRows) {
			if !m.sleep() {
				return
			}
			continue
		}
		if err != nil {
			logger.Error.Printf("worker=%d claim failed err=%v", id, err)
			if !m.sleep() {
				return
			}
			continue
		}

		logger.Info.Printf("worker=%d processing job=%s video=%s", id, job.ID, job.VideoID)
		m.runJob(job)
	}
}

func (m *Manager) runJob(job *models.Job) {
	ctx, cancel := context.WithCancel(m.ctx)
	m.registerCancel(job.ID, cancel)
	defer func() {
		cancel()
		m.unregisterCancel(job.ID)
	}()

	defer func() {
		if recovered := recover(); recovered != nil {
			logger.Error.Printf("job=%s panicked err=%v", job.ID, recovered)
			removeJobArtifacts(job)
			if m.ctx.Err() != nil {
				m.requeueOrCancel(job.ID)
				return
			}
			m.failOrCancel(job.ID, "processing stopped unexpectedly")
		}
	}()

	var req models.ProcessRequest
	if err := json.Unmarshal([]byte(job.Operations), &req); err != nil || len(req.Operations) == 0 {
		removeJobArtifacts(job)
		m.failOrCancel(job.ID, "invalid saved operations")
		return
	}

	result, processErr := service.ProcessJob(ctx, job, req, func(progress float64) {
		if err := m.jobs.UpdateProgress(job.ID, progress); err != nil {
			logger.Error.Printf("job=%s progress update failed err=%v", job.ID, err)
		}
	})

	current, err := m.jobs.Get(job.ID)
	if err != nil {
		logger.Error.Printf("job=%s reload after processing failed err=%v", job.ID, err)
		removeJobArtifacts(job)
		return
	}

	switch {
	case current.Status == models.StatusCancelRequested:
		removeJobArtifacts(job)
		if _, _, err := m.jobs.MarkCancelled(job.ID); err != nil {
			logger.Error.Printf("job=%s cancellation update failed err=%v", job.ID, err)
		}
		logger.Info.Printf("job=%s cancelled", job.ID)
		return

	case current.Status == models.StatusCancelled:
		removeJobArtifacts(job)
		return

	case m.ctx.Err() != nil:
		removeJobArtifacts(job)
		m.requeueOrCancel(job.ID)
		return

	case processErr != nil:
		removeJobArtifacts(job)
		m.failOrCancel(job.ID, processErr.Error())
		return
	}

	updated, ok, err := m.jobs.Complete(job.ID, result.OutputPath)
	if err != nil {
		logger.Error.Printf("job=%s completion update failed err=%v", job.ID, err)
		removeJobArtifacts(job)
		m.failOrCancel(job.ID, "failed to save processing result")
		return
	}
	if !ok {
		// Cancellation won the race with completion. Never leave an output
		// that the database does not consider complete.
		removeJobArtifacts(job)
		current, getErr := m.jobs.Get(job.ID)
		if getErr == nil && current.Status == models.StatusCancelRequested {
			if _, _, markErr := m.jobs.MarkCancelled(job.ID); markErr != nil {
				logger.Error.Printf("job=%s cancellation update failed err=%v", job.ID, markErr)
			}
		}
		return
	}

	logger.Info.Printf(
		"worker completed job=%s video=%s outputType=%s",
		updated.ID,
		updated.VideoID,
		result.OutputType,
	)
}

func (m *Manager) failOrCancel(jobID, message string) {
	_, ok, err := m.jobs.Fail(jobID, message)
	if err != nil {
		logger.Error.Printf("job=%s failure update failed err=%v", jobID, err)
		return
	}
	if ok {
		return
	}

	current, err := m.jobs.Get(jobID)
	if err != nil {
		return
	}
	if current.Status == models.StatusCancelRequested {
		if _, _, err := m.jobs.MarkCancelled(jobID); err != nil {
			logger.Error.Printf("job=%s cancellation update failed err=%v", jobID, err)
		}
	}
}

func (m *Manager) requeueOrCancel(jobID string) {
	current, err := m.jobs.Get(jobID)
	if err != nil {
		logger.Error.Printf("job=%s reload for shutdown failed err=%v", jobID, err)
		return
	}

	if current.Status == models.StatusCancelRequested {
		if _, _, err := m.jobs.MarkCancelled(jobID); err != nil {
			logger.Error.Printf("job=%s cancellation update failed err=%v", jobID, err)
		}
		return
	}

	_, ok, err := m.jobs.Requeue(jobID)
	if err != nil {
		logger.Error.Printf("job=%s requeue after shutdown failed err=%v", jobID, err)
		return
	}
	if ok {
		logger.Info.Printf("job=%s requeued after shutdown", jobID)
	}
}

// Recover repairs jobs interrupted by a previous process. It must run before
// workers start.
func (m *Manager) Recover() error {
	interrupted, err := m.jobs.ListByStatus(
		models.StatusProcessing,
		models.StatusCancelRequested,
	)
	if err != nil {
		return fmt.Errorf("loading interrupted jobs: %w", err)
	}

	for i := range interrupted {
		job := &interrupted[i]
		removeJobArtifacts(job)

		if job.Status == models.StatusCancelRequested {
			if _, _, err := m.jobs.MarkCancelled(job.ID); err != nil {
				logger.Error.Printf("job=%s recovery cancellation failed err=%v", job.ID, err)
			} else {
				logger.Info.Printf("job=%s cancelled during recovery", job.ID)
			}
			continue
		}

		if _, err := os.Stat(job.SourcePath); err != nil {
			m.failOrCancel(job.ID, "source file is no longer available")
			logger.Info.Printf("job=%s failed recovery: source missing", job.ID)
			continue
		}

		var req models.ProcessRequest
		if err := json.Unmarshal([]byte(job.Operations), &req); err != nil || len(req.Operations) == 0 {
			m.failOrCancel(job.ID, "invalid saved operations")
			logger.Info.Printf("job=%s failed recovery: invalid operations", job.ID)
			continue
		}

		_, ok, err := m.jobs.Requeue(job.ID)
		if err != nil {
			logger.Error.Printf("job=%s recovery requeue failed err=%v", job.ID, err)
			continue
		}
		if ok {
			logger.Info.Printf("job=%s requeued after restart", job.ID)
		}
	}

	m.Notify()
	return nil
}
