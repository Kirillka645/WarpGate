package main

import (
	"errors"
	"os"
	"sync/atomic"

	"github.com/amnezia-vpn/amneziawg-go/tun"
)

// debugTun feeds one dummy IPv4 packet to trigger the handshake, then blocks.
type debugTun struct {
	events chan tun.Event
	done   chan struct{}
	fed    atomic.Bool
}

func newDebugTun() *debugTun {
	ch := make(chan tun.Event, 1)
	ch <- tun.EventUp
	return &debugTun{events: ch, done: make(chan struct{})}
}

var dummyPacket = []byte{
	0x45, 0x00, 0x00, 0x14, 0x00, 0x00, 0x00, 0x00, 0x40, 0x06,
	0x00, 0x00, 0xac, 0x10, 0x00, 0x02, 0x0a, 0x00, 0x00, 0x01,
}

func (t *debugTun) File() *os.File { return nil }

func (t *debugTun) Read(bufs [][]byte, sizes []int, offset int) (int, error) {
	if !t.fed.CompareAndSwap(false, true) {
		<-t.done
		return 0, errors.New("closed")
	}
	n := copy(bufs[0][offset:], dummyPacket)
	sizes[0] = n
	return 1, nil
}

func (t *debugTun) Write(bufs [][]byte, offset int) (int, error) { return len(bufs), nil }
func (t *debugTun) MTU() (int, error)                            { return 1420, nil }
func (t *debugTun) Name() (string, error)                        { return "dbg", nil }
func (t *debugTun) Events() <-chan tun.Event                     { return t.events }
func (t *debugTun) BatchSize() int                               { return 1 }
func (t *debugTun) Close() error {
	select {
	case <-t.done:
	default:
		close(t.done)
	}
	return nil
}
