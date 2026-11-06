package fileserver

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"

	"github.com/easyshell-org/easyshell/easyshell-agent/internal/config"
)

var (
	ErrPathTraversal    = errors.New("path traversal detected")
	ErrAccessDenied     = errors.New("access denied by security rules")
	ErrSymlinkTraversal = errors.New("symlink traversal detected")
	ErrReadOnly         = errors.New("file system is read-only")
	ErrFileTooLarge     = errors.New("file exceeds maximum size")
	ErrUnsafePath       = errors.New("unsafe path")
)

var DefaultBlacklist = []string{
	`^/etc/shadow$`,
	`^/etc/gshadow$`,
	`^/etc/sudoers$`,
	`^/etc/sudoers\.d/`,
	`^/etc/ssh/ssh_host_`,
	`/\.ssh/id_`,
	`/\.ssh/authorized_keys$`,
	`/\.gnupg/`,
	`/\.aws/credentials$`,
	`/\.aws/config$`,
	`/\.kube/config$`,
	`/\.docker/config\.json$`,
	`/\.[a-z]*_history$`,
	`/\.env$`,
	`/\.env\.`,
	`/secrets?\.(ya?ml|json|toml|properties|conf)$`,
	`/credentials?\.(ya?ml|json|toml|properties|conf)$`,
	`/\.git/config$`,
	`/\.gitconfig$`,
	`/\.netrc$`,
	`/\.pgpass$`,
	`/private[_-]?key`,
	`\.pem$`,
	`\.key$`,
	// Pseudo filesystems: /proc/self/environ leaks the agent's environment (including
	// secrets) and device nodes can hang the reader.
	`^/proc/`,
	`^/sys/`,
	`^/dev/`,
}

// systemRoots are directories that must never be the file root without an explicit opt-in.
var systemRoots = map[string]bool{
	"/": true, "/etc": true, "/root": true, "/home": true,
	"/var": true, "/usr": true, "/opt": true, "/boot": true,
}

type SecurityConfig struct {
	Enabled          bool
	RootPath         string
	ReadOnly         bool
	MaxFileSize      int64
	MaxListEntries   int
	BlacklistRegexp  []*regexp.Regexp
	WhitelistPaths   []string
}

func NewSecurityConfig(cfg *config.FileConfig) (*SecurityConfig, error) {
	root := filepath.Clean(cfg.Root)
	if root == "" {
		root = config.DefaultFileRoot
	}
	if systemRoots[root] && !cfg.AllowSystemRoot {
		return nil, fmt.Errorf("%w: refusing to use %s as the file root without agent.file.allow-system-root: true", ErrUnsafePath, root)
	}

	sc := &SecurityConfig{
		Enabled:        cfg.Enabled,
		RootPath:       root,
		ReadOnly:       cfg.ReadOnly,
		MaxFileSize:    int64(cfg.MaxSizeMB) * 1024 * 1024,
		MaxListEntries: cfg.MaxListEntries,
		WhitelistPaths: cfg.Whitelist,
	}

	for _, pattern := range DefaultBlacklist {
		if re, err := regexp.Compile(pattern); err == nil {
			sc.BlacklistRegexp = append(sc.BlacklistRegexp, re)
		}
	}
	for _, pattern := range cfg.Blacklist {
		if re, err := regexp.Compile(pattern); err == nil {
			sc.BlacklistRegexp = append(sc.BlacklistRegexp, re)
		}
	}

	return sc, nil
}

// isBlacklisted runs the blacklist against a path.
func (sc *SecurityConfig) isBlacklisted(path string) bool {
	for _, re := range sc.BlacklistRegexp {
		if re.MatchString(path) {
			return true
		}
	}
	return false
}

func (sc *SecurityConfig) withinRoot(path string) bool {
	root := sc.RootPath
	if root == "/" {
		return filepath.IsAbs(path)
	}
	return path == root || strings.HasPrefix(path, root+string(os.PathSeparator))
}

// resolveExisting walks up to the closest existing ancestor and returns its fully
// resolved (symlink-free) path plus the remaining components. Used for write targets
// that do not exist yet, where EvalSymlinks would otherwise fail and skip the checks.
func resolveExisting(path string) (string, []string, error) {
	remaining := []string{}
	current := path
	for {
		resolved, err := filepath.EvalSymlinks(current)
		if err == nil {
			return filepath.Clean(resolved), remaining, nil
		}
		if !os.IsNotExist(err) {
			return "", nil, err
		}
		parent := filepath.Dir(current)
		if parent == current {
			return "", nil, fmt.Errorf("no existing ancestor for %s", path)
		}
		remaining = append([]string{filepath.Base(current)}, remaining...)
		current = parent
	}
}

