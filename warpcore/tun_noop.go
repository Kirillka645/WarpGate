// Package warpcore provides the WarpGate Android VPN core: WireGuard /
// AmneziaWG tunneling, Cloudflare WARP account registration and endpoint
// latency scanning. It is designed to be bound with gomobile.
package warpcore

import (
	"errors"
	"os"
	"sync/atomic"

	"github.com/amnezia-vpn/amneziawg-go/tun"
)

// probeTun is a tun.Device that emits one dummy IPv4 packet (to trigger the
// initial handshake) and then blocks until closed. Used by the endpoint
// scanner so handshake probes do not need a real interface.
type probeTun struct {
	events chan tun.Event
	done   chan struct{}
	fed    atomic.Bool
}

func newNoopTun() *probeTun {
	ch := make(chan tun.Event, 1)
	ch <- tun.EventUp
	return &probeTun{events: ch, done: make(chan struct{})}
}

func (t *probeTun) File() *os.File { return nil }

// dummyIPv4Packet is a minimal IPv4 header (dst 10.0.0.1, src 172.16.0.2,
// protocol 6) with no payload. Its contents are irrelevant: it only serves to
// trigger the WireGuard handshake initiation.
var dummyIPv4Packet = []byte{
	0x45, 0x00, 0x00, 0x14, 0x00, 0x00, 0x00, 0x00, 0x40, 0x06,
	0x00, 0x00, 0xac, 0x10, 0x00, 0x02, 0x0a, 0x00, 0x00, 0x01,
}

func (t *probeTun) Read(bufs [][]byte, sizes []int, offset int) (int, error) {
	if !t.fed.CompareAndSwap(false, true) {
		// Block forever until the device is closed.
		<-t.done
		return 0, errors.New("probe tun closed")
	}
	n := copy(bufs[0][offset:], dummyIPv4Packet)
	sizes[0] = n
	return 1, nil
}

func (t *probeTun) Write(bufs [][]byte, offset int) (int, error) {
	return len(bufs), nil
}

func (t *probeTun) MTU() (int, error)  { return 1420, nil }
func (t *probeTun) Name() (string, error) { return "noop", nil }
func (t *probeTun) Events() <-chan tun.Event { return t.events }
func (t *probeTun) BatchSize() int { return 1 }

func (t *probeTun) Close() error {
	select {
	case <-t.done:
	default:
		close(t.done)
	}
	return nil
}
