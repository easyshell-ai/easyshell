package client

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"time"
)

type HTTPClient struct {
	ServerURL  string
	httpClient *http.Client
}

func NewHTTPClient(serverURL string) *HTTPClient {
	return &HTTPClient{
		ServerURL: serverURL,
		httpClient: &http.Client{
			Timeout: 10 * time.Second,
		},
	}
}

// APIResponse is the generic server response wrapper: {"code":200,"message":"success","data":...}
type APIResponse struct {
	Code    int             `json:"code"`
	Message string          `json:"message"`
	Data    json.RawMessage `json:"data"`
}

// RegisterRequest matches AgentRegisterRequest on server
type RegisterRequest struct {
	AgentID      string `json:"agentId"`
	Hostname     string `json:"hostname"`
	IP           string `json:"ip"`
	OS           string `json:"os"`
	Arch         string `json:"arch"`
	Kernel       string `json:"kernel"`
	CPUModel     string `json:"cpuModel"`
	CPUCores     int    `json:"cpuCores"`
	MemTotal     uint64 `json:"memTotal"`
	AgentVersion string `json:"agentVersion"`
}

// HeartbeatRequest matches AgentHeartbeatRequest on server
type HeartbeatRequest struct {
	AgentID   string  `json:"agentId"`
	CPUUsage  float64 `json:"cpuUsage"`
	MemUsage  float64 `json:"memUsage"`
	DiskUsage float64 `json:"diskUsage"`
}

// JobResultRequest matches JobResultRequest on server
type JobResultRequest struct {
	JobID    string `json:"jobId"`
	AgentID  string `json:"agentId"`
	Status   int    `json:"status"`
	ExitCode int    `json:"exitCode"`
	Output   string `json:"output"`
}

// PendingJob represents a job from GET /api/v1/agent/jobs/pending/{agentId}
type PendingJob struct {
	ID             string `json:"id"`
	TaskID         string `json:"taskId"`
	AgentID        string `json:"agentId"`
	Status         int    `json:"status"`
	ScriptContent  string `json:"scriptContent,omitempty"`
	TimeoutSeconds int    `json:"timeoutSeconds,omitempty"`
}

func (c *HTTPClient) postJSON(path string, body interface{}) (*APIResponse, error) {
	data, err := json.Marshal(body)
	if err != nil {
		return nil, fmt.Errorf("marshal request: %w", err)
	}

	url := c.ServerURL + path
	resp, err := c.httpClient.Post(url, "application/json", bytes.NewReader(data))
	if err != nil {
		return nil, fmt.Errorf("POST %s: %w", path, err)
	}
	defer resp.Body.Close()

	respBody, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("read response: %w", err)
	}

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, fmt.Errorf("POST %s returned %d: %s", path, resp.StatusCode, string(respBody))
	}

	var apiResp APIResponse
	if err := json.Unmarshal(respBody, &apiResp); err != nil {
		return nil, fmt.Errorf("unmarshal response: %w", err)
	}

	if apiResp.Code != 200 {
		return nil, fmt.Errorf("API error %d: %s", apiResp.Code, apiResp.Message)
	}

	return &apiResp, nil
}

func (c *HTTPClient) Register(req *RegisterRequest) error {
	slog.Info("registering agent", "agent_id", req.AgentID, "hostname", req.Hostname, "url", c.ServerURL+"/api/v1/agent/register")
	_, err := c.postJSON("/api/v1/agent/register", req)
	if err != nil {
		return fmt.Errorf("register: %w", err)
	}
	slog.Info("agent registered successfully", "agent_id", req.AgentID)
	return nil
}

func (c *HTTPClient) SendHeartbeat(req *HeartbeatRequest) error {
	slog.Debug("sending heartbeat", "agent_id", req.AgentID, "cpu", req.CPUUsage, "mem", req.MemUsage, "disk", req.DiskUsage)
	_, err := c.postJSON("/api/v1/agent/heartbeat", req)
	if err != nil {
		return fmt.Errorf("heartbeat: %w", err)
	}
	return nil
}

func (c *HTTPClient) ReportJobResult(req *JobResultRequest) error {
	slog.Info("reporting job result", "job_id", req.JobID, "status", req.Status, "exit_code", req.ExitCode)
	_, err := c.postJSON("/api/v1/agent/jobs/result", req)
	if err != nil {
		return fmt.Errorf("report job result: %w", err)
	}
	return nil
}

func (c *HTTPClient) ReportJobLog(jobID string, logLine string) error {
	body := map[string]string{
		"jobId": jobID,
		"log":   logLine,
	}
	_, err := c.postJSON("/api/v1/agent/jobs/log", body)
	if err != nil {
		return fmt.Errorf("report job log: %w", err)
	}
	return nil
}

func (c *HTTPClient) GetPendingJobs(agentID string) ([]PendingJob, error) {
	url := c.ServerURL + "/api/v1/agent/jobs/pending/" + agentID
	resp, err := c.httpClient.Get(url)
	if err != nil {
		return nil, fmt.Errorf("GET pending jobs: %w", err)
	}
	defer resp.Body.Close()

	respBody, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("read response: %w", err)
	}

	var apiResp APIResponse
	if err := json.Unmarshal(respBody, &apiResp); err != nil {
		return nil, fmt.Errorf("unmarshal response: %w", err)
	}

	if apiResp.Code != 200 {
		return nil, fmt.Errorf("API error %d: %s", apiResp.Code, apiResp.Message)
	}

	var jobs []PendingJob
	if apiResp.Data != nil {
		if err := json.Unmarshal(apiResp.Data, &jobs); err != nil {
			return nil, fmt.Errorf("unmarshal jobs: %w", err)
		}
	}

	return jobs, nil
}

func (c *HTTPClient) GetConfig(agentID string) (map[string]string, error) {
	url := c.ServerURL + "/api/v1/agent/config?agentId=" + agentID
	resp, err := c.httpClient.Get(url)
	if err != nil {
		return nil, fmt.Errorf("GET config: %w", err)
	}
	defer resp.Body.Close()

	respBody, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("read response: %w", err)
	}

	var apiResp APIResponse
	if err := json.Unmarshal(respBody, &apiResp); err != nil {
		return nil, fmt.Errorf("unmarshal response: %w", err)
	}

	if apiResp.Code != 200 {
		return nil, fmt.Errorf("API error %d: %s", apiResp.Code, apiResp.Message)
	}

	configMap := make(map[string]string)
	if apiResp.Data != nil {
		if err := json.Unmarshal(apiResp.Data, &configMap); err != nil {
			return nil, fmt.Errorf("unmarshal config: %w", err)
		}
	}

	return configMap, nil
}
