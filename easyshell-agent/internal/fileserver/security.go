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

func NewSecurityConfig(cfg *config.FileConfig) *SecurityConfig {
	sc := &SecurityConfig{
		Enabled:        cfg.Enabled,
		RootPath:       filepath.Clean(cfg.Root),
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

	return sc
}

func (sc *SecurityConfig) ValidatePath(requestedPath string) (string, error) {
	cleaned := filepath.Clean(requestedPath)
	absPath := filepath.Clean(filepath.Join(sc.RootPath, cleaned))

	rootClean := filepath.Clean(sc.RootPath)
	// Special case: when root is "/", all absolute paths are valid under root
	if rootClean == "/" {
		if !filepath.IsAbs(absPath) {
			return "", ErrPathTraversal
		}
	} else {
		if !strings.HasPrefix(absPath, rootClean+string(os.PathSeparator)) && absPath != rootClean {
			return "", ErrPathTraversal
		}
	}

	if _, err := os.Lstat(absPath); err == nil {
		evalPath, err := filepath.EvalSymlinks(absPath)
		if err == nil {
			evalClean := filepath.Clean(evalPath)
			if rootClean != "/" {
				if !strings.HasPrefix(evalClean, rootClean+string(os.PathSeparator)) && evalClean != rootClean {
					return "", ErrSymlinkTraversal
				}
			}
		}
	}

	for _, re := range sc.BlacklistRegexp {
		if re.MatchString(absPath) {
			return "", ErrAccessDenied
		}
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

func (sc *SecurityConfig) ValidateWrite(requestedPath string) (string, error) {
	if sc.ReadOnly {
		return "", ErrReadOnly
	}
	return sc.ValidatePath(requestedPath)
}

func (sc *SecurityConfig) ValidateFileSize(size int64) error {
	if size > sc.MaxFileSize {
		return fmt.Errorf("%w: %d > %d", ErrFileTooLarge, size, sc.MaxFileSize)
	}
	return nil
}
