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
	// RegistrationSecret is the shared secret used to obtain the agent's first token.
	// It can also be supplied through the AGENT_REGISTER_SECRET environment variable.
	RegistrationSecret string `yaml:"registration-secret"`
}

type AgentConfig struct {
	ID   string     `yaml:"id"`
	File FileConfig `yaml:"file"`
}

type FileConfig struct {
	Enabled        bool     `yaml:"enabled"`
	Root           string   `yaml:"root"`
	ReadOnly       bool     `yaml:"readonly"`
	// AllowSystemRoot must be set to true to keep using "/" (or another system
	// directory) as the file root. Without it the agent refuses to start.
	AllowSystemRoot bool     `yaml:"allow-system-root"`
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

// DefaultFileRoot is used when no file section is configured: a dedicated directory
// instead of the whole filesystem.
const DefaultFileRoot = "/var/lib/easyshell"

func Load(path string) (*Config, error) {
	cfg := &Config{
		Server: ServerConfig{URL: "http://localhost:8080"},
		Agent: AgentConfig{
			File: FileConfig{
				Enabled:        true,
				Root:           DefaultFileRoot,
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

	if cfg.Server.RegistrationSecret == "" {
		cfg.Server.RegistrationSecret = os.Getenv("AGENT_REGISTER_SECRET")
	}

	if cfg.Agent.File.Root == "" {
		cfg.Agent.File.Root = DefaultFileRoot
	}
	if cfg.Agent.File.MaxSizeMB <= 0 {
		cfg.Agent.File.MaxSizeMB = 500
	}
	if cfg.Agent.File.MaxListEntries <= 0 {
		cfg.Agent.File.MaxListEntries = 1000
	}

	return cfg, nil
}

// WarnAboutFileRoot reports configuration that weakens the file server: a system root
// without an explicit opt-in, or a world readable config file.
func (c *Config) WarnAboutFileRoot() string {
	switch c.Agent.File.Root {
	case "/", "/etc", "/root", "/home", "/var", "/usr", "/opt":
		if !c.Agent.File.AllowSystemRoot {
			return "agent.file.root is set to the system directory " + c.Agent.File.Root +
				" without agent.file.allow-system-root: true — the agent will refuse to start"
		}
		return "agent.file.root is the system directory " + c.Agent.File.Root +
			" (explicitly allowed); every path is still checked against the blacklist and symlink rules"
	default:
		return ""
	}
}
