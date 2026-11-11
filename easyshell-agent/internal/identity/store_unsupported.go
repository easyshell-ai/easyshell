//go:build !linux

package identity

import (
	"crypto/ed25519"
	"errors"
)

type Identity struct {
	AgentID    string             `json:"agent_id"`
	PrivateKey ed25519.PrivateKey `json:"private_key"`
}

// LoadOrCreate fails closed on platforms without this storage implementation.
func LoadOrCreate(dir, agentID string) (*Identity, error) {
	return nil, errors.New("identity: secure storage unsupported on this platform")
}
