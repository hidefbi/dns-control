package dnsx

import (
	"testing"

	"github.com/celzero/firestack/intra/settings"
)

// UnproxiablePid must never return the base proxy under global lockdown.
func TestUnproxiablePid(t *testing.T) {
	defer settings.GlobalLockdown.Store(false)
	defer settings.Loopingback.Store(false)
	defer SetLockdownProxy("")

	tests := []struct {
		name     string
		lockdown bool
		loop     bool
		lpid     string
		want     string
	}{
		{"default", false, false, "", NetBaseProxy},
		{"loopback", false, true, "", NetExitProxy},
		{"lockdown", true, false, "", NetNoProxy},
		{"lockdown+loopback", true, true, "", NetNoProxy},
		{"lockdown+proxy", true, false, "S5", "S5"},
		{"lockdown+proxy+loopback", true, true, "wg1", "wg1"},
		{"proxy without lockdown", false, false, "S5", NetBaseProxy},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			settings.GlobalLockdown.Store(tc.lockdown)
			settings.Loopingback.Store(tc.loop)
			SetLockdownProxy(tc.lpid)
			if got := UnproxiablePid(); got != tc.want {
				t.Fatalf("UnproxiablePid() = %q, want %q", got, tc.want)
			}
		})
	}
}
