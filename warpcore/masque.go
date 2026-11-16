package warpcore

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"math/big"
	"net"
	"sync"
	"syscall"
	"time"

	usqueapi "github.com/Diniboy1123/usque/api"
	"github.com/amnezia-vpn/amneziawg-go/tun"
)

// MasqueConfig is the JSON payload passed to MasqueStart.
type MasqueConfig struct {
	PrivateKey string `json:"private_key"` // base64 ECDSA DER (SEC1)
	EndpointV4 string `json:"endpoint_v4"`
	EndpointV6 string `json:"endpoint_v6"`
	PubKey     string `json:"pub_key"`     // server PEM public key
	ID         string `json:"id"`
	Token      string `json:"token"`
	IPv4       string `json:"ipv4"`
	IPv6       string `json:"ipv6"`
	License    string `json:"license"`
}

// MasqueRegistrationResult is returned by MasqueRegister as JSON.
type MasqueRegistrationResult struct {
	Config   MasqueConfig `json:"config"`
	Address4 string       `json:"address4"`
	Address6 string       `json:"address6"`
}

// MasqueRegister registers a WARP device for MASQUE (cf-connect-ip) mode via
// usque: account registration + ECDSA key enrollment.
func MasqueRegister(model string) (string, error) {
	accountData, err := usqueapi.Register(model, "en-US", "", true)
	if err != nil {
		return "", fmt.Errorf("register: %w", err)
	}

	privKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return "", err
	}
	privDER, err := x509.MarshalECPrivateKey(privKey)
	if err != nil {
		return "", err
	}
	pubDER, err := x509.MarshalPKIXPublicKey(&privKey.PublicKey)
	if err != nil {
		return "", err
	}

	updated, apiErr, err := usqueapi.EnrollKey(accountData, pubDER, model)
	if err != nil {
		if apiErr != nil {
			return "", fmt.Errorf("enroll: %v (%s)", err, apiErr.ErrorsAsString("; "))
		}
		return "", fmt.Errorf("enroll: %w", err)
	}

	cfg := MasqueConfig{
		PrivateKey: base64.StdEncoding.EncodeToString(privDER),
		EndpointV4: stripPortSuffix(updated.Config.Peers[0].Endpoint.V4),
		EndpointV6: stripV6Suffix(updated.Config.Peers[0].Endpoint.V6),
		PubKey:     updated.Config.Peers[0].PublicKey,
		ID:         updated.ID,
		Token:      accountData.Token,
		IPv4:       updated.Config.Interface.Addresses.V4,
		IPv6:       updated.Config.Interface.Addresses.V6,
		License:    updated.Account.License,
	}
	out, _ := json.Marshal(MasqueRegistrationResult{
		Config:   cfg,
		Address4: cfg.IPv4,
		Address6: cfg.IPv6,
	})
	return string(out), nil
}

// "162.159.198.1:0" -> "162.159.198.1"
func stripPortSuffix(s string) string {
	if len(s) > 2 && s[len(s)-2:] == ":0" {
		return s[:len(s)-2]
	}
	return s
}

// "[2606:4700:103::]:0" -> "2606:4700:103::"
func stripV6Suffix(s string) string {
	if len(s) > 1 && s[0] == '[' && len(s) > 3 && s[len(s)-3:] == "]:0" {
		return s[1 : len(s)-3]
	}
	return s
}

// --- tunnel ---

type masqueHandle struct {
	cancel  context.CancelFunc
	dev     tun.Device
	adapter *tunToTunnelDevice
}

var (
	masqueHandles   = make(map[int32]*masqueHandle)
	masqueHandlesMu sync.Mutex
	masqueNextID    int32
)

// tunToTunnelDevice adapts the wireguard-go tun.Device to usque TunnelDevice.
type tunToTunnelDevice struct {
	dev   tun.Device
	sizes []int
}

func (a *tunToTunnelDevice) ReadPacket(buf []byte) (int, error) {
	if a.sizes == nil {
		a.sizes = make([]int, 1)
	}
	n, err := a.dev.Read([][]byte{buf}, a.sizes, 0)
	if err != nil {
		return 0, err
	}
	if n == 0 {
		return 0, nil
	}
	return a.sizes[0], nil
}

func (a *tunToTunnelDevice) WritePacket(pkt []byte) error {
	n, err := a.dev.Write([][]byte{pkt}, 0)
	if err != nil {
		return err
	}
	if n == 0 {
		return fmt.Errorf("write rejected")
	}
	return nil
}

