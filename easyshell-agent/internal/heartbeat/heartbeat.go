package heartbeat

import (
	"context"
	"log/slog"
	"sync"
	"time"

	"github.com/easyshell-org/easyshell/easyshell-agent/internal/client"
	"github.com/easyshell-org/easyshell/easyshell-agent/internal/collector"
)

type Service struct {
	client   *client.HTTPClient
	agentID  string
	interval time.Duration
	mu       sync.RWMutex
}

func NewService(c *client.HTTPClient, agentID string, intervalSec int) *Service {
	return &Service{
		client:   c,
		agentID:  agentID,
		interval: time.Duration(intervalSec) * time.Second,
	}
}

func (s *Service) UpdateInterval(newInterval time.Duration) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if newInterval != s.interval && newInterval >= 1*time.Second {
		slog.Info("heartbeat interval updated", "old", s.interval, "new", newInterval)
		s.interval = newInterval
	}
}

func (s *Service) getInterval() time.Duration {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.interval
}

func (s *Service) Start(ctx context.Context) {
	slog.Info("heartbeat service starting", "interval", s.interval)

	s.sendHeartbeat()

	ticker := time.NewTicker(s.getInterval())
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			slog.Info("heartbeat service stopped")
			return
		case <-ticker.C:
			s.sendHeartbeat()
			currentInterval := s.getInterval()
			ticker.Reset(currentInterval)
		}
	}
}

func (s *Service) sendHeartbeat() {
	cpuUsage, memUsage, diskUsage := collector.CollectMetrics()

	req := &client.HeartbeatRequest{
		AgentID:   s.agentID,
		CPUUsage:  cpuUsage,
		MemUsage:  memUsage,
		DiskUsage: diskUsage,
	}

	if err := s.client.SendHeartbeat(req); err != nil {
		slog.Error("heartbeat failed", "error", err)
	}
}
