package client

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"sync"
	"time"
)

type HTTPClient struct {
	ServerURL   string
	registerSecret string
	agentToken  string
	tokenMu     sync.Mutex
	httpClient  *http.Client
}

func NewHTTPClient(serverURL string, registerSecret string) *HTTPClient {
	return &HTTPClient{
		ServerURL:      serverURL,
		registerSecret: registerSecret,
		httpClient: &http.Client{
			Timeout: 10 * time.Second,
		},
	}
}

// SetAgentToken installs the credential returned by the server at registration time.
func (c *HTTPClient) SetAgentToken(token string) {
	c.tokenMu.Lock()
	defer c.tokenMu.Unlock()
	c.agentToken = token
}

// AgentToken returns the current credential (empty when not registered yet).
func (c *HTTPClient) AgentToken() string {
	c.tokenMu.Lock()
	defer c.tokenMu.Unlock()
	return c.agentToken
}

func (c *HTTPClient) authHeader(req *http.Request) {
	if token := c.AgentToken(); token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
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
	req, err := http.NewRequest(http.MethodPost, url, bytes.NewReader(data))
	if err != nil {
		return nil, fmt.Errorf("build POST %s: %w", path, err)
	}
	req.Header.Set("Content-Type", "application/json")
	c.authHeader(req)
	resp, err := c.httpClient.Do(req)
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

	data, err := json.Marshal(req)
	if err != nil {
		return fmt.Errorf("marshal register request: %w", err)
	}

	httpReq, err := http.NewRequest(http.MethodPost, c.ServerURL+"/api/v1/agent/register", bytes.NewReader(data))
	if err != nil {
		return fmt.Errorf("build register request: %w", err)
	}
	httpReq.Header.Set("Content-Type", "application/json")
	c.authHeader(httpReq)
	if c.registerSecret != "" {
		// Only used to bootstrap the very first token; afterwards the token itself is sent.
		httpReq.Header.Set("X-Agent-Register-Secret", c.registerSecret)
	}

	resp, err := c.httpClient.Do(httpReq)
	if err != nil {
		return fmt.Errorf("register: %w", err)
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return fmt.Errorf("register: read response: %w", err)
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("register returned %d: %s", resp.StatusCode, string(body))
	}

	var apiResp struct {
		Code    int             `json:"code"`
		Message string          `json:"message"`
		Data    json.RawMessage `json:"data"`
	}
	if err := json.Unmarshal(body, &apiResp); err != nil {
		return fmt.Errorf("register: unmarshal response: %w", err)
	}
	if apiResp.Code != 200 {
		return fmt.Errorf("register: API error %d: %s", apiResp.Code, apiResp.Message)
	}

	var registerResp struct {
		AgentToken string `json:"agentToken"`
	}
	if len(apiResp.Data) > 0 {
		if err := json.Unmarshal(apiResp.Data, &registerResp); err == nil && registerResp.AgentToken != "" {
			c.SetAgentToken(registerResp.AgentToken)
			slog.Info("agent credential received", "agent_id", req.AgentID, "token_length", len(registerResp.AgentToken))
		}
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
	url := c.ServerURL + "/api/v1/agent/jobs/pending/" + url.PathEscape(agentID)
	req, err := http.NewRequest(http.MethodGet, url, nil)
	if err != nil {
		return nil, fmt.Errorf("build pending jobs request: %w", err)
	}
	c.authHeader(req)
	resp, err := c.httpClient.Do(req)
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
	url := c.ServerURL + "/api/v1/agent/config?agentId=" + url.QueryEscape(agentID)
	req, err := http.NewRequest(http.MethodGet, url, nil)
	if err != nil {
		return nil, fmt.Errorf("build config request: %w", err)
	}
	c.authHeader(req)
	resp, err := c.httpClient.Do(req)
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
