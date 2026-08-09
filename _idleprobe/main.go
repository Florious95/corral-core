// idle probe: auth one ws client, hold 6s, close; then idle 6s.
// Used only to measure the daemon CPU contrast (connected vs zero-client).
package main

import (
	"context"
	"fmt"
	"os"
	"time"

	"github.com/agentmirror/agentmirror/internal/protocol"
	"github.com/coder/websocket"
)

func main() {
	url := os.Args[1]
	conn, _, err := websocket.Dial(context.Background(), url, nil)
	if err != nil {
		fmt.Println("dial err:", err)
		os.Exit(1)
	}
	auth := []byte(`{"type":"auth","token":"idle-test","version":1}`)
	if err := conn.Write(context.Background(), websocket.MessageText, auth); err != nil {
		fmt.Println("auth write err:", err)
		os.Exit(1)
	}
	_, _, _ = conn.Read(context.Background()) // auth_ack
	fmt.Println("authed; holding 6s")
	time.Sleep(6 * time.Second)
	_ = conn.CloseNow()
	fmt.Println("closed; idle 6s")
	_ = protocol.TypeAuthAck // keep import used
}
