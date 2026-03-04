package config

import (
	"os"

	"gopkg.in/yaml.v3"
)

type Config struct {
	Server    ServerConfig    `yaml:"server"`
	Agent     AgentConfig     `yaml:"agent"`
	Heartbeat HeartbeatConfig `yaml:"heartbeat"`
	Metrics   MetricsConfig   `yaml:"metrics"`
	Log       LogConfig       `yaml:"log"`
}

type ServerConfig struct {
	URL string `yaml:"url"`
}

type AgentConfig struct {
	ID   string     `yaml:"id"`
	File FileConfig `yaml:"file"`
}

type FileConfig struct {
	Enabled        bool     `yaml:"enabled"`
	Root           string   `yaml:"root"`
	ReadOnly       bool     `yaml:"readonly"`
	MaxSizeMB      int      `yaml:"max-size-mb"`
	MaxListEntries int      `yaml:"max-list-entries"`
	Blacklist      []string `yaml:"blacklist"`
	Whitelist      []string `yaml:"whitelist"`
}

type HeartbeatConfig struct {
	Interval int `yaml:"interval"`
}

type MetricsConfig struct {
	Interval int `yaml:"interval"`
}

type LogConfig struct {
	Level string `yaml:"level"`
}

func Load(path string) (*Config, error) {
	cfg := &Config{
		Server: ServerConfig{URL: "http://localhost:8080"},
		Agent: AgentConfig{
			File: FileConfig{
				Enabled:        true,
				Root:           "/",
				ReadOnly:       false,
				MaxSizeMB:      500,
				MaxListEntries: 1000,
			},
		},
		Heartbeat: HeartbeatConfig{Interval: 30},
		Metrics:   MetricsConfig{Interval: 60},
		Log:       LogConfig{Level: "info"},
	}

	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}

	if err := yaml.Unmarshal(data, cfg); err != nil {
		return nil, err
	}

	return cfg, nil
}
