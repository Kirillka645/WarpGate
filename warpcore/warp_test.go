package warpcore

import (
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// warpAPIResponse builds a registration payload in the shape of the newer API
// generation (flat) or the older one (nested under "result").
func warpAPIResponse(nested bool) string {
	inner := `{
		"id": "abc-123",
		"token": "tok-456",
		"warp_enabled": true,
		"config": {
			"client_id": "hRL0",
			"interface": { "addresses": { "v4": "172.16.0.2", "v6": "2606:4700:110::1" } },
			"peers": [{
				"public_key": "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=",
				"endpoint": { "host": "engage.cloudflareclient.com:2408" }
			}]
		}
	}`
	if nested {
		return `{"result": ` + inner + `, "success": true}`
	}
	return inner
}

// warpAPIServer serves the register + activate cycle like the WARP API does.
func warpAPIServer(t *testing.T, nested bool, failWith int) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if failWith != 0 {
			w.WriteHeader(failWith)
			fmt.Fprint(w, `{"errors":[{"code":1,"message":"nope"}]}`)
			return
		}
		switch {
		case r.Method == http.MethodPost && r.URL.Path == "/reg":
			fmt.Fprint(w, warpAPIResponse(nested))
		case r.Method == http.MethodPatch && strings.HasPrefix(r.URL.Path, "/reg/"):
			if r.Header.Get("Authorization") != "Bearer tok-456" {
				w.WriteHeader(http.StatusUnauthorized)
				fmt.Fprint(w, `{"errors":[{"code":1001,"message":"bad token"}]}`)
				return
			}
			fmt.Fprint(w, warpAPIResponse(nested))
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
}

func withAPIBases(t *testing.T, bases ...string) {
	t.Helper()
	old := warpAPIBases
	warpAPIBases = bases
	t.Cleanup(func() { warpAPIBases = old })
}

func TestRegisterWarpFlatResponse(t *testing.T) {
	srv := warpAPIServer(t, false, 0)
	defer srv.Close()
	withAPIBases(t, srv.URL)

	reg, err := RegisterWarp("Android")
	if err != nil {
		t.Fatalf("registration failed: %v", err)
	}
	if reg.DeviceID != "abc-123" || reg.Token != "tok-456" {
		t.Errorf("unexpected ids: %+v", reg)
	}
	if reg.ClientID != "hRL0" {
		t.Errorf("client id = %q, want hRL0", reg.ClientID)
	}
	if reg.AddressV4 != "172.16.0.2" {
		t.Errorf("address v4 = %q", reg.AddressV4)
	}
	if reg.AddressV6 != "2606:4700:110::1" {
		t.Errorf("address v6 = %q", reg.AddressV6)
	}
	if reg.PeerPub != warpGenPeerPublicKey {
		t.Errorf("peer key = %q", reg.PeerPub)
	}
	if reg.Endpoint != "engage.cloudflareclient.com:2408" {
		t.Errorf("endpoint = %q", reg.Endpoint)
	}
	if reg.PrivateKey == "" || reg.PublicKey == "" {
		t.Error("the key pair must be filled")
	}
}

func TestRegisterWarpNestedResponse(t *testing.T) {
	srv := warpAPIServer(t, true, 0)
	defer srv.Close()
	withAPIBases(t, srv.URL)

	reg, err := RegisterWarp("Android")
	if err != nil {
		t.Fatalf("registration failed: %v", err)
	}
	if reg.ClientID != "hRL0" {
		t.Errorf("client id = %q, want hRL0", reg.ClientID)
	}
	if reg.AddressV6 != "2606:4700:110::1" {
		t.Errorf("address v6 = %q", reg.AddressV6)
	}
}

func TestRegisterWarpFallsBackToTheNextAPIGeneration(t *testing.T) {
	broken := warpAPIServer(t, false, http.StatusInternalServerError)
	defer broken.Close()
	working := warpAPIServer(t, false, 0)
	defer working.Close()

	withAPIBases(t, broken.URL, working.URL)

	reg, err := RegisterWarp("Android")
	if err != nil {
		t.Fatalf("the fallback generation must be used: %v", err)
	}
	if reg.ClientID != "hRL0" {
		t.Errorf("client id = %q", reg.ClientID)
	}
}

func TestRegisterWarpReportsTheLastError(t *testing.T) {
	broken := warpAPIServer(t, false, http.StatusBadGateway)
	defer broken.Close()
	withAPIBases(t, broken.URL)

	if _, err := RegisterWarp("Android"); err == nil {
		t.Fatal("expected an error when every generation fails")
	}
}

func TestWarpAPIPayloadUnwrapsBothShapes(t *testing.T) {
	flat, err := warpAPIPayload([]byte(warpAPIResponse(false)))
	if err != nil {
		t.Fatalf("flat payload: %v", err)
	}
	if warpField(flat, "id") != "abc-123" {
		t.Errorf("flat id = %q", warpField(flat, "id"))
	}

	nested, err := warpAPIPayload([]byte(warpAPIResponse(true)))
	if err != nil {
		t.Fatalf("nested payload: %v", err)
	}
	if warpField(nested, "id") != "abc-123" {
		t.Errorf("nested id = %q", warpField(nested, "id"))
	}
	if warpField(nested, "config", "client_id") != "hRL0" {
		t.Errorf("nested client id = %q", warpField(nested, "config", "client_id"))
	}
	if warpPeerPublicKey(nested) != warpGenPeerPublicKey {
		t.Errorf("nested peer key = %q", warpPeerPublicKey(nested))
	}
	if warpPeerEndpoint(nested) != "engage.cloudflareclient.com:2408" {
		t.Errorf("nested endpoint = %q", warpPeerEndpoint(nested))
	}
}

func TestOrderAddressesIPv4First(t *testing.T) {
	ips := []net.IPAddr{
		{IP: net.ParseIP("2606:4700::1")},
		{IP: net.ParseIP("8.6.112.0")},
		{IP: net.ParseIP("2606:4700::2")},
		{IP: net.ParseIP("8.47.69.0")},
	}
	ordered := orderAddressesIPv4First(ips)
	if len(ordered) != 4 {
		t.Fatalf("length = %d", len(ordered))
	}
	for i := 0; i < 2; i++ {
		if ordered[i].IP.To4() == nil {
			t.Errorf("address %d must be IPv4, got %s", i, ordered[i].IP)
		}
	}
	for i := 2; i < 4; i++ {
		if ordered[i].IP.To4() != nil {
			t.Errorf("address %d must be IPv6, got %s", i, ordered[i].IP)
		}
	}
}

func TestClassifyWarpError(t *testing.T) {
	cases := []struct {
		err  error
		want warpErrorKind
	}{
		{errors.New(`Post "https://api.cloudflareclient.com/v0a2158/reg": net/http: TLS handshake timeout`), warpErrTimeout},
		{errors.New(`dial tcp: lookup api.cloudflareclient.com on 1.1.1.1:53: no such host`), warpErrDNS},
		{errors.New("read tcp 10.0.0.2:443: connection reset by peer"), warpErrBlocked},
		{errors.New("something else entirely"), warpErrUnknown},
		{nil, warpErrUnknown},
	}
	for _, c := range cases {
		if got := classifyWarpError(c.err); got != c.want {
			t.Errorf("classifyWarpError(%v) = %v, want %v", c.err, got, c.want)
		}
	}
}

func TestIsRetryableNetworkError(t *testing.T) {
	retryable := []string{
		"net/http: TLS handshake timeout",
		"read tcp: connection reset by peer",
		"dial tcp: i/o timeout",
		"unexpected EOF",
	}
	for _, msg := range retryable {
		if !isRetryableNetworkError(errors.New(msg)) {
			t.Errorf("%q must be retryable", msg)
		}
	}
	if isRetryableNetworkError(errors.New("malformed response (no id/token)")) {
		t.Error("a protocol error must not be retried")
	}
	if isRetryableNetworkError(nil) {
		t.Error("nil is not an error")
	}
}
