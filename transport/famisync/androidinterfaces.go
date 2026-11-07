// Package famisync — Android interface-list fallback.
//
// Android (app UIDs / SELinux untrusted_app domain) denies the NETLINK_ROUTE
// socket and /proc/net, so Go's stdlib net.Interfaces() always fails with
// "route ip+net: netlinkrib: permission denied" and netmon.New() aborts, which
// kills tsnet.Server.Start(). Upstream ships feature/androidbin for exactly
// this, but its register.go is gated to "android && !cgo"; gomobile bind builds
// "android && cgo", so that hook is NOT installed and the official app instead
// relies on a Java-backed getter via netmon.RegisterInterfaceGetter.
//
// A gomobile tsnet app has no Java thread to call RegisterInterfaceGetter, so
// we install the equivalent here: register a getter that reports a synthetic
// interface whose addresses are discovered by asking the kernel to route an
// outbound UDP socket (dialing sends no packets and IS permitted under the app
// sandbox). This mirrors feature/androidbin's fallbackInterfaces. It's inert on
// non-Android in spirit, but this file only builds for Android.
package famisync

import (
	"net"
	"net/netip"
	"time"

	"tailscale.com/net/netmon"

	// androidbin carries the /proc-based default-route + home-router
	// detection and the Android CA/DNS shims; importing it for its init side
	// effects is harmless even though its interface hook is build-tag-gated off
	// for android+cgo. netmon itself selects interfaces_android.go by GOOS.
	_ "tailscale.com/feature/androidbin"
)

func init() {
	// Install as the primary system-interface getter (higher priority than the
	// last-resort HookInterfacesFallback): netInterfaces() consults this first
	// and never even tries net.Interfaces() when it's set.
	netmon.RegisterInterfaceGetter(syntheticInterfaces)
}

// syntheticInterfaces returns a single made-up interface carrying the process's
// outbound IPv4 and IPv6 source addresses. Sufficient for magicsock to discover
// local endpoints; it cannot see the full multi-interface picture, which the
// app sandbox makes impossible without a Java ConnectivityManager bridge.
func syntheticInterfaces() ([]netmon.Interface, error) {
	var addrs []net.Addr
	if ip, ok := outboundIP("udp4", "8.8.8.8:53"); ok {
		addrs = append(addrs, &net.IPNet{IP: ip.AsSlice(), Mask: net.CIDRMask(32, 32)})
	}
	if ip, ok := outboundIP("udp6", "[2001:4860:4860::8888]:53"); ok {
		addrs = append(addrs, &net.IPNet{IP: ip.AsSlice(), Mask: net.CIDRMask(128, 128)})
	}
	if len(addrs) == 0 {
		// No outbound route (e.g. airplane mode). Return an empty list rather
		// than an error so start-up can proceed; magicsock will retry as the
		// network comes up.
		return nil, nil
	}
	return []netmon.Interface{{
		Interface: &net.Interface{
			MTU:   1500,
			Name:  "android",
			Flags: net.FlagUp | net.FlagRunning | net.FlagMulticast,
		},
		AltAddrs: addrs,
	}}, nil
}

// outboundIP reports the local source address the kernel picks for an outbound
// socket to addr. Dialing UDP performs no I/O, so this works under Android's
// app sandbox where interface enumeration does not.
func outboundIP(network, addr string) (netip.Addr, bool) {
	d := net.Dialer{Timeout: 2 * time.Second}
	c, err := d.Dial(network, addr)
	if err != nil {
		return netip.Addr{}, false
	}
	defer c.Close()
	ua, ok := c.LocalAddr().(*net.UDPAddr)
	if !ok {
		return netip.Addr{}, false
	}
	ip, ok := netip.AddrFromSlice(ua.IP)
	if !ok {
		return netip.Addr{}, false
	}
	ip = ip.Unmap()
	if ip.IsLoopback() || ip.IsUnspecified() {
		return netip.Addr{}, false
	}
	return ip, true
}