// generateCert mirrors usque internal.GenerateCert: a 24h self-signed cert.
func generateCert(priv *ecdsa.PrivateKey) ([][]byte, error) {
	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if err != nil {
		return nil, err
	}
	tpl := x509.Certificate{
		SerialNumber:          serial,
		Subject:               pkix.Name{CommonName: "usque"},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().Add(24 * time.Hour),
		KeyUsage:              x509.KeyUsageDigitalSignature,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageClientAuth},
		BasicConstraintsValid: true,
	}
	der, err := x509.CreateCertificate(rand.Reader, &tpl, &tpl, &priv.PublicKey, priv)
	if err != nil {
		return nil, err
	}
	block, _ := pem.Decode(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}))
	if block == nil {
		return nil, fmt.Errorf("cert encode failed")
	}
	return [][]byte{block.Bytes}, nil
}

// MasqueStart brings up a WARP-over-MASQUE (cf-connect-ip) tunnel on the
// given TUN file descriptor. configJSON is the JSON MasqueConfig produced by
// MasqueRegister.
func MasqueStart(tunFd int32, mtu int32, configJSON string, protector Protector) (int32, error) {
	var cfg MasqueConfig
	if err := json.Unmarshal([]byte(configJSON), &cfg); err != nil {
		return 0, fmt.Errorf("invalid masque config: %w", err)
	}

	privDER, err := base64.StdEncoding.DecodeString(cfg.PrivateKey)
	if err != nil {
		return 0, err
	}
	privKey, err := x509.ParseECPrivateKey(privDER)
	if err != nil {
		return 0, err
	}
	peerPubKey, err := parsePemEcdsaPub(cfg.PubKey)
	if err != nil {
		return 0, err
	}
	cert, err := generateCert(privKey)
	if err != nil {
		return 0, err
	}

	tlsConfig, err := usqueapi.PrepareTlsConfig(privKey, peerPubKey, cert, "consumer-masque.cloudflareclient.com")
	if err != nil {
		return 0, err
	}

	// socket protection hook for the QUIC UDP socket
	usqueapi.DialControl = func(network, address string, c syscall.RawConn) error {
		var result error
		c.Control(func(fd uintptr) {
			if protector != nil && !protector.Protect(int32(fd)) {
				result = fmt.Errorf("socket protect failed")
			}
		})
		return result
	}

	dev, err := tunFromFd(int(tunFd))
	if err != nil {
		return 0, err
	}

	ctx, cancel := context.WithCancel(context.Background())
	endpoint := net.ParseIP(cfg.EndpointV4)
	if endpoint == nil {
		endpoint = net.ParseIP(cfg.EndpointV6)
	}
	if endpoint == nil {
		cancel()
		dev.Close()
		return 0, fmt.Errorf("no masque endpoint")
	}

	adapter := &tunToTunnelDevice{dev: dev}
	go usqueapi.MaintainTunnel(ctx, usqueapi.MaintainTunnelConfig{
		TLSConfig:         tlsConfig,
		KeepalivePeriod:   30 * time.Second,
		InitialPacketSize: 1242,
		Endpoint:          &net.UDPAddr{IP: endpoint, Port: 443},
		Device:            adapter,
		MTU:               int(mtu),
		ReconnectDelay:    1 * time.Second,
		AlwaysReconnect:   true,
	})

	masqueHandlesMu.Lock()
	defer masqueHandlesMu.Unlock()
	masqueNextID++
	masqueHandles[masqueNextID] = &masqueHandle{cancel: cancel, dev: dev, adapter: adapter}
	return masqueNextID, nil
}

// MasqueStop tears down a MASQUE tunnel.
func MasqueStop(handle int32) error {
	masqueHandlesMu.Lock()
	h, ok := masqueHandles[handle]
	if ok {
		delete(masqueHandles, handle)
	}
	masqueHandlesMu.Unlock()
	if !ok {
		return fmt.Errorf("unknown masque handle %d", handle)
	}
	h.cancel()
	return h.dev.Close()
}

func parsePemEcdsaPub(pemStr string) (*ecdsa.PublicKey, error) {
	block, _ := pem.Decode([]byte(pemStr))
	if block == nil {
		return nil, fmt.Errorf("invalid endpoint pubkey PEM")
	}
	pub, err := x509.ParsePKIXPublicKey(block.Bytes)
	if err != nil {
		return nil, err
	}
	ec, ok := pub.(*ecdsa.PublicKey)
	if !ok {
		return nil, fmt.Errorf("endpoint pubkey is not ECDSA")
	}
	return ec, nil
}
