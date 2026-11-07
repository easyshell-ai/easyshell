package ws

import (
	"context"
	"net/http"
	"net/http/httptest"
	"runtime"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/gorilla/websocket"

	"github.com/easyshell-org/easyshell/easyshell-agent/internal/client"
	"github.com/easyshell-org/easyshell/easyshell-agent/internal/executor"
)

// wsTestServer upgrades every request and hands the connection to handle.
func wsTestServer(t *testing.T, handle func(*websocket.Conn)) *httptest.Server {
	t.Helper()
	upgrader := websocket.Upgrader{}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			t.Errorf("upgrade: %v", err)
			return
		}
		defer conn.Close()
		handle(conn)
	}))
	t.Cleanup(srv.Close)
	return srv
}

func wsURLOf(srv *httptest.Server) string {
	return "ws" + strings.TrimPrefix(srv.URL, "http")
}

func dialWS(t *testing.T, wsURL string) *websocket.Conn {
	t.Helper()
	conn, _, err := websocket.DefaultDialer.Dial(wsURL, nil)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	t.Cleanup(func() { _ = conn.Close() })
	return conn
}

// readUntilError keeps the server side of a connection alive so control frames
// are processed; it returns when the peer goes away.
func readUntilError(conn *websocket.Conn) {
	for {
		if _, _, err := conn.ReadMessage(); err != nil {
			return
		}
	}
}

// TestPingLoopKeepsHeartbeatingThenStopsWhenReadLoopFinishes covers the whole
// lifecycle: pings flow while the connection is alive, and the moment the read
// loop is done (done closed) the loop returns and never writes again.
func TestPingLoopKeepsHeartbeatingThenStopsWhenReadLoopFinishes(t *testing.T) {
	var pings atomic.Int64
	srv := wsTestServer(t, func(conn *websocket.Conn) {
		conn.SetPingHandler(func(string) error {
			pings.Add(1)
			return conn.WriteControl(websocket.PongMessage, nil, time.Now().Add(time.Second))
		})
		readUntilError(conn)
	})
	conn := dialWS(t, wsURLOf(srv))

	done := make(chan struct{})
	returned := make(chan struct{})
	go func() {
		defer close(returned)
		pingLoop(context.Background(), conn, done, 10*time.Millisecond)
	}()

	deadline := time.Now().Add(5 * time.Second)
	for pings.Load() < 2 && time.Now().Before(deadline) {
		time.Sleep(5 * time.Millisecond)
	}
	if got := pings.Load(); got < 2 {
		t.Fatalf("server saw %d pings, want at least 2", got)
	}

	// This is what connectAndListen's defer does once the read loop returns.
	close(done)

	select {
	case <-returned:
	case <-time.After(5 * time.Second):
		t.Fatal("ping loop did not return after the read loop finished")
	}

	settled := pings.Load()
	time.Sleep(200 * time.Millisecond) // many ping intervals
	if got := pings.Load(); got != settled {
		t.Fatalf("server saw %d more pings after the read loop finished, want 0", got-settled)
	}
}

func TestPingLoopStopsWhenContextIsCancelled(t *testing.T) {
	srv := wsTestServer(t, readUntilError)
	conn := dialWS(t, wsURLOf(srv))

	ctx, cancel := context.WithCancel(context.Background())
	returned := make(chan struct{})
	go func() {
		defer close(returned)
		pingLoop(ctx, conn, make(chan struct{}), 10*time.Millisecond)
	}()

	time.Sleep(30 * time.Millisecond) // let it tick at least once
	cancel()

	select {
	case <-returned:
	case <-time.After(5 * time.Second):
		t.Fatal("ping loop did not return after the context was cancelled")
	}
}

// TestConnectAndListenDoesNotLeakPingGoroutine walks the real path: the server
// drops the connection, the read loop returns an error, and the ping goroutine
// has to go away with it.
func TestConnectAndListenDoesNotLeakPingGoroutine(t *testing.T) {
	connected := make(chan struct{})
	release := make(chan struct{})
	srv := wsTestServer(t, func(conn *websocket.Conn) {
		close(connected)
		<-release
	})

	c := NewClient(srv.URL, "ag_ping_leak", client.NewHTTPClient(srv.URL, ""), executor.New(), nil)

	before := runtime.NumGoroutine()
	errs := make(chan error, 1)
	go func() { errs <- c.connectAndListen(context.Background()) }()

	<-connected
	close(release) // server closes the connection, the agent's read loop fails

	select {
	case err := <-errs:
		if err == nil {
			t.Fatal("connectAndListen should report the read failure")
		}
	case <-time.After(10 * time.Second):
		t.Fatal("connectAndListen did not return")
	}

	deadline := time.Now().Add(10 * time.Second)
	for runtime.NumGoroutine() > before && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	if got := runtime.NumGoroutine(); got > before {
		t.Fatalf("goroutines leaked by the dropped connection: before=%d after=%d", before, got)
	}
}
