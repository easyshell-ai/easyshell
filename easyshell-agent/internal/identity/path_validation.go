package identity

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// ValidateDirectorySeparation verifies that two existing directories do not
// overlap, resolving symlinks before comparing them. Both paths must be
// absolute; missing, inaccessible, or otherwise unverifiable paths are rejected.
// This is a point-in-time check: callers must also prevent subsequent changes
// to either path or its ancestors before relying on it.
func ValidateDirectorySeparation(identityDir, fileRoot string) error {
	resolve := func(path string) (string, error) {
		if path == "" || !filepath.IsAbs(path) || filepath.Clean(path) == string(filepath.Separator) {
			return "", errors.New("invalid or root directory")
		}
		resolved, err := filepath.EvalSymlinks(path)
		if err != nil {
			return "", err
		}
		info, err := os.Stat(resolved)
		if err != nil {
			return "", err
		}
		if !info.IsDir() || filepath.Dir(resolved) == resolved {
			return "", errors.New("not a non-root directory")
		}
		return resolved, nil
	}
	identity, err := resolve(identityDir)
	if err != nil {
		return fmt.Errorf("identity: cannot validate identity directory: %w", err)
	}
	root, err := resolve(fileRoot)
	if err != nil {
		return fmt.Errorf("identity: cannot validate file root: %w", err)
	}
	contains := func(parent, child string) (bool, error) {
		rel, err := filepath.Rel(parent, child)
		if err != nil {
			return false, err
		}
		return rel == "." || (rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator))), nil
	}
	inside, err := contains(identity, root)
	if err != nil {
		return fmt.Errorf("identity: cannot compare directories: %w", err)
	}
	reverse, err := contains(root, identity)
	if err != nil {
		return fmt.Errorf("identity: cannot compare directories: %w", err)
	}
	if inside || reverse {
		return errors.New("identity: identity directory and file root overlap")
	}
	return nil
}
