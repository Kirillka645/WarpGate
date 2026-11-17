package warpcore

import (
	"bytes"
	"crypto/ecdh"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"
)

// warpAPIBases are the WARP API generations used for the device registration.
//
// The generations differ only in the shape of the JSON envelope: the newer
// one nests the payload under "result", the older one keeps it flat. The
// content is identical, so the app can fall back to the other generation when
// one of them is unreachable (a filtered path, a filtered hostname, a proxy
// that only knows one of them).
var warpAPIBases = []string{
	"https://api.cloudflareclient.com/v0a2158",
	"https://api.cloudflareclient.com/v0i1909051800",
}

// WarpRegistration holds everything needed to build a WARP WireGuard config.
type WarpRegistration struct {
	DeviceID   string `json:"device_id"`
	Token      string `json:"token"`
	PrivateKey string `json:"private_key"` // base64 raw x25519 private key
	PublicKey  string `json:"public_key"`
	ClientID   string `json:"client_id"`   // base64, becomes the WG reserved bytes
	PeerPub    string `json:"peer_pub"`    // WARP server WireGuard public key
	AddressV4  string `json:"address_v4"`
	AddressV6  string `json:"address_v6"`
	Endpoint   string `json:"endpoint"` // e.g. engage.cloudflareclient.com:2408
}

// warpDevice is the raw result of a WARP device registration.
type warpDevice struct {
	ID       string
	Token    string
	ClientID string
	AddressV4 string
	AddressV6 string
	PeerPub  string
	Endpoint string
}

// RegisterWarp performs a Cloudflare WARP device registration and returns the
// parameters needed for a WireGuard configuration. The private key never
// leaves the caller.
func RegisterWarp(model string) (*WarpRegistration, error) {
	priv, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		return nil, err
	}
	pubB64 := base64.StdEncoding.EncodeToString(priv.PublicKey().Bytes())
	privB64 := base64.StdEncoding.EncodeToString(priv.Bytes())

	dev, err := registerWarpDevice(model, pubB64)
	if err != nil {
		return nil, err
	}

	return &WarpRegistration{
		DeviceID:   dev.ID,
		Token:      dev.Token,
		PrivateKey: privB64,
		PublicKey:  pubB64,
		ClientID:   dev.ClientID,
		PeerPub:    dev.PeerPub,
		AddressV4:  dev.AddressV4,
		AddressV6:  dev.AddressV6,
		Endpoint:   dev.Endpoint,
	}, nil
}

// registerWarpDevice registers a public key with the WARP API and enables the
// WARP tunnel for it.
//
// Every API generation is tried at the same time: a generation that cannot be
// reached (filtered path, broken DNS, dropped TLS) must not block the
// registration when the other one answers. Running them in parallel keeps the
// worst case at one timeout instead of the sum of both.
func registerWarpDevice(model, publicKeyB64 string) (*warpDevice, error) {
	type outcome struct {
		dev *warpDevice
		err error
	}
	results := make(chan outcome, len(warpAPIBases))
	for _, base := range warpAPIBases {
		go func(b string) {
			dev, err := registerWarpOnBase(b, model, publicKeyB64)
			results <- outcome{dev: dev, err: err}
		}(base)
	}

	var lastErr error
	for range warpAPIBases {
		r := <-results
		if r.err == nil {
			return r.dev, nil
		}
		lastErr = r.err
	}
	return nil, fmt.Errorf("warp register: %w", lastErr)
}

