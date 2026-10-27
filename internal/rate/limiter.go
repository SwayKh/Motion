package rate

import (
	"net"
	"net/http"
	"sync"
	"time"
)

type entry struct {
	count     int
	windowEnd time.Time
}

type Limiter struct {
	mu      sync.Mutex
	limit   int
	window  time.Duration
	entries map[string]*entry
}

func New(limit int, window time.Duration) *Limiter {
	l := &Limiter{
		limit:   limit,
		window:  window,
		entries: make(map[string]*entry),
	}
	go l.cleanup()
	return l
}

func (l *Limiter) cleanup() {
	t := time.NewTicker(l.window)
	for range t.C {
		l.mu.Lock()
		now := time.Now()
		for k, e := range l.entries {
			if now.After(e.windowEnd) {
				delete(l.entries, k)
			}
		}
		l.mu.Unlock()
	}
}

func (l *Limiter) Allow(key string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := time.Now()
	e, ok := l.entries[key]
	if !ok || now.After(e.windowEnd) {
		l.entries[key] = &entry{count: 1, windowEnd: now.Add(l.window)}
		return true
	}
	if e.count >= l.limit {
		return false
	}
	e.count++
	return true
}

func KeyFromRequest(r *http.Request) string {
	// Try to get real IP if behind trusted proxy - but don't trust blindly; use remote addr
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	// Also consider forwarded headers only if explicitly trusted? The requirement says
	// "Do not trust arbitrary forwarded headers from clients. Only honor proxy-related headers
	// when the deployment configuration explicitly trusts the relevant proxy." - for simplicity
	// in this setup, prefer RemoteAddr
	return host
}
