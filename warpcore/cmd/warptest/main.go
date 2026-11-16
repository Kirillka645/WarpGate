package main

import (
	"encoding/json"
	"fmt"
	"os"

	"warpgate/warpcore"
)

// Desktop validation harness: registers a WARP account, then verifies the
// WireGuard handshake to Cloudflare endpoints with/without the reserved
// (client_id) patch, and runs the endpoint scanner.

func main() {
	fmt.Println("== 1. Registering WARP account ==")
	reg, err := warpcore.RegisterWarp("Windows")
	if err != nil {
		fmt.Println("REGISTER FAIL:", err)
		os.Exit(1)
	}
	raw, _ := json.MarshalIndent(reg, "", "  ")
	fmt.Println(string(raw))

	endpoints := []string{
		"162.159.192.5:2408",
		"162.159.193.10:2408",
		"162.159.195.1:2408",
		"188.114.96.1:2408",
		"188.114.97.1:2408",
	}

	fmt.Println("\n== 2. Handshake WITH reserved (expect OK) ==")
	res := probe("with-reserved", endpoints, reg, true)
	fmt.Println(res)

	fmt.Println("\n== 3. Handshake WITHOUT reserved (expect fail) ==")
	res2 := probe("no-reserved", endpoints[:2], reg, false)
	fmt.Println(res2)
}

func probe(label string, endpoints []string, reg *warpcore.WarpRegistration, useReserved bool) string {
	reserved := reg.ClientID
	if !useReserved {
		reserved = ""
	}
	epsJSON, _ := json.Marshal(endpoints)
	out, err := warpcore.ScanEndpoints(string(epsJSON), reg.PrivateKey, reg.PeerPub, reserved, 5000)
	if err != nil {
		return label + " ERROR: " + err.Error()
	}
	return label + ": " + out
}
