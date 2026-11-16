//go:build !android

package warpcore

import (
	"encoding/base64"
	"fmt"
)

// Debug helpers used by the desktop test harnesses (cmd/warpdebug etc.).
// Not part of the gomobile bind surface.

// NewProbeTunForDebug exposes the probe TUN for desktop debugging.
func NewProbeTunForDebug() *probeTun {
	return newNoopTun()
}

// DecodeClientID decodes the base64 WARP client_id into raw reserved bytes.
func DecodeClientID(b64 string) ([3]byte, bool) {
	var out [3]byte
	raw, err := base64.StdEncoding.DecodeString(b64)
	if err != nil || len(raw) != 3 {
		return out, false
	}
	copy(out[:], raw)
	return out, true
}

// BuildProbeSettings returns the IpcSet string used by handshake probes.
func BuildProbeSettings(privateKeyB64, peerPubB64, endpoint string) string {
	return fmt.Sprintf(
		"private_key=%s\npublic_key=%s\nendpoint=%s\nallowed_ip=0.0.0.0/0\nallowed_ip=::/0\npersistent_keepalive_interval=1\n",
		hexKey(privateKeyB64),
		hexKey(peerPubB64),
		endpoint,
	)
}
