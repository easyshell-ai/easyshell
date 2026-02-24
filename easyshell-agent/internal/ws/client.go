package ws

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"

	"github.com/easyshell-org/easyshell/easyshell-agent/internal/client"
	"github.com/easyshell-org/easyshell/easyshell-agent/internal/executor"
	"github.com/easyshell-org/easyshell/easyshell-agent/internal/terminal"
)

type Client struct {
	ServerURL   string
	AgentID     string
	httpClient  *client.HTTPClient
	executor    *executor.Executor
	termManager *terminal.Manager
	conn        *websocket.Conn
	mu          sync.Mutex
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

func NewClient(serverURL string, agentID string, httpClient *client.HTTPClient, exec *executor.Executor) *Client {
	return &Client{
		ServerURL:   serverURL,
		AgentID:     agentID,
		httpClient:  httpClient,
		executor:    exec,
		termManager: terminal.NewManager(),
	}
}

func (c *Client) Start(ctx context.Context) {
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
			case <-time.After(5 * time.Second):
				slog.Info("reconnecting websocket...")
			}
		}
	}
}

func (c *Client) connectAndListen(ctx context.Context) error {
	wsURL := c.buildWSURL()
	slog.Info("connecting websocket", "url", wsURL)

	conn, _, err := websocket.DefaultDialer.Dial(wsURL, nil)
	if err != nil {
		return fmt.Errorf("dial: %w", err)
	}
	c.conn = conn
	slog.Info("websocket connected")

	defer c.close()

	// Set pong handler to reset read deadline on pong received
	conn.SetPongHandler(func(appData string) error {
		slog.Debug("pong received")
		return nil
	})

	// Start ping goroutine to keep connection alive
	pingDone := make(chan struct{})
	go func() {
		defer close(pingDone)
		ticker := time.NewTicker(30 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				c.mu.Lock()
				if c.conn != nil {
					if err := c.conn.WriteMessage(websocket.PingMessage, nil); err != nil {
						slog.Warn("ping send failed", "error", err)
						c.mu.Unlock()
						return
					}
					slog.Debug("ping sent")
				}
				c.mu.Unlock()
			}
		}
	}()

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
		go c.executeJob(ctx, execMsg)
	case "terminal_open", "terminal_input", "terminal_resize", "terminal_close":
		c.handleTerminalMessage(data, msg)
	default:
		slog.Warn("unknown message type", "type", msgType)
	}
}

func (c *Client) executeJob(ctx context.Context, msg ExecuteMessage) {
	slog.Info("executing job", "job_id", msg.JobID, "task_id", msg.TaskID, "timeout", msg.Timeout)

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

	exitCode, err := c.executor.ExecuteWithCallback(execCtx, msg.Script, onLog)

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

	if err := c.sendJSON(result); err != nil {
		slog.Warn("ws result send failed, falling back to HTTP", "error", err)
		_ = c.httpClient.ReportJobResult(&client.JobResultRequest{
			JobID:    msg.JobID,
			AgentID:  c.AgentID,
			Status:   status,
			ExitCode: exitCode,
			Output:   output,
		})
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
	url := c.ServerURL
	url = strings.Replace(url, "https://", "wss://", 1)
	url = strings.Replace(url, "http://", "ws://", 1)
	return url + "/ws/agent/" + c.AgentID
}
