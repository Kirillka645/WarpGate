package main

import (
	"encoding/base64"
	"fmt"
)

// Debug helpers local to the desktop harness (not part of the bind surface).

// buildProbeSettings returns the IpcSet string used by handshake probes.
func buildProbeSettings(privateKeyB64, peerPubB64, endpoint string) string {
	priv, _ := base64.StdEncoding.DecodeString(privateKeyB64)
	peer, _ := base64.StdEncoding.DecodeString(peerPubB64)
	return fmt.Sprintf(
		"private_key=%x\npublic_key=%x\nendpoint=%s\nallowed_ip=0.0.0.0/0\nallowed_ip=::/0\npersistent_keepalive_interval=1\n",
		priv, peer, endpoint,
	)
}

// decodeClientID decodes the base64 WARP client_id into raw reserved bytes.
func decodeClientID(b64 string) ([3]byte, bool) {
	var out [3]byte
	raw, err := base64.StdEncoding.DecodeString(b64)
	if err != nil || len(raw) != 3 {
		return out, false
	}
	copy(out[:], raw)
	return out, true
}
