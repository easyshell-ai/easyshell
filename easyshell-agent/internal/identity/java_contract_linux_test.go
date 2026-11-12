//go:build linux

package identity

import (
	"bytes"
	"crypto/ed25519"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"testing"
)

// The seed and public key are RFC 8032 section 7.1, test 1. The protocol
// signatures were independently generated with OpenJDK 21 Signature Ed25519,
// using AgentBoundKeyService's UTF-8 input construction and X.509 key format.
// This fixture does not execute the Java service or an agent signing API.
func TestJavaBoundKeySigningVectors(t *testing.T) {
	const (
		agent       = "agent-测试"
		generation  = 42
		token       = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmno-_"
		seedHex     = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"
		spkiHex     = "302a300506032b6570032100d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
		spkiBase64  = "MCowBQYDK2VwAyEA11qYAYKxCrfVS/7TyWQHOg7hcvPapiMlrwIaaPcHURo="
		tokenDigest = "3160cd16097ed635e9428c9884499d55878924b83b88b177f43ddf1aaeaff8ad"
	)
	decode := func(s string) []byte {
		t.Helper()
		b, err := hex.DecodeString(s)
		if err != nil {
			t.Fatal(err)
		}
		return b
	}
	key := ed25519.NewKeyFromSeed(decode(seedHex))
	dir := filepath.Join(t.TempDir(), "identity")
	if err := os.Mkdir(dir, 0700); err != nil {
		t.Fatal(err)
	}
	data, err := json.Marshal(Identity{AgentID: agent, PrivateKey: key})
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "identity.json"), data, 0600); err != nil {
		t.Fatal(err)
	}
	id, err := LoadOrCreate(dir, agent)
	if err != nil {
		t.Fatal(err)
	}
	pub := id.PrivateKey.Public().(ed25519.PublicKey)
	spki, err := x509.MarshalPKIXPublicKey(pub)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(spki, decode(spkiHex)) {
		t.Fatalf("SPKI: %x", spki)
	}
	if got := base64.StdEncoding.EncodeToString(spki); got != spkiBase64 {
		t.Fatalf("SPKI base64: %s", got)
	}
	digest := sha256.Sum256([]byte(token))
	if got := hex.EncodeToString(digest[:]); got != tokenDigest {
		t.Fatalf("token digest: %s", got)
	}

	for _, tc := range []struct{ name, input, signature string }{
		{"binding", fmt.Sprintf("easyshell-agent-bind-v1\n%s\n%d\n%s\n%s", agent, generation, hex.EncodeToString(digest[:]), base64.StdEncoding.EncodeToString(spki)), "a05938b14317ce0c0eccd20d18913eafb5328eddfb8a0bf0836f9d3d951c9dbf5c999046ce3812c5c0a7a1c18023803f907d9af49dae2e423c4d9caefe0d9202"},
		{"proof", fmt.Sprintf("easyshell-agent-proof-v1\n%s\n%d\n%s", agent, generation, token), "dde0671e177bf1fd23070e474ea6e1ae3fabfd56b34c17073f79c62b6c9b8a72c3063bdde2a88dd32824ba39e74900f1ffe2c588b67518e5b9fd2b57ac0de508"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			want := decode(tc.signature)
			if got := ed25519.Sign(id.PrivateKey, []byte(tc.input)); !bytes.Equal(got, want) {
				t.Fatalf("signature: %x", got)
			}
			if !ed25519.Verify(pub, []byte(tc.input), want) {
				t.Fatal("Java signature rejected")
			}
			if ed25519.Verify(pub, []byte(tc.input+"\n"), want) {
				t.Fatal("accepted trailing newline")
			}
		})
	}
}
