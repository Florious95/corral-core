package bridge

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"os"
	"testing"
	"time"
)

func TestOverflowPublishesCauseBeforeDataEOF(t *testing.T) {
	reader, writer, err := os.Pipe()
	if err != nil {
		t.Fatalf("os.Pipe: %v", err)
	}
	defer writer.Close()

	slow := make(chan []byte, 1)
	loss := make(chan error, 1)
	entered := make(chan struct{})
	release := make(chan struct{})
	s := &sharedPipe{
		gen:        1,
		subs:       map[uint64]chan []byte{1: slow},
		losses:     map[uint64]chan error{1: loss},
		lossClosed: map[uint64]bool{1: false},
		failed:     map[uint64]bool{1: false},
		refs:       1,
		beforeDataClose: func(uint64) {
			close(entered)
			<-release
		},
	}
	done := make(chan struct{})
	go s.fanout(reader, done, 1)
	payload := bytes.Repeat([]byte("cause-order\n"), streamBufferBytes/len("cause-order\n")*2+1)
	written := make(chan struct{})
	go func() {
		_, _ = writer.Write(payload)
		close(written)
	}()
	select {
	case <-written:
	case <-time.After(5 * time.Second):
		t.Fatal("controlled reader did not receive payload")
	}
	select {
	case <-entered:
	case <-time.After(2 * time.Second):
		t.Fatal("overflow close barrier was not reached")
	}
	select {
	case got, ok := <-loss:
		if !ok || !errors.Is(got, ErrSubscriberOverflow) {
			t.Fatalf("loss=(%v,%v), want ErrSubscriberOverflow before data EOF", got, ok)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("data-close barrier was reached before loss publication")
	}
	close(release)
	_ = writer.Close()
	select {
	case _, ok := <-slow:
		if ok {
			t.Fatal("slow data channel delivered stale bytes after overflow")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("slow data channel did not close")
	}
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("fanout did not finish")
	}
}

func TestOldGenerationDetachCannotTouchNewGeneration(t *testing.T) {
	reader1, writer1, err := os.Pipe()
	if err != nil {
		t.Fatalf("os.Pipe gen1: %v", err)
	}
	oldData := make(chan []byte, 1)
	oldLoss := make(chan error, 1)
	s := &sharedPipe{
		gen:        1,
		fifo:       "generation-1",
		reader:     reader1,
		subs:       map[uint64]chan []byte{0: oldData},
		losses:     map[uint64]chan error{0: oldLoss},
		lossClosed: map[uint64]bool{0: false},
		failed:     map[uint64]bool{0: false},
		nextID:     1,
		refs:       1,
	}
	done1 := make(chan struct{})
	go s.fanout(reader1, done1, 1)
	_ = writer1.Close()
	select {
	case <-done1:
	case <-time.After(2 * time.Second):
		t.Fatal("generation 1 fanout did not finish")
	}
	if len(s.losses) != 0 || len(s.lossClosed) != 0 || len(s.failed) != 0 {
		t.Fatalf("generation 1 metadata retained: losses=%d closed=%d failed=%d", len(s.losses), len(s.lossClosed), len(s.failed))
	}

	reader2, writer2, err := os.Pipe()
	if err != nil {
		t.Fatalf("os.Pipe gen2: %v", err)
	}
	s.mu.Lock()
	s.gen = 2
	s.fifo = "generation-2"
	s.reader = reader2
	s.dead = false
	s.fanoutDone = nil
	s.mu.Unlock()
	newData, _, newDetach, err := s.add(context.Background())
	if err != nil {
		t.Fatalf("generation 2 add: %v", err)
	}
	// This is the delayed detach closure from generation 1. It must not
	// decrement the new generation's ref count.
	s.drop(0)
	s.mu.Lock()
	refs, gen, members := s.refs, s.gen, len(s.subs)
	s.mu.Unlock()
	if refs != 1 || gen != 2 || members != 1 {
		t.Fatalf("old detach touched new generation: refs=%d gen=%d members=%d", refs, gen, members)
	}

	done2 := make(chan struct{})
	go s.fanout(reader2, done2, 2)
	want := []byte("generation-2-alive")
	if _, err := writer2.Write(want); err != nil {
		t.Fatalf("write generation 2: %v", err)
	}
	select {
	case got := <-newData:
		if !bytes.Equal(got, want) {
			t.Fatalf("generation 2 data=%q, want %q", got, want)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("generation 2 subscriber stopped after old detach")
	}
	_ = writer2.Close()
	select {
	case <-done2:
	case <-time.After(2 * time.Second):
		t.Fatal("generation 2 fanout did not finish")
	}
	newDetach()
}

func TestSubscribeAttachRetriesAreBounded(t *testing.T) {
	attempts := 0
	var s *sharedPipe
	s = &sharedPipe{
		dead: true,
		attachFn: func(context.Context) error {
			attempts++
			s.mu.Lock()
			s.gen++
			s.fifo = fmt.Sprintf("failed-generation-%d", attempts)
			s.dead = true
			s.mu.Unlock()
			return nil
		},
	}
	_, _, _, err := s.add(context.Background())
	if !errors.Is(err, ErrAttachUnstable) {
		t.Fatalf("add error=%v, want ErrAttachUnstable", err)
	}
	if attempts != maxSubscribeAttachAttempts {
		t.Fatalf("attach attempts=%d, want %d", attempts, maxSubscribeAttachAttempts)
	}
	s.mu.Lock()
	fifo, refs, members := s.fifo, s.refs, len(s.subs)
	s.mu.Unlock()
	if fifo != "" || refs != 0 || members != 0 {
		t.Fatalf("bounded retry retained state: fifo=%q refs=%d members=%d", fifo, refs, members)
	}
}
