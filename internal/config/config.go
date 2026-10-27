package config

import (
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
		TrustedOrigins:    split(getEnv("TRUSTED_ORIGINS", "")),
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
	return cfg
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
			return d
		}
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
