package main

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
)

func testLogger() *slog.Logger { return slog.New(slog.NewTextHandler(io.Discard, nil)) }

// fakePubSub is an in-memory PubSub: it records subscribe/unsubscribe/publish calls and lets a test
// inject a received message onto the Messages() stream to drive RedisHub.Run.
type fakePubSub struct {
	mu         sync.Mutex
	subscribed map[string]int
	published  []pubsubMessage
	out        chan pubsubMessage
}

func newFakePubSub() *fakePubSub {
	return &fakePubSub{subscribed: map[string]int{}, out: make(chan pubsubMessage, 16)}
}

func (f *fakePubSub) Publish(_ context.Context, channel string, payload []byte) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.published = append(f.published, pubsubMessage{Channel: channel, Payload: payload})
	return nil
}

func (f *fakePubSub) Subscribe(_ context.Context, channel string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.subscribed[channel]++
	return nil
}

func (f *fakePubSub) Unsubscribe(_ context.Context, channel string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.subscribed[channel]--
	return nil
}

func (f *fakePubSub) Messages() <-chan pubsubMessage { return f.out }
func (f *fakePubSub) Close() error                   { close(f.out); return nil }

func (f *fakePubSub) subCount(channel string) int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.subscribed[channel]
}

func (f *fakePubSub) pubCount() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.published)
}

func TestLocalRegistryFirstAndLast(t *testing.T) {
	reg := newLocalRegistry()
	u := uuid.New()

	_, first1, remove1 := reg.add(u)
	if !first1 {
		t.Fatal("first session for a user must report first=true")
	}
	_, first2, remove2 := reg.add(u)
	if first2 {
		t.Fatal("second session must report first=false")
	}
	if reg.count(u) != 2 {
		t.Fatalf("count = %d, want 2", reg.count(u))
	}
	if last := remove1(); last {
		t.Fatal("removing one of two sessions must report last=false")
	}
	if last := remove2(); !last {
		t.Fatal("removing the final session must report last=true")
	}
	if reg.count(u) != 0 {
		t.Fatalf("count = %d, want 0 after all removed", reg.count(u))
	}
	// remove is idempotent.
	if last := remove2(); last {
		t.Fatal("second remove call must be a no-op (last=false)")
	}
}

func TestLocalRegistryDeliverDropsOnFullBuffer(t *testing.T) {
	reg := newLocalRegistry()
	u := uuid.New()
	ch, _, _ := reg.add(u)

	// Fill the buffer (cap 32) then deliver more — the surplus is dropped, not blocked.
	for i := 0; i < 40; i++ {
		reg.deliver(u, Message{ID: uuid.New()})
	}
	if len(ch) != cap(ch) {
		t.Fatalf("buffer should be full at cap %d, got %d", cap(ch), len(ch))
	}
}

func TestRedisHubSubscribeManagesChannel(t *testing.T) {
	f := newFakePubSub()
	hub := NewRedisHub(f, testLogger())
	u := uuid.New()
	ch := channelFor(u)

	_, unsub1 := hub.Subscribe(u)
	if f.subCount(ch) != 1 {
		t.Fatalf("first subscribe should SUBSCRIBE the channel, count=%d", f.subCount(ch))
	}
	_, unsub2 := hub.Subscribe(u)
	if f.subCount(ch) != 1 {
		t.Fatalf("second local session must not re-SUBSCRIBE, count=%d", f.subCount(ch))
	}
	unsub1()
	if f.subCount(ch) != 1 {
		t.Fatal("channel must stay subscribed while one session remains")
	}
	unsub2()
	if f.subCount(ch) != 0 {
		t.Fatalf("last unsubscribe should UNSUBSCRIBE the channel, count=%d", f.subCount(ch))
	}
}

func TestRedisHubPublishGoesToRecipientChannel(t *testing.T) {
	f := newFakePubSub()
	hub := NewRedisHub(f, testLogger())
	recipient := uuid.New()
	msg := Message{ID: uuid.New(), Body: "hi"}

	hub.Publish(recipient, msg)

	if f.pubCount() != 1 {
		t.Fatalf("expected one publish, got %d", f.pubCount())
	}
	got := f.published[0]
	if got.Channel != channelFor(recipient) {
		t.Fatalf("published to %q, want %q", got.Channel, channelFor(recipient))
	}
	var decoded Message
	if err := json.Unmarshal(got.Payload, &decoded); err != nil {
		t.Fatalf("payload not a Message: %v", err)
	}
	if decoded.ID != msg.ID || decoded.Body != "hi" {
		t.Fatalf("payload mismatch: %+v", decoded)
	}
}

func TestRedisHubRunDeliversToLocalSessions(t *testing.T) {
	f := newFakePubSub()
	hub := NewRedisHub(f, testLogger())
	u := uuid.New()
	ch, unsub := hub.Subscribe(u)
	defer unsub()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go hub.Run(ctx)

	want := Message{ID: uuid.New(), Body: "cross-replica"}
	payload, _ := json.Marshal(want)
	f.out <- pubsubMessage{Channel: channelFor(u), Payload: payload}

	select {
	case got := <-ch:
		if got.ID != want.ID {
			t.Fatalf("delivered %+v, want %+v", got, want)
		}
	case <-time.After(time.Second):
		t.Fatal("timed out waiting for cross-replica delivery")
	}
}

func TestRedisHubRunIgnoresUnknownChannelAndBadPayload(t *testing.T) {
	f := newFakePubSub()
	hub := NewRedisHub(f, testLogger())
	u := uuid.New()
	ch, unsub := hub.Subscribe(u)
	defer unsub()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go hub.Run(ctx)

	// A message on an unrelated channel and a malformed payload on the right channel must both be
	// dropped, never delivered to u's session.
	f.out <- pubsubMessage{Channel: "dm:not-a-uuid", Payload: []byte(`{}`)}
	f.out <- pubsubMessage{Channel: channelFor(u), Payload: []byte("not json")}

	// A valid message afterwards proves the loop kept running and only this one is delivered.
	good := Message{ID: uuid.New()}
	payload, _ := json.Marshal(good)
	f.out <- pubsubMessage{Channel: channelFor(u), Payload: payload}

	select {
	case got := <-ch:
		if got.ID != good.ID {
			t.Fatalf("delivered the wrong (or misrouted) message: %+v", got)
		}
	case <-time.After(time.Second):
		t.Fatal("timed out; the good message should still be delivered")
	}
	if len(ch) != 0 {
		t.Fatalf("expected exactly one delivery, %d extra queued", len(ch))
	}
}