// checkResolved applies every rule that must hold for the *real* location of a path:
// containment in the file root and the blacklist (both of which were previously only
// checked against the literal, pre-symlink path).
func (sc *SecurityConfig) checkResolved(resolved string) error {
	if !sc.withinRoot(resolved) {
		return ErrSymlinkTraversal
	}
	if sc.isBlacklisted(resolved) {
		return ErrAccessDenied
	}
	return nil
}

func (sc *SecurityConfig) ValidatePath(requestedPath string) (string, error) {
	if requestedPath == "" {
		return "", ErrPathTraversal
	}
	if strings.ContainsRune(requestedPath, 0) {
		return "", ErrPathTraversal
	}

	cleaned := filepath.Clean(requestedPath)
	absPath := filepath.Clean(filepath.Join(sc.RootPath, cleaned))

	if !sc.withinRoot(absPath) {
		return "", ErrPathTraversal
	}

	// Reject any component that is itself a symlink: a symlinked directory would let a
	// request escape the root even when the final component is fine.
	if err := sc.rejectSymlinkComponents(absPath); err != nil {
		return "", err
	}

	if info, err := os.Lstat(absPath); err == nil {
		if info.Mode()&os.ModeSymlink != 0 {
			return "", ErrSymlinkTraversal
		}

		evalPath, err := filepath.EvalSymlinks(absPath)
		if err != nil {
			return "", ErrSymlinkTraversal
		}
		if err := sc.checkResolved(filepath.Clean(evalPath)); err != nil {
			return "", err
		}
	} else if !os.IsNotExist(err) {
		return "", err
	}

	// The blacklist is checked against the literal path as well, so rules anchored at "^"
	// keep working for paths that do not exist yet.
	if sc.isBlacklisted(absPath) {
		return "", ErrAccessDenied
	}

	if len(sc.WhitelistPaths) > 0 {
		allowed := false
		for _, w := range sc.WhitelistPaths {
			wClean := filepath.Clean(w)
			if strings.HasPrefix(absPath, wClean+string(os.PathSeparator)) || absPath == wClean {
				allowed = true
				break
			}
		}
		if !allowed {
			return "", ErrAccessDenied
		}
	}

	return absPath, nil
}

// rejectSymlinkComponents walks every component of the path and refuses the request when
// any of them is a symlink (this is what made "/tmp/link -> /etc" style bypasses work).
func (sc *SecurityConfig) rejectSymlinkComponents(absPath string) error {
	current := sc.RootPath
	if current == "/" {
		current = ""
	}
	for _, part := range strings.Split(strings.TrimPrefix(absPath, sc.RootPath), string(os.PathSeparator)) {
		if part == "" || part == "." {
			continue
		}
		current = current + string(os.PathSeparator) + part
		info, err := os.Lstat(current)
		if err != nil {
			if os.IsNotExist(err) {
				return nil // nothing below here exists yet
			}
			return err
		}
		if info.Mode()&os.ModeSymlink != 0 {
			return ErrSymlinkTraversal
		}
	}
	return nil
}

// ValidateWrite validates a destination path for a write operation. When the target does
// not exist, the closest existing ancestor is resolved and validated instead, which closes
// the "upload through a symlinked parent directory" hole.
func (sc *SecurityConfig) ValidateWrite(requestedPath string) (string, error) {
	if sc.ReadOnly {
		return "", ErrReadOnly
	}
	return sc.ValidatePath(requestedPath)
}

// ValidateWriteTarget validates the destination of an upload/rename, including the case
// where the final component does not exist yet.
func (sc *SecurityConfig) ValidateWriteTarget(requestedPath string) (string, error) {
	if sc.ReadOnly {
		return "", ErrReadOnly
	}
	if requestedPath == "" {
		return "", ErrPathTraversal
	}

	cleaned := filepath.Clean(requestedPath)
	absPath := filepath.Clean(filepath.Join(sc.RootPath, cleaned))
	if !sc.withinRoot(absPath) {
		return "", ErrPathTraversal
	}

	resolved, remaining, err := resolveExisting(absPath)
	if err != nil {
		return "", err
	}
	if err := sc.checkResolved(resolved); err != nil {
		return "", err
	}
	// Any not-yet-existing component must also pass the blacklist once joined.
	if len(remaining) > 0 {
		candidate := filepath.Join(append([]string{resolved}, remaining...)...)
		if err := sc.checkResolved(candidate); err != nil {
			return "", err
		}
		if err := sc.rejectSymlinkComponents(candidate); err != nil {
			return "", err
		}
		return candidate, nil
	}
	return resolved, nil
}

func (sc *SecurityConfig) ValidateFileSize(size int64) error {
	if size > sc.MaxFileSize {
		return fmt.Errorf("%w: %d > %d", ErrFileTooLarge, size, sc.MaxFileSize)
	}
	return nil
}
