module warpgate/warpcore

go 1.26.1

replace github.com/amnezia-vpn/amneziawg-go => ./awg-fork

require (
	github.com/Diniboy1123/usque v1.5.0
	github.com/amnezia-vpn/amneziawg-go v0.0.0-00010101000000-000000000000
)

require (
	github.com/Diniboy1123/connect-ip-go v0.0.0-20251011145655-7be32d5976d9 // indirect
	github.com/dunglas/httpsfv v1.1.0 // indirect
	github.com/google/btree v1.1.3 // indirect
	github.com/quic-go/qpack v0.6.0 // indirect
	github.com/quic-go/quic-go v0.59.0 // indirect
	github.com/songgao/water v0.0.0-20200317203138-2b4b6d7c09d8 // indirect
	github.com/tevino/abool v1.2.0 // indirect
	github.com/yosida95/uritemplate/v3 v3.0.2 // indirect
	go.uber.org/atomic v1.11.0 // indirect
	golang.org/x/crypto v0.58.0 // indirect
	golang.org/x/exp v0.0.0-20260312153236-7ab1446f8b90 // indirect
	golang.org/x/mobile v0.0.0-20261011034528-197770444867 // indirect
	golang.org/x/mod v0.42.0 // indirect
	golang.org/x/net v0.61.0 // indirect
	golang.org/x/sync v0.24.0 // indirect
	golang.org/x/sys v0.49.0 // indirect
	golang.org/x/text v0.43.0 // indirect
	golang.org/x/time v0.15.0 // indirect
	golang.org/x/tools v0.52.0 // indirect
	golang.zx2c4.com/wintun v0.0.0-20230126152724-0fa3db229ce2 // indirect
	golang.zx2c4.com/wireguard v0.0.0-20250521234502-f333402bd9cb // indirect
	gvisor.dev/gvisor v0.0.0-20260408064518-65a410b0d584 // indirect
)

tool golang.org/x/mobile/cmd/gobind

replace github.com/Diniboy1123/usque => ./usque-fork
