package fileserver

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/easyshell-org/easyshell/easyshell-agent/internal/config"
)

func newConfig(t *testing.T, root string, allowSystemRoot bool) *SecurityConfig {
	t.Helper()
	cfg := &config.FileConfig{
		Enabled:          true,
		Root:             root,
		ReadOnly:         false,
		AllowSystemRoot:  allowSystemRoot,
		MaxSizeMB:        10,
		MaxListEntries:   100,
	}
	sc, err := NewSecurityConfig(cfg)
	if err != nil {
		t.Fatalf("NewSecurityConfig: %v", err)
	}
	return sc
}

func TestNewSecurityConfig_RefusesSystemRootWithoutOptIn(t *testing.T) {
	_, err := NewSecurityConfig(&config.FileConfig{Root: "/", MaxSizeMB: 10})
	if err == nil {
		t.Fatal("expected the agent to refuse root=/ without an explicit opt-in")
	}

	if _, err := NewSecurityConfig(&config.FileConfig{Root: "/", AllowSystemRoot: true, MaxSizeMB: 10}); err != nil {
		t.Fatalf("explicit opt-in should be accepted: %v", err)
	}
}

func TestValidatePath_TraversalStaysInsideRoot(t *testing.T) {
	root := t.TempDir()
	sc := newConfig(t, root, false)

	// With a restricted root every request is re-rooted, so "../../.." cannot leave it.
	for _, path := range []string{
		"/../../etc/passwd",
		"/../../../etc/shadow",
		"/tmp/../../etc/passwd",
	} {
		resolved, err := sc.ValidatePath(path)
		if err != nil {
			continue // rejected outright, also fine
		}
		if !strings.HasPrefix(resolved, root) {
			t.Errorf("path %q escaped the root: %s", path, resolved)
		}
	}
}

func TestValidatePath_SystemRootStillBlocksSensitivePaths(t *testing.T) {
	if os.Geteuid() != 0 {
		t.Skip("needs root to read /etc/shadow")
	}
	sc := newConfig(t, "/", true)

	for _, path := range []string{
		"/etc/shadow",
		"/etc/ssh/ssh_host_rsa_key",
		"/root/.ssh/id_rsa",
		"/proc/self/environ",
		"/sys/kernel",
		"/dev/mem",
	} {
		if _, err := sc.ValidatePath(path); err == nil {
			t.Errorf("expected %q to be blocked even with root=/", path)
		}
	}
}

func TestValidatePath_SystemRootAllowsOrdinaryPaths(t *testing.T) {
	sc := newConfig(t, "/", true)
	resolved, err := sc.ValidatePath("/etc/hostname")
	if err != nil {
		t.Fatalf("expected an ordinary path to be allowed: %v", err)
	}
	if resolved != "/etc/hostname" {
		t.Fatalf("unexpected resolved path: %s", resolved)
	}
}

func TestValidatePath_SymlinkEscape(t *testing.T) {
	root := t.TempDir()
	sc := newConfig(t, root, false)

	outside := t.TempDir()
	secret := filepath.Join(outside, "secret.txt")
	if err := os.WriteFile(secret, []byte("top secret"), 0o600); err != nil {
		t.Fatal(err)
	}

	link := filepath.Join(root, "escape")
	if err := os.Symlink(outside, link); err != nil {
		t.Skipf("symlinks unavailable: %v", err)
	}

	// Direct path through the symlinked directory must be rejected.
	if _, err := sc.ValidatePath("/escape/secret.txt"); err == nil {
		t.Error("expected symlinked directory access to be rejected")
	}

	// The literal symlink itself must be rejected as well.
	if _, err := sc.ValidatePath("/escape"); err == nil {
		t.Error("expected the symlink itself to be rejected")
	}
}

func TestValidatePath_BlacklistAppliesToResolvedPath(t *testing.T) {
	root := t.TempDir()
	sc := newConfig(t, root, false)

	// A symlink pointing straight at /etc/shadow: the literal path is inside the root and
	// not blacklisted, only the resolved path is.
	link := filepath.Join(root, "shadow-link")
	if err := os.Symlink("/etc/shadow", link); err != nil {
		t.Skipf("symlinks unavailable: %v", err)
	}

	_, err := sc.ValidatePath("/shadow-link")
	if err == nil {
		t.Fatal("expected /etc/shadow to be blocked through a symlink")
	}
	if !strings.Contains(err.Error(), "access denied") && !strings.Contains(err.Error(), "symlink") {
		t.Fatalf("unexpected error: %v", err)
	}
}

