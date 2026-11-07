package ws

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"

	"github.com/easyshell-org/easyshell/easyshell-agent/internal/client"
	"github.com/easyshell-org/easyshell/easyshell-agent/internal/executor"
	"github.com/easyshell-org/easyshell/easyshell-agent/internal/terminal"
	"github.com/easyshell-org/easyshell/easyshell-agent/internal/fileserver"
)

const (
	// Reconnect backoff: fixed 5s forever hammered a struggling server and never gave up.
	minReconnectDelay = 2 * time.Second
	maxReconnectDelay = 60 * time.Second
	readDeadline      = 90 * time.Second
	maxMessageBytes   = 4 * 1024 * 1024
	maxConcurrentJobs = 8
	pingInterval      = 30 * time.Second
	pingWriteTimeout  = 10 * time.Second
)

type Client struct {
	ServerURL   string
	AgentID     string
	httpClient  *client.HTTPClient
	executor    *executor.Executor
	termManager *terminal.Manager
	fileHandler *fileserver.Handler
	conn        *websocket.Conn
	mu          sync.Mutex
	jobs        chan struct{}
}

type ExecuteMessage struct {
	Type    string `json:"type"`
	JobID   string `json:"jobId"`
	TaskID  string `json:"taskId"`
	Script  string `json:"script"`
	Timeout int    `json:"timeout"`
}

type LogMessage struct {
	Type  string `json:"type"`
	JobID string `json:"jobId"`
	Log   string `json:"log"`
}

type ResultMessage struct {
	Type     string `json:"type"`
	JobID    string `json:"jobId"`
	Status   int    `json:"status"`
	ExitCode int    `json:"exitCode"`
	Output   string `json:"output"`
}

func NewClient(serverURL string, agentID string, httpClient *client.HTTPClient, exec *executor.Executor, fileHandler *fileserver.Handler) *Client {
	return &Client{
		ServerURL:   serverURL,
		AgentID:     agentID,
		httpClient:  httpClient,
		executor:    exec,
		termManager: terminal.NewManager(),
		fileHandler: fileHandler,
		jobs:        make(chan struct{}, maxConcurrentJobs),
	}
}

func (c *Client) Start(ctx context.Context) {
	delay := minReconnectDelay
	for {
		select {
		case <-ctx.Done():
			c.close()
			slog.Info("websocket client stopped")
			return
		default:
			if err := c.connectAndListen(ctx); err != nil {
				slog.Error("websocket connection error", "error", err)
			}
			select {
			case <-ctx.Done():
				return
			case <-time.After(delay):
				slog.Info("reconnecting websocket...", "delay", delay.String())
			}
			// Exponential backoff, reset as soon as a connection stays up.
			if delay < maxReconnectDelay {
				delay *= 2
				if delay > maxReconnectDelay {
					delay = maxReconnectDelay
				}
			}
		}
	}
}

func (c *Client) connectAndListen(ctx context.Context) error {
	wsURL := c.buildWSURL()
	slog.Info("connecting websocket", "url", wsURL)

	header := http.Header{}
	if token := c.httpClient.AgentToken(); token != "" {
		header.Set("Authorization", "Bearer "+token)
	}

	conn, _, err := websocket.DefaultDialer.Dial(wsURL, header)
	if err != nil {
		return fmt.Errorf("dial: %w", err)
	}

	c.mu.Lock()
	c.conn = conn
	c.mu.Unlock()

	conn.SetReadLimit(maxMessageBytes)
	slog.Info("websocket connected")

	defer c.close()

	// Reset the read deadline on every pong so a half-open connection is detected.
	conn.SetPongHandler(func(appData string) error {
		_ = conn.SetReadDeadline(time.Now().Add(readDeadline))
		slog.Debug("pong received")
		return nil
	})
	_ = conn.SetReadDeadline(time.Now().Add(readDeadline))

	// Start ping goroutine to keep connection alive. pingDone is closed when the
	// read loop below returns, which is the only reliable signal that this
	// connection is over: without it a flapping connection strands one goroutine
	// per attempt, spinning on an empty ticker until the process shuts down.
	pingDone := make(chan struct{})
	defer close(pingDone)
	go pingLoop(ctx, conn, pingDone, pingInterval)

	for {
		select {
		case <-ctx.Done():
			return nil
		default:
			_, message, err := conn.ReadMessage()
			if err != nil {
				return fmt.Errorf("read: %w", err)
			}
			c.handleMessage(ctx, message)
		}
	}
}

