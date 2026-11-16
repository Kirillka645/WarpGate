package main

import (
	"fmt"
	"os"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/conn"
	"github.com/amnezia-vpn/amneziawg-go/device"
	"warpgate/warpcore"
)

// Debug single-endpoint probe with verbose logging.
func main() {
	reg, err := warpcore.RegisterWarp("Windows")
	if err != nil {
		fmt.Println("REGISTER FAIL:", err)
		os.Exit(1)
	}
	fmt.Printf("client_id=%s peer=%s\n", reg.ClientID, reg.PeerPub)

	probeTun := newDebugTun()
	dev := device.NewDevice(probeTun, conn.NewStdNetBind(),
		device.NewLogger(device.LogLevelVerbose, "[dbg] "))
	defer dev.Close()

	device.EnablePacketDebugLog()
	if cid, ok := decodeClientID(reg.ClientID); ok {
		dev.SetWarpReserved(cid)
	}

	settings := buildProbeSettings(reg.PrivateKey, reg.PeerPub, "162.159.192.5:2408")
	if err := dev.IpcSet(settings); err != nil {
		fmt.Println("IpcSet FAIL:", err)
		return
	}
	if err := dev.Up(); err != nil {
		fmt.Println("Up FAIL:", err)
		return
	}

	deadline := time.Now().Add(8 * time.Second)
	for time.Now().Before(deadline) {
		time.Sleep(1 * time.Second)
		ipc, err := dev.IpcGet()
		if err != nil {
			fmt.Println("IpcGet err:", err)
			continue
		}
		fmt.Println("---- IpcGet ----")
		fmt.Println(ipc)
	}
}
