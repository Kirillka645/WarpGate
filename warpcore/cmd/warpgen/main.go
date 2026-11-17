package main

import (
	"flag"
	"fmt"
	"os"

	"warpgate/warpcore"
)

// warpgen prints an exportable Cloudflare WARP configuration.
//
// It is the command line counterpart of the in-app generator and is handy for
// scripting, for router setups and for cross-checking the generated configs.
//
// Usage:
//
//	warpgen -format amneziawg
//	warpgen -format clash -ipv6
//	warpgen -host 162.159.193.1 -port 2408
func main() {
	format := flag.String("format", "amneziawg", "config format: amneziawg | clash")
	host := flag.String("host", "", "endpoint host (default: random from the pool)")
	port := flag.Int("port", 0, "endpoint port (default: random from the pool)")
	ipv6 := flag.Bool("ipv6", false, "include IPv6 (dual stack)")
	flag.Parse()

	reg, err := warpcore.WarpGenRegister()
	if err != nil {
		fmt.Fprintln(os.Stderr, "registration failed:", err)
		os.Exit(1)
	}

	h := *host
	p := int32(*port)
	if h == "" || p == 0 {
		ep := warpcore.WarpGenRandomEndpoint()
		if h == "" {
			h = ep.Host
		}
		if p == 0 {
			p = ep.Port
		}
	}

	switch *format {
	case "amneziawg":
		fmt.Print(warpcore.WarpGenAmneziaWG(reg.PrivateKey, reg.PeerPub, reg.ClientIPv6, h, p, *ipv6))
	case "clash":
		fmt.Print(warpcore.WarpGenClash(reg.PrivateKey, reg.ClientIPv6, h, p, *ipv6))
	default:
		fmt.Fprintf(os.Stderr, "unknown format %q (use amneziawg or clash)\n", *format)
		os.Exit(1)
	}
}
