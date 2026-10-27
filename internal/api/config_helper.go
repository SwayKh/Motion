package api

import "VideoProject/internal/config"

func GetConfig() *config.Config {
	return config.Load()
}
