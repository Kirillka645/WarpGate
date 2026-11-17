package internal

import (
	"context"
	"fmt"
	"net"
	"net/http"
	"time"
)

// Client is the HTTP client used for the Cloudflare API calls.
//
// It prefers IPv4 over IPv6. On networks with a broken IPv6 path the TCP
// handshake succeeds but the TLS bytes are dropped, which surfaces as
// "net/http: TLS handshake timeout" even though the IPv4 route works fine.
// Go's own dialer only falls back to IPv4 when the connection itself fails,
// so the address family is resolved explicitly here.
var Client = &http.Client{
	Timeout:   30 * time.Second,
	Transport: NewTransport(),
}

// NewTransport builds the transport used by Client.
func NewTransport() *http.Transport {
	return &http.Transport{
		Proxy: http.ProxyFromEnvironment,
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			return DialPreferIPv4(ctx, network, addr)
		},
		ForceAttemptHTTP2:     true,
		MaxIdleConns:          10,
		IdleConnTimeout:       30 * time.Second,
		TLSHandshakeTimeout:   15 * time.Second,
		ResponseHeaderTimeout: 20 * time.Second,
	}
}

// DialPreferIPv4 resolves the host and dials the IPv4 addresses first.
func DialPreferIPv4(ctx context.Context, network, addr string) (net.Conn, error) {
	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		return (&net.Dialer{Timeout: 12 * time.Second}).DialContext(ctx, network, addr)
	}
	if ip := net.ParseIP(host); ip != nil {
		return (&net.Dialer{Timeout: 12 * time.Second}).DialContext(ctx, network, addr)
	}

	ips, err := net.DefaultResolver.LookupIPAddr(ctx, host)
	if err != nil {
		return nil, err
	}
	if len(ips) == 0 {
		return nil, fmt.Errorf("no addresses for %s", host)
	}

	v4 := make([]net.IPAddr, 0, len(ips))
	v6 := make([]net.IPAddr, 0, len(ips))
	for _, ip := range ips {
		if ip.IP.To4() != nil {
			v4 = append(v4, ip)
		} else {
			v6 = append(v6, ip)
		}
	}

	d := &net.Dialer{Timeout: 12 * time.Second, KeepAlive: 30 * time.Second}
	var lastErr error
	for _, ip := range append(v4, v6...) {
		conn, err := d.DialContext(ctx, "tcp", net.JoinHostPort(ip.IP.String(), port))
		if err == nil {
			return conn, nil
		}
		lastErr = err
	}
	return nil, lastErr
}
