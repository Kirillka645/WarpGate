package warpcore

import (
	"crypto/ecdh"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"math/big"
	"regexp"
	"strings"
)

// warpGenPeerPublicKey is the well-known Cloudflare WARP WireGuard public key.
// It is only used as a fallback when the registration response omits the peer
// section; the endpoint address is a WARP constant for every registration.
const warpGenPeerPublicKey = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="

// warpGenClientIPv4 is the client address WARP assigns to every device.
const warpGenClientIPv4 = "172.16.0.2"

// warpGenMTU is the MTU used by the generated configs. WARP tunnels IPv6, so
// 1280 is the safe value that survives the extra encapsulation headers.
const warpGenMTU = 1280

// warpGenDNSResolvers are the DNS servers of the geoblock-bypass provider.
// Both providers are listed so the client has a fallback when one of them is
// unreachable from the current network.
var warpGenDNSResolvers = []string{
	"45.155.204.190",
	"37.230.192.51",
	"83.220.169.155",
	"212.109.195.93",
}

// warpGenHosts is the pool of WARP endpoints. The official hostname plus
// anycast IPs from different /24 ranges: if a provider blocks one range, the
// others keep working.
var warpGenHosts = []string{
	"engage.cloudflareclient.com",
	"162.159.192.1",
	"162.159.193.1",
	"162.159.195.1",
}

// warpGenPorts are the UDP ports Cloudflare WARP is known to accept.
var warpGenPorts = []int32{4500, 2408, 500, 1701}

// WarpGenHosts returns the pool of WARP endpoints as a JSON array.
//
// gobind only binds basic, named and pointer types, so slices are handed over
// as JSON — the same approach the endpoint scanner uses.
func WarpGenHosts() string {
	b, _ := json.Marshal(warpGenHosts)
	return string(b)
}

// WarpGenPorts returns the UDP ports Cloudflare WARP is known to accept, as a
// JSON array.
func WarpGenPorts() string {
	b, _ := json.Marshal(warpGenPorts)
	return string(b)
}

// WarpGenRegistration holds everything an exportable WARP config needs.
type WarpGenRegistration struct {
	DeviceID   string `json:"device_id"`
	Token      string `json:"token"`
	PrivateKey string `json:"private_key"` // base64 raw x25519 private key
	PublicKey  string `json:"public_key"`
	PeerPub    string `json:"peer_pub"`    // WARP server WireGuard public key
	ClientIPv6 string `json:"client_ipv6"`
	AddressV4  string `json:"address_v4"`
}

// WarpGenRegister registers a freshly generated WireGuard key with Cloudflare
// WARP and enables the WARP tunnel for it. The private key never leaves the
// device: only its public half is sent to the API.
func WarpGenRegister() (*WarpGenRegistration, error) {
	priv, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		return nil, fmt.Errorf("generate x25519 key: %w", err)
	}
	pubB64 := base64.StdEncoding.EncodeToString(priv.PublicKey().Bytes())
	privB64 := base64.StdEncoding.EncodeToString(priv.Bytes())

	dev, err := registerWarpDevice("Android", pubB64)
	if err != nil {
		return nil, err
	}

	return &WarpGenRegistration{
		DeviceID:   dev.ID,
		Token:      dev.Token,
		PrivateKey: privB64,
		PublicKey:  pubB64,
		PeerPub:    dev.PeerPub,
		ClientIPv6: dev.AddressV6,
		AddressV4:  dev.AddressV4,
	}, nil
}

// WarpGenAmneziaWG builds an AmneziaWG 1.5 (.conf) configuration for Cloudflare
// WARP.
//
// WARP is a standard WireGuard server, so the headers (H1-H4) and the padding
// (S1/S2) keep their standard values and only the junk packet count (Jc) and
// the I1 signature survive against it: the I1 bytes are sent before the
// handshake so a DPI middlebox sees a plausible protocol header instead of a
// WireGuard datagram.
func WarpGenAmneziaWG(privateKey, peerPub, clientIPv6, host string, port int32, ipv6 bool) string {
	if peerPub == "" {
		peerPub = warpGenPeerPublicKey
	}
	dns := strings.Join(warpGenDNSResolvers, ", ")

	address := warpGenClientIPv4
	allowedIPs := "0.0.0.0/0"
	if ipv6 && clientIPv6 != "" {
		address = warpGenClientIPv4 + ", " + clientIPv6
		allowedIPs = "0.0.0.0/0, ::/0"
	}

	var iface strings.Builder
	fmt.Fprintf(&iface, "[Interface]\n")
	fmt.Fprintf(&iface, "PrivateKey = %s\n", privateKey)
	fmt.Fprintf(&iface, "Address = %s\n", address)
	fmt.Fprintf(&iface, "DNS = %s\n", dns)
	fmt.Fprintf(&iface, "MTU = %d\n", warpGenMTU)
	fmt.Fprintf(&iface, "S1 = 0\n")
	fmt.Fprintf(&iface, "S2 = 0\n")
	fmt.Fprintf(&iface, "Jc = 4\n")
	fmt.Fprintf(&iface, "Jmin = 40\n")
	fmt.Fprintf(&iface, "Jmax = 70\n")
	fmt.Fprintf(&iface, "H1 = 1\n")
	fmt.Fprintf(&iface, "H2 = 2\n")
	fmt.Fprintf(&iface, "H3 = 3\n")
	fmt.Fprintf(&iface, "H4 = 4\n")
	fmt.Fprintf(&iface, "%s\n", warpGenRandomI1Mask())

	var peer strings.Builder
	fmt.Fprintf(&peer, "[Peer]\n")
	fmt.Fprintf(&peer, "PublicKey = %s\n", peerPub)
	fmt.Fprintf(&peer, "AllowedIPs = %s\n", allowedIPs)
	fmt.Fprintf(&peer, "Endpoint = %s:%d\n", host, port)
	fmt.Fprintf(&peer, "PersistentKeepalive = 25\n")

	return iface.String() + "\n" + peer.String()
}

