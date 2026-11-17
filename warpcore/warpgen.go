package warpcore

import (
	"bytes"
	"crypto/ecdh"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"math/big"
	"net/http"
	"regexp"
	"strings"
	"time"
)

// warpGenAPIBase is the WARP API generation used by third-party config
// generators. It returns the standard WARP peer key and the client IPv6
// address, which is exactly what an exportable AmneziaWG / Clash config needs.
const warpGenAPIBase = "https://api.cloudflareclient.com/v0i1909051800"

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

// warpGenRegisterResponse models the WARP registration reply. Depending on the
// API generation the payload is either nested under "result" or flat at the
// top level, so both shapes are accepted.
type warpGenRegisterResponse struct {
	Result *struct {
		ID    string `json:"id"`
		Token string `json:"token"`
	} `json:"result"`
	ID    string `json:"id"`
	Token string `json:"token"`
	Config struct {
		Peers []struct {
			PublicKey string `json:"public_key"`
		} `json:"peers"`
		Interface struct {
			Addresses struct {
				V4 string `json:"v4"`
				V6 string `json:"v6"`
			} `json:"addresses"`
		} `json:"interface"`
	} `json:"config"`
}

// warpGenPatchResponse models the WARP activation reply.
type warpGenPatchResponse struct {
	Result *struct {
		Config struct {
			Peers []struct {
				PublicKey string `json:"public_key"`
			} `json:"peers"`
			Interface struct {
				Addresses struct {
					V4 string `json:"v4"`
					V6 string `json:"v6"`
				} `json:"addresses"`
			} `json:"interface"`
		} `json:"config"`
	} `json:"result"`
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

	body, err := json.Marshal(map[string]any{
		"install_id": "",
		"tos":        time.Now().UTC().Format("2006-01-02T15:04:05.000Z"),
		"key":        pubB64,
		"fcm_token":  "",
		"type":       "ios",
		"locale":     "en_US",
	})
	if err != nil {
		return nil, err
	}

	client := &http.Client{Timeout: 20 * time.Second}
	req, err := http.NewRequest(http.MethodPost, warpGenAPIBase+"/reg", bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("User-Agent", "okhttp/3.12.1")

	resp, err := client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("warp register: %w", err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("warp register: HTTP %d: %s", resp.StatusCode, truncate(string(raw), 200))
	}

	var rr warpGenRegisterResponse
	if err := json.Unmarshal(raw, &rr); err != nil {
		return nil, fmt.Errorf("warp register: %w", err)
	}

	id, token := rr.ID, rr.Token
	if rr.Result != nil {
		if id == "" {
			id = rr.Result.ID
		}
		if token == "" {
			token = rr.Result.Token
		}
	}
	if id == "" || token == "" {
		return nil, fmt.Errorf("warp register: malformed response (no id/token)")
	}

	// Enable the WARP tunnel for the freshly registered device.
	patch, err := json.Marshal(map[string]any{"warp_enabled": true})
	if err != nil {
		return nil, err
	}
	req2, err := http.NewRequest(http.MethodPatch, warpGenAPIBase+"/reg/"+id, bytes.NewReader(patch))
	if err != nil {
		return nil, err
	}
	req2.Header.Set("Content-Type", "application/json")
	req2.Header.Set("User-Agent", "okhttp/3.12.1")
	req2.Header.Set("Authorization", "Bearer "+token)

	resp2, err := client.Do(req2)
	if err != nil {
		return nil, fmt.Errorf("warp activate: %w", err)
	}
	defer resp2.Body.Close()
	raw2, _ := io.ReadAll(resp2.Body)
	if resp2.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("warp activate: HTTP %d: %s", resp2.StatusCode, truncate(string(raw2), 200))
	}

	var pr warpGenPatchResponse
	if err := json.Unmarshal(raw2, &pr); err != nil {
		return nil, fmt.Errorf("warp activate: %w", err)
	}

	reg := &WarpGenRegistration{
		DeviceID:   id,
		Token:      token,
		PrivateKey: privB64,
		PublicKey:  pubB64,
		PeerPub:    warpGenPeerPublicKey,
		ClientIPv6: rr.Config.Interface.Addresses.V6,
		AddressV4:  rr.Config.Interface.Addresses.V4,
	}
	if len(rr.Config.Peers) > 0 && rr.Config.Peers[0].PublicKey != "" {
		reg.PeerPub = rr.Config.Peers[0].PublicKey
	}
	if pr.Result != nil {
		if reg.ClientIPv6 == "" {
			reg.ClientIPv6 = pr.Result.Config.Interface.Addresses.V6
		}
		if reg.AddressV4 == "" {
			reg.AddressV4 = pr.Result.Config.Interface.Addresses.V4
		}
		if len(pr.Result.Config.Peers) > 0 && pr.Result.Config.Peers[0].PublicKey != "" {
			reg.PeerPub = pr.Result.Config.Peers[0].PublicKey
		}
	}
	if reg.ClientIPv6 == "" {
		return nil, fmt.Errorf("warp activate: response has no client IPv6 address")
	}
	if reg.AddressV4 == "" {
		reg.AddressV4 = warpGenClientIPv4
	}
	return reg, nil
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
