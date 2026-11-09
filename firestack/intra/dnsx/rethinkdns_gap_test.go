package dnsx

import (
	"os"
	"path/filepath"
	"testing"
)

// list ids may have gaps (disabled lists keep their position); loading must not panic
func TestLoadFiletagWithGaps(t *testing.T) {
	p := filepath.Join(t.TempDir(), "filetag.json")
	data := `{
	  "A": {"value": 0, "vname": "one", "subg": "s", "group": "g"},
	  "B": {"value": 3, "vname": "two", "subg": "s", "group": "g"}
	}`
	if err := os.WriteFile(p, []byte(data), 0o600); err != nil {
		t.Fatal(err)
	}
	flags, tags, err := load(p)
	if err != nil {
		t.Fatal(err)
	}
	if len(flags) != 4 {
		t.Fatalf("len(flags)=%d, want 4", len(flags))
	}
	if flags[1] != "" || flags[2] != "" {
		t.Fatalf("gap ids must be empty, got %q %q", flags[1], flags[2])
	}
	if flags[3] != "s:two" {
		t.Fatalf("flags[3]=%q, want s:two", flags[3])
	}
	if tags["B"] != "s:two" {
		t.Fatalf("tags[B]=%q, want s:two", tags["B"])
	}
}
