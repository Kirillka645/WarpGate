//go:build !windows

package warpcore

import (
	awgtun "github.com/amnezia-vpn/amneziawg-go/tun"
)

// tunFromFd wraps the VpnService TUN file descriptor into a tun.Device.
// CreateUnmonitoredTUNFromFD is the wireguard-android pattern for fds handed
// out by VpnService.establish(): no netlink monitoring, no MTU ioctls.
func tunFromFd(fd int) (awgtun.Device, error) {
	dev, _, err := awgtun.CreateUnmonitoredTUNFromFD(fd)
	return dev, err
}
