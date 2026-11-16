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

const warpAPIBase = "https://api.cloudflareclient.com/v0a2158"

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

type warpRegRequest struct {
	InstallID    string `json:"install_id"`
	FCMToken     string `json:"fcm_token"`
	TOS          string `json:"tos"`
	Model        string `json:"model"`
	SerialNumber string `json:"serial_number"`
	Locale       string `json:"locale"`
	Key          string `json:"key"`
	Type         string `json:"type"`
}

type warpRegResponse struct {
	ID     string `json:"id"`
	Key    string `json:"key"`
	Token  string `json:"token"`
	Config struct {
		ClientID string `json:"client_id"`
		Peers    []struct {
			PublicKey string `json:"public_key"`
			Endpoint  struct {
				Host string `json:"host"`
				V4   string `json:"v4"`
				V6   string `json:"v6"`
			} `json:"endpoint"`
		} `json:"peers"`
		Interface struct {
			Addresses struct {
				V4 string `json:"v4"`
				V6 string `json:"v6"`
			} `json:"addresses"`
		} `json:"interface"`
	} `json:"config"`
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

	body, _ := json.Marshal(warpRegRequest{
		TOS:          time.Now().UTC().Format("2006-01-02T15:04:05.000Z"),
		Model:        model,
		SerialNumber: randomHex(8),
		Locale:       "en_US",
		Key:          pubB64,
		Type:         "Android",
	})

	req, err := http.NewRequest(http.MethodPost, warpAPIBase+"/reg", bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("User-Agent", "okhttp/3.12.1")
	req.Header.Set("CF-Client-Version", "a-6.81-4616")

	client := &http.Client{Timeout: 20 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("warp register: HTTP %d: %s", resp.StatusCode, string(raw))
	}

	var rr warpRegResponse
	if err := json.Unmarshal(raw, &rr); err != nil {
		return nil, err
	}
	if len(rr.Config.Peers) == 0 {
		return nil, fmt.Errorf("warp register: no peers in response")
	}

	reg := &WarpRegistration{
		DeviceID:   rr.ID,
		Token:      rr.Token,
		PrivateKey: privB64,
		PublicKey:  pubB64,
		ClientID:   rr.Config.ClientID,
		PeerPub:    rr.Config.Peers[0].PublicKey,
		AddressV4:  rr.Config.Interface.Addresses.V4,
		AddressV6:  rr.Config.Interface.Addresses.V6,
		Endpoint:   rr.Config.Peers[0].Endpoint.Host,
	}
	return reg, nil
}

func randomHex(n int) string {
	b := make([]byte, n)
	rand.Read(b)
	return fmt.Sprintf("%x", b)
}
