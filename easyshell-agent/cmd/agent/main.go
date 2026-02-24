package main

import (
	"context"
	"flag"
	"fmt"
	"log/slog"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/easyshell-org/easyshell/easyshell-agent/internal/client"
	"github.com/easyshell-org/easyshell/easyshell-agent/internal/collector"
	"github.com/easyshell-org/easyshell/easyshell-agent/internal/config"
	"github.com/easyshell-org/easyshell/easyshell-agent/internal/executor"
	"github.com/easyshell-org/easyshell/easyshell-agent/internal/heartbeat"
	"github.com/easyshell-org/easyshell/easyshell-agent/internal/ws"
)

var version = "0.1.0-dev"

func main() {
	configPath := flag.String("config", "configs/agent.yaml", "path to config file")
	showVersion := flag.Bool("version", false, "show version")
	flag.Parse()

	if *showVersion {
		fmt.Printf("easyshell-agent %s\n", version)
		os.Exit(0)
	}

	cfg, err := config.Load(*configPath)
	if err != nil {
		slog.Error("failed to load config", "path", *configPath, "error", err)
		os.Exit(1)
	}

	setupLogger(cfg.Log.Level)

	agentID := cfg.Agent.ID
	if agentID == "" {
		hostname, _ := os.Hostname()
		agentID = "ag_" + sanitizeHostname(hostname)
		slog.Info("generated agent ID from hostname", "agent_id", agentID)
	}

	slog.Info("easyshell-agent starting",
		"version", version,
		"server_url", cfg.Server.URL,
		"agent_id", agentID,
	)

	httpClient := client.NewHTTPClient(cfg.Server.URL)

	sysInfo, err := collector.Collect()
	if err != nil {
		slog.Error("failed to collect system info", "error", err)
		os.Exit(1)
	}

	regReq := &client.RegisterRequest{
		AgentID:      agentID,
		Hostname:     sysInfo.Hostname,
		IP:           sysInfo.IP,
		OS:           sysInfo.OS,
		Arch:         sysInfo.Arch,
		Kernel:       sysInfo.Kernel,
		CPUModel:     sysInfo.CPUModel,
		CPUCores:     sysInfo.CPUCores,
		MemTotal:     sysInfo.MemTotal,
		AgentVersion: version,
	}

	if err := httpClient.Register(regReq); err != nil {
		slog.Error("failed to register agent", "error", err)
		os.Exit(1)
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	hbService := heartbeat.NewService(httpClient, agentID, cfg.Heartbeat.Interval)
	go hbService.Start(ctx)

	exec := executor.New()
	wsClient := ws.NewClient(cfg.Server.URL, agentID, httpClient, exec)
	go wsClient.Start(ctx)

	go pollConfig(ctx, httpClient, agentID, hbService)

	slog.Info("easyshell-agent is running", "agent_id", agentID)

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)

	sig := <-sigCh
	slog.Info("received signal, shutting down", "signal", sig)
	cancel()

	slog.Info("easyshell-agent stopped")
}

func pollConfig(ctx context.Context, httpClient *client.HTTPClient, agentID string, hbService *heartbeat.Service) {
	// Poll once immediately on startup
	applyRemoteConfig(httpClient, agentID, hbService)

	ticker := time.NewTicker(10 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			applyRemoteConfig(httpClient, agentID, hbService)
		}
	}
}

func applyRemoteConfig(httpClient *client.HTTPClient, agentID string, hbService *heartbeat.Service) {
	cfgMap, err := httpClient.GetConfig(agentID)
	if err != nil {
		slog.Warn("failed to poll config", "error", err)
		return
	}

	applied := false
	if v, ok := cfgMap["agent.heartbeat.interval"]; ok {
		if seconds, err := strconv.Atoi(v); err == nil && seconds > 0 {
			hbService.UpdateInterval(time.Duration(seconds) * time.Second)
			applied = true
			slog.Info("applied remote config", "heartbeat_interval", seconds)
		}
	}
	if v, ok := cfgMap["agent.metrics.interval"]; ok {
		if seconds, err := strconv.Atoi(v); err == nil && seconds > 0 {
			hbService.UpdateInterval(time.Duration(seconds) * time.Second)
			if !applied {
				slog.Info("applied remote config", "metrics_interval", seconds)
			}
		}
	}
}

func sanitizeHostname(hostname string) string {
	hostname = strings.ToLower(hostname)
	hostname = strings.ReplaceAll(hostname, " ", "-")
	hostname = strings.ReplaceAll(hostname, ".", "-")
	return hostname
}

func setupLogger(level string) {
	var logLevel slog.Level
	switch level {
	case "debug":
		logLevel = slog.LevelDebug
	case "warn":
		logLevel = slog.LevelWarn
	case "error":
		logLevel = slog.LevelError
	default:
		logLevel = slog.LevelInfo
	}

	handler := slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: logLevel})
	slog.SetDefault(slog.New(handler))
}
