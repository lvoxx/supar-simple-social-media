package main

import (
	"context"

	"github.com/google/uuid"
	"github.com/redis/go-redis/v9"
)

// channelPrefix namespaces this service's pub/sub channels in the shared Redis so they never collide
// with engagement-service's counter keys. There is one channel per recipient: a replica subscribes
// only to the users currently streaming on it, so a PUBLISH reaches exactly the replicas that have
// that recipient connected — no global fan-out, no wasted delivery.
const channelPrefix = "notifications:"

func channelFor(userID uuid.UUID) string { return channelPrefix + userID.String() }

// pubsubMessage is one cross-replica delivery: the channel it arrived on (which encodes the
// recipient) and the JSON-encoded Notification payload.
type pubsubMessage struct {
	Channel string
	Payload []byte
}

// PubSub is the cross-replica transport behind RedisHub. Publish broadcasts a payload to a channel;
// Subscribe/Unsubscribe adjust which channels THIS replica receives; Messages streams every payload
// received on a subscribed channel. An interface so RedisHub's fan-out is unit-testable with a fake.
type PubSub interface {
	Publish(ctx context.Context, channel string, payload []byte) error
	Subscribe(ctx context.Context, channel string) error
	Unsubscribe(ctx context.Context, channel string) error
	Messages() <-chan pubsubMessage
	Close() error
}

// redisPubSub is the go-redis-backed PubSub. A single *redis.PubSub multiplexes every subscribed
// channel through one connection and one Channel(); a pump goroutine adapts redis messages to the
// transport-neutral pubsubMessage so RedisHub never imports go-redis.
type redisPubSub struct {
	rdb    *redis.Client
	pubsub *redis.PubSub
	out    chan pubsubMessage
}

// newRedisPubSub opens a PubSub with no channels yet; recipients are added/removed as SSE streams come
// and go. The pump runs until Close() shuts the underlying connection, which ends Channel() and so
// closes out.
func newRedisPubSub(rdb *redis.Client) *redisPubSub {
	t := &redisPubSub{
		rdb:    rdb,
		pubsub: rdb.Subscribe(context.Background()),
		out:    make(chan pubsubMessage, 64),
	}
	go t.pump()
	return t
}

func (t *redisPubSub) pump() {
	defer close(t.out)
	for msg := range t.pubsub.Channel() {
		t.out <- pubsubMessage{Channel: msg.Channel, Payload: []byte(msg.Payload)}
	}
}

func (t *redisPubSub) Publish(ctx context.Context, channel string, payload []byte) error {
	return t.rdb.Publish(ctx, channel, payload).Err()
}

func (t *redisPubSub) Subscribe(ctx context.Context, channel string) error {
	return t.pubsub.Subscribe(ctx, channel)
}

func (t *redisPubSub) Unsubscribe(ctx context.Context, channel string) error {
	return t.pubsub.Unsubscribe(ctx, channel)
}

func (t *redisPubSub) Messages() <-chan pubsubMessage { return t.out }

func (t *redisPubSub) Close() error { return t.pubsub.Close() }
