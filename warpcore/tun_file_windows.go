//go:build windows

package warpcore

import (
	"errors"

	awgtun "github.com/amnezia-vpn/amneziawg-go/tun"
)

// TUN file descriptors from VpnService only exist on Android; on Windows this
// build exists so the package compiles for the desktop test harness.
func tunFromFd(fd int) (awgtun.Device, error) {
	return nil, errors.New("TunnelStart is only supported on Android")
}
