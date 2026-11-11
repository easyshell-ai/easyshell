//go:build linux

// Package identity provides a local, agent-bound signing identity. Callers must
// choose a private directory outside every served or shared file tree.
package identity

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"

	"golang.org/x/sys/unix"
)

// Identity contains private key material. It must never be logged or served.
type Identity struct {
	AgentID    string             `json:"agent_id"`
	PrivateKey ed25519.PrivateKey `json:"private_key"`
}

// LoadOrCreate atomically creates an identity or validates the existing one.
// dir must be an absolute, clean path to a dedicated private directory; its
// parent must already exist. Existing identities are never replaced or fixed.
func LoadOrCreate(dir, agentID string) (*Identity, error) {
	if agentID == "" || !filepath.IsAbs(dir) || filepath.Clean(dir) != dir || dir == "/" {
		return nil, errors.New("identity: invalid directory or agent ID")
	}
	fd, err := openDirectory(dir)
	if err != nil {
		return nil, err
	}
	defer unix.Close(fd)
	id, err := readIdentity(fd, agentID)
	if err == nil {
		return id, nil
	}
	if !errors.Is(err, unix.ENOENT) {
		return nil, err
	}
	_, key, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		return nil, err
	}
	data, err := json.Marshal(Identity{AgentID: agentID, PrivateKey: key})
	if err != nil {
		return nil, err
	}
	var suffix [16]byte
	if _, err = rand.Read(suffix[:]); err != nil {
		return nil, err
	}
	tmp := fmt.Sprintf(".identity-%x", suffix)
	f, err := unix.Openat(fd, tmp, unix.O_WRONLY|unix.O_CREAT|unix.O_EXCL|unix.O_NOFOLLOW|unix.O_CLOEXEC, 0600)
	if err != nil {
		return nil, err
	}
	defer unix.Unlinkat(fd, tmp, 0)
	file := os.NewFile(uintptr(f), tmp)
	_, err = file.Write(data)
	if err == nil {
		err = file.Chmod(0600)
	}
	if err == nil {
		err = file.Sync()
	}
	if closeErr := file.Close(); err == nil {
		err = closeErr
	}
	if err != nil {
		return nil, err
	}
	err = unix.Renameat2(fd, tmp, fd, "identity.json", unix.RENAME_NOREPLACE)
	if err != nil && !errors.Is(err, unix.EEXIST) {
		return nil, err
	}
	if err == nil {
		if err = unix.Fsync(fd); err != nil {
			return nil, err
		}
	}
	return readIdentity(fd, agentID)
}

// openDirectory walks using directory descriptors and refuses symlinks and
// untrusted writable ancestors. Root-owned sticky directories (e.g. /tmp) are
// safe ancestors because other users cannot rename another owner's entries.
func openDirectory(path string) (int, error) {
	return openDirectoryWithSync(path, unix.Fsync)
}

func openDirectoryWithSync(path string, syncParent func(int) error) (int, error) {
	fd, err := unix.Open("/", unix.O_RDONLY|unix.O_DIRECTORY|unix.O_CLOEXEC, 0)
	if err != nil {
		return -1, err
	}
	parts := strings.Split(strings.TrimPrefix(path, "/"), "/")
	for i, part := range parts {
		if i == len(parts)-1 {
			if err = unix.Mkdirat(fd, part, 0700); err != nil && !errors.Is(err, unix.EEXIST) {
				unix.Close(fd)
				return -1, err
			}
			// Also sync on EEXIST: another caller may have created the directory
			// without yet syncing it, or an earlier sync may have failed.
			if err = syncParent(fd); err != nil {
				unix.Close(fd)
				return -1, fmt.Errorf("identity: sync parent directory: %w", err)
			}
		}
		next, e := unix.Openat(fd, part, unix.O_RDONLY|unix.O_DIRECTORY|unix.O_NOFOLLOW|unix.O_CLOEXEC, 0)
		unix.Close(fd)
		if e != nil {
			return -1, e
		}
		fd = next
		var st unix.Stat_t
		if e = unix.Fstat(fd, &st); e != nil {
			unix.Close(fd)
			return -1, e
		}
		uid := uint32(os.Geteuid())
		if i == len(parts)-1 {
			if st.Uid != uid || st.Mode&07777 != 0700 {
				unix.Close(fd)
				return -1, errors.New("identity: unsafe private directory")
			}
		} else if (st.Uid != 0 && st.Uid != uid) || (st.Mode&0022 != 0 && !(st.Uid == 0 && st.Mode&unix.S_ISVTX != 0)) {
			unix.Close(fd)
			return -1, errors.New("identity: unsafe ancestor directory")
		}
	}
	return fd, nil
}

func readIdentity(dirfd int, agentID string) (*Identity, error) {
	fd, err := unix.Openat(dirfd, "identity.json", unix.O_RDONLY|unix.O_NOFOLLOW|unix.O_NONBLOCK|unix.O_CLOEXEC, 0)
	if err != nil {
		return nil, err
	}
	file := os.NewFile(uintptr(fd), "identity.json")
	defer file.Close()
	var st unix.Stat_t
	if err = unix.Fstat(fd, &st); err != nil {
		return nil, err
	}
	if st.Mode&unix.S_IFMT != unix.S_IFREG || st.Mode&07777 != 0600 || st.Uid != uint32(os.Geteuid()) || st.Nlink != 1 || st.Size > 4096 {
		return nil, errors.New("identity: unsafe identity file")
	}
	decoder := json.NewDecoder(io.LimitReader(file, 4097))
	decoder.DisallowUnknownFields()
	var id Identity
	if err = decoder.Decode(&id); err != nil {
		return nil, err
	}
	var extra any
	if err = decoder.Decode(&extra); err != io.EOF {
		return nil, errors.New("identity: invalid trailing data")
	}
	if id.AgentID != agentID || len(id.PrivateKey) != ed25519.PrivateKeySize {
		return nil, errors.New("identity: identity mismatch or invalid key")
	}
	derived := ed25519.NewKeyFromSeed(id.PrivateKey.Seed())
	if !derived.Equal(id.PrivateKey) {
		return nil, errors.New("identity: invalid private key")
	}
	return &id, nil
}
