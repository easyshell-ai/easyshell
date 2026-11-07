package terminal

import (
	"fmt"
	"os"
	"path/filepath"
	"testing"
	"time"
)

// scriptShell writes an executable shell that behaves as described by body and
// points SHELL at it, so a session can be driven to exit (or kept alive)
// without a real user typing anything.
func scriptShell(t *testing.T, body string) {
	t.Helper()
	path := filepath.Join(t.TempDir(), "shell.sh")
	if err := os.WriteFile(path, []byte("#!/bin/sh\n"+body+"\n"), 0o755); err != nil {
		t.Fatalf("write shell script: %v", err)
	}
	t.Setenv("SHELL", path)
}

// waitFor polls cond until it holds or the deadline passes.
func waitFor(t *testing.T, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for %s", what)
}

func sessionCount(m *Manager) int {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return len(m.sessions)
}

func TestManagerUnregistersSessionWhenShellExits(t *testing.T) {
	scriptShell(t, "exit 0")
	m := NewManager()
	defer m.CloseAll()

	if _, err := m.Create("sess-1", func([]byte) {}); err != nil {
		t.Fatalf("Create: %v", err)
	}
	if got := sessionCount(m); got != 1 {
		t.Fatalf("sessions after create = %d, want 1", got)
	}

	waitFor(t, "the manager to forget the exited session", func() bool {
		_, ok := m.Get("sess-1")
		return !ok
	})

	if got := sessionCount(m); got != 0 {
		t.Fatalf("sessions after the shell exited = %d, want 0", got)
	}
}

func TestManagerUnregistersSessionWhenUserTypesExit(t *testing.T) {
	// A shell that reads commands and exits on "exit", i.e. what a real user does.
	scriptShell(t, "while read -r line; do\n  if [ \"$line\" = exit ]; then exit 0; fi\ndone")
	m := NewManager()
	defer m.CloseAll()

	session, err := m.Create("sess-exit", func([]byte) {})
	if err != nil {
		t.Fatalf("Create: %v", err)
	}
	// The realistic path: the user types "exit" and the shell goes away.
	if err := session.Write([]byte("exit\n")); err != nil {
		t.Fatalf("Write: %v", err)
	}

	waitFor(t, "the manager to forget the session the user exited", func() bool {
		_, ok := m.Get("sess-exit")
		return !ok
	})
	if got := sessionCount(m); got != 0 {
		t.Fatalf("sessions after the user exited = %d, want 0", got)
	}
}

func TestManagerAcceptsNewSessionAfterEveryShellExited(t *testing.T) {
	scriptShell(t, "exit 0")
	m := NewManager()
	defer m.CloseAll()

	for i := 0; i < MaxSessions; i++ {
		id := fmt.Sprintf("sess-%d", i)
		if _, err := m.Create(id, func([]byte) {}); err != nil {
			t.Fatalf("Create(%s): %v", id, err)
		}
	}
	// Every shell exits on its own, so every slot has to come back.
	for i := 0; i < MaxSessions; i++ {
		id := fmt.Sprintf("sess-%d", i)
		waitFor(t, id+" to be unregistered", func() bool {
			_, ok := m.Get(id)
			return !ok
		})
	}

	if got := sessionCount(m); got != 0 {
		t.Fatalf("sessions after all %d shells exited = %d, want 0", MaxSessions, got)
	}
	if _, err := m.Create("sess-after-exit", func([]byte) {}); err != nil {
		t.Fatalf("Create after all sessions exited: %v", err)
	}
}

func TestManagerCloseRemovesSession(t *testing.T) {
	scriptShell(t, "sleep 30")
	m := NewManager()
	defer m.CloseAll()

	if _, err := m.Create("sess-close", func([]byte) {}); err != nil {
		t.Fatalf("Create: %v", err)
	}
	m.Close("sess-close")

	if _, ok := m.Get("sess-close"); ok {
		t.Fatal("Close left the session registered")
	}
	if got := sessionCount(m); got != 0 {
		t.Fatalf("sessions after Close = %d, want 0", got)
	}
}

func TestUnregisterIgnoresStaleSession(t *testing.T) {
	m := NewManager()
	live := &Session{ID: "dup"}
	stale := &Session{ID: "dup"}
	m.sessions["dup"] = live

	// A predecessor that outlives its replacement must not evict it.
	m.unregister("dup", stale)
	if got := m.sessions["dup"]; got != live {
		t.Fatalf("stale session evicted the live one: got %v, want %v", got, live)
	}

	m.unregister("dup", live)
	if got := len(m.sessions); got != 0 {
		t.Fatalf("live session was not unregistered: %d sessions left", got)
	}

	// Unregistering twice is a no-op, never a panic.
	m.unregister("dup", live)
}

func TestUnregisterUnknownSessionIsNoOp(t *testing.T) {
	m := NewManager()
	m.sessions["other"] = &Session{ID: "other"}

	m.unregister("missing", &Session{ID: "missing"})

	if got := len(m.sessions); got != 1 {
		t.Fatalf("sessions = %d, want 1", got)
	}
}
