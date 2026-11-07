package famisync

import (
	"context"
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"testing"
	"time"
)

// A failed native start must return with its server closed, not leave work that
// can publish a node after Kotlin has reported failure or switched configuration.
func TestFailedNativeStartLeavesNoNode(t *testing.T) {
	control := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "isolated test control plane unavailable", http.StatusServiceUnavailable)
	}))
	defer control.Close()
	defer Stop()
	for attempt := 0; attempt < 2; attempt++ {
		started := time.Now()
		_, err := StartWithTimeout(t.TempDir(), "lifecycle-test", "", control.URL, 50)
		if err == nil {
			t.Fatal("unavailable isolated control plane must fail")
		}
		if time.Since(started) > 5*time.Second {
			t.Fatal("native startup exceeded test deadline budget")
		}
		if got := Status(); got != "down" {
			t.Fatalf("failed start left a node: %s", got)
		}
	}
}

// When the peer map has not arrived, there must be no attempt on any dialer.
func TestMissingPeerNeverDialsAndRespectsDeadline(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer cancel()
	calls := 0
	_, err := dialTailnetOnly(ctx, "tcp", "100.65.248.62:18080",
		func(context.Context, string, string) (netip.AddrPort, bool, error) {
			return netip.MustParseAddrPort("100.65.248.62:18080"), false, nil
		},
		func(context.Context, netip.AddrPort) (net.Conn, error) {
			calls++
			return nil, errors.New("must not dial")
		})
	if !errors.Is(err, context.DeadlineExceeded) || calls != 0 {
		t.Fatalf("err=%v calls=%d", err, calls)
	}
}

func TestReadyPeerUsesResolvedIPOnNetstackOnly(t *testing.T) {
	plans, dials := 0, 0
	want := netip.MustParseAddrPort("100.65.248.62:18080")
	marker := errors.New("netstack result")
	_, err := dialTailnetOnly(context.Background(), "tcp", "home.example:18080",
		func(context.Context, string, string) (netip.AddrPort, bool, error) {
			plans++
			return want, plans > 1, nil
		},
		func(_ context.Context, got netip.AddrPort) (net.Conn, error) {
			dials++
			if got != want {
				t.Fatalf("address changed: %v", got)
			}
			return nil, marker
		})
	if !errors.Is(err, marker) || dials != 1 || plans != 2 {
		t.Fatalf("err=%v dials=%d plans=%d", err, dials, plans)
	}
}
