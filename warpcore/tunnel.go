package warpcore

import (
	"encoding/base64"
	"fmt"
	"sync"
	"syscall"

	"github.com/amnezia-vpn/amneziawg-go/conn"
	"github.com/amnezia-vpn/amneziawg-go/device"
)

// Protector is implemented on the Kotlin/Java side to protect UDP sockets
// from the VPN routing loop (VpnService.protect).
type Protector interface {
	Protect(fd int32) bool
}

var (
	handles   = make(map[int32]*tunnelHandle)
	handlesMu sync.Mutex
	nextID    int32

	protectors    sync.Map // map[*Protector]struct{} — active protectors
	protectorOnce sync.Once
)

type tunnelHandle struct {
	dev       *device.Device
	protector *Protector
}

// globalControl applies every active protector to each UDP socket the bind
// creates. Registered once for the whole process.
func globalControl(network, address string, c syscall.RawConn) error {
	var result error
	c.Control(func(fd uintptr) {
		pfd := int32(fd)
		ok := false
		protectors.Range(func(_, v any) bool {
			p, _ := v.(Protector)
			if p != nil && p.Protect(pfd) {
				ok = true
				return false
			}
			return true
		})
		if !ok {
			result = fmt.Errorf("socket protect failed for %s %s", network, address)
		}
	})
	return result
}

// TunnelStart brings up a WireGuard / AmneziaWG tunnel on the given TUN file
// descriptor. settings is a WireGuard IpcSet configuration string
// (private_key=...\npublic_key=...\nendpoint=...\n... including AmneziaWG
// jc/jmin/jmax/s1/s2/h1..h4 keys). reservedB64 is the base64 WARP client_id
// for Cloudflare endpoints ("q/t8" etc.), or "" for plain WireGuard/AWG.
// Returns a handle used by TunnelStop/TunnelGetStats.
func TunnelStart(tunFd int32, mtu int32, settings string, reservedB64 string, protector Protector) (int32, error) {
	var storedProtector *Protector
	if protector != nil {
		protectorOnce.Do(func() {
			conn.AppendControlFn(globalControl)
		})
		storedProtector = &protector
		protectors.Store(storedProtector, protector)
	}

	tunDev, err := tunFromFd(int(tunFd))
	if err != nil {
		return 0, fmt.Errorf("CreateUnmonitoredTUNFromFD: %w", err)
	}

	logger := device.NewLogger(device.LogLevelError, "[warpcore] ")
	dev := device.NewDevice(tunDev, conn.NewStdNetBind(), logger)
	dev.DisableSomeRoamingForBrokenMobileSemantics()

	if reservedB64 != "" {
		raw, err := base64.StdEncoding.DecodeString(reservedB64)
		if err != nil || len(raw) != 3 {
			dev.Close()
			return 0, fmt.Errorf("invalid warp reserved bytes: %q", reservedB64)
		}
		var r [3]byte
		copy(r[:], raw)
		dev.SetWarpReserved(r)
	}

	if err := dev.IpcSet(settings); err != nil {
		dev.Close()
		return 0, fmt.Errorf("IpcSet: %w", err)
	}
	if err := dev.Up(); err != nil {
		dev.Close()
		return 0, fmt.Errorf("Up: %w", err)
	}

	handlesMu.Lock()
	defer handlesMu.Unlock()
	nextID++
	handles[nextID] = &tunnelHandle{dev: dev, protector: storedProtector}
	return nextID, nil
}

// TunnelStop tears down a tunnel started with TunnelStart.
func TunnelStop(handle int32) error {
	handlesMu.Lock()
	h, ok := handles[handle]
	if ok {
		delete(handles, handle)
	}
	handlesMu.Unlock()
	if !ok {
		return fmt.Errorf("unknown tunnel handle %d", handle)
	}
	if h.protector != nil {
		protectors.Delete(h.protector)
	}
	h.dev.Close()
	return nil
}

// TunnelGetStats returns the device IpcGet dump (private key hidden on the
// Kotlin side) including last_handshake_time_sec, rx/tx byte counters.
func TunnelGetStats(handle int32) (string, error) {
	handlesMu.Lock()
	h, ok := handles[handle]
	handlesMu.Unlock()
	if !ok {
		return "", fmt.Errorf("unknown tunnel handle %d", handle)
	}
	return h.dev.IpcGet()
}

// TunnelStopAll is called when the VPN service dies unexpectedly.
func TunnelStopAll() error {
	handlesMu.Lock()
	hs := make([]*tunnelHandle, 0, len(handles))
	for id, h := range handles {
		hs = append(hs, h)
		delete(handles, id)
	}
	handlesMu.Unlock()
	for _, h := range hs {
		if h.protector != nil {
			protectors.Delete(h.protector)
		}
		h.dev.Close()
	}
	return nil
}
