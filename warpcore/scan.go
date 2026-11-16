package warpcore

import (
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/conn"
	"github.com/amnezia-vpn/amneziawg-go/device"
)

// ScanResult is one endpoint latency measurement (milliseconds; -1 = fail).
type ScanResult struct {
	Endpoint string `json:"endpoint"`
	MS       int64  `json:"ms"`
	OK       bool   `json:"ok"`
}

// ScanEndpoints measures WireGuard handshake RTT to each "ip:port" endpoint
// (endpointsJSON is a JSON array of strings) using the given private key /
// peer public key / WARP client_id. Returns a JSON array of results sorted
// best-first; failures included with ok=false.
func ScanEndpoints(endpointsJSON, privateKeyB64, peerPubB64, reservedB64 string, timeoutMs int32) (string, error) {
	var endpoints []string
	if err := json.Unmarshal([]byte(endpointsJSON), &endpoints); err != nil {
		return "", fmt.Errorf("invalid endpoints JSON: %w", err)
	}
	if len(endpoints) == 0 {
		return "[]", nil
	}
	var reserved [3]byte
	if reservedB64 != "" {
		raw, err := base64.StdEncoding.DecodeString(reservedB64)
		if err != nil || len(raw) != 3 {
			return "", fmt.Errorf("invalid reserved bytes")
		}
		copy(reserved[:], raw)
	}

	priv := hexKey(privateKeyB64)
	peer := hexKey(peerPubB64)

	timeout := time.Duration(timeoutMs) * time.Millisecond
	if timeout <= 0 {
		timeout = 3 * time.Second
	}

	results := make([]ScanResult, len(endpoints))
	sem := make(chan struct{}, 6) // parallel probes
	var wg sync.WaitGroup
	for i, ep := range endpoints {
		wg.Add(1)
		go func(idx int, endpoint string) {
			defer wg.Done()
			sem <- struct{}{}
			defer func() { <-sem }()
			ms, ok := probeHandshake(endpoint, priv, peer, &reserved, timeout)
			results[idx] = ScanResult{Endpoint: endpoint, MS: ms, OK: ok}
		}(i, ep)
	}
	wg.Wait()

	sort.SliceStable(results, func(i, j int) bool {
		if results[i].OK != results[j].OK {
			return results[i].OK
		}
		if !results[i].OK {
			return false
		}
		return results[i].MS < results[j].MS
	})
	out, _ := json.Marshal(results)
	return string(out), nil
}

// probeHandshake creates a throwaway device on a noop tun and waits for the
// handshake to complete, measuring elapsed time.
func probeHandshake(endpoint string, privHex, peerHex string, reserved *[3]byte, timeout time.Duration) (int64, bool) {
	dev := device.NewDevice(newNoopTun(), conn.NewStdNetBind(), device.NewLogger(device.LogLevelError, ""))
	defer dev.Close()

	if reserved != nil {
		dev.SetWarpReserved(*reserved)
	}

	settings := fmt.Sprintf(
		"private_key=%s\npublic_key=%s\nendpoint=%s\nallowed_ip=0.0.0.0/0\nallowed_ip=::/0\npersistent_keepalive_interval=1\n",
		privHex,
		peerHex,
		endpoint,
	)
	if err := dev.IpcSet(settings); err != nil {
		return -1, false
	}
	if err := dev.Up(); err != nil {
		return -1, false
	}

	start := time.Now()
	deadline := start.Add(timeout)
	for time.Now().Before(deadline) {
		time.Sleep(50 * time.Millisecond)
		ipc, err := dev.IpcGet()
		if err != nil {
			return -1, false
		}
		if handshakeDone(ipc) {
			return time.Since(start).Milliseconds(), true
		}
	}
	return -1, false
}

// handshakeDone parses IpcGet output for peer.last_handshake_time_sec > 0.
func handshakeDone(ipc string) bool {
	for _, line := range strings.Split(ipc, "\n") {
		line = strings.TrimSpace(line)
		if strings.HasPrefix(line, "last_handshake_time_sec=") {
			val := strings.TrimPrefix(line, "last_handshake_time_sec=")
			return val != "" && val != "0"
		}
	}
	return false
}

// hexKey converts a base64 WireGuard key to the hex encoding the IpcSet
// protocol expects, defaulting to a zero key on invalid input.
func hexKey(b64 string) string {
	raw, err := base64.StdEncoding.DecodeString(b64)
	if err != nil || len(raw) != 32 {
		raw = make([]byte, 32)
	}
	return hex.EncodeToString(raw)
}