// WarpGenClash builds a mihomo/Clash configuration from the vendored template.
//
// The template ships three proxy entries that differ only by their handshake
// mask (the AmneziaWG I1 signature) and share one common anchor, so the key,
// host and port are substituted once and cover all three nodes.
func WarpGenClash(privateKey, clientIPv6, host string, port int32, ipv6 bool) string {
	out := warpGenClashTemplate

	out = replaceFirst(out, regexp.MustCompile(`private-key:[^\n]*`), "private-key: "+privateKey)
	out = replaceAll(out, regexp.MustCompile(`(?m)^ {4}server:.*$`), fmt.Sprintf("    server: %s", host))
	out = replaceAll(out, regexp.MustCompile(`(?m)^ {4}port:.*$`), fmt.Sprintf("    port: %d", port))

	// The endpoint pin keeps the WARP server itself outside of the tunnel. It
	// has to follow the chosen endpoint: an IP-CIDR rule cannot match a
	// hostname, so the rule is dropped for hostname endpoints.
	if isIPv4(host) {
		out = strings.Replace(out, "WARP_ENDPOINT", host, 1)
	} else {
		out = replaceAll(out, regexp.MustCompile(`(?m)^ {2}- IP-CIDR,WARP_ENDPOINT/32,DIRECT,no-resolve\n`), "")
	}

	if ipv6 && clientIPv6 != "" {
		out = replaceFirst(out, regexp.MustCompile(`(?m)^( {2}ip: .*)$`), "${1}\n  ipv6: "+clientIPv6)
		out = replaceFirst(out, regexp.MustCompile(`allowed-ips:\s*\['0\.0\.0\.0/0'\]`), "allowed-ips: ['0.0.0.0/0', '::/0']")
		out = replaceAll(out, regexp.MustCompile(`(?m)^([ \t]*)ipv6: false$`), "${1}ipv6: true")
		out = replaceAll(out, regexp.MustCompile(`(?m)^ {2}- IP-CIDR,::/0,REJECT-DROP,no-resolve\n`), "")
	}

	return out
}

// WarpGenEndpoint is a WARP endpoint pair.
type WarpGenEndpoint struct {
	Host string `json:"host"`
	Port int32  `json:"port"`
}

// WarpGenRandomEndpoint picks a random WARP endpoint from the pool.
//
// Only numeric addresses are used for the random pick: a hostname resolves
// through DNS, which is exactly what a blocked network prevents. The spread
// across different /24 ranges means that blocking one address or one port
// still leaves working alternatives.
func WarpGenRandomEndpoint() *WarpGenEndpoint {
	ips := make([]string, 0, len(warpGenHosts))
	for _, h := range warpGenHosts {
		if isIPv4(h) {
			ips = append(ips, h)
		}
	}
	if len(ips) == 0 {
		ips = warpGenHosts
	}
	return &WarpGenEndpoint{
		Host: ips[randomInt(len(ips))],
		Port: warpGenPorts[randomInt(len(warpGenPorts))],
	}
}

// warpGenRandomI1Mask returns one of the vendored AmneziaWG handshake masks.
func warpGenRandomI1Mask() string {
	masks := []string{warpGenI1Mask1, warpGenI1Mask2}
	return masks[randomInt(len(masks))]
}

// randomInt returns a uniformly distributed value in [0, n).
func randomInt(n int) int {
	if n <= 1 {
		return 0
	}
	v, err := rand.Int(rand.Reader, big.NewInt(int64(n)))
	if err != nil {
		return 0
	}
	return int(v.Int64())
}

func isIPv4(host string) bool {
	parts := strings.Split(host, ".")
	if len(parts) != 4 {
		return false
	}
	for _, p := range parts {
		if p == "" {
			return false
		}
		for _, c := range p {
			if c < '0' || c > '9' {
				return false
			}
		}
	}
	return true
}

// replaceFirst replaces the first match of re and expands capture groups in
// the replacement (the $1 syntax of ExpandString).
func replaceFirst(s string, re *regexp.Regexp, repl string) string {
	loc := re.FindStringSubmatchIndex(s)
	if loc == nil {
		return s
	}
	return s[:loc[0]] + string(re.ExpandString(nil, repl, s, loc)) + s[loc[1]:]
}

// replaceAll replaces every match of re and expands capture groups in the
// replacement.
func replaceAll(s string, re *regexp.Regexp, repl string) string {
	return re.ReplaceAllString(s, repl)
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n] + "..."
}
