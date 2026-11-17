package api

import (
	"net/http"

	"github.com/SwayKh/Motion/internal/config"
)

// ConfigHandler exposes the non-sensitive client configuration the frontend
// needs to render itself correctly (for example whether the verification banner
// should be shown).
func ConfigHandler(d *Deps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		cfg := d.Cfg
		if cfg == nil {
			cfg = config.Load()
		}
		writeJSON(w, http.StatusOK, map[string]any{
			"requireEmailVerification": cfg.RequireEmailVerification,
			"maxUploadBytes":           cfg.MaxUploadBytes,
			"maxDurationSeconds":       cfg.MaxDurationSeconds,
		})
	}
}
