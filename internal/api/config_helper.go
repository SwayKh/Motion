package api

import "github.com/SwayKh/Motion/internal/config"

func GetConfig() *config.Config {
	return config.Load()
}
