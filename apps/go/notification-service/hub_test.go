package main

import (
	"context"
	"encoding/json"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
)

// fakeTransport is an in-memory PubSub: it records publishes and subscribe/unsubscribe calls, and
// lets a test inject() messages as if they arrived from another replica. Safe for concurrent use so
// it can run under hub.Run in a goroutine.
type fakeTransport struct {
	mu           sync.Mutex
	published    []pubsubMessage
	subscribed   []string
	unsubscribed []string
	out          chan pubsubMessage
}

func newFakeTransport() *fakeTransport {
	return &fakeTransport{out: make(chan pubsubMessage, 16)}
}

func (f *fakeTransport) Publish(_ context.Context, channel string, payload []byte) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.published = append(f.published, pubsubMessage{Channel: channel, Payload: payload})
	return nil
}

func (f *fakeTransport) Subscribe(_ context.Context, channel string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.subscribed = append(f.subscribed, channel)
	return nil
}

func (f *fakeTransport) Unsubscribe(_ context.Context, channel string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.unsubscribed = append(f.unsubscribed, channel)
	return nil
}

func (f *fakeTransport) Messages() <-chan pubsubMessage { return f.out }
func (f *fakeTransport) Close() error                   { close(f.out); return nil }

// inject simulates a cross-replica notification arriving on channel.
func (f *fakeTransport) inject(m pubsubMessage) { f.out <- m }

func (f *fakeTransport) counts() (pub, sub, unsub int) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.published), len(f.subscribed), len(f.unsubscribed)
}

// --- localRegistry ---------------------------------------------------------

func TestLocalRegistryFirstAndLast(t *testing.T) {
	reg := newLocalRegistry()
	user := uuid.New()

	_, first, remove1 := reg.add(user)
	if !first {
		t.Fatal("the only stream for a user must report first=true")
	}
	_, first2, remove2 := reg.add(user)
	if first2 {
		t.Fatal("a second stream for the same user must report first=false")
	}
	if reg.count(user) != 2 {
		t.Fatalf("want 2 local streams, got %d", reg.count(user))
	}

	if last := remove1(); last {
		t.Error("removing one of two streams must report last=false")
	}
	if last := remove2(); !last {
		t.Error("removing the final stream must report last=true")
	}
	if reg.count(user) != 0 {
		t.Errorf("registry should be empty, count=%d", reg.count(user))
	}
	remove2() // idempotent: must not panic or double-close
}

func TestLocalRegistryDeliverDropsWhenBufferFull(t *testing.T) {
	reg := newLocalRegistry()
	user := uuid.New()
	_, _, remove := reg.add(user)
	defer remove()

	done := make(chan struct{})
	go func() {
		for i := 0; i < 1000; i++ { // buffer is 16; an absent reader must not block deliver
			reg.deliver(user, Notification{ID: uuid.New(), RecipientID: user})
		}
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("deliver blocked on a full subscriber buffer")
	}
}

// --- RedisHub --------------------------------------------------------------

func TestRedisHubSubscribeManagesRedisChannel(t *testing.T) {
	tr := newFakeTransport()
	hub := NewRedisHub(tr, discardLogger())
	user := uuid.New()

	_, unsub1 := hub.Subscribe(user)
	_, unsub2 := hub.Subscribe(user)
	if _, sub, _ := tr.counts(); sub != 1 {
		t.Fatalf("only the first stream for a user should SUBSCRIBE the redis channel, got %d", sub)
	}

	unsub1()
	if _, _, unsub := tr.counts(); unsub != 0 {
		t.Errorf("a non-final unsubscribe must not UNSUBSCRIBE the redis channel, got %d", unsub)
	}
	unsub2()
	_, _, unsub := tr.counts()
	if unsub != 1 {
		t.Errorf("the final unsubscribe should UNSUBSCRIBE the redis channel once, got %d", unsub)
	}
}

func TestRedisHubPublishBroadcastsToRecipientChannel(t *testing.T) {
	tr := newFakeTransport()
	hub := NewRedisHub(tr, discardLogger())
	recipient := uuid.New()

	n := Notification{ID: uuid.New(), RecipientID: recipient, ActorID: uuid.New(), Kind: KindLike}
	hub.Publish(recipient, n)

	tr.mu.Lock()
	defer tr.mu.Unlock()
	if len(tr.published) != 1 {
		t.Fatalf("want 1 publish, got %d", len(tr.published))
	}
	msg := tr.published[0]
	if msg.Channel != channelFor(recipient) {
		t.Errorf("published to %q, want %q", msg.Channel, channelFor(recipient))
	}
	var got Notification
	if err := json.Unmarshal(msg.Payload, &got); err != nil {
		t.Fatalf("payload is not a valid Notification: %v", err)
	}
	if got.ID != n.ID || got.Kind != KindLike {
		t.Errorf("payload = %+v, want id=%v kind=%v", got, n.ID, KindLike)
	}
}

func TestRedisHubRunDeliversInjectedMessageToLocalStream(t *testing.T) {
	tr := newFakeTransport()
	hub := NewRedisHub(tr, discardLogger())
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go hub.Run(ctx)

	user := uuid.New()
	ch, unsub := hub.Subscribe(user)
	defer unsub()

	n := Notification{ID: uuid.New(), ActorID: uuid.New(), Kind: KindReply}
	payload, _ := json.Marshal(n)
	tr.inject(pubsubMessage{Channel: channelFor(user), Payload: payload})

	select {
	case got := <-ch:
		if got.ID != n.ID {
			t.Errorf("got notification %v, want %v", got.ID, n.ID)
		}
	case <-time.After(time.Second):
		t.Fatal("local stream did not receive the injected cross-replica notification")
	}
}

func TestRedisHubRunIgnoresOtherUsersAndBadChannels(t *testing.T) {
	tr := newFakeTransport()
	hub := NewRedisHub(tr, discardLogger())
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go hub.Run(ctx)

	me, other := uuid.New(), uuid.New()
	ch, unsub := hub.Subscribe(me)
	defer unsub()

	// A message for another user, and one on a non-notification channel, must not reach me.
	payload, _ := json.Marshal(Notification{ID: uuid.New()})
	tr.inject(pubsubMessage{Channel: channelFor(other), Payload: payload})
	tr.inject(pubsubMessage{Channel: "engagement:dirty", Payload: []byte("garbage")})

	select {
	case <-ch:
		t.Fatal("received a notification not addressed to me")
	case <-time.After(50 * time.Millisecond):
		// expected: nothing delivered
	}
}
