package main

import (
	"encoding/binary"
	"fmt"
	"net"
	"time"
)

func main() {
	l, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(0, 0, 0, 0)})
	if err != nil {
		fmt.Println("listen:", err)
		return
	}
	defer l.Close()
	local := l.LocalAddr().(*net.UDPAddr)
	fmt.Println("local port:", local.Port)

	q := make([]byte, 28)
	binary.BigEndian.PutUint16(q[0:2], 0x1234)
	binary.BigEndian.PutUint16(q[2:4], 0x0100)
	q[4], q[5] = 0, 1
	q[12] = 7
	copy(q[13:20], "example")
	q[20], q[21], q[22], q[23] = 3, 'c', 'o', 'm'
	q[24], q[25] = 0, 1
	q[26], q[27] = 0, 1

	dns := &net.UDPAddr{IP: net.IPv4(1, 1, 1, 1), Port: 53}
	if _, _, err := l.WriteMsgUDP(q, nil, dns); err != nil {
		fmt.Println("send:", err)
		return
	}

	buf := make([]byte, 1500)
	l.SetReadDeadline(time.Now().Add(4 * time.Second))
	n, _, _, from, err := l.ReadMsgUDPAddrPort(buf, nil)
	fmt.Printf("response: %d bytes from %v err=%v first=%x\n", n, from, err, buf[:4])
}
