package config

import (
	"fmt"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"
)

type Config struct {
	Env               string
	Port              string
	SessionTTL        time.Duration
	SessionIdleTTL    time.Duration
	SecureCookies     bool
	TrustedOrigins    []string
	RateLimitLogin    int
	RateLimitRegister int
	RateLimitReset    int
	SMTPHost          string
	SMTPPort          int
	SMTPUser          string
	SMTPPass          string
	SMTPFrom          string
	BaseURL           string
}

func Load() *Config {
	cfg := &Config{
		Env:               getEnv("APP_ENV", "development"),
		Port:              getEnv("PORT", "8080"),
		SessionTTL:        getDuration("SESSION_TTL", 24*time.Hour),
		SessionIdleTTL:    getDuration("SESSION_IDLE_TTL", 2*time.Hour),
		SecureCookies:     getBool("SECURE_COOKIES", strings.EqualFold(getEnv("APP_ENV", "development"), "production")),
		TrustedOrigins:    normalizeOrigins(split(getEnv("TRUSTED_ORIGINS", ""))),
		RateLimitLogin:    getInt("RATE_LIMIT_LOGIN", 10),
		RateLimitRegister: getInt("RATE_LIMIT_REGISTER", 5),
		RateLimitReset:    getInt("RATE_LIMIT_RESET", 3),
		SMTPHost:          getEnv("SMTP_HOST", ""),
		SMTPPort:          getInt("SMTP_PORT", 587),
		SMTPUser:          getEnv("SMTP_USER", ""),
		SMTPPass:          getEnv("SMTP_PASS", ""),
		SMTPFrom:          getEnv("SMTP_FROM", ""),
		BaseURL:           getEnv("BASE_URL", "http://localhost:8080"),
	}
	if err := cfg.Validate(); err != nil {
		panic(err)
	}
	return cfg
}

func (c *Config) Validate() error {
	if c.SessionTTL <= 0 {
		return fmt.Errorf("invalid SESSION_TTL")
	}
	if c.SessionIdleTTL <= 0 {
		return fmt.Errorf("invalid SESSION_IDLE_TTL")
	}
	if c.SessionIdleTTL > c.SessionTTL && c.SessionTTL > 0 {
		// allow but keep consistent
	}
	if c.RateLimitLogin < 0 || c.RateLimitRegister < 0 || c.RateLimitReset < 0 {
		return fmt.Errorf("invalid rate limit")
	}
	return nil
}

func getEnv(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func getInt(key string, def int) int {
	if v := os.Getenv(key); v != "" {
		if i, err := strconv.Atoi(v); err == nil {
			return i
		}
	}
	return def
}

func getBool(key string, def bool) bool {
	if v := os.Getenv(key); v != "" {
		v = strings.ToLower(v)
		if v == "1" || v == "true" || v == "yes" {
			return true
		}
		if v == "0" || v == "false" || v == "no" {
			return false
		}
	}
	return def
}

func getDuration(key string, def time.Duration) time.Duration {
	if v := os.Getenv(key); v != "" {
		if d, err := time.ParseDuration(v); err == nil {
			if d <= 0 {
				return def
			}
			return d
		}
		// invalid format - fall back to default for safety
	}
	return def
}

func split(s string) []string {
	if s == "" {
		return nil
	}
	parts := strings.Split(s, ",")
	var out []string
	for _, p := range parts {
		p = strings.TrimSpace(p)
		if p != "" {
			out = append(out, p)
		}
	}
	return out
}

func normalizeOrigins(origins []string) []string {
	var out []string
	for _, o := range origins {
		o = strings.TrimSpace(o)
		if o == "" {
			continue
		}
		if !strings.Contains(o, "://") {
			continue // reject malformed
		}
		u, err := url.Parse(o)
		if err != nil || u.Scheme == "" || u.Host == "" {
			continue
		}
		// only scheme+host
		out = append(out, u.Scheme+"://"+u.Host)
	}
	return out
}