// registerWarpOnBase runs the register + enable cycle against one API
// generation.
func registerWarpOnBase(base, model, publicKeyB64 string) (*warpDevice, error) {
	body, err := json.Marshal(map[string]any{
		"install_id": "",
		"tos":        time.Now().UTC().Format("2006-01-02T15:04:05.000Z"),
		"key":        publicKeyB64,
		"fcm_token":  "",
		"type":       "Android",
		"locale":     "en_US",
	})
	if err != nil {
		return nil, err
	}

	resp, err := warpDo(func() (*http.Request, error) {
		req, err := http.NewRequest(http.MethodPost, base+"/reg", bytes.NewReader(body))
		if err != nil {
			return nil, err
		}
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("User-Agent", "okhttp/3.12.1")
		req.Header.Set("CF-Client-Version", "a-6.81-4616")
		return req, nil
	}, 2)
	if err != nil {
		return nil, err
	}
	raw, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("HTTP %d: %s", resp.StatusCode, truncate(string(raw), 200))
	}

	env, err := warpAPIPayload(raw)
	if err != nil {
		return nil, err
	}

	id := warpField(env, "id")
	token := warpField(env, "token")
	if id == "" || token == "" {
		return nil, fmt.Errorf("malformed response (no id/token)")
	}

	dev := &warpDevice{
		ID:        id,
		Token:     token,
		ClientID:  warpField(env, "config", "client_id"),
		AddressV4: warpField(env, "config", "interface", "addresses", "v4"),
		AddressV6: warpField(env, "config", "interface", "addresses", "v6"),
		PeerPub:   warpPeerPublicKey(env),
		Endpoint:  warpPeerEndpoint(env),
	}

	// Enable the WARP tunnel for the freshly registered device.
	patch, err := json.Marshal(map[string]any{"warp_enabled": true})
	if err != nil {
		return nil, err
	}
	resp2, err := warpDo(func() (*http.Request, error) {
		req, err := http.NewRequest(http.MethodPatch, base+"/reg/"+id, bytes.NewReader(patch))
		if err != nil {
			return nil, err
		}
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("User-Agent", "okhttp/3.12.1")
		req.Header.Set("Authorization", "Bearer "+token)
		return req, nil
	}, 2)
	if err != nil {
		// The device exists even if the activation failed: the tunnel can still
		// be used, so the registration itself is not a failure.
		if dev.ClientID == "" {
			return nil, err
		}
		return dev.withDefaults(), nil
	}
	raw2, _ := io.ReadAll(resp2.Body)
	resp2.Body.Close()
	if resp2.StatusCode != http.StatusOK {
		if dev.ClientID == "" {
			return nil, fmt.Errorf("activate: HTTP %d: %s", resp2.StatusCode, truncate(string(raw2), 200))
		}
		return dev.withDefaults(), nil
	}
	if env2, err := warpAPIPayload(raw2); err == nil {
		if v := warpField(env2, "config", "client_id"); v != "" {
			dev.ClientID = v
		}
		if v := warpField(env2, "config", "interface", "addresses", "v4"); v != "" {
			dev.AddressV4 = v
		}
		if v := warpField(env2, "config", "interface", "addresses", "v6"); v != "" {
			dev.AddressV6 = v
		}
		if v := warpPeerPublicKey(env2); v != "" {
			dev.PeerPub = v
		}
		if v := warpPeerEndpoint(env2); v != "" {
			dev.Endpoint = v
		}
	}

	if dev.ClientID == "" {
		return nil, fmt.Errorf("response has no client id")
	}
	return dev.withDefaults(), nil
}

// withDefaults fills the fields a response may omit. The WARP address and the
// server key are constants for every registration, so a missing field is
// never fatal.
func (d *warpDevice) withDefaults() *warpDevice {
	if d.AddressV4 == "" {
		d.AddressV4 = warpGenClientIPv4
	}
	if d.PeerPub == "" {
		d.PeerPub = warpGenPeerPublicKey
	}
	return d
}

// warpAPIPayload unwraps the API response.
//
// The newer generation nests everything under "result", the older one returns
// the payload flat. Both carry the same fields.
func warpAPIPayload(raw []byte) (map[string]any, error) {
	var m map[string]any
	if err := json.Unmarshal(raw, &m); err != nil {
		return nil, fmt.Errorf("decode response: %w", err)
	}
	if r, ok := m["result"].(map[string]any); ok {
		return r, nil
	}
	return m, nil
}

// warpField reads a string field from a decoded API payload.
func warpField(env map[string]any, path ...string) string {
	var cur any = env
	for _, p := range path {
		m, ok := cur.(map[string]any)
		if !ok {
			return ""
		}
		cur = m[p]
	}
	s, _ := cur.(string)
	return s
}

// warpPeerPublicKey reads the WARP server key from the peer list.
func warpPeerPublicKey(env map[string]any) string {
	peers, ok := warpFieldAny(env, "config", "peers").([]any)
	if !ok || len(peers) == 0 {
		return ""
	}
	peer, ok := peers[0].(map[string]any)
	if !ok {
		return ""
	}
	s, _ := peer["public_key"].(string)
	return s
}

// warpPeerEndpoint reads the WARP endpoint host from the peer list.
func warpPeerEndpoint(env map[string]any) string {
	peers, ok := warpFieldAny(env, "config", "peers").([]any)
	if !ok || len(peers) == 0 {
		return ""
	}
	peer, ok := peers[0].(map[string]any)
	if !ok {
		return ""
	}
	endpoint, ok := peer["endpoint"].(map[string]any)
	if !ok {
		return ""
	}
	s, _ := endpoint["host"].(string)
	return s
}

// warpFieldAny reads any value from a decoded API payload.
func warpFieldAny(env map[string]any, path ...string) any {
	var cur any = env
	for _, p := range path {
		m, ok := cur.(map[string]any)
		if !ok {
			return nil
		}
		cur = m[p]
	}
	return cur
}
