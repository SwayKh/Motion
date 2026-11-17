package rate

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestKeyFromRequestIgnoresForwardedHeaders(t *testing.T) {
	r := httptest.NewRequest(http.MethodGet, "/", nil)
	r.RemoteAddr = "203.0.113.7:5555"
	r.Header.Set("X-Forwarded-For", "10.0.0.1")

	if got := KeyFromRequest(r); got != "203.0.113.7" {
		t.Fatalf("KeyFromRequest = %q, want peer address", got)
	}
}

func TestClientIPWithoutTrustedProxies(t *testing.T) {
	r := httptest.NewRequest(http.MethodGet, "/", nil)
	r.RemoteAddr = "203.0.113.7:5555"
	r.Header.Set("X-Forwarded-For", "198.51.100.9")

	// With no trusted proxies the forwarded header must be ignored so a client
	// cannot spoof its identity.
	if got := ClientIP(r, nil); got != "203.0.113.7" {
		t.Fatalf("ClientIP = %q, want 203.0.113.7", got)
	}
}

func TestClientIPTrustsForwardedHeaderFromProxy(t *testing.T) {
	r := httptest.NewRequest(http.MethodGet, "/", nil)
	r.RemoteAddr = "10.0.0.5:44321"
	r.Header.Set("X-Forwarded-For", "198.51.100.9")

	if got := ClientIP(r, []string{"10.0.0.5"}); got != "198.51.100.9" {
		t.Fatalf("ClientIP = %q, want 198.51.100.9", got)
	}
}

func TestClientIPTrustsCIDRProxy(t *testing.T) {
	r := httptest.NewRequest(http.MethodGet, "/", nil)
	r.RemoteAddr = "10.1.2.3:44321"
	r.Header.Set("X-Forwarded-For", "198.51.100.9")

	if got := ClientIP(r, []string{"10.0.0.0/8"}); got != "198.51.100.9" {
		t.Fatalf("ClientIP = %q, want 198.51.100.9", got)
	}
}

func TestClientIPSkipsTrustedHops(t *testing.T) {
	r := httptest.NewRequest(http.MethodGet, "/", nil)
	r.RemoteAddr = "10.0.0.5:44321"
	// Client, then an internal proxy, then the edge proxy (the peer).
	r.Header.Set("X-Forwarded-For", "198.51.100.9, 10.0.0.6")

	if got := ClientIP(r, []string{"10.0.0.0/8"}); got != "198.51.100.9" {
		t.Fatalf("ClientIP = %q, want 198.51.100.9", got)
	}
}

func TestClientIPFallsBackToPeerWhenChainAllTrusted(t *testing.T) {
	r := httptest.NewRequest(http.MethodGet, "/", nil)
	r.RemoteAddr = "10.0.0.5:44321"
	r.Header.Set("X-Forwarded-For", "10.0.0.6, 10.0.0.7")

	if got := ClientIP(r, []string{"10.0.0.0/8"}); got != "10.0.0.5" {
		t.Fatalf("ClientIP = %q, want peer 10.0.0.5", got)
	}
}

func TestClientIPIgnoresMalformedForwardedEntries(t *testing.T) {
	r := httptest.NewRequest(http.MethodGet, "/", nil)
	r.RemoteAddr = "10.0.0.5:44321"
	r.Header.Set("X-Forwarded-For", "unknown, 198.51.100.9")

	if got := ClientIP(r, []string{"10.0.0.0/8"}); got != "198.51.100.9" {
		t.Fatalf("ClientIP = %q, want 198.51.100.9", got)
	}
}