func TestValidatePath_PseudoFilesystems(t *testing.T) {
	// Only reachable when the agent is explicitly configured with a system root.
	sc := newConfig(t, "/", true)

	for _, path := range []string{"/proc/self/environ", "/sys/kernel", "/dev/mem"} {
		if _, err := sc.ValidatePath(path); err == nil {
			t.Errorf("expected %q to be blocked", path)
		}
	}
}

func TestValidatePath_InsideRoot(t *testing.T) {
	root := t.TempDir()
	sc := newConfig(t, root, false)

	if err := os.WriteFile(filepath.Join(root, "hello.txt"), []byte("hi"), 0o600); err != nil {
		t.Fatal(err)
	}
	resolved, err := sc.ValidatePath("/hello.txt")
	if err != nil {
		t.Fatalf("expected a normal file to be allowed: %v", err)
	}
	if resolved != filepath.Join(root, "hello.txt") {
		t.Fatalf("unexpected resolved path: %s", resolved)
	}
}

func TestValidateWriteTarget_SymlinkedParent(t *testing.T) {
	root := t.TempDir()
	sc := newConfig(t, root, false)

	outside := t.TempDir()
	link := filepath.Join(root, "out")
	if err := os.Symlink(outside, link); err != nil {
		t.Skipf("symlinks unavailable: %v", err)
	}

	// Uploading a brand new file through a symlinked directory must be refused even though
	// the destination itself does not exist yet.
	if _, err := sc.ValidateWriteTarget("/out/newfile.txt"); err == nil {
		t.Fatal("expected an upload through a symlinked parent to be rejected")
	}
}

func TestValidateWriteTarget_NonExistentInsideRoot(t *testing.T) {
	root := t.TempDir()
	sc := newConfig(t, root, false)

	resolved, err := sc.ValidateWriteTarget("/sub/dir/file.txt")
	if err != nil {
		t.Fatalf("expected a new path inside the root to be allowed: %v", err)
	}
	if !strings.HasPrefix(resolved, root) {
		t.Fatalf("resolved path escaped the root: %s", resolved)
	}
}

func TestValidateWrite_ReadOnly(t *testing.T) {
	root := t.TempDir()
	cfg := &config.FileConfig{Enabled: true, Root: root, ReadOnly: true, MaxSizeMB: 10}
	sc, err := NewSecurityConfig(cfg)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := sc.ValidateWrite("/anything"); err == nil {
		t.Fatal("expected writes to be rejected in read-only mode")
	}
}

func TestValidateFileSize(t *testing.T) {
	root := t.TempDir()
	sc := newConfig(t, root, false)

	if err := sc.ValidateFileSize(1024); err != nil {
		t.Fatalf("small file should be allowed: %v", err)
	}
	if err := sc.ValidateFileSize(int64(sc.MaxFileSize) + 1); err == nil {
		t.Fatal("expected oversized upload to be rejected")
	}
}

func TestIsProtectedPath(t *testing.T) {
	protected := []string{"/", "/etc", "/etc/nginx", "/usr/local/bin", "/var/lib", "/var/lib/easyshell"}
	for _, path := range protected {
		if !isProtectedPath(path, "/opt/easyshell-data") {
			t.Errorf("expected %q to be protected", path)
		}
	}

	unprotected := []string{"/etc2", "/etcy/nginx", "/opt/easyshell-data/file.txt"}
	for _, path := range unprotected {
		if isProtectedPath(path, "/opt/easyshell-data") {
			t.Errorf("expected %q not to be protected", path)
		}
	}

	// With a system root the protected directories themselves are off limits, but their
	// contents stay manageable (the file manager keeps working across the filesystem).
	if !isProtectedPath("/etc", "/") {
		t.Error("expected /etc itself to be protected when the root is /")
	}
	if isProtectedPath("/etc/nginx/nginx.conf", "/") {
		t.Error("expected paths inside /etc to stay manageable when the root is /")
	}
}
