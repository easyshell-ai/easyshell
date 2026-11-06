package ws

import (
	"net/url"
	"testing"
)

func TestBuildWSURL_EscapesAgentID(t *testing.T) {
	cases := []struct {
		agentID  string
		expected string
	}{
		{"ag_demo_host_01", "ws://localhost:18080/ws/agent/ag_demo_host_01"},
		{"ag_with/slash", "ws://localhost:18080/ws/agent/ag_with%2Fslash"},
		{"../evil", "ws://localhost:18080/ws/agent/..%2Fevil"},
		{"ag?x=1", "ws://localhost:18080/ws/agent/ag%3Fx=1"},
	}

	for _, tc := range cases {
		c := &Client{ServerURL: "http://localhost:18080", AgentID: tc.agentID}
		got := c.buildWSURL()
		if got != tc.expected {
			t.Errorf("agentID %q: expected %q, got %q", tc.agentID, tc.expected, got)
		}
		// The escaped id must never contain a raw path separator or query marker.
		if _, err := url.Parse(got); err != nil {
			t.Errorf("agentID %q produced an unparseable URL: %v", tc.agentID, err)
		}
	}
}

func TestBuildWSURL_UpgradesScheme(t *testing.T) {
	c := &Client{ServerURL: "https://easyshell.example.com", AgentID: "ag_1"}
	if got := c.buildWSURL(); got != "wss://easyshell.example.com/ws/agent/ag_1" {
		t.Fatalf("unexpected URL: %s", got)
	}
}
