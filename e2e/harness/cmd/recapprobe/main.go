// recapprobe is a throwaway measurement tool (not shipped, not a test): it
// connects to an isolated agentmirrord daemon, subscribes to one pane, and
// timestamps every inbound binary Delta frame for a fixed window so the
// caller can compute inter-arrival interval percentiles across a real
// full-screen recap under a chosen network RTT. w-dev-repaint needs this
// distribution to calibrate the recap "settle" silence threshold — it must
// exceed the largest normal inter-delta gap or the threshold fires mid-recap
// under Tailscale-scale latency.
//
// Usage: recapprobe -url ws://10.0.2.2:PORT/ws -token TOK -seconds 8
package main

import (
	"context"
	"flag"
	"fmt"
	"os"
	"sort"
	"time"

	"github.com/agentmirror/agentmirror/internal/protocol"
	"github.com/coder/websocket"
)

func main() {
	url := flag.String("url", "", "daemon ws URL")
	token := flag.String("token", "", "pairing token (env AGENTMIRROR_PROBE_TOKEN preferred over this flag)")
	seconds := flag.Int("seconds", 8, "capture window in seconds after subscribe")
	flag.Parse()
	if *token == "" {
		*token = os.Getenv("AGENTMIRROR_PROBE_TOKEN")
	}
	if *url == "" || *token == "" {
		fmt.Fprintln(os.Stderr, "need -url and a token (flag or AGENTMIRROR_PROBE_TOKEN)")
		os.Exit(2)
	}

	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(*seconds+15)*time.Second)
	defer cancel()

	conn, _, err := websocket.Dial(ctx, *url, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, "dial:", err)
		os.Exit(1)
	}
	defer conn.CloseNow()

	send := func(p protocol.Typed) error {
		data, err := protocol.MarshalFrame(p)
		if err != nil {
			return err
		}
		return conn.Write(ctx, websocket.MessageText, data)
	}

	if err := send(protocol.Auth{Token: *token}); err != nil {
		fmt.Fprintln(os.Stderr, "send auth:", err)
		os.Exit(1)
	}

	var ref string
	var subscribed bool
	var arrivals []time.Time
	var hasED2 []bool // per-arrival: does this delta chunk's raw bytes contain ESC[2J or ESC[3J (clear screen)?
	ed2 := []byte{0x1b, '[', '2', 'J'}
	ed3 := []byte{0x1b, '[', '3', 'J'}
	deadline := time.Now().Add(time.Duration(*seconds) * time.Second)
	authed := false

	for {
		if subscribed && time.Now().After(deadline) {
			break
		}
		rctx, rcancel := context.WithTimeout(ctx, 2*time.Second)
		typ, data, err := conn.Read(rctx)
		rcancel()
		if err != nil {
			if subscribed {
				break // read timeout after window elapsed is expected; treat as end
			}
			continue
		}
		if typ == websocket.MessageBinary {
			bin, err := protocol.DecodeBinary(data)
			if err != nil {
				continue
			}
			if subscribed && bin.Ref == ref && bin.Kind == protocol.KindDelta {
				arrivals = append(arrivals, time.Now())
				hasED2 = append(hasED2, bytesContains(bin.Data, ed2) || bytesContains(bin.Data, ed3))
			}
			continue
		}
		f, err := protocol.UnmarshalFrame(data)
		if err != nil {
			continue
		}
		switch v := f.(type) {
		case protocol.AuthAck:
			if !v.OK {
				fmt.Fprintln(os.Stderr, "auth rejected:", v.Reason)
				os.Exit(1)
			}
			authed = true
			if err := send(protocol.List{ReqID: 1}); err != nil {
				fmt.Fprintln(os.Stderr, "send list:", err)
				os.Exit(1)
			}
		case protocol.Listing:
			if !authed || subscribed {
				continue
			}
			for _, ws := range v.Workspaces {
				for _, s := range ws.Sessions {
					ref = s.Ref
				}
			}
			if ref == "" {
				fmt.Fprintln(os.Stderr, "no session found in listing")
				os.Exit(1)
			}
			if err := send(protocol.Subscribe{Ref: ref, Rows: 40, Cols: 100}); err != nil {
				fmt.Fprintln(os.Stderr, "send subscribe:", err)
				os.Exit(1)
			}
			subscribed = true
			deadline = time.Now().Add(time.Duration(*seconds) * time.Second)
		}
	}

	if len(arrivals) < 2 {
		fmt.Printf("chunks=%d insufficient for interval distribution\n", len(arrivals))
		return
	}
	intervals := make([]float64, 0, len(arrivals)-1)
	for i := 1; i < len(arrivals); i++ {
		intervals = append(intervals, float64(arrivals[i].Sub(arrivals[i-1]))/float64(time.Millisecond))
	}
	unsorted := append([]float64(nil), intervals...)
	sort.Float64s(intervals)
	pct := func(p float64) float64 {
		idx := int(p * float64(len(intervals)-1))
		return intervals[idx]
	}
	fmt.Printf("chunks=%d intervals_ms: p50=%.1f p90=%.1f p99=%.1f max=%.1f\n",
		len(arrivals), pct(0.50), pct(0.90), pct(0.99), intervals[len(intervals)-1])
	fmt.Print("raw_intervals_ms_in_arrival_order: ")
	for i, v := range unsorted {
		if i > 0 {
			fmt.Print(",")
		}
		fmt.Printf("%.1f", v)
	}
	fmt.Println()

	ed2Count := 0
	var ed2Indices []int
	for i, v := range hasED2 {
		if v {
			ed2Count++
			ed2Indices = append(ed2Indices, i)
		}
	}
	fmt.Printf("ED2_or_ED3_clear_screen_occurrences: %d\n", ed2Count)
	if ed2Count == 0 {
		return
	}
	fmt.Printf("ED2_at_chunk_indices: %v\n", ed2Indices)
	// Post-ED2 repaint-internal intervals: from the ED2-carrying chunk's
	// arrival to the next several chunks, until a gap exceeding
	// postED2SettleGapMs marks the repaint as finished (heuristic boundary —
	// caller should sanity check against the printed indices/timestamps).
	const postED2SettleGapMs = 300.0
	var postED2 []float64
	for _, idx := range ed2Indices {
		for j := idx; j < len(intervals) && j < idx+40; j++ { // arrivals[idx+1]-arrivals[idx] is intervals[idx]
			gap := unsorted[j]
			if j > idx && gap > postED2SettleGapMs {
				break
			}
			postED2 = append(postED2, gap)
		}
	}
	if len(postED2) < 2 {
		fmt.Printf("post_ED2_repaint_intervals: insufficient samples (n=%d)\n", len(postED2))
		return
	}
	sort.Float64s(postED2)
	pct2 := func(p float64) float64 {
		idx := int(p * float64(len(postED2)-1))
		return postED2[idx]
	}
	fmt.Printf("post_ED2_repaint_intervals_ms: n=%d p50=%.1f p90=%.1f p99=%.1f max=%.1f\n",
		len(postED2), pct2(0.50), pct2(0.90), pct2(0.99), postED2[len(postED2)-1])
}

func bytesContains(haystack, needle []byte) bool {
	if len(needle) == 0 || len(haystack) < len(needle) {
		return false
	}
	for i := 0; i+len(needle) <= len(haystack); i++ {
		match := true
		for j := range needle {
			if haystack[i+j] != needle[j] {
				match = false
				break
			}
		}
		if match {
			return true
		}
	}
	return false
}
