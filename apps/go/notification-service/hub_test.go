package main

import (
	"testing"
	"time"

	"github.com/google/uuid"
)

func TestHubDeliversToSubscriber(t *testing.T) {
	hub := NewHub()
	user := uuid.New()
	ch, unsubscribe := hub.Subscribe(user)
	defer unsubscribe()

	n := Notification{ID: uuid.New(), RecipientID: user, Kind: KindLike}
	hub.Publish(user, n)

	select {
	case got := <-ch:
		if got.ID != n.ID {
			t.Errorf("got notification %v, want %v", got.ID, n.ID)
		}
	case <-time.After(time.Second):
		t.Fatal("subscriber did not receive the published notification")
	}
}

func TestHubDoesNotDeliverToOtherUsers(t *testing.T) {
	hub := NewHub()
	me := uuid.New()
	other := uuid.New()
	ch, unsubscribe := hub.Subscribe(me)
	defer unsubscribe()

	hub.Publish(other, Notification{ID: uuid.New(), RecipientID: other})

	select {
	case <-ch:
		t.Fatal("received a notification addressed to another user")
	case <-time.After(50 * time.Millisecond):
		// expected: nothing delivered
	}
}

func TestHubUnsubscribeRemovesStream(t *testing.T) {
	hub := NewHub()
	user := uuid.New()
	_, unsubscribe := hub.Subscribe(user)
	if hub.SubscriberCount(user) != 1 {
		t.Fatalf("want 1 subscriber, got %d", hub.SubscriberCount(user))
	}
	unsubscribe()
	if hub.SubscriberCount(user) != 0 {
		t.Errorf("unsubscribe should remove the stream, count=%d", hub.SubscriberCount(user))
	}
	unsubscribe() // idempotent: must not panic
}

func TestHubPublishDropsWhenBufferFull(t *testing.T) {
	hub := NewHub()
	user := uuid.New()
	_, unsubscribe := hub.Subscribe(user)
	defer unsubscribe()

	// Buffer is 16; pushing far more must not block (slow/absent reader).
	done := make(chan struct{})
	go func() {
		for i := 0; i < 1000; i++ {
			hub.Publish(user, Notification{ID: uuid.New(), RecipientID: user})
		}
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("Publish blocked on a full subscriber buffer")
	}
}
