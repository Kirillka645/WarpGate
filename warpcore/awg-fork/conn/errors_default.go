//go:build !linux

/* SPDX-License-Identifier: MIT
 *
 * Copyright (C) 2017-2023 WireGuard LLC. All Rights Reserved.
 */

package conn

import (
	"errors"
	"syscall"
)

// Windows Sockets error numbers (syscall.Errno values).
const (
	wsaEFAULT      syscall.Errno = 10014
	wsaEOPNOTSUPP  syscall.Errno = 10045
	wsaEINVAL      syscall.Errno = 10022
)

func errShouldDisableUDPGSO(err error) bool {
	// Windows: WSASendMsg can fail with WSAEFAULT when the GSO-style control
	// data is rejected by the stack; retry without tx offload.
	var errno syscall.Errno
	if errors.As(err, &errno) {
		switch errno {
		case wsaEFAULT, wsaEOPNOTSUPP, wsaEINVAL:
			return true
		}
	}
	return false
}
