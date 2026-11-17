package rate

import (
	"net"
	"net/http"
	"strings"
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

// KeyFromRequest returns the rate-limiting key for a request using only the
// direct peer address. It never trusts forwarded headers; use ClientIP when a
// trusted-proxy list is available.
func KeyFromRequest(r *http.Request) string {
	return hostOnly(r.RemoteAddr)
}

// ClientIP returns the client address used for rate limiting.
//
// Forwarded headers (X-Forwarded-For) are only honoured when the direct peer is
// itself a trusted proxy, as configured via TRUSTED_PROXIES. The chain is then
// walked from right to left, skipping trusted proxies, and the first untrusted
// address is returned. When no proxy is trusted (the default) this is exactly
// the peer address, so a client can never spoof its identity.
func ClientIP(r *http.Request, trusted []string) string {
	peer := hostOnly(r.RemoteAddr)
	if len(trusted) == 0 || !isTrusted(peer, trusted) {
		return peer
	}

	var chain []string
	for _, header := range r.Header.Values("X-Forwarded-For") {
		for _, part := range strings.Split(header, ",") {
			if ip := strings.TrimSpace(part); ip != "" {
				chain = append(chain, ip)
			}
		}
	}
	chain = append(chain, peer)

	for i := len(chain) - 1; i >= 0; i-- {
		candidate := hostOnly(chain[i])
		if candidate == "" {
			continue
		}
		if !isTrusted(candidate, trusted) {
			return candidate
		}
	}
	return peer
}

func hostOnly(addr string) string {
	host, _, err := net.SplitHostPort(addr)
	if err != nil {
		return strings.TrimSpace(addr)
	}
	return host
}

// isTrusted reports whether ip falls inside any of the configured trusted
// entries. Entries may be bare IPs or CIDR ranges.
func isTrusted(ip string, trusted []string) bool {
	parsed := net.ParseIP(ip)
	if parsed == nil {
		return false
	}
	for _, entry := range trusted {
		entry = strings.TrimSpace(entry)
		if entry == "" {
			continue
		}
		if strings.Contains(entry, "/") {
			if _, network, err := net.ParseCIDR(entry); err == nil && network.Contains(parsed) {
				return true
			}
			continue
		}
		if candidate := net.ParseIP(entry); candidate != nil && candidate.Equal(parsed) {
			return true
		}
	}
	return false
}
