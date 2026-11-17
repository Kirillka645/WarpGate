package warpcore

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"regexp"
	"strings"
	"testing"
)

const (
	testPrivKey = "aGVsbG8gd29ybGQgdGhpcyBpcyAzMiBieXRlcyEhIQ=="
	testPubKey  = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="
	testIPv6    = "2606:4700:110:8a1b:c4b2:1a2b:3c4d:5e6f"
)

func TestWarpGenAmneziaWGBasics(t *testing.T) {
	cfg := WarpGenAmneziaWG(testPrivKey, testPubKey, testIPv6, "162.159.192.1", 4500, false)

	for _, want := range []string{
		"[Interface]",
		"PrivateKey = " + testPrivKey,
		"Address = " + warpGenClientIPv4,
		"MTU = 1280",
		"S1 = 0",
		"S2 = 0",
		"Jc = 4",
		"Jmin = 40",
		"Jmax = 70",
		"H1 = 1",
		"H2 = 2",
		"H3 = 3",
		"H4 = 4",
		"[Peer]",
		"PublicKey = " + testPubKey,
		"AllowedIPs = 0.0.0.0/0",
		"Endpoint = 162.159.192.1:4500",
		"PersistentKeepalive = 25",
	} {
		if !strings.Contains(cfg, want) {
			t.Errorf("config is missing %q\n---\n%s", want, cfg)
		}
	}

	// Exactly one I1 signature, and it must be one of the vendored masks.
	i1 := regexp.MustCompile(`(?m)^I1 = <b 0x[0-9a-f]+>$`).FindAllString(cfg, -1)
	if len(i1) != 1 {
		t.Fatalf("expected exactly one I1 mask, got %d", len(i1))
	}
	if i1[0] != warpGenI1Mask1 && i1[0] != warpGenI1Mask2 {
		t.Errorf("unexpected I1 mask: %q", i1[0])
	}

	// Section order: the interface block must come before the peer block.
	if strings.Index(cfg, "[Interface]") > strings.Index(cfg, "[Peer]") {
		t.Error("[Interface] must precede [Peer]")
	}
}

func TestWarpGenAmneziaWGDNSResolvers(t *testing.T) {
	cfg := WarpGenAmneziaWG(testPrivKey, testPubKey, testIPv6, "162.159.192.1", 2408, false)

	if !strings.Contains(cfg, "DNS = "+strings.Join(warpGenDNSResolvers, ", ")) {
		t.Errorf("config does not carry the geoblock-bypass resolvers:\n%s", cfg)
	}
	for _, resolver := range warpGenDNSResolvers {
		if !strings.Contains(cfg, resolver) {
			t.Errorf("resolver %s missing from the config", resolver)
		}
	}
}

func TestWarpGenAmneziaWGDualStack(t *testing.T) {
	cfg := WarpGenAmneziaWG(testPrivKey, testPubKey, testIPv6, "162.159.193.1", 500, true)

	if !strings.Contains(cfg, "Address = "+warpGenClientIPv4+", "+testIPv6) {
		t.Errorf("dual-stack address is wrong:\n%s", cfg)
	}
	if !strings.Contains(cfg, "AllowedIPs = 0.0.0.0/0, ::/0") {
		t.Errorf("dual-stack AllowedIPs is wrong:\n%s", cfg)
	}
}

func TestWarpGenAmneziaWGEmptyPeerKeyFallsBack(t *testing.T) {
	cfg := WarpGenAmneziaWG(testPrivKey, "", testIPv6, "162.159.195.1", 1701, false)

	if !strings.Contains(cfg, "PublicKey = "+warpGenPeerPublicKey) {
		t.Errorf("expected the fallback peer key:\n%s", cfg)
	}
}