// pingLoop heartbeats one connection every interval until its read loop
// finishes (done), the context is cancelled, or a write fails. conn is the
// connection owned by the caller, so a loop belonging to a dead connection can
// never ping its successor. WriteControl is the one writer that is safe to run
// concurrently with sendJSON, so the heartbeat needs no lock.
func pingLoop(ctx context.Context, conn *websocket.Conn, done <-chan struct{}, interval time.Duration) {
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case <-done:
			return
		case <-ctx.Done():
			return
		case <-ticker.C:
			if err := conn.WriteControl(websocket.PingMessage, nil, time.Now().Add(pingWriteTimeout)); err != nil {
				slog.Warn("ping send failed", "error", err)
				return
			}
			slog.Debug("ping sent")
		}
	}
}

func (c *Client) handleMessage(ctx context.Context, data []byte) {
	var msg map[string]interface{}
	if err := json.Unmarshal(data, &msg); err != nil {
		slog.Error("failed to parse message", "error", err)
		return
	}

	msgType, _ := msg["type"].(string)
	switch msgType {
	case "execute":
		var execMsg ExecuteMessage
		if err := json.Unmarshal(data, &execMsg); err != nil {
			slog.Error("failed to parse execute message", "error", err)
			return
		}
		select {
		case c.jobs <- struct{}{}:
			go func() {
				defer func() { <-c.jobs }()
				c.executeJob(ctx, execMsg)
			}()
		default:
			// Never let the server exhaust the agent with concurrent jobs.
			slog.Warn("too many concurrent jobs, rejecting", "job_id", execMsg.JobID)
			result := ResultMessage{
				Type:     "result",
				JobID:    execMsg.JobID,
				Status:   3,
				ExitCode: -1,
				Output:   "agent is busy: too many concurrent jobs",
			}
			if err := c.sendJSON(result); err != nil {
				slog.Debug("failed to report busy state", "error", err)
			}
		}
	case "terminal_open", "terminal_input", "terminal_resize", "terminal_close":
		c.handleTerminalMessage(data, msg)
	case "file_list", "file_download", "file_upload_start", "file_upload_chunk", "file_mkdir", "file_delete", "file_rename":
		c.handleFileMessage(data)
	default:
		slog.Warn("unknown message type", "type", msgType)
	}
}

