//go:build linux

package identity

import (
	"bytes"
	"crypto/ed25519"
	"errors"
	"os"
	"path/filepath"
	"sync"
	"testing"

	"golang.org/x/sys/unix"
)

func TestSyncParentDirectory(t *testing.T) {
	parent := t.TempDir()
	dir := filepath.Join(parent, "identity")
	failure := errors.New("sync failed")
	for _, wantFailure := range []bool{true, false} {
		calls := 0
		fd, err := openDirectoryWithSync(dir, func(fd int) error {
			calls++
			var got unix.Stat_t
			if err := unix.Fstat(fd, &got); err != nil {
				return err
			}
			var expected unix.Stat_t
			if err := unix.Stat(parent, &expected); err != nil {
				return err
			}
			if got.Dev != expected.Dev || got.Ino != expected.Ino {
				t.Error("sync did not receive parent directory")
			}
			if wantFailure {
				return failure
			}
			return unix.Fsync(fd)
		})
		if calls != 1 {
			t.Fatalf("sync calls: %d", calls)
		}
		if wantFailure {
			if fd != -1 || !errors.Is(err, failure) {
				t.Fatalf("sync failure not propagated: fd=%d err=%v", fd, err)
			}
			if _, err := os.Stat(filepath.Join(dir, "identity.json")); !os.IsNotExist(err) {
				t.Fatalf("identity unexpectedly exists: %v", err)
			}
		} else {
			if err != nil {
				t.Fatal(err)
			}
			unix.Close(fd)
		}
	}
}

func TestCreateReloadAndBinding(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "identity")
	first, err := LoadOrCreate(dir, "agent-1")
	if err != nil {
		t.Fatal(err)
	}
	again, err := LoadOrCreate(dir, "agent-1")
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(first.PrivateKey, again.PrivateKey) {
		t.Fatal("identity changed")
	}
	message := []byte("test")
	if !ed25519.Verify(first.PrivateKey.Public().(ed25519.PublicKey), message, ed25519.Sign(again.PrivateKey, message)) {
		t.Fatal("invalid signature")
	}
	if _, err := LoadOrCreate(dir, "agent-2"); err == nil {
		t.Fatal("accepted different agent ID")
	}
	for _, item := range []struct {
		path string
		mode os.FileMode
	}{{dir, 0700}, {filepath.Join(dir, "identity.json"), 0600}} {
		info, err := os.Stat(item.path)
		if err != nil {
			t.Fatal(err)
		}
		if info.Mode().Perm() != item.mode {
			t.Fatalf("%s: %o", item.path, info.Mode().Perm())
		}
	}
}

func TestConcurrentCreate(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "identity")
	const n = 24
	ids := make([]*Identity, n)
	errs := make([]error, n)
	var wg sync.WaitGroup
	for i := range ids {
		wg.Add(1)
		go func(i int) { defer wg.Done(); ids[i], errs[i] = LoadOrCreate(dir, "same") }(i)
	}
	wg.Wait()
	for i := range ids {
		if errs[i] != nil {
			t.Fatal(errs[i])
		}
		if !bytes.Equal(ids[0].PrivateKey, ids[i].PrivateKey) {
			t.Fatal("different identities created")
		}
	}
}

func TestRejectUnsafeState(t *testing.T) {
	for _, tc := range []struct {
		name  string
		setup func(string) error
	}{
		{"directory symlink", func(dir string) error {
			target := dir + "-target"
			if err := os.Mkdir(target, 0700); err != nil {
				return err
			}
			return os.Symlink(target, dir)
		}},
		{"unsafe directory", func(dir string) error {
			if err := os.Mkdir(dir, 0700); err != nil {
				return err
			}
			return os.Chmod(dir, 0755)
		}},
		{"file symlink", func(dir string) error {
			if err := os.Mkdir(dir, 0700); err != nil {
				return err
			}
			return os.Symlink("missing", filepath.Join(dir, "identity.json"))
		}},
		{"corrupt file", func(dir string) error {
			if err := os.Mkdir(dir, 0700); err != nil {
				return err
			}
			return os.WriteFile(filepath.Join(dir, "identity.json"), []byte("{}"), 0600)
		}},
		{"insecure file", func(dir string) error {
			if err := os.Mkdir(dir, 0700); err != nil {
				return err
			}
			p := filepath.Join(dir, "identity.json")
			if err := os.WriteFile(p, []byte("{}"), 0600); err != nil {
				return err
			}
			return os.Chmod(p, 0644)
		}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			dir := filepath.Join(t.TempDir(), "identity")
			if err := tc.setup(dir); err != nil {
				t.Fatal(err)
			}
			if _, err := LoadOrCreate(dir, "agent"); err == nil {
				t.Fatal("accepted unsafe state")
			}
		})
	}
}
