package identity

import (
	"os"
	"path/filepath"
	"testing"
)

func TestValidateDirectorySeparation(t *testing.T) {
	base := t.TempDir()
	id := filepath.Join(base, "identity")
	root := filepath.Join(base, "files")
	sibling := filepath.Join(base, "identity-sibling")
	for _, dir := range []string{id, root, sibling, filepath.Join(id, "child")} {
		if err := os.MkdirAll(dir, 0700); err != nil {
			t.Fatal(err)
		}
	}
	link := filepath.Join(base, "link")
	if err := os.Symlink(id, link); err != nil {
		t.Fatal(err)
	}
	dangling := filepath.Join(base, "dangling")
	if err := os.Symlink(filepath.Join(base, "missing"), dangling); err != nil {
		t.Fatal(err)
	}
	file := filepath.Join(base, "file")
	if err := os.WriteFile(file, nil, 0600); err != nil {
		t.Fatal(err)
	}
	tests := []struct {
		name, identity, root string
		ok                   bool
	}{
		{"separate", id, root, true},
		{"prefix sibling", id, sibling, true},
		{"same", id, id, false},
		{"identity ancestor", id, filepath.Join(id, "child"), false},
		{"root ancestor", filepath.Join(id, "child"), id, false},
		{"symlink same", link, id, false},
		{"symlink ancestor", filepath.Join(link, "child"), id, false},
		{"symlink separate", link, root, true},
		{"missing identity", filepath.Join(base, "missing"), root, false},
		{"missing root", id, filepath.Join(base, "missing"), false},
		{"dangling symlink", dangling, root, false},
		{"file", file, root, false},
		{"relative", "identity", root, false},
		{"empty", "", root, false},
		{"identity filesystem root", string(filepath.Separator), root, false},
		{"file filesystem root", id, string(filepath.Separator), false},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			err := ValidateDirectorySeparation(tt.identity, tt.root)
			if (err == nil) != tt.ok {
				t.Fatalf("ValidateDirectorySeparation() error = %v, want success %v", err, tt.ok)
			}
		})
	}
}