func TestWarpGenClashSubstitutions(t *testing.T) {
	cfg := WarpGenClash(testPrivKey, testIPv6, "162.159.192.1", 4500, false)

	if strings.Contains(cfg, "PRIVATE_KEY") {
		t.Error("the PRIVATE_KEY placeholder was not replaced")
	}
	if strings.Contains(cfg, "WARP_ENDPOINT") {
		t.Error("the WARP_ENDPOINT placeholder was not replaced")
	}
	if !strings.Contains(cfg, "private-key: "+testPrivKey) {
		t.Errorf("private key was not injected:\n%s", cfg)
	}
	if !strings.Contains(cfg, "server: 162.159.192.1") {
		t.Errorf("server was not injected:\n%s", cfg)
	}
	if !strings.Contains(cfg, "port: 4500") {
		t.Errorf("port was not injected:\n%s", cfg)
	}
	// The three mask nodes all share the substituted endpoint.
	if got := strings.Count(cfg, "server: 162.159.192.1"); got != 3 {
		t.Errorf("expected 3 proxy nodes with the endpoint, got %d", got)
	}
	// The endpoint pin must point at the WARP server, outside of the tunnel.
	if !strings.Contains(cfg, "IP-CIDR,162.159.192.1/32,DIRECT,no-resolve") {
		t.Errorf("endpoint pin rule is wrong:\n%s", cfg)
	}
	// v4-only by default.
	if !strings.Contains(cfg, "allowed-ips: ['0.0.0.0/0']") {
		t.Errorf("default allowed-ips should stay v4-only:\n%s", cfg)
	}
}

func TestWarpGenClashDualStack(t *testing.T) {
	cfg := WarpGenClash(testPrivKey, testIPv6, "162.159.193.1", 2408, true)

	if !strings.Contains(cfg, "ipv6: "+testIPv6) {
		t.Errorf("client IPv6 was not injected:\n%s", cfg)
	}
	if !strings.Contains(cfg, "allowed-ips: ['0.0.0.0/0', '::/0']") {
		t.Errorf("allowed-ips was not extended to IPv6:\n%s", cfg)
	}
	if strings.Contains(cfg, "ipv6: false") {
		t.Errorf("ipv6 flags were not flipped:\n%s", cfg)
	}
	if strings.Contains(cfg, "IP-CIDR,::/0,REJECT-DROP") {
		t.Errorf("the IPv6 reject rule must be removed in dual-stack mode:\n%s", cfg)
	}
	if !strings.Contains(cfg, "ip: "+warpGenClientIPv4) {
		t.Errorf("client IPv4 address is missing:\n%s", cfg)
	}
}

func TestWarpGenClashHostnameEndpointDropsPin(t *testing.T) {
	cfg := WarpGenClash(testPrivKey, testIPv6, "engage.cloudflareclient.com", 4500, false)

	if strings.Contains(cfg, "WARP_ENDPOINT") {
		t.Error("the endpoint pin must be dropped for hostname endpoints")
	}
	if !strings.Contains(cfg, "server: engage.cloudflareclient.com") {
		t.Errorf("server was not injected:\n%s", cfg)
	}
}

func TestWarpGenRandomEndpointStaysInPool(t *testing.T) {
	for i := 0; i < 200; i++ {
		ep := WarpGenRandomEndpoint()
		if ep == nil {
			t.Fatal("random endpoint must not be nil")
		}
		host, port := ep.Host, ep.Port
		if !isIPv4(host) {
			t.Fatalf("random endpoint %q is not an IP address", host)
		}
		found := false
		for _, h := range jsonHosts(t) {
			if h == host {
				found = true
				break
			}
		}
		if !found {
			t.Fatalf("random endpoint %q is not part of the pool", host)
		}
		found = false
		for _, p := range jsonPorts(t) {
			if p == port {
				found = true
				break
			}
		}
		if !found {
			t.Fatalf("random port %d is not part of the pool", port)
		}
	}
}

func TestWarpGenPoolContents(t *testing.T) {
	hosts := jsonHosts(t)
	ports := jsonPorts(t)

	if len(hosts) == 0 {
		t.Error("the endpoint pool must not be empty")
	}
	if len(ports) == 0 {
		t.Error("the port pool must not be empty")
	}
	for _, h := range hosts {
		if strings.TrimSpace(h) == "" {
			t.Error("the endpoint pool contains an empty entry")
		}
	}
	for _, p := range ports {
		if p <= 0 || p > 65535 {
			t.Errorf("invalid port in the pool: %d", p)
		}
	}
}