func (c *Client) executeJob(ctx context.Context, msg ExecuteMessage) {
	defer func() {
		if r := recover(); r != nil {
			slog.Error("PANIC in executeJob - recovered", "job_id", msg.JobID, "panic", r)
			// Try to report the failure back
			result := ResultMessage{
				Type:     "result",
				JobID:    msg.JobID,
				Status:   3, // failed
				ExitCode: -1,
				Output:   fmt.Sprintf("agent panic: %v", r),
			}
			if err := c.sendJSON(result); err != nil {
				slog.Error("failed to send panic result via WS", "error", err)
				_ = c.httpClient.ReportJobResult(&client.JobResultRequest{
					JobID:    msg.JobID,
					AgentID:  c.AgentID,
					Status:   3,
					ExitCode: -1,
					Output:   fmt.Sprintf("agent panic: %v", r),
				})
			}
		}
	}()

	slog.Info("executing job", "job_id", msg.JobID, "task_id", msg.TaskID, "timeout", msg.Timeout, "script_length", len(msg.Script))

	timeout := time.Duration(msg.Timeout) * time.Second
	if timeout == 0 {
		timeout = 10 * time.Minute
	}
	execCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	var outputLines []string
	var mu sync.Mutex

	onLog := func(line string) {
		mu.Lock()
		outputLines = append(outputLines, line)
		mu.Unlock()

		logMsg := LogMessage{
			Type:  "log",
			JobID: msg.JobID,
			Log:   line,
		}

		if err := c.sendJSON(logMsg); err != nil {
			slog.Debug("ws log send failed, falling back to HTTP", "error", err)
			_ = c.httpClient.ReportJobLog(msg.JobID, line)
		}
	}

	slog.Info("starting script execution", "job_id", msg.JobID)
	exitCode, err := c.executor.ExecuteWithCallback(execCtx, msg.Script, onLog)
	slog.Info("script execution completed", "job_id", msg.JobID, "exit_code", exitCode, "error", err)

	const statusSuccess = 2
	const statusFailed = 3

	mu.Lock()
	fullOutput := strings.Join(outputLines, "\n")
	mu.Unlock()

	status := statusSuccess
	output := fullOutput
	if err != nil {
		status = statusFailed
		if fullOutput != "" {
			output = fullOutput + "\n" + err.Error()
		} else {
			output = err.Error()
		}
		slog.Error("job execution error", "job_id", msg.JobID, "error", err)
	} else if exitCode != 0 {
		status = statusFailed
		if fullOutput != "" {
			output = fullOutput + "\nexit code: " + fmt.Sprintf("%d", exitCode)
		} else {
			output = fmt.Sprintf("exit code: %d", exitCode)
		}
	}

	result := ResultMessage{
		Type:     "result",
		JobID:    msg.JobID,
		Status:   status,
		ExitCode: exitCode,
		Output:   output,
	}

	slog.Info("sending job result", "job_id", msg.JobID, "status", status, "output_length", len(output))
	if err := c.sendJSON(result); err != nil {
		slog.Warn("ws result send failed, falling back to HTTP", "job_id", msg.JobID, "error", err)
		_ = c.httpClient.ReportJobResult(&client.JobResultRequest{
			JobID:    msg.JobID,
			AgentID:  c.AgentID,
			Status:   status,
			ExitCode: exitCode,
			Output:   output,
		})
	} else {
		slog.Info("job result sent successfully via WS", "job_id", msg.JobID)
	}
}

func (c *Client) sendJSON(v interface{}) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.conn == nil {
		return fmt.Errorf("no connection")
	}
	data, err := json.Marshal(v)
	if err != nil {
		return err
	}
	return c.conn.WriteMessage(websocket.TextMessage, data)
}

func (c *Client) handleTerminalMessage(data []byte, msg map[string]interface{}) {
	sessionID, _ := msg["sessionId"].(string)
	msgType, _ := msg["type"].(string)

	err := c.termManager.HandleMessage(data, func(output []byte) {
		payload := map[string]interface{}{
			"type":      "terminal_output",
			"sessionId": sessionID,
			"data":      string(output),
		}
		if sendErr := c.sendJSON(payload); sendErr != nil {
			slog.Debug("failed to send terminal output", "error", sendErr)
		}
	})
	if err != nil {
		slog.Error("terminal message error", "error", err)
		errPayload := map[string]interface{}{
			"type":      "terminal_error",
			"sessionId": sessionID,
			"data":      err.Error(),
		}
		_ = c.sendJSON(errPayload)
		return
	}

	if msgType == "terminal_open" {
		readyPayload := map[string]interface{}{
			"type":      "terminal_ready",
			"sessionId": sessionID,
		}
		if sendErr := c.sendJSON(readyPayload); sendErr != nil {
			slog.Error("failed to send terminal_ready", "error", sendErr)
		}
	}
}

func (c *Client) handleFileMessage(data []byte) {
	c.fileHandler.HandleMessage(data, func(resp interface{}) {
		if err := c.sendJSON(resp); err != nil {
			slog.Error("failed to send file response", "error", err)
		}
	})
}

func (c *Client) close() {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.conn != nil {
		c.termManager.CloseAll()
		_ = c.conn.Close()
		c.conn = nil
	}
}

func (c *Client) buildWSURL() string {
	endpoint := c.ServerURL
	endpoint = strings.Replace(endpoint, "https://", "wss://", 1)
	endpoint = strings.Replace(endpoint, "http://", "ws://", 1)
	// The agent id ends up in the request path, so it has to be escaped: an id containing
	// "/" or ".." would otherwise change which endpoint is addressed.
	return endpoint + "/ws/agent/" + url.PathEscape(c.AgentID)
}
