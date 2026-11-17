package warpcore

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/http"
	"strings"
	"time"
)

// warpHTTPClient is the HTTP client used for every Cloudflare WARP API call.
//
// Two things matter on mobile networks:
//
//   - IPv4 is preferred. A half-broken IPv6 path (very common on cellular
//     networks) completes the TCP handshake and then silently drops the TLS
//     bytes, which surfaces as "net/http: TLS handshake timeout" even though
//     the IPv4 path works fine.
//   - Connection errors are retried. A captive portal, a mobile handover or
//     a transient DPI reset breaks the connection without any server answer.
var warpHTTPClient = &http.Client{
	Timeout:   30 * time.Second,
	Transport: newWarpTransport(),
}

func newWarpTransport() *http.Transport {
	return &http.Transport{
		Proxy: http.ProxyFromEnvironment,
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			return dialPreferIPv4(ctx, network, addr)
		},
		ForceAttemptHTTP2:     true,
		MaxIdleConns:          10,
		IdleConnTimeout:       30 * time.Second,
		TLSHandshakeTimeout:   15 * time.Second,
		ResponseHeaderTimeout: 20 * time.Second,
		ExpectContinueTimeout: 1 * time.Second,
	}
}

// dialPreferIPv4 resolves the host itself and dials the IPv4 addresses first.
//
// Go's own dialer implements Happy Eyeballs, but it only falls back to IPv4
// when the IPv6 connection attempt fails. A path that completes the TCP
// handshake and then drops the TLS bytes never triggers that fallback, so the
// request dies with a handshake timeout even though IPv4 would have worked.
func dialPreferIPv4(ctx context.Context, network, addr string) (net.Conn, error) {
	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		// Not a host:port pair (unlikely for HTTP), let the default dialer try.
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

	ordered := orderAddressesIPv4First(ips)
	d := &net.Dialer{Timeout: 12 * time.Second, KeepAlive: 30 * time.Second}

	var lastErr error
	for _, ip := range ordered {
		conn, err := d.DialContext(ctx, "tcp", net.JoinHostPort(ip.IP.String(), port))
		if err == nil {
			return conn, nil
		}
		lastErr = err
	}
	return nil, lastErr
}

// orderAddressesIPv4First puts the IPv4 addresses in front of the IPv6 ones.
func orderAddressesIPv4First(ips []net.IPAddr) []net.IPAddr {
	v4 := make([]net.IPAddr, 0, len(ips))
	v6 := make([]net.IPAddr, 0, len(ips))
	for _, ip := range ips {
		if ip.IP.To4() != nil {
			v4 = append(v4, ip)
		} else {
			v6 = append(v6, ip)
		}
	}
	return append(v4, v6...)
}

// warpDo performs an HTTP request and retries it when the failure happened
// before the server could answer.
//
// newReq has to build a fresh request for every attempt, because the request
// body is consumed by the first try.
func warpDo(newReq func() (*http.Request, error), attempts int) (*http.Response, error) {
	var lastErr error
	for attempt := 0; attempt < attempts; attempt++ {
		if attempt > 0 {
			// Back off a little: a blocked or flaky network needs a moment.
			time.Sleep(time.Duration(attempt) * 700 * time.Millisecond)
		}
		req, err := newReq()
		if err != nil {
			lastErr = err
			continue
		}
		resp, err := warpHTTPClient.Do(req)
		if err == nil {
			return resp, nil
		}
		lastErr = err
		if !isRetryableNetworkError(err) {
			break
		}
	}
	return nil, lastErr
}

// isRetryableNetworkError reports whether the error looks like a temporary
// network problem rather than a protocol or server error.
func isRetryableNetworkError(err error) bool {
	if err == nil {
		return false
	}
	if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
		return true
	}
	var netErr net.Error
	if errors.As(err, &netErr) {
		return netErr.Timeout()
	}
	msg := strings.ToLower(err.Error())
	for _, marker := range []string{
		"tls handshake timeout",
		"connection reset",
		"connection refused",
		"broken pipe",
		"eof",
		"timeout",
		"temporary failure",
		"no route to host",
	} {
		if strings.Contains(msg, marker) {
			return true
		}
	}
	return false
}

// warpErrorKind classifies a failure so the UI can explain it in plain words.
type warpErrorKind int

const (
	warpErrUnknown warpErrorKind = iota
	warpErrTimeout
	warpErrDNS
	warpErrBlocked
	warpErrServer
)

// classifyWarpError maps a transport error onto a coarse kind.
func classifyWarpError(err error) warpErrorKind {
	if err == nil {
		return warpErrUnknown
	}
	msg := strings.ToLower(err.Error())
	switch {
	case strings.Contains(msg, "no such host"), strings.Contains(msg, "dns"):
		return warpErrDNS
	case strings.Contains(msg, "tls handshake timeout"), strings.Contains(msg, "timeout"):
		return warpErrTimeout
	case strings.Contains(msg, "connection refused"), strings.Contains(msg, "connection reset"),
		strings.Contains(msg, "broken pipe"), strings.Contains(msg, "eof"):
		return warpErrBlocked
	}
	var netErr net.Error
	if errors.As(err, &netErr) && netErr.Timeout() {
		return warpErrTimeout
	}
	return warpErrUnknown
}
