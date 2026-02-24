package terminal

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"os"
	"os/exec"
	"sync"

	"github.com/creack/pty"
)

// Session represents an active PTY terminal session
type Session struct {
	ID     string
	cmd    *exec.Cmd
	ptmx   *os.File
	mu     sync.Mutex
	cancel context.CancelFunc
	closed bool
}

// TerminalInput represents input from the browser
type TerminalInput struct {
	Type string `json:"type"`
	Data string `json:"data,omitempty"`
	Cols uint16 `json:"cols,omitempty"`
	Rows uint16 `json:"rows,omitempty"`
}

// TerminalOutput represents output to the browser
type TerminalOutput struct {
	Type string `json:"type"`
	Data string `json:"data,omitempty"`
}

// OutputCallback is called when the PTY produces output
type OutputCallback func(data []byte)

// NewSession creates a new PTY terminal session
func NewSession(id string, onOutput OutputCallback) (*Session, error) {
	ctx, cancel := context.WithCancel(context.Background())

	shell := getShell()
	cmd := exec.CommandContext(ctx, shell)
	cmd.Env = append(os.Environ(), "TERM=xterm-256color")

	ptmx, err := pty.Start(cmd)
	if err != nil {
		cancel()
		return nil, fmt.Errorf("start pty: %w", err)
	}

	// Set initial size
	_ = pty.Setsize(ptmx, &pty.Winsize{Rows: 24, Cols: 80})

	session := &Session{
		ID:     id,
		cmd:    cmd,
		ptmx:   ptmx,
		cancel: cancel,
	}

	// Start reading output from PTY
	go func() {
		buf := make([]byte, 4096)
		for {
			n, err := ptmx.Read(buf)
			if err != nil {
				if err != io.EOF {
					slog.Debug("pty read error", "session", id, "error", err)
				}
				break
			}
			if n > 0 {
				onOutput(buf[:n])
			}
		}
		slog.Info("terminal session ended", "session", id)
	}()

	// Wait for process to exit, then cleanup
	go func() {
		_ = cmd.Wait()
		session.Close()
	}()

	_ = ctx // keep linter happy

	slog.Info("terminal session started", "session", id, "shell", shell)
	return session, nil
}

// Write sends input to the PTY
func (s *Session) Write(data []byte) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return fmt.Errorf("session closed")
	}
	_, err := s.ptmx.Write(data)
	return err
}

// Resize changes the PTY window size
func (s *Session) Resize(cols, rows uint16) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return fmt.Errorf("session closed")
	}
	return pty.Setsize(s.ptmx, &pty.Winsize{Cols: cols, Rows: rows})
}

// Close terminates the session
func (s *Session) Close() {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return
	}
	s.closed = true
	s.cancel()
	_ = s.ptmx.Close()
	slog.Info("terminal session closed", "session", s.ID)
}

// Manager manages multiple terminal sessions
type Manager struct {
	sessions map[string]*Session
	mu       sync.RWMutex
}

// NewManager creates a new terminal session manager
func NewManager() *Manager {
	return &Manager{
		sessions: make(map[string]*Session),
	}
}

// Create creates a new terminal session
func (m *Manager) Create(id string, onOutput OutputCallback) (*Session, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	// Close existing session with same ID
	if existing, ok := m.sessions[id]; ok {
		existing.Close()
		delete(m.sessions, id)
	}

	session, err := NewSession(id, func(data []byte) {
		onOutput(data)
	})
	if err != nil {
		return nil, err
	}

	m.sessions[id] = session
	return session, nil
}

// Get returns an existing session
func (m *Manager) Get(id string) (*Session, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	session, ok := m.sessions[id]
	return session, ok
}

// Close closes and removes a session
func (m *Manager) Close(id string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if session, ok := m.sessions[id]; ok {
		session.Close()
		delete(m.sessions, id)
	}
}

// CloseAll closes all sessions
func (m *Manager) CloseAll() {
	m.mu.Lock()
	defer m.mu.Unlock()
	for id, session := range m.sessions {
		session.Close()
		delete(m.sessions, id)
	}
}

// HandleMessage processes a terminal message from the server
func (m *Manager) HandleMessage(data []byte, onOutput OutputCallback) error {
	var msg map[string]interface{}
	if err := json.Unmarshal(data, &msg); err != nil {
		return fmt.Errorf("unmarshal: %w", err)
	}

	sessionID, _ := msg["sessionId"].(string)
	if sessionID == "" {
		return fmt.Errorf("missing sessionId")
	}

	msgType, _ := msg["type"].(string)

	switch msgType {
	case "terminal_open":
		_, err := m.Create(sessionID, onOutput)
		if err != nil {
			return fmt.Errorf("create session: %w", err)
		}
		return nil

	case "terminal_input":
		session, ok := m.Get(sessionID)
		if !ok {
			return fmt.Errorf("session not found: %s", sessionID)
		}
		input, _ := msg["data"].(string)
		return session.Write([]byte(input))

	case "terminal_resize":
		session, ok := m.Get(sessionID)
		if !ok {
			return fmt.Errorf("session not found: %s", sessionID)
		}
		cols, _ := msg["cols"].(float64)
		rows, _ := msg["rows"].(float64)
		return session.Resize(uint16(cols), uint16(rows))

	case "terminal_close":
		m.Close(sessionID)
		return nil

	default:
		return fmt.Errorf("unknown terminal message type: %s", msgType)
	}
}

func getShell() string {
	shell := os.Getenv("SHELL")
	if shell != "" {
		return shell
	}
	if _, err := exec.LookPath("bash"); err == nil {
		return "bash"
	}
	return "sh"
}