// jsonHosts decodes the JSON payload of the endpoint pool.
func jsonHosts(t *testing.T) []string {
	t.Helper()
	var hosts []string
	if err := json.Unmarshal([]byte(WarpGenHosts()), &hosts); err != nil {
		t.Fatalf("the endpoint pool must be a JSON array: %v", err)
	}
	return hosts
}

// jsonPorts decodes the JSON payload of the port pool.
func jsonPorts(t *testing.T) []int32 {
	t.Helper()
	var ports []int32
	if err := json.Unmarshal([]byte(WarpGenPorts()), &ports); err != nil {
		t.Fatalf("the port pool must be a JSON array: %v", err)
	}
	return ports
}

func TestWarpGenMasksAreHex(t *testing.T) {
	hexOnly := regexp.MustCompile(`^I1 = <b 0x[0-9a-f]+>$`)
	for _, mask := range []string{warpGenI1Mask1, warpGenI1Mask2} {
		if !hexOnly.MatchString(mask) {
			t.Errorf("mask is not a valid I1 signature: %q", mask)
		}
	}
	if warpGenI1Mask1 == warpGenI1Mask2 {
		t.Error("the two vendored I1 masks must differ")
	}
}

func TestIsIPv4(t *testing.T) {
	cases := map[string]bool{
		"162.159.192.1":           true,
		"0.0.0.0":                 true,
		"engage.cloudflareclient.com": false,
		"162.159.192":             false,
		"162.159.192.1.5":         false,
		"162.159.192.a":           false,
		"":                        false,
	}
	for host, want := range cases {
		if got := isIPv4(host); got != want {
			t.Errorf("isIPv4(%q) = %v, want %v", host, got, want)
		}
	}
}

// TestWarpGenGoldenDigests pins the generated configs to the digests of the
// warp-config-ru reference implementation, so a change in the output format
// cannot pass unnoticed. The AmneziaWG digest normalises the randomly picked
// I1 mask; the Clash digests cover all template branches.
func TestWarpGenGoldenDigests(t *testing.T) {
	const (
		priv = "aGVsbG8gd29ybGQgdGhpcyBpcyAzMiBieXRlcyEhIQ=="
		v6   = "2606:4700:110:8a1b:c4b2:1a2b:3c4d:5e6f"
	)
	i1re := regexp.MustCompile(`I1 = <b 0x[0-9a-f]+>`)
	sum := func(s string) string {
		h := sha256.Sum256([]byte(s))
		return hex.EncodeToString(h[:])
	}

	got := map[string]string{
		"awg-v4":         sum(i1re.ReplaceAllString(WarpGenAmneziaWG(priv, "", v6, "162.159.192.1", 4500, false), "I1 = <MASK>")),
		"awg-v6":         sum(i1re.ReplaceAllString(WarpGenAmneziaWG(priv, "", v6, "162.159.192.1", 4500, true), "I1 = <MASK>")),
		"clash-v4":       sum(WarpGenClash(priv, v6, "162.159.192.1", 4500, false)),
		"clash-v6":       sum(WarpGenClash(priv, v6, "162.159.192.1", 4500, true)),
		"clash-hostname": sum(WarpGenClash(priv, v6, "engage.cloudflareclient.com", 4500, false)),
	}
	want := map[string]string{
		"awg-v4":         "62e2786f9e8efc8802d3d2120d8dbbe6b13a0ac78825332d54a6e9f98fdfff2a",
		"awg-v6":         "686b3982e07f2de6314cd6d3ac33ff570c5af9a8f9c94409199c14ff9a759d5c",
		"clash-v4":       "23388eaa31828a9aecd736286ffceb380202fc970c196b2d26748c9b81000522",
		"clash-v6":       "c6a975f8c1f1436310c51084f5bcd6842a9d543c088c21cb9950332ccacb5379",
		"clash-hostname": "bdbd50c898602119e53c46eb52fc44c08cf653e24004654079750bac56697275",
	}
	for name, digest := range want {
		if got[name] != digest {
			t.Errorf("%s digest = %s, want %s (the config format drifted)", name, got[name], digest)
		}
	}
}
